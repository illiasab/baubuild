package com.vesper.baubuild.feature.staff.portal

import com.vesper.baubuild.feature.staff.StaffNav
import com.vesper.baubuild.feature.staff.StaffTab
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * Cross-page requests inside the desktop staff portal.
 *
 * Overview's "Open" (recent rows, "Needs your attention") shows the inquiry's detail on the Inquiries page, as
 * the portal design's breadcrumb ("Inquiries / title") implies: [openInquiry] parks the id here and asks the shell
 * (via [StaffNav]) to switch to Inquiries; [StaffInquiriesPortalPage] consumes the pending id with [takePendingInquiry].
 * A StateFlow (not a one-shot event) so the request survives the Inquiries page being composed after it was made.
 */
object StaffPortalNav {
    private val _pendingInquiryId = MutableStateFlow<String?>(null)
    val pendingInquiryId: StateFlow<String?> = _pendingInquiryId.asStateFlow()

    fun openInquiry(inquiryId: String) {
        _pendingInquiryId.value = inquiryId
        StaffNav.selectTab(StaffTab.Inquiries)
    }

    /** Returns and clears the pending request (null if none). */
    fun takePendingInquiry(): String? = _pendingInquiryId.getAndUpdate { null }
}
