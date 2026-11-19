package com.vesper.baubuild.shell

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vesper.baubuild.designsystem.BauColors

internal val GlassBarHeight = 64.dp
internal val BarBottomGap = 12.dp
internal val BarItemGap = 12.dp

private val SelectedTint = BauColors.BlueDeep
private val SelectedFill = BauColors.BlueDeep.copy(alpha = 0.12f)
private val IdleTint = BauColors.TextSecondary

private class TabSpec(val tab: AppTab, val label: String, val icon: ImageVector)

private val PrimaryTabs = listOf(
    TabSpec(AppTab.Projects, "Projects", TabIcons.Projects),
    TabSpec(AppTab.MyWork, "My Work", TabIcons.MyWork),
    TabSpec(AppTab.Review, "Review", TabIcons.Review),
    TabSpec(AppTab.Profile, "Profile", TabIcons.Profile),
)

/**
 * "Glass" surface echoing iOS Liquid Glass: translucent white with a top-lit sheen, hairline white border and a
 * soft ink shadow. No backdrop blur — Compose has no portable backdrop filter, and the shell keeps content above
 * the bar, so there is nothing to blur.
 */
internal fun Modifier.glassSurface(shape: Shape, elevation: Dp = 16.dp): Modifier = this
    .shadow(elevation, shape, ambientColor = BauColors.Ink.copy(alpha = 0.14f), spotColor = BauColors.Ink.copy(alpha = 0.14f))
    .clip(shape)
    .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.92f), Color.White.copy(alpha = 0.72f))))
    .border(1.dp, Color.White.copy(alpha = 0.85f), shape)

private val BarSpring = spring<Dp>(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow)
private val BarFade = tween<Float>(durationMillis = 180)

/**
 * Compact-window bottom bar, Apple Music style. Normally: floating pill with the four primary tabs + a round search
 * button on its right. While searching: the pill collapses into a round button on the left showing the tab search
 * will return to, and the search button stretches into a glass capsule holding [ShellSearchField].
 * Both halves keep the same total width ([pillWidth] + gap + one bar height), so the bar never jumps sideways.
 */
@Composable
internal fun GlassBottomBar(
    nav: ShellNavigation,
    pillWidth: Dp,
    onExitSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val searching = nav.isSearching
    val shape = RoundedCornerShape(GlassBarHeight / 2)
    val leftWidth by animateDpAsState(if (searching) GlassBarHeight else pillWidth, BarSpring, label = "pillWidth")
    val rightWidth by animateDpAsState(if (searching) pillWidth else GlassBarHeight, BarSpring, label = "searchWidth")

    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(leftWidth).height(GlassBarHeight).glassSurface(shape)) {
            Crossfade(searching, Modifier.fillMaxSize(), animationSpec = BarFade, label = "pillContent") { collapsed ->
                if (collapsed) {
                    val spec = PrimaryTabs.first { it.tab == nav.returnTab }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clickable(role = Role.Button, onClickLabel = "Close search", onClick = onExitSearch)
                            .semantics { contentDescription = "Close search, back to ${spec.label}" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(spec.icon, contentDescription = null, tint = BauColors.Ink, modifier = Modifier.size(24.dp))
                    }
                } else {
                    // Laid out at full pill width and clipped by the shrinking glass, so tabs don't squash mid-morph.
                    PrimaryTabsRow(
                        selected = nav.selected,
                        onSelect = nav::select,
                        modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true).width(pillWidth),
                    )
                }
            }
        }
        Spacer(Modifier.width(BarItemGap))
        Box(Modifier.width(rightWidth).height(GlassBarHeight).glassSurface(shape)) {
            Crossfade(searching, Modifier.fillMaxSize(), animationSpec = BarFade, label = "searchContent") { expanded ->
                if (expanded) {
                    ShellSearchField()
                } else {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .selectable(selected = false, onClick = nav::enterSearch, role = Role.Tab)
                            .semantics { contentDescription = "Search projects" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(TabIcons.Search, contentDescription = null, tint = BauColors.Ink, modifier = Modifier.size(24.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun PrimaryTabsRow(selected: AppTab, onSelect: (AppTab) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxHeight().padding(4.dp).selectableGroup()) {
        PrimaryTabs.forEach { spec ->
            TabItem(
                spec = spec,
                selected = selected == spec.tab,
                onClick = { onSelect(spec.tab) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

/** Wide-window search field shown above the content (the side rail has no bar to morph). */
@Composable
internal fun WideSearchBar(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .height(56.dp)
                .glassSurface(RoundedCornerShape(28.dp), elevation = 8.dp),
        ) { ShellSearchField() }
    }
}

@Composable
private fun TabItem(spec: TabSpec, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) =
    GlassTabItem(spec.label, spec.icon, selected, onClick, modifier)

/** One tab of a glass bar / rail: icon over a small label, blue tint + soft pill when selected. */
@Composable
internal fun GlassTabItem(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val fill by animateColorAsState(if (selected) SelectedFill else Color.Transparent, label = "tabFill")
    val tint by animateColorAsState(if (selected) SelectedTint else IdleTint, label = "tabTint")
    Column(
        modifier
            .clip(RoundedCornerShape(28.dp))
            .background(fill)
            .selectable(selected = selected, onClick = onClick, role = Role.Tab),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(3.dp))
        Text(label, color = tint, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Vertical glass rail for wide mobile-design windows (Android tablets ≥ 840dp). Desktop uses the portal sidebar. */
@Composable
internal fun GlassSideRail(selected: AppTab, onSelect: (AppTab) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(32.dp)
    Column(
        modifier
            .width(96.dp)
            .fillMaxHeight()
            .glassSurface(shape, elevation = 10.dp)
            .padding(vertical = 16.dp, horizontal = 8.dp)
            .selectableGroup(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BrandMark(size = 44.dp)
        Spacer(Modifier.height(24.dp))
        RailItem(TabSpec(AppTab.Search, "Search", TabIcons.Search), selected == AppTab.Search) { onSelect(AppTab.Search) }
        Spacer(Modifier.height(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryTabs.forEach { spec ->
                RailItem(spec, selected == spec.tab) { onSelect(spec.tab) }
            }
        }
    }
}

@Composable
private fun RailItem(spec: TabSpec, selected: Boolean, onClick: () -> Unit) {
    TabItem(
        spec = spec,
        selected = selected,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(64.dp),
    )
}

/** Brand mark: yellow rounded tile with an ink "B". */
@Composable
internal fun BrandMark(modifier: Modifier = Modifier, size: Dp = 72.dp) {
    Box(
        modifier
            .size(size)
            .shadow(size / 6, RoundedCornerShape(size * 0.28f), ambientColor = BauColors.Ink.copy(alpha = 0.12f), spotColor = BauColors.Ink.copy(alpha = 0.12f))
            .clip(RoundedCornerShape(size * 0.28f))
            .background(BauColors.Yellow),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "B",
            color = BauColors.Ink,
            fontSize = (size.value * 0.52f).sp,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center,
        )
    }
}
