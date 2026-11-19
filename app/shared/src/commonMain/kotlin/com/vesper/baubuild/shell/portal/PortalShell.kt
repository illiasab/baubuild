package com.vesper.baubuild.shell.portal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vesper.baubuild.core.model.ScoreTier
import com.vesper.baubuild.core.model.User
import com.vesper.baubuild.core.session.SessionState
import com.vesper.baubuild.designsystem.BauColors
import com.vesper.baubuild.designsystem.DisplayFamily
import com.vesper.baubuild.designsystem.UserAvatar
import com.vesper.baubuild.di.AppGraph
import com.vesper.baubuild.feature.mywork.portal.MyWorkPortalPage
import com.vesper.baubuild.feature.profile.portal.ProfilePortalPage
import com.vesper.baubuild.feature.projects.portal.ProjectsPortalPage
import com.vesper.baubuild.feature.review.portal.ReviewPortalPage
import com.vesper.baubuild.feature.search.SearchQuery
import com.vesper.baubuild.feature.search.SearchRoot
import com.vesper.baubuild.shell.TabIcons

/** Sections of the Web portal sidebar, in sidebar order (Ctrl/⌘+1..4). */
internal enum class PortalSection(val label: String) {
    Projects("Projects"), MyWork("My Work"), Review("Review"), Profile("Profile");

    val icon: ImageVector
        get() = when (this) {
            Projects -> TabIcons.Projects
            MyWork -> TabIcons.MyWork
            Review -> TabIcons.Review
            Profile -> TabIcons.Profile
        }
}

// Colours from the Web portal design (docs__Portal_*.html sidebar).
internal object PortalColors {
    val Sidebar = BauColors.Ink                 // #0D1220
    val SelectedFill = Color(0xFF1E2848)
    val IdleText = Color(0xFFC9D1E2)
    val Card = Color(0xFF151B2E)
    val CardBorder = Color(0xFF2C3757)
}

internal val SidebarWidth = 260.dp
private const val SearchKey = "Search"

/**
 * Signed-in desktop layout in the Web portal design: dark 260dp sidebar (brand, search, nav, user card) and the
 * selected page to the right. Each page keeps its saveable state across section switches.
 *
 * Search: the sidebar field is two-way bound to [SearchQuery.text]; while it is focused or has text the content
 * area shows [SearchRoot]. Esc, or picking a section, clears the query and leaves search.
 */
@Composable
internal fun PortalShell() {
    val session by AppGraph.current.session.state.collectAsStateWithLifecycle()
    val user = (session as? SessionState.SignedIn)?.user

    var selectedName by rememberSaveable { mutableStateOf(PortalSection.Projects.name) }
    val selected = PortalSection.valueOf(selectedName)
    val query by SearchQuery.text.collectAsStateWithLifecycle()
    var searchFocused by remember { mutableStateOf(false) }
    val searching = searchFocused || query.isNotEmpty()

    val pageStates = rememberSaveableStateHolder()
    val focusManager = LocalFocusManager.current
    val searchFocus = remember { FocusRequester() }

    val exitSearch: () -> Unit = {
        SearchQuery.clear()
        focusManager.clearFocus()
    }
    val select: (PortalSection) -> Unit = { section ->
        if (searching) exitSearch()
        selectedName = section.name
    }

    val currentSearching by rememberUpdatedState(searching)
    val currentSelect by rememberUpdatedState(select)
    val currentExit by rememberUpdatedState(exitSearch)
    DisposableEffect(Unit) {
        PortalKeyboard.previewHandler = handler@{ event ->
            if (event.type != KeyEventType.KeyDown) return@handler false
            if (!(event.isMetaPressed || event.isCtrlPressed) || event.isAltPressed) return@handler false
            val section = when (event.key) {
                Key.One, Key.NumPad1 -> PortalSection.Projects
                Key.Two, Key.NumPad2 -> PortalSection.MyWork
                Key.Three, Key.NumPad3 -> PortalSection.Review
                Key.Four, Key.NumPad4 -> PortalSection.Profile
                Key.K -> {
                    runCatching { searchFocus.requestFocus() }
                    return@handler true
                }
                else -> return@handler false
            }
            currentSelect(section)
            true
        }
        PortalKeyboard.fallbackHandler = handler@{ event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape && currentSearching) {
                currentExit()
                true
            } else false
        }
        onDispose {
            PortalKeyboard.previewHandler = null
            PortalKeyboard.fallbackHandler = null
        }
    }

    Row(Modifier.fillMaxSize().background(BauColors.Paper)) {
        PortalSidebar(
            selected = selected,
            searching = searching,
            user = user,
            onSelect = select,
            searchField = {
                PortalSearchField(
                    focusRequester = searchFocus,
                    onFocusChanged = { searchFocused = it },
                    onEscape = exitSearch,
                )
            },
        )
        Box(Modifier.weight(1f).fillMaxHeight()) {
            if (searching) {
                pageStates.SaveableStateProvider(SearchKey) {
                    // SearchRoot is laid out for phone widths; keep it to a readable column on wide windows.
                    Box(Modifier.fillMaxSize().background(BauColors.Paper), contentAlignment = Alignment.TopCenter) {
                        SearchRoot(Modifier.widthIn(max = 880.dp).fillMaxSize().padding(horizontal = 12.dp, vertical = 12.dp))
                    }
                }
            } else {
                pageStates.SaveableStateProvider(selected.name) {
                    when (selected) {
                        PortalSection.Projects -> ProjectsPortalPage()
                        // Pending (waiting-for-approval) rows link back to the project on the Projects page.
                        PortalSection.MyWork -> MyWorkPortalPage(onOpenProject = { select(PortalSection.Projects) })
                        PortalSection.Review -> ReviewPortalPage()
                        PortalSection.Profile -> ProfilePortalPage()
                    }
                }
            }
        }
    }
}

@Composable
private fun PortalSidebar(
    selected: PortalSection,
    searching: Boolean,
    user: User?,
    onSelect: (PortalSection) -> Unit,
    searchField: @Composable () -> Unit,
) {
    Column(
        Modifier
            .width(SidebarWidth)
            .fillMaxHeight()
            .background(PortalColors.Sidebar)
            .padding(horizontal = 16.dp, vertical = 24.dp),
    ) {
        BrandRow()
        Spacer(Modifier.height(20.dp))
        searchField()
        Spacer(Modifier.height(20.dp))
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PortalSection.entries.forEachIndexed { index, section ->
                NavItem(
                    section = section,
                    // While search owns the content area no section is shown as current.
                    selected = !searching && section == selected,
                    shortcut = shortcutLabel("${index + 1}"),
                    onClick = { onSelect(section) },
                )
            }
        }
        Spacer(Modifier.weight(1f))
        if (user != null) UserCard(user, onClick = { onSelect(PortalSection.Profile) })
    }
}

@Composable
internal fun BrandRow() {
    Row(
        Modifier.heightIn(min = 44.dp).padding(horizontal = 8.dp).semantics { contentDescription = "BAU BUILD" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BrandBlocks(Modifier.size(28.dp))
        Text(
            "BAU BUILD",
            color = Color.White,
            fontFamily = DisplayFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            letterSpacing = 0.72.sp, // 0.04em
        )
    }
}

/** The portal's 28×28 brand glyph: two solid yellow blocks and one dashed outline block. */
@Composable
private fun BrandBlocks(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val u = size.width / 28f
        val yellow = BauColors.Yellow
        drawRect(yellow, Offset(2 * u, 16 * u), Size(24 * u, 9 * u))
        drawRect(yellow, Offset(2 * u, 4 * u), Size(11 * u, 9 * u))
        drawRect(
            yellow,
            Offset(15.75f * u, 4.75f * u),
            Size(9.5f * u, 7.5f * u),
            style = Stroke(width = 1.5f * u, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.5f * u, 2f * u))),
        )
    }
}

@Composable
private fun NavItem(section: PortalSection, selected: Boolean, shortcut: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val tint = when {
        selected || hovered -> BauColors.Yellow
        else -> PortalColors.IdleText
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) PortalColors.SelectedFill else Color.Transparent)
            .hoverable(interaction)
            .selectable(selected = selected, interactionSource = interaction, indication = null, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(section.icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text(
            section.label,
            color = tint,
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        if (hovered) Text(shortcut, color = PortalColors.IdleText.copy(alpha = 0.6f), fontSize = 12.sp)
    }
}

@Composable
private fun UserCard(user: User, onClick: () -> Unit) {
    val tier = ScoreTier.of(user.score)
    val tierName = tier.label.lowercase().replaceFirstChar { it.uppercase() }
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(PortalColors.Card)
            .border(1.dp, PortalColors.CardBorder, shape)
            .clickable(role = Role.Button, onClickLabel = "Open profile", onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        UserAvatar(user, size = 36.dp)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                user.displayName,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text("Score ${user.score} · $tierName", color = PortalColors.IdleText, fontSize = 12.sp, maxLines = 1)
        }
    }
}

/**
 * Compact dark search field for the sidebar. Two-way bound to [SearchQuery.text] (outside writes such as a recent
 * search tapped in [SearchRoot] appear here with the cursor at the end). Esc inside the field leaves search.
 */
@Composable
private fun PortalSearchField(
    focusRequester: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    onEscape: () -> Unit,
) {
    val query by SearchQuery.text.collectAsStateWithLifecycle()
    var local by remember { mutableStateOf(TextFieldValue(query, TextRange(query.length))) }
    val value = if (local.text == query) local else TextFieldValue(query, TextRange(query.length))
    LaunchedEffect(value) { local = value }
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(shape)
            .background(PortalColors.Card)
            .border(1.dp, if (focused) BauColors.Blue else PortalColors.CardBorder, shape)
            .padding(start = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(TabIcons.Search, contentDescription = null, tint = PortalColors.IdleText, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.text.isEmpty()) {
                Text("Search projects", color = PortalColors.IdleText.copy(alpha = 0.7f), fontSize = 14.sp, maxLines = 1)
            }
            BasicTextField(
                value = value,
                onValueChange = {
                    local = it
                    SearchQuery.update(it.text)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged {
                        focused = it.isFocused
                        onFocusChanged(it.isFocused)
                    }
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                            onEscape()
                            true
                        } else false
                    }
                    .semantics { contentDescription = "Search projects" },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                cursorBrush = SolidColor(BauColors.Yellow),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            )
        }
        if (value.text.isNotEmpty()) {
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClickLabel = "Clear search") { SearchQuery.clear() }
                    .semantics { contentDescription = "Clear search" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(TabIcons.Clear, contentDescription = null, tint = PortalColors.IdleText, modifier = Modifier.size(10.dp))
            }
        } else if (!focused) {
            ShortcutHint(shortcutLabel("K"))
        }
    }
}

@Composable
private fun ShortcutHint(text: String) {
    Text(
        text,
        color = PortalColors.IdleText.copy(alpha = 0.7f),
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .padding(end = 4.dp)
            .border(1.dp, PortalColors.CardBorder, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** "⌘1" on macOS, "Ctrl+1" elsewhere. */
internal fun shortcutLabel(key: String): String =
    PortalKeyboard.modifierLabel.let { if (it == "⌘") "$it$key" else "$it+$key" }
