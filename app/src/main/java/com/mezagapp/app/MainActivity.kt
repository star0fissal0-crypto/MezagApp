package com.mezagapp.app

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private val dateFormat =
        SimpleDateFormat("EEEE، d MMMM yyyy", Locale("ar"))

    private val clockFormat =
        SimpleDateFormat("hh:mm a", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        updateDateAndTime()
    }

    private fun updateDateAndTime() {
        val now = Date()

        val clockText = findViewById<TextView>(R.id.clockText)
        val dateText = findViewById<TextView>(R.id.dateText)

        clockText.text = clockFormat.format(now)
        dateText.text = dateFormat.format(now)
    }
}
