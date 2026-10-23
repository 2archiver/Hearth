package com.phairplay.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.phairplay.BuildConfig
import com.phairplay.R
import com.phairplay.diagnostics.ActivityEntry
import com.phairplay.diagnostics.ActivityLog
import com.phairplay.diagnostics.Severity

/**
 * ActivityLogDialog — the chronological activity view (Home → Activity, Settings → Advanced).
 *
 * WHAT IT SHOWS, AND WHY
 *  - **Chronological** entries, oldest first, auto-scrolled to the newest. 2.0's "connection
 *    details" was one unlabelled multi-line string: you could not tell what happened when, which
 *    subsystem said it, or whether it mattered.
 *  - **Severity is a word, not just a colour**: every row carries `INFO`/`WARN`/`ERROR`/`CRIT`
 *    and CRITICAL adds "needs action". The colour is a hint, never the only signal.
 *  - **Filters** by minimum severity. Subsystem filtering is available through the log API and is
 *    intentionally kept out of the first TV screenful: three focus targets are reachable with a
 *    D-pad, a spinner per axis is not.
 *  - **Details on demand**: a row with technical detail is focusable and expands on OK/click, so
 *    playback is never cluttered with stack frames.
 *  - **Copy** puts the sanitised export on the clipboard; **Clear** empties the bounded ring and
 *    its file.
 *
 * Every control is focusable and the dialog keeps the D-pad inside itself until it is dismissed.
 */
object ActivityLogDialog {

    fun show(context: Context) {
        ActivityLog.init(context)
        val density = context.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        var minSeverity: Severity? = null

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), 0)
        }

        val status = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(context.getColor(R.color.text_secondary))
            setPadding(0, dp(6), 0, dp(8))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }

        val filterRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // Virtualize the bounded log: build only visible rows rather than all 300 entries.
        val list = ListView(context).apply {
            divider = null
            isSmoothScrollbarEnabled = true
            isFastScrollEnabled = true
            itemsCanFocus = true
            setPadding(0, dp(6), 0, dp(6))
            setOnKeyListener { _, key, event ->
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount >= 3 &&
                    (key == KeyEvent.KEYCODE_DPAD_DOWN || key == KeyEvent.KEYCODE_DPAD_UP) && count > 0
                ) {
                    val direction = if (key == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1
                    val current = selectedItemPosition.takeIf { it >= 0 } ?: firstVisiblePosition
                    setSelection((current + direction * 5).coerceIn(0, count - 1))
                    true
                } else false
            }
        }
        list.setOnItemClickListener { _, row, _, _ -> row.performClick() }

        lateinit var render: () -> Unit

        fun chip(labelRes: Int, filter: Severity?): TextView = TextView(context).apply {
            setText(labelRes)
            isFocusable = true
            isClickable = true
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(11), dp(16), dp(11))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(ContextCompat.getColorStateList(context, R.color.btn_control_text))
            setBackgroundResource(R.drawable.btn_control_selector)
            setOnClickListener {
                minSeverity = filter
                render()
            }
        }

        val allChip = chip(R.string.activity_log_filter_all, null)
        val warnChip = chip(R.string.activity_log_filter_warning, Severity.WARNING)
        val errorChip = chip(R.string.activity_log_filter_error, Severity.ERROR)
        filterRow.addView(allChip)
        filterRow.addView(warnChip)
        filterRow.addView(errorChip)

        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val copyButton = actionButton(context, R.string.activity_log_copy)
        val clearButton = actionButton(context, R.string.activity_log_clear)
        actionRow.addView(copyButton)
        actionRow.addView(clearButton)
        val oldestButton = actionButton(context, R.string.activity_log_oldest)
        val newestButton = actionButton(context, R.string.activity_log_newest)
        oldestButton.setOnClickListener { list.setSelection(0); list.requestFocus() }
        newestButton.setOnClickListener { list.setSelection((list.count - 1).coerceAtLeast(0)); list.requestFocus() }
        actionRow.addView(oldestButton)
        actionRow.addView(newestButton)
        actionRow.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        actionRow.addView(TextView(context).apply {
            setText(R.string.activity_log_sort_hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(context.getColor(R.color.text_tertiary))
        })

        render = {
            val entries = ActivityLog.entries(minSeverity)
            status.text = if (entries.isEmpty()) context.getString(R.string.activity_log_empty) else
                context.getString(R.string.activity_log_summary, entries.size, ActivityLog.MAX_ENTRIES)
            list.adapter = object : BaseAdapter() {
                override fun getCount() = entries.size
                override fun getItem(position: Int) = entries[position]
                override fun getItemId(position: Int) = position.toLong()
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                    entryRow(context, entries[position]) { dp(it) }
            }
            list.setSelection((entries.size - 1).coerceAtLeast(0))
        }

        copyButton.setOnClickListener {
            val report = ActivityLog.exportText(
                mapOf(
                    "Hearth version" to BuildConfig.VERSION_NAME,
                    "Android API" to Build.VERSION.SDK_INT.toString(),
                )
            )
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (clipboard != null) {
                clipboard.setPrimaryClip(
                    ClipData.newPlainText(context.getString(R.string.home_log_share_subject), report)
                )
                status.text = context.getString(R.string.activity_log_copied)
            } else {
                status.text = context.getString(R.string.activity_log_copy_failed)
            }
        }

        clearButton.setOnClickListener {
            ActivityLog.clear()
            ActivityLog.record(Severity.INFO, "ui", "Activity log cleared by the user")
            render()
        }

        root.addView(filterRow)
        root.addView(status)
        // Roughly half a TV screen: enough for ~8 rows, still leaves the dialog title and the
        // hardware D-pad focus ring inside the safe area.
        val listHeight = (context.resources.configuration.screenHeightDp * 0.55f).toInt().coerceAtLeast(240)
        root.addView(list, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(listHeight)
        ))
        root.addView(actionRow)

        render()

        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.activity_log_title)
            .setView(root)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dialog.show()
        // Newest activity is at the bottom of a chronological list; show it without making the
        // user scroll through yesterday to find out what just failed.
        list.post { list.setSelection((list.count - 1).coerceAtLeast(0)) }
        allChip.requestFocus()
    }

    /** One row: timestamp, severity word, subsystem, description, optional expandable details. */
    private fun entryRow(
        context: Context,
        entry: ActivityEntry,
        dp: (Int) -> Int,
    ): View {
        val details = entry.details
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundResource(R.drawable.focus_ring_selector)
        }

        val header = TextView(context).apply {
            text = buildString {
                append(entry.timeLabel()).append("  ")
                append(entry.severity.label.padEnd(5)).append(' ')
                append(entry.subsystem)
                if (entry.severity == Severity.CRITICAL) append("  · needs action")
            }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.MONOSPACE
            setTextColor(context.getColor(severityColor(entry.severity)))
        }
        val message = TextView(context).apply {
            text = entry.message
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(context.getColor(R.color.text_primary))
            setPadding(0, dp(3), 0, 0)
        }
        container.addView(header)
        container.addView(message)

        if (!details.isNullOrBlank()) {
            val detailView = TextView(context).apply {
                text = details
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.MONOSPACE
                setTextColor(context.getColor(R.color.text_tertiary))
                setPadding(0, dp(6), 0, 0)
                visibility = View.GONE
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            container.addView(detailView)
            container.isFocusable = false
            container.isClickable = true
            container.setOnClickListener {
                val showing = detailView.visibility == View.VISIBLE
                detailView.visibility = if (showing) View.GONE else View.VISIBLE
                ViewCompat.setStateDescription(
                    container,
                    context.getString(
                        if (showing) R.string.activity_log_details_hidden
                        else R.string.activity_log_details_shown
                    )
                )
            }
            ViewCompat.setStateDescription(container, context.getString(R.string.activity_log_details_hint))
        }

        // The delegate is AccessibilityDelegateCompat (not the framework class) because that is
        // what ViewCompat#setAccessibilityDelegate accepts. The node text is the entry summary so a
        // screen reader announces what happened instead of a bare container.
        ViewCompat.setAccessibilityDelegate(container, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.text = entry.summaryLine()
            }
        })
        return container
    }

    private fun actionButton(context: Context, labelRes: Int): Button {
        // Density is resolved here so the caller never has to pass a `dp` reference around.
        val density = context.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()
        return Button(context).apply {
            setText(labelRes)
            isFocusable = true
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(ContextCompat.getColorStateList(context, R.color.btn_control_text))
            setBackgroundResource(R.drawable.btn_control_selector)
            minHeight = dp(48)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
        }
    }

    private fun severityColor(severity: Severity): Int = when (severity) {
        Severity.DEBUG -> R.color.text_tertiary
        Severity.INFO -> R.color.text_secondary
        Severity.WARNING -> R.color.status_transitioning
        Severity.ERROR -> R.color.status_stopped
        Severity.CRITICAL -> R.color.status_critical
    }
}
