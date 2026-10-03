package com.phairplay.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import com.phairplay.R
import com.phairplay.util.NetworkUtils

/**
 * WaitingScreen — The idle screen shown when no AirPlay sender is connected.
 *
 * WHY: Users need visual confirmation that Hearth is running and ready to receive.
 * Without this screen, the TV would show a black screen and users wouldn't know
 * if the app is working or not.
 *
 * HOW: A simple full-screen view showing:
 * - The app name ("Hearth")
 * - The device's AirPlay name (what appears in macOS AirPlay picker)
 * - Brief instructions for the user
 *
 * Created programmatically in MainActivity.createScreens() and added to
 * the waiting_container FrameLayout.
 *
 * Example:
 *   val waitingScreen = WaitingScreen(context)
 *   container.addView(waitingScreen)
 *   // The screen reads the device name automatically from Android settings
 */
class WaitingScreen @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {

    init {
        // Inflate the layout for this screen
        LayoutInflater.from(context).inflate(R.layout.screen_waiting, this, true)
        populateDeviceName()
    }

    /**
     * Reads the device's display name from Android settings and shows it on screen.
     *
     * The device name is what macOS users will see in the AirPlay picker, so it's
     * important to show the exact same name here. This way users know which device
     * to select on their Mac.
     *
     * The name is read at startup and doesn't change while the app is running.
     */
    private fun populateDeviceName() {
        val deviceName = NetworkUtils.getDeviceName(context)
        findViewById<TextView>(R.id.text_device_name).text = deviceName
    }
}
