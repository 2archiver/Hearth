package com.phairplay

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * MainActivityTest — Instrumented tests for MainActivity.
 *
 * WHY: Unit tests can mock Android APIs, but some things must be tested on
 * a real Android device (or emulator) to verify they actually work:
 * - Does the Activity start without crashing?
 * - Is the WaitingScreen visible on startup?
 * - Does the UI respond correctly to orientation changes?
 *
 * HOW: These are instrumented tests — they run on a real Android device or
 * emulator (not on the development machine). Run with:
 *   ./gradlew connectedAndroidTest
 *
 * The [ActivityScenarioRule] automatically starts and stops the Activity
 * for each test.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    // ActivityScenarioRule starts the Activity before each test and stops it after
    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    /**
     * Test: MainActivity starts without crashing.
     *
     * WHY: This is Acceptance Criterion AC-1.3 / AC-1.4.
     * The most basic requirement: the app must start.
     * If this test fails, something is fundamentally broken.
     */
    @Test
    fun mainActivity_startsWithoutCrash() {
        // If we reach this line, the Activity started without throwing an exception.
        // ActivityScenarioRule would have failed the test during setup if a crash occurred.
    }

    /**
     * Test: The app's navigation (and therefore a usable screen) is visible at startup.
     *
     * WHY: Acceptance Criterion AC-1.5 — the user must see the app rather than a blank
     * screen. The startup screen itself is HomeFragment; if the nav panel renders then the
     * fragment container has been laid out too.
     */
    @Test
    fun mainActivity_showsUiOnStart() {
        onView(withId(R.id.nav_app_name))
            .check(matches(isDisplayed()))
    }

    /**
     * Test: The StreamingScreen container is hidden when the app starts.
     *
     * WHY: The StreamingScreen should only be visible when streaming is active.
     * If it's visible at startup, it would show a black surface over the UI.
     */
    @Test
    fun mainActivity_streamingContainerHiddenOnStart() {
        // The streaming container exists in the layout but has visibility GONE initially
        onView(withId(R.id.streaming_container))
            .check(matches(org.hamcrest.Matchers.not(isDisplayed())))
    }
}
