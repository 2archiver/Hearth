package com.phairplay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.phairplay.service.PhairPlayService
import com.phairplay.service.PhotoFrame
import com.phairplay.service.ProtocolState
import com.phairplay.service.ServiceController
import com.phairplay.airplay.NowPlayingInfo
import com.phairplay.ui.HomeFragment
import com.phairplay.ui.NowPlayingScreen
import com.phairplay.ui.PhotoScreen
import com.phairplay.ui.SettingsFragment
import com.phairplay.ui.StreamingScreen
import kotlinx.coroutines.Job
import androidx.activity.addCallback
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * MainActivity — The single Activity hosting Hearth's navigation and fragments.
 *
 * WHY: Hearth uses a single-Activity architecture with Fragment-based navigation.
 * This is the recommended pattern for Android TV apps: one Activity with swappable
 * Fragments avoids the overhead of Activity transitions and keeps the Leanback
 * launcher integration simple.
 *
 * Layout structure:
 *   ┌─ Nav Panel ──┬─ Content (FrameLayout) ─────────────────┐
 *   │  Home        │  HomeFragment  OR  SettingsFragment      │
 *   │  Settings    │                                          │
 *   └──────────────┴──────────────────────────────────────────┘
 *   [streaming_container] — full-screen overlay (GONE when idle)
 *
 * HOW: D-pad left/right navigation between nav panel and content area.
 * The nav panel items switch fragments. PhairPlayService is started on app launch.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var navItemHome: TextView
    private lateinit var navItemSettings: TextView
    private lateinit var contentContainer: FrameLayout
    private lateinit var streamingContainer: FrameLayout
    private lateinit var streamingScreen: StreamingScreen
    private lateinit var photoScreen: PhotoScreen
    private lateinit var nowPlayingScreen: NowPlayingScreen
    private var service: PhairPlayService? = null
    private var isBound = false
    private var overlayJob: Job? = null
    private var currentAirPlayState = ProtocolState.DISABLED
    /**
     * Apple Casting (AirPlay screen mirroring) state. Same receiver as [currentAirPlayState] —
     * this one is only CONNECTED while a mirror *video* stream is really on screen, which is what
     * decides whether to put the full-screen Surface up.
     */
    private var currentAppleCastingState = ProtocolState.DISABLED
    private var currentPhotoFrame: PhotoFrame? = null
    private var currentNowPlaying: NowPlayingInfo? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? PhairPlayService.LocalBinder)?.getService()
            isBound = true
            Timber.d("MainActivity: bound to PhairPlayService")
            service?.setVideoSurfaceProvider { getVideoSurface() }
            observeOverlayState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            overlayJob?.cancel()
            service = null
            Timber.d("MainActivity: unbound from PhairPlayService")
        }
    }
    private var selectedNavIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Timber.d("MainActivity created")
        bindViews()
        setupOverlayScreens()
        setupNavigation()
        if (savedInstanceState == null) {
            navigateTo(HomeFragment(), navItemHome)
        }
        ServiceController.start(this)
        lifecycleScope.launch { requestRuntimePermissions() }
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, PhairPlayService::class.java)
        isBound = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        overlayJob?.cancel()
        overlayJob = null
        super.onStop()
        service?.setVideoSurfaceProvider { null }
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        service = null
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            Timber.d("MainActivity finishing — stopping service so mirroring doesn't linger")
            ServiceController.stop(this)
        } else {
            Timber.d("MainActivity destroyed (recreation) — leaving service running")
        }
    }

    private fun bindViews() {
        navItemHome       = findViewById(R.id.nav_item_home)
        navItemSettings   = findViewById(R.id.nav_item_settings)
        contentContainer  = findViewById(R.id.content_container)
        streamingContainer = findViewById(R.id.streaming_container)
    }

    /**
     * Creates the StreamingScreen (SurfaceView for video) and adds it to the
     * streaming_container. Created eagerly so the Surface is ready before streaming starts.
     */
    private fun setupOverlayScreens() {
        streamingScreen = StreamingScreen(this)
        photoScreen = PhotoScreen(this)
        nowPlayingScreen = NowPlayingScreen(this)
        streamingContainer.addView(streamingScreen)
        streamingContainer.addView(photoScreen)
        streamingContainer.addView(nowPlayingScreen)
        photoScreen.visibility = View.GONE
        nowPlayingScreen.visibility = View.GONE
    }

    /**
     * Sets up click listeners for the navigation panel items.
     * Also updates the visual selected state (text color) of the active item.
     */
    private fun setupNavigation() {
        navItemHome.setOnClickListener {
            if (selectedNavIndex != 0) {
                navigateTo(HomeFragment(), navItemHome)
            }
        }
        navItemSettings.setOnClickListener {
            if (selectedNavIndex != 1) {
                navigateTo(SettingsFragment(), navItemSettings)
            }
        }
        selectedNavIndex = if (supportFragmentManager.findFragmentById(R.id.content_container) is SettingsFragment) 1 else 0
        setNavSelected(navItemHome, selectedNavIndex == 0)
        setNavSelected(navItemSettings, selectedNavIndex == 1)
        onBackPressedDispatcher.addCallback(this) {
            when {
                streamingContainer.visibility == View.VISIBLE -> {
                    ServiceController.stop(this@MainActivity)
                    hideStreamingScreen()
                    navItemHome.requestFocus()
                }
                selectedNavIndex == 1 -> {
                    navigateTo(HomeFragment(), navItemHome)
                    navItemHome.requestFocus()
                }
                !navItemHome.hasFocus() -> navItemHome.requestFocus()
                else -> {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        }
    }

    /**
     * Replaces the content_container fragment with [fragment] and updates
     * the nav panel selection highlight.
     *
     * @param fragment  The Fragment to show in the content area.
     * @param navItem   The nav panel TextView that was clicked (for highlight update).
     */
    private fun navigateTo(fragment: Fragment, navItem: TextView) {
        setNavSelected(navItemHome, navItem == navItemHome)
        setNavSelected(navItemSettings, navItem == navItemSettings)
        selectedNavIndex = if (navItem == navItemHome) 0 else 1
        supportFragmentManager.beginTransaction()
            .replace(R.id.content_container, fragment)
            .commit()
    }

    /**
     * Updates the nav panel item's visual state.
     *
     * @param item     The nav item TextView.
     * @param selected True if this item is currently active.
     */
    private fun setNavSelected(item: TextView, selected: Boolean) {
        item.isSelected = selected
        item.setTextColor(
            getColor(if (selected) R.color.text_primary else R.color.nav_item_normal)
        )
    }

    /**
     * Shows the full-screen streaming overlay (called by PhairPlayService
     * via a state update or broadcast when a stream becomes active).
     *
     * Hides the nav panel and content area to give the stream the full screen.
     */
    fun showStreamingScreen() {
        photoScreen.visibility = View.GONE
        nowPlayingScreen.visibility = View.GONE
        nowPlayingScreen.clear()
        streamingScreen.visibility = View.VISIBLE
        streamingContainer.keepScreenOn = true
        streamingContainer.visibility = View.VISIBLE
        streamingContainer.bringToFront()
    }

    fun showPhotoScreen(photoFrame: PhotoFrame) {
        if (photoScreen.showPhoto(photoFrame.bytes)) {
            streamingScreen.visibility = View.GONE
            nowPlayingScreen.visibility = View.GONE
                photoScreen.visibility = View.VISIBLE
            streamingContainer.keepScreenOn = true
            streamingContainer.visibility = View.VISIBLE
            streamingContainer.bringToFront()
        }
    }

    /** Shows the audio-only now-playing card (AirPlay audio with no video). */
    fun showNowPlayingScreen(info: NowPlayingInfo) {
        nowPlayingScreen.update(info)
        streamingScreen.visibility = View.GONE
        photoScreen.visibility = View.GONE
        nowPlayingScreen.visibility = View.VISIBLE
        streamingContainer.keepScreenOn = true
        streamingContainer.visibility = View.VISIBLE
        streamingContainer.bringToFront()
    }

    /**
     * Hides the streaming overlay and returns to the normal app UI.
     * Called when a stream ends.
     */
    fun hideStreamingScreen() {
        photoScreen.clearPhoto()
        photoScreen.visibility = View.GONE
        nowPlayingScreen.clear()
        nowPlayingScreen.visibility = View.GONE
        streamingScreen.visibility = View.VISIBLE
        streamingContainer.keepScreenOn = false
        streamingContainer.visibility = View.GONE
    }

    /** Returns the SurfaceView Surface for the VideoDecoder. */
    fun getVideoSurface() = streamingScreen.getSurface()

    /**
     * Routes TV-remote media keys to the AirPlay sender (DACP reverse control) while audio-only or a
     * stream is showing — so the remote can play/pause/skip what the Mac/iPhone is streaming. Returns
     * false for other keys so normal navigation is unaffected.
     */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        val overlayActive = currentNowPlaying != null ||
            currentAirPlayState == ProtocolState.CONNECTED ||
            currentAppleCastingState == ProtocolState.CONNECTED
        if (overlayActive) {
            val command = when (keyCode) {
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY,
                android.view.KeyEvent.KEYCODE_MEDIA_PAUSE,
                android.view.KeyEvent.KEYCODE_DPAD_CENTER -> com.phairplay.airplay.DacpClient.CMD_PLAY_PAUSE
                android.view.KeyEvent.KEYCODE_MEDIA_NEXT,
                android.view.KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> com.phairplay.airplay.DacpClient.CMD_NEXT
                android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                android.view.KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD -> com.phairplay.airplay.DacpClient.CMD_PREV
                android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> com.phairplay.airplay.DacpClient.CMD_FF
                android.view.KeyEvent.KEYCODE_MEDIA_REWIND -> com.phairplay.airplay.DacpClient.CMD_REW
                else -> null
            }
            if (command != null) {
                service?.sendAirPlayRemoteCommand(command)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * Requests POST_NOTIFICATIONS on Android 13+, for the foreground-service notification.
     *
     * That is the only runtime permission Hearth still needs. The Wi-Fi Direct, location and
     * "nearby devices" grants went away with the Miracast receiver, and AirPlay runs on ordinary
     * TCP/UDP sockets that need none.
     */
    private suspend fun requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ActivityCompat.requestPermissions(
            this,
            arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
            PERMISSION_REQUEST_NOTIFICATIONS
        )
    }

    companion object {
        private const val PERMISSION_REQUEST_NOTIFICATIONS = 1001
    }

    /**
     * Observes [PhairPlayService.airPlayState], [PhairPlayService.appleCastingState],
     * [PhairPlayService.photoFrame] and shows the appropriate full-screen overlay.
     *
     * Called once after the service is bound. The coroutine is automatically cancelled
     * by [lifecycleScope] when the Activity stops.
     */
    private fun observeOverlayState() {
        overlayJob?.cancel()
        val svc = service ?: return
        overlayJob = lifecycleScope.launch {
            launch {
                svc.airPlayState.collectLatest { state ->
                    currentAirPlayState = state
                    updateOverlay()
                }
            }
            launch {
                svc.appleCastingState.collectLatest { state ->
                    currentAppleCastingState = state
                    updateOverlay()
                }
            }
            launch {
                svc.photoFrame.collectLatest { frame ->
                    currentPhotoFrame = frame
                    updateOverlay()
                }
            }
            launch {
                svc.nowPlaying.collectLatest { info ->
                    currentNowPlaying = info
                    updateOverlay()
                }
            }
        }
    }

    private fun updateOverlay() {
        val photoFrame = currentPhotoFrame
        val nowPlaying = currentNowPlaying
        when {
            nowPlaying != null -> showNowPlayingScreen(nowPlaying)
            photoFrame != null -> showPhotoScreen(photoFrame)
            currentAppleCastingState == ProtocolState.CONNECTED ||
                currentAirPlayState == ProtocolState.CONNECTED -> showStreamingScreen()
            else -> hideStreamingScreen()
        }
    }
}
