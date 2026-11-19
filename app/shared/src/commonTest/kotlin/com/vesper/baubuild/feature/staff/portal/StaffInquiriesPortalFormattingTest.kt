package com.vesper.baubuild.feature.staff.portal

import com.vesper.baubuild.core.model.InquiryStatus
import com.vesper.baubuild.core.model.ProjectType
import com.vesper.baubuild.core.model.StaffOverview
import com.vesper.baubuild.data.fake.FakeStaffData
import com.vesper.baubuild.feature.staff.inquiries.InquiryFilter
import com.vesper.baubuild.feature.staff.inquiries.sectionsFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StaffInquiriesPortalFormattingTest {
    private val all = FakeStaffData.inquiries
    private fun byId(id: String) = all.first { it.id == id }

    @Test fun typesUseShortPlatformNamesInCatalogueOrder() {
        assertEquals("iOS · Android · Website", portalTypes(setOf(ProjectType.Website, ProjectType.AndroidApp, ProjectType.IosApp)))
        assertEquals("Data / Script", portalTypes(setOf(ProjectType.DataScript)))
        assertEquals("—", portalTypes(emptySet()))
    }

    @Test fun projectForIncludesClassCodeButNotUrgency() {
        assertEquals("Class Project · CMPS433", byId("si_course_search").portalProjectFor)
        assertEquals("BAU Project", byId("si_shuttle").portalProjectFor)
        assertTrue(byId("si_shuttle").isUrgent)
    }

    @Test fun latestFallsBackToStatusSummary() {
        val shuttle = byId("si_shuttle")
        assertEquals("Day 4 of 7 · 1 blocker", shuttle.portalLatest)
        assertTrue(shuttle.hasOpenBlocker)
        assertEquals("Waiting for your check", byId("si_lab_booking").copy(latest = "").portalLatest)
        assertFalse(byId("si_room_booking").hasOpenBlocker)
    }

    @Test fun detailMetaAndDayLineMatchDesign() {
        val shuttle = byId("si_shuttle")
        assertEquals("BAU Project · iOS · Android · Website · $35 · 3 students · dev lead reviews", shuttle.portalDetailMeta)
        assertEquals("Day 4 of 7 · due Nov 22", shuttle.portalDayLine())
        assertEquals(null, byId("si_room_booking").copy(progress = null).portalDayLine())
    }

    @Test fun openCountIncludesInProgress() {
        val overview = StaffOverview(raisedUsd = 0, wastedUsd = 0, openCount = 2, inProgressCount = 1, deliveredCount = 9)
        assertEquals(3, overview.portalOpenCount)
    }

    @Test fun rowsFollowFilterAndSearch() {
        val open = portalInquiryRows(sectionsFor(all, InquiryFilter.Open), "")
        assertTrue(open.all { it.status.isOpen })
        val closed = portalInquiryRows(sectionsFor(all, InquiryFilter.Closed), "")
        assertTrue(closed.all { it.status == InquiryStatus.Done || it.status == InquiryStatus.Cancelled })
        assertEquals(all.size, portalInquiryRows(sectionsFor(all, InquiryFilter.All), "  ").size)

        val search = portalInquiryRows(sectionsFor(all, InquiryFilter.All), "SHUTTLE")
        assertEquals(listOf("si_shuttle"), search.map { it.id })
        val byClass = portalInquiryRows(sectionsFor(all, InquiryFilter.All), "cmps433").map { it.id }.toSet()
        assertEquals(setOf("si_lab_booking", "si_course_search"), byClass)
    }

    @Test fun emptyMessagesExplainTheSituation() {
        assertEquals("No matches", portalEmptyMessage(InquiryFilter.All, "zzz", hasAny = true).first)
        assertEquals("No inquiries yet", portalEmptyMessage(InquiryFilter.Open, "", hasAny = false).first)
        assertEquals("Nothing open", portalEmptyMessage(InquiryFilter.Open, "", hasAny = true).first)
        assertEquals("Nothing closed yet", portalEmptyMessage(InquiryFilter.Closed, "", hasAny = true).first)
    }
}
