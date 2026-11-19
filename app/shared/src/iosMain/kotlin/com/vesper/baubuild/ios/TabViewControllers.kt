package com.vesper.baubuild.ios

import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import com.vesper.baubuild.designsystem.BauColors
import com.vesper.baubuild.designsystem.BauTheme
import com.vesper.baubuild.designsystem.DebugLayout
import com.vesper.baubuild.feature.auth.AuthRoot
import com.vesper.baubuild.feature.mywork.MyWorkTabRoot
import com.vesper.baubuild.feature.profile.ProfileTabRoot
import com.vesper.baubuild.feature.projects.ProjectsTabRoot
import com.vesper.baubuild.feature.review.ReviewTabRoot
import com.vesper.baubuild.feature.search.SearchRoot
import com.vesper.baubuild.feature.staff.inquiries.StaffInquiriesTabRoot
import com.vesper.baubuild.feature.staff.newinquiry.NewInquiryRoot
import com.vesper.baubuild.feature.staff.overview.StaffOverviewTabRoot
import com.vesper.baubuild.feature.staff.profile.StaffProfileTabRoot
import com.vesper.baubuild.feature.staff.tour.StaffTourHost
import platform.UIKit.UIViewController

/*
 * One UIViewController per Compose root, hosted by the native SwiftUI shell (iosApp/RootView.swift).
 * The SwiftUI side lets each controller extend under the Liquid Glass tab bar; the tab bar height is
 * part of the view's safeAreaInsets (WindowInsets.safeDrawing). Screens turn that into their bottom padding through
 * designsystem/BottomBarSpacing.kt, so their last item rests 24dp above the bar while content scrolls beneath it.
 */

private fun hosted(content: @Composable () -> Unit): UIViewController =
    ComposeUIViewController {
        BauTheme {
            // /v1/config on iOS too: update gate (only when below the minimum version; fails open) + maintenance banner.
            com.vesper.baubuild.feature.appstatus.UpdateRequiredGate {
                Box(Modifier.fillMaxSize().background(BauColors.Paper)) {
                    content()
                    com.vesper.baubuild.feature.appstatus.AppStatusBanner(
                        Modifier.align(androidx.compose.ui.Alignment.TopCenter).windowInsetsPadding(
                            androidx.compose.foundation.layout.WindowInsets.safeDrawing
                                .only(androidx.compose.foundation.layout.WindowInsetsSides.Top),
                        ),
                    )
                }
            }
        }
    }

/** DEBUG hook (Swift calls it only in DEBUG builds, `-startScrolledToBottom`): long tab roots open scrolled to the end. */
fun setDebugStartScrolledToBottom(enabled: Boolean) {
    DebugLayout.startScrolledToBottom = enabled
}

fun AuthViewController(): UIViewController = hosted { AuthRoot() }
fun ProjectsViewController(): UIViewController = hosted { ProjectsTabRoot() }
fun MyWorkViewController(): UIViewController = hosted { MyWorkTabRoot() }
fun ReviewViewController(): UIViewController = hosted { ReviewTabRoot() }
fun ProfileViewController(): UIViewController = hosted { ProfileTabRoot() }
fun SearchViewController(): UIViewController = hosted { SearchRoot() }

/*
 * Staff (@bau.edu) roots, hosted by StaffTabShell.swift. Each is wrapped in a StaffTourHost so staff screens can
 * spotlight tour targets (`Modifier.staffTourTarget`); the "N of 6" banner itself is drawn natively above the bar.
 */
private fun hostedStaff(content: @Composable () -> Unit): UIViewController =
    hosted { StaffTourHost(showBanner = false) { content() } }

fun StaffOverviewViewController(): UIViewController = hostedStaff { StaffOverviewTabRoot() }
fun StaffInquiriesViewController(): UIViewController = hostedStaff { StaffInquiriesTabRoot() }
fun StaffProfileViewController(): UIViewController = hostedStaff { StaffProfileTabRoot() }

/** The New Inquiry flow, presented full-screen by the staff shell; [onClose] dismisses it. */
fun NewInquiryViewController(onClose: () -> Unit): UIViewController = hostedStaff { NewInquiryRoot(onClose = onClose) }
