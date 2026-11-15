package com.mezagapp.app

import android.app.Activity
import android.content.Context
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
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.roundToInt

/** يحفظ سبب أي توقف مفاجئ ليُعرض في المرة التالية */
object CrashLog {
    private var installed = false

    fun install(ctx: Context) {
        if (installed) return
        installed = true
        val app = ctx.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                app.getSharedPreferences("mezag_crash", Context.MODE_PRIVATE)
                    .edit().putString("last", "الخيط: ${t.name}\n" + sw.toString().take(2500)).commit()
            } catch (x: Throwable) {
            }
            prev?.uncaughtException(t, e)
        }
    }

    fun take(ctx: Context): String? {
        val p = ctx.applicationContext.getSharedPreferences("mezag_crash", Context.MODE_PRIVATE)
        val s = p.getString("last", null)
        if (s != null) p.edit().remove("last").apply()
        return s
    }
}

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

    private var crashText: String? = null

    private val font: Typeface by lazy {
        try {
            Typeface.createFromAsset(assets, "font.otf")
        } catch (e: Exception) {
            try {
                Typeface.createFromAsset(assets, "font.ttf")
            } catch (e2: Exception) {
                Typeface.DEFAULT
            }
        }
    }

    /** يعيد الرسم عند بداية كل دقيقة تقريبًا */
    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 60_000L - System.currentTimeMillis() % 60_000L + 300L)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun roundBox(color: Int, radius: Int = 20): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        crashText = CrashLog.take(this)
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(18), dp(16), dp(18), dp(36))
        }
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            fitsSystemWindows = true
            addView(col)
        }
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
            } catch (e: Throwable) {
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
        try {
            renderInner()
        } catch (e: Throwable) {
            showError(e)
        }
    }

    private fun showError(e: Throwable) {
        scroll.setBackgroundColor(android.graphics.Color.BLACK)
        col.removeAllViews()
        col.addView(TextView(this).apply {
            text = "حدث خطأ في عرض الشاشة. أرسل لي هذا النص:\n\n" + e.toString() + "\n" +
                e.stackTrace.take(14).joinToString("\n")
            textSize = 12f
            setTextColor(android.graphics.Color.WHITE)
            setTextIsSelectable(true)
        })
    }

    private fun renderInner() {
        val settings = SettingsRepository(this)
        val store = Store(this)
        val th = ThemeManager.resolve(this, settings, store)
        val arabic = WidgetPrefs.arabicDigits(this)
        val use24 = settings.use24h

        scroll.background = th.gradient()
        ThemeManager.applySystemBars(this, th)
        col.removeAllViews()

        fun n(s: String): String {
            if (!arabic) return s
            val sb = StringBuilder()
            for (ch in s) sb.append(if (ch in '0'..'9') '٠' + (ch - '0') else ch)
            return sb.toString()
        }

        fun tv(
            s: String, size: Float, color: Int,
            bold: Boolean = false, useFont: Boolean = true
        ): TextView = TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = when {
                !useFont -> Typeface.DEFAULT
                bold -> Typeface.create(font, Typeface.BOLD)
                else -> font
            }
        }

        fun add(v: View, top: Int = 12, center: Boolean = false) {
            val lp = LinearLayout.LayoutParams(
                if (center) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(top)
                if (center) gravity = Gravity.CENTER_HORIZONTAL
            }
            col.addView(v, lp)
        }

        fun cardBox(): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundBox(th.card, 24)
            setPadding(dp(10), dp(14), dp(10), dp(14))
        }

        fun hRow(): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        fun cell(): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        fun gap(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }

        fun weightLp(m: Int = 2) = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ).apply { setMargins(dp(m), 0, dp(m), 0) }

        fun vLine() = View(this).apply {
            setBackgroundColor(ThemeManager.alpha(th.text, 0x2E))
        }

        val zone = PrayerManager.zone(store)
        val zNow = ZonedDateTime.now(zone)
        val today = zNow.toLocalDate()
        val local = LocalDateTime.now()
        val r = if (store.hasLocation) PrayerManager.compute(store, zNow) else null

        // ===== الرأس: المدينة + الإعدادات =====
        val header = hRow()
        header.addView(
            tv(
                if (store.hasLocation) "📍 ${store.place}" else "📍 اختر المدينة",
                15f, th.sub, false, false
            ).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setPadding(0, dp(8), 0, dp(8))
                setOnClickListener {
                    startActivity(Intent(this@MainActivity, LocationActivity::class.java))
                }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        header.addView(
            tv("⚙️", 20f, th.text, false, false).apply {
                background = roundBox(th.card, 20)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setOnClickListener {
                    startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                }
            }
        )
        add(header, 0)

        val ct = crashText
        if (ct != null) {
            add(tv("⚠️ توقف التطبيق في المرة السابقة. أرسل لي هذا النص:", 13f, th.accent, false, false), 12)
            add(
                TextView(this).apply {
                    text = ct
                    textSize = 10f
                    setTextColor(th.text)
                    setTextIsSelectable(true)
                    typeface = Typeface.MONOSPACE
                    background = roundBox(th.card, 12)
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                }, 6
            )
            add(
                tv("إخفاء", 14f, th.accent, true, false).apply {
                    setPadding(0, dp(8), 0, dp(8))
                    setOnClickListener {
                        crashText = null
                        render()
                    }
                }, 4
            )
        }

        // ===== الساعة والتاريخ =====
        val date = local.toLocalDate()
        add(tv(ClockManager.dayName(date), 22f, th.accent, true), 18)
        val cp = ClockManager.parts(local, use24)
        add(tv(n("${cp.hour}:${cp.minute}"), 88f, th.text, true), 2)
        if (cp.period.isNotEmpty()) add(tv(cp.period, 20f, th.sub), 0)
        add(
            tv(
                n(ClockManager.gregorian(date, settings.levantMonths)) + "   ·   " +
                    n(ClockManager.hijri(date, settings.hijriAdjust)),
                14f, th.sub
            ), 6
        )

        // ===== العد التنازلي للصلاة القادمة =====
        if (r != null) {
            add(
                tv(n(PrayerManager.text(r.state)), 16f, th.accent, true).apply {
                    background = roundBox(ThemeManager.alpha(th.accent, 0x2A), 26)
                    setPadding(dp(18), dp(10), dp(18), dp(10))
                }, 22, true
            )
        }

        // ===== مواقيت الصلاة =====
        if (!store.hasLocation) {
            add(
                tv("اختر مدينتك لعرض المواقيت والطقس", 17f, th.accent, true).apply {
                    background = roundBox(th.card, 24)
                    setPadding(dp(16), dp(20), dp(16), dp(20))
                    setOnClickListener {
                        startActivity(Intent(this@MainActivity, LocationActivity::class.java))
                    }
                }, 28
            )
        } else if (r == null) {
            add(tv("جارٍ جلب المواقيت...", 15f, th.sub), 28)
        } else {
            val pc = cardBox()
            val prow = hRow()
            for (i in 0..5) {
                val t = r.times[i]
                val (tm, per) = hm(use24, t.hour, t.minute)
                val isNext = i == r.nextIndex
                val c = cell().apply {
                    setPadding(dp(2), dp(10), dp(2), dp(10))
                    if (isNext) background = roundBox(ThemeManager.alpha(th.accent, 0x38), 14)
                }
                c.addView(
                    tv(PrayerManager.displayName(i, today), 12f, if (isNext) th.accent else th.sub)
                )
                c.addView(gap(6))
                c.addView(tv(n(tm), 17f, if (isNext) th.accent else th.text, true))
                if (per.isNotEmpty()) c.addView(tv(per, 11f, th.sub))
                prow.addView(c, weightLp())
            }
            pc.addView(prow)
            add(pc, 26)
        }

        // ===== الطقس =====
        val days = if (store.hasLocation) WeatherManager.days(store, today) else emptyList()
        if (store.hasLocation) {
            if (days.isEmpty()) {
                add(tv("جارٍ جلب الطقس...", 15f, th.sub), 16)
            } else {
                val wc = cardBox()
                val drow = hRow()
                for ((k, d) in days.withIndex()) {
                    if (k > 0) {
                        drow.addView(
                            vLine(),
                            LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT)
                                .apply { setMargins(0, dp(6), 0, dp(6)) }
                        )
                    }
                    val name = when (d.date) {
                        today -> "اليوم"
                        today.plusDays(1) -> "غدًا"
                        today.plusDays(2) -> "بعد غد"
                        else -> ClockManager.dayName(d.date)
                    }
                    val c = cell()
                    c.addView(tv(name, 13f, th.sub))
                    c.addView(gap(4))
                    c.addView(tv(WeatherManager.cond(d.code).icon, 32f, th.text, false, false))
                    c.addView(gap(4))
                    c.addView(tv(n("${d.max}°"), 22f, th.text, true))
                    drow.addView(c, weightLp(4))
                }
                wc.addView(drow)

                val slots = fixedSlots(store, zNow.toLocalDateTime())
                if (slots.isNotEmpty()) {
                    wc.addView(
                        View(this).apply {
                            setBackgroundColor(ThemeManager.alpha(th.text, 0x2E))
                        },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
                            .apply { setMargins(dp(8), dp(14), dp(8), dp(14)) }
                    )
                    val srow = hRow()
                    for (s in slots) {
                        val label = if (use24) {
                            n(String.format(Locale.US, "%02d:00", s.time.hour))
                        } else {
                            val h12 = if (s.time.hour % 12 == 0) 12 else s.time.hour % 12
                            n("$h12") + " " + (if (s.time.hour < 12) "ص" else "م")
                        }
                        val c = cell()
                        c.addView(tv(label, 12f, th.sub))
                        c.addView(
                            tv(if (s.time.toLocalDate() != today) "غدًا" else " ", 10f, th.sub)
                        )
                        c.addView(gap(2))
                        c.addView(tv(WeatherManager.cond(s.code).icon, 22f, th.text, false, false))
                        c.addView(gap(2))
                        c.addView(tv(n("${s.temp}°"), 15f, th.text, true))
                        srow.addView(c, weightLp(1))
                    }
                    wc.addView(srow)
                }
                add(wc, 16)
            }
        }

        // ===== تنبيه البيانات القديمة =====
        val oldW = days.isNotEmpty() &&
            System.currentTimeMillis() - store.weatherFetched > 3L * 3600_000L
        if (store.hasLocation && (oldW || r?.stale == true)) {
            add(tv("بعض البيانات قديمة، تحقق من الاتصال", 12f, th.sub), 16)
        }
    }
}
