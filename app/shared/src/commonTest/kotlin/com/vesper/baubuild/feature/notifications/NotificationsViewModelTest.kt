package com.vesper.baubuild.feature.notifications

import com.vesper.baubuild.core.LoadState
import com.vesper.baubuild.core.dataOrNull
import com.vesper.baubuild.data.fake.FakeNotificationRepository
import com.vesper.baubuild.data.fake.SampleNotifications
import com.vesper.baubuild.data.repository.AppNotification
import com.vesper.baubuild.data.repository.NotificationRepository
import com.vesper.baubuild.data.repository.NotificationType
import com.vesper.baubuild.feature.projects.MainDispatcherTest
import com.vesper.baubuild.feature.projects.ProjectsHomeViewModel
import com.vesper.baubuild.feature.projects.TestInquiryRepository
import com.vesper.baubuild.feature.projects.TestProjectRepository
import com.vesper.baubuild.feature.projects.signedIn
import com.vesper.baubuild.core.network.AppJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val NOW = 1_800_000_000_000L

private class TestNotificationRepository(var items: List<AppNotification> = emptyList()) : NotificationRepository {
    var fail = false
    var failMarkRead = false
    val marked = mutableListOf<String>()
    /** When set, markRead() suspends until completed (to observe the optimistic state). */
    var markGate: CompletableDeferred<Unit>? = null

    override suspend fun notifications(): List<AppNotification> { if (fail) error("offline"); return items }

    override suspend fun markRead(id: String) {
        markGate?.await()
        if (failMarkRead) error("offline")
        marked += id
        items = items.map { if (it.id == id) it.copy(read = true) else it }
    }
}

class NotificationsViewModelTest : MainDispatcherTest() {
    private fun vm(repo: NotificationRepository) = NotificationsViewModel(repo, clock = { NOW })

    @Test fun defaultFakeIsEmpty() = test {
        val vm = vm(FakeNotificationRepository())
        advanceUntilIdle()
        assertEquals(emptyList(), vm.state.value.notifications.dataOrNull)
        assertEquals(0, vm.state.value.unreadCount)
    }

    @Test fun loadsNonEmptyList() = test {
        val vm = vm(TestNotificationRepository(SampleNotifications.all(NOW)))
        advanceUntilIdle()
        assertEquals(4, vm.state.value.notifications.dataOrNull?.size)
        assertEquals(2, vm.state.value.unreadCount)
        assertEquals(NOW, vm.state.value.now)
    }

    @Test fun markReadUpdatesImmediatelyAndCallsBackend() = test {
        val repo = TestNotificationRepository(SampleNotifications.all(NOW))
        val vm = vm(repo)
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>().also { repo.markGate = it }
        vm.markRead("n_join")
        runCurrent()
        assertTrue(vm.state.value.notifications.dataOrNull!!.first { it.id == "n_join" }.read, "optimistic")
        assertEquals(emptyList(), repo.marked)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("n_join"), repo.marked)
        assertEquals(1, vm.state.value.unreadCount)
    }

    @Test fun markReadOnAlreadyReadIsNoOp() = test {
        val repo = TestNotificationRepository(SampleNotifications.all(NOW))
        val vm = vm(repo)
        advanceUntilIdle()
        vm.markRead("n_welcome")
        advanceUntilIdle()
        assertEquals(emptyList(), repo.marked)
    }

    @Test fun failedMarkReadReverts() = test {
        val repo = TestNotificationRepository(SampleNotifications.all(NOW)).apply { failMarkRead = true }
        val vm = vm(repo)
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>().also { repo.markGate = it }
        vm.markRead("n_join")
        runCurrent()
        assertTrue(vm.state.value.notifications.dataOrNull!!.first { it.id == "n_join" }.read)
        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.state.value.notifications.dataOrNull!!.first { it.id == "n_join" }.read)
    }

    @Test fun failedRefreshKeepsContentAndMarksStale() = test {
        val repo = TestNotificationRepository(SampleNotifications.all(NOW))
        val vm = vm(repo)
        advanceUntilIdle()
        repo.fail = true
        vm.refresh()
        advanceUntilIdle()
        val state = assertIs<LoadState.Content<List<AppNotification>>>(vm.state.value.notifications)
        assertTrue(state.stale)
        assertEquals(4, state.data.size)
    }

    @Test fun firstLoadFailureThenRetryRecovers() = test {
        val repo = TestNotificationRepository().apply { fail = true }
        val vm = vm(repo)
        advanceUntilIdle()
        assertIs<LoadState.Failed>(vm.state.value.notifications)
        repo.fail = false
        vm.refresh()
        advanceUntilIdle()
        assertEquals(emptyList(), vm.state.value.notifications.dataOrNull)
    }

    @Test fun relativeTimes() {
        assertEquals("Just now", relativeTime(NOW - 20_000, NOW))
        assertEquals("5m", relativeTime(NOW - 5 * 60_000, NOW))
        assertEquals("3h", relativeTime(NOW - 3 * 3_600_000, NOW))
        assertEquals("Yesterday", relativeTime(NOW - 30 * 3_600_000L, NOW))
        assertEquals("4d", relativeTime(NOW - 4 * 86_400_000L, NOW))
        assertEquals("2w", relativeTime(NOW - 15 * 86_400_000L, NOW))
        assertEquals("Just now", relativeTime(NOW + 60_000, NOW), "clock skew never shows negative times")
    }

    @Test fun decodesUnknownTypeAsGeneral() {
        val json = """[{"id":"1","type":"something_new","title":"Hi","created_at":1,"extra":true},
            {"id":"2","type":"join_approved","title":"In","created_at":2,"read":true}]"""
        val items = AppJson.decodeFromString<List<AppNotification>>(json)
        assertEquals(NotificationType.General, items[0].type)
        assertEquals(NotificationType.JoinApproved, items[1].type)
        assertTrue(items[1].read)
    }

    @Test fun homeBellShowsDotOnlyWithUnread() = test {
        val repo = TestNotificationRepository()
        val home = ProjectsHomeViewModel(TestProjectRepository(), TestInquiryRepository(), signedIn(), repo)
        advanceUntilIdle()
        assertFalse(home.state.value.hasUnreadNotifications)
        repo.items = SampleNotifications.all(NOW)
        home.refreshUnread()
        advanceUntilIdle()
        assertTrue(home.state.value.hasUnreadNotifications)
        repo.fail = true
        home.refreshUnread()
        advanceUntilIdle()
        assertTrue(home.state.value.hasUnreadNotifications, "failed check keeps last known badge")
    }
}
