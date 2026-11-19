package com.vesper.baubuild.shell.staff

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import com.vesper.baubuild.feature.staff.StaffTab

/** Selected staff tab + whether the full-screen New Inquiry flow is open (survives config changes). */
@Stable
internal class StaffShellNavigation(initial: StaffTab = StaffTab.Overview, newInquiryOpen: Boolean = false) {
    var selected by mutableStateOf(initial)
        private set
    var isNewInquiryOpen by mutableStateOf(newInquiryOpen)
        private set

    fun select(tab: StaffTab) {
        selected = tab
    }

    fun openNewInquiry() {
        isNewInquiryOpen = true
    }

    /** Returns false when it wasn't open. */
    fun closeNewInquiry(): Boolean {
        if (!isNewInquiryOpen) return false
        isNewInquiryOpen = false
        return true
    }

    companion object {
        val Saver: Saver<StaffShellNavigation, Any> = listSaver(
            save = { listOf(it.selected.name, it.isNewInquiryOpen.toString()) },
            restore = { StaffShellNavigation(StaffTab.valueOf(it[0]), it[1].toBoolean()) },
        )
    }
}
