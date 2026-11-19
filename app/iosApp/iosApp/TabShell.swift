import Shared
import SwiftUI

enum AppTab: Hashable, CaseIterable {
    case projects, myWork, review, profile, search

    var title: String {
        switch self {
        case .projects: "Projects"
        case .myWork: "My Work"
        case .review: "Review"
        case .profile: "Profile"
        case .search: "Search"
        }
    }

    var systemImage: String {
        switch self {
        case .projects: "square.grid.2x2"
        case .myWork: "chevron.left.forwardslash.chevron.right"
        case .review: "checkmark.circle"
        case .profile: "person"
        case .search: "magnifyingglass"
        }
    }

    var makeController: () -> UIViewController {
        switch self {
        case .projects: TabViewControllersKt.ProjectsViewController
        case .myWork: TabViewControllersKt.MyWorkViewController
        case .review: TabViewControllersKt.ReviewViewController
        case .profile: TabViewControllersKt.ProfileViewController
        case .search: TabViewControllersKt.SearchViewController
        }
    }

    static let primary: [AppTab] = [.projects, .myWork, .review, .profile]
}

/// Mirrors Kotlin `SearchQuery.text` for the tab-bar search field (both shells), two-way.
/// Loop-free: Swift writes only when its text differs from Kotlin's, and Kotlin callbacks re-read the current
/// Kotlin value (never a stale emission) and assign only when it differs from the field.
@MainActor
final class SearchQueryModel: ObservableObject {
    @Published var text: String = SearchBridgeKt.currentSearchQuery() {
        didSet {
            if text != SearchBridgeKt.currentSearchQuery() { SearchBridgeKt.setSearchQuery(text: text) }
        }
    }
    private var observation: SearchQueryObservation?

    init() {
        observation = SearchBridgeKt.observeSearchQuery { [weak self] _ in
            Task { @MainActor in self?.pullFromKotlin() }
        }
    }

    deinit { observation?.close() }

    private func pullFromKotlin() {
        let current = SearchBridgeKt.currentSearchQuery()
        if current != text { text = current }
    }
}

/// Picks the tab bar for the running OS. Both behave like Apple Music: selecting Search collapses the four tabs
/// into one round button on the left (the previous tab's icon) and the search field takes over the rest of the bar;
/// that button leaves search and brings the tabs back with the previous tab selected. Leaving search drops the
/// keyboard but keeps the query.
/// - iOS 26+: native Liquid Glass `TabView` with `Tab(role: .search)` + `.searchable` (the system does the morph).
/// - iOS 16–25: custom `.ultraThinMaterial` bar that recreates the morph.
///
/// Debug launch arguments (DEBUG builds only):
/// - `-forceLegacyTabBar` previews the iOS 16–25 bar on a newer simulator.
/// - `-initialTab search` (or projects/myWork/review/profile) starts on that tab.
/// - `-searchQuery <text>` pre-fills Kotlin `SearchQuery` (checks the Kotlin → field direction).
/// - `-startScrolledToBottom` opens Profile / My Work / Projects scrolled to the end (checks the gap above the bar).
/// - Staff (see StaffTabShell): `-initialTab overview|inquiries|profile`, `-openNewInquiry`, `-staffTour show|off`.
/// - `-apiBaseUrl <url>` (read in Kotlin, core/network) points the app at a local backend, e.g. http://127.0.0.1:8000/.
/// All of these apply after a real sign-in; there is no way to skip authentication.
struct TabShell: View {
    @StateObject private var search = SearchQueryModel()

    var body: some View {
        if #available(iOS 26.0, *), !DebugLaunch.forceLegacyTabBar {
            GlassTabShell(search: search, initialTab: DebugLaunch.initialTab ?? .projects)
        } else {
            MaterialTabShell(search: search, initialTab: DebugLaunch.initialTab ?? .projects)
        }
    }
}

/// DEBUG-only launch hooks; all are inert in Release builds.
enum DebugLaunch {
    #if DEBUG
    static let forceLegacyTabBar = ProcessInfo.processInfo.arguments.contains("-forceLegacyTabBar")
    static let initialTab: AppTab? = {
        switch UserDefaults.standard.string(forKey: "initialTab") {
        case "projects": .projects
        case "myWork": .myWork
        case "review": .review
        case "profile": .profile
        case "search": .search
        default: nil
        }
    }()
    static let searchQuery: String? = UserDefaults.standard.string(forKey: "searchQuery")
    static let startScrolledToBottom = ProcessInfo.processInfo.arguments.contains("-startScrolledToBottom")
    /// Staff shell: `-initialTab overview|inquiries|profile`.
    static let staffInitialTab: StaffAppTab? = {
        switch UserDefaults.standard.string(forKey: "initialTab") {
        case "overview": .overview
        case "inquiries": .inquiries
        case "profile": .profile
        default: nil
        }
    }()
    /// Staff shell: present New Inquiry right away (checks the full-screen cover).
    static let openNewInquiry = ProcessInfo.processInfo.arguments.contains("-openNewInquiry")
    /// `-staffTour show` starts the first-run tour even if it was seen; `-staffTour off` hides it this run.
    static let staffTour: String? = UserDefaults.standard.string(forKey: "staffTour")
    #else
    static let forceLegacyTabBar = false
    static let initialTab: AppTab? = nil
    static let searchQuery: String? = nil
    static let startScrolledToBottom = false
    static let staffInitialTab: StaffAppTab? = nil
    static let openNewInquiry = false
    static let staffTour: String? = nil
    #endif
}

// MARK: - iOS 26+: Liquid Glass

/// Each tab hosts its Compose root and extends under the glass bar; the bar height reaches Compose as
/// safe-area insets, so roots that pad with `WindowInsets.safeDrawing` keep their last row visible.
/// `.tabBarMinimizeBehavior(.onScrollDown)` is not used: it tracks UIScrollViews, which Compose doesn't use.
///
/// The search tab drives a native `.searchable` field, so the system gives the Apple Music morph (on 26 and 27)
/// and `SearchRoot` (Compose) only shows results for the bound `SearchQuery`.
@available(iOS 26.0, *)
private struct GlassTabShell: View {
    @ObservedObject var search: SearchQueryModel
    @State private var selection: AppTab

    init(search: SearchQueryModel, initialTab: AppTab) {
        self.search = search
        _selection = State(initialValue: initialTab)
    }

    var body: some View {
        TabView(selection: $selection) {
            ForEach(AppTab.primary, id: \.self) { tab in
                Tab(tab.title, systemImage: tab.systemImage, value: tab) {
                    ComposeHost(make: tab.makeController).ignoresSafeArea()
                }
            }
            Tab(value: AppTab.search, role: .search) {
                // The NavigationStack is what hosts the `.searchable` field; without it the search tab splits off but
                // never turns into a field. Its (empty) bar is hidden so it can't take touches over Compose.
                NavigationStack {
                    ComposeHost(make: AppTab.search.makeController)
                        .ignoresSafeArea()
                        .toolbar(.hidden, for: .navigationBar)
                }
            }
        }
        .searchable(text: $search.text, prompt: "Search projects")
        .tabViewSearchActivation(.searchTabSelection)
        .tint(BauPalette.blueDeep)
    }
}

// MARK: - iOS 16–25: ultra-thin material fallback

/// Normal: a width-limited capsule with the four tabs plus a separate round search button.
/// Searching: the tabs capsule shrinks into one round button on the left (previous tab's icon) and the search
/// button grows into a capsule holding the text field, focused automatically. Spring animation (none with Reduce
/// Motion). While searching the bar rides on the keyboard (SwiftUI keyboard safe area); otherwise it stays behind it.
/// Tabs are created when first opened and kept alive afterwards, so each keeps its scroll and navigation state.
private struct MaterialTabShell: View {
    @ObservedObject var search: SearchQueryModel
    @State private var selection: AppTab
    /// The tab to return to when leaving search.
    @State private var lastPrimary: AppTab
    @State private var opened: Set<AppTab>
    @FocusState private var fieldFocused: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private let barHeight: CGFloat = 62
    private let barBottomPadding: CGFloat = 8

    init(search: SearchQueryModel, initialTab: AppTab) {
        self.search = search
        _selection = State(initialValue: initialTab)
        _lastPrimary = State(initialValue: initialTab == .search ? .projects : initialTab)
        _opened = State(initialValue: [initialTab])
    }

    private var isSearching: Bool { selection == .search }

    var body: some View {
        ZStack(alignment: .bottom) {
            BauPalette.paper.ignoresSafeArea()

            ForEach(AppTab.allCases, id: \.self) { tab in
                if opened.contains(tab) {
                    // Compose's bottom safe area = device inset + this = exactly the bar's top edge (the bar sits
                    // `barBottomPadding` above the device inset). Compose adds its own gap (BottomBarSpacing.kt).
                    ComposeHost(make: tab.makeController, extraBottomInset: barHeight + barBottomPadding)
                        .ignoresSafeArea()
                        .opacity(selection == tab ? 1 : 0)
                        .allowsHitTesting(selection == tab)
                        .accessibilityHidden(selection != tab)
                }
            }

            HStack(spacing: 10) {
                tabsPill
                searchPill
            }
            .frame(maxWidth: 440)
            .padding(.horizontal, 16)
            .padding(.bottom, barBottomPadding)
        }
        // The bar stays behind the keyboard while a Compose form is being edited (like the native TabView); it only
        // rides on the keyboard while searching, because the search field lives in it.
        .ignoresSafeArea(isSearching ? [] : .keyboard, edges: .bottom)
        .onAppear { if isSearching { fieldFocused = true } }
    }

    /// Four tabs normally; one round button (previous tab) while searching.
    private var tabsPill: some View {
        ZStack {
            HStack(spacing: 0) {
                ForEach(AppTab.primary, id: \.self) { tab in
                    tabButton(tab)
                }
            }
            .padding(.horizontal, 6)
            .opacity(isSearching ? 0 : 1)
            .allowsHitTesting(!isSearching)
            .accessibilityHidden(isSearching)

            if isSearching {
                Button { select(lastPrimary) } label: {
                    Image(systemName: lastPrimary.systemImage)
                        .font(.system(size: 20, weight: .semibold))
                        .foregroundStyle(BauPalette.ink)
                        .frame(width: barHeight, height: barHeight)
                        .contentShape(Circle())
                }
                .buttonStyle(.plain)
                .transition(.opacity)
                .accessibilityLabel(lastPrimary.title)
                .accessibilityHint("Leaves search")
            }
        }
        .frame(width: isSearching ? barHeight : nil, height: barHeight)
        .frame(maxWidth: isSearching ? barHeight : .infinity)
        .clipShape(Capsule())
        .glassCapsule()
    }

    /// Round search button normally; the search field capsule while searching.
    private var searchPill: some View {
        ZStack {
            if isSearching {
                HStack(spacing: 8) {
                    Image(systemName: "magnifyingglass")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(BauPalette.ink.opacity(0.6))
                        .accessibilityHidden(true)
                    TextField("Search projects", text: $search.text)
                        .focused($fieldFocused)
                        .submitLabel(.search)
                        .onSubmit { fieldFocused = false }
                        .autocorrectionDisabled()
                        .foregroundStyle(BauPalette.ink)
                        .tint(BauPalette.blueDeep)
                    if !search.text.isEmpty {
                        Button { search.text = "" } label: {
                            Image(systemName: "xmark.circle.fill")
                                .font(.system(size: 17))
                                .foregroundStyle(BauPalette.ink.opacity(0.35))
                                .frame(width: 44, height: 44)
                                .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("Clear search")
                    }
                }
                .padding(.leading, 18)
                .padding(.trailing, search.text.isEmpty ? 18 : 6)
                .transition(.opacity)
            } else {
                Button { select(.search) } label: {
                    Image(systemName: AppTab.search.systemImage)
                        .font(.system(size: 20, weight: .semibold))
                        .foregroundStyle(BauPalette.ink)
                        .frame(width: barHeight, height: barHeight)
                        .contentShape(Circle())
                }
                .buttonStyle(.plain)
                .transition(.opacity)
                .accessibilityLabel(AppTab.search.title)
            }
        }
        .frame(width: isSearching ? nil : barHeight, height: barHeight)
        .frame(maxWidth: isSearching ? .infinity : barHeight)
        .clipShape(Capsule())
        .glassCapsule()
    }

    private func tabButton(_ tab: AppTab) -> some View {
        let isSelected = selection == tab
        return Button { select(tab) } label: {
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
    }

    private func select(_ tab: AppTab) {
        guard tab != selection else { return }
        if tab != .search {
            lastPrimary = tab
            fieldFocused = false
        }
        opened.insert(tab)
        withAnimation(reduceMotion ? nil : .spring(response: 0.42, dampingFraction: 0.84)) {
            selection = tab
        }
        if tab == .search {
            // Focus once the field is in the hierarchy.
            DispatchQueue.main.async { fieldFocused = true }
        }
    }
}

extension View {
    /// Shared material capsule look of the fallback bar (a circle when width == height).
    func glassCapsule() -> some View {
        background(.ultraThinMaterial, in: Capsule())
            .overlay(Capsule().strokeBorder(Color.white.opacity(0.55), lineWidth: 0.75))
            .shadow(color: BauPalette.ink.opacity(0.12), radius: 16, y: 6)
    }
}
