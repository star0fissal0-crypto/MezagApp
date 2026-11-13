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
        val limit = now.plusDays(3)
        while (out.size < 6 && t.isBefore(limit)) {
            if (t.isAfter(cutoff)) {
                val i = index[t]
                if (i != null) {
                    out.add(FixedSlot(t, temp.getDouble(i).roundToInt(), code.getInt(i)))
                }
            }
            t = t.plusHours(4)
        }
    } catch (e: Exception) {
    }
    return out
}

class MainActivity : Activity() {
    private lateinit var col: LinearLayout
    private lateinit var scroll: ScrollView
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var fetching = false

    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 30_000)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun box(color: Int, stroke: Int = 0): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(14).toFloat()
        if (stroke != 0) setStroke(dp(2), stroke)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(14), dp(24), dp(14), dp(32))
        }
        scroll = ScrollView(this).apply { addView(col) }
        setContentView(scroll)
        render()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(tick)
        handler.post(tick)
        refreshData()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    /** يجلب المواقيت والطقس عند الحاجة (خيط خلفي) ثم يعيد الرسم */
    private fun refreshData() {
        if (fetching) return
        val app = applicationContext
        if (!Store(app).hasLocation) return
        fetching = true
        Thread {
            try {
                val s = Store(app)
                val now = System.currentTimeMillis()
                var changed = false
                if ((s.prayerData.isEmpty() || now - s.prayerFetched > 12L * 3600_000L) &&
                    now - s.prayerAttempt > 5L * 60_000L
                ) {
                    s.prayerAttempt = now
                    PrayerManager.fetch(s)
                    changed = true
                }
                if ((s.weatherJson.isEmpty() || now - s.weatherFetched > 55L * 60_000L) &&
                    now - s.weatherAttempt > 5L * 60_000L
                ) {
                    s.weatherAttempt = now
                    WeatherManager.fetch(s)
                    changed = true
                }
                if (changed) {
                    WidgetUpdateManager.updateAll(app, false)
                    runOnUiThread { if (!isFinishing) render() }
                }
            } catch (e: Exception) {
            } finally {
                fetching = false
            }
        }.start()
    }

    private fun hm(use24: Boolean, h: Int, m: Int): Pair<String, String> =
        if (use24) {
            Pair(String.format(Locale.US, "%02d:%02d", h, m), "")
        } else {
            val h12 = if (h % 12 == 0) 12 else h % 12
            Pair(String.format(Locale.US, "%d:%02d", h12, m), if (h < 12) "ص" else "م")
        }

    private fun render() {
        val settings = SettingsRepository(this)
        val store = Store(this)
        val th = ThemeManager.get(settings.themeIndex)
        val arabic = WidgetPrefs.arabicDigits(this)
        val use24 = settings.use24h

        fun n(s: String): String {
            if (!arabic) return s
            val sb = StringBuilder()
            for (ch in s) sb.append(if (ch in '0'..'9') '٠' + (ch - '0') else ch)
            return sb.toString()
        }

        fun tv(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

        fun add(v: View, top: Int = 12) {
            col.addView(
                v,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(top) }
            )
        }

        fun tap(s: String, onClick: () -> Unit) = tv(s, 17f, th.accent).apply {
            background = box(th.card)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener { onClick() }
        }

        fun hRow() = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        fun vBox(stroke: Int = 0, hPad: Int = 4) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = box(th.card, stroke)
            setPadding(dp(hPad), dp(10), dp(hPad), dp(10))
        }

        fun cellLp(m: Int = 3) = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.MATCH_PARENT, 1f
        ).apply { setMargins(dp(m), 0, dp(m), 0) }

        scroll.setBackgroundColor(th.bg)
        col.removeAllViews()

        val zone = PrayerManager.zone(store)
        val zNow = ZonedDateTime.now(zone)
        val today = zNow.toLocalDate()
        val local = LocalDateTime.now()
        val r = if (store.hasLocation) PrayerManager.compute(store, zNow) else null

        // ===== الرأس: المدينة + الإعدادات =====
        val header = hRow()
        header.addView(
            tv(if (store.hasLocation) "📍 ${store.place}" else "📍 اختر المدينة", 17f, th.accent).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setOnClickListener { startActivity(Intent(this@MainActivity, LocationActivity::class.java)) }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        header.addView(
            tv("⚙️ الإعدادات", 17f, th.accent).apply {
                background = box(th.card)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
            }
        )
        add(header, 0)

        // ===== الساعة والتاريخ =====
        val cp = ClockManager.parts(local, use24)
        add(tv(n("${cp.hour}:${cp.minute}"), 76f, th.accent, true), 8)
        if (cp.period.isNotEmpty()) add(tv(cp.period, 20f, th.sub), 0)
        val date = local.toLocalDate()
        add(tv(ClockManager.dayName(date), 30f, th.text, true), 4)
        add(tv(n(ClockManager.gregorian(date, settings.levantMonths)), 18f, th.sub), 2)
        add(tv(n(ClockManager.hijri(date, settings.hijriAdjust)), 18f, th.sub), 2)

        // ===== مواقيت الصلاة =====
        if (!store.hasLocation) {
            add(tap("اختر مدينتك لعرض المواقيت والطقس") {
                startActivity(Intent(this, LocationActivity::class.java))
            }, 20)
        } else if (r == null) {
            add(tv("جارٍ جلب المواقيت...", 16f, th.sub), 20)
        } else {
            add(
                tv(n(PrayerManager.text(r.state)), 18f, th.accent, true).apply {
                    background = box(th.card)
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                }, 20
            )
            for (line in 0..1) {
                val row = hRow()
                for (k in 0..2) {
                    val i = line * 3 + k
                    val t = r.times[i]
                    val (tm, per) = hm(use24, t.hour, t.minute)
                    val isNext = i == r.nextIndex
                    val c = vBox(if (isNext) th.accent else 0)
                    c.addView(tv(PrayerManager.displayName(i, today), 14f, th.sub))
                    c.addView(tv(n(tm), 22f, if (isNext) th.accent else th.text, true))
                    c.addView(tv(per, 12f, th.sub))
                    row.addView(c, cellLp())
                }
                add(row, 8)
            }
        }

        // ===== الطقس =====
        val days = if (store.hasLocation) WeatherManager.days(store, today) else emptyList()
        if (store.hasLocation) {
            if (days.isEmpty()) {
                add(tv("جارٍ جلب الطقس...", 16f, th.sub), 20)
            } else {
                add(tv("الأيام القادمة", 14f, th.sub), 20)
                val row = hRow()
                for (d in days) {
                    val name = when (d.date) {
                        today -> "اليوم"
                        today.plusDays(1) -> "غدًا"
                        today.plusDays(2) -> "بعد غد"
                        else -> ClockManager.dayName(d.date)
                    }
                    val c = vBox(0, 4)
                    c.addView(tv(name, 16f, th.text, true))
                    c.addView(
                        tv(
                            n(ClockManager.gregorian(d.date, settings.levantMonths).substringBeforeLast(' ')),
                            12f, th.sub
                        )
                    )
                    c.addView(tv(WeatherManager.cond(d.code).icon, 34f, th.text))
                    c.addView(tv(n("${d.max}°"), 22f, th.accent, true))
                    row.addView(c, cellLp())
                }
                add(row, 6)
            }

            val slots = fixedSlots(store, zNow.toLocalDateTime())
            if (slots.isNotEmpty()) {
                add(tv("خلال اليوم", 14f, th.sub), 16)
                val row = hRow()
                for (s in slots) {
                    val label = if (use24) {
                        n(String.format(Locale.US, "%02d:00", s.time.hour))
                    } else {
                        val h12 = if (s.time.hour % 12 == 0) 12 else s.time.hour % 12
                        n("$h12") + " " + (if (s.time.hour < 12) "ص" else "م")
                    }
                    val c = vBox(0, 2)
                    c.addView(tv(label, 12f, th.sub))
                    c.addView(
                        tv(if (s.time.toLocalDate() != today) "غدًا" else " ", 10f, th.sub)
                    )
                    c.addView(tv(WeatherManager.cond(s.code).icon, 22f, th.text))
                    c.addView(tv(n("${s.temp}°"), 15f, th.text, true))
                    row.addView(c, cellLp(2))
                }
                add(row, 6)
            }
        }

        // ===== تنبيه البيانات القديمة =====
        val oldW = days.isNotEmpty() &&
            System.currentTimeMillis() - store.weatherFetched > 3L * 3600_000L
        if (store.hasLocation && (oldW || r?.stale == true)) {
            add(tv("بعض البيانات قديمة، تحقق من الاتصال", 13f, th.sub), 16)
        }
    }
}
