package com.vesper.baubuild.feature.profile.lead

import androidx.lifecycle.ViewModel
import com.vesper.baubuild.core.model.ReviewStageState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LeadStage(val title: String, val detail: String, val state: ReviewStageState)

data class BecomeLeadUiState(
    val stages: List<LeadStage> = defaultLeadStages,
    /** Inline message after tapping the CTA (exam booking is not wired to the backend yet). */
    val notice: String? = null,
)

val defaultLeadStages = listOf(
    LeadStage("Proctored Exam", "A short coding and analytical-thinking exam, taken with a proctor.", ReviewStageState.Active),
    LeadStage("In-Person Stage", "Pass the exam, then meet Ylyas, Tech Lead Engineer, in person.", ReviewStageState.Pending),
    LeadStage("Lead Status", "Your role changes to Lead and you can approve or send back code.", ReviewStageState.Pending),
)

class BecomeLeadViewModel : ViewModel() {
    private val _state = MutableStateFlow(BecomeLeadUiState())
    val state: StateFlow<BecomeLeadUiState> = _state.asStateFlow()

    fun startExam() {
        // TODO(lead-track): request/schedule a proctored exam via a backend LeadRepository
        //  (e.g. `requestExam()` returning a slot or booking link) and reflect progress in [stages].
        _state.value = _state.value.copy(notice = "Exam booking opens soon. We’ll let you know when a proctor slot is available.")
    }
}
