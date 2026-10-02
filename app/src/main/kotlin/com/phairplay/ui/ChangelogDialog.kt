package com.phairplay.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.ArrayRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.phairplay.R

/**
 * ChangelogDialog — the in-app "What's new" screen (Settings → About → What's new).
 *
 * WHY: the changelog only existed as CHANGELOG.md on GitHub, which is the last place someone
 * sitting in front of a TV is going to look. This shows the release the user is actually
 * running, on the device itself.
 *
 * HOW: the copy lives in `res/values/changelog.xml` — string resources, so it is translatable
 * like the rest of the app — and this class only lays it out. Newest release first, then a
 * one-line-per-release "Earlier versions" list, then a pointer to the full history in
 * CHANGELOG.md.
 *
 * WHY A DIALOG: it matches the existing rename dialog (see [SettingsFragment]), needs no
 * navigation destination, and a `ScrollView` inside an `AlertDialog` is focusable with a TV
 * remote — the D-pad scrolls the body and reaches the Close button.
 */
object ChangelogDialog {

    /** Horizontal/vertical breathing room inside the dialog, in dp. */
    private const val PADDING_DP = 24

    /** A titled group of entries, e.g. "Fixed" → `changelog_1_3_fixed`. */
    private data class Section(@StringRes val title: Int, @ArrayRes val entries: Int)

    /** The release described by `changelog.xml`, newest first. */
    private val SECTIONS = listOf(
        Section(R.string.changelog_section_fixed, R.array.changelog_1_3_fixed),
        Section(R.string.changelog_section_improved, R.array.changelog_1_3_improved),
        Section(R.string.changelog_section_new, R.array.changelog_1_3_new),
    )

    /**
     * Shows the changelog dialog.
     *
     * @param context any context; the dialog is owned by the caller's Activity
     */
    fun show(context: Context) {
        val text = TextView(context).apply {
            setText(buildText(context), TextView.BufferType.SPANNABLE)
            textSize = 16f
            setLineSpacing(0f, 1.25f)
            val padding = (PADDING_DP * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, padding / 2)
        }

        val scroll = ScrollView(context).apply {
            addView(text)
            isFillViewport = false
        }

        AlertDialog.Builder(context)
            .setTitle(R.string.changelog_title)
            .setView(scroll)
            .setPositiveButton(R.string.changelog_close, null)
            .show()
    }

    /**
     * Builds the dialog body: a version header, the sections of the current release, the
     * earlier releases, and where to find the full history.
     */
    private fun buildText(context: Context): CharSequence {
        val accent = ContextCompat.getColor(context, R.color.accent_blue)
        val out = SpannableStringBuilder()

        fun heading(title: String) {
            val start = out.length
            out.append(title.uppercase()).append('\n')
            out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(
                ForegroundColorSpan(accent),
                start,
                out.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }

        fun bullets(entries: Array<String>) {
            for (entry in entries) out.append(BULLET).append(entry).append('\n')
        }

        // Header — "Version 1.3.0 · 2 October 2026"
        val headerStart = out.length
        out.append(
            context.getString(
                R.string.changelog_version_header,
                context.getString(R.string.changelog_release_version),
                context.getString(R.string.changelog_release_date),
            )
        ).append("\n\n")
        out.setSpan(
            StyleSpan(Typeface.BOLD),
            headerStart,
            out.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )

        for (section in SECTIONS) {
            heading(context.getString(section.title))
            bullets(context.resources.getStringArray(section.entries))
            out.append('\n')
        }

        heading(context.getString(R.string.changelog_earlier_title))
        bullets(context.resources.getStringArray(R.array.changelog_earlier))

        out.append('\n')
        val footerStart = out.length
        out.append(context.getString(R.string.changelog_full_history))
        out.setSpan(
            ForegroundColorSpan(ContextCompat.getColor(context, R.color.text_secondary)),
            footerStart,
            out.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )

        return out
    }

    /** Bullet used for every entry line. */
    private const val BULLET = "•  "
}
