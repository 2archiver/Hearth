package com.phairplay.update

import org.json.JSONArray
import org.json.JSONObject

/**
 * UpdateInfo — everything the in-app updater knows about a newer Hearth build.
 *
 * WHY: the updater has to answer two questions from a GitHub release without guessing:
 * "is this newer than what is installed?" (needs a numeric [versionCode], not a version
 * *string* — `1.10.0` sorts before `1.9.0` as text) and "which asset is the APK?" (the
 * release also carries `SHA256SUMS.txt` and `version.json`).
 *
 * HOW: `versionCode`, exact APK size and full SHA-256 are taken from validated release metadata.
 * New releases put them in the release body; older releases may provide the checksum and size in
 * `version.json` / `SHA256SUMS.txt`. Missing or conflicting integrity metadata means no update is
 * offered — see [ReleaseParser].
 */
data class UpdateInfo(
    /** Human-readable version, e.g. `1.4.0` or `1.4.0-main.123`. */
    val versionName: String,
    /** Monotonic integer version. Compared against the installed BuildConfig.VERSION_CODE. */
    val versionCode: Int,
    /** Release tag, e.g. `latest` or `v1.4.0`. */
    val tagName: String,
    /** Web page for the release, opened by "view release notes". */
    val htmlUrl: String,
    /** File name of the APK asset, e.g. `Hearth-1.6.1-googletv.apk`. */
    val apkName: String,
    /** Direct download URL for [apkName]. */
    val apkUrl: String,
    /** APK size in bytes as reported by GitHub (0 when unknown). */
    val apkSizeBytes: Long,
    /** Lowercase hex SHA-256 of a validated release APK; null only on display-only staged records. */
    val sha256: String?,
    /** Release notes / body, trimmed, null when empty. */
    val notes: String?
) {
    /** True when this build reports a strictly newer version than [installedVersionCode]. */
    fun isNewerThan(installedVersionCode: Int): Boolean = versionCode > installedVersionCode

    /** Compact one-line summary for the Settings row, e.g. `1.4.0-main.131 (20432100)`. */
    fun shortLabel(): String =
        if (versionCode > 0) "$versionName ($versionCode)" else versionName

    companion object {
        /**
         * Asset name the release workflow used when every release carried three files
         * (`version.json`, `SHA256SUMS.txt`, the APK). Releases are a single version-named APK
         * now, so this only exists to keep reading *older* releases correctly.
         */
        const val DESCRIPTOR_ASSET = "version.json"

        /**
         * The fixed APK name used before releases named their asset after the version. It is
         * still `PhairPlay-…`: that is what those older releases actually publish, and finding
         * them is the point. A release from now on is matched by the `.apk` suffix instead.
         */
        const val APK_ASSET = "PhairPlay-googletv.apk"

        /** Asset carrying the APK checksums, published by releases before the one-asset change. */
        const val CHECKSUM_ASSET = "SHA256SUMS.txt"

        /** A release's payload is recognised by this suffix — its name carries the version. */
        const val APK_SUFFIX = ".apk"
    }
}

/** One entry of a GitHub release's `assets` array. */
data class ReleaseAsset(
    val name: String,
    val url: String,
    val sizeBytes: Long
)

/** A GitHub release, as returned by `GET /repos/{owner}/{repo}/releases/latest`. */
data class Release(
    val tagName: String,
    val name: String,
    val htmlUrl: String,
    val body: String?,
    val assets: List<ReleaseAsset>
) {
    fun asset(name: String): ReleaseAsset? = assets.firstOrNull { it.name == name }

    /**
     * Select the actual Google TV APK returned by GitHub, never simply the first `.apk` asset.
     *
     * A release can contain APKs for more than one device/flavor. Matching the explicit
     * `-googletv.apk` suffix (including the legacy fixed `PhairPlay-googletv.apk`) keeps the TV
     * updater from selecting a mobile or otherwise incompatible package. Ambiguous duplicate
     * Google TV assets are rejected instead of depending on API array order.
     */
    fun apkAsset(flavor: String = "googletv"): ReleaseAsset? {
        if (!flavor.matches(Regex("[A-Za-z0-9_-]{1,32}"))) return null
        val suffix = "-$flavor.apk"
        val compatible = assets.filter { it.name.endsWith(suffix, ignoreCase = true) }
        return compatible.singleOrNull()
    }
}

/**
 * ReleaseParser — turns GitHub JSON into [Release]/[UpdateInfo] without a JSON library
 * dependency (Android ships `org.json`).
 *
 * Defensive by design: a malformed or unexpected payload yields null rather than an
 * exception, because a network response is untrusted input and a crash here would take
 * down the Settings screen.
 */
object ReleaseParser {

    /**
     * Parses the `releases/latest` response. Returns null when it is not usable.
     *
     * Note the block body: Kotlin forbids `return` inside a function declared with an
     * expression body (`= try { … }`), and bailing out on a missing `tag_name` is exactly
     * what this does.
     */
    fun parseRelease(json: String): Release? {
        return try {
            val root = JSONObject(json)
            val tag = root.optStringOrNull("tag_name") ?: return null
            Release(
                tagName = tag,
                name = root.optStringOrNull("name") ?: tag,
                htmlUrl = root.optStringOrNull("html_url") ?: "",
                body = root.optStringOrNull("body")?.trim()?.takeIf { it.isNotEmpty() },
                assets = parseAssets(root.optJSONArray("assets"))
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parses the `version.json` descriptor the release workflow publishes.
     *
     * Shape:
     * ```json
     * { "versionName": "1.4.0-main.131", "versionCode": 20432100, "apk": "PhairPlay-googletv.apk",
     *   "sha256": "…", "size": 12345678, "commit": "abc1234" }
     * ```
     */
    fun parseDescriptor(json: String): UpdateDescriptor? = try {
        val root = JSONObject(json)
        UpdateDescriptor(
            versionName = root.optStringOrNull("versionName") ?: root.optStringOrNull("version"),
            versionCode = root.optIntOrNull("versionCode"),
            apk = root.optStringOrNull("apk"),
            sha256 = root.optStringOrNull("sha256"),
            sizeBytes = root.optLongOrNull("size")
        )
    } catch (e: Exception) {
        null
    }

    /**
     * Scrapes a versionCode out of release notes that predate `version.json`.
     *
     * The release workflow writes `| Version | 1.4.0 · versionCode 20432100 |`, so this
     * looks for `versionCode <digits>` and ignores everything else. Returns null when the
     * notes do not mention a code; release parsing then refuses to offer an unverifiable update.
     */
    fun scrapeVersionCode(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val match = VERSION_CODE_REGEX.find(text) ?: return null
        return match.groupValues[1].toLongOrNull()
            ?.coerceIn(0L, Int.MAX_VALUE.toLong())
            ?.toInt()
    }

    /**
     * Scrapes the APK's SHA-256 out of a release body.
     *
     * A one-asset release publishes no `SHA256SUMS.txt`, so the digest the workflow computed is
     * written into the notes instead ("**SHA-256:** `…`"). Requires the full 64 hex characters, so
     * a truncated or prose mention of a hash can never be mistaken for the real one.
     */
    fun scrapeSha256(text: String?): String? {
        if (text.isNullOrBlank()) return null
        return SHA256_REGEX.find(text)?.groupValues?.get(1)?.lowercase()
    }

    /**
     * The version carried by the single asset's file name: `Hearth-1.6.1-main.44-googletv.apk`
     * -> `1.6.1-main.44`. More trustworthy than the release title because it is the name of the
     * file that actually gets installed.
     *
     * Both brands are accepted: every release published before the rename is `PhairPlay-…`, and
     * a TV running an older build still updates from whatever the `latest` release says.
     */
    fun versionNameFromAssetName(name: String?): String? {
        if (name.isNullOrBlank()) return null
        return ASSET_VERSION_REGEX.find(name)
            ?.groupValues?.get(1)
            ?.takeIf { it.isNotBlank() && !it.equals("googletv", ignoreCase = true) }
    }

    /** Parses `SHA256SUMS.txt` (lines of `<hex>  <filename>`) into a filename → hex map. */
    fun parseSha256Sums(text: String?): Map<String, String> {
        if (text.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { line ->
                // "hash  file" or "hash *file" (binary mode marker)
                val parts = line.split(Regex("\\s+"), limit = 2)
                if (parts.size == 2) {
                    val hash = parts[0].lowercase()
                    val file = parts[1].trim().trimStart('*')
                    if (hash.length == 64 && file.isNotEmpty()) out[file] = hash
                }
            }
        return out
    }

    /**
     * Builds the [UpdateInfo] for a release, filling in the version from the descriptor when
     * it is available and otherwise from the release notes / tag.
     *
     * @param release    the GitHub release to describe
     * @param descriptor parsed `version.json`, or null when the release has none
     * @param checksums  parsed `SHA256SUMS.txt`, or null when unavailable
     * @param fallbackVersionName used when neither descriptor nor tag give a version
     */
    fun buildUpdateInfo(
        release: Release,
        descriptor: UpdateDescriptor?,
        checksums: Map<String, String>? = null,
        fallbackVersionName: String = release.tagName,
        compatibleFlavor: String = "googletv",
    ): UpdateInfo? {
        val apk = release.apkAsset(compatibleFlavor) ?: return null
        val descriptorName = descriptor?.apk?.takeIf { it.isNotBlank() }
        if (descriptorName != null && descriptorName != apk.name) return null

        val notesVersionCode = scrapeVersionCode(release.body)
        val descriptorVersionCode = descriptor?.versionCode?.takeIf { it > 0 }
        if (descriptor?.versionCode != null && descriptorVersionCode == null) return null
        if (descriptorVersionCode != null && notesVersionCode != null &&
            descriptorVersionCode != notesVersionCode
        ) return null
        val versionCode = descriptorVersionCode ?: notesVersionCode ?: return null

        val assetVersionName = versionNameFromAssetName(apk.name)
        val descriptorVersionName = descriptor?.versionName?.takeIf { it.isNotBlank() }
        if (assetVersionName != null && descriptorVersionName != null &&
            normalizeFlavorSuffix(assetVersionName) != normalizeFlavorSuffix(descriptorVersionName)
        ) return null

        val publishedSize = apk.sizeBytes.takeIf { it > 0L }
        val descriptorSize = descriptor?.sizeBytes?.takeIf { it > 0L }
        if (publishedSize != null && descriptorSize != null && publishedSize != descriptorSize) return null
        val sizeBytes = descriptorSize ?: publishedSize ?: return null

        val checksumValues = listOfNotNull(
            descriptor?.sha256,
            checksums?.get(apk.name),
            scrapeSha256(release.body),
        ).map { it.trim().lowercase() }
        if (checksumValues.isEmpty() || checksumValues.any { !SHA256_FULL_REGEX.matches(it) }) return null
        if (checksumValues.distinct().size != 1) return null
        val sha256 = checksumValues.first()

        val versionName = descriptorVersionName
            ?: assetVersionName
            ?: release.name.takeIf { it.isNotBlank() }
            ?: fallbackVersionName

        return UpdateInfo(
            versionName = versionName,
            versionCode = versionCode,
            tagName = release.tagName,
            htmlUrl = release.htmlUrl,
            apkName = apk.name,
            apkUrl = apk.url,
            apkSizeBytes = sizeBytes,
            sha256 = sha256,
            notes = release.body
        )
    }

    private fun normalizeFlavorSuffix(value: String): String =
        value.removeSuffix("-googletv")

    // ─── Internal helpers ────────────────────────────────────────────────────

    private val VERSION_CODE_REGEX = Regex("""versionCode\s*[:=]?\s*(\d{4,10})""", RegexOption.IGNORE_CASE)

    /** `SHA-256: ` + up to eight separator characters + 64 hex digits. */
    private val SHA256_REGEX = Regex("""(?i)SHA-?256\W{0,8}([0-9a-f]{64})""")
    private val SHA256_FULL_REGEX = Regex("(?i)^[0-9a-f]{64}$")

    private val ASSET_VERSION_REGEX =
        Regex("""^(?:Hearth|PhairPlay)-(.+?)(?:-googletv)?\.apk$""", RegexOption.IGNORE_CASE)

    private fun parseAssets(array: JSONArray?): List<ReleaseAsset> {
        if (array == null) return emptyList()
        val out = ArrayList<ReleaseAsset>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val name = obj.optStringOrNull("name") ?: continue
            val url = obj.optStringOrNull("browser_download_url") ?: continue
            out += ReleaseAsset(
                name = name,
                url = url,
                sizeBytes = obj.optLongOrNull("size") ?: 0L
            )
        }
        return out
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key) else null

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE } else null

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (has(key) && !isNull(key)) optLong(key, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE } else null
}

/** The parts of `version.json` the updater needs; every field is optional. */
data class UpdateDescriptor(
    val versionName: String?,
    val versionCode: Int?,
    val apk: String?,
    val sha256: String?,
    val sizeBytes: Long?
)
