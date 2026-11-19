package com.vesper.baubuild.feature.appstatus

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vesper.baubuild.core.session.DeviceInfo
import com.vesper.baubuild.data.http.config.AppConfigRepository
import com.vesper.baubuild.data.http.config.AppStatus
import com.vesper.baubuild.data.http.config.OpenAppConfigRepository
import com.vesper.baubuild.designsystem.BauColors
import com.vesper.baubuild.designsystem.BauPrimaryButton
import com.vesper.baubuild.designsystem.BauTextLink
import com.vesper.baubuild.feature.auth.BauBuildMark
import kotlin.concurrent.Volatile
import kotlinx.coroutines.launch

/**
 * Where the app-status UI finds its repository. AppGraph installs the real one once
 * (`AppStatusHost.repository = appConfigRepository`); until then everything is open (fail open).
 */
object AppStatusHost {
    @Volatile
    var repository: AppConfigRepository = OpenAppConfigRepository()
}

/** Store / download pages per platform. TODO: replace the placeholders once the listings exist. */
object UpdateLinks {
    var ios: String = "https://apps.apple.com/app/id0000000000"
    var android: String = "https://play.google.com/store/apps/details?id=com.vesper.baubuild"
    var desktop: String = "https://baubuild.vesperconnect.org/download"

    fun forPlatform(platform: String): String = when (platform) {
        "ios" -> ios
        "android" -> android
        else -> desktop
    }
}

/** Fetches `/v1/config` when the host first resumes (launch) and on every resume after (throttled in the repo). */
@Composable
private fun RefreshOnResume(repository: AppConfigRepository) {
    val scope = rememberCoroutineScope()
    LifecycleResumeEffect(repository) {
        scope.launch { repository.refresh() }
        onPauseOrDispose { }
    }
}

/**
 * Slim, non-blocking maintenance banner (`maintenance.message` from `/v1/config`). Renders nothing when there is
 * no message. Mount at the top of each shell, above the content.
 */
@Composable
fun AppStatusBanner(
    modifier: Modifier = Modifier,
    repository: AppConfigRepository = AppStatusHost.repository,
) {
    val status by repository.status.collectAsStateWithLifecycle()
    RefreshOnResume(repository)
    // Keep the last text while the banner animates out.
    val lastMessage = remember { arrayOfNulls<String>(1) }
    status.maintenanceMessage?.let { lastMessage[0] = it }
    AnimatedVisibility(
        visible = status.maintenanceMessage != null,
        enter = expandVertically(),
        exit = shrinkVertically(),
        modifier = modifier,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(BauColors.WarningSoft)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                lastMessage[0].orEmpty(),
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = BauColors.WarningInk,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Shows [content] unless this app version is below `min_supported` for this platform, in which case a blocking
 * "Update BAU BUILD" screen replaces it. Fails open: no config, an unparseable version or a failed fetch never
 * blocks. Wrap each shell's root with it.
 */
@Composable
fun UpdateRequiredGate(
    modifier: Modifier = Modifier,
    repository: AppConfigRepository = AppStatusHost.repository,
    content: @Composable () -> Unit,
) {
    val status by repository.status.collectAsStateWithLifecycle()
    RefreshOnResume(repository)
    if (status.updateRequired) {
        val scope = rememberCoroutineScope()
        UpdateRequiredScreen(status, onCheckAgain = { scope.launch { repository.refresh(force = true) } }, modifier)
    } else {
        content()
    }
}

@Composable
private fun UpdateRequiredScreen(status: AppStatus, onCheckAgain: () -> Unit, modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    val url = UpdateLinks.forPlatform(DeviceInfo.current.platform)
    Box(
        modifier.fillMaxSize().background(BauColors.Paper).safeDrawingPadding().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 420.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BauBuildMark(Modifier.size(56.dp))
            Text(
                "Update BAU BUILD",
                modifier = Modifier.semantics { heading() },
                fontSize = 26.sp,
                lineHeight = 32.sp,
                fontWeight = FontWeight.Bold,
                color = BauColors.Ink,
                textAlign = TextAlign.Center,
            )
            Text(
                buildString {
                    append("This version")
                    status.currentVersion?.let { append(" ($it)") }
                    append(" is no longer supported. Update")
                    status.minimumVersion?.let { append(" to $it or later") }
                    append(" to keep building.")
                },
                fontSize = 16.sp,
                lineHeight = 22.sp,
                color = BauColors.TextSecondary,
                textAlign = TextAlign.Center,
            )
            BauPrimaryButton(
                text = "Update",
                onClick = { runCatching { uriHandler.openUri(url) } },
                modifier = Modifier.fillMaxWidth(),
            )
            BauTextLink("Check again", onClick = onCheckAgain)
        }
    }
}
