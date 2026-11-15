package com.mezagapp.app

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.format.DateFormat
import android.view.View
import android.view.WindowInsetsController
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.chrono.HijrahDate
import java.time.temporal.ChronoField
import java.util.Locale

/** التخزين المحلي: الموقع، مواقيت الصلاة، الطقس، تعديل الدقائق */
class Store(ctx: Context) {
    private val p = ctx.applicationContext
        .getSharedPreferences("mezag_data", Context.MODE_PRIVATE)

    private fun str(k: String): String = p.getString(k, "") ?: ""
    private fun lng(k: String): Long = p.getLong(k, 0L)

    val lat: Double get() = str("lat").toDoubleOrNull() ?: 0.0
    val lon: Double get() = str("lon").toDoubleOrNull() ?: 0.0
    val place: String get() = str("place")
    val country: String get() = str("country")
    val tz: String get() = str("tz")
    val hasLocation: Boolean get() = str("lat").isNotEmpty() && str("lon").isNotEmpty()

    fun setLocation(lat: Double, lon: Double, place: String, country: String, tz: String) {
        p.edit()
            .putString("lat", lat.toString())
            .putString("lon", lon.toString())
            .putString("place", place)
            .putString("country", country)
            .putString("tz", tz)
            .putString("prayerData", "")
            .putString("weatherJson", "")
            .putLong("prayerFetched", 0L)
            .putLong("prayerAttempt", 0L)
            .putLong("weatherFetched", 0L)
            .putLong("weatherAttempt", 0L)
            .apply()
    }

    var prayerData: String
        get() = str("prayerData")
        set(v) { p.edit().putString("prayerData", v).apply() }

    var prayerFetched: Long
        get() = lng("prayerFetched")
        set(v) { p.edit().putLong("prayerFetched", v).apply() }

    var prayerAttempt: Long
        get() = lng("prayerAttempt")
        set(v) { p.edit().putLong("prayerAttempt", v).apply() }

    var weatherJson: String
        get() = str("weatherJson")
        set(v) { p.edit().putString("weatherJson", v).apply() }

    var weatherFetched: Long
        get() = lng("weatherFetched")
        set(v) { p.edit().putLong("weatherFetched", v).apply() }

    var weatherAttempt: Long
        get() = lng("weatherAttempt")
        set(v) { p.edit().putLong("weatherAttempt", v).apply() }

    /** 0 = تلقائي حسب الدولة، وإلا رقم طريقة الحساب في Aladhan */
    var methodChoice: Int
        get() = p.getInt("methodChoice", 0)
        set(v) { p.edit().putInt("methodChoice", v).apply() }

    fun offset(i: Int): Int = p.getInt("off$i", 0)
    fun setOffset(i: Int, v: Int) { p.edit().putInt("off$i", v).apply() }

    /** يصفّر أوقات الجلب والمحاولات ليُعاد الجلب في أول تحديث */
    fun forceRefresh() {
        p.edit()
            .putLong("prayerFetched", 0L)
            .putLong("prayerAttempt", 0L)
            .putLong("weatherFetched", 0L)
            .putLong("weatherAttempt", 0L)
            .apply()
    }
}

/** إعدادات المستخدم */
class SettingsRepository(private val ctx: Context) {
    private val p = ctx.applicationContext
        .getSharedPreferences("mezag_settings", Context.MODE_PRIVATE)

    var use24h: Boolean
        get() = p.getBoolean("use24h", DateFormat.is24HourFormat(ctx))
        set(v) { p.edit().putBoolean("use24h", v).apply() }

    var levantMonths: Boolean
        get() = p.getBoolean("levantMonths", true)
        set(v) { p.edit().putBoolean("levantMonths", v).apply() }

    var hijriAdjust: Int
        get() = p.getInt("hijriAdjust", 0)
        set(v) { p.edit().putInt("hijriAdjust", v).apply() }

    /** ThemeManager.AUTO (تلقائي حسب الصلاة) أو ThemeManager.SYSTEM (حسب الهاتف) أو 0..7 ثيم ثابت */
    var themeChoice: Int
        get() = p.getInt("themeChoice", ThemeManager.AUTO)
        set(v) { p.edit().putInt("themeChoice", v).apply() }

    /** -1 = لون تلقائي متناسق مع الخلفية، وإلا رقم في ThemeManager.TEXT_COLORS */
    var textColorChoice: Int
        get() = p.getInt("textColorChoice", -1)
        set(v) { p.edit().putInt("textColorChoice", v).apply() }
}

/** ثيم = خلفية متدرجة + لون نص + لون مميز */
class Theme(
    val arabicName: String,
    val bgTop: Int,
    val bgBottom: Int,
    val text: Int,
    val accent: Int,
    val dark: Boolean
) {
    val bg: Int get() = bgTop
    val sub: Int get() = ThemeManager.alpha(text, 0xB8)
    val card: Int get() = if (dark) Color.argb(0x26, 255, 255, 255) else Color.argb(0x1C, 0, 0, 0)

    fun gradient(): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(bgTop, bgBottom))

    fun withText(c: Int) = Theme(arabicName, bgTop, bgBottom, c, accent, dark)
}

object ThemeManager {
    const val AUTO = -1
    const val SYSTEM = -2

    /** 0 الفجر، 1 الشروق، 2 الظهر، 3 العصر، 4 المغرب، 5 العشاء، 6 أبيض، 7 أسود */
    val themes = listOf(
        Theme(
            "الفجر",
            Color.parseColor("#0B1233"), Color.parseColor("#3B3478"),
            Color.parseColor("#EFE8FF"), Color.parseColor("#F4A8C4"), true
        ),
        Theme(
            "الشروق",
            Color.parseColor("#FFC98A"), Color.parseColor("#FFE9C7"),
            Color.parseColor("#4A2A0E"), Color.parseColor("#C4501B"), false
        ),
        Theme(
            "الظهر",
            Color.parseColor("#5BB4F0"), Color.parseColor("#CBEBFF"),
            Color.parseColor("#0B2A4A"), Color.parseColor("#0A5BB5"), false
        ),
        Theme(
            "العصر",
            Color.parseColor("#E3A02F"), Color.parseColor("#F8D78A"),
            Color.parseColor("#3A2208"), Color.parseColor("#7A3E00"), false
        ),
        Theme(
            "المغرب",
            Color.parseColor("#3A1C57"), Color.parseColor("#C94E2D"),
            Color.parseColor("#FFF2E3"), Color.parseColor("#FFD27A"), true
        ),
        Theme(
            "العشاء",
            Color.parseColor("#050A18"), Color.parseColor("#16264A"),
            Color.parseColor("#F1E7CF"), Color.parseColor("#E8CC82"), true
        ),
        Theme(
            "أبيض",
            Color.parseColor("#FFFFFF"), Color.parseColor("#F1F3F6"),
            Color.parseColor("#14181F"), Color.parseColor("#1F6FEB"), false
        ),
        Theme(
            "أسود",
            Color.parseColor("#000000"), Color.parseColor("#0B0B0D"),
            Color.parseColor("#F2F2F2"), Color.parseColor("#E8CC82"), true
        )
    )

    /** ألوان الخط الاختيارية */
    val TEXT_COLORS = intArrayOf(
        Color.parseColor("#FFFFFF"),
        Color.parseColor("#F6EEDC"),
        Color.parseColor("#E8CC82"),
        Color.parseColor("#14181F"),
        Color.parseColor("#3A2208"),
        Color.parseColor("#0B2A4A")
    )

    fun alpha(c: Int, a: Int): Int = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    private fun lum(c: Int): Double {
        fun f(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * f(Color.red(c)) + 0.7152 * f(Color.green(c)) + 0.0722 * f(Color.blue(c))
    }

    private fun contrast(a: Int, b: Int): Double {
        val l1 = Math.max(lum(a), lum(b))
        val l2 = Math.min(lum(a), lum(b))
        return (l1 + 0.05) / (l2 + 0.05)
    }

    /** الثيم الذي يناسب الوقت الحالي: يبدأ قبل كل صلاة بربع ساعة */
    fun autoIndex(store: Store): Int {
        if (store.hasLocation) {
            val now = ZonedDateTime.now(PrayerManager.zone(store))
            val r = PrayerManager.compute(store, now)
            if (r != null) {
                var idx = 5
                for (i in 0..5) {
                    if (!now.isBefore(r.times[i].minusMinutes(15))) idx = i
                }
                return idx
            }
        }
        val h = LocalDateTime.now().hour
        return when {
            h < 4 -> 5
            h < 6 -> 0
            h < 12 -> 1
            h < 15 -> 2
            h < 18 -> 3
            h < 20 -> 4
            else -> 5
        }
    }

    private fun isNight(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun mix(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
    )

    /** ثيم لون واحد مستوحى من متوسط لون الخلفية */
    fun fromBackground(ctx: Context, i: Int): Theme {
        val idx = i.coerceIn(0, 5)
        val c = Backgrounds.color(ctx, idx) ?: return themes[idx]
        val dark = lum(c) < 0.35
        val text = Color.parseColor(if (dark) "#F4F0E8" else "#14181F")
        val accent = if (dark) mix(c, Color.WHITE, 0.7f) else mix(c, Color.BLACK, 0.6f)
        return Theme(themes[idx].arabicName, c, c, text, accent, dark)
    }

    /** يحدد الثيم الفعلي مع لون الخط (الاختياري إن كان واضحًا، وإلا اللون المتناسق) */
    fun resolve(ctx: Context, settings: SettingsRepository, store: Store): Theme {
        val base = fromBackground(ctx, autoIndex(store))
        val tc = settings.textColorChoice
        if (tc in TEXT_COLORS.indices) {
            val c = TEXT_COLORS[tc]
            if (Math.min(contrast(c, base.bgTop), contrast(c, base.bgBottom)) >= 3.0) {
                return base.withText(c)
            }
        }
        return base
    }

    /** ألوان شريطي الحالة والتنقل وأيقوناتهما حسب الثيم */
    @Suppress("DEPRECATION")
    fun applySystemBars(a: Activity, th: Theme) {
        val w = a.window
        w.statusBarColor = th.bgTop
        w.navigationBarColor = th.bgBottom
        val lightBars = !th.dark
        if (Build.VERSION.SDK_INT >= 30) {
            val flags = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            w.insetsController?.setSystemBarsAppearance(if (lightBars) flags else 0, flags)
        } else {
            var f = w.decorView.systemUiVisibility
            val mask = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            f = if (lightBars) f or mask else f and mask.inv()
            w.decorView.systemUiVisibility = f
        }
    }
}

/** الساعة والتاريخان وأسماء الأيام */
object ClockManager {
    class Parts(val hour: String, val minute: String, val period: String)

    private val DAYS = arrayOf("الإثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت", "الأحد")

    private val STD_MONTHS = arrayOf(
        "يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو",
        "يوليو", "أغسطس", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر"
    )
    private val LEVANT_MONTHS = arrayOf(
        "كانون الثاني", "شباط", "آذار", "نيسان", "أيار", "حزيران",
        "تموز", "آب", "أيلول", "تشرين الأول", "تشرين الثاني", "كانون الأول"
    )
    private val HIJRI_MONTHS = arrayOf(
        "محرم", "صفر", "ربيع الأول", "ربيع الآخر", "جمادى الأولى", "جمادى الآخرة",
        "رجب", "شعبان", "رمضان", "شوال", "ذو القعدة", "ذو الحجة"
    )

    fun parts(t: LocalDateTime, use24h: Boolean): Parts {
        val m = String.format(Locale.US, "%02d", t.minute)
        return if (use24h) {
            Parts(String.format(Locale.US, "%02d", t.hour), m, "")
        } else {
            val h = if (t.hour % 12 == 0) 12 else t.hour % 12
            Parts(h.toString(), m, if (t.hour < 12) "ص" else "م")
        }
    }

    fun dayName(date: LocalDate): String = DAYS[date.dayOfWeek.value - 1]

    fun gregorian(date: LocalDate, levant: Boolean): String {
        val months = if (levant) LEVANT_MONTHS else STD_MONTHS
        return "${date.dayOfMonth} ${months[date.monthValue - 1]} ${date.year}"
    }

    /** هجري تقريبي (أم القرى) مع تعديل يدوي بالأيام لمطابقة ديانت */
    fun hijri(date: LocalDate, adjust: Int): String {
        val h = HijrahDate.from(date.plusDays(adjust.toLong()))
        val d = h.get(ChronoField.DAY_OF_MONTH)
        val m = h.get(ChronoField.MONTH_OF_YEAR)
        val y = h.get(ChronoField.YEAR)
        return "$d ${HIJRI_MONTHS[m - 1]} $y هـ"
    }
}

/** خلفيات الواجهة الرئيسية: assets/bg0.jpg … bg5.jpg */
object Backgrounds {
    private var cachedIndex = -1
    private var cachedBmp: Bitmap? = null
    private val avg = HashMap<Int, Int>()

    private fun cropToScreen(ctx: Context, b: Bitmap): Bitmap {
        val m = ctx.resources.displayMetrics
        val target = m.widthPixels.toFloat() / m.heightPixels
        val cur = b.width.toFloat() / b.height
        return if (cur > target) {
            val w = (b.height * target).toInt()
            Bitmap.createBitmap(b, (b.width - w) / 2, 0, w, b.height)
        } else {
            val h = (b.width / target).toInt()
            Bitmap.createBitmap(b, 0, (b.height - h) / 2, b.width, h)
        }
    }

    private fun decode(ctx: Context, i: Int): Bitmap? = try {
        val name = "bg$i.jpg"
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.assets.open(name).use { BitmapFactory.decodeStream(it, null, o) }
        var s = 1
        while (maxOf(o.outWidth, o.outHeight) / s > 1800) s *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = s }
        val src = ctx.assets.open(name).use { BitmapFactory.decodeStream(it, null, o2) }
        src?.let { cropToScreen(ctx, it) }
    } catch (e: Exception) {
        null
    }

    private fun bitmap(ctx: Context, i: Int): Bitmap? {
        if (i == cachedIndex && cachedBmp != null) return cachedBmp
        val b = decode(ctx, i) ?: return null
        cachedIndex = i
        cachedBmp = b
        val s = Bitmap.createScaledBitmap(b, 24, 24, true)
        var r = 0L
        var g = 0L
        var bl = 0L
        for (y in 0 until 24) for (x in 0 until 24) {
            val p = s.getPixel(x, y)
            r += Color.red(p)
            g += Color.green(p)
            bl += Color.blue(p)
        }
        avg[i] = Color.rgb((r / 576).toInt(), (g / 576).toInt(), (bl / 576).toInt())
        return b
    }

    fun drawable(ctx: Context, i: Int): BitmapDrawable? =
        bitmap(ctx, i)?.let { BitmapDrawable(ctx.resources, it) }

    fun color(ctx: Context, i: Int): Int? = avg[i] ?: run { bitmap(ctx, i); avg[i] }
}
