package com.vesper.baubuild.di

import com.vesper.baubuild.core.auth.FakeMicrosoftSignIn
import com.vesper.baubuild.core.auth.MicrosoftAuthConfig
import com.vesper.baubuild.core.auth.MicrosoftOAuthSignIn
import com.vesper.baubuild.core.auth.MicrosoftSignIn
import com.vesper.baubuild.core.model.AccountPolicy
import com.vesper.baubuild.core.network.ApiConfig
import com.vesper.baubuild.core.network.createHttpClient
import com.vesper.baubuild.core.session.SessionStore
import com.vesper.baubuild.core.session.TokenStore
import com.vesper.baubuild.core.session.platformTokenStore
import com.vesper.baubuild.data.fake.*
import com.vesper.baubuild.data.repository.*

/**
 * Manual DI container. Created once per process by each platform entry point via [AppGraph.init].
 * Real (HTTP) repositories replace the fakes when [ApiConfig.useFakeBackend] is false.
 */
class AppGraph(
    val config: ApiConfig = ApiConfig(),
    val policy: AccountPolicy = AccountPolicy(),
    /** Keychain / Keystore / prefs. Tests pass [com.vesper.baubuild.core.session.InMemoryTokenStore]. */
    tokenStore: TokenStore = platformTokenStore(),
    val microsoftAuthConfig: MicrosoftAuthConfig = MicrosoftAuthConfig(),
) {
    val session = SessionStore(tokenStore)

    /**
     * Real Microsoft sign-in only when the app registration is filled in and the real backend is on
     * (the fake backend can't redeem a real code). Otherwise a demo flow with selectable accounts.
     */
    val microsoftSignIn: MicrosoftSignIn by lazy {
        if (!config.useFakeBackend && microsoftAuthConfig.isConfigured) MicrosoftOAuthSignIn(microsoftAuthConfig)
        else FakeMicrosoftSignIn()
    }
    val httpClient by lazy { createHttpClient(config, session) }

    val authRepository: AuthRepository = FakeAuthRepository(policy)
    val projectRepository: ProjectRepository = FakeProjectRepository()
    val inquiryRepository: InquiryRepository = FakeInquiryRepository()
    val classRepository: ClassRepository = FakeClassRepository()
    val workRepository: WorkRepository = FakeWorkRepository()

    companion object {
        private var instance: AppGraph? = null
        val current: AppGraph get() = instance ?: AppGraph().also { instance = it }
        fun init(graph: AppGraph) { instance = graph }
    }
}
