package com.vesper.baubuild.feature.staff.inquiries

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vesper.baubuild.designsystem.BauSpacing
import com.vesper.baubuild.designsystem.BauTextLink
import com.vesper.baubuild.designsystem.LoadStateContent
import com.vesper.baubuild.designsystem.tabBarContentPadding
import com.vesper.baubuild.di.AppGraph
import com.vesper.baubuild.feature.staff.StaffNav
import com.vesper.baubuild.feature.staff.inquiries.components.InquiryRow
import com.vesper.baubuild.feature.staff.inquiries.components.StaffEmptyMessage
import com.vesper.baubuild.feature.staff.inquiries.components.StaffGroupedSection
import com.vesper.baubuild.feature.staff.inquiries.components.StaffLargeTitle
import com.vesper.baubuild.feature.staff.inquiries.components.StaffSegmentedControl

/**
 * Staff mobile entry point. Hosted by the staff tab shells (native iOS TabView / Compose on Android).
 * Inquiries list → inquiry detail (own back stack, system back pops).
 */
@Composable
fun StaffInquiriesTabRoot(modifier: Modifier = Modifier) {
    val backStack = rememberStaffBackStack()
    val listViewModel = viewModel { StaffInquiriesViewModel(AppGraph.current.staffRepository) }
    StaffTabHost(hostKey = "inquiries", backStack = backStack, backLabel = "Back to Inquiries", modifier = modifier) {
        StaffInquiriesScreen(
            viewModel = listViewModel,
            onOpenInquiry = { backStack.push(StaffRoute.InquiryDetail(it)) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StaffInquiriesScreen(
    viewModel: StaffInquiriesViewModel,
    onOpenInquiry: (inquiryId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LoadStateContent(state.inquiries, onRetry = { viewModel.refresh() }, modifier = modifier.fillMaxSize()) { _ ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .padding(horizontal = BauSpacing.screen)
                    .padding(top = BauSpacing.md)
                    .tabBarContentPadding(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                StaffLargeTitle(
                    "Inquiries",
                    subtitle = "Everything you have asked BAU BUILD to make.",
                    modifier = Modifier.padding(top = BauSpacing.md),
                )
                StaffSegmentedControl(
                    options = InquiryFilter.entries,
                    selected = state.filter,
                    label = { it.label },
                    onSelect = viewModel::selectFilter,
                )
                if (state.sections.isEmpty()) {
                    EmptyInquiries(state.filter, hasAny = state.hasAny)
                } else {
                    state.sections.forEach { section ->
                        StaffGroupedSection(section.title) {
                            section.inquiries.forEachIndexed { i, inquiry ->
                                InquiryRow(
                                    inquiry = inquiry,
                                    showDivider = i < section.inquiries.lastIndex,
                                    onClick = { onOpenInquiry(inquiry.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyInquiries(filter: InquiryFilter, hasAny: Boolean) {
    when {
        !hasAny -> StaffEmptyMessage(
            title = "No inquiries yet",
            body = "Tell BAU BUILD what you need. AI turns it into a clear project and students build it.",
        ) { BauTextLink("Post your first inquiry", onClick = StaffNav::openNewInquiry) }
        filter == InquiryFilter.Open -> StaffEmptyMessage(
            title = "Nothing open",
            body = "Every inquiry is delivered or closed. Post a new one with +.",
        )
        else -> StaffEmptyMessage(
            title = "Nothing closed yet",
            body = "Delivered and cancelled inquiries show up here.",
        )
    }
}
