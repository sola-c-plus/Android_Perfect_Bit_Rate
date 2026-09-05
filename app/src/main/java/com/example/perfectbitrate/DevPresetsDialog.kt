package com.example.perfectbitrate

import android.app.Activity
import android.graphics.Color
import android.view.View
import android.view.WindowManager
import android.widget.Button
import com.google.android.material.bottomsheet.BottomSheetDialog

class DevPresetsDialog(private val activity: Activity) {

    fun show() {
        val bottomSheetDialog = BottomSheetDialog(activity, R.style.CustomBottomSheetDialogTheme).apply {
            window?.setDimAmount(0f)
            window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }

        val view = activity.layoutInflater.inflate(R.layout.dialog_dev_presets, null)
        FreqPresetManager.hookDevPresetsDialog(view)
        bottomSheetDialog.setContentView(view)

        bottomSheetDialog.setOnShowListener {
            val bottomSheet = bottomSheetDialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.setBackgroundColor(Color.TRANSPARENT)
        }

        bottomSheetDialog.setOnDismissListener {
            FreqPresetManager.clearDevDialogRefs()
        }

        view.findViewById<Button>(R.id.btnDevClose)?.setOnClickListener {
            bottomSheetDialog.dismiss()
        }

        bottomSheetDialog.show()
    }
}