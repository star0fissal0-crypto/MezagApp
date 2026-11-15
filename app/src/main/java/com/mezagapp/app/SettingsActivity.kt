package com.mezagapp.app

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsActivity : Activity() {
    private lateinit var settings: SettingsRepository
    private lateinit var store: Store

    // القسم المفتوح، والإعداد المفتوح داخله (واحد فقط في كل مرة)
    private var openSection = -1
    private var openKey: String? = null

    private class Opt(val label: String, val selected: Boolean, val action: () -> Unit)

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

        /** الإعدادات داخل القسم: مزاحة قليلاً عن عنوان القسم */
        fun bodyLp() = rowLp().apply { marginStart = dp(12) }

        /** خيارات القائمة المنسدلة: مزاحة أكثر */
        fun optLp() = rowLp().apply { marginStart = dp(28) }

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

        /** مفتاح تفعيل بسيط: مسار مدوّر وزر دائري، يأخذ ألوان الثيم */
        fun toggle(on: Boolean): View = FrameLayout(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(if (on) t.accent else ThemeManager.alpha(t.text, 0x44))
            }
            addView(
                View(this@SettingsActivity).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(if (on) t.bgTop else ThemeManager.alpha(t.text, 0xCC))
                    }
                },
                FrameLayout.LayoutParams(dp(22), dp(22)).apply {
                    gravity = (if (on) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL
                    setMargins(dp(3), 0, dp(3), 0)
                }
            )
        }

        /** إعداد تفعيل/تعطيل: النص مع المفتاح بجانبه، والضغط على السطر كله يبدّل */
        fun toggleRow(s: String, on: Boolean, onChange: (Boolean) -> Unit): LinearLayout =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = box(t.card)
                setPadding(dp(18), dp(14), dp(18), dp(14))
                addView(
                    TextView(this@SettingsActivity).apply {
                        text = s
                        textSize = 18f
                        setTextColor(t.text)
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
                addView(toggle(on), LinearLayout.LayoutParams(dp(48), dp(28)))
                setOnClickListener { onChange(!on) }
            }

        /**
         * إعداد اختيار من قائمة: سطر واحد فيه الاسم والقيمة الحالية،
         * وعند الضغط تظهر الخيارات تحته. [extra] لإضافة عناصر مخصصة أسفل الخيارات.
         */
        fun choiceRow(
            key: String,
            title: String,
            current: String,
            opts: List<Opt>,
            emptyNote: String? = null,
            extra: (() -> Unit)? = null
        ) {
            val isOpen = openKey == key
            val head = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = box(t.card)
                setPadding(dp(18), dp(14), dp(18), dp(14))
                addView(
                    TextView(this@SettingsActivity).apply {
                        text = title
                        textSize = 18f
                        setTextColor(t.text)
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
                if (current.isNotEmpty()) {
                    addView(TextView(this@SettingsActivity).apply {
                        text = current
                        textSize = 15f
                        setTextColor(t.accent)
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        maxWidth = dp(170)
                    })
                }
                addView(TextView(this@SettingsActivity).apply {
                    text = if (isOpen) "  ▴" else "  ▾"
                    textSize = 14f
                    setTextColor(t.sub)
                })
                setOnClickListener {
                    openKey = if (isOpen) null else key
                    render()
                }
            }
            col.addView(head, bodyLp())
            if (isOpen) {
                if (opts.isEmpty() && emptyNote != null) col.addView(note(emptyNote), optLp())
                for (o in opts) {
                    col.addView(
                        tap(
                            (if (o.selected) "✓  " else "      ") + o.label,
                            if (o.selected) t.accent else t.text, t.card
                        ) {
                            openKey = null
                            o.action()
                            render()
                        },
                        optLp()
                    )
                }
                extra?.invoke()
            }
        }

        /** قسم رئيسي قابل للفتح والإغلاق */
        fun section(index: Int, title: String, body: () -> Unit) {
            val isOpen = openSection == index
            col.addView(
                tap(title + (if (isOpen) "   ▴" else "   ▾"), t.accent, t.card, true) {
                    openSection = if (isOpen) -1 else index
                    openKey = null
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
            val themeName = when {
                choice in 0..5 -> "ثابت: " + ThemeManager.themes[choice].arabicName
                choice == 6 -> "الأبيض"
                choice == 7 -> "الأسود"
                else -> "تلقائي حسب الوقت"
            }
            val themeOpts = ArrayList<Opt>()
            themeOpts.add(Opt("تلقائي حسب الوقت", choice !in 0..7) {
                settings.themeChoice = ThemeManager.AUTO
            })
            for (i in 0..5) {
                themeOpts.add(Opt("تثبيت: " + ThemeManager.themes[i].arabicName, choice == i) {
                    settings.themeChoice = i
                })
            }
            themeOpts.add(Opt("الثيم الأبيض", choice == 6) { settings.themeChoice = 6 })
            themeOpts.add(Opt("الثيم الأسود", choice == 7) { settings.themeChoice = 7 })
            choiceRow("theme", "الثيم", themeName, themeOpts)

            choiceRow(
                "fontColor", "لون الخط",
                if (settings.textColorChoice < 0) "تلقائي" else "مخصص",
                listOf(Opt("تلقائي (متناسق مع الخلفية)", settings.textColorChoice < 0) {
                    settings.textColorChoice = -1
                }),
                null
            ) {
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
                            openKey = null
                            render()
                        }
                    }, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                        setMargins(dp(3), 0, dp(3), 0)
                    })
                }
                col.addView(swatches, optLp())
                col.addView(
                    note("اللون الضعيف التباين مع الخلفية يُستبدل تلقائيًا لضمان وضوح النص."),
                    optLp()
                )
            }
        }

        // ===================== 2) التاريخ والوقت =====================
        section(1, "التاريخ والوقت") {
            val adj = settings.hijriAdjust
            choiceRow(
                "hijri", "التاريخ الهجري",
                if (settings.hijriManual) "يدوي" else "تلقائي",
                listOf(
                    Opt("تلقائي", !settings.hijriManual) {
                        settings.hijriManual = false
                        settings.hijriAdjust = 0
                    },
                    Opt("يدوي (بتعديل الأيام)", settings.hijriManual) {
                        settings.hijriManual = true
                    }
                )
            )
            if (settings.hijriManual) {
                col.addView(
                    stepper(
                        n(
                            when {
                                adj > 0 -> "+$adj يوم"
                                adj < 0 -> "$adj يوم"
                                else -> "بلا تعديل"
                            }
                        ),
                        { settings.hijriAdjust = settings.hijriAdjust - 1; render() },
                        { settings.hijriAdjust = settings.hijriAdjust + 1; render() }
                    ),
                    optLp()
                )
            }

            choiceRow(
                "months", "الشهور الميلادية",
                if (settings.levantMonths) "شرقية" else "إنجليزية",
                listOf(
                    Opt("شرقية (كانون الثاني، شباط…)", settings.levantMonths) {
                        settings.levantMonths = true
                    },
                    Opt("إنجليزية (يناير، فبراير…)", !settings.levantMonths) {
                        settings.levantMonths = false
                    }
                )
            )

            choiceRow(
                "clock", "نظام الساعة",
                n(if (settings.use24h) "24 ساعة" else "12 ساعة"),
                listOf(
                    Opt(n("12 ساعة"), !settings.use24h) { settings.use24h = false },
                    Opt(n("24 ساعة"), settings.use24h) { settings.use24h = true }
                )
            )

            col.addView(
                toggleRow("الأرقام العربية (١٢٣)", WidgetPrefs.arabicDigits(this)) {
                    WidgetPrefs.setArabicDigits(this, it)
                    render()
                },
                bodyLp()
            )
        }

        // ===================== 3) الموقع =====================
        section(2, "الموقع") {
            choiceRow(
                "locMode", "تحديد الموقع",
                if (store.hasLocation) store.place else "غير محدد",
                listOf(
                    Opt("تلقائي (حسب موقعي الحالي)", false) {
                        startActivity(
                            Intent(this, LocationActivity::class.java).putExtra("mode", "auto")
                        )
                    },
                    Opt("يدوي (بحث عن مدينة)", false) {
                        startActivity(Intent(this, LocationActivity::class.java))
                    }
                )
            )

            val hist = store.history()
            choiceRow(
                "history", "مواقعي القديمة",
                if (hist.isEmpty()) "" else n("${hist.size}"),
                hist.map { h ->
                    Opt(h.place, h.place == store.place) {
                        store.setLocation(h.lat, h.lon, h.place, h.country, h.tz)
                    }
                },
                "لا توجد مواقع سابقة بعد."
            )

            col.addView(
                note(
                    "مواقيت الصلاة: " + stamp(store.prayerFetched) +
                        "\nالطقس: " + stamp(store.weatherFetched)
                ),
                bodyLp()
            )
            col.addView(tap("تحديث البيانات الآن", t.accent, t.card) {
                store.forceRefresh()
                render()
            }, bodyLp())
            col.addView(note("بعد الضغط ارجع للشاشة الرئيسية لبدء التحديث (يلزم إنترنت)."), bodyLp())
        }

        // ===================== 4) الصلاة =====================
        section(3, "الصلاة") {
            choiceRow("offsets", "تعديل أوقات الصلاة", "", emptyList(), null) {
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
                    col.addView(line, optLp())
                }
            }

            val mName = methods.firstOrNull { it.first == store.methodChoice }?.second
                ?: methods[0].second
            choiceRow(
                "method", "طريقة حساب المواقيت", mName,
                methods.map { m ->
                    Opt(m.second, m.first == store.methodChoice) {
                        store.methodChoice = m.first
                        store.prayerData = ""
                        store.forceRefresh()
                    }
                }
            )

            col.addView(
                toggleRow("الأذان", settings.adhanEnabled) {
                    settings.adhanEnabled = it
                    render()
                },
                bodyLp()
            )
            if (settings.adhanEnabled) {
                for (i in intArrayOf(0, 2, 3, 4, 5)) {
                    col.addView(
                        toggleRow("أذان " + PrayerManager.names[i], settings.adhanOn(i)) {
                            settings.setAdhan(i, it)
                            render()
                        },
                        optLp()
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
