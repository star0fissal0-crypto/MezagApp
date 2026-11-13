package com.mezagapp.app

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.roundToInt

private class FixedSlot(val time: LocalDateTime, val temp: Int, val code: Int)

/**
 * فترات الطقس الثابتة: 04 و08 و12 و16 و20 و00.
 * تُحذف الفترة بعد 30 دقيقة من وقتها ويُضاف التالي، فيبقى العدد 6 دائمًا.
 */
private fun fixedSlots(store: Store, now: LocalDateTime): List<FixedSlot> {
    val out = ArrayList<FixedSlot>()
    try {
        val h = JSONObject(store.weatherJson).getJSONObject("hourly")
        val time = h.getJSONArray("time")
        val temp = h.getJSONArray("temperature_2m")
        val code = h.getJSONArray("weather_code")
        val index = HashMap<LocalDateTime, Int>()
        for (i in 0 until time.length()) index[LocalDateTime.parse(time.getString(i))] = i
        val cutoff = now.minusMinutes(30)
        var t = now.toLocalDate().atStartOfDay()
