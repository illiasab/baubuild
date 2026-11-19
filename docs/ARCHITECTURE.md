# BAU BUILD app — architecture

Kotlin Multiplatform + Compose Multiplatform. Targets: **iOS, Android, macOS / Windows / Linux (Compose Desktop, JVM)**.
The web app is built separately and is not part of this repo.

Design reference: https://baybuild.vercel.app/ (tab "iOS app").

## Layers (all in `app/shared/src/commonMain/kotlin/com/vesper/baubuild/`)

| Package | What lives there |
|---|---|
| `core/model` | Serializable domain models + `AccountPolicy` (email-domain rules). |
| `core/session` | `SessionStore` (StateFlow of `SessionState`), `TokenStore` for the backend's opaque token. |
| `core/network` | `ApiConfig`, `AppJson`, Ktor `HttpClient` with retry/backoff + Bearer token. |
| `core/LoadState*.kt` | `LoadState<T>` and `MutableStateFlow<LoadState<T>>.load {}` helper. |
| `data/repository` | Repository interfaces (the contract with the Go backend). |
| `data/fake` | In-memory fakes seeded from the design. Used while `ApiConfig.useFakeBackend = true`. |
| `di/AppGraph` | Manual DI container. `AppGraph.current`. |
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
4. **iOS shell is native SwiftUI.** The `TabView` (Liquid Glass, iOS 26+) with a separate search glass
   button hosts each tab's Compose root via a `UIViewController` exported from `iosMain`.
   Android/desktop use the Compose shell in `shell/`.
5. **Tab roots are the contract** between features and shells:
   `AuthRoot`, `ProjectsTabRoot`, `MyWorkTabRoot`, `ReviewTabRoot`, `ProfileTabRoot`, `SearchRoot` —
   each `@Composable fun X(modifier: Modifier = Modifier)`. In-tab navigation (list → detail) is owned
   by the feature, inside its root, with system back support.
6. **Access rules** (`AccountPolicy`): `@stu.bau.edu` students and `@bau.edu` staff are free; staff can
   submit project inquiries; everyone else is blocked for now, later allowed behind payment. The backend
   enforces this; the client only explains it.
7. Accessibility: touch targets ≥ 44pt/48dp, content descriptions on icon-only buttons, respect reduced
   motion for animations.
