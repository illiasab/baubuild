package com.vesper.baubuild.data.http.config

import com.vesper.baubuild.core.session.DeviceInfo
import com.vesper.baubuild.core.session.epochMillis
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * App status from `GET /v1/config`: minimum version gate, maintenance banner, feature flags.
 * Fails open: until a fetch succeeds (or when it fails) [status] is [AppStatus.Open]; a failed fetch keeps the last
 * good value. Never throws from [refresh].
 */
interface AppConfigRepository {
    val status: StateFlow<AppStatus>
    val features: StateFlow<FeatureFlags>

    /** Fetches the config. Throttled to one fetch per interval unless [force]; concurrent calls share one fetch. */
    suspend fun refresh(force: Boolean = false)
}

/** Default before `/v1/config` answers, and for tests: nothing is ever blocked, all default flags. */
class OpenAppConfigRepository(initial: AppStatus = AppStatus.Open) : AppConfigRepository {
    private val _status = MutableStateFlow(initial)
    override val status: StateFlow<AppStatus> = _status.asStateFlow()
    override val features: StateFlow<FeatureFlags> = MutableStateFlow(initial.features).asStateFlow()
    override suspend fun refresh(force: Boolean) = Unit
}

/**
 * [client] is the shared backend client ([com.vesper.baubuild.core.network.createHttpClient]); `/v1/config` is
 * public, so it works signed out too. Fetch on launch and on resume; [minIntervalMillis] throttles resumes.
 */
class HttpAppConfigRepository(
    private val client: HttpClient,
    private val device: DeviceInfo = DeviceInfo.current,
    private val clock: () -> Long = ::epochMillis,
    private val minIntervalMillis: Long = 5 * 60_000L,
    private val failureRetryMillis: Long = 30_000L,
) : AppConfigRepository {
    private val _status = MutableStateFlow(AppStatus.Open)
    override val status: StateFlow<AppStatus> = _status.asStateFlow()

    private val _features = MutableStateFlow(AppStatus.Open.features)
    override val features: StateFlow<FeatureFlags> = _features.asStateFlow()

    private val mutex = Mutex()
    private var lastSuccessMillis: Long? = null
    private var lastAttemptMillis: Long? = null

    override suspend fun refresh(force: Boolean) {
        mutex.withLock {
            val now = clock()
            if (!force) {
                // After a success wait the full interval; after a failure retry sooner.
                lastSuccessMillis?.let { if (now - it < minIntervalMillis) return }
                lastAttemptMillis?.let { if (now - it < failureRetryMillis) return }
            }
            lastAttemptMillis = now
            val config = try {
                val response = client.get("v1/config")
                if (response.status.isSuccess()) AppConfig.parse(response.bodyAsText()) else null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null // offline / 5xx / anything: fail open, keep what we had
            }
            if (config != null) {
                val next = AppStatus.from(config, device.platform, device.appVersion)
                _status.value = next
                _features.value = next.features
                lastSuccessMillis = now
            }
        }
    }
}
