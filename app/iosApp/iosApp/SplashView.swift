import SwiftUI

enum BauPalette {
    static let paper = Color(red: 0xF3 / 255, green: 0xF4 / 255, blue: 0xEF / 255)
    static let ink = Color(red: 0x0D / 255, green: 0x12 / 255, blue: 0x20 / 255)
    static let yellow = Color(red: 0xFF / 255, green: 0xD6 / 255, blue: 0x0A / 255)
    static let blueDeep = Color(red: 0x2B / 255, green: 0x50 / 255, blue: 0xE0 / 255)
}

/// Shown while the Kotlin SessionStore restores the stored token, to avoid flashing the sign-in screen.
/// Matches the system launch screen (Info.plist `UILaunchScreen`: `SplashLogo` on `SplashBackground`,
/// logo at its natural 341pt size, centred) so launch → splash has no visible jump.
struct SplashView: View {
    var body: some View {
        ZStack {
            Color("SplashBackground").ignoresSafeArea()
            Image("SplashLogo")
                .resizable()
                .interpolation(.high)
                .frame(width: 341, height: 341)
                .accessibilityLabel("BAU BUILD, loading")
        }
        .ignoresSafeArea()
    }
}
