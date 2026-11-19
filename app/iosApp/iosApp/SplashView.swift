import SwiftUI

enum BauPalette {
    static let paper = Color(red: 0xF3 / 255, green: 0xF4 / 255, blue: 0xEF / 255)
    static let ink = Color(red: 0x0D / 255, green: 0x12 / 255, blue: 0x20 / 255)
    static let yellow = Color(red: 0xFF / 255, green: 0xD6 / 255, blue: 0x0A / 255)
    static let blueDeep = Color(red: 0x2B / 255, green: 0x50 / 255, blue: 0xE0 / 255)
}

/// Brand mark: yellow rounded tile with an ink "B".
struct BrandMark: View {
    var size: CGFloat = 72

    var body: some View {
        RoundedRectangle(cornerRadius: size * 0.28, style: .continuous)
            .fill(BauPalette.yellow)
            .frame(width: size, height: size)
            .overlay(
                Text("B")
                    .font(.system(size: size * 0.52, weight: .heavy, design: .rounded))
                    .foregroundStyle(BauPalette.ink)
            )
            .shadow(color: BauPalette.ink.opacity(0.12), radius: 12, y: 6)
    }
}

/// Shown while the Kotlin SessionStore restores the stored token, to avoid flashing the sign-in screen.
struct SplashView: View {
    var body: some View {
        ZStack {
            BauPalette.paper.ignoresSafeArea()
            VStack(spacing: 16) {
                BrandMark()
                Text("BAU BUILD")
                    .font(.system(size: 22, weight: .bold))
                    .tracking(1.5)
                    .foregroundStyle(BauPalette.ink)
            }
            .accessibilityElement(children: .combine)
            .accessibilityLabel("BAU BUILD, loading")
        }
    }
}
