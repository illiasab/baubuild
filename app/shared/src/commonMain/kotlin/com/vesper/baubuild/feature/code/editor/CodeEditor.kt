package com.vesper.baubuild.feature.code.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vesper.baubuild.designsystem.BauColors
import com.vesper.baubuild.designsystem.MonoFamily

/** Editor palette (light, matches the app's Paper/Ink design). */
object CodeColors {
    val Background = Color(0xFFFFFFFF)
    val Gutter = Color(0xFFF7F8F3)
    val GutterText = Color(0xFF8A93A6)
    val CurrentLine = Color(0xFFF1F4FF)
    val Text = BauColors.Ink
    val Keyword = Color(0xFF2B50E0)
    val String = Color(0xFF17803D)
    val Number = Color(0xFFB4530A)
    val Comment = Color(0xFF8A93A6)
    val Added = Color(0xFFE6F6EA)
    val AddedInk = Color(0xFF17803D)
    val Removed = Color(0xFFFDE8E8)
    val RemovedInk = Color(0xFFB42318)
}

fun codeTextStyle(fontSize: TextUnit = 13.sp, lineHeight: TextUnit = 20.sp) = TextStyle(
    fontFamily = MonoFamily,
    fontSize = fontSize,
    lineHeight = lineHeight,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    color = CodeColors.Text,
)

/** Builds the highlighted text. Linear in the text length. */
fun highlight(text: String, spec: LanguageSpec): AnnotatedString {
    if (spec === LanguageSpec.PlainText) return AnnotatedString(text)
    val tokens = SyntaxTokenizer.tokenize(text, spec)
    val builder = AnnotatedString.Builder(text)
    for (t in tokens) {
        val style = when (t.kind) {
            TokenKind.Keyword -> SpanStyle(color = CodeColors.Keyword, fontWeight = FontWeight.SemiBold)
            TokenKind.String -> SpanStyle(color = CodeColors.String)
            TokenKind.Number -> SpanStyle(color = CodeColors.Number)
            TokenKind.Comment -> SpanStyle(color = CodeColors.Comment, fontStyle = FontStyle.Italic)
        }
        builder.addStyle(style, t.start, t.end)
    }
    return builder.toAnnotatedString()
}

/**
 * Highlighting as a [VisualTransformation] (identity offsets). Caches the last result, so recompositions that don't
 * change the text (caret moves, focus, scroll) do no work; a keystroke costs one linear tokenizer pass.
 */
private class SyntaxTransformation(private val spec: LanguageSpec) : VisualTransformation {
    private var lastText: String? = null
    private var lastResult: AnnotatedString = AnnotatedString("")

    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        if (raw != lastText) {
            lastResult = highlight(raw, spec)
            lastText = raw
        }
        return TransformedText(lastResult, OffsetMapping.Identity)
    }
}

/**
 * Holds the editor's text + selection. Created per file and [revision] (see [rememberCodeEditorState]) so external
 * resets (discard) replace the text while typing never round-trips through the ViewModel.
 */
@Stable
class CodeEditorState(initial: String) {
    var value by mutableStateOf(TextFieldValue(initial))
        internal set

    /** Applies an edit coming from outside the text field (symbol bar). */
    fun apply(transform: (TextFieldValue) -> TextFieldValue, onTextChange: (String) -> Unit) {
        val next = transform(value)
        val changed = next.text != value.text
        value = next
        if (changed) onTextChange(next.text)
    }
}

@Composable
fun rememberCodeEditorState(path: String, revision: Int, text: String): CodeEditorState =
    remember(path, revision) { CodeEditorState(text) }

/**
 * Code editor: monospace text, line-number gutter, horizontal scroll (no soft wrap), current-line highlight,
 * syntax highlighting, Tab → two spaces, Enter → keeps the previous line's indent.
 */
@Composable
fun CodeEditor(
    state: CodeEditorState,
    path: String,
    onTextChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
    fontSize: TextUnit = 13.sp,
    lineHeight: TextUnit = 20.sp,
    focusRequester: FocusRequester? = null,
    onFocusChanged: (Boolean) -> Unit = {},
) {
    val spec = remember(path) { LanguageSpec.forPath(path) }
    val transformation = remember(spec) { SyntaxTransformation(spec) }
    val style = remember(fontSize, lineHeight) { codeTextStyle(fontSize, lineHeight) }
    val value = state.value
    val lineCount = remember(value.text) { value.text.count { it == '\n' } + 1 }
    val gutterText = remember(lineCount) { (1..lineCount).joinToString("\n") }
    val gutterWidth = remember(lineCount) { lineCount.toString().length.coerceAtLeast(2) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val density = LocalDensity.current
    val verticalPadding = 12.dp

    BoxWithConstraints(modifier.background(CodeColors.Background).semantics { contentDescription = "Code editor, $path" }) {
        val viewportWidth = maxWidth
        val viewportHeight = maxHeight
        Row(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .drawBehind {
                    val l = layout ?: return@drawBehind
                    val caret = value.selection.end.coerceIn(0, value.text.length)
                    val line = l.getLineForOffset(caret)
                    val top = l.getLineTop(line) + with(density) { verticalPadding.toPx() }
                    drawRect(CodeColors.CurrentLine, Offset(0f, top), Size(size.width, l.getLineBottom(line) - l.getLineTop(line)))
                },
        ) {
            Text(
                gutterText,
                modifier = Modifier
                    .background(CodeColors.Gutter.copy(alpha = 0.6f))
                    .padding(start = 10.dp, end = 10.dp, top = verticalPadding, bottom = verticalPadding)
                    .widthIn(min = (gutterWidth * 8).dp),
                style = style.copy(color = CodeColors.GutterText, textAlign = TextAlign.End),
                softWrap = false,
            )
            Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(rememberScrollState())) {
                BasicTextField(
                    value = value,
                    onValueChange = { next ->
                        val indented = EditorEditing.autoIndent(state.value, next)
                        val changed = indented.text != state.value.text
                        state.value = indented
                        if (changed) onTextChange(indented.text)
                    },
                    readOnly = readOnly,
                    textStyle = style,
                    visualTransformation = transformation,
                    onTextLayout = { layout = it },
                    cursorBrush = SolidColor(BauColors.BlueDeep),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Ascii,
                    ),
                    modifier = Modifier
                        .defaultMinSize(
                            minWidth = (viewportWidth - (gutterWidth * 8 + 20).dp - 44.dp).coerceAtLeast(0.dp),
                            minHeight = (viewportHeight - verticalPadding * 2).coerceAtLeast(0.dp),
                        )
                        .padding(start = 12.dp, end = 32.dp, top = verticalPadding, bottom = verticalPadding)
                        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                        .onFocusChanged { onFocusChanged(it.isFocused) }
                        .onPreviewKeyEvent { e ->
                            if (readOnly || e.key != Key.Tab || e.isShiftPressed || e.isCtrlPressed || e.isMetaPressed || e.isAltPressed) {
                                return@onPreviewKeyEvent false
                            }
                            if (e.type == KeyEventType.KeyDown) state.apply(EditorEditing::insertTab, onTextChange)
                            true // also swallow KeyUp so focus doesn't move
                        },
                )
            }
        }
    }
}
