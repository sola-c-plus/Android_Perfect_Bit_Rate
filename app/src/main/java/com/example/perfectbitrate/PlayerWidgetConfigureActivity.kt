package com.example.perfectbitrate

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit

class PlayerWidgetConfigureActivity : AppCompatActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        setContentView(R.layout.activity_player_widget_configure)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val prefs = getSharedPreferences("bp_widget_prefs", Context.MODE_PRIVATE)
        val currentTheme = prefs.getString("widget_theme_$appWidgetId", "auto") ?: "auto"

        val radioGroup = findViewById<RadioGroup>(R.id.radioGroupWidgetTheme)
        when (currentTheme) {
            "dark" -> findViewById<RadioButton>(R.id.radioDark).isChecked = true
            "light" -> findViewById<RadioButton>(R.id.radioLight).isChecked = true
            else -> findViewById<RadioButton>(R.id.radioAuto).isChecked = true
        }

        findViewById<Button>(R.id.btnWidgetSave).setOnClickListener {
            val selectedTheme = when (radioGroup.checkedRadioButtonId) {
                R.id.radioDark -> "dark"
                R.id.radioLight -> "light"
                else -> "auto"
            }

            prefs.edit { putString("widget_theme_$appWidgetId", selectedTheme) }

            // ウィジェットを即時再描画
            PlayerWidgetProvider.updateAllWidgets(this)

            val resultValue = Intent().apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            setResult(RESULT_OK, resultValue)
            finish()
        }
    }
}