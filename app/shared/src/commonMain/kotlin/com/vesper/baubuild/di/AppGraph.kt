package com.vesper.baubuild.di

import com.vesper.baubuild.core.model.AccountPolicy
import com.vesper.baubuild.core.network.ApiConfig
import com.vesper.baubuild.core.network.createHttpClient
import com.vesper.baubuild.core.session.SessionState
import com.vesper.baubuild.core.session.SessionStore
import com.vesper.baubuild.core.session.TokenStore
import com.vesper.baubuild.core.session.platformTokenStore
import com.vesper.baubuild.data.github.BackendLinkedGitHubRepository
import com.vesper.baubuild.data.github.GitHubBackendRegistration
import com.vesper.baubuild.data.github.GitHubRepository
import com.vesper.baubuild.data.github.HttpGitHubRepository
import com.vesper.baubuild.data.github.HttpGitHubTokenLinkApi
import com.vesper.baubuild.data.http.auth.HttpAuthRepository
import com.vesper.baubuild.data.http.config.AppConfigRepository
import com.vesper.baubuild.data.http.config.HttpAppConfigRepository
import com.vesper.baubuild.data.http.staff.HttpStaffRepository
import com.vesper.baubuild.data.http.student.HttpClassRepository
import com.vesper.baubuild.data.http.student.HttpInquiryRepository
import com.vesper.baubuild.data.http.student.HttpNotificationRepository
import com.vesper.baubuild.data.http.student.HttpProfileRepository
import com.vesper.baubuild.data.http.student.HttpProjectRepository
import com.vesper.baubuild.data.http.student.HttpWorkRepository
import com.vesper.baubuild.data.repository.*
import com.vesper.baubuild.feature.appstatus.AppStatusHost

/**
 * Manual DI container. Created once per process by each platform entry point via [AppGraph.init].
 * Everything talks to the real BAU BUILD backend at [ApiConfig.baseUrl]; tests build ViewModels with the fakes in
 * `commonTest` instead of going through this graph.
 */
class AppGraph(
    val config: ApiConfig = ApiConfig(),
    val policy: AccountPolicy = AccountPolicy(),
    /** Keychain / Keystore / prefs. Tests pass [com.vesper.baubuild.core.session.InMemoryTokenStore]. */
    tokenStore: TokenStore = platformTokenStore(),
) {
    val session = SessionStore(tokenStore)

    val httpClient by lazy { createHttpClient(config, session) }

    /** Email + password accounts with email-code verification; stores the session when tokens come back. */
    val authRepository: AuthRepository by lazy { HttpAuthRepository(httpClient, session, policy) }

    /** `/v1/config`: minimum versions, maintenance banner, feature flags. Fails open. */
    val appConfigRepository: AppConfigRepository by lazy { HttpAppConfigRepository(httpClient) }
    val projectRepository: ProjectRepository by lazy { HttpProjectRepository(httpClient) }
    val inquiryRepository: InquiryRepository by lazy { HttpInquiryRepository(httpClient) }
    val classRepository: ClassRepository by lazy { HttpClassRepository(httpClient) }
    val workRepository: WorkRepository by lazy { HttpWorkRepository(httpClient, projects = projectRepository) }
    val notificationRepository: NotificationRepository by lazy { HttpNotificationRepository(httpClient) }
    val staffRepository: StaffRepository by lazy { HttpStaffRepository(httpClient) }

    /**
     * Student's GitHub (in-app code workspace, GitHub API with the student's token). Also links the token with the
     * backend (PUT /v1/integrations/github/token) so the org push agent can use it — only while `features.github`
     * is on; the Connect GitHub UI itself stays available either way.
     */
    val gitHubRepository: GitHubRepository by lazy {
        BackendLinkedGitHubRepository(
            HttpGitHubRepository(),
            GitHubBackendRegistration(
                HttpGitHubTokenLinkApi(httpClient),
                // Respect `features.github` from /v1/config (currently false on production).
                backendIntegrationEnabled = { appConfigRepository.features.value.github },
            ),
        )
    }
    val profileRepository: ProfileRepository by lazy {
        HttpProfileRepository(httpClient, { (session.state.value as? SessionState.SignedIn)?.user }, policy)
    }

    /** Launch-time restore used by every platform shell: stored tokens → `GET /v1/me`, or signed out. */
    suspend fun restoreSession() {
        session.restore { authRepository.me() }
    }

    init {
        // App status UI (update gate + maintenance banner) reads config from here.
        AppStatusHost.repository = appConfigRepository
    }

    companion object {
        private var instance: AppGraph? = null
        val current: AppGraph get() = instance ?: AppGraph().also { instance = it }
        fun init(graph: AppGraph) { instance = graph }
    }
}
