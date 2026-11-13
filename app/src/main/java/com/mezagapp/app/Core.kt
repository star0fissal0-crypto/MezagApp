package com.mezagapp.app

import android.content.Context
import android.graphics.Color
import android.text.format.DateFormat
import java.time.LocalDate
import java.time.LocalDateTime
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

    var themeIndex: Int
        get() = p.getInt("themeIndex", 0)
        set(v) { p.edit().putInt("themeIndex", v).apply() }
}

/** الثيمات الستة */
class Theme(
    val arabicName: String,
    val bg: Int,
    val card: Int,
    val text: Int,
    val sub: Int,
    val accent: Int
)

object ThemeManager {
    val themes = listOf(
        Theme(
            "منتصف الليل",
            Color.parseColor("#0B1B33"), Color.parseColor("#14284A"),
            Color.parseColor("#F6EEDC"), Color.parseColor("#C9B47E"), Color.parseColor("#E8CC82")
        ),
        Theme(
            "زمردي",
            Color.parseColor("#06231C"), Color.parseColor("#0E3A2E"),
            Color.parseColor("#EFF8F2"), Color.parseColor("#9FC9B5"), Color.parseColor("#5FD6A0")
        ),
        Theme(
            "بنفسجي",
            Color.parseColor("#1A1030"), Color.parseColor("#2A1B4D"),
            Color.parseColor("#F3EEFB"), Color.parseColor("#BBA8E0"), Color.parseColor("#B794F6")
        ),
        Theme(
            "جرافيت",
            Color.parseColor("#16181B"), Color.parseColor("#25282D"),
            Color.parseColor("#ECECEC"), Color.parseColor("#A5A9AF"), Color.parseColor("#D9DDE3")
        ),
        Theme(
            "برونزي",
            Color.parseColor("#231810"), Color.parseColor("#3A2A1C"),
            Color.parseColor("#F7EBDD"), Color.parseColor("#CDAE8A"), Color.parseColor("#D9A066")
        ),
        Theme(
            "خمري",
            Color.parseColor("#2A0D14"), Color.parseColor("#431621"),
            Color.parseColor("#F8ECEE"), Color.parseColor("#D1A1AB"), Color.parseColor("#E26A80")
        )
    )

    fun get(index: Int): Theme = themes[index.coerceIn(0, themes.size - 1)]
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
