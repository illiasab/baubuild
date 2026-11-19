package com.vesper.baubuild.feature.code

import com.vesper.baubuild.data.github.RepoEntry
import com.vesper.baubuild.data.github.RepoEntryType

/** Flattened file tree for the drawer / left column: folders first, then files, alphabetical within a folder. */
object CodeTree {
    /** Rows to show: every entry whose ancestor folders are all in [expanded]. */
    fun visibleRows(entries: List<RepoEntry>, expanded: Set<String>): List<RepoEntry> {
        val children = entries.groupBy { it.path.substringBeforeLast('/', "") }
        val out = ArrayList<RepoEntry>(entries.size)
        fun walk(dir: String) {
            val kids = children[dir].orEmpty()
                .sortedWith(compareBy<RepoEntry>({ it.type != RepoEntryType.Directory }, { it.name.lowercase() }))
            for (e in kids) {
                out += e
                if (e.type == RepoEntryType.Directory && e.path in expanded) walk(e.path)
            }
        }
        walk("")
        return out
    }

    /** Folders open by default: the top level and anything on the way to [paths] (e.g. open tabs). */
    fun defaultExpanded(entries: List<RepoEntry>, paths: Collection<String>): Set<String> {
        val top = entries.filter { it.type == RepoEntryType.Directory && it.depth == 0 }.map { it.path }
        return (top + paths.flatMap(::ancestors)).toSet()
    }

    fun ancestors(path: String): List<String> {
        val parts = path.split('/')
        return (1 until parts.size).map { parts.take(it).joinToString("/") }
    }
}
