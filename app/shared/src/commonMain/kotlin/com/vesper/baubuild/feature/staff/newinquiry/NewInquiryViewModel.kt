package com.vesper.baubuild.feature.staff.newinquiry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vesper.baubuild.core.model.Difficulty
import com.vesper.baubuild.core.model.InquiryDraft
import com.vesper.baubuild.core.model.InquiryPriority
import com.vesper.baubuild.core.model.ProjectFor
import com.vesper.baubuild.core.model.ProjectType
import com.vesper.baubuild.core.model.RefinedInquiry
import com.vesper.baubuild.core.model.RewardType
import com.vesper.baubuild.data.repository.StaffRepository
import com.vesper.baubuild.feature.staff.StaffNav
import com.vesper.baubuild.feature.staff.StaffTab
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock

enum class NewInquiryStep { Details, RewardTiming, Ready }

/**
 * What the staff member has typed, exactly as typed (numbers stay strings so a half-typed value is never lost).
 * [toDraft] turns it into the [InquiryDraft] sent to the backend.
 */
@Serializable
data class NewInquiryForm(
    val title: String = "",
    val explanation: String = "",
    val expectation: String = "",
    val types: Set<ProjectType> = emptySet(),
    val projectFor: ProjectFor = ProjectFor.BauProject,
    /** Kept while "BAU Project" is selected so switching back restores it; only sent for class projects. */
    val classCode: String = "",
    val rewards: Set<RewardType> = setOf(RewardType.Price),
    val price: String = "",
    val discount: String = "",
    val priority: InquiryPriority = InquiryPriority.Normal,
    /** ISO yyyy-MM-dd. */
    val deadline: String? = null,
    /** Null = "Let AI suggest". */
    val difficulty: Difficulty? = null,
) {
    fun toDraft(): InquiryDraft = InquiryDraft(
        title = title.trim(),
        explanation = explanation.trim(),
        expectation = expectation.trim(),
        types = types,
        projectFor = projectFor,
        classCode = if (projectFor == ProjectFor.ClassProject) NewInquiryRules.normalizeClassCode(classCode).ifEmpty { null } else null,
        rewards = rewards,
        priceUsd = if (RewardType.Price in rewards) price.toIntOrNull() else null,
        schoolDiscountPercent = if (RewardType.SchoolDiscount in rewards) discount.toIntOrNull() else null,
        priority = priority,
        deadline = deadline,
        difficulty = difficulty,
    )

    companion object {
        /** Start from an existing draft (e.g. editing a saved one). */
        fun from(draft: InquiryDraft) = NewInquiryForm(
            title = draft.title, explanation = draft.explanation, expectation = draft.expectation, types = draft.types,
            projectFor = draft.projectFor, classCode = draft.classCode.orEmpty(), rewards = draft.rewards,
            price = draft.priceUsd?.toString().orEmpty(), discount = draft.schoolDiscountPercent?.toString().orEmpty(),
            priority = draft.priority, deadline = draft.deadline, difficulty = draft.difficulty,
        )
    }
}

object NewInquiryRules {
    const val MAX_TITLE = 80
    const val MAX_TEXT = 2_000
    const val MAX_CLASS_CODE = 12
    const val MAX_PRICE_USD = 100_000
    const val MAX_PRICE_DIGITS = 6

    /** "cmps 433" → "CMPS433". */
    fun normalizeClassCode(raw: String): String = raw.filter { !it.isWhitespace() }.uppercase()

    fun isValidClassCode(code: String): Boolean =
        code.length in 3..MAX_CLASS_CODE && code.all { it in 'A'..'Z' || it in '0'..'9' || it == '-' } &&
            code.any { it in 'A'..'Z' } && code.any { it in '0'..'9' }
}

/** Shown wherever a refined brief came from the client-side fallback instead of the backend AI. */
const val BASIC_REWRITE_LABEL = "Basic rewrite (AI refine not available yet)"

data class NewInquiryUiState(
    val form: NewInquiryForm = NewInquiryForm(),
    val step: NewInquiryStep = NewInquiryStep.Details,
    /** The form as it was before the user typed anything (incl. profile prefill) — for discard confirmation. */
    val baseline: NewInquiryForm = NewInquiryForm(),
    /** Classes the staff member teaches (from their profile), offered as Class Code suggestions. */
    val classSuggestions: List<String> = emptyList(),
    /** "Ilias and Arian, the senior engineers" from the profile, used when the AI result doesn't say. */
    val notifyFallback: String? = null,
    /** Validation messages appear only after the user tried to continue / refine. */
    val detailsAttempted: Boolean = false,
    val rewardAttempted: Boolean = false,
    val refining: Boolean = false,
    val refineError: String? = null,
    val refined: RefinedInquiry? = null,
    /** The draft [refined] was produced from. */
    val refinedFor: InquiryDraft? = null,
    val posting: Boolean = false,
    val postError: String? = null,
    val posted: Boolean = false,
    val showDiscardConfirm: Boolean = false,
    val savingDraft: Boolean = false,
    val saveDraftError: String? = null,
    /** The flow should be dismissed (discarded, saved as draft, or nothing typed). */
    val closed: Boolean = false,
    val todayEpochDay: Long = 0,
    /** False → [StaffRepository.refine] is the basic client-side rewrite, labelled as such. */
    val aiRefineAvailable: Boolean = true,
) {
    /** Subtitle of Ready to Post. */
    val readySubtitle: String
        get() = if (aiRefineAvailable) "AI refined your inquiry. Check it, then post."
        else "$BASIC_REWRITE_LABEL. Check it, then post."

    val draft: InquiryDraft get() = form.toDraft()
    val isDirty: Boolean get() = form != baseline
    val busy: Boolean get() = refining || posting || savingDraft || posted

    // ---- step 1 ----
    private val titleProblem: String? get() = if (form.title.isBlank()) "Give the project a title." else null
    private val explanationProblem: String? get() = if (form.explanation.isBlank()) "Explain the problem and who has it." else null
    private val typesProblem: String? get() = if (form.types.isEmpty()) "Pick at least one project type." else null

    val titleError: String? get() = titleProblem.takeIf { detailsAttempted }
    val explanationError: String? get() = explanationProblem.takeIf { detailsAttempted }
    val typesError: String? get() = typesProblem.takeIf { detailsAttempted }

    /** Continue is enabled once the required step-1 fields are filled. */
    val isDetailsValid: Boolean get() = titleProblem == null && explanationProblem == null && typesProblem == null

    // ---- step 2 ----
    val showsClassCode: Boolean get() = form.projectFor == ProjectFor.ClassProject
    val showsPrice: Boolean get() = RewardType.Price in form.rewards
    val showsDiscount: Boolean get() = RewardType.SchoolDiscount in form.rewards

    private val classCodeProblem: String?
        get() {
            if (!showsClassCode) return null
            val code = NewInquiryRules.normalizeClassCode(form.classCode)
            return when {
                code.isEmpty() -> "Enter the class code, e.g. CMPS433."
                !NewInquiryRules.isValidClassCode(code) -> "Use the course code, e.g. CMPS433."
                else -> null
            }
        }
    private val rewardsProblem: String? get() = if (form.rewards.isEmpty()) "Pick at least one reward." else null
    private val priceProblem: String?
        get() {
            if (!showsPrice) return null
            val v = form.price.toIntOrNull()
            return when {
                v == null || v <= 0 -> "Enter a price in USD."
                v > NewInquiryRules.MAX_PRICE_USD -> "Keep the price under $${NewInquiryRules.MAX_PRICE_USD}."
                else -> null
            }
        }
    private val discountProblem: String?
        get() {
            if (!showsDiscount) return null
            val v = form.discount.toIntOrNull()
            return if (v == null || v !in 1..100) "Enter a discount from 1 to 100%." else null
        }
    private val deadlineProblem: String?
        get() {
            val day = form.deadline?.let(InquiryDates::isoToEpochDay)
            return when {
                form.deadline == null -> "Pick a deadline."
                day == null -> "Use a date like 2026-12-03."
                day <= todayEpochDay -> "Pick a date in the future."
                else -> null
            }
        }

    val classCodeError: String? get() = classCodeProblem.takeIf { rewardAttempted }
    val rewardsError: String? get() = rewardsProblem.takeIf { rewardAttempted }
    val priceError: String? get() = priceProblem.takeIf { rewardAttempted }
    val discountError: String? get() = discountProblem.takeIf { rewardAttempted }
    val deadlineError: String? get() = deadlineProblem.takeIf { rewardAttempted }

    val isRewardValid: Boolean
        get() = classCodeProblem == null && rewardsProblem == null && priceProblem == null && discountProblem == null && deadlineProblem == null

    /** Class codes to offer as one-tap chips (not the one already typed). */
    val visibleClassSuggestions: List<String>
        get() = classSuggestions.filter { it != NewInquiryRules.normalizeClassCode(form.classCode) }

    /** The cached AI result still matches what the user typed. */
    val refinedIsCurrent: Boolean get() = refined != null && refinedFor == draft

    val notifiesOnDone: String? get() = refined?.notifiesOnDone ?: notifyFallback
}

/** What survives process death (Android) — restored through `rememberSaveable` in [NewInquiryRoot]. */
@Serializable
data class NewInquirySnapshot(
    val form: NewInquiryForm,
    val baseline: NewInquiryForm,
    val step: NewInquiryStep,
    val refined: RefinedInquiry? = null,
    val refinedFor: InquiryDraft? = null,
    val classSuggestions: List<String> = emptyList(),
    val notifyFallback: String? = null,
) {
    fun encode(): String = SnapshotJson.encodeToString(serializer(), this)

    companion object {
        private val SnapshotJson = Json { ignoreUnknownKeys = true }
        fun decode(text: String): NewInquirySnapshot? =
            runCatching { SnapshotJson.decodeFromString(serializer(), text) }.getOrNull()
    }
}

/**
 * The whole New Inquiry flow (mobile: step 1 → step 2 → AI refining → Ready to Post; desktop portal: one page +
 * refined view). Holds the form in a [StateFlow]; refine / post / save-draft keep the input on failure.
 */
class NewInquiryViewModel(
    private val repository: StaffRepository,
    restored: NewInquirySnapshot? = null,
    private val todayEpochDay: () -> Long = { Clock.System.now().toEpochMilliseconds().floorDiv(InquiryDates.MILLIS_PER_DAY) },
) : ViewModel() {
    private val _state = MutableStateFlow(initial(restored).copy(aiRefineAvailable = repository.aiRefineAvailable))
    val state: StateFlow<NewInquiryUiState> = _state.asStateFlow()

    /** Serialized [NewInquirySnapshot] whenever something worth restoring changes. */
    val snapshots: Flow<String> = _state
        .map { NewInquirySnapshot(it.form, it.baseline, it.step, it.refined, it.refinedFor, it.classSuggestions, it.notifyFallback) }
        .distinctUntilChanged()
        .map { it.encode() }

    private var refineJob: Job? = null

    init {
        loadProfile(prefill = restored == null)
    }

    private fun initial(restored: NewInquirySnapshot?): NewInquiryUiState {
        val today = todayEpochDay()
        if (restored == null) return NewInquiryUiState(todayEpochDay = today)
        val s = NewInquiryUiState(
            form = restored.form, baseline = restored.baseline, step = restored.step,
            refined = restored.refined, refinedFor = restored.refinedFor,
            classSuggestions = restored.classSuggestions, notifyFallback = restored.notifyFallback, todayEpochDay = today,
        )
        // Ready to Post only makes sense with a result for exactly this draft.
        return if (s.step == NewInquiryStep.Ready && !s.refinedIsCurrent) s.copy(step = NewInquiryStep.RewardTiming) else s
    }

    private inline fun update(block: NewInquiryUiState.() -> NewInquiryUiState) {
        _state.value = _state.value.block()
    }

    private inline fun edit(block: NewInquiryForm.() -> NewInquiryForm) {
        if (_state.value.busy) return
        update { copy(form = form.block(), refineError = null, postError = null) }
    }

    private fun loadProfile(prefill: Boolean) {
        viewModelScope.launch {
            val profile = try {
                repository.profile()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                return@launch // suggestions are optional
            }
            val notify = profile.seniorEngineers.map { it.name }.takeIf { it.isNotEmpty() }?.let { names ->
                val joined = if (names.size == 1) names[0] else names.dropLast(1).joinToString(", ") + " and " + names.last()
                "$joined, the senior engineer${if (names.size == 1) "" else "s"}"
            }
            update {
                val next = copy(classSuggestions = profile.classes.map(NewInquiryRules::normalizeClassCode), notifyFallback = notify)
                // Professors usually post for one of their classes: preselect it, unless the user already started.
                if (prefill && form == baseline && profile.classes.isNotEmpty()) {
                    val pre = form.copy(projectFor = ProjectFor.ClassProject, classCode = next.classSuggestions.first())
                    next.copy(form = pre, baseline = pre)
                } else next
            }
        }
    }

    // ---- step 1 ----
    fun onTitleChange(text: String) = edit { copy(title = text.replace('\n', ' ').take(NewInquiryRules.MAX_TITLE)) }
    fun onExplanationChange(text: String) = edit { copy(explanation = text.take(NewInquiryRules.MAX_TEXT)) }
    fun onExpectationChange(text: String) = edit { copy(expectation = text.take(NewInquiryRules.MAX_TEXT)) }
    fun toggleType(type: ProjectType) = edit { copy(types = if (type in types) types - type else types + type) }

    /** Step 1 → step 2. Returns false (and shows the messages) when step 1 is incomplete. */
    fun continueToReward(): Boolean {
        val s = _state.value
        if (s.busy) return false
        if (!s.isDetailsValid) {
            update { copy(detailsAttempted = true) }
            return false
        }
        update { copy(step = NewInquiryStep.RewardTiming) }
        return true
    }

    // ---- step 2 ----
    fun setProjectFor(value: ProjectFor) = edit {
        val suggestion = _state.value.classSuggestions.firstOrNull()
        val code = if (value == ProjectFor.ClassProject && classCode.isBlank() && suggestion != null) suggestion else classCode
        copy(projectFor = value, classCode = code)
    }
    fun onClassCodeChange(text: String) =
        edit { copy(classCode = text.filter { !it.isWhitespace() }.uppercase().take(NewInquiryRules.MAX_CLASS_CODE)) }
    fun toggleReward(reward: RewardType) = edit { copy(rewards = if (reward in rewards) rewards - reward else rewards + reward) }
    fun onPriceChange(text: String) = edit {
        copy(price = text.filter { it.isDigit() }.trimStart('0').take(NewInquiryRules.MAX_PRICE_DIGITS))
    }
    fun onDiscountChange(text: String) = edit { copy(discount = text.filter { it.isDigit() }.trimStart('0').take(3)) }
    fun setPriority(value: InquiryPriority) = edit { copy(priority = value) }
    /** [iso] is yyyy-MM-dd (or null to clear). */
    fun setDeadline(iso: String?) = edit { copy(deadline = iso?.trim()?.ifEmpty { null }) }
    fun setDeadlineEpochDay(day: Long) = setDeadline(InquiryDates.epochDayToIso(day))
    fun setDifficulty(value: Difficulty?) = edit { copy(difficulty = value) }

    fun backToDetails() {
        if (_state.value.busy) return
        update { copy(step = NewInquiryStep.Details, refineError = null) }
    }

    /**
     * "Refine with AI". Validates both steps (jumping back to step 1 if it is incomplete). Reuses the last AI result
     * when nothing changed since, otherwise asks the AI.
     */
    fun refine() {
        val s = _state.value
        if (s.busy) return
        if (!s.isDetailsValid) {
            update { copy(detailsAttempted = true, rewardAttempted = true, step = NewInquiryStep.Details) }
            return
        }
        if (!s.isRewardValid) {
            update { copy(rewardAttempted = true) }
            return
        }
        if (s.refinedIsCurrent) {
            update { copy(step = NewInquiryStep.Ready, refineError = null) }
            return
        }
        runRefine(s.draft)
    }

    /** "Refine Again" on Ready to Post: asks the AI again for the same draft and replaces the result. */
    fun refineAgain() {
        val s = _state.value
        if (s.busy || s.step != NewInquiryStep.Ready) return
        runRefine(s.refinedFor ?: s.draft)
    }

    private fun runRefine(draft: InquiryDraft) {
        update { copy(refining = true, refineError = null, postError = null) }
        refineJob = viewModelScope.launch {
            try {
                val result = repository.refine(draft)
                update { copy(refining = false, refined = result, refinedFor = draft, step = NewInquiryStep.Ready) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                update { copy(refining = false, refineError = "AI couldn’t refine your inquiry. Check your connection and try again.") }
            }
        }
    }

    /** Cancel on the refining screen: back where the user was, everything kept. */
    fun cancelRefine() {
        if (!_state.value.refining) return
        refineJob?.cancel()
        refineJob = null
        update { copy(refining = false) }
    }

    // ---- Ready to Post ----
    /** "Edit": back to step 1 with everything kept. */
    fun editFromReady() {
        if (_state.value.busy) return
        update { copy(step = NewInquiryStep.Details, postError = null, refineError = null) }
    }

    fun backFromReady() {
        if (_state.value.busy) return
        update { copy(step = NewInquiryStep.RewardTiming, postError = null, refineError = null) }
    }

    fun post() {
        val s = _state.value
        val refined = s.refined ?: return
        val draft = s.refinedFor ?: return
        if (s.busy || s.step != NewInquiryStep.Ready) return
        update { copy(posting = true, postError = null, refineError = null) }
        viewModelScope.launch {
            try {
                repository.post(draft, refined)
                update { copy(posting = false, posted = true) }
                StaffNav.selectTab(StaffTab.Inquiries)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                update { copy(posting = false, postError = "Couldn’t post your project. Check your connection and try again.") }
            }
        }
    }

    // ---- closing ----
    /** Close (X) / back on step 1: closes right away when nothing was typed, otherwise asks first. */
    fun requestClose() {
        val s = _state.value
        if (s.posting || s.posted || s.savingDraft) return
        if (s.refining) cancelRefine()
        if (s.isDirty) update { copy(showDiscardConfirm = true, saveDraftError = null) } else update { copy(closed = true) }
    }

    fun keepEditing() {
        if (_state.value.savingDraft) return
        update { copy(showDiscardConfirm = false, saveDraftError = null) }
    }

    fun discard() {
        if (_state.value.savingDraft) return
        update { copy(showDiscardConfirm = false, closed = true) }
    }

    /** "Save draft" in the discard dialog: saves, then closes. On failure the dialog stays with an inline error. */
    fun saveDraftAndClose() {
        val s = _state.value
        if (s.savingDraft || s.posting) return
        update { copy(savingDraft = true, saveDraftError = null) }
        viewModelScope.launch {
            try {
                repository.saveDraft(s.draft)
                update { copy(savingDraft = false, showDiscardConfirm = false, closed = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                update { copy(savingDraft = false, saveDraftError = "Couldn’t save the draft. Try again.") }
            }
        }
    }
}

/** Calendar helpers without a date library. Dates are UTC days, matching Material3's DatePicker. */
object InquiryDates {
    const val MILLIS_PER_DAY = 86_400_000L
    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    /** Days since 1970-01-01 → "yyyy-MM-dd". */
    fun epochDayToIso(epochDay: Long): String {
        val (y, m, d) = civil(epochDay)
        return "${y.toString().padStart(4, '0')}-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
    }

    /** "yyyy-MM-dd" → days since 1970-01-01, or null when it isn't a real date. */
    fun isoToEpochDay(iso: String): Long? {
        val parts = iso.trim().split('-')
        if (parts.size != 3 || parts[0].length != 4 || parts[1].length != 2 || parts[2].length != 2) return null
        val y = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        val d = parts[2].toIntOrNull() ?: return null
        if (m !in 1..12 || d !in 1..31) return null
        val day = daysFromCivil(y, m, d)
        return if (civil(day) == Triple(y, m, d)) day else null
    }

    /** "2026-12-03" → "Dec 3". */
    fun short(iso: String?): String? {
        val day = iso?.let(::isoToEpochDay) ?: return null
        val (_, m, d) = civil(day)
        return "${MONTHS[m - 1]} $d"
    }

    /** "2026-12-03" → "Dec 3, 2026". */
    fun long(iso: String?): String? {
        val day = iso?.let(::isoToEpochDay) ?: return null
        val (y, m, d) = civil(day)
        return "${MONTHS[m - 1]} $d, $y"
    }

    // Howard Hinnant's civil-from-days / days-from-civil.
    private fun civil(epochDay: Long): Triple<Int, Int, Int> {
        val z = epochDay + 719_468
        val era = (if (z >= 0) z else z - 146_096) / 146_097
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
        val y = (yoe + era * 400 + if (m <= 2) 1 else 0).toInt()
        return Triple(y, m, d)
    }

    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }
}
