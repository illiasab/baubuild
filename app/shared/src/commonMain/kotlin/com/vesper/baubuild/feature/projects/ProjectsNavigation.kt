package com.vesper.baubuild.feature.projects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.vesper.baubuild.core.model.Difficulty

/** What a project list screen shows. */
sealed interface ProjectListSource {
    data object AllOpen : ProjectListSource
    data class ByDifficulty(val difficulty: Difficulty) : ProjectListSource
    data class ForClass(val code: String, val name: String) : ProjectListSource
}

/** Destinations inside the Projects tab. Home is the implicit root (empty back stack). */
sealed interface ProjectsRoute {
    /** Stable, saveable identity of this destination (also its ViewModelStore key). */
    val key: String

    data class ProjectList(val source: ProjectListSource) : ProjectsRoute {
        override val key: String
            get() = when (source) {
                ProjectListSource.AllOpen -> "list/all"
                is ProjectListSource.ByDifficulty -> "list/${source.difficulty.name}"
                is ProjectListSource.ForClass -> "class/${source.code}|${source.name}"
            }
    }

    data object MyClasses : ProjectsRoute {
        override val key: String get() = "classes"
    }

    data class Detail(val projectId: String) : ProjectsRoute {
        override val key: String get() = "project/$projectId"
    }

    data object RequestProject : ProjectsRoute {
        override val key: String get() = "inquiry"
    }

    data object Notifications : ProjectsRoute {
        override val key: String get() = "notifications"
    }

    companion object {
        fun decode(key: String): ProjectsRoute? {
            val arg = key.substringAfter('/', missingDelimiterValue = "")
            return when (key.substringBefore('/')) {
                "classes" -> MyClasses
                "inquiry" -> RequestProject
                "notifications" -> Notifications
                "project" -> arg.ifEmpty { null }?.let(::Detail)
                "class" -> arg.substringBefore('|').ifEmpty { null }
                    ?.let { ProjectList(ProjectListSource.ForClass(it, arg.substringAfter('|', ""))) }
                "list" -> when (arg) {
                    "all" -> ProjectList(ProjectListSource.AllOpen)
                    else -> Difficulty.entries.firstOrNull { it.name == arg }?.let { ProjectList(ProjectListSource.ByDifficulty(it)) }
                }
                else -> null
            }
        }
    }
}

/** In-tab back stack. Survives rotation and process death (saved as route keys). */
class ProjectsBackStack internal constructor(private val entries: SnapshotStateList<ProjectsRoute>) {
    val routes: List<ProjectsRoute> get() = entries
    val current: ProjectsRoute? get() = entries.lastOrNull()
    val canGoBack: Boolean get() = entries.isNotEmpty()

    fun push(route: ProjectsRoute) {
        if (entries.lastOrNull() != route) entries.add(route)
    }

    fun pop(): Boolean = entries.removeLastOrNull() != null

    /** Back to Projects home (the tab's root). */
    fun popToRoot() = entries.clear()

    companion object {
        fun of(vararg routes: ProjectsRoute) = ProjectsBackStack(mutableStateListOf(*routes))

        val Saver = listSaver<ProjectsBackStack, String>(
            save = { stack -> stack.entries.map { it.key } },
            restore = { keys -> ProjectsBackStack(mutableStateListOf(*keys.mapNotNull(ProjectsRoute::decode).toTypedArray())) },
        )
    }
}

@Composable
internal fun rememberProjectsBackStack(): ProjectsBackStack =
    rememberSaveable(saver = ProjectsBackStack.Saver) { ProjectsBackStack.of() }

/**
 * One [ViewModelStore] per destination so a popped screen's ViewModel is cleared instead of living on in the
 * tab's store. The holder itself lives in the tab's store, so destination stores survive rotation.
 */
internal class ProjectsDestinationStores : ViewModel() {
    private class Owner : ViewModelStoreOwner {
        override val viewModelStore: ViewModelStore = ViewModelStore()
    }

    private val owners = mutableMapOf<String, Owner>()

    fun ownerFor(key: String): ViewModelStoreOwner = owners.getOrPut(key) { Owner() }

    /** Clears stores of destinations no longer on the back stack. */
    fun retainOnly(keys: Set<String>) {
        (owners.keys - keys).forEach { owners.remove(it)?.viewModelStore?.clear() }
    }

    override fun onCleared() {
        owners.values.forEach { it.viewModelStore.clear() }
        owners.clear()
    }
}
