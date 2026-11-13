package com.mezagapp.app

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsActivity : Activity() {
    private lateinit var settings: SettingsRepository
    private lateinit var store: Store

    private val methods = listOf(
        0 to "تلقائي (حسب الدولة)", 13 to "ديانت تركيا", 3 to "رابطة العالم الإسلامي",
        4 to "أم القرى", 5 to "الهيئة المصرية", 1 to "كراتشي",
        2 to "أمريكا الشمالية", 8 to "الخليج", 9 to "الكويت", 10 to "قطر"
    )

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun box(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(14).toFloat()
    }

    /** يحوّل الأرقام حسب الخيار العام للتطبيق (عربية أو إنجليزية) */
    private fun n(s: String): String {
        if (!WidgetPrefs.arabicDigits(this)) return s
        val sb = StringBuilder()
        for (ch in s) sb.append(if (ch in '0'..'9') '٠' + (ch - '0') else ch)
        return sb.toString()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsRepository(this)
        store = Store(this)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onPause() {
        super.onPause()
        WidgetUpdateManager.updateAll(applicationContext, false)
    }

    private fun stamp(ms: Long): String =
        if (ms == 0L) "لم تُجلب بعد"
        else "آخر تحديث: " + n(SimpleDateFormat("d/M HH:mm", Locale.US).format(Date(ms)))

    private fun render() {
        val t = ThemeManager.get(settings.themeIndex)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(20), dp(28), dp(20), dp(40))
        }

        fun label(s: String, size: Float, color: Int) = TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            setPadding(0, dp(12), 0, dp(8))
        }

        fun switchRow(s: String, checked: Boolean, onChange: (Boolean) -> Unit) =
            Switch(this).apply {
                text = s
                textSize = 18f
                isChecked = checked
                setTextColor(t.text)
                setPadding(0, dp(10), 0, dp(10))
                setOnCheckedChangeListener { _, v -> onChange(v) }
            }

        fun tap(s: String, color: Int, bg: Int, onClick: () -> Unit) = TextView(this).apply {
            text = s
            textSize = 18f
            setTextColor(color)
            background = box(bg)
            setPadding(dp(18), dp(14), dp(18), dp(14))
            setOnClickListener { onClick() }
        }

        fun rowLp() = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) }

        fun stepper(valueText: String, onMinus: () -> Unit, onPlus: () -> Unit): LinearLayout {
            fun b(s: String, f: () -> Unit) = TextView(this).apply {
                text = s
                textSize = 24f
                gravity = Gravity.CENTER
                setTextColor(t.accent)
                background = box(t.card)
                setPadding(dp(22), dp(6), dp(22), dp(6))
                setOnClickListener { f() }
            }
            return LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(b("+", onPlus))
                addView(TextView(this@SettingsActivity).apply {
                    text = valueText
                    textSize = 17f
                    gravity = Gravity.CENTER
                    setTextColor(t.text)
                    minWidth = dp(90)
                })
                addView(b("−", onMinus))
            }
        }

        col.addView(label("الإعدادات", 30f, t.text))

        col.addView(switchRow(n("نظام 24 ساعة"), settings.use24h) { settings.use24h = it })
        col.addView(switchRow("أسماء الشهور المشرقية (كانون، شباط...)", settings.levantMonths) {
            settings.levantMonths = it
        })
        col.addView(switchRow(
            "الأرقام العربية (١٢٣) في التطبيق والويدجت، وإلا الإنجليزية (123)",
            WidgetPrefs.arabicDigits(this)
        ) {
            WidgetPrefs.setArabicDigits(this, it)
            render()
        })

        col.addView(label("تعديل التاريخ الهجري", 18f, t.sub))
        val a = settings.hijriAdjust
        col.addView(stepper(
            n(
                when {
                    a > 0 -> "+$a يوم"
                    a < 0 -> "$a يوم"
                    else -> "بلا تعديل"
                }
            ),
            { settings.hijriAdjust = settings.hijriAdjust - 1; render() },
            { settings.hijriAdjust = settings.hijriAdjust + 1; render() }
        ))

        col.addView(label("الموقع", 18f, t.sub))
        col.addView(tap(
            if (store.hasLocation) "📍 ${store.place}" else "📍 اختر المدينة", t.accent, t.card
        ) {
            startActivity(Intent(this, LocationActivity::class.java))
        }, rowLp())

        col.addView(label("طريقة حساب مواقيت الصلاة", 18f, t.sub))
        val mName = methods.firstOrNull { it.first == store.methodChoice }?.second ?: methods[0].second
        col.addView(tap(mName, t.accent, t.card) {
            val idx = methods.indexOfFirst { it.first == store.methodChoice }
            val next = methods[(idx + 1) % methods.size].first
            store.methodChoice = next
            store.prayerData = ""
            store.forceRefresh()
            render()
        }, rowLp())
        col.addView(TextView(this).apply {
            text = "اضغط لتغيير الطريقة. يُعاد جلب المواقيت تلقائيًا."
            textSize = 13f
            setTextColor(t.sub)
        })

        col.addView(label("تعديل الدقائق لكل صلاة", 18f, t.sub))
        for (i in intArrayOf(0, 2, 3, 4, 5)) {
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            line.addView(TextView(this).apply {
                text = PrayerManager.names[i]
                textSize = 18f
                setTextColor(t.text)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val v = store.offset(i)
            line.addView(stepper(
                n(
                    when {
                        v > 0 -> "+$v دقيقة"
                        v < 0 -> "$v دقيقة"
                        else -> "0"
                    }
                ),
                { store.setOffset(i, store.offset(i) - 1); render() },
                { store.setOffset(i, store.offset(i) + 1); render() }
            ))
            col.addView(line, rowLp())
        }

        col.addView(label("حالة البيانات", 18f, t.sub))
        col.addView(TextView(this).apply {
            text = "مواقيت الصلاة: " + stamp(store.prayerFetched) +
                "\nالطقس: " + stamp(store.weatherFetched)
            textSize = 15f
            setTextColor(t.text)
        })
        col.addView(tap("تحديث البيانات الآن", t.accent, t.card) {
            store.forceRefresh()
            render()
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10); bottomMargin = dp(8) })
        col.addView(TextView(this).apply {
            text = "بعد الضغط ارجع للشاشة الرئيسية لبدء التحديث (يلزم إنترنت)."
            textSize = 13f
            setTextColor(t.sub)
        })

        col.addView(label("الثيم", 18f, t.sub))
        ThemeManager.themes.forEachIndexed { i, th ->
            val selected = i == settings.themeIndex
            col.addView(tap(
                if (selected) "✓  ${th.arabicName}" else th.arabicName, th.accent, th.card
            ) {
                settings.themeIndex = i
                render()
            }, rowLp())
        }

        setContentView(ScrollView(this).apply {
            setBackgroundColor(t.bg)
            addView(col)
        })
    }
}
