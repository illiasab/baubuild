package com.vesper.baubuild.feature.mywork.mentor

/** A piece of a mentor reply: prose (with `inline code`) or a fenced ``` code block. */
sealed interface MentorBlock {
    data class Prose(val text: String) : MentorBlock
    data class Code(val code: String, val language: String? = null) : MentorBlock
}

/**
 * Splits [text] on ``` fences. An unclosed fence (a reply still streaming) runs to the end as code. The first line
 * after an opening fence is the language when it is a single word (```kotlin).
 */
fun mentorBlocks(text: String): List<MentorBlock> {
    val parts = text.split("```")
    val out = mutableListOf<MentorBlock>()
    parts.forEachIndexed { i, part ->
        if (i % 2 == 0) {
            val prose = part.trim('\n')
            if (prose.isNotBlank()) out += MentorBlock.Prose(prose)
        } else {
            val newline = part.indexOf('\n')
            val first = if (newline >= 0) part.substring(0, newline).trim() else ""
            val isLanguage = first.isNotEmpty() && first.none { it.isWhitespace() } && first.length <= 20
            val body = (if (isLanguage) part.substring(newline + 1) else part).trim('\n').trimEnd()
            if (body.isNotEmpty()) out += MentorBlock.Code(body, if (isLanguage) first else null)
        }
    }
    return out
}

/** Splits text on backticks: odd segments are inline code. An unmatched trailing backtick is kept as text. */
fun mentorSegments(text: String): List<Pair<String, Boolean>> {
    val parts = text.split('`')
    if (parts.size % 2 == 0) {
        val head = parts.dropLast(2)
        val tail = parts[parts.size - 2] + "`" + parts.last()
        return (head + tail).mapIndexed { i, s -> s to (i % 2 == 1) }.filter { it.first.isNotEmpty() }
    }
    return parts.mapIndexed { i, s -> s to (i % 2 == 1) }.filter { it.first.isNotEmpty() }
}
