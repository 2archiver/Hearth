// App module build configuration for PhairPlay.
//
// PhairPlay ships for Google TV only (Android TV OS 10+; developed and tested against
// Google TV 4K running Android TV OS 14).
// The single "googletv" product flavor is kept so Gradle task names (assembleGoogletvRelease, ...),
// the applicationId, and the Cast SDK source set stay stable.
//
// Shared code lives in src/main/. Google TV specific code lives in src/googletv/.

import java.security.KeyStore

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

fun String.escapedForBuildConfig(): String =
    replace("\\", "\\\\").replace("\"", "\\\"")

/**
 * versionCode fallback: minutes since 2024-01-01T00:00:00Z (epoch seconds 1704067200 / 60).
 *
 * WHY: Android only installs an APK over an existing one when its versionCode is higher, so a
 * code that grows with every build means "download the newest APK and install it" just works —
 * no uninstall first (provided the signing key matches, see docs/RELEASING.md). A clock-derived
 * code also stays monotonic across BOTH release paths (rolling `latest` builds from main and
 * permanent `v*` tag releases), which a version-derived code cannot.
 *
 * Range: ~1.45M today, inside Int32 until roughly the year 6053. CI passes an explicit
 * -Pphairplay.versionCode computed the same way, so published builds report a stable code.
 */
fun monotonicVersionCode(): Int = ((System.currentTimeMillis() / 60_000L) - 28_401_120L).toInt()

val castAppId: String =
    (providers.gradleProperty("phairplay.castAppId").orNull
        ?: providers.environmentVariable("PHAIRPLAY_CAST_APP_ID").orNull
        ?: "").trim()

/**
 * GitHub repo the in-app update checker polls, as "owner/name".
 *
 * Override per fork with `-Pphairplay.updateRepo=you/your-fork` (or the
 * PHAIRPLAY_UPDATE_REPO environment variable) so a fork's APK checks its own releases
 * instead of upstream's.
 */
val updateRepo: String =
    (providers.gradleProperty("phairplay.updateRepo").orNull
        ?: providers.environmentVariable("PHAIRPLAY_UPDATE_REPO").orNull
        ?: "2archiver/phairplay-archiver-fork-").trim()

/**
 * Describes the key a build signs with.
 *
 * @see signingKeySpec for how one of these is chosen.
 */
data class SigningKeySpec(
    val store: java.io.File,
    val storeType: String,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String
)

/**
 * True when [spec] can actually be opened and its key read with the given passwords.
 *
 * WHY BOTHER: if the toolchain ever stops reading this keystore format, we would rather
 * fall back to the debug key with a loud warning than fail the build — and the warning is
 * what tells a maintainer that published APKs will no longer update each other in place.
 */
fun SigningKeySpec.canLoad(): Boolean = try {
    val store = KeyStore.getInstance(storeType)
    this.store.inputStream().use { store.load(it, storePassword.toCharArray()) }
    store.containsAlias(keyAlias) && store.getKey(keyAlias, keyPassword.toCharArray()) != null
} catch (e: Exception) {
    false
}

/**
 * The signing key this build uses — the fix for "App not installed as package conflicts
 * with an existing package".
 *
 * WHY: Android refuses to install an APK over an installed one when the two are signed
 * with different keys, and reports exactly that error. PhairPlay is sideloaded (no Play
 * Store), so until now every build that did not carry the maintainer's private keystore
 * secrets fell back to a throw-away debug key that differed per run — meaning every
 * update had to be preceded by an uninstall.
 *
 * Order of preference:
 *   1. Your own key — KEYSTORE_PATH (+ KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD)
 *      environment variables, or the matching `phairplay.keystore*` Gradle properties.
 *      Use this for anything you publish to other people.
 *   2. app/signing/phairplay.p12 — the public "community build" key committed to this
 *      repository. It is published on purpose (see docs/RELEASING.md) so that CI runs,
 *      tag releases and local clones all produce APKs that update each other in place.
 *
 * Both `debug` and `release` use whichever key is chosen, so a debug APK from CI and a
 * release APK from the release workflow can also replace each other.
 */
val signingKeySpec: SigningKeySpec? = run {
    val customPath = System.getenv("KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
        ?: providers.gradleProperty("phairplay.keystorePath").orNull?.takeIf { it.isNotBlank() }
    if (customPath != null) {
        SigningKeySpec(
            store = file(customPath),
            // PKCS12 is what openssl/keytool write today; a .jks needs the legacy type.
            storeType = System.getenv("KEYSTORE_TYPE")?.takeIf { it.isNotBlank() }
                ?: providers.gradleProperty("phairplay.keystoreType").orNull?.takeIf { it.isNotBlank() }
                ?: if (customPath.endsWith(".p12", ignoreCase = true) ||
                    customPath.endsWith(".pfx", ignoreCase = true)
                ) "pkcs12" else "jks",
            storePassword = System.getenv("KEYSTORE_PASSWORD")
                ?: providers.gradleProperty("phairplay.keystorePassword").orNull ?: "",
            keyAlias = System.getenv("KEY_ALIAS")
                ?: providers.gradleProperty("phairplay.keyAlias").orNull ?: "",
            keyPassword = System.getenv("KEY_PASSWORD")
                ?: providers.gradleProperty("phairplay.keyPassword").orNull ?: ""
        )
    } else {
        val communityKey = file("signing/phairplay.p12")
        val community = if (communityKey.isFile) {
            SigningKeySpec(
                store = communityKey,
                storeType = "pkcs12",
                storePassword = "phairplay",
                keyAlias = "phairplay",
                keyPassword = "phairplay"
            )
        } else {
            null
        }
        if (community != null && !community.canLoad()) {
            // Do not fail the build over this — but make it impossible to miss.
            logger.warn(
                "PhairPlay: app/signing/phairplay.p12 could not be read with this JDK, so " +
                    "this APK will NOT update an existing install. Regenerate it with " +
                    "tools/make-signing-key.sh, or point KEYSTORE_PATH at your own key."
            )
        }
        community
    }
}


android {
    namespace = "com.phairplay"
    compileSdk = 35
    ndkVersion = "28.2.13676358"

    defaultConfig {
        // applicationId is overridden per flavor below
        minSdk = 29           // Google TV / Android TV OS 10+
        targetSdk = 35
        // Version source of truth: phairplay.versionName in gradle.properties (bumped per
        // release train). CI overrides both per build — see .github/workflows/release.yml:
        //   merge to main → "<base>-main.<run>" in the rolling `latest` release
        //   v1.3.0 tag    → "1.3.0" in a permanent versioned release
        // The versionCode fallback increases with every build so a new APK updates the old one.
        versionCode = providers.gradleProperty("phairplay.versionCode").orNull?.toIntOrNull()
            ?: monotonicVersionCode()
        versionName = providers.gradleProperty("phairplay.versionName").getOrElse("1.3.0")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "CAST_APP_ID", "\"${castAppId.escapedForBuildConfig()}\"")
        buildConfigField("String", "UPDATE_REPO", "\"${updateRepo.escapedForBuildConfig()}\"")

        // Native code (libplayfair.so, libalac) — Google TV hardware is ARM only
        // (Chromecast with Google TV, Google TV Streamer, Sony/TCL/Hisense/Philips TVs),
        // so x86/x86_64 are dropped to keep the APK small. arm64-v8a is the main target;
        // armeabi-v7a stays because many Google TV devices (incl. Chromecast with Google TV)
        // run a 32-bit userspace.
        ndk {
            abiFilters += setOf("armeabi-v7a", "arm64-v8a")
        }
    }

    // Native build: RPiPlay's FairPlay (playfair) compiled via CMake → libplayfair.so.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Single flavor: Google TV. Flavor-specific code (Cast Connect receiver),
    // resources, and dependencies live in src/googletv/.
    flavorDimensions += "platform"
    productFlavors {
        create("googletv") {
            dimension = "platform"
            applicationId = "com.phairplay.googletv"
            minSdk = 29        // Google TV requires Android 10+
            versionNameSuffix = "-googletv"
        }
    }

    // Signing: one key for every build type, chosen by [signingKeySpec].
    //
    // Sharing the key between `debug` and `release` is deliberate: it means a debug APK
    // downloaded from a CI run and a release APK from the release workflow install over
    // each other, instead of Android rejecting the second one.
    signingConfigs {
        val spec = signingKeySpec
        if (spec != null) {
            // Re-bound after the null check so the properties below are non-null.
            val key = spec
            create("phairplay") {
                storeFile = key.store
                storeType = key.storeType
                storePassword = key.storePassword
                keyAlias = key.keyAlias
                keyPassword = key.keyPassword
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            // Same key as release so debug ↔ release are interchangeable on the TV.
            // Left unset when no key is configured, so AGP keeps its own debug key.
            val key = signingConfigs.findByName("phairplay")
            if (key != null) {
                signingConfig = key
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Fall back to the auto-generated debug key only if no key at all is available
            // (e.g. a fork that deleted app/signing/phairplay.p12). Android rejects an
            // unsigned APK, so never leave this build type unsigned.
            signingConfig = signingConfigs.findByName("phairplay")
                ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        // Enable strict coroutine checks in debug builds
        freeCompilerArgs += listOf(
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi"
        )
    }

    // Source sets: shared code in main, flavor-specific overrides in flavor directories
    sourceSets {
        getByName("main") {
            kotlin.srcDirs("src/main/kotlin")
            res.srcDirs("src/main/res")
        }
        getByName("googletv") {
            kotlin.srcDirs("src/googletv/kotlin")
            res.srcDirs("src/googletv/res")
        }
        getByName("test") {
            kotlin.srcDirs("src/test/kotlin")
        }
        getByName("androidTest") {
            kotlin.srcDirs("src/androidTest/kotlin")
        }
    }

    // Lint configuration: treat all warnings as errors in CI
    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = true
        // Keep lint focused on PhairPlay sources. The Google Cast SDK pulls a
        // large transitive graph that exceeds the small CI/dev VM during
        // dependency lint analysis, while app-source lint still catches local
        // manifest/resource/API regressions.
        checkDependencies = false
        disable += setOf(
            // Dependency freshness is tracked intentionally, but should not block
            // protocol/build CI when the pinned toolchain is known-good.
            "AndroidGradlePluginVersion",
            "GradleDependency",
            // Localizations are incomplete during the pre-release hardware-test phase.
            "MissingTranslation",
            // Cleanup/style issues that should not block debug APK CI.
            "ButtonStyle",
            "DataExtractionRules",
            "DiscouragedApi",
            "MonochromeLauncherIcon",
            // Launcher-icon shape is advisory; on Android TV the banner is the primary
            // artwork and the icon is rarely shown (sibling of MonochromeLauncherIcon above).
            "IconLauncherShape",
            "ObsoleteSdkInt",
            "Overdraw",
            "UnusedResources",
            // Advisory: the project deliberately supports a wide API range for old TVs;
            // targetSdk is bumped deliberately, not on every new platform release.
            "OldTargetApi",
            // PhairPlay ships for Google TV only, where every device is ARM (Chromecast with
            // Google TV, Google TV Streamer, Sony/TCL/Hisense/Philips TVs). The native
            // FairPlay/ALAC libraries are built for armeabi-v7a and arm64-v8a to keep the APK
            // small; x86/x86_64 would only serve ChromeOS, which this app does not target.
            "ChromeOsAbiSupport"
        )
    }

    packaging {
        jniLibs {
            keepDebugSymbols += "**/*.so"
        }
        resources {
            // BouncyCastle (and some other crypto libs) include OSGI manifest files
            // that conflict when multiple jars are merged. Exclude them — they are
            // not needed at runtime on Android (OSGI is a Java EE/OSGi framework).
            excludes += "META-INF/versions/9/OSGI-INF/**"
            excludes += "META-INF/NOTICE.md"
            excludes += "META-INF/LICENSE.md"
        }
    }

    buildFeatures {
        // BuildConfig is disabled by default in AGP 8.x — enable it explicitly
        // because PhairPlayApp.kt and SettingsFragment.kt use BuildConfig.VERSION_NAME etc.
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

// Say which key the build is using: a release that silently falls back to the debug key is
// exactly how the "package conflicts with an existing package" error used to ship.
val signingKeyForLog = signingKeySpec
logger.lifecycle(
    if (signingKeyForLog == null) {
        "PhairPlay signing: NO KEY FOUND — falling back to the Gradle debug key. " +
            "Add app/signing/phairplay.p12 or set KEYSTORE_PATH (see docs/RELEASING.md)."
    } else {
        "PhairPlay signing: ${signingKeyForLog.store.name} " +
            "(${signingKeyForLog.storeType}, alias '${signingKeyForLog.keyAlias}')"
    }
)

dependencies {
    // AndroidX UI (View-based, for maximum TV compatibility)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)

    // Leanback — TV focus management, on-screen keyboard, TV-specific widgets
    implementation(libs.androidx.leanback)

    // DataStore — async, type-safe replacement for SharedPreferences
    implementation(libs.androidx.datastore.preferences)

    // Async I/O — all network and media operations use coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Logging — tagged, level-filtered logs with pluggable backend
    implementation(libs.timber)

    // Cryptography — AES-128-CTR for audio decryption, future SRP-6a pairing
    implementation(libs.bouncycastle)

    // Binary property lists — AirPlay 2 handshake payloads (GET /info, SETUP)
    implementation(libs.ddplist)

    // Google TV Cast Connect receiver SDK (needs Google Play Services, present on Google TV).
    "googletvImplementation"(libs.play.services.cast.tv)

    // Unit Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    // Robolectric — real Android framework classes (Intent, Base64, …) in JVM unit tests
    testImplementation(libs.robolectric)

    // Instrumented Testing (on device)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
