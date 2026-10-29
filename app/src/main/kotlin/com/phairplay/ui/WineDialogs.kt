package com.phairplay.ui

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.view.ContextThemeWrapper
import androidx.appcompat.app.AlertDialog
import com.phairplay.R

/** All update prompts share the app's wine surfaces, typography and button colors. */
internal object WineDialogs {
    fun builder(context: Context): AlertDialog.Builder = object : AlertDialog.Builder(
        ContextThemeWrapper(context, R.style.Theme_Hearth_Dialog)
    ) {
        override fun create(): AlertDialog = super.create().also { dialog ->
            dialog.setOnShowListener {
                dialog.window?.setBackgroundDrawable(ColorDrawable(context.getColor(R.color.background_surface)))
                for (which in listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)) {
                    dialog.getButton(which)?.setTextColor(context.getColor(R.color.accent_blue))
                }
            }
        }
    }
}
