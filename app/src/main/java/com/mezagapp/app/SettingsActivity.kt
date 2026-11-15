package com.mezagapp.app

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
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

    // حالة فتح الأقسام والقوائم الفرعية (تبقى أثناء وجود الشاشة)
    private var openSection = -1
    private var pinOpen = false
    private var historyOpen = false
    private var offsetsOpen = false

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
        val t = ThemeManager.resolve(this, settings, store)
        ThemeManager.applySystemBars(this, t)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(20), dp(28), dp(20), dp(40))
        }

        fun rowLp() = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) }

        /** عناصر داخل القسم: مزاحة قليلاً عن عنوان القسم */
        fun bodyLp() = rowLp().apply { marginStart = dp(14) }

        fun tap(
            s: String, color: Int, bg: Int, bold: Boolean = false, onClick: () -> Unit
        ) = TextView(this).apply {
            text = s
            textSize = 18f
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            background = box(bg)
            setPadding(dp(18), dp(14), dp(18), dp(14))
            setOnClickListener { onClick() }
        }

        fun note(s: String) = TextView(this).apply {
            text = s
            textSize = 13f
            setTextColor(t.sub)
            setPadding(dp(4), 0, dp(4), dp(8))
        }

        fun item(s: String, on: Boolean = false, onClick: () -> Unit) =
            tap("🔸 " + (if (on) "✓  " else "") + s, t.text, t.card) { onClick() }

        fun switchRow(s: String, checked: Boolean, onChange: (Boolean) -> Unit) =
            Switch(this).apply {
                text = s
                textSize = 17f
                isChecked = checked
                setTextColor(t.text)
                setPadding(dp(6), dp(8), dp(6), dp(8))
                setOnCheckedChangeListener { _, v -> onChange(v) }
            }

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

        /** قسم رئيسي قابل للفتح والإغلاق */
        fun section(index: Int, title: String, body: () -> Unit) {
            val isOpen = openSection == index
            col.addView(
                tap("🔶 $title" + (if (isOpen) "   ▴" else "   ▾"), t.accent, t.card, true) {
                    openSection = if (isOpen) -1 else index
                    render()
                },
                rowLp()
            )
            if (isOpen) body()
        }

        col.addView(TextView(this).apply {
            text = "الإعدادات"
            textSize = 30f
            setTextColor(t.text)
            setPadding(0, 0, 0, dp(14))
        })

        // ===================== 1) المظهر =====================
        section(0, "المظهر") {
            val choice = settings.themeChoice
            val pinned = choice in 0..5

            col.addView(item("ثيم تلقائي حسب الوقت", choice == ThemeManager.AUTO) {
                settings.themeChoice = ThemeManager.AUTO
                pinOpen = false
                render()
            }, bodyLp())

            col.addView(
                item(
                    "تثبيت ثيم واحد" +
                        (if (pinned) " (${ThemeManager.themes[choice].arabicName})" else ""),
                    pinned
                ) {
                    pinOpen = !pinOpen
                    render()
                },
                bodyLp()
            )
            if (pinOpen) {
                for (i in 0..5) {
                    col.addView(
                        tap(
                            (if (choice == i) "✓  " else "◦  ") + ThemeManager.themes[i].arabicName,
                            t.text, t.card
                        ) {
                            settings.themeChoice = i
                            pinOpen = false
                            render()
                        },
                        bodyLp().apply { marginStart = dp(34) }
                    )
                }
            }

            col.addView(item("اختيار الثيم الأبيض", choice == 6) {
                settings.themeChoice = 6
                pinOpen = false
                render()
            }, bodyLp())
            col.addView(item("اختيار الثيم الأسود", choice == 7) {
                settings.themeChoice = 7
                pinOpen = false
                render()
            }, bodyLp())

            // لون الخط (ميزة موجودة سابقًا، أُبقيت داخل هذا القسم)
            col.addView(item("لون الخط: تلقائي (متناسق مع الخلفية)", settings.textColorChoice < 0) {
                settings.textColorChoice = -1
                render()
            }, bodyLp())
            val swatches = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
            }
            ThemeManager.TEXT_COLORS.forEachIndexed { i, c ->
                val sel = settings.textColorChoice == i
                swatches.addView(View(this).apply {
                    background = GradientDrawable().apply {
                        setColor(c)
                        cornerRadius = dp(10).toFloat()
                        setStroke(
                            dp(if (sel) 3 else 1),
                            if (sel) t.accent else ThemeManager.alpha(t.text, 0x66)
                        )
                    }
                    setOnClickListener {
                        settings.textColorChoice = i
                        render()
                    }
                }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
            }
            col.addView(swatches, bodyLp())
            col.addView(note("اللون الضعيف التباين مع الخلفية يُستبدل تلقائيًا لضمان وضوح النص."), bodyLp())
        }

        // ===================== 2) التاريخ والوقت =====================
        section(1, "التاريخ والوقت") {
            col.addView(item("التاريخ الهجري: تلقائي", !settings.hijriManual) {
                settings.hijriManual = false
                settings.hijriAdjust = 0
                render()
            }, bodyLp())
            col.addView(item("التاريخ الهجري: يدوي", settings.hijriManual) {
                settings.hijriManual = true
                render()
            }, bodyLp())
            if (settings.hijriManual) {
                val a = settings.hijriAdjust
                col.addView(
                    stepper(
                        n(
                            when {
                                a > 0 -> "+$a يوم"
                                a < 0 -> "$a يوم"
                                else -> "بلا تعديل"
                            }
                        ),
                        { settings.hijriAdjust = settings.hijriAdjust - 1; render() },
                        { settings.hijriAdjust = settings.hijriAdjust + 1; render() }
                    ),
                    bodyLp()
                )
            }

            col.addView(item("الشهور الميلادية: شرقية (كانون، شباط…)", settings.levantMonths) {
                settings.levantMonths = true
                render()
            }, bodyLp())
            col.addView(item("الشهور الميلادية: إنجليزية (يناير، فبراير…)", !settings.levantMonths) {
                settings.levantMonths = false
                render()
            }, bodyLp())

            col.addView(item(n("نظام الساعة: 12 ساعة"), !settings.use24h) {
                settings.use24h = false
                render()
            }, bodyLp())
            col.addView(item(n("نظام الساعة: 24 ساعة"), settings.use24h) {
                settings.use24h = true
                render()
            }, bodyLp())

            // الأرقام (ميزة موجودة سابقًا، أُبقيت داخل هذا القسم)
            col.addView(
                switchRow(
                    "الأرقام العربية (١٢٣) في التطبيق والويدجت، وإلا الإنجليزية (123)",
                    WidgetPrefs.arabicDigits(this)
                ) {
                    WidgetPrefs.setArabicDigits(this, it)
                    render()
                },
                bodyLp()
            )
        }

        // ===================== 3) الموقع =====================
        section(2, "الموقع") {
            if (store.hasLocation) {
                col.addView(note("📍 الموقع الحالي: ${store.place}"), bodyLp())
            }
            col.addView(item("تلقائي (حسب موقعي الحالي)") {
                startActivity(
                    Intent(this, LocationActivity::class.java).putExtra("mode", "auto")
                )
            }, bodyLp())
            col.addView(item("يدوي (بحث عن مدينة)") {
                startActivity(Intent(this, LocationActivity::class.java))
            }, bodyLp())

            col.addView(item("مواقعي القديمة") {
                historyOpen = !historyOpen
                render()
            }, bodyLp())
            if (historyOpen) {
                val hist = store.history()
                if (hist.isEmpty()) {
                    col.addView(note("لا توجد مواقع سابقة بعد."), bodyLp())
                }
                for (h in hist) {
                    col.addView(
                        tap("📍 ${h.place}", t.text, t.card) {
                            store.setLocation(h.lat, h.lon, h.place, h.country, h.tz)
                            historyOpen = false
                            render()
                        },
                        bodyLp().apply { marginStart = dp(34) }
                    )
                }
            }

            col.addView(note(
                "مواقيت الصلاة: " + stamp(store.prayerFetched) +
                    "\nالطقس: " + stamp(store.weatherFetched)
            ), bodyLp())
            col.addView(item("تحديث البيانات الآن") {
                store.forceRefresh()
                render()
            }, bodyLp())
            col.addView(note("بعد الضغط ارجع للشاشة الرئيسية لبدء التحديث (يلزم إنترنت)."), bodyLp())
        }

        // ===================== 4) الصلاة =====================
        section(3, "الصلاة") {
            col.addView(item("تعديل أوقات الصلاة") {
                offsetsOpen = !offsetsOpen
                render()
            }, bodyLp())
            if (offsetsOpen) {
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
                    line.addView(
                        stepper(
                            n(
                                when {
                                    v > 0 -> "+$v دقيقة"
                                    v < 0 -> "$v دقيقة"
                                    else -> "0"
                                }
                            ),
                            { store.setOffset(i, store.offset(i) - 1); render() },
                            { store.setOffset(i, store.offset(i) + 1); render() }
                        )
                    )
                    col.addView(line, bodyLp().apply { marginStart = dp(34) })
                }
            }

            // طريقة الحساب (ميزة موجودة سابقًا، أُبقيت داخل هذا القسم)
            val mName = methods.firstOrNull { it.first == store.methodChoice }?.second
                ?: methods[0].second
            col.addView(item("طريقة الحساب: $mName") {
                val idx = methods.indexOfFirst { it.first == store.methodChoice }
                store.methodChoice = methods[(idx + 1) % methods.size].first
                store.prayerData = ""
                store.forceRefresh()
                render()
            }, bodyLp())
            col.addView(note("اضغط لتغيير الطريقة. يُعاد جلب المواقيت تلقائيًا."), bodyLp())

            col.addView(
                item(
                    if (settings.adhanEnabled) "الأذان: مفعّل" else "الأذان: غير مفعّل",
                    settings.adhanEnabled
                ) {
                    settings.adhanEnabled = !settings.adhanEnabled
                    render()
                },
                bodyLp()
            )
            if (settings.adhanEnabled) {
                for (i in intArrayOf(0, 2, 3, 4, 5)) {
                    col.addView(
                        switchRow("أذان " + PrayerManager.names[i], settings.adhanOn(i)) {
                            settings.setAdhan(i, it)
                        },
                        bodyLp().apply { marginStart = dp(34) }
                    )
                }
            }
            col.addView(note("صوت الأذان سيُضاف لاحقًا؛ هذه الخيارات فقط الآن."), bodyLp())
        }

        // ===================== 5) المنبه =====================
        section(4, "المنبه") {
            col.addView(note("سيُضاف لاحقًا."), bodyLp())
        }

        // ===================== 6) الإشعارات =====================
        section(5, "الإشعارات") {
            col.addView(note("سيُضاف لاحقًا."), bodyLp())
        }

        setContentView(ScrollView(this).apply {
            setBackgroundColor(t.bgTop)
            fitsSystemWindows = true
            addView(col)
        })
    }
}
