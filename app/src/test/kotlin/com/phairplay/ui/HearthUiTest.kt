package com.phairplay.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import com.phairplay.R
import com.phairplay.airplay.NowPlayingInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
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
