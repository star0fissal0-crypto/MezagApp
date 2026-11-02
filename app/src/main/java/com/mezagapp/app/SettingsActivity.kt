package com.mezagapp.app

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.Switch

class SettingsActivity : Activity() {

    private val preferences by lazy {
        getSharedPreferences(
            "mezag_settings",
            MODE_PRIVATE
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_settings)

        val timeFormatSwitch =
            findViewById<Switch>(R.id.timeFormatSwitch)

        val savedValue = preferences.getBoolean(
            "use_24_hour",
            android.text.format.DateFormat
                .is24HourFormat(this)
        )

        timeFormatSwitch.isChecked = savedValue

        timeFormatSwitch.setOnCheckedChangeListener {
                _, isChecked ->

            preferences.edit()
                .putBoolean("use_24_hour", isChecked)
                .apply()
        }

        findViewById<Button>(R.id.backButton)
            .setOnClickListener {
                finish()
            }
    }
}
