package com.vesper.baubuild.feature.mywork

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel

/** Destinations inside the My Work tab. The list itself is the implicit root (empty back stack). */
sealed interface MyWorkRoute {
    val key: String

    data class Workspace(val projectId: String) : MyWorkRoute {
        override val key get() = "workspace/$projectId"
    }

    data class MentorAi(val projectId: String) : MyWorkRoute {
        override val key get() = "mentor/$projectId"
    }

    companion object {
        fun decode(key: String): MyWorkRoute? {
            val id = key.substringAfter('/', missingDelimiterValue = "").ifEmpty { return null }
            return when (key.substringBefore('/')) {
                "workspace" -> Workspace(id)
                "mentor" -> MentorAi(id)
                else -> null
            }
        }
    }
}

/**
 * In-tab back stack. Survives rotation and process death (saved as route keys).
 * The list screen is the root and is never on the stack.
 */
class MyWorkBackStack internal constructor(private val entries: SnapshotStateList<MyWorkRoute>) {
    val routes: List<MyWorkRoute> get() = entries
    val current: MyWorkRoute? get() = entries.lastOrNull()
    val canGoBack: Boolean get() = entries.isNotEmpty()

    fun push(route: MyWorkRoute) {
        if (entries.lastOrNull() != route) entries.add(route)
    }

    fun pop(): Boolean = entries.removeLastOrNull() != null

    companion object {
        fun of(vararg routes: MyWorkRoute) = MyWorkBackStack(mutableStateListOf(*routes))

        val Saver = listSaver<MyWorkBackStack, String>(
            save = { stack -> stack.entries.map { it.key } },
            restore = { keys -> MyWorkBackStack(mutableStateListOf(*keys.mapNotNull(MyWorkRoute::decode).toTypedArray())) },
        )
    }
}

@Composable
fun rememberMyWorkBackStack(): MyWorkBackStack = rememberSaveable(saver = MyWorkBackStack.Saver) { MyWorkBackStack.of() }

/**
 * Gives each destination its own [ViewModelStore] so a popped workspace's ViewModel is cleared instead of
 * living on in the tab's store. The holder itself lives in the tab's store, so stores survive rotation.
 */
internal class MyWorkDestinationStores : ViewModel() {
    private val stores = mutableMapOf<String, ViewModelStore>()

    fun ownerFor(key: String): ViewModelStoreOwner {
        val store = stores.getOrPut(key) { ViewModelStore() }
        return object : ViewModelStoreOwner { override val viewModelStore = store }
    }

    /** Clears stores for destinations that are no longer on the back stack. */
    fun retainOnly(keys: Set<String>) {
        val removed = stores.keys - keys
        removed.forEach { stores.remove(it)?.clear() }
    }

    override fun onCleared() {
        stores.values.forEach { it.clear() }
        stores.clear()
    }
}

@Composable
internal fun rememberDestinationStores(backStack: MyWorkBackStack): MyWorkDestinationStores {
    val stores = viewModel { MyWorkDestinationStores() }
    val keys = backStack.routes.map { it.key }.toSet()
    LaunchedEffect(keys) { stores.retainOnly(keys) }
    return remember(stores) { stores }
}

