import Combine
import Shared
import SwiftUI
import UIKit

/// Hosts a Compose root (a `UIViewController` exported from Kotlin `iosMain`) inside SwiftUI.
/// `extraBottomInset` is added to the safe area so Compose content clears a custom floating tab bar
/// while still scrolling underneath it.
struct ComposeHost: UIViewControllerRepresentable {
    let make: () -> UIViewController
    var extraBottomInset: CGFloat = 0

    func makeUIViewController(context: Context) -> UIViewController {
        let controller = make()
        controller.view.backgroundColor = .clear
        controller.additionalSafeAreaInsets.bottom = extraBottomInset
        return controller
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
        if uiViewController.additionalSafeAreaInsets.bottom != extraBottomInset {
            uiViewController.additionalSafeAreaInsets.bottom = extraBottomInset
        }
    }
}

/// Mirrors the Kotlin `SessionStore` state for SwiftUI. Lives for the whole app process.
@MainActor
final class SessionModel: ObservableObject {
    /// `signedInStaff`: a @bau.edu staff user (Kotlin `User.isStaff`) → `StaffTabShell`; `signedIn` → student `TabShell`.
    enum Phase: Equatable { case restoring, signedOut, signedIn, signedInStaff }

    @Published private(set) var phase: Phase = .restoring
    private var observation: SessionObservation?

    init() {
        // Kotlin delivers on Dispatchers.Main (the main thread); hop explicitly so Swift concurrency agrees.
        observation = SessionBridgeKt.observeSession { [weak self] snapshot in
            let next: Phase = snapshot.isRestoring ? .restoring
                : !snapshot.isSignedIn ? .signedOut
                : snapshot.isStaff ? .signedInStaff : .signedIn
            Task { @MainActor in
                guard let self, next != self.phase else { return }
                self.phase = next
            }
        }
        // Restore the stored session once (no-op on subsequent calls): real tokens → /v1/me, otherwise Welcome.
        // DEBUG `-searchQuery <text>` pre-fills the search; see `DebugLaunch`.
        if let query = DebugLaunch.searchQuery { SearchBridgeKt.setSearchQuery(text: query) }
        if DebugLaunch.startScrolledToBottom { TabViewControllersKt.setDebugStartScrolledToBottom(enabled: true) }
        if let mode = DebugLaunch.staffTour { StaffBridgeKt.debugStaffTour(mode: mode) }
        SessionBridgeKt.startSession()
    }
}

/// Root of the iOS app: splash while restoring, Compose `AuthRoot` when signed out, the student tab shell or (for
/// @bau.edu staff) the staff tab shell when signed in.
struct ContentView: View {
    @StateObject private var session = SessionModel()
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
            case .signedInStaff:
                StaffTabShell()
                    .transition(.opacity)
            }
        }
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.25), value: session.phase)
    }
}
