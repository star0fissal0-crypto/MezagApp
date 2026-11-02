package com.mezagapp.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.widget.Button
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var clockText: TextView
    private lateinit var dateText: TextView

    private val handler = Handler(Looper.getMainLooper())

    private val updateTask = object : Runnable {
        override fun run() {
            updateDateAndTime()
            handler.postDelayed(this, 30_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        clockText = findViewById(R.id.clockText)
        dateText = findViewById(R.id.dateText)

        findViewById<Button>(R.id.settingsButton)
            .setOnClickListener {
                startActivity(
                    Intent(this, SettingsActivity::class.java)
                )
            }

        updateDateAndTime()
    }

    override fun onResume() {
        super.onResume()

        if (::clockText.isInitialized) {
            handler.removeCallbacks(updateTask)
            handler.post(updateTask)
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(updateTask)
    }

    private fun updateDateAndTime() {
        val now = Date()

        val preferences = getSharedPreferences(
            "mezag_settings",
            MODE_PRIVATE
        )

        val use24Hour = preferences.getBoolean(
            "use_24_hour",
            DateFormat.is24HourFormat(this)
        )

        val clockPattern = if (use24Hour) {
            "HH:mm"
        } else {
            "hh:mm a"
        }

        val clockFormatter = SimpleDateFormat(
            clockPattern,
            Locale.getDefault()
        )

        val dateFormatter = SimpleDateFormat(
            "EEEE، d MMMM yyyy",
            Locale("ar")
        )

        clockText.text = clockFormatter.format(now)
        dateText.text = dateFormatter.format(now)
    }
}
