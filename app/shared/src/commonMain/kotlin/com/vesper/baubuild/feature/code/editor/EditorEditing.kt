package com.vesper.baubuild.feature.code.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** Pure text-editing helpers for [CodeEditor] (kept out of Compose so they are unit-testable). */
object EditorEditing {
    const val Indent = "  "

    /**
     * If [new] is [old] with exactly one '\n' typed at the caret, copies the previous line's leading whitespace
     * after it (and adds one level after an opening `{`, `[`, `(` or `:` at the end of the line). Otherwise returns [new].
     */
    fun autoIndent(old: TextFieldValue, new: TextFieldValue): TextFieldValue {
        if (new.text.length != old.text.length + 1) return new
        val caret = new.selection.start
        if (!new.selection.collapsed || caret <= 0 || new.text[caret - 1] != '\n') return new
        // The rest must be unchanged: old == new without that one character.
        val insertAt = caret - 1
        if (!new.text.regionMatches(0, old.text, 0, insertAt) ||
            !new.text.regionMatches(caret, old.text, insertAt, old.text.length - insertAt)
        ) return new

        val lineStart = old.text.lastIndexOf('\n', insertAt - 1) + 1
        val line = old.text.substring(lineStart, insertAt)
        var indent = line.takeWhile { it == ' ' || it == '\t' }
        val lastChar = line.trimEnd().lastOrNull()
        if (lastChar == '{' || lastChar == '[' || lastChar == '(' || lastChar == ':') indent += Indent
        if (indent.isEmpty()) return new
        return insert(new, indent)
    }

    /** Replaces the selection with [text] and puts the caret after it. */
    fun insert(value: TextFieldValue, text: String): TextFieldValue {
        val start = minOf(value.selection.start, value.selection.end)
        val end = maxOf(value.selection.start, value.selection.end)
        val result = value.text.substring(0, start) + text + value.text.substring(end)
        return TextFieldValue(result, TextRange(start + text.length))
    }

    /** Tab key: two spaces. */
    fun insertTab(value: TextFieldValue): TextFieldValue = insert(value, Indent)


    /** 0-based line index of [offset]. */
    fun lineOf(text: String, offset: Int): Int {
        var line = 0
        val end = offset.coerceIn(0, text.length)
        for (i in 0 until end) if (text[i] == '\n') line++
        return line
    }
}
