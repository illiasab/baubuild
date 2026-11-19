import Shared
import SwiftUI
import UIKit

enum StaffAppTab: Hashable, CaseIterable {
    case overview, inquiries, profile
    /// Not a real tab: the yellow "+" that presents New Inquiry full-screen.
    case newInquiry

    var title: String {
        switch self {
        case .overview: "Overview"
        case .inquiries: "Inquiries"
        case .profile: "Profile"
        case .newInquiry: "New Inquiry"
        }
    }

    var systemImage: String {
        switch self {
        case .overview: "chart.bar"
        case .inquiries: "tray.full"
        case .profile: "person"
        case .newInquiry: "plus"
        }
    }

    var makeController: () -> UIViewController {
        switch self {
        case .overview: TabViewControllersKt.StaffOverviewViewController
        case .inquiries: TabViewControllersKt.StaffInquiriesViewController
        case .profile: TabViewControllersKt.StaffProfileViewController
        case .newInquiry: { UIViewController() }
        }
    }

    init?(kotlinKey: String) {
        switch kotlinKey {
        case "overview": self = .overview
        case "inquiries": self = .inquiries
        case "profile": self = .profile
        default: return nil
        }
    }

    static let primary: [StaffAppTab] = [.overview, .inquiries, .profile]
}

/// Mirrors Kotlin `StaffTour.step` (0 = no tour) and `StaffNav.events` for the staff shell.
@MainActor
final class StaffShellModel: ObservableObject {
    @Published var selection: StaffAppTab
    @Published var presentingNewInquiry = false
    @Published private(set) var tourStep: Int = Int(StaffBridgeKt.currentStaffTourStep())
    private var observations: [StaffObservation] = []

    init(initialTab: StaffAppTab) {
        selection = initialTab
        observations.append(StaffBridgeKt.observeStaffTour { [weak self] step in
            let value = step.intValue
            Task { @MainActor in self?.tourStep = value }
        })
        observations.append(StaffBridgeKt.observeStaffNav(
            onOpenNewInquiry: { [weak self] in Task { @MainActor in self?.openNewInquiry() } },
            onSelectTab: { [weak self] key in
                Task { @MainActor in
                    guard let self, let tab = StaffAppTab(kotlinKey: key) else { return }
                    self.presentingNewInquiry = false
                    self.selection = tab
                }
            }
        ))
        StaffBridgeKt.startStaffTourIfFirstTime()
    }

    deinit { observations.forEach { $0.close() } }

    func openNewInquiry() {
        StaffBridgeKt.staffTourPlusTapped()
        presentingNewInquiry = true
    }

    func newInquiryDismissed() {
        StaffBridgeKt.staffTourNewInquiryDismissed()
    }

    func skipTour() { StaffBridgeKt.skipStaffTour() }

    var tourMessage: String { StaffBridgeKt.staffTourMessage(step: Int32(tourStep)) }

    /// Tour targets owned by the shell: step 1 → "+", step 5 → the Inquiries tab (until it's selected; then the
    /// Compose list spotlights the row itself).
    var tourTarget: StaffAppTab? {
        switch tourStep {
        case 1: .newInquiry
        case 5 where selection != .inquiries: .inquiries
        default: nil
        }
    }
}

/// Staff (@bau.edu) shell: Overview / Inquiries / Profile + a separate solid yellow "+" on the right (where students
/// have Search). "+" presents the Compose New Inquiry flow full-screen; the previous tab stays selected underneath.
/// - iOS 26+: `GlassStaffShell` — the real system Liquid Glass `TabView` bar, configured exactly like the students'
///   (`GlassTabShell`), with the "+" in the system's separate trailing slot.
/// - iOS 16–25 (and `-forceLegacyTabBar`): `StaffBarShell`, the same layout on `.ultraThinMaterial`.
/// Also renders the first-run tour banner ("N of 6 · message · Skip") above everything, and the spotlight for the
/// shell's own targets (Kotlin `feature/staff/tour/StaffTour`).
struct StaffTabShell: View {
    @StateObject private var model = StaffShellModel(initialTab: DebugLaunch.staffInitialTab ?? .overview)

    var body: some View {
        shell
        .overlay(alignment: .top) { StaffTourBanner(model: model) }
        .fullScreenCover(isPresented: $model.presentingNewInquiry, onDismiss: model.newInquiryDismissed) {
            NewInquiryCover(model: model)
        }
        .onAppear {
            if DebugLaunch.openNewInquiry { model.openNewInquiry() }
        }
    }

    @ViewBuilder private var shell: some View {
        if #available(iOS 26.0, *), !DebugLaunch.forceLegacyTabBar {
            GlassStaffShell(model: model)
        } else {
            StaffBarShell(model: model)
        }
    }
}

/// Hosts the Compose New Inquiry flow; its `onClose` dismisses the cover.
private struct NewInquiryCover: View {
    @ObservedObject var model: StaffShellModel

    var body: some View {
        ComposeHost(make: { [weak model] in
            TabViewControllersKt.NewInquiryViewController(onClose: {
                Task { @MainActor in model?.presentingNewInquiry = false }
            })
        })
        .ignoresSafeArea()
        .background(BauPalette.paper.ignoresSafeArea())
        .overlay(alignment: .top) { StaffTourBanner(model: model) }
    }
}

// MARK: - Tour

/// Design: dark banner under the status bar — yellow "N of 6" pill, the step's message, a "Skip" pill.
private struct StaffTourBanner: View {
    @ObservedObject var model: StaffShellModel

    var body: some View {
        if model.tourStep > 0 {
            HStack(spacing: 10) {
                Text("\(model.tourStep) of \(StaffBridgeKt.staffTourStepCount())")
                    .font(.system(size: 12, weight: .bold))
                    .foregroundStyle(BauPalette.ink)
                    .padding(.horizontal, 9)
                    .padding(.vertical, 4)
                    .background(BauPalette.yellow, in: Capsule())
                Text(model.tourMessage)
                    .font(.system(size: 14, weight: .medium))
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .fixedSize(horizontal: false, vertical: true)
                Button { model.skipTour() } label: {
                    Text("Skip")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 14)
                        .frame(minHeight: 44)
                        .background(Color.white.opacity(0.16), in: Capsule())
                        .contentShape(Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityHint("Ends the tour")
            }
            .padding(.leading, 14)
            .padding(.trailing, 8)
            .padding(.vertical, 8)
            .background(BauPalette.ink.opacity(0.94), in: RoundedRectangle(cornerRadius: 24, style: .continuous))
            .shadow(color: .black.opacity(0.3), radius: 15, y: 12)
            .padding(.horizontal, 16)
            .padding(.top, 4)
            .accessibilityElement(children: .contain)
            .accessibilityLabel("Tour, step \(model.tourStep) of \(StaffBridgeKt.staffTourStepCount())")
            .transition(.opacity)
        }
    }
}

/// Dims everything except `rect` (global coordinates) and rings it in yellow. Never takes touches.
private struct TourSpotlight: View {
    let rect: CGRect
    let cornerRadius: CGFloat

    var body: some View {
        GeometryReader { proxy in
            let origin = proxy.frame(in: .global).origin
            let hole = rect.offsetBy(dx: -origin.x, dy: -origin.y).insetBy(dx: -6, dy: -6)
            let radius = cornerRadius + 6
            ZStack {
                Path { path in
                    path.addRect(CGRect(origin: .zero, size: proxy.size))
                    path.addRoundedRect(in: hole, cornerSize: CGSize(width: radius, height: radius), style: .continuous)
                }
                .fill(BauPalette.ink.opacity(0.45), style: FillStyle(eoFill: true))
                RoundedRectangle(cornerRadius: radius + 1.5, style: .continuous)
                    .stroke(BauPalette.yellow, lineWidth: 3)
                    .frame(width: hole.width + 3, height: hole.height + 3)
                    .position(x: hole.midX, y: hole.midY)
            }
        }
        .ignoresSafeArea()
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

/// Global frames of shell elements the tour points at.
private struct TourTargetFramesKey: PreferenceKey {
    static var defaultValue: [StaffAppTab: CGRect] = [:]
    static func reduce(value: inout [StaffAppTab: CGRect], nextValue: () -> [StaffAppTab: CGRect]) {
        value.merge(nextValue()) { $1 }
    }
}

private extension View {
    func tourTargetFrame(_ tab: StaffAppTab) -> some View {
        background(GeometryReader { proxy in
            Color.clear.preference(key: TourTargetFramesKey.self, value: [tab: proxy.frame(in: .global)])
        })
    }
}

// MARK: - iOS 26+: native Liquid Glass bar

/// The system `TabView` bar, set up like the students' `GlassTabShell`: three tabs plus a `Tab(role: .search)` with
/// `.searchable` + `.tabViewSearchActivation(.searchTabSelection)`. That combination is what makes iOS put the search
/// tab into its own trailing slot next to a narrower tab capsule (with three tabs and no `.searchable` it stays inside
/// the bar as a 4th item). The slot itself only renders as a glass button, so the "+" is ours: the system slot is made
/// invisible (`NativeTabBarReader`) and a solid yellow circle is drawn at exactly its frame. If the system slot is
/// still selected (e.g. a future UIKit change exposes it), selection snaps back and New Inquiry opens — the search
/// field is never presented.
@available(iOS 26.0, *)
private struct GlassStaffShell: View {
    @ObservedObject var model: StaffShellModel
    @State private var unusedQuery = ""
    @State private var searchPresented = false
    @State private var frames = NativeTabBarFrames()

    var body: some View {
        TabView(selection: $model.selection) {
            ForEach(StaffAppTab.primary, id: \.self) { tab in
                Tab(tab.title, systemImage: tab.systemImage, value: tab) {
                    ComposeHost(make: tab.makeController).ignoresSafeArea()
                }
            }
            Tab(value: StaffAppTab.newInquiry, role: .search) {
                NavigationStack { Color.clear.toolbar(.hidden, for: .navigationBar) }
            } label: {
                Label(StaffAppTab.newInquiry.title, systemImage: StaffAppTab.newInquiry.systemImage)
            }
        }
        .searchable(text: $unusedQuery, isPresented: $searchPresented)
        .tabViewSearchActivation(.searchTabSelection)
        .tint(BauPalette.blueDeep)
        .onChange(of: model.selection) { old, new in
            guard new == .newInquiry else { return }
            model.selection = old == .newInquiry ? .overview : old
            searchPresented = false
            model.openNewInquiry()
        }
        .onChange(of: searchPresented) { _, presented in if presented { searchPresented = false } }
        .background(NativeTabBarReader { frames = $0 }.allowsHitTesting(false).accessibilityHidden(true))
        .overlay { plusButton }
        .overlay {
            if let target = model.tourTarget, let rect = frames.rect(for: target) {
                TourSpotlight(rect: rect, cornerRadius: rect.height / 2)
            }
        }
    }

    /// Solid #FFD60A circle with an ink "+" at the system slot's frame (window coordinates).
    @ViewBuilder private var plusButton: some View {
        if let slot = frames.plus {
            GeometryReader { proxy in
                let origin = proxy.frame(in: .global).origin
                Button { model.openNewInquiry() } label: {
                    StaffPlusCircle(diameter: slot.width)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(StaffAppTab.newInquiry.title)
                .position(x: slot.midX - origin.x, y: slot.midY - origin.y)
            }
            .ignoresSafeArea()
        }
    }
}

/// Window-coordinate frames of the native tab bar's parts that the shell draws over or points at.
private struct NativeTabBarFrames: Equatable {
    var plus: CGRect?
    var tabs: [CGRect] = []

    func rect(for tab: StaffAppTab) -> CGRect? {
        if tab == .newInquiry { return plus }
        guard let index = StaffAppTab.primary.firstIndex(of: tab), index < tabs.count else { return nil }
        return tabs[index]
    }
}

/// Finds the window's `UITabBar`, hides its separate trailing (search-role) slot so only our yellow "+" shows there,
/// and reports the slot's and tab buttons' frames. Re-checks periodically because the bar re-lays out (rotation,
/// size changes) and may restore the slot's alpha. Relies on UIKit's view hierarchy (iOS 26/27: the slot is a
/// `_UITabBarAuxiliaryView`, the tabs `_UITabButton`s in the capsule platter); if that changes, nothing is reported
/// and the system slot stays visible and working (selecting it still opens New Inquiry).
private struct NativeTabBarReader: UIViewRepresentable {
    let onChange: (NativeTabBarFrames) -> Void

    func makeUIView(context: Context) -> ReaderView {
        let view = ReaderView()
        view.onChange = onChange
        return view
    }

    func updateUIView(_ uiView: ReaderView, context: Context) { uiView.onChange = onChange }

    final class ReaderView: UIView {
        var onChange: ((NativeTabBarFrames) -> Void)?
        private var timer: Timer?
        private var last = NativeTabBarFrames()

        override func didMoveToWindow() {
            super.didMoveToWindow()
            timer?.invalidate()
            timer = nil
            guard window != nil else { return }
            timer = Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self] _ in
                MainActor.assumeIsolated { self?.scan() }
            }
            DispatchQueue.main.async { [weak self] in self?.scan() }
        }

        override func layoutSubviews() {
            super.layoutSubviews()
            scan()
        }

        private func scan() {
            guard let window, let bar = Self.first(UITabBar.self, in: window) else { return }
            var frames = NativeTabBarFrames()
            let visible = !bar.isHidden && bar.alpha > 0.01
            if visible, let slot = Self.first(named: "AuxiliaryView", in: bar) {
                if slot.alpha != 0 { slot.alpha = 0 }
                frames.plus = slot.convert(slot.bounds, to: window)
            }
            if visible {
                let platter = bar.subviews.first { Self.name($0).contains("ItemPlatterView") }
                let buttons = (platter?.subviews ?? []).filter { Self.name($0).contains("TabButton") }
                var seen: [CGRect] = []
                for button in buttons {
                    let rect = button.convert(button.bounds, to: window)
                    if !seen.contains(where: { abs($0.minX - rect.minX) < 1 }) { seen.append(rect) }
                }
                frames.tabs = seen.sorted { $0.minX < $1.minX }
            }
            if frames != last {
                last = frames
                onChange?(frames)
            }
        }

        private static func name(_ view: UIView) -> String { String(describing: type(of: view)) }

        private static func first<T: UIView>(_ type: T.Type, in view: UIView) -> T? {
            if let match = view as? T { return match }
            for sub in view.subviews { if let found = first(type, in: sub) { return found } }
            return nil
        }

        private static func first(named fragment: String, in view: UIView) -> UIView? {
            if name(view).contains(fragment) { return view }
            for sub in view.subviews { if let found = first(named: fragment, in: sub) { return found } }
            return nil
        }

        deinit { timer?.invalidate() }
    }
}

/// The yellow "+": solid #FFD60A circle, ink glyph, subtle shadow. No glass or material.
private struct StaffPlusCircle: View {
    let diameter: CGFloat

    var body: some View {
        Image(systemName: "plus")
            .font(.system(size: 22, weight: .semibold))
            .foregroundStyle(BauPalette.ink)
            .frame(width: diameter, height: diameter)
            .background(BauPalette.yellow, in: Circle())
            .shadow(color: BauPalette.ink.opacity(0.16), radius: 12, y: 6)
            .contentShape(Circle())
    }
}

// MARK: - iOS 16–25: ultra-thin material bar


/// Width-limited `.ultraThinMaterial` capsule with the three tabs plus a separate solid yellow "+" (same footprint as
/// the student `MaterialTabShell`'s tabs + search button: 62pt bar, 10pt gap, 16pt gutters, max 440pt). Content runs under the bar; the bar's height reaches Compose as extra bottom safe area
/// (`ComposeHost.extraBottomInset`), so screens keep their last row 24pt above it (BottomBarSpacing.kt).
/// Tabs are created on first open and kept alive (scroll / navigation state).
private struct StaffBarShell: View {
    @ObservedObject var model: StaffShellModel
    @State private var opened: Set<StaffAppTab> = []
    @State private var targetFrames: [StaffAppTab: CGRect] = [:]

    private let barHeight: CGFloat = 62
    private let barBottomPadding: CGFloat = 8

    var body: some View {
        ZStack(alignment: .bottom) {
            BauPalette.paper.ignoresSafeArea()

            ForEach(StaffAppTab.primary, id: \.self) { tab in
                if opened.contains(tab) || model.selection == tab {
                    ComposeHost(make: tab.makeController, extraBottomInset: barHeight + barBottomPadding)
                        .ignoresSafeArea()
                        .opacity(model.selection == tab ? 1 : 0)
                        .allowsHitTesting(model.selection == tab)
                        .accessibilityHidden(model.selection != tab)
                        .onAppear { opened.insert(tab) }
                }
            }

            HStack(spacing: 10) {
                HStack(spacing: 0) {
                    ForEach(StaffAppTab.primary, id: \.self) { tab in
                        tabButton(tab)
                    }
                }
                .padding(.horizontal, 6)
                .frame(maxWidth: .infinity)
                .frame(height: barHeight)
                .clipShape(Capsule())
                .glassCapsule()

                Button { model.openNewInquiry() } label: {
                    StaffPlusCircle(diameter: barHeight)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(StaffAppTab.newInquiry.title)
                .tourTargetFrame(.newInquiry)
            }
            .frame(maxWidth: 440)
            .padding(.horizontal, 16)
            .padding(.bottom, barBottomPadding)

            if let target = model.tourTarget, let frame = targetFrames[target] {
                TourSpotlight(rect: frame, cornerRadius: frame.height / 2)
            }
        }
        .ignoresSafeArea(.keyboard, edges: .bottom)
        .onPreferenceChange(TourTargetFramesKey.self) { targetFrames = $0 }
    }

    private func tabButton(_ tab: StaffAppTab) -> some View {
        let isSelected = model.selection == tab
        return Button { model.selection = tab } label: {
            VStack(spacing: 3) {
                Image(systemName: tab.systemImage)
                    .font(.system(size: 18, weight: isSelected ? .semibold : .regular))
                Text(tab.title)
                    .font(.system(size: 10, weight: isSelected ? .semibold : .medium))
                    .lineLimit(1)
            }
            .foregroundStyle(isSelected ? BauPalette.blueDeep : BauPalette.ink.opacity(0.75))
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background {
                if isSelected {
                    Capsule().fill(BauPalette.ink.opacity(0.07)).padding(.vertical, 6)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(tab.title)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
        .tourTargetFrame(tab)
    }
}
