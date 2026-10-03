package com.phairplay.cast.bridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import com.phairplay.util.Logger
import java.net.URLDecoder

/**
 * Hands a DIAL launch request to an app that is **already installed on the TV**.
 *
 * WHY THIS EXISTS
 * ---------------
 * PhairPlay's built-in receiver plays a media URL. That is all a generic receiver can honestly
 * do: apps like Netflix, YouTube, Disney+ and Spotify do not hand a URL to the receiver, they
 * launch an app *registered to them* and then speak a private command channel only that app
 * understands. No open-source receiver can substitute for that.
 *
 * A real smart TV does not try to. Its DIAL server **launches the native app that is installed
 * on the TV**, and the phone then drives that app (over the service's own cloud, in Netflix's
 * case; over DIAL + the Lounge API in YouTube's). PhairPlay can do exactly the same thing,
 * because a Google TV is an Android TV: `netflix` → `com.netflix.ninja` is one intent away.
 *
 * So:
 *  - recognised DIAL name **and** the app is installed → launch it, report `running`, and stay
 *    out of the way. The sender is talking to the real app after that.
 *  - anything else (`CC1AD845`, VLC, Plex, Chrome, Infuse…) → the built-in receiver plays the
 *    URL, exactly as before.
 *
 * The table is deliberately conservative: a wrong package name is worse than no entry, because
 * it would launch something unrelated. Every candidate is checked with PackageManager before
 * it is ever used.
 */
internal class DialAppRouter(private val context: Context) : DialAppLauncher {

    override fun isInstalled(appName: String): Boolean = resolvePackage(appName) != null

    override fun launch(appName: String, body: String): Boolean {
        val packageName = resolvePackage(appName) ?: return false
        val intent = buildIntent(appName, packageName, body) ?: return false
        return try {
            // A DIAL server answers an HTTP request from a phone; there is no task of ours to
            // attach to, and Android 10+ blocks activity starts from a pure background process.
            // PhairPlay runs a foreground service and calls bringToFront() before this point;
            // if the platform still refuses, we log it and fall back to the built-in receiver
            // rather than leaving the sender hanging with a 201 and nothing on screen.
            context.startActivity(intent)
            Logger.i("Cast: DIAL handed '$appName' to $packageName")
            true
        } catch (e: SecurityException) {
            Logger.w("Cast: the TV blocked launching $packageName for '$appName' — " +
                "falling back to the built-in receiver", e)
            false
        } catch (e: Exception) {
            Logger.w("Cast: could not launch $packageName for '$appName': ${e.message}")
            false
        }
    }

    /** The first candidate package for [appName] that is actually installed on this TV. */
    private fun resolvePackage(appName: String): String? {
        val candidates = DialApps.packagesFor(appName)
        if (candidates.isEmpty()) return null
        val manager = context.packageManager
        for (candidate in candidates) {
            val present = try {
                @Suppress("DEPRECATION")
                manager.getPackageInfo(candidate.packageName, 0) != null
            } catch (e: PackageManager.NameNotFoundException) {
                false
            } catch (e: Exception) {
                false
            }
            if (present) return candidate.packageName
        }
        return null
    }

    /**
     * Prefers a deep link into the app when the DIAL body carried a content id (YouTube's
     * `v=<id>`, Netflix's `dial=<id>`), because opening the app's front page and expecting the
     * user to search is not "it works". Falls back to the leanback launch intent.
     */
    private fun buildIntent(appName: String, packageName: String, body: String): Intent? {
        val manager = context.packageManager
        val contentId = DialApps.contentIdFrom(body)
        val link = if (contentId.isBlank()) null else DialApps.deepLinkFor(appName, contentId)
        if (link != null) {
            val view = Intent(Intent.ACTION_VIEW, Uri.parse(link))
                .setPackage(packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (resolves(manager, view)) return view
            Logger.d("Cast: $link did not resolve in $packageName — opening the app instead")
        }
        val leanback = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            .setPackage(packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        if (resolves(manager, leanback)) return leanback
        return try {
            manager.getLaunchIntentForPackage(packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        } catch (e: Exception) {
            null
        }
    }

    private fun resolves(manager: PackageManager, intent: Intent): Boolean = try {
        manager.resolveActivity(intent, 0) != null
    } catch (e: Exception) {
        false
    }
}

/**
 * What [DialHttpServer] needs from the platform, so the HTTP layer stays pure and unit
 * testable on the plain JVM (no PackageManager, no Context).
 */
internal interface DialAppLauncher {
    /** True when a recognised app for this DIAL name is installed on the TV. */
    fun isInstalled(appName: String): Boolean

    /** Launches it. Returns false when PhairPlay should keep handling the request itself. */
    fun launch(appName: String, body: String): Boolean
}

/**
 * DialApps — the pure half: DIAL name → installed-app candidates, and body parsing.
 *
 * No Android imports on purpose, so this table is unit tested on the JVM.
 */
internal object DialApps {

    /** One installed-app candidate for a DIAL application name. */
    data class Candidate(
        val packageName: String,
        /** Builds a deep link into the app from a content id, or null to just open the app. */
        val deepLink: ((contentId: String) -> String?)? = null
    )

    /**
     * DIAL application names (lower-cased) → Android packages that can serve them.
     *
     * Multiple packages are listed where the app has shipped under more than one over the
     * years; the first one actually installed wins.
     */
    private val APPS: Map<String, List<Candidate>> = mapOf(
        "netflix" to listOf(
            Candidate("com.netflix.ninja") { id -> "https://www.netflix.com/title/$id" }
        ),
        "youtube" to listOf(
            Candidate("com.google.android.apps.youtube.tv") { id -> "https://www.youtube.com/watch?v=$id" },
            Candidate("com.google.android.youtube.tv") { id -> "https://www.youtube.com/watch?v=$id" }
        ),
        "youtubetv" to listOf(
            Candidate("com.google.android.apps.youtube.livetv")
        ),
        "youtubekids" to listOf(
            Candidate("com.google.android.apps.youtube.kids")
        ),
        "spotify" to listOf(
            Candidate("com.spotify.tv.android"),
            Candidate("com.spotify.music")
        ),
        "disneyplus" to listOf(
            Candidate("com.disney.disneyplus")
        ),
        "primevideo" to listOf(
            Candidate("com.amazon.avod.thirdpartyclient")
        ),
        "hbomax" to listOf(
            Candidate("com.wbd.hbo.max"),
            Candidate("com.hbo.hbonow")
        ),
        "hulu" to listOf(
            Candidate("com.hulu.plus")
        ),
        "peacock" to listOf(
            Candidate("com.peacocktv.peacocktv")
        ),
        "paramountplus" to listOf(
            Candidate("com.cbs.ott")
        ),
        "plex" to listOf(
            Candidate("com.plexapp.android")
        ),
        "twitch" to listOf(
            Candidate("tv.twitch.android.app")
        ),
        "crunchyroll" to listOf(
            Candidate("com.crunchyroll.crunchyroid")
        ),
        "vlc" to listOf(
            Candidate("org.videolan.vlc")
        ),
        "kodi" to listOf(
            Candidate("org.xbmc.kodi")
        ),
        "ted" to listOf(
            Candidate("com.ted.android")
        ),
        "vimeo" to listOf(
            Candidate("com.vimeo.android.videoapp")
        ),
        "tubi" to listOf(
            Candidate("com.tubitv")
        ),
        "plutotv" to listOf(
            Candidate("tv.pluto.android")
        ),
        "dazn" to listOf(
            Candidate("com.dazn.dazn")
        ),
        "soundcloud" to listOf(
            Candidate("com.soundcloud.android")
        ),
        "deezer" to listOf(
            Candidate("deezer.android.app")
        ),
        "tunein" to listOf(
            Candidate("tunein.player")
        ),
        "pandora" to listOf(
            Candidate("com.pandora.android")
        ),
        "bbciplayer" to listOf(
            Candidate("uk.co.bbc.android.iplayer"),
            Candidate("uk.co.bbc.iplayer")
        )
    )

    /** Body keys that carry the content id, in the order they are tried. */
    private val CONTENT_KEYS = listOf("v", "videoId", "id", "contentId", "dial", "url")

    fun normalize(appName: String): String = appName.trim().lowercase()

    fun packagesFor(appName: String): List<Candidate> = APPS[normalize(appName)] ?: emptyList()

    /** Every entry in the table — exposed so a test can assert the whole table stays sane. */
    fun allEntries(): Map<String, List<Candidate>> = APPS

    fun isKnown(appName: String): Boolean = APPS.containsKey(normalize(appName))

    /** Builds the deep link for [appName], or null when the app has none. */
    fun deepLinkFor(appName: String, contentId: String): String? {
        if (contentId.isBlank()) return null
        val candidate = packagesFor(appName).firstOrNull { it.deepLink != null } ?: return null
        return try {
            candidate.deepLink!!.invoke(contentId)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Pulls the content id out of a DIAL launch body.
     *
     * YouTube sends a bare `v=<videoId>`; Netflix sends `dial=<opaque id>`; some senders post
     * nothing at all (a plain "open the app" launch). Anything unrecognised is returned as-is so
     * the app still opens.
     */
    fun contentIdFrom(body: String): String {
        val raw = body.trim()
        if (raw.isEmpty()) return ""
        if (!raw.contains("=")) return raw

        val pairs = raw.split("&")
        for (key in CONTENT_KEYS) {
            val value = pairs
                .firstOrNull { it.substringBefore("=").trim().equals(key, ignoreCase = true) }
                ?.substringAfter("=", "")
                ?.trim()
                .orEmpty()
            if (value.isNotEmpty()) return urlDecode(value)
        }
        // A single "key=value" with an unrecognised key is still better than nothing.
        if (pairs.size == 1) {
            val value = pairs[0].substringAfter("=", "").trim()
            if (value.isNotEmpty()) return urlDecode(value)
        }
        return ""
    }

    private fun urlDecode(value: String): String = try {
        URLDecoder.decode(value, Charsets.UTF_8.name())
    } catch (e: Exception) {
        value
    }
}
