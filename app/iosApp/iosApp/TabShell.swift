import Shared
import SwiftUI

enum AppTab: Hashable {
    case projects, myWork, review, profile, search
}

/// Native Liquid Glass tab bar (iOS 26). Four primary tabs plus the system search tab, which iOS renders as a
/// separate glass button to the right of the (width-limited) tab bar. Each tab hosts its Compose root.
///
/// Compose content extends under the glass bar (`ignoresSafeArea`); the bar height arrives in Compose as part of
/// the safe-area insets, so roots that pad with `WindowInsets.safeDrawing` keep their last row visible.
/// `.tabBarMinimizeBehavior(.onScrollDown)` is intentionally not used: it tracks UIScrollViews, and Compose
/// scrolling is not one, so the bar would never minimize.
struct TabShell: View {
    @State private var selection: AppTab = .projects

    var body: some View {
        TabView(selection: $selection) {
            Tab("Projects", systemImage: "square.grid.2x2", value: AppTab.projects) {
                ComposeHost(make: TabViewControllersKt.ProjectsViewController).ignoresSafeArea()
            }
            Tab("My Work", systemImage: "chevron.left.forwardslash.chevron.right", value: AppTab.myWork) {
                ComposeHost(make: TabViewControllersKt.MyWorkViewController).ignoresSafeArea()
            }
            Tab("Review", systemImage: "checkmark.circle", value: AppTab.review) {
                ComposeHost(make: TabViewControllersKt.ReviewViewController).ignoresSafeArea()
            }
            Tab("Profile", systemImage: "person", value: AppTab.profile) {
                ComposeHost(make: TabViewControllersKt.ProfileViewController).ignoresSafeArea()
            }
            Tab("Search", systemImage: "magnifyingglass", value: AppTab.search, role: Self.searchRole) {
                ComposeHost(make: TabViewControllersKt.SearchViewController).ignoresSafeArea()
            }
        }
        .tint(BauPalette.blueDeep)
    }

    /// iOS 27 only gives the search tab its separate glass button when it drives a native `.searchable` field
    /// (`automaticallyActivatesSearch`). Search UI here is Compose (`SearchRoot`), so on 27+ we ask for the
    /// prominent treatment explicitly; on 26 the `.search` role renders as the separate trailing button.
    private static var searchRole: TabRole {
        if #available(iOS 27.0, *) { return .prominent }
        return .search
    }
}
