package com.vesper.baubuild.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import com.vesper.baubuild.designsystem.BauColors
import com.vesper.baubuild.designsystem.BauTheme
import com.vesper.baubuild.feature.auth.AuthRoot
import com.vesper.baubuild.feature.mywork.MyWorkTabRoot
import com.vesper.baubuild.feature.profile.ProfileTabRoot
import com.vesper.baubuild.feature.projects.ProjectsTabRoot
import com.vesper.baubuild.feature.review.ReviewTabRoot
import com.vesper.baubuild.feature.search.SearchRoot
import platform.UIKit.UIViewController

/*
 * One UIViewController per Compose root, hosted by the native SwiftUI shell (iosApp/RootView.swift).
 * The SwiftUI side lets each controller extend under the Liquid Glass tab bar; the tab bar height is
 * part of the view's safeAreaInsets, so roots that pad with WindowInsets.safeDrawing / systemBars keep
 * their last item above the bar while content scrolls beneath it.
 */

private fun hosted(content: @Composable () -> Unit): UIViewController =
    ComposeUIViewController {
        BauTheme {
            Box(Modifier.fillMaxSize().background(BauColors.Paper)) { content() }
        }
    }

fun AuthViewController(): UIViewController = hosted { AuthRoot() }
fun ProjectsViewController(): UIViewController = hosted { ProjectsTabRoot() }
fun MyWorkViewController(): UIViewController = hosted { MyWorkTabRoot() }
fun ReviewViewController(): UIViewController = hosted { ReviewTabRoot() }
fun ProfileViewController(): UIViewController = hosted { ProfileTabRoot() }
fun SearchViewController(): UIViewController = hosted { SearchRoot() }
