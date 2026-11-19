package com.vesper.baubuild.feature.staff.inquiries

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vesper.baubuild.di.AppGraph
import com.vesper.baubuild.feature.staff.inquiries.detail.InquiryDetailScreen
import com.vesper.baubuild.feature.staff.inquiries.detail.InquiryDetailViewModel

/** Destinations pushed inside a staff tab (Overview or Inquiries). The tab's list is the implicit root. */
sealed interface StaffRoute {
    val key: String

    data class InquiryDetail(val inquiryId: String) : StaffRoute {
        override val key get() = "inquiry/$inquiryId"
    }

    companion object {
        fun decode(key: String): StaffRoute? = when {
            key.startsWith("inquiry/") -> key.removePrefix("inquiry/").takeIf { it.isNotEmpty() }?.let(::InquiryDetail)
            else -> null
        }
    }
}

/** Saveable in-tab back stack (each staff tab owns one). */
class StaffBackStack internal constructor(private val entries: SnapshotStateList<StaffRoute>) {
    val routes: List<StaffRoute> get() = entries
    val current: StaffRoute? get() = entries.lastOrNull()
    val canGoBack: Boolean get() = entries.isNotEmpty()

    fun push(route: StaffRoute) {
        if (entries.lastOrNull() != route) entries.add(route)
    }

    fun pop(): Boolean = entries.removeLastOrNull() != null

    companion object {
        fun of(vararg routes: StaffRoute) = StaffBackStack(mutableStateListOf(*routes))

        val Saver = listSaver<StaffBackStack, String>(
            save = { stack -> stack.entries.map { it.key } },
            restore = { keys -> StaffBackStack(mutableStateListOf(*keys.mapNotNull(StaffRoute::decode).toTypedArray())) },
        )
    }
}

@Composable
fun rememberStaffBackStack(): StaffBackStack = rememberSaveable(saver = StaffBackStack.Saver) { StaffBackStack.of() }

/** Per-destination ViewModel stores so a popped detail's ViewModel is cleared. Lives in the tab's own store. */
internal class StaffDestinationStores : ViewModel() {
    private val stores = mutableMapOf<String, ViewModelStore>()

    fun ownerFor(key: String): ViewModelStoreOwner {
        val store = stores.getOrPut(key) { ViewModelStore() }
        return object : ViewModelStoreOwner { override val viewModelStore = store }
    }

    fun retainOnly(keys: Set<String>) {
        (stores.keys - keys).forEach { stores.remove(it)?.clear() }
    }

    override fun onCleared() {
        stores.values.forEach { it.clear() }
        stores.clear()
    }
}

/**
 * Hosts a staff tab: shows [root] when the back stack is empty, otherwise the pushed destination.
 * System back (Android back, iOS edge swipe, desktop Esc) pops via [BackHandler].
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun StaffTabHost(
    /** Unique per tab ("overview", "inquiries") so tabs sharing a ViewModelStoreOwner never share stores. */
    hostKey: String,
    backStack: StaffBackStack,
    backLabel: String,
    modifier: Modifier = Modifier,
    root: @Composable () -> Unit,
) {
    val stores = viewModel(key = "staff-destinations/$hostKey") { StaffDestinationStores() }
    val keys = backStack.routes.map { it.key }.toSet()
    LaunchedEffect(keys) { stores.retainOnly(keys) }

    BackHandler(enabled = backStack.canGoBack) { backStack.pop() }

    Box(modifier.fillMaxSize()) {
        when (val route = backStack.current) {
            null -> root()
            else -> key(route.key) {
                val owner = remember(route.key) { stores.ownerFor(route.key) }
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                    when (route) {
                        is StaffRoute.InquiryDetail -> InquiryDetailScreen(
                            viewModel = viewModel(key = route.key) {
                                InquiryDetailViewModel(AppGraph.current.staffRepository, route.inquiryId)
                            },
                            backLabel = backLabel,
                            onBack = { backStack.pop() },
                        )
                    }
                }
            }
        }
    }
}
