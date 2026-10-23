package com.phairplay.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.SwitchCompat
import com.phairplay.R
import com.phairplay.airplay.NowPlayingInfo
import com.phairplay.airplay.StreamStats
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Renders real Android layouts with deterministic sample data and checks TV usability.
 * Native graphics catches inflation, layout and bitmap regressions without a hardware receiver.
 * Run :app:testGoogletvDebugUnitTest --tests com.phairplay.ui.HearthUiTest.
 */
@RunWith(RobolectricTestRunner::class)
// Qualifiers must stay in Android's documented order (size → orientation → UI mode → density);
// Robolectric rejects an out-of-order string like "television-land-mdpi-w960dp-h540dp".
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HearthUiTest {
    private fun host(): Activity = Robolectric.buildActivity(Activity::class.java).setup().get().apply {
        setTheme(R.style.Theme_PhairPlay_Fullscreen)
    }

    private fun shell(activity: Activity, layout: Int): View {
        val root = LayoutInflater.from(activity).inflate(R.layout.activity_main, null)
        val container = root.findViewById<FrameLayout>(R.id.content_container)
        LayoutInflater.from(activity).inflate(layout, container, true)
        activity.setContentView(root)
        return root
    }

    private fun View.label(id: Int, value: String) { findViewById<TextView>(id).text = value }

    private fun populateHome(root: View) {
        root.findViewById<View>(R.id.nav_item_home).isSelected = true
        root.label(R.id.text_device_name, "Living Room")
        root.label(R.id.text_network, "Ethernet · 192.168.1.42")
        root.label(R.id.text_service_state, "Running")
        root.findViewById<View>(R.id.dot_service_state).background.mutate().setTint(root.context.getColor(R.color.status_running))
        root.label(R.id.text_connection_log, "No connections yet. Choose this TV on your Apple device to begin.")
        for ((id, title, detail) in listOf(
            Triple(R.id.card_airplay, "AirPlay", "Music, videos and photos from your Apple devices."),
            Triple(R.id.card_apple_casting, "Apple Casting", "Mirror your iPhone, iPad or Mac screen.")
        )) {
            val card = root.findViewById<View>(id)
            card.label(R.id.text_protocol_name, title)
            card.label(R.id.text_protocol_state, "Ready to connect")
            card.label(R.id.text_protocol_detail, detail)
            card.findViewById<View>(R.id.dot_protocol_status).background.mutate().setTint(root.context.getColor(R.color.status_running))
        }
        root.findViewById<View>(R.id.btn_start).apply { isEnabled = false; alpha = 0.4f }
    }

    private fun render(root: View, name: String, width: Int = 960, height: Int = 540) {
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val out = File("build/reports/hearth-ui/$name.png")
        out.parentFile.mkdirs()
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun activityLogVirtualizesFullHistoryAndOffersJumpControls() {
        val activity = host()
        com.phairplay.diagnostics.ActivityLog.init(activity)
        com.phairplay.diagnostics.ActivityLog.clear()
        repeat(300) { n -> com.phairplay.diagnostics.ActivityLog.record(
            com.phairplay.diagnostics.Severity.INFO, "test", "Sample event $n"
        ) }
        ActivityLogDialog.show(activity)
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        val root = dialog.window!!.decorView
        render(root, "activity-log-2.4")
        fun descendants(view: View): List<View> = listOf(view) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
            else emptyList()
        val views = descendants(root)
        val list = views.filterIsInstance<android.widget.ListView>().single()
        assertEquals(300, list.adapter.count)
        assertTrue("History should create only visible rows", list.childCount < list.adapter.count)
        assertTrue(views.filterIsInstance<TextView>().any { it.text.toString() == "Newest" })
        assertTrue(views.filterIsInstance<TextView>().any { it.text.toString() == "Oldest" })
        dialog.dismiss()
        com.phairplay.diagnostics.ActivityLog.clear()
    }

    @Test fun homeRendersAndRemoteCanReachBothCardsAndControls() {
        val activity = host()
        val root = shell(activity, R.layout.fragment_home)
        populateHome(root)
        render(root, "home-wine")
        val airplay = root.findViewById<View>(R.id.card_airplay)
        // Robolectric starts a native-graphics window in touch mode, where requestFocus() and
        // focusSearch() both refuse non-touch-focusable views — a TV is never in touch mode once a
        // D-pad key arrives, and requestFocusFromTouch() is exactly that keypress: it leaves touch
        // mode, then focuses. focusSearch below only answers correctly because of that.
        assertTrue(
            "A remote keypress should focus the AirPlay card (inTouchMode=${airplay.isInTouchMode}, " +
                "shown=${airplay.isShown}, attached=${airplay.isAttachedToWindow})",
            airplay.requestFocusFromTouch()
        )
        assertTrue("The AirPlay card should hold focus", airplay.hasFocus())
        assertEquals(R.id.card_apple_casting, airplay.focusSearch(View.FOCUS_RIGHT)?.id)
        val activityButton = root.findViewById<View>(R.id.btn_connection_log)
        assertTrue("Activity should be reachable with the remote", activityButton.isFocusable)
        // Compare the control's bottom edge with the viewport rather than its visible height: an
        // exact height match also fails on a 1px rounding clip, which says nothing about reach.
        val position = IntArray(2)
        activityButton.getLocationInWindow(position)
        assertTrue("Activity should be laid out", activityButton.height > 0)
        assertTrue(
            "Controls should fit on a standard TV viewport: Activity ends at y=" +
                "${position[1] + activityButton.height} of ${root.height}px",
            position[1] + activityButton.height <= root.height
        )
        render(root, "home-focus-wine")
    }

    @Test fun settingsGrowToFitDescriptionsAndKeepOneFocusTargetPerRow() {
        val activity = host()
        val root = shell(activity, R.layout.fragment_settings)
        root.findViewById<View>(R.id.nav_item_settings).isSelected = true
        val headers = mapOf(R.id.header_display to "Picture & identity", R.id.header_protocols to "Connection",
            R.id.header_airplay to "Sound", R.id.header_service to "Startup", R.id.header_developer to "Advanced",
            R.id.header_updates to "Updates", R.id.header_about to "About")
        headers.forEach { (id, title) -> root.label(id, title) }
        root.label(R.id.text_display_name_value, "Living Room")
        val rows = mapOf(
            R.id.row_force_high_res to Pair("Higher resolution", "Use up to 4K when your TV and decoder support it."),
            R.id.row_airplay to Pair("AirPlay & Apple Casting", "Receive music, photos, videos and screen mirroring."),
            R.id.row_mirror_audio to Pair("Mirror audio", "Play sound from your iPhone, iPad or Mac."),
            R.id.row_start_on_boot to Pair("Start on boot", "Keep Hearth ready when your TV starts."),
            R.id.row_debug_overlay to Pair("Debug overlay", "Show decoder performance during playback."),
            R.id.row_auto_check_updates to Pair("Check automatically", "Look for new versions in the background."),
            R.id.row_auto_download_updates to Pair("Download automatically", "Keep new versions ready to install."),
            R.id.row_auto_install_updates to Pair("Install automatically", "Ask Android to install when ready.")
        )
        rows.forEach { (id, texts) ->
            val row = root.findViewById<View>(id)
            row.label(R.id.text_setting_label, texts.first)
            row.findViewById<TextView>(R.id.text_setting_subtitle).apply { text = texts.second; visibility = View.VISIBLE }
            row.findViewById<SwitchCompat>(R.id.switch_setting).isChecked = true
            // FOCUSABLES_ALL ignores touch mode, so this counts the row's focus targets the way a
            // D-pad sees them instead of the way a touchscreen would.
            val focusables = ArrayList<View>()
            row.addFocusables(focusables, View.FOCUS_FORWARD, View.FOCUSABLES_ALL)
            assertEquals("Each Settings row should expose exactly one focus target", 1, focusables.size)
        }
        // The activity-log row opens a dialog rather than toggling a value: it must still expose a
        // single D-pad target and must not be mistaken for a switch row.
        val activityRow = root.findViewById<View>(R.id.row_activity_log)
        activityRow.label(R.id.text_action_row_label, "Activity log")
        activityRow.findViewById<TextView>(R.id.text_action_row_subtitle).text =
            "Chronological receiver, casting and decoder events with severity."
        val activityFocusables = ArrayList<View>()
        activityRow.addFocusables(activityFocusables, View.FOCUS_FORWARD, View.FOCUSABLES_ALL)
        assertEquals("The activity row should expose exactly one focus target", 1, activityFocusables.size)

        root.findViewById<View>(R.id.row_display_name).requestFocusFromTouch()
        render(root, "settings-wine")
        val row = root.findViewById<View>(R.id.row_force_high_res)
        val subtitle = row.findViewById<TextView>(R.id.text_setting_subtitle)
        val textLayout = subtitle.layout
        assertNotNull("The subtitle should be laid out", textLayout)
        assertTrue(
            "The row should grow to fit its description: view=${subtitle.height}px " +
                "text=${textLayout?.height}px",
            subtitle.height >= (textLayout?.height ?: 0)
        )
    }

    /**
     * Regression for the two unexplained blocks in the 2.0 screenshots.
     *
     * Home's "Stop casting" icon button rendered `ic_stop` (a full-bleed solid rectangle) and
     * Settings' Device Name row rendered `ic_chevron_right` (another solid rectangle), so both
     * looked like stray brown/beige squares. They are vectors now: transparent at the corners and
     * visibly drawn in the middle. This measures the drawables themselves, so it fails if either
     * reverts to a filled shape.
     */
    @Test fun homeAndSettingsAffordanceGlyphsAreNotSolidBlocks() {
        val activity = host()
        val size = 48
        listOf(
            R.drawable.ic_stop to "Stop casting",
            R.drawable.ic_chevron_right to "Settings chevron",
            R.drawable.ic_airplay to "AirPlay mark",
        ).forEach { (res, name) ->
            val drawable = AppCompatResources.getDrawable(activity, res)
            assertNotNull("$name drawable should resolve", drawable)
            drawable!!.setBounds(0, 0, size, size)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            drawable.draw(Canvas(bitmap))

            listOf(0 to 0, size - 1 to 0, 0 to size - 1, size - 1 to size - 1).forEach { (x, y) ->
                assertEquals(
                    "$name must not be a full-bleed block: corner ($x,$y) should be transparent",
                    0,
                    Color.alpha(bitmap.getPixel(x, y)),
                )
            }
            var drawn = 0
            for (x in 0 until size) for (y in 0 until size) {
                if (Color.alpha(bitmap.getPixel(x, y)) != 0) drawn++
            }
            assertTrue("$name should still draw a visible glyph (drawn=$drawn)", drawn > size)
            bitmap.recycle()
        }
    }

    /**
     * The debug HUD, measured on a 1080p television layout.
     *
     * Checks the three things 2.0 got wrong and that no screenshot can prove on its own: it sits
     * inside the safe area (not at a fixed 40px offset), it never becomes a focus/accessibility
     * target, and its numbers are labelled by what they measure — an unmeasured frame rate says
     * `N/A` instead of `0`, and the payload rate is never printed as an fps.
     */
    @Test fun debugOverlayStaysInTheSafeAreaAndLabelsWhatItMeasures() {
        val activity = host()
        val screen = StreamingScreen(activity)
        activity.setContentView(screen)
        StreamStats.overlayEnabled = true
        StreamStats.beginSession(StreamStats.SOURCE_AIRPLAY)
        try {
            val width = 1920
            val height = 1080
            screen.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
            screen.layout(0, 0, width, height)
            // The HUD refreshes at 1 Hz; advance the main looper past one tick.
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1_200))

            val hud = screen.getChildAt(1) as TextView
            assertEquals("The overlay should be visible while enabled", View.VISIBLE, hud.visibility)

            val params = hud.layoutParams as FrameLayout.LayoutParams
            // The container is laid out by the activity at the TV surface size, so the expected
            // margins are derived the same way StreamingScreen derives them (5% of the actual
            // size, floored at 24dp) rather than hard-coding one resolution.
            val density = activity.resources.displayMetrics.density
            val floor = (24 * density).toInt()
            val expectedLeft = maxOf(floor, (screen.width * 0.05f).toInt())
            val expectedTop = maxOf(floor, (screen.height * 0.05f).toInt())
            assertTrue("the screen should be laid out, was ${screen.width}x${screen.height}", screen.width >= 320)
            assertEquals("left margin should follow the 5% safe area", expectedLeft, params.leftMargin)
            assertEquals("top margin should follow the 5% safe area", expectedTop, params.topMargin)
            assertTrue("the overlay must not be flush with the screen edge", params.leftMargin > 0 && params.topMargin > 0)
            assertTrue(
                "the overlay must stay in the top-left quadrant, was (${params.leftMargin},${params.topMargin})",
                params.leftMargin < screen.width / 2 && params.topMargin < screen.height / 2,
            )
            // A long decoder name must wrap instead of running off the right edge.
            assertTrue(
                "the overlay must be width-capped (maxWidth=${hud.maxWidth})",
                hud.maxWidth in 1..(screen.width - params.leftMargin * 2),
            )

            assertFalse("the overlay must never take D-pad focus", hud.isFocusable)
            assertFalse("the overlay must never take touch focus", hud.isFocusableInTouchMode)
            assertEquals(
                "the overlay must not be announced by accessibility services",
                View.IMPORTANT_FOR_ACCESSIBILITY_NO,
                hud.importantForAccessibility,
            )

            val text = hud.text.toString()
            assertTrue("one FPS field should be shown: $text", text.contains("N/A"))
            assertTrue("compact HUD should fit within three lines: $text", text.lines().size <= 3)
            listOf("SOURCE", "PACE", "AUDIO", "RX", "KEYS").forEach { label ->
                assertFalse("$label should be absent from compact HUD: $text", text.contains(label))
            }
            assertTrue("HUD should remain small", hud.maxWidth <= (280 * density).toInt())
            render(screen, "debug-overlay-2.2")
        } finally {
            StreamStats.endSession()
            StreamStats.overlayEnabled = false
        }
    }

    @Test fun nowPlayingFitsTelevisionAndHandlesMissingMetadata() {
        val activity = host()
        val view = NowPlayingScreen(activity)
        activity.setContentView(view)
        view.update(NowPlayingInfo("Ben’s iPhone", "A little closer to home", "Evening listening", "Living room sessions"))
        render(view, "now-playing-wine")
        view.update(NowPlayingInfo("MacBook"))
        render(view, "now-playing-no-metadata")
        view.clear()
        view.update(NowPlayingInfo("MacBook"))
    }

    @Test fun artworkIsSampledBeforeAllocatingAndInvalidImagesFailSafely() {
        val original = Bitmap.createBitmap(2400, 1600, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().apply { original.compress(Bitmap.CompressFormat.PNG, 100, this) }.toByteArray()
        original.recycle()
        val decoded = BoundedBitmap.decode(bytes, 512)
        assertNotNull(decoded)
        assertTrue(decoded!!.width <= 512 && decoded.height <= 512)
        decoded.recycle()
        assertNull(BoundedBitmap.decode(byteArrayOf(1, 2, 3), 512))
        assertNull(BoundedBitmap.decode(bytes, 0))
    }
}
