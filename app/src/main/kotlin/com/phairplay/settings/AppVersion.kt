package com.phairplay.settings

/**
 * AppVersion — turns the raw `BuildConfig.VERSION_NAME` into the version a person reads.
 *
 * WHY: CI bakes a lot of detail into the version name so a bug report can identify the exact
 * build:
 *
 *   release.yml (rolling, every merge to main) → `1.3.0-main.4`, plus the flavor suffix
 *   release.yml (v* tag)                       → `1.3.0`, plus the flavor suffix
 *   a local build                              → `1.3.0`, plus the flavor suffix
 *
 * so the About screen used to read `1.3.0-main.4-googletv`. That is the right string to keep
 * on the Version row (issue reports ask for it), but it is the wrong string for "what
 * version is this" — the changelog, the release page and the user all talk about **1.3.0**.
 *
 * HOW: [base] strips the rolling-build marker and the flavor suffix; [train] keeps only the
 * major.minor part. Both are pure functions, so the JVM test suite pins them.
 */
object AppVersion {

    /** Appended by the `googletv` product flavor in app/build.gradle.kts. */
    private const val FLAVOR_SUFFIX = "-googletv"

    /** Appended by the rolling release workflow: `1.3.0-main.<run number>`. */
    private val ROLLING_BUILD = Regex("""-main\.\d+$""")

    /** What the helpers return when handed a blank string. */
    const val UNKNOWN = "unknown"

    /**
     * The release version behind a build.
     *
     * `1.3.0-main.4-googletv` → `1.3.0`, `1.3.0-googletv` → `1.3.0`, `1.3.0-beta.1-googletv`
     * → `1.3.0-beta.1`.
     */
    fun base(versionName: String): String =
        versionName.trim()
            .removeSuffix(FLAVOR_SUFFIX)
            .replace(ROLLING_BUILD, "")
            .ifBlank { UNKNOWN }

    /**
     * The release train behind a build — what a changelog is filed under.
     *
     * `1.3.0-main.4-googletv` → `1.3`
     */
    fun train(versionName: String): String =
        base(versionName).substringBefore('.').ifBlank { UNKNOWN }
}
