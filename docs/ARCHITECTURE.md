# BAU BUILD app — architecture

Kotlin Multiplatform + Compose Multiplatform. Targets: **iOS, Android, macOS / Windows / Linux (Compose Desktop, JVM)**.
The web app is built separately and is not part of this repo.

Design reference: https://baybuild.vercel.app/ (tab "iOS app").

## Layers (all in `app/shared/src/commonMain/kotlin/com/vesper/baubuild/`)

| Package | What lives there |
|---|---|
| `core/model` | Serializable domain models + `AccountPolicy` (email-domain rules). |
| `core/session` | `SessionStore` (StateFlow of `SessionState`), `TokenStore` for the backend's access + refresh tokens (Keychain / Keystore). |
| `core/auth` | Microsoft (Entra ID) sign-in: PKCE authorization code via the platform browser (`MicrosoftOAuthSignIn`), redeemed by the backend. **App registration lives in `core/auth/MicrosoftAuthConfig.kt`** (`BAU_ENTRA_CLIENT_ID`, `BAU_ENTRA_TENANT`); while they are placeholders, sign-in shows "Microsoft sign-in isn't set up for this build yet." without opening a browser. |
| `core/network` | `ApiConfig` (base `https://api.baubuild.vesperconnect.org/`, debug override below), `AppJson`, Ktor client with retry/backoff, Bearer access token + automatic refresh, `ApiException` (`apiCall {}`) from the backend's `{"error":{"code","message"}}`. |
| `core/LoadState*.kt` | `LoadState<T>` and `MutableStateFlow<LoadState<T>>.load {}` helper. |
| `data/repository` | Repository interfaces (the contract with the backend; real implementations in `data/http`). |
| `data/http` | Real backend repositories (`auth`, `config`, `student`, `staff`). Contract: `docs/openapi.json` + `docs/index.html`; backend needs: `docs/BACKEND_REQUIREMENTS.md`. |
| `data/github` | In-app coding against GitHub with the student's token (Keychain/Keystore), also registered with the backend (`docs/BACKEND_GITHUB_TOKEN.md`) while `/v1/config` `features.github` is on. |
| `di/AppGraph` | Manual DI container. `AppGraph.current`. Real backend only: every repository is the `Http*` one. |
| `designsystem` | `BauColors`, spacing/radius tokens, `BauTheme`, shared components (`BauPrimaryButton`, `BauCard`, `BauChip`, `BauPill`, `ScreenTitle`, `SectionHeader`, `LoadStateContent`). |
| `feature/<name>` | One package per feature: screens, ViewModels, feature-local components. |
| `shell` | Signed-in/out switching and the tab bar for Android/desktop. |

## Rules

1. **MVVM with StateFlow.** Each screen has a `ViewModel` (`androidx.lifecycle.ViewModel`) exposing
   `val state: StateFlow<XxxUiState>` (an immutable data class) and plain functions for user intents.
   Composables collect with `collectAsStateWithLifecycle()` and never call repositories directly.
   Construct ViewModels with `viewModel { XxxViewModel(AppGraph.current.someRepository) }`.
2. **Zero downtime on the client.** Never replace visible data with an error screen. Use `LoadState` +
   the `load {}` helper; render with `LoadStateContent`. Writes (join, submit) show inline progress and
   inline errors, and keep the user's input on failure. Network layer retries idempotent calls with backoff.
   Backend JSON may gain fields at any time (`ignoreUnknownKeys`).
3. **Shared UI first.** All screens are Compose in `commonMain`. Platform code only where the platform
   demands it (Microsoft sign-in, secure token storage, iOS Liquid Glass tab bar).
4. **Three shells, one set of features.**
   - **iOS** — native SwiftUI. iOS 26+: Liquid Glass `TabView`; iOS 16–25: `.ultraThinMaterial` bar with the same
     layout. Each tab hosts its Compose root via a `UIViewController` exported from `iosMain`.
   - **Android** — Compose floating glass tab bar in `shell/`.
   - **Desktop (macOS / Windows / Linux)** — the **Web portal** design (`shell/portal/`, sidebar + wide pages in
     `feature/*/portal/`). `usesPortalLayout` (shell/LayoutMode.kt) selects it. Desktop never uses mobile layouts.
   - **Search** (mobile) is Apple Music–style: selecting it collapses the tabs into one round button on the left and
     the search field takes over the bar. The field lives in the shell (native `.searchable` on iOS 26+), bound to
     `feature/search/SearchQuery.text`; `SearchRoot` only renders results.
   - Data that changes elsewhere (e.g. joining a project) is broadcast via `core/DataChanges`; ViewModels listening
     to it refresh themselves.
5. **Tab roots are the contract** between features and shells:
   `AuthRoot`, `ProjectsTabRoot`, `MyWorkTabRoot`, `ReviewTabRoot`, `ProfileTabRoot`, `SearchRoot` —
   each `@Composable fun X(modifier: Modifier = Modifier)`. In-tab navigation (list → detail) is owned
   by the feature, inside its root, with system back support.
6. **Access rules** (`AccountPolicy`): `@stu.bau.edu` students and `@bau.edu` staff are free; staff can
   submit project inquiries; everyone else is blocked for now, later allowed behind payment. The backend
   enforces this; the client only explains it.
7. Accessibility: touch targets ≥ 44pt/48dp, content descriptions on icon-only buttons, respect reduced
   motion for animations.
8. **Text links** ("Log In", "See All", "Forgot password?", "Cancel") use `BauTextLink`: only the words are tappable,
   no background / border / ripple on press — the text dims instead. Never use Material `TextButton`.
   In "Already a member? Log In" only "Log In" is the button.
9. **Bottom spacing** (mobile): scroll content and pinned buttons end `BauSpacing.aboveTabBar` (24dp) above the tab bar
   via `bottomContentPadding()` / `Modifier.tabBarContentPadding()`.
10. **Real data only.** The shipped app has no mock data, demo accounts or auth bypass. In-memory fakes
    (`Fake*Repository`, `FakeData`, `FakeMicrosoftSignIn`, `FakeGitHubRepository`) live in
    `app/shared/src/commonTest` (same packages) and are only for tests. Every screen renders repository data through
    `LoadStateContent`, with intentional empty and error states for a fresh account or an unreachable server.

## Backend URL and local testing

Production: `https://api.baubuild.vesperconnect.org/` (`ApiConfig.DEFAULT_BASE_URL`). To point a build at a local
server (e.g. `server/` on port 8000):

- **Desktop:** `-Dbau.apiBaseUrl=http://127.0.0.1:8000/` (JVM arg) or env `BAU_API_BASE_URL=http://127.0.0.1:8000/`.
- **iOS (Debug builds only):** launch argument `-apiBaseUrl http://127.0.0.1:8000/` (Xcode → Edit Scheme → Run →
  Arguments, or `xcrun simctl launch <udid> com.vesper.baubuild.demo… -apiBaseUrl http://127.0.0.1:8000/`).
  Info.plist allows local-network HTTP (`NSAllowsLocalNetworking`).
- **Android:** no switch (always production). For a local build call
  `AppGraph.init(AppGraph(ApiConfig(baseUrl = "http://10.0.2.2:8000/")))` in `MainActivity.onCreate` (cleartext HTTP
  also needs a network security config).

iOS DEBUG launch arguments that remain are purely visual and apply after a real sign-in: `-initialTab`,
`-forceLegacyTabBar`, `-startScrolledToBottom`, `-searchQuery`, `-staffTour show|off`, `-openNewInquiry`.
