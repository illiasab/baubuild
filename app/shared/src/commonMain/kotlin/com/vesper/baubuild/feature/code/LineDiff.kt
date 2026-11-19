package com.vesper.baubuild.feature.code

enum class DiffKind { Unchanged, Added, Removed }

/** One line of a unified diff. Line numbers are 1-based; null on the side the line doesn't exist. */
data class DiffLine(val kind: DiffKind, val text: String, val oldNumber: Int?, val newNumber: Int?)

/**
 * Small line diff for the code workspace: trims the common prefix/suffix, then an LCS table over the changed middle.
 * Files are ≤ ~1000 lines, and typical edits touch a few lines, so the middle stays tiny. If the middle is huge
 * (whole-file rewrite) it falls back to "all removed, all added" instead of allocating a big table.
 */
object LineDiff {
    private const val MaxCells = 4_000_000L

    fun lines(text: String): List<String> = if (text.isEmpty()) emptyList() else text.split('\n')

    fun compute(old: String, new: String): List<DiffLine> = compute(lines(old), lines(new))

    fun compute(old: List<String>, new: List<String>): List<DiffLine> {
        var prefix = 0
        while (prefix < old.size && prefix < new.size && old[prefix] == new[prefix]) prefix++
        var suffix = 0
        while (suffix < old.size - prefix && suffix < new.size - prefix &&
            old[old.size - 1 - suffix] == new[new.size - 1 - suffix]
        ) suffix++

        val out = ArrayList<DiffLine>(maxOf(old.size, new.size) + 8)
        for (i in 0 until prefix) out += DiffLine(DiffKind.Unchanged, old[i], i + 1, i + 1)

        val a = old.subList(prefix, old.size - suffix)
        val b = new.subList(prefix, new.size - suffix)
        if (a.isEmpty() || b.isEmpty() || a.size.toLong() * b.size > MaxCells) {
            a.forEachIndexed { i, l -> out += DiffLine(DiffKind.Removed, l, prefix + i + 1, null) }
            b.forEachIndexed { j, l -> out += DiffLine(DiffKind.Added, l, null, prefix + j + 1) }
        } else {
            // lcs[i][j] = LCS length of a[i..] and b[j..], flattened.
            val n = a.size
            val m = b.size
            val w = m + 1
            val lcs = IntArray((n + 1) * w)
            for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
                lcs[i * w + j] = if (a[i] == b[j]) lcs[(i + 1) * w + j + 1] + 1
                else maxOf(lcs[(i + 1) * w + j], lcs[i * w + j + 1])
            }
            var i = 0
            var j = 0
            while (i < n && j < m) {
                when {
                    a[i] == b[j] -> { out += DiffLine(DiffKind.Unchanged, a[i], prefix + i + 1, prefix + j + 1); i++; j++ }
                    lcs[(i + 1) * w + j] >= lcs[i * w + j + 1] -> { out += DiffLine(DiffKind.Removed, a[i], prefix + i + 1, null); i++ }
                    else -> { out += DiffLine(DiffKind.Added, b[j], null, prefix + j + 1); j++ }
                }
            }
            while (i < n) { out += DiffLine(DiffKind.Removed, a[i], prefix + i + 1, null); i++ }
            while (j < m) { out += DiffLine(DiffKind.Added, b[j], null, prefix + j + 1); j++ }
        }

        for (k in 0 until suffix) {
            val oi = old.size - suffix + k
            val ni = new.size - suffix + k
            out += DiffLine(DiffKind.Unchanged, old[oi], oi + 1, ni + 1)
        }
        return out
    }

    /** "+3 −1" counts for a changed file. */
    fun stats(lines: List<DiffLine>): Pair<Int, Int> =
        lines.count { it.kind == DiffKind.Added } to lines.count { it.kind == DiffKind.Removed }
}
