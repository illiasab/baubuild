package com.vesper.baubuild.feature.code.editor

enum class TokenKind { Keyword, String, Number, Comment }

/** [start, end) range in the source text. Text between tokens is plain. */
data class Token(val start: Int, val end: Int, val kind: TokenKind)

/** What the tokenizer needs to know about a language. Deliberately tiny: this is highlighting, not parsing. */
data class LanguageSpec(
    val name: String,
    val keywords: Set<String> = emptySet(),
    val lineComment: String? = null,
    val blockComment: Pair<String, String>? = null,
    /** Quote characters that start a single-line string (backtick strings may span lines). */
    val quotes: String = "\"'",
    val multiLineQuotes: String = "",
    /** Triple-quoted strings (`"""` in Kotlin/Python/Swift). */
    val tripleQuotes: Boolean = false,
    val markdown: Boolean = false,
) {
    companion object {
        private val jsKeywords = setOf(
            "break", "case", "catch", "class", "const", "continue", "debugger", "default", "delete", "do", "else",
            "export", "extends", "finally", "for", "function", "if", "import", "in", "instanceof", "new", "return",
            "super", "switch", "this", "throw", "try", "typeof", "var", "void", "while", "with", "yield", "let",
            "static", "async", "await", "of", "from", "as", "true", "false", "null", "undefined",
        )
        private val tsExtra = setOf(
            "interface", "type", "enum", "implements", "private", "public", "protected", "readonly", "declare",
            "namespace", "abstract", "keyof", "string", "number", "boolean", "any", "unknown", "never",
        )

        val JavaScript = LanguageSpec("JavaScript", jsKeywords, "//", "/*" to "*/", multiLineQuotes = "`")
        val TypeScript = JavaScript.copy(name = "TypeScript", keywords = jsKeywords + tsExtra)
        val Kotlin = LanguageSpec(
            "Kotlin",
            setOf(
                "package", "import", "class", "interface", "object", "fun", "val", "var", "if", "else", "when", "for",
                "while", "do", "return", "break", "continue", "throw", "try", "catch", "finally", "is", "in", "as",
                "null", "true", "false", "this", "super", "data", "sealed", "enum", "private", "internal", "public",
                "protected", "override", "open", "abstract", "suspend", "inline", "companion", "const", "lateinit",
                "by", "typealias",
            ),
            "//", "/*" to "*/", quotes = "\"'", tripleQuotes = true,
        )
        val Swift = LanguageSpec(
            "Swift",
            setOf(
                "import", "class", "struct", "enum", "protocol", "extension", "func", "let", "var", "if", "else",
                "guard", "switch", "case", "default", "for", "in", "while", "repeat", "return", "break", "continue",
                "throw", "throws", "try", "catch", "do", "nil", "true", "false", "self", "Self", "super", "init",
                "private", "fileprivate", "internal", "public", "open", "static", "override", "async", "await",
                "some", "any", "where", "as", "is",
            ),
            "//", "/*" to "*/", quotes = "\"", tripleQuotes = true,
        )
        val Python = LanguageSpec(
            "Python",
            setOf(
                "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del", "elif", "else",
                "except", "finally", "for", "from", "global", "if", "import", "in", "is", "lambda", "nonlocal",
                "not", "or", "pass", "raise", "return", "try", "while", "with", "yield", "None", "True", "False",
                "self",
            ),
            "#", null, tripleQuotes = true,
        )
        val Go = LanguageSpec(
            "Go",
            setOf(
                "break", "case", "chan", "const", "continue", "default", "defer", "else", "fallthrough", "for",
                "func", "go", "goto", "if", "import", "interface", "map", "package", "range", "return", "select",
                "struct", "switch", "type", "var", "nil", "true", "false",
            ),
            "//", "/*" to "*/", multiLineQuotes = "`",
        )
        val Java = LanguageSpec(
            "Java",
            setOf(
                "abstract", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue",
                "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "if",
                "implements", "import", "instanceof", "int", "interface", "long", "new", "package", "private",
                "protected", "public", "return", "short", "static", "super", "switch", "this", "throw", "throws",
                "try", "void", "while", "var", "null", "true", "false", "record",
            ),
            "//", "/*" to "*/",
        )
        val Css = LanguageSpec(
            "CSS",
            setOf("important", "media", "import", "keyframes", "supports", "font-face", "root", "hover", "focus"),
            null, "/*" to "*/",
        )
        val Json = LanguageSpec("JSON", setOf("true", "false", "null"), null, null, quotes = "\"")
        val Markdown = LanguageSpec("Markdown", markdown = true)
        val PlainText = LanguageSpec("Plain text", quotes = "")

        /** By file extension; unknown extensions get plain text (no highlighting). */
        fun forPath(path: String): LanguageSpec = when (path.substringAfterLast('.', "").lowercase()) {
            "js", "jsx", "mjs", "cjs" -> JavaScript
            "ts", "tsx" -> TypeScript
            "kt", "kts" -> Kotlin
            "swift" -> Swift
            "py" -> Python
            "go" -> Go
            "java" -> Java
            "css", "scss" -> Css
            "json" -> Json
            "md", "markdown" -> Markdown
            else -> PlainText
        }
    }
}

/**
 * Single-pass, linear-time tokenizer. Handles line/block comments, single/multi-line/triple-quoted strings with
 * backslash escapes, numbers (incl. hex, decimals, `_` separators) and keywords. Markdown: headings as keywords,
 * inline/fenced code as strings.
 */
object SyntaxTokenizer {
    fun tokenize(text: String, spec: LanguageSpec): List<Token> =
        if (spec.markdown) markdown(text) else code(text, spec)

    private fun code(text: String, spec: LanguageSpec): List<Token> {
        val out = ArrayList<Token>()
        val n = text.length
        var i = 0
        while (i < n) {
            val c = text[i]
            val line = spec.lineComment
            val block = spec.blockComment
            when {
                line != null && text.startsWith(line, i) -> {
                    val end = text.indexOf('\n', i).let { if (it < 0) n else it }
                    out += Token(i, end, TokenKind.Comment); i = end
                }
                block != null && text.startsWith(block.first, i) -> {
                    val close = text.indexOf(block.second, i + block.first.length)
                    val end = if (close < 0) n else close + block.second.length
                    out += Token(i, end, TokenKind.Comment); i = end
                }
                spec.tripleQuotes && (c == '"' || c == '\'') && text.startsWith("$c$c$c", i) -> {
                    val close = text.indexOf("$c$c$c", i + 3)
                    val end = if (close < 0) n else close + 3
                    out += Token(i, end, TokenKind.String); i = end
                }
                c in spec.quotes || c in spec.multiLineQuotes -> {
                    val multi = c in spec.multiLineQuotes
                    var j = i + 1
                    while (j < n) {
                        val d = text[j]
                        if (d == '\\') { j += 2; continue }
                        if (d == c) { j++; break }
                        if (d == '\n' && !multi) break
                        j++
                    }
                    val end = minOf(j, n)
                    out += Token(i, end, TokenKind.String); i = end
                }
                c.isDigit() && (i == 0 || !isIdent(text[i - 1])) -> {
                    var j = i + 1
                    if (c == '0' && j < n && (text[j] == 'x' || text[j] == 'X')) j++
                    while (j < n && (text[j].isLetterOrDigit() || text[j] == '_' ||
                            (text[j] == '.' && j + 1 < n && text[j + 1].isDigit()))
                    ) j++
                    out += Token(i, j, TokenKind.Number); i = j
                }
                isIdentStart(c) -> {
                    var j = i + 1
                    while (j < n && (isIdent(text[j]) || (spec === LanguageSpec.Css && text[j] == '-'))) j++
                    if (text.substring(i, j) in spec.keywords) out += Token(i, j, TokenKind.Keyword)
                    i = j
                }
                else -> i++
            }
        }
        return out
    }

    private fun markdown(text: String): List<Token> {
        val out = ArrayList<Token>()
        var lineStart = 0
        var inFence = false
        var fenceStart = 0
        while (lineStart <= text.length) {
            val nl = text.indexOf('\n', lineStart)
            val lineEnd = if (nl < 0) text.length else nl
            val trimmed = text.substring(lineStart, lineEnd).trimStart()
            if (trimmed.startsWith("```")) {
                if (inFence) out += Token(fenceStart, lineEnd, TokenKind.String) else fenceStart = lineStart
                inFence = !inFence
            } else if (!inFence) {
                if (trimmed.startsWith("#")) {
                    out += Token(lineStart, lineEnd, TokenKind.Keyword)
                } else {
                    var k = lineStart
                    while (k < lineEnd) {
                        if (text[k] == '`') {
                            val close = text.indexOf('`', k + 1)
                            if (close in (k + 1) until lineEnd) { out += Token(k, close + 1, TokenKind.String); k = close + 1; continue }
                        }
                        k++
                    }
                }
            }
            if (nl < 0) break
            lineStart = nl + 1
        }
        if (inFence) out += Token(fenceStart, text.length, TokenKind.String)
        return out
    }

    private fun isIdentStart(c: Char) = c.isLetter() || c == '_' || c == '$'
    private fun isIdent(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'
}
