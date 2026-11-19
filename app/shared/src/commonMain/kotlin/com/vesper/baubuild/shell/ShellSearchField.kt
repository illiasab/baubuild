package com.vesper.baubuild.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vesper.baubuild.designsystem.BauColors
import com.vesper.baubuild.feature.search.SearchQuery

/**
 * Search field drawn by the shell (inside the morphing bottom bar on compact windows, above the content on wide ones).
 * Two-way bound to [SearchQuery.text]: typing writes there, and outside writes (e.g. a recent search tapped in
 * SearchRoot) show up here with the cursor at the end. Fills its parent; the parent supplies the glass surface.
 */
@Composable
internal fun ShellSearchField(modifier: Modifier = Modifier, autoFocus: Boolean = true) {
    val query by SearchQuery.text.collectAsStateWithLifecycle()
    var local by remember { mutableStateOf(TextFieldValue(query, TextRange(query.length))) }
    // Text changed outside the field: adopt it (rendered immediately, stored on the next effect run).
    val value = if (local.text == query) local else TextFieldValue(query, TextRange(query.length))
    LaunchedEffect(value) { local = value }

    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    if (autoFocus) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Row(modifier.fillMaxSize().padding(start = 18.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(TabIcons.Search, contentDescription = null, tint = BauColors.TextSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.text.isEmpty()) {
                Text("Search projects", color = BauColors.TextTertiary, fontSize = 17.sp, maxLines = 1)
            }
            BasicTextField(
                value = value,
                onValueChange = {
                    local = it
                    SearchQuery.update(it.text)
                },
                modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "Search projects" },
                singleLine = true,
                textStyle = TextStyle(color = BauColors.Ink, fontSize = 17.sp),
                cursorBrush = SolidColor(BauColors.Blue),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            )
        }
        AnimatedVisibility(value.text.isNotEmpty(), enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClickLabel = "Clear search") { SearchQuery.clear() }
                    .semantics { contentDescription = "Clear search" },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(22.dp).clip(CircleShape).background(BauColors.TextTertiary.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(TabIcons.Clear, contentDescription = null, tint = BauColors.Ink, modifier = Modifier.size(12.dp))
                }
            }
        }
    }
}
