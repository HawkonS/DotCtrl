package tech.hawkon.dotctrl.updates

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowActivity
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowPackageManager
import tech.hawkon.dotctrl.BuildConfig
import tech.hawkon.dotctrl.ui.DotCtrlTheme
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w400dp-h900dp")
class UpdateUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun updateDialogKeepsItsBoundsWhenTheCheckFinishes() {
        val state = mutableStateOf(UpdateState(phase = UpdatePhase.CHECKING))
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.get().setContent {
            DotCtrlTheme(darkTheme = false) {
                UpdateDialog(state.value, {}, {}, {}, {}, {}, {})
            }
        }
        try {
            val checkingBounds = compose.onNode(isDialog()).fetchSemanticsNode().boundsInRoot
            val release = ReleaseInfo("1.0.8", "修复停止按钮\n".repeat(30), RELEASES_URL,
                ReleaseApk("$RELEASES_URL/download/v1.0.8/app-release.apk", 100))
            val results = listOf(
                UpdateState(phase = UpdatePhase.CURRENT),
                UpdateState(phase = UpdatePhase.CURRENT, noRelease = true),
                UpdateState(phase = UpdatePhase.ERROR, error = UpdateError.NETWORK),
                UpdateState(phase = UpdatePhase.AVAILABLE, release = release),
                UpdateState(phase = UpdatePhase.DOWNLOADING, release = release, downloaded = 50, total = 100),
                UpdateState(phase = UpdatePhase.READY, release = release),
            )
            results.forEach { result ->
                compose.runOnIdle { state.value = result }
                assertEquals("Dialog moved or resized for ${result.phase}", checkingBounds,
                    compose.onNode(isDialog()).fetchSemanticsNode().boundsInRoot)
            }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun downloadPermissionReturnAndInstallerCancellationPreserveThePackage() {
        val backend = Downloads()
        val model = UpdateViewModel(object : ReleaseSource {
            override suspend fun latest() = ReleaseInfo("1.0.8", "修复停止按钮", "$RELEASES_URL/tag/v1.0.8",
                ReleaseApk("$RELEASES_URL/download/v1.0.8/app-release.apk", 100))
        }, backend, "1.0.7", Dispatchers.Main.immediate)
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val shadow = Shadow.extract<ShadowActivity>(activity)
        val packages = Shadow.extract<ShadowPackageManager>(activity.packageManager)
        packages.setCanRequestPackageInstalls(false)
        activity.setContent {
            DotCtrlTheme(darkTheme = false) { UpdateHost(model); UpdateSettingsRow(model) }
        }
        try {
            compose.onNodeWithText(BuildConfig.VERSION_NAME).assertIsDisplayed()
            compose.onNodeWithText("检查更新").assertDoesNotExist()
            compose.onNodeWithText("版本信息").performClick()
            compose.onNodeWithText("修复停止按钮").assertIsDisplayed()
            compose.onNodeWithText("下载并安装").performClick()
            compose.onNodeWithText("取消下载").assertExists()
            compose.onNodeWithText("稍后").performClick()
            compose.onNodeWithText("取消下载").assertDoesNotExist()
            assertEquals(1, backend.starts)
            backend.complete = true
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(600))
            compose.waitForIdle()
            val permission = shadow.nextStartedActivityForResult
            assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, permission.intent.action)
            assertEquals("package:${activity.packageName}", permission.intent.data.toString())
            shadow.receiveResult(permission.intent, Activity.RESULT_CANCELED, null)
            compose.onNodeWithText("尚未允许点控安装更新。可点击“立即安装”重新授权。").assertIsDisplayed()
            compose.onNodeWithText(BuildConfig.VERSION_NAME).assertExists()
            assertEquals(UpdatePhase.READY, model.state.value.phase)
            assertEquals(1, backend.starts)

            // An allowed return resumes installation without downloading a second copy.
            compose.onAllNodesWithText("立即安装").filter(hasAnyAncestor(isDialog())).onFirst().performClick()
            compose.waitForIdle()
            val secondPermission = shadow.nextStartedActivityForResult
            packages.setCanRequestPackageInstalls(true)
            shadow.receiveResult(secondPermission.intent, Activity.RESULT_CANCELED, null)
            compose.waitForIdle()
            val install = shadow.nextStartedActivityForResult
            assertEquals(Intent.ACTION_VIEW, install.intent.action)
            assertEquals("application/vnd.android.package-archive", install.intent.type)
            assertTrue(install.intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals("content", install.intent.data!!.scheme)
            shadow.receiveResult(install.intent, Activity.RESULT_CANCELED, null)
            compose.waitForIdle()
            assertEquals(UpdatePhase.READY, model.state.value.phase)
            assertFalse(model.state.value.installRequested)
            assertEquals(1, backend.starts)
        } finally {
            model.setForeground(false)
            controller.pause().stop().destroy()
        }
    }

    private class Downloads : UpdateDownloads {
        var complete = false
        var starts = 0
        override fun restore(): DownloadTask? = null
        override fun start(release: ReleaseInfo) = DownloadTask((++starts).toLong(), release)
        override fun query(task: DownloadTask) = DownloadSnapshot(complete, 50, 100)
        override fun discard(task: DownloadTask) = Unit
        override fun verify(task: DownloadTask) = Unit
        override fun installerUri(task: DownloadTask): Uri = Uri.parse("content://tech.hawkon.dotctrl.updates/updates/update.apk")
    }
}
