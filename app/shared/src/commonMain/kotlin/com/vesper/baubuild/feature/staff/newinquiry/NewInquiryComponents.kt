package com.vesper.baubuild.feature.staff.newinquiry

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vesper.baubuild.core.model.Difficulty
import com.vesper.baubuild.core.model.InquiryDraft
import com.vesper.baubuild.core.model.InquiryPriority
import com.vesper.baubuild.core.model.ProjectFor
import com.vesper.baubuild.core.model.ProjectType
import com.vesper.baubuild.core.model.RefinedInquiry
import com.vesper.baubuild.core.model.RewardType
import com.vesper.baubuild.designsystem.BauColors
import com.vesper.baubuild.designsystem.BauSpacing
import com.vesper.baubuild.designsystem.BauTextLink
import com.vesper.baubuild.feature.auth.rememberPrefersReducedMotion
import kotlinx.coroutines.delay

/*
 * Reusable pieces of the New Inquiry flow. The mobile flow (NewInquiryFlow.kt) shows them one step per screen; the
 * desktop portal can put [InquiryDetailsFields] + [RewardTimingFields] on one page and show [RefinedInquiryCard]
 * next to [WhatYouWroteCard] (collapsible = false).
 */

// ---------------------------------------------------------------- step 1

/** Project Title, Project Explanation, What You Expect, Project Type. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InquiryDetailsFields(state: NewInquiryUiState, vm: NewInquiryViewModel, modifier: Modifier = Modifier) {
    val enabled = !state.busy
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        InquiryTextField(
            label = "Project Title", value = state.form.title, onValueChange = vm::onTitleChange,
            placeholder = "e.g. Lab equipment booking app", enabled = enabled, error = state.titleError,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
        )
        InquiryTextField(
            label = "Project Explanation", value = state.form.explanation, onValueChange = vm::onExplanationChange,
            placeholder = "What is the problem, and who has it?", enabled = enabled, error = state.explanationError,
            singleLine = false,
        )
        InquiryTextField(
            label = "What You Expect", value = state.form.expectation, onValueChange = vm::onExpectationChange,
            placeholder = "What does done look like? Any must-haves?", enabled = enabled, singleLine = false,
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LabelRow("Project Type", trailing = "Tick all that apply")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ProjectType.entries.forEach { type ->
                    CheckChip(type.label, checked = type in state.form.types, enabled = enabled, onToggle = { vm.toggleType(type) })
                }
            }
            state.typesError?.let { ErrorHint(it) }
        }
    }
}

// ---------------------------------------------------------------- step 2

/** Project For, Class Code, Reward (+ Price / School Discount), Priority, Deadline, Difficulty. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RewardTimingFields(state: NewInquiryUiState, vm: NewInquiryViewModel, modifier: Modifier = Modifier) {
    val enabled = !state.busy
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FieldLabel("Project For")
            GlassSegmented(
                options = ProjectFor.entries, selected = state.form.projectFor, label = { it.label },
                enabled = enabled, onSelect = vm::setProjectFor,
            )
        }
        AnimatedVisibility(state.showsClassCode) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InquiryTextField(
                    label = "Class Code", value = state.form.classCode, onValueChange = vm::onClassCodeChange,
                    placeholder = "e.g. CMPS433", enabled = enabled, error = state.classCodeError,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done,
                    ),
                )
                val suggestions = state.visibleClassSuggestions
                if (suggestions.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        suggestions.forEach { code ->
                            SuggestionChip(code, enabled = enabled, onClick = { vm.onClassCodeChange(code) })
                        }
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LabelRow("Reward", trailing = "Tick one or more")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RewardType.entries.forEach { reward ->
                    CheckChip(reward.label, checked = reward in state.form.rewards, enabled = enabled, onToggle = { vm.toggleReward(reward) })
                }
            }
            state.rewardsError?.let { ErrorHint(it) }
        }
        if (state.showsPrice || state.showsDiscount) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.showsPrice) {
                    InquiryTextField(
                        label = "Price (USD)", value = state.form.price, onValueChange = vm::onPriceChange,
                        placeholder = "18", enabled = enabled, error = state.priceError, modifier = Modifier.weight(1f),
                        prefix = "$",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    )
                }
                if (state.showsDiscount) {
                    InquiryTextField(
                        label = "School Discount", value = state.form.discount, onValueChange = vm::onDiscountChange,
                        placeholder = "e.g. 5", enabled = enabled, error = state.discountError, modifier = Modifier.weight(1f),
                        suffix = "%",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    )
                }
                if (!(state.showsPrice && state.showsDiscount)) Spacer(Modifier.weight(1f))
            }
        }
        TimingCard(state, vm, enabled)
        state.deadlineError?.let { ErrorHint(it) }
    }
}

@Composable
private fun TimingCard(state: NewInquiryUiState, vm: NewInquiryViewModel, enabled: Boolean) {
    var pickingDate by remember { mutableStateOf(false) }
    var pickingDifficulty by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(24.dp)
    Column(
        Modifier.fillMaxWidth()
            .shadow(6.dp, shape, ambientColor = BauColors.Ink.copy(alpha = 0.06f), spotColor = BauColors.Ink.copy(alpha = 0.06f))
            .clip(shape).background(BauColors.Surface).border(1.dp, BauColors.Line, shape),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Priority", Modifier.weight(1f), fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-0.34).sp, color = BauColors.Ink)
            InsetSegmented(
                options = InquiryPriority.entries, selected = state.form.priority, label = { it.name },
                enabled = enabled, onSelect = vm::setPriority,
            )
        }
        Divider()
        val deadlineText = InquiryDates.long(state.form.deadline) ?: state.form.deadline
        SettingsRow(
            title = "Deadline", value = deadlineText ?: "Choose", valueMuted = deadlineText == null,
            enabled = enabled, onClick = { pickingDate = true }, error = state.deadlineError != null,
        )
        Divider()
        Box {
            SettingsRow(
                title = "Difficulty", value = state.form.difficulty?.display ?: "Let AI suggest", valueMuted = false,
                enabled = enabled, onClick = { pickingDifficulty = true },
            )
            DropdownMenu(
                expanded = pickingDifficulty, onDismissRequest = { pickingDifficulty = false },
                containerColor = BauColors.Surface,
            ) {
                (listOf<Difficulty?>(null) + Difficulty.entries).forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                option?.display ?: "Let AI suggest", fontSize = 16.sp, color = BauColors.Ink,
                                fontWeight = if (option == state.form.difficulty) FontWeight.Bold else FontWeight.Normal,
                            )
                        },
                        onClick = { vm.setDifficulty(option); pickingDifficulty = false },
                        trailingIcon = if (option == state.form.difficulty) {
                            { PathIcon(TICK, 16.dp, BauColors.BlueDeep, 2.4f, viewBox = 16f) }
                        } else null,
                    )
                }
            }
        }
    }
    if (pickingDate) {
        DeadlinePickerDialog(
            initialIso = state.form.deadline, todayEpochDay = state.todayEpochDay,
            onPick = { vm.setDeadlineEpochDay(it); pickingDate = false },
            onDismiss = { pickingDate = false },
        )
    }
}

/** Material3 date picker limited to future days. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeadlinePickerDialog(initialIso: String?, todayEpochDay: Long, onPick: (epochDay: Long) -> Unit, onDismiss: () -> Unit) {
    val initialDay = initialIso?.let(InquiryDates::isoToEpochDay)?.takeIf { it > todayEpochDay }
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = initialDay?.times(InquiryDates.MILLIS_PER_DAY),
        selectableDates = remember(todayEpochDay) {
            object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    utcTimeMillis.floorDiv(InquiryDates.MILLIS_PER_DAY) > todayEpochDay
            }
        },
    )
    val colors = DatePickerDefaults.colors(
        containerColor = BauColors.Surface,
        selectedDayContainerColor = BauColors.Ink, selectedDayContentColor = Color.White,
        todayDateBorderColor = BauColors.Blue, todayContentColor = BauColors.BlueDeep,
        selectedYearContainerColor = BauColors.Ink, selectedYearContentColor = Color.White,
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            BauTextLink(
                "Done", enabled = pickerState.selectedDateMillis != null, color = BauColors.BlueDeep,
                onClick = { pickerState.selectedDateMillis?.let { onPick(it.floorDiv(InquiryDates.MILLIS_PER_DAY)) } },
                modifier = Modifier.padding(end = 8.dp),
            )
        },
        dismissButton = { BauTextLink("Cancel", onClick = onDismiss, color = BauColors.TextSecondary, fontWeight = FontWeight.Medium) },
        colors = colors,
    ) {
        DatePicker(state = pickerState, colors = colors, title = {
            Text("Deadline", Modifier.padding(start = 24.dp, top = 16.dp), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = BauColors.TextSecondary)
        })
    }
}

// ---------------------------------------------------------------- refined

/** The AI's version: "Class Project · CMPS433" + reward, title, brief, Done Looks Like, tags. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RefinedInquiryCard(refined: RefinedInquiry, draft: InquiryDraft, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier.fillMaxWidth()
            .shadow(6.dp, shape, ambientColor = BauColors.Ink.copy(alpha = 0.06f), spotColor = BauColors.Ink.copy(alpha = 0.06f))
            .clip(shape).background(BauColors.Surface).border(1.dp, BauColors.Line, shape)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                draft.headline, Modifier.weight(1f), fontSize = 13.sp, lineHeight = 18.sp,
                fontWeight = FontWeight.SemiBold, color = BauColors.BlueDeep,
            )
            Text(refined.rewardSummary, fontSize = 22.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold, color = BauColors.Ink, textAlign = TextAlign.End)
        }
        Text(
            refined.title, Modifier.semantics { heading() },
            fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp, color = BauColors.Ink,
        )
        Text(refined.brief, fontSize = 15.sp, lineHeight = 20.sp, color = BriefInk)
        if (refined.doneLooksLike.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Done Looks Like", fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, color = BauColors.TextSecondary)
                refined.doneLooksLike.forEach { item ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PathIcon(DONE_TICK, 16.dp, BauColors.BlueDeep, 2.2f, viewBox = 18f, modifier = Modifier.padding(top = 2.dp))
                        Text(item, fontSize = 15.sp, lineHeight = 20.sp, color = BauColors.Ink)
                    }
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            refined.tags().forEach { Tag(it) }
        }
    }
}

/** "What You Wrote": the staff member's original title / explanation / expectation. */
@Composable
fun WhatYouWroteCard(draft: InquiryDraft, modifier: Modifier = Modifier, collapsible: Boolean = true) {
    var expanded by rememberSaveable { mutableStateOf(!collapsible) }
    val shape = RoundedCornerShape(20.dp)
    val chevronAngle by animateFloatAsState(if (expanded) 90f else 0f)
    Column(modifier.fillMaxWidth().clip(shape).background(BauColors.Surface).border(1.dp, BauColors.Line, shape)) {
        val header = Modifier.fillMaxWidth().heightIn(min = 52.dp)
        Row(
            if (collapsible) {
                header.toggleable(value = expanded, role = Role.Button, onValueChange = { expanded = it })
                    .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
            } else header,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "What You Wrote", Modifier.weight(1f).padding(start = 16.dp),
                fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold, color = BauColors.Ink,
            )
            if (collapsible) {
                PathIcon(CHEVRON, 16.dp, BauColors.TextTertiary, 2.2f, modifier = Modifier.padding(end = 16.dp).rotate(chevronAngle))
            }
        }
        AnimatedVisibility(expanded) {
            Column(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                WroteItem("Title", draft.title)
                WroteItem("Explanation", draft.explanation)
                WroteItem("Expectation", draft.expectation)
            }
        }
    }
}

@Composable
private fun WroteItem(label: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, color = BauColors.TextTertiary)
        Text(text.ifBlank { "—" }, fontSize = 15.sp, lineHeight = 20.sp, color = BauColors.TextSecondary)
    }
}

/** Blue note: "What AI Changed" + explanation. */
@Composable
fun WhatAiChangedNote(text: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(BauColors.BlueSoft)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PathIcon(SPARKLE, 20.dp, BauColors.Ink, 1.8f)
            Text("What AI Changed", fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold, color = BauColors.Ink)
        }
        Text(text, fontSize = 14.sp, lineHeight = 19.sp, color = NoteInk)
    }
}

/** Bell + "When it is done, Ilias and Arian, the senior engineers, are notified." */
@Composable
fun NotifyOnDoneNote(who: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PathIcon(BELL, 20.dp, BauColors.TextSecondary, 1.8f)
        Text("When it is done, $who, are notified.", fontSize = 13.sp, lineHeight = 18.sp, color = BauColors.TextSecondary)
    }
}

// ---------------------------------------------------------------- AI refining

private val RefiningHints = listOf(
    "Rewriting the title in plain language…",
    "Turning your expectations into acceptance checks…",
    "Suggesting a difficulty and minimum score…",
    "Checking the reward and deadline…",
)

/** Pulsing sparkle + "AI is refining your inquiry…" + rotating hints. Static when the OS asks for reduced motion. */
@Composable
fun AiRefiningIndicator(modifier: Modifier = Modifier) {
    val reduced = rememberPrefersReducedMotion()
    var hint by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_200)
            hint = (hint + 1) % RefiningHints.size
        }
    }
    val transition = rememberInfiniteTransition()
    val pulse by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(1_400, easing = FastOutSlowInEasing), RepeatMode.Restart),
    )
    val breathe by transition.animateFloat(
        0.92f, 1.06f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
    )
    Column(
        modifier.semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(140.dp).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
            if (!reduced) {
                Canvas(Modifier.fillMaxSize()) {
                    val base = size.minDimension * 0.34f
                    drawCircle(BauColors.Yellow.copy(alpha = 0.45f * (1f - pulse)), radius = base + (size.minDimension / 2 - base) * pulse)
                }
            }
            Box(
                Modifier.size(96.dp).scale(if (reduced) 1f else breathe).clip(CircleShape).background(BauColors.Yellow),
                contentAlignment = Alignment.Center,
            ) {
                PathIcon(SPARKLE, 44.dp, BauColors.Ink, 1.6f)
            }
        }
        Text(
            "AI is refining your inquiry…", fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold,
            letterSpacing = (-0.44).sp, color = BauColors.Ink, textAlign = TextAlign.Center,
        )
        Crossfade(hint, Modifier.heightIn(min = 40.dp)) { i ->
            Text(
                RefiningHints[i], Modifier.fillMaxWidth(), fontSize = 15.sp, lineHeight = 20.sp,
                color = BauColors.TextSecondary, textAlign = TextAlign.Center,
            )
        }
    }
}

// ---------------------------------------------------------------- building blocks

internal val BriefInk = Color(0xFF333B4D)
internal val NoteInk = Color(0xFF1E2848)
internal val ChipOutline = Color(0xFFC5CAD3)
internal val InsetTrack = Color(0xFFEEF0EA)

internal val Difficulty.display: String get() = when (this) { Difficulty.Easy -> "Easy"; Difficulty.Medium -> "Medium"; Difficulty.Hard -> "Hard" }

/** "Class Project · CMPS433" / "BAU Project · Urgent". */
internal val InquiryDraft.headline: String
    get() = buildList {
        add(projectFor.label)
        if (projectFor == ProjectFor.ClassProject && classCode != null) add(classCode)
        if (priority == InquiryPriority.Urgent) add("Urgent")
    }.joinToString(" · ")

/** "iOS App", "Android App", "Medium", "Score 150+", "Due Dec 3". */
internal fun RefinedInquiry.tags(): List<String> = buildList {
    ProjectType.entries.filter { it in types }.forEach { add(it.label) }
    add(difficulty.display)
    add(if (minScore > 0) "Score $minScore+" else "Any score")
    InquiryDates.short(deadline)?.let { add("Due $it") }
}

@Composable
private fun Tag(text: String) {
    Text(
        text, Modifier.clip(RoundedCornerShape(999.dp)).background(BauColors.Paper).padding(horizontal = 10.dp, vertical = 5.dp),
        fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, color = BauColors.TextSecondary,
    )
}

@Composable
internal fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text, modifier.padding(horizontal = 4.dp),
        fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, color = BauColors.TextSecondary,
    )
}

@Composable
private fun LabelRow(label: String, trailing: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        FieldLabel(label, Modifier.weight(1f))
        Text(trailing, Modifier.padding(horizontal = 4.dp), fontSize = 13.sp, lineHeight = 18.sp, color = BauColors.TextSecondary)
    }
}

@Composable
internal fun ErrorHint(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(horizontal = 4.dp), fontSize = 13.sp, lineHeight = 18.sp, color = BauColors.Danger)
}

@Composable
private fun inquiryFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = BauColors.Blue, unfocusedBorderColor = BauColors.Line, disabledBorderColor = BauColors.Line,
    errorBorderColor = BauColors.Danger,
    focusedContainerColor = BauColors.Surface, unfocusedContainerColor = BauColors.Surface,
    disabledContainerColor = BauColors.Surface, errorContainerColor = BauColors.Surface,
    focusedTextColor = BauColors.Ink, unfocusedTextColor = BauColors.Ink, disabledTextColor = BauColors.TextSecondary,
    errorTextColor = BauColors.Ink, cursorColor = BauColors.Blue,
    focusedPrefixColor = BauColors.TextSecondary, unfocusedPrefixColor = BauColors.TextSecondary,
    focusedSuffixColor = BauColors.TextSecondary, unfocusedSuffixColor = BauColors.TextSecondary,
)

private val FieldText = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, color = BauColors.Ink)

@Composable
internal fun InquiryTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    error: String? = null,
    singleLine: Boolean = true,
    prefix: String? = null,
    suffix: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldLabel(label)
        OutlinedTextField(
            value = value, onValueChange = onValueChange, enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = if (singleLine) 48.dp else 92.dp)
                .semantics { contentDescription = label },
            singleLine = singleLine, minLines = if (singleLine) 1 else 3, maxLines = if (singleLine) 1 else 8,
            isError = error != null, textStyle = FieldText,
            placeholder = { Text(placeholder, style = FieldText, color = BauColors.TextTertiary) },
            prefix = prefix?.let { p -> { Text(p, style = FieldText) } },
            suffix = suffix?.let { s -> { Text(s, style = FieldText) } },
            keyboardOptions = keyboardOptions,
            shape = RoundedCornerShape(16.dp), colors = inquiryFieldColors(),
        )
        error?.let { ErrorHint(it) }
    }
}

/** Multi-select chip: white with a hollow circle, or Ink with a tick when on. */
@Composable
internal fun CheckChip(text: String, checked: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Row(
        Modifier.heightIn(min = 44.dp).clip(shape)
            .background(if (checked) BauColors.Ink else BauColors.Surface)
            .then(if (checked) Modifier else Modifier.border(1.dp, ChipOutline, shape))
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(start = 12.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (checked) {
            PathIcon(TICK, 16.dp, Color.White, 2.4f, viewBox = 16f)
        } else {
            Canvas(Modifier.size(16.dp)) {
                drawCircle(BauColors.TextTertiary, radius = size.width * 6.2f / 16f, style = Stroke(size.width * 1.6f / 16f))
            }
        }
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (checked) Color.White else BauColors.Ink)
    }
}

@Composable
private fun SuggestionChip(text: String, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(BauSpacing.xl)
    Box(
        Modifier.heightIn(min = 40.dp).clip(shape).border(1.dp, BauColors.Line, shape)
            .clickable(enabled = enabled, onClickLabel = "Use $text", role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text("+ $text", fontSize = 15.sp, color = BauColors.Ink) }
}

/** Full-width glass segmented control (Project For). */
@Composable
internal fun <T> GlassSegmented(options: List<T>, selected: T, label: (T) -> String, enabled: Boolean, onSelect: (T) -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Row(
        Modifier.fillMaxWidth().height(44.dp).clip(shape).background(Color.White.copy(alpha = 0.55f))
            .border(1.dp, Color.White.copy(alpha = 0.85f), shape).padding(3.dp),
    ) {
        options.forEach { option ->
            val on = option == selected
            Box(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(19.dp))
                    .background(if (on) BauColors.Ink else Color.Transparent)
                    .selectable(selected = on, enabled = enabled, role = Role.RadioButton, onClick = { onSelect(option) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(label(option), fontSize = 14.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, color = if (on) Color.White else BauColors.Ink)
            }
        }
    }
}

/** Compact segmented control on a grey track (Priority). */
@Composable
internal fun <T> InsetSegmented(options: List<T>, selected: T, label: (T) -> String, enabled: Boolean, onSelect: (T) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(22.dp)).background(InsetTrack).padding(3.dp)) {
        options.forEach { option ->
            val on = option == selected
            Box(
                Modifier.heightIn(min = 38.dp).clip(RoundedCornerShape(19.dp))
                    .background(if (on) BauColors.Ink else Color.Transparent)
                    .selectable(selected = on, enabled = enabled, role = Role.RadioButton, onClick = { onSelect(option) })
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label(option), fontSize = 14.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, color = if (on) Color.White else BauColors.Ink)
            }
        }
    }
}

@Composable
private fun SettingsRow(title: String, value: String, valueMuted: Boolean, enabled: Boolean, onClick: () -> Unit, error: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .clickable(enabled = enabled, onClickLabel = "Change $title", role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, Modifier.weight(1f), fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-0.34).sp, color = BauColors.Ink)
        Text(
            value, fontSize = 17.sp, lineHeight = 22.sp,
            color = when { error -> BauColors.Danger; valueMuted -> BauColors.TextTertiary; else -> BauColors.TextSecondary },
        )
        PathIcon(CHEVRON, 14.dp, BauColors.TextTertiary, 2.2f)
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(BauColors.Line))
}

/** Yellow 58dp CTA ("Continue", "Refine with AI", "Post Project"). */
@Composable
internal fun FlowPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    sparkle: Boolean = false,
) {
    Button(
        onClick = onClick, enabled = enabled && !loading,
        modifier = modifier.heightIn(min = 58.dp),
        shape = RoundedCornerShape(29.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = BauColors.Yellow, contentColor = BauColors.Ink,
            disabledContainerColor = if (loading) BauColors.Yellow else BauColors.Line,
            disabledContentColor = if (loading) BauColors.Ink else BauColors.TextTertiary,
        ),
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(22.dp), color = BauColors.Ink, strokeWidth = 2.4.dp)
        } else {
            if (sparkle) {
                PathIcon(SPARKLE, 20.dp, if (enabled) BauColors.Ink else BauColors.TextTertiary, 1.8f)
                Spacer(Modifier.width(10.dp))
            }
            Text(text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.38).sp, maxLines = 1)
        }
    }
}

/** Outlined 58dp secondary ("Refine Again"). */
@Composable
internal fun FlowSecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 58.dp),
        shape = RoundedCornerShape(29.dp), border = BorderStroke(1.dp, ChipOutline),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = BauColors.Surface, contentColor = BauColors.Ink),
    ) {
        Text(text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.34).sp, maxLines = 1)
    }
}

/** 44dp glass circle icon button (Close / Back). */
@Composable
internal fun GlassIconButton(path: String, label: String, onClick: () -> Unit, enabled: Boolean = true, strokeWidth: Float = 2.2f) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.55f))
            .border(1.dp, Color.White.copy(alpha = 0.85f), CircleShape)
            .clickable(enabled = enabled, onClickLabel = label, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { PathIcon(path, 20.dp, BauColors.Ink, strokeWidth) }
}

/** Glass pill with a label ("Edit"). */
@Composable
internal fun GlassPillButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        Modifier.height(44.dp).clip(shape).background(Color.White.copy(alpha = 0.55f))
            .border(1.dp, Color.White.copy(alpha = 0.85f), shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = BauColors.Ink) }
}

/** "Step 1 of 2" + two progress bars. */
@Composable
internal fun StepIndicator(step: Int, of: Int = 2) {
    Row(
        Modifier.semantics(mergeDescendants = true) { contentDescription = "Step $step of $of" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Step $step of $of", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = BauColors.TextSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(of) { i ->
                Box(Modifier.size(28.dp, 6.dp).clip(RoundedCornerShape(3.dp)).background(if (i < step) BauColors.Ink else ChipOutline))
            }
        }
    }
}

// ---- icons (SVG paths from the design, drawn as strokes) ----

internal const val SPARKLE = "M10 2.5l1.8 4.7 4.7 1.8-4.7 1.8L10 15.5l-1.8-4.7L3.5 9l4.7-1.8z M16 14v4M14 16h4"
internal const val TICK = "M3 8.5l3 3 7-7.5"
internal const val DONE_TICK = "M3.5 9.5l3.5 3.5 7.5-8"
internal const val CHEVRON = "M7.5 4l6 6-6 6"
internal const val BACK = "M12 4l-6 6 6 6"
internal const val CLOSE = "M5 5l10 10M15 5L5 15"
internal const val BELL = "M5 8a5 5 0 0110 0c0 4 1.5 5 1.5 5h-13S5 12 5 8zM8.5 16a1.6 1.6 0 003 0"
internal const val CHECK_BIG = "M5 10.5l3.5 3.5 6.5-7.5"

/** Strokes an SVG path given in a [viewBox]×[viewBox] coordinate space. Decorative. */
@Composable
internal fun PathIcon(d: String, size: Dp, color: Color, strokeWidth: Float, modifier: Modifier = Modifier, viewBox: Float = 20f) {
    val path = remember(d) { PathParser().parsePathString(d).toPath() }
    Canvas(modifier.size(size)) {
        val k = this.size.width / viewBox
        scale(k, pivot = Offset.Zero) {
            drawPath(path, color, style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
