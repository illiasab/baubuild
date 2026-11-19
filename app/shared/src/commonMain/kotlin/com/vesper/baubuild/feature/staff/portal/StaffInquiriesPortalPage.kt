package com.vesper.baubuild.feature.staff.portal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vesper.baubuild.designsystem.BauColors
import com.vesper.baubuild.designsystem.LoadStateContent
import com.vesper.baubuild.di.AppGraph
import com.vesper.baubuild.feature.staff.StaffNav
import com.vesper.baubuild.feature.staff.inquiries.InquiryFilter
import com.vesper.baubuild.feature.staff.inquiries.StaffDestinationStores
import com.vesper.baubuild.feature.staff.inquiries.StaffInquiriesUiState
import com.vesper.baubuild.feature.staff.inquiries.StaffInquiriesViewModel
import com.vesper.baubuild.feature.staff.inquiries.detail.InquiryDetailViewModel

/**
 * Desktop staff Inquiries page (Web portal design). Hosted by the staff portal shell's content area.
 *
 * All / Open / Closed table → in-page inquiry detail ([StaffInquiriesPortalDetail]); the breadcrumb, Esc and
 * system back return to the table. Overview's "Open" lands here through [StaffPortalNav].
 */
@Composable
fun StaffInquiriesPortalPage(modifier: Modifier = Modifier) {
    val graph = AppGraph.current
    val listViewModel = viewModel(key = "portal/staff/inquiries") { StaffInquiriesViewModel(graph.staffRepository) }
    val stores = viewModel(key = "portal/staff/inquiries/destinations") { StaffDestinationStores() }
    var openInquiryId by rememberSaveable { mutableStateOf<String?>(null) }

    // Requests from Overview ("Open inquiry", recent rows), whether made before or after this page appeared.
    val pending by StaffPortalNav.pendingInquiryId.collectAsState()
    LaunchedEffect(pending) {
        if (pending != null) StaffPortalNav.takePendingInquiry()?.let { openInquiryId = it }
    }

    val destinationKey = openInquiryId?.let(::detailKey)
    LaunchedEffect(destinationKey) { stores.retainOnly(setOfNotNull(destinationKey)) }

    Box(modifier.fillMaxSize().background(BauColors.Paper)) {
        val inquiryId = openInquiryId
        if (inquiryId == null) {
            InquiriesListPage(listViewModel, onOpen = { openInquiryId = it })
        } else {
            key(inquiryId) {
                val owner = remember(inquiryId) { stores.ownerFor(detailKey(inquiryId)) }
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                    StaffInquiriesPortalDetail(
                        viewModel = viewModel(key = detailKey(inquiryId)) { InquiryDetailViewModel(graph.staffRepository, inquiryId) },
                        onBack = { openInquiryId = null },
                    )
                }
            }
        }
    }
}

private fun detailKey(inquiryId: String) = "portal-inquiry/$inquiryId"

private val InquiryColumns = listOf(
    StaffInquiryColumn("INQUIRY", 2.4f) { StaffInquiryCells.InquiryTitle(it) },
    StaffInquiryColumn("PROJECT FOR", 1.6f) { StaffInquiryCells.Secondary(it.portalProjectFor) },
    StaffInquiryColumn("TYPE", 1.6f) { StaffInquiryCells.Secondary(portalTypes(it.draft.types)) },
    StaffInquiryColumn("REWARD", 1.0f) { StaffInquiryCells.Secondary(it.portalReward) },
    StaffInquiryColumn("STATUS", 1.2f) { StaffInquiryCells.Status(it) },
    StaffInquiryColumn("LATEST", 1.9f) { StaffInquiryCells.Latest(it) },
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InquiriesListPage(viewModel: StaffInquiriesViewModel, onOpen: (String) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        StaffPortalHeader(
            title = "Inquiries",
            refreshing = state.isRefreshing,
            onRefresh = { viewModel.refresh() },
        ) {
            StaffPortalSearchField(query, { query = it }, Modifier.width(260.dp))
        }
        LoadStateContent(state.inquiries, onRetry = { viewModel.refresh() }, modifier = Modifier.fillMaxWidth().weight(1f)) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(start = StaffPortalGutter, end = StaffPortalGutter, top = 4.dp, bottom = StaffPortalGutter),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    StaffPortalSegmented(
                        options = InquiryFilter.entries,
                        selected = state.filter,
                        label = { it.label },
                        onSelect = viewModel::selectFilter,
                    )
                    StaffPortalButton("New inquiry", onClick = StaffNav::openNewInquiry, style = StaffPortalButtonStyle.Primary)
                }
                InquiriesTable(state, query, onOpen)
                Text(
                    "Open any inquiry to see today’s progress, blockers, and to edit its details.",
                    style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
                    color = BauColors.TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun InquiriesTable(state: StaffInquiriesUiState, query: String, onOpen: (String) -> Unit) {
    val rows = portalInquiryRows(state.sections, query)
    Box(Modifier.fillMaxWidth().staffPortalCard()) {
        StaffInquiryTable(
            inquiries = rows,
            columns = InquiryColumns,
            minWidth = 980.dp,
            onOpen = { onOpen(it.id) },
            empty = {
                val (title, body) = portalEmptyMessage(state.filter, query, state.hasAny)
                StaffPortalEmpty(title, body, action = if (!state.hasAny && query.isBlank()) {
                    { StaffPortalButton("New inquiry", onClick = StaffNav::openNewInquiry, style = StaffPortalButtonStyle.Primary) }
                } else null)
            },
        )
    }
}
