package com.vesper.baubuild.core.session

import com.vesper.baubuild.data.fake.FakeData
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class SessionStoreTest {
    @Test
    fun restoresPairAndUserAndRefreshesUser() = runTest {
        val store = InMemoryTokenStore()
        SessionStore(store).signedIn(AuthTokens("a", "r", 99L), FakeData.sam)

        val restored = SessionStore(store)
        var tokenSeen: String? = null
        restored.restore { tokenSeen = restored.token; FakeData.sam.copy(bio = "fresh") }
        assertEquals("a", tokenSeen) // tokens are current while /v1/me runs
        assertEquals(AuthTokens("a", "r", 99L), restored.tokens)
        assertEquals("fresh", assertIs<SessionState.SignedIn>(restored.state.value).user.bio)
    }

    @Test
    fun offlineLaunchKeepsCachedUser() = runTest {
        val store = InMemoryTokenStore()
        SessionStore(store).signedIn(AuthTokens("a", "r"), FakeData.sam)
        val restored = SessionStore(store)
        restored.restore { error("offline") }
        assertEquals(FakeData.sam, assertIs<SessionState.SignedIn>(restored.state.value).user)
        assertEquals("a", restored.token)
    }

    @Test
    fun rejectedDuringRestoreEndsSignedOut() = runTest {
        val store = InMemoryTokenStore()
        SessionStore(store).signedIn(AuthTokens("a", "r"), FakeData.sam)
        val restored = SessionStore(store)
        restored.restore { restored.signOut(); error("401") } // what the HTTP layer does on refresh_reused
        assertEquals(SessionState.SignedOut, restored.state.value)
        assertNull(store.read())
    }

    @Test
    fun legacyBareTokenIsReadAsAccessToken() = runTest {
        val store = InMemoryTokenStore().apply { write("legacy-token") }
        val restored = SessionStore(store)
        restored.restore { FakeData.sam }
        assertEquals("legacy-token", restored.token)
        assertIs<SessionState.SignedIn>(restored.state.value)
    }
}
