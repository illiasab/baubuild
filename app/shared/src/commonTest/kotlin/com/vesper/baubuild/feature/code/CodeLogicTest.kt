package com.vesper.baubuild.feature.code

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.vesper.baubuild.data.github.RepoEntry
import com.vesper.baubuild.data.github.RepoEntryType
import com.vesper.baubuild.feature.code.editor.EditorEditing
import com.vesper.baubuild.feature.code.editor.LanguageSpec
import com.vesper.baubuild.feature.code.editor.SyntaxTokenizer
import com.vesper.baubuild.feature.code.editor.TokenKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LineDiffTest {
    @Test fun identicalTextIsAllUnchanged() {
        val d = LineDiff.compute("a\nb\nc", "a\nb\nc")
        assertTrue(d.all { it.kind == DiffKind.Unchanged })
        assertEquals(listOf(1, 2, 3), d.map { it.newNumber })
    }

    @Test fun detectsAddedRemovedAndChangedLines() {
        val d = LineDiff.compute("a\nb\nc\nd", "a\nB\nc\nd\ne")
        assertEquals(
            listOf(
                DiffKind.Unchanged to "a", DiffKind.Removed to "b", DiffKind.Added to "B",
                DiffKind.Unchanged to "c", DiffKind.Unchanged to "d", DiffKind.Added to "e",
            ),
            d.map { it.kind to it.text },
        )
        val removed = d.first { it.kind == DiffKind.Removed }
        assertEquals(2, removed.oldNumber)
        assertEquals(null, removed.newNumber)
        assertEquals(5, d.last().newNumber)
        assertEquals(2 to 1, LineDiff.stats(d))
    }

    @Test fun lcsKeepsCommonLinesInTheMiddle() {
        val d = LineDiff.compute(listOf("x", "1", "2", "3", "y"), listOf("z", "1", "2", "3", "w"))
        assertEquals(3, d.count { it.kind == DiffKind.Unchanged })
        assertEquals(2, d.count { it.kind == DiffKind.Added })
        assertEquals(2, d.count { it.kind == DiffKind.Removed })
    }

    @Test fun emptySides() {
        assertTrue(LineDiff.compute("", "a\nb").all { it.kind == DiffKind.Added })
        assertTrue(LineDiff.compute("a\nb", "").all { it.kind == DiffKind.Removed })
        assertTrue(LineDiff.compute("", "").isEmpty())
    }

    @Test fun thousandLineFileWithSmallEditIsFast() {
        val old = (1..1000).map { "line $it" }
        val new = old.toMutableList().apply { set(500, "changed"); add(700, "inserted") }
        val d = LineDiff.compute(old, new)
        assertEquals(2 to 1, LineDiff.stats(d))
    }
}

class SyntaxTokenizerTest {
    private fun kinds(text: String, spec: LanguageSpec) =
        SyntaxTokenizer.tokenize(text, spec).map { text.substring(it.start, it.end) to it.kind }

    @Test fun javascriptKeywordsStringsNumbersComments() {
        val src = "const x = 42; // note\nif (s === 'a\\'b') return `t\n${'$'}{x}`; /* block */"
        val t = kinds(src, LanguageSpec.forPath("src/a.js"))
        assertTrue("const" to TokenKind.Keyword in t)
        assertTrue("42" to TokenKind.Number in t)
        assertTrue("// note" to TokenKind.Comment in t)
        assertTrue("if" to TokenKind.Keyword in t)
        assertTrue("'a\\'b'" to TokenKind.String in t)
        assertTrue("return" to TokenKind.Keyword in t)
        assertTrue(t.any { it.second == TokenKind.String && it.first.startsWith("`") && it.first.contains("\n") })
        assertTrue("/* block */" to TokenKind.Comment in t)
        assertTrue(t.none { it.first == "x" })
    }

    @Test fun identifiersWithDigitsAreNotNumbers() {
        val t = kinds("val a1 = b2 + 0x1F + 3.14", LanguageSpec.forPath("A.kt"))
        assertEquals(listOf("val" to TokenKind.Keyword, "0x1F" to TokenKind.Number, "3.14" to TokenKind.Number), t)
    }

    @Test fun pythonHashCommentsAndTripleQuotes() {
        val t = kinds("def f():\n    \"\"\"doc\nmore\"\"\"\n    return None  # done", LanguageSpec.forPath("x.py"))
        assertTrue("def" to TokenKind.Keyword in t)
        assertTrue("\"\"\"doc\nmore\"\"\"" to TokenKind.String in t)
        assertTrue("None" to TokenKind.Keyword in t)
        assertTrue("# done" to TokenKind.Comment in t)
    }

    @Test fun unterminatedStringStopsAtLineEnd() {
        val t = kinds("x = \"open\ny = 1", LanguageSpec.forPath("a.ts"))
        assertTrue("\"open" to TokenKind.String in t)
        assertTrue("1" to TokenKind.Number in t)
    }

    @Test fun markdownAndJsonAndPlainText() {
        val md = kinds("# Title\nUse `npm test`.\n```\ncode\n```", LanguageSpec.forPath("README.md"))
        assertTrue("# Title" to TokenKind.Keyword in md)
        assertTrue("`npm test`" to TokenKind.String in md)
        assertTrue(md.any { it.second == TokenKind.String && it.first.contains("code") })
        val json = kinds("{\"a\": true, \"b\": 12}", LanguageSpec.forPath("p.json"))
        assertEquals(listOf("\"a\"" to TokenKind.String, "true" to TokenKind.Keyword, "\"b\"" to TokenKind.String, "12" to TokenKind.Number), json)
        assertTrue(kinds("const 1 'x'", LanguageSpec.forPath("notes.txt")).none { it.second != TokenKind.Number })
    }

    @Test fun languageByExtension() {
        assertEquals("TypeScript", LanguageSpec.forPath("a.tsx").name)
        assertEquals("Swift", LanguageSpec.forPath("A.swift").name)
        assertEquals("Go", LanguageSpec.forPath("main.go").name)
        assertEquals("Java", LanguageSpec.forPath("A.java").name)
        assertEquals("CSS", LanguageSpec.forPath("a.css").name)
        assertEquals("Plain text", LanguageSpec.forPath("Makefile").name)
    }
}

class EditorEditingTest {
    private fun typed(old: String, caret: Int, ch: Char): Pair<TextFieldValue, TextFieldValue> {
        val before = TextFieldValue(old, TextRange(caret))
        val after = TextFieldValue(old.substring(0, caret) + ch + old.substring(caret), TextRange(caret + 1))
        return before to after
    }

    @Test fun newlineKeepsIndent() {
        val (a, b) = typed("    foo();", 10, '\n')
        val r = EditorEditing.autoIndent(a, b)
        assertEquals("    foo();\n    ", r.text)
        assertEquals(TextRange(15), r.selection)
    }

    @Test fun newlineAfterBraceIndentsOneMore() {
        val (a, b) = typed("  if (x) {", 10, '\n')
        assertEquals("  if (x) {\n    ", EditorEditing.autoIndent(a, b).text)
    }

    @Test fun otherEditsAreUntouched() {
        val (a, b) = typed("  foo", 5, 'x')
        assertEquals(b, EditorEditing.autoIndent(a, b))
        val (c, d) = typed("foo", 3, '\n')
        assertEquals(d, EditorEditing.autoIndent(c, d))
        // Paste of several chars incl. newline: untouched.
        val pasted = TextFieldValue("  a\nb\n", TextRange(7))
        assertEquals(pasted, EditorEditing.autoIndent(TextFieldValue("  a", TextRange(3)), pasted))
    }

    @Test fun tabInsertsTwoSpacesReplacingSelection() {
        assertEquals(TextFieldValue("a  b", TextRange(3)), EditorEditing.insertTab(TextFieldValue("ab", TextRange(1))))
        assertEquals(TextFieldValue("  c", TextRange(2)), EditorEditing.insertTab(TextFieldValue("abc", TextRange(0, 2))))
        assertEquals(2, EditorEditing.lineOf("a\nb\nc", 4))
    }
}

class CodeTreeTest {
    private val entries = listOf(
        RepoEntry("README.md", RepoEntryType.File),
        RepoEntry("src", RepoEntryType.Directory),
        RepoEntry("src/b.js", RepoEntryType.File),
        RepoEntry("src/util", RepoEntryType.Directory),
        RepoEntry("src/util/a.js", RepoEntryType.File),
        RepoEntry("assets", RepoEntryType.Directory),
    )

    @Test fun foldersFirstAndCollapsed() {
        assertEquals(listOf("assets", "src", "README.md"), CodeTree.visibleRows(entries, emptySet()).map { it.path })
        assertEquals(
            listOf("assets", "src", "src/util", "src/b.js", "README.md"),
            CodeTree.visibleRows(entries, setOf("src")).map { it.path },
        )
        assertEquals(
            listOf("assets", "src", "src/util", "src/util/a.js", "src/b.js", "README.md"),
            CodeTree.visibleRows(entries, setOf("src", "src/util")).map { it.path },
        )
    }

    @Test fun defaultExpandedOpensTopLevelAndPathsToTabs() {
        assertEquals(setOf("src", "assets", "src/util"), CodeTree.defaultExpanded(entries, listOf("src/util/a.js")))
    }
}
