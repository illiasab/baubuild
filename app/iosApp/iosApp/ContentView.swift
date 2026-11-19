import Shared
import SwiftUI
import UIKit

/// Hosts a Compose root (a `UIViewController` exported from Kotlin `iosMain`) inside SwiftUI.
struct ComposeHost: UIViewControllerRepresentable {
    let make: () -> UIViewController

    func makeUIViewController(context: Context) -> UIViewController {
        let controller = make()
        controller.view.backgroundColor = .clear
        return controller
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

/// Mirrors the Kotlin `SessionStore` state for SwiftUI. Lives for the whole app process.
@MainActor
@Observable
final class SessionModel {
    enum Phase: Equatable { case restoring, signedOut, signedIn }

    private(set) var phase: Phase = .restoring
    @ObservationIgnored private var observation: SessionObservation?

    init() {
        // Kotlin delivers on Dispatchers.Main, i.e. the main thread.
        observation = SessionBridgeKt.observeSession { [weak self] snapshot in
            MainActor.assumeIsolated {
                guard let self else { return }
                let next: Phase = snapshot.isRestoring ? .restoring : (snapshot.isSignedIn ? .signedIn : .signedOut)
                if next != self.phase { self.phase = next }
            }
        }
        // Restore the stored session once (no-op on subsequent calls).
        SessionBridgeKt.startSession()
    }
}

/// Root of the iOS app: splash while restoring, Compose `AuthRoot` when signed out, native tab shell when signed in.
struct ContentView: View {
    @State private var session = SessionModel()
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ZStack {
            switch session.phase {
            case .restoring:
                SplashView()
                    .transition(.opacity)
            case .signedOut:
                ComposeHost(make: TabViewControllersKt.AuthViewController)
                    .ignoresSafeArea()
                    .transition(.opacity)
            case .signedIn:
                TabShell()
                    .transition(.opacity)
            }
        }
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.25), value: session.phase)
    }
}
