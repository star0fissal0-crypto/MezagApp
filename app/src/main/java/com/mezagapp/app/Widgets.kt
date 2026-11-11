package com.mezagapp.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.widget.RemoteViews
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.util.Locale

class MainWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray
    ) {
        WidgetUpdateManager.updateAll(context, false)
        WidgetUpdateManager.schedule(context)
    }

    override fun onEnabled(context: Context) {
        WidgetUpdateManager.schedule(context)
    }

    override fun onDisabled(context: Context) {
        WidgetUpdateManager.cancel(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val a = intent.action
        if (a == WidgetUpdateManager.ACTION_TICK ||
            a == Intent.ACTION_TIME_CHANGED ||
            a == Intent.ACTION_TIMEZONE_CHANGED
        ) {
            val pending = goAsync()
            Thread {
                try {
                    WidgetUpdateManager.updateAll(context, true)
                } catch (e: Exception) {
                } finally {
                    WidgetUpdateManager.schedule(context)
                    pending.finish()
                }
            }.start()
        }
    }
}

object WidgetUpdateManager {
    const val ACTION_TICK = "com.mezagapp.app.WIDGET_TICK"

    private fun ids(ctx: Context): IntArray =
        AppWidgetManager.getInstance(ctx)
            .getAppWidgetIds(ComponentName(ctx, MainWidgetProvider::class.java))

    private fun tickIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 0,
        Intent(ctx, MainWidgetProvider::class.java).setAction(ACTION_TICK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** يجدول التحديث التالي عند بداية الدقيقة القادمة (تقريبيًا حسب توفير الطاقة) */
    fun schedule(context: Context) {
        val ctx = context.applicationContext
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (ids(ctx).isEmpty()) {
            am.cancel(tickIntent(ctx))
            return
        }
        val next = (System.currentTimeMillis() / 60_000L + 1) * 60_000L + 500L
        am.setAndAllowWhileIdle(AlarmManager.RTC, next, tickIntent(ctx))
    }

    fun cancel(context: Context) {
        val ctx = context.applicationContext
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(tickIntent(ctx))
    }

    private fun fetchIfNeeded(store: Store) {
        if (!store.hasLocation) return
        val now = System.currentTimeMillis()
        val needP = store.prayerData.isEmpty() || now - store.prayerFetched > 12L * 3600_000L
        if (needP && now - store.prayerAttempt > 5L * 60_000L) {
            store.prayerAttempt = now
            PrayerManager.fetch(store)
        }
        val needW = store.weatherJson.isEmpty() || now - store.weatherFetched > 55L * 60_000L
        if (needW && now - store.weatherAttempt > 5L * 60_000L) {
            store.weatherAttempt = now
            WeatherManager.fetch(store)
        }
    }

    fun updateAll(context: Context, fetch: Boolean) {
        val ctx = context.applicationContext
        val ids = ids(ctx)
        if (ids.isEmpty()) return
        val mgr = AppWidgetManager.getInstance(ctx)
        mgr.updateAppWidget(ids, MainWidget.build(ctx))
        if (fetch) {
            val store = Store(ctx)
            val before = store.prayerFetched + store.weatherFetched
            fetchIfNeeded(store)
            if (store.prayerFetched + store.weatherFetched != before) {
                mgr.updateAppWidget(ids, MainWidget.build(ctx))
            }
        }
    }
}

/** إعداد الأرقام: عربية (١٢٣) أو إنجليزية (123) */
object WidgetPrefs {
    private fun p(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("mezag_data", Context.MODE_PRIVATE)

    fun arabicDigits(ctx: Context): Boolean = p(ctx).getBoolean("arabicDigits", true)

    fun setArabicDigits(ctx: Context, v: Boolean) =
        p(ctx).edit().putBoolean("arabicDigits", v).apply()
}

object MainWidget {
    fun build(ctx: Context): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_main)
        v.setImageViewBitmap(R.id.w_img, WidgetRenderer.render(ctx))
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        v.setOnClickPendingIntent(R.id.w_root, open)
        return v
    }
}

/**
 * يرسم الويدجت كله كصورة واحدة فوق الصورة الفارغة.
 * كل الإحداثيات بمقياس الصورة الأصلية 1220 × 2010.
 * الملفات المطلوبة في assets: widget_blank.jpg و font.otf و p0.png إلى p5.png
 * رموز الطقس: إيموجي النظام (تُستبدل لاحقًا بأيقونات مصنّعة).
 */
object WidgetRenderer {
    private const val W = 1220f
    private const val H = 2010f
    private const val S = 0.72f

    private val GOLD = Color.parseColor("#E8CC82")
    private val CREAM = Color.parseColor("#F6EEDC")
    private val DIM = Color.parseColor("#C9B47E")
    private val WHITE = Color.WHITE

    private var bg: Bitmap? = null
    private var font: Typeface? = null
    private val icons = HashMap<String, Bitmap?>()
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private fun typeface(ctx: Context): Typeface {
        font?.let { return it }
        val tf = try {
            Typeface.createFromAsset(ctx.assets, "font.otf")
        } catch (e: Exception) {
            try {
                Typeface.createFromAsset(ctx.assets, "font.ttf")
            } catch (e2: Exception) {
                Typeface.DEFAULT
            }
        }
        font = tf
        return tf
    }

    private fun decode(ctx: Context, name: String, targetPx: Int): Bitmap? = try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.assets.open(name).use { BitmapFactory.decodeStream(it, null, opts) }
        var sample = 1
        while (opts.outWidth / (sample * 2) >= targetPx) sample *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
        ctx.assets.open(name).use { BitmapFactory.decodeStream(it, null, o2) }
    } catch (e: Exception) {
        null
    }

    private fun icon(ctx: Context, name: String, targetPx: Int): Bitmap? {
        if (icons.containsKey(name)) return icons[name]
        val b = decode(ctx, name, targetPx)
        icons[name] = b
        return b
    }

    private fun background(ctx: Context): Bitmap? {
        bg?.let { return it }
        val tw = (W * S).toInt()
        val th = (H * S).toInt()
        val raw = decode(ctx, "widget_blank.jpg", tw)
            ?: decode(ctx, "widget_blank.png", tw)
            ?: return null
        val scaled = Bitmap.createScaledBitmap(raw, tw, th, true)
        if (scaled !== raw) raw.recycle()
        bg = scaled
        return scaled
    }

    private fun paint(color: Int, tf: Typeface) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        typeface = tf
        textAlign = Paint.Align.CENTER
    }

    /** يكتب النص داخل المستطيل ويصغّره تلقائيًا إن زاد عن عرضه */
    private fun fit(c: Canvas, text: String, box: RectF, p: Paint, size: Float) {
        if (text.isEmpty()) return
        p.textSize = size
        val w = p.measureText(text)
        if (w > box.width()) p.textSize = size * box.width() / w
        val fm = p.fontMetrics
        c.drawText(text, box.centerX(), box.centerY() - (fm.ascent + fm.descent) / 2f, p)
    }

    private fun drawBmp(c: Canvas, b: Bitmap, box: RectF) {
        val s = minOf(box.width() / b.width, box.height() / b.height)
        val w = b.width * s
        val h = b.height * s
        val dst = RectF(
            box.centerX() - w / 2f, box.centerY() - h / 2f,
            box.centerX() + w / 2f, box.centerY() + h / 2f
        )
        c.drawBitmap(b, null, dst, bmpPaint)
    }

    /** رمز الطقس: إيموجي النظام التلقائي (خط النظام الافتراضي لا خط الويدجت) */
    private fun drawEmoji(c: Canvas, emoji: String, box: RectF) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = box.height() * 0.78f
        }
        val fm = p.fontMetrics
        c.drawText(emoji, box.centerX(), box.centerY() - (fm.ascent + fm.descent) / 2f, p)
    }

    private fun toArabic(s: String): String {
        val sb = StringBuilder()
        for (ch in s) sb.append(if (ch in '0'..'9') '٠' + (ch - '0') else ch)
        return sb.toString()
    }

    private fun dashYear(s: String): String {
        val i = s.lastIndexOf(' ')
        return if (i > 0) s.substring(0, i) + " - " + s.substring(i + 1) else s
    }

    fun render(ctx: Context): Bitmap {
        val settings = SettingsRepository(ctx)
        val store = Store(ctx)
        val tf = typeface(ctx)
        val arabic = WidgetPrefs.arabicDigits(ctx)
        fun n(s: String): String = if (arabic) toArabic(s) else s

        val bw = (W * S).toInt()
        val bh = (H * S).toInt()
        val out = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)

        // الخلفية بزوايا مدورة
        c.save()
        val clip = Path()
        clip.addRoundRect(RectF(0f, 0f, bw.toFloat(), bh.toFloat()), 48f, 48f, Path.Direction.CW)
        c.clipPath(clip)
        val back = background(ctx)
        if (back != null) c.drawBitmap(back, 0f, 0f, bmpPaint)
        else c.drawColor(Color.parseColor("#0B1B33"))
        c.restore()

        c.scale(S, S)

        val local = LocalDateTime.now()
        val date = local.toLocalDate()
        val now = ZonedDateTime.now(PrayerManager.zone(store))
        val today = now.toLocalDate()

        // ===== الساعة =====
        val cp = ClockManager.parts(local, settings.use24h)
        fit(c, n("${cp.hour}:${cp.minute}"), RectF(172f, 150f, 308f, 240f), paint(GOLD, tf), 82f)
        if (cp.period.isNotEmpty()) {
            fit(c, cp.period, RectF(172f, 236f, 308f, 262f), paint(DIM, tf), 24f)
        }

        // ===== التاريخان =====
        val hj = ClockManager.hijri(date, settings.hijriAdjust).removeSuffix(" هـ")
        fit(c, n(dashYear(hj)), RectF(345f, 143f, 548f, 191f), paint(DIM, tf), 34f)
        val gr = ClockManager.gregorian(date, settings.levantMonths)
        fit(c, n(dashYear(gr)), RectF(345f, 206f, 548f, 252f), paint(DIM, tf), 34f)

        // ===== اسم اليوم =====
        fit(c, ClockManager.dayName(date), RectF(745f, 195f, 1150f, 380f), paint(GOLD, tf), 175f)

        // ===== الصلاة =====
        val r = if (store.hasLocation) PrayerManager.compute(store, now) else null
        val cardX = arrayOf(
            floatArrayOf(1012f, 1192f), floatArrayOf(820f, 1000f), floatArrayOf(628f, 810f),
            floatArrayOf(432f, 615f), floatArrayOf(237f, 420f), floatArrayOf(33f, 222f)
        )
        val st = r?.state
        if (st is PrayerManager.State.Now) {
            fit(c, "صلاة ${st.prayer}", RectF(60f, 700f, 1160f, 1000f), paint(GOLD, tf), 190f)
        } else if (r != null) {
            for (i in 0..5) {
                val x0 = cardX[i][0]
                val x1 = cardX[i][1]
                val cx = (x0 + x1) / 2f
                val b = icon(ctx, "p$i.png", (104 * S).toInt())
                if (b != null) drawBmp(c, b, RectF(cx - 52f, 689f, cx + 52f, 793f))
                fit(
                    c, PrayerManager.displayName(i, today),
                    RectF(x0 + 6f, 800f, x1 - 6f, 858f), paint(CREAM, tf), 46f
                )
                val t = r.times[i]
                val col = if (i == r.nextIndex) WHITE else GOLD
                fit(
                    c, n(String.format(Locale.US, "%02d", t.hour)),
                    RectF(x0, 866f, x1, 948f), paint(col, tf), 92f
                )
                fit(
                    c, n(String.format(Locale.US, "%02d", t.minute)),
                    RectF(x0, 944f, x1, 1002f), paint(col, tf), 64f
                )
            }
        }

        // ===== شريط العد التنازلي =====
        val barText = when {
            !store.hasLocation -> "اختر مدينتك من التطبيق"
            r == null -> "جارٍ جلب المواقيت..."
            st is PrayerManager.State.Now -> "حان الآن موعد الصلاة"
            st != null -> PrayerManager.text(st)
            else -> ""
        }
        fit(c, n(barText), RectF(200f, 1086f, 1020f, 1174f), paint(CREAM, tf), 54f)

        // ===== اسم المدينة =====
        val place = if (store.hasLocation) store.place else "اختر المدينة"
        fit(c, place, RectF(340f, 1200f, 985f, 1272f), paint(GOLD, tf), 56f)

        // ===== توقعات ثلاثة أيام =====
        val days = WeatherManager.days(store, today)
        val wx = arrayOf(
            floatArrayOf(820f, 1192f), floatArrayOf(425f, 803f), floatArrayOf(28f, 410f)
        )
        for (i in 0..2) {
            val d = days.getOrNull(i) ?: continue
            val x0 = wx[i][0]
            val x1 = wx[i][1]
            val w = x1 - x0
            val name = when (d.date) {
                today -> "اليوم"
                today.plusDays(1) -> "غدًا"
                else -> ClockManager.dayName(d.date)
            }
            val dateStr = ClockManager.gregorian(d.date, settings.levantMonths).substringBeforeLast(' ')
            fit(
                c, "$name ${n(dateStr)}",
                RectF(x0 + 10f, 1322f, x1 - 10f, 1370f), paint(CREAM, tf), 38f
            )
            fit(
                c, n("${d.max}°"),
                RectF(x0 + w * 0.05f, 1378f, x0 + w * 0.52f, 1490f), paint(WHITE, tf), 130f
            )
            drawEmoji(
                c, WeatherManager.cond(d.code).icon,
                RectF(x0 + w * 0.54f, 1374f, x0 + w * 0.94f, 1496f)
            )
        }

        // ===== فترات اليوم (5 فترات كل 4 ساعات) =====
        val slots = WeatherManager.slots(store, now.toLocalDateTime())
        val sx = floatArrayOf(1085f, 862f, 610f, 360f, 133f)
        for (i in 0..4) {
            val s = slots.getOrNull(i) ?: continue
            val x = sx[i]
            fit(c, n(s.label), RectF(x - 105f, 1546f, x + 105f, 1590f), paint(DIM, tf), 34f)
            fit(c, n("${s.temp}°"), RectF(x - 90f, 1638f, x + 90f, 1696f), paint(CREAM, tf), 44f)
            drawEmoji(
                c, WeatherManager.cond(s.code).icon,
                RectF(x - 48f, 1700f, x + 48f, 1770f)
            )
        }

        // ===== تنبيه البيانات القديمة =====
        val oldW = days.isNotEmpty() &&
            System.currentTimeMillis() - store.weatherFetched > 3L * 3600_000L
        if (store.hasLocation && (oldW || r?.stale == true)) {
            fit(c, "بعض البيانات قديمة، تحقق من الاتصال", RectF(260f, 1930f, 960f, 1990f), paint(DIM, tf), 32f)
        }

        return out
    }
}
