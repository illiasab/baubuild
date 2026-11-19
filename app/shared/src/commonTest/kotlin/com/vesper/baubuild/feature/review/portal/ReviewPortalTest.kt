package com.vesper.baubuild.feature.review.portal

import com.vesper.baubuild.core.LoadState
import com.vesper.baubuild.data.fake.FakeData
import com.vesper.baubuild.feature.review.ReviewUiState
import kotlin.test.*

class ReviewPortalTest {
    private val one = FakeData.reviews.first()
    private val two = one.copy(project = FakeData.shuttleWidget, headline = "With the dev lead.")

    private fun content(vararg r: com.vesper.baubuild.core.model.ReviewStatus) =
        LoadState.Content(r.toList())

    @Test fun selectsFirstSubmissionByDefault() {
        assertEquals(one, ReviewUiState(content(one, two)).portalSelection())
    }

    @Test fun selectsPickedSubmission() {
        assertEquals(two, ReviewUiState(content(one, two), selectedProjectId = two.project.id).portalSelection())
    }

    @Test fun unknownSelectionFallsBackToFirst() {
        assertEquals(one, ReviewUiState(content(one, two), selectedProjectId = "gone").portalSelection())
    }

    @Test fun nothingSelectedWhenEmptyOrLoading() {
        assertNull(ReviewUiState(content()).portalSelection())
        assertNull(ReviewUiState().portalSelection())
    }

    @Test fun currentStageIsActiveElseLastDone() {
        val active = one.stages.firstOrNull { it.state == com.vesper.baubuild.core.model.ReviewStageState.Active }
        val expected = active?.title
            ?: one.stages.last { it.state == com.vesper.baubuild.core.model.ReviewStageState.Done }.title
        assertEquals(expected, one.currentStageTitle())
    }
}
