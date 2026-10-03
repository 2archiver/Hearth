// App module build configuration for PhairPlay.
//
// PhairPlay ships for Google TV only (Android TV OS 10+; developed and tested against
// Google TV 4K running Android TV OS 14).
// The single "googletv" product flavor is kept so Gradle task names (assembleGoogletvRelease, ...),
// the applicationId, and the Cast SDK source set stay stable.
//
// Shared code lives in src/main/. Google TV specific code lives in src/googletv/.

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
        versionName = providers.gradleProperty("phairplay.versionName").getOrElse("1.4.0")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "CAST_APP_ID", "\"${castAppId.escapedForBuildConfig()}\"")

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

    // Release signing: credentials are injected via environment variables in CI.
    // Set KEYSTORE_PATH, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD to enable.
    // Local builds without these vars are signed with the debug key (fine for dev/test).
    val keystorePath = System.getenv("KEYSTORE_PATH")
    if (keystorePath != null) {
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Use the release key when provided; otherwise fall back to the auto-generated
            // debug key so a locally built release APK is still installable on a TV
            // (an unsigned APK is rejected by Android). Updates only work across builds
            // signed with the same key, so use a real keystore for published releases.
            signingConfig = signingConfigs.findByName("release")
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
            "NewerVersionAvailable",
            // Localizations are incomplete during the pre-release hardware-test phase.
            "MissingTranslation",
            // Cleanup/style issues that should not block debug APK CI.
            "ButtonStyle",
            "DataExtractionRules",
            "DiscouragedApi",
            "MonochromeLauncherIcon",
            "AdaptableIcon",
            // Launcher-icon shape is advisory; on Android TV the banner is the primary
            // artwork and the icon is rarely shown (sibling of MonochromeLauncherIcon above).
            "IconLauncherShape",
            "ObsoleteSdkInt",
            "Overdraw",
            "UnusedResources",
            "UnusedIds",
            "DuplicateDivider",
            "RtlSymmetry",
            "RtlHardcoded",
            "StopShip",
            "TypographyFractions",
            "TypographyQuotes",
            "SetTextI18n",
            // Advisory: the project deliberately supports a wide API range for old TVs;
            // targetSdk is bumped deliberately, not on every new platform release.
            "OldTargetApi",
            // PhairPlay ships for Google TV only, where every device is ARM (Chromecast with
            // Google TV, Google TV Streamer, Sony/TCL/Hisense/Philips TVs). The native
            // FairPlay/ALAC libraries are built for armeabi-v7a and arm64-v8a to keep the APK
            // small; x86/x86_64 would only serve ChromeOS, which this app does not target.
            "ChromeOsAbiSupport",
            // TV-only app; gestures / touch is not required
            "GestureNavBackArrowMigration",
            "BackButton",
            "UnusedAttribute",
            "InvalidWearFeatureConfig",
            "ImpliedTouchscreenHardware",
            "MissingClass",
            "InvalidPackage",
            "VisibleForTests"
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
