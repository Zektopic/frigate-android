package com.zektopic.frigate.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.activity.ComponentActivity
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.zektopic.frigate.ai.MotionBox
import com.zektopic.frigate.data.CameraConfigEntity
import com.zektopic.frigate.data.EventEntity
import com.zektopic.frigate.data.ReviewSeverity
import com.zektopic.frigate.data.UiSettings
import com.zektopic.frigate.media.StreamState
import com.zektopic.frigate.service.CameraStats
import com.zektopic.frigate.service.StatsSample
import com.zektopic.frigate.ui.live.LiveCameraView
import com.zektopic.frigate.ui.settings.SettingsPages
import com.zektopic.frigate.ui.settings.SettingsScreen
import com.zektopic.frigate.ui.theme.FrigateAndroidTheme
import com.zektopic.frigate.ui.theme.FrigateTheme
import com.zektopic.frigate.ui.theme.ThemeMode
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.sin

/**
 * Renders every page to `docs/screenshots/` so a change to the UI can be looked at
 * without a device. Skipped unless asked for:
 *
 *     ./gradlew testDebugUnitTest -Pscreenshots
 *
 * The camera pictures are generated gradients, not footage.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ScreenshotTest {

    private val outputDir = File(System.getProperty("frigate.screenshots.dir") ?: "build/screenshots")

    @Before
    fun onlyWhenAsked() {
        assumeTrue(System.getProperty("frigate.screenshots") == "true")
        outputDir.mkdirs()
    }

    private val cameras = listOf(
        camera("front_door", "Front Door"),
        camera("driveway", "Driveway"),
        camera("back_garden", "Back Garden"),
        camera("garage", "Garage"),
        camera("kitchen", "Kitchen"),
        camera("side_gate", "Side Gate", enabled = false)
    )

    private fun camera(id: String, name: String, enabled: Boolean = true) = CameraConfigEntity(
        id = id,
        name = name,
        rtspUrl = "rtsp://admin:secret@192.168.1.9:8554/$id",
        detectRtspUrl = "rtsp://192.168.1.9:8554/${id}_sub",
        isEnabled = enabled
    )

    private val configYaml = """
        cameras:
          front_door:
            ffmpeg:
              inputs:
                - path: rtsp://192.168.1.9:8554/front_door
            zones:
              porch:
                coordinates: 0.05,0.55,0.55,0.5,0.6,0.95,0.05,0.95
              path:
                coordinates: 0.62,0.45,0.95,0.5,0.95,0.95,0.66,0.95
            motion:
              mask:
                - 0,0,0.35,0,0.35,0.08,0,0.08
    """.trimIndent()

    private val now = System.currentTimeMillis()

    private val events = (0 until 14).map { i ->
        EventEntity(
            id = i + 1L,
            cameraId = cameras[i % 5].id,
            label = "motion",
            confidence = 1f,
            timestamp = now - (i * 47L + 3) * 60_000L,
            snapshotPath = null,
            videoPath = null,
            zone = if (i % 3 == 0) "porch" else null,
            severity = if (i % 4 == 3) ReviewSeverity.DETECTION.key else ReviewSeverity.ALERT.key,
            hasBeenReviewed = i % 5 == 4,
            endTime = if (i == 0) null else now - (i * 47L + 2) * 60_000L
        )
    }

    private val stats = (0 until 60).map { i ->
        StatsSample(
            timestamp = now - (60 - i) * 2_000L,
            cpuPercent = 14f + 9f * sin(i / 5f) + if (i in 40..46) 22f else 0f,
            memoryPercent = 61f + 2f * sin(i / 9f),
            heapMb = 96f,
            motionMs = 2.4f + sin(i / 3f),
            cameras = cameras.filter { it.isEnabled }.associate { camera ->
                camera.id to CameraStats(
                    cameraFps = 5f + 0.4f * sin(i / 4f + camera.id.length),
                    detectFps = 4.8f + 0.3f * sin(i / 6f),
                    skippedFps = if (i % 17 == 0) 0.5f else 0f,
                    motionMs = 2.2f + sin(i / 3f + camera.id.length)
                )
            }
        )
    }

    private val state = NvrUiState(
        cameras = cameras,
        events = events,
        configYaml = configYaml,
        streamStates = mapOf(
            "front_door" to StreamState.LIVE,
            "driveway" to StreamState.LIVE,
            "back_garden" to StreamState.LIVE,
            "garage" to StreamState.OFFLINE,
            "kitchen" to StreamState.CONNECTING
        ),
        activeMotion = setOf("front_door", "driveway"),
        activeAlerts = setOf("front_door"),
        stats = stats,
        isServiceRunning = true,
        ui = UiSettings(themeMode = ThemeMode.DARK)
    )

    private val frames: Map<String, Bitmap> = cameras.take(3).mapIndexed { index, camera ->
        camera.id to sampleFrame(index)
    }.toMap()

    /** A stand-in for a camera picture: sky over ground, tinted differently per camera. */
    private fun sampleFrame(seed: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val skies = intArrayOf(0xFF6F8FAF.toInt(), 0xFF8A9A6B.toInt(), 0xFF9A7F6B.toInt())
        val grounds = intArrayOf(0xFF2F3B2F.toInt(), 0xFF3A3A32.toInt(), 0xFF2B3138.toInt())
        val paint = Paint()
        paint.shader = LinearGradient(0f, 0f, 0f, 360f, skies[seed % 3], grounds[seed % 3], Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, 640f, 360f, paint)
        paint.shader = null
        paint.color = 0x55000000
        canvas.drawRect(60f + seed * 90f, 150f, 200f + seed * 90f, 360f, paint)
        paint.color = 0x33FFFFFF
        canvas.drawRect(380f - seed * 40f, 210f, 600f - seed * 40f, 360f, paint)
        return bitmap
    }

    private val actions = object : NvrActions {
        override suspend fun saveConfig(yaml: String) = Unit
        override fun startService() = Unit
        override fun stopService() = Unit
        override fun sendTestNotification() = Unit
        override suspend fun clearAllRecordings() = Unit
        override fun setReviewed(eventIds: List<Long>, reviewed: Boolean) = Unit
        override fun deleteEvents(events: List<EventEntity>) = Unit
        override fun motionBoxes(cameraId: String) = listOf(MotionBox(150, 150, 260, 330))
        override fun updateUi(settings: UiSettings) = Unit
    }

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun capture(name: String, mode: ThemeMode = ThemeMode.DARK, content: @Composable () -> Unit) {
        // The pages animate forever (a pulsing motion dot, spinners), so the clock is
        // driven by hand: waiting for the UI to go idle would never return.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            FrigateAndroidTheme(themeMode = mode) {
                Box(Modifier.fillMaxSize().background(FrigateTheme.colors.background)) { content() }
            }
        }
        compose.mainClock.advanceTimeBy(600)
        compose.onRoot().captureRoboImage(File(outputDir, "$name.png").path)
    }

    private fun shell(name: String, page: Page, mode: ThemeMode = ThemeMode.DARK, uiState: NvrUiState = state) {
        capture(name, mode) { FrigateAppShell(uiState.copy(ui = uiState.ui.copy(themeMode = mode)), frames, actions, startPage = page) }
    }

    // --- Tablet / landscape: the desktop layout -------------------------------

    @Test @Config(qualifiers = TABLET) fun wideLive() = shell("wide-live", Page.LIVE)
    @Test @Config(qualifiers = TABLET) fun wideLiveLight() = shell("wide-live-light", Page.LIVE, ThemeMode.LIGHT)
    @Test @Config(qualifiers = TABLET) fun wideReview() = shell("wide-review", Page.REVIEW)
    @Test @Config(qualifiers = TABLET) fun wideSystem() = shell("wide-system", Page.SYSTEM)
    @Test @Config(qualifiers = TABLET) fun wideSettings() = shell("wide-settings", Page.SETTINGS)
    @Test @Config(qualifiers = TABLET) fun wideConfig() = shell("wide-config", Page.CONFIG)

    @Test @Config(qualifiers = TABLET)
    fun wideStopped() = shell("wide-stopped", Page.LIVE, uiState = state.copy(isServiceRunning = false, stats = emptyList()))

    @Test @Config(qualifiers = TABLET)
    fun wideCamera() = capture("wide-camera") {
        LiveCameraView(cameras[0], state, frames[cameras[0].id], actions, wide = true, onBack = {}, onOpenHistory = {})
    }

    @Test @Config(qualifiers = TABLET)
    fun wideMasksAndZones() = capture("wide-masks-zones") {
        SettingsScreen(state, frames, actions, wide = true, pageKey = SettingsPages.MASKS_AND_ZONES, onPageChange = {})
    }

    @Test @Config(qualifiers = TABLET)
    fun wideCameraManagement() = capture("wide-camera-management") {
        SettingsScreen(state, frames, actions, wide = true, pageKey = SettingsPages.CAMERA_MANAGEMENT, onPageChange = {})
    }

    // --- Phone, upright: the mobile layout -----------------------------------

    @Test @Config(qualifiers = PHONE) fun phoneLive() = shell("phone-live", Page.LIVE)
    @Test @Config(qualifiers = PHONE) fun phoneReview() = shell("phone-review", Page.REVIEW)
    @Test @Config(qualifiers = PHONE) fun phoneSystem() = shell("phone-system", Page.SYSTEM)
    @Test @Config(qualifiers = PHONE) fun phoneSettings() = shell("phone-settings", Page.SETTINGS)

    @Test @Config(qualifiers = PHONE)
    fun phoneMotionTuner() = capture("phone-motion-tuner") {
        SettingsScreen(state, frames, actions, wide = false, pageKey = SettingsPages.MOTION_TUNER, onPageChange = {})
    }

    private companion object {
        const val TABLET = "w1280dp-h800dp-land-mdpi"
        const val PHONE = "w412dp-h892dp-port-xhdpi"
    }
}
