package com.vesper.baubuild.feature.staff.portal

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StaffPortalNavTest {
    @AfterTest fun clear() { StaffPortalNav.takePendingInquiry() }

    @Test fun openInquiryParksIdUntilTaken() {
        StaffPortalNav.openInquiry("si_shuttle")
        assertEquals("si_shuttle", StaffPortalNav.pendingInquiryId.value)
        assertEquals("si_shuttle", StaffPortalNav.takePendingInquiry())
        assertNull(StaffPortalNav.pendingInquiryId.value)
        assertNull(StaffPortalNav.takePendingInquiry())
    }

    @Test fun latestRequestWins() {
        StaffPortalNav.openInquiry("a")
        StaffPortalNav.openInquiry("b")
        assertEquals("b", StaffPortalNav.takePendingInquiry())
    }
}
