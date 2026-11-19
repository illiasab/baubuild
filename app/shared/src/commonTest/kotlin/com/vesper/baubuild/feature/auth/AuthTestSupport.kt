package com.vesper.baubuild.feature.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest

/** Records what the form ViewModels asked of the auth flow. */
class RecordingNavigator : AuthNavigator {
    val verifications = mutableListOf<Pair<String, Long?>>()
    val notAllowed = mutableListOf<String>()
    override fun openVerifyEmail(email: String, resendAfterSeconds: Long?) { verifications += email to resendAfterSeconds }
    override fun showNotAllowed(email: String) { notAllowed += email }
}

/** viewModelScope runs on a [StandardTestDispatcher] installed as Main, so tests control time. */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class ViewModelTest {
    protected val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUpMain() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDownMain() = Dispatchers.resetMain()

    protected fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }
}
