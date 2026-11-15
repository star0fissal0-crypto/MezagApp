package com.mezagapp.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.net.URLEncoder
import java.time.ZoneId
import java.util.Locale

class LocationActivity : Activity() {
    private lateinit var store: Store
    private lateinit var th: Theme
    private lateinit var results: LinearLayout
    private lateinit var status: TextView
    private lateinit var input: EditText

    private val handler = Handler(Looper.getMainLooper())
    private var gpsListener: LocationListener? = null
    private var gotFix = false
    private var saved = false

    private class Place(
        val title: String,
        val lat: Double,
        val lon: Double,
        val cc: String,
        val tz: String
    )

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun box(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(14).toFloat()
    }

    private fun btn(s: String, onClick: () -> Unit) = TextView(this).apply {
        text = s
        textSize = 18f
        setTextColor(th.accent)
        background = box(th.card)
        setPadding(dp(18), dp(14), dp(18), dp(14))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) }
    }

    private fun hideKeyboard() {
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(input.windowToken, 0)
        } catch (e: Exception) {
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // لا تُظهر لوحة المفاتيح تلقائيًا عند الفتح
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )

        store = Store(this)
        th = ThemeManager.resolve(this, SettingsRepository(this), store)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(th.bgTop)
            fitsSystemWindows = true
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(20), dp(20), dp(20), dp(12))
            isFocusableInTouchMode = true
        }
        root.addView(
            col,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        col.addView(TextView(this).apply {
            text = "اختيار الموقع"
            textSize = 28f
            setTextColor(th.text)
            setPadding(0, 0, 0, dp(12))
        })

        input = EditText(this).apply {
            hint = "ابحث عن مدينة (عربي أو إنجليزي)"
            setTextColor(th.text)
            setHintTextColor(th.sub)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = box(th.card)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    search(text.toString().trim())
                    true
                } else {
                    false
                }
            }
        }
        col.addView(
            input,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        )

        col.addView(btn("🔍 بحث") { search(input.text.toString().trim()) })
        col.addView(btn("📍 استخدام موقعي الحالي") { useGps() })

        status = TextView(this).apply {
            textSize = 14f
            setTextColor(th.sub)
            setPadding(0, dp(4), 0, dp(8))
        }
        col.addView(status)

        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(
            ScrollView(this).apply { addView(results) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        setContentView(root)
        ThemeManager.applySystemBars(this, th)
        col.requestFocus()
        if (intent.getStringExtra("mode") == "auto") handler.post { useGps() }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopGps()
    }

    // ===================== البحث اليدوي =====================

    private fun search(q: String) {
        hideKeyboard()
        if (q.length < 2) {
            status.text = "اكتب حرفين على الأقل"
            return
        }
        status.text = "جارٍ البحث..."
        results.removeAllViews()
        Thread {
            val items = ArrayList<Place>()
            var reached = false
            try {
                val body = Http.get(
                    "https://geocoding-api.open-meteo.com/v1/search?name=" +
                        URLEncoder.encode(q, "UTF-8") + "&count=10&language=ar&format=json"
                )
                reached = body != null
                val arr = if (body != null) JSONObject(body).optJSONArray("results") else null
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val title = listOf(
                            o.optString("name"), o.optString("admin1"), o.optString("country")
                        ).filter { it.isNotEmpty() }.distinct().joinToString("، ")
                        items.add(
                            Place(
                                title,
                                o.getDouble("latitude"),
                                o.getDouble("longitude"),
                                o.optString("country_code"),
                                o.optString("timezone")
                            )
                        )
                    }
                }
            } catch (e: Throwable) {
            }
            val ok = reached
            runOnUiThread { if (!isFinishing) showResults(ok, items) }
        }.start()
    }

    private fun showResults(reached: Boolean, items: List<Place>) {
        results.removeAllViews()
        if (!reached) {
            status.text = "تعذّر الاتصال بالإنترنت"
            return
        }
        if (items.isEmpty()) {
            status.text = "لا توجد نتائج"
            return
        }
        status.text = "اختر مدينتك:"
        for (pl in items) {
            results.addView(btn(pl.title) { save(pl.lat, pl.lon, pl.title, pl.cc, pl.tz) })
        }
    }

    /** يحفظ الموقع فقط ويغلق الشاشة. جلب المواقيت والطقس يتم في الشاشة الرئيسية */
    private fun save(lat: Double, lon: Double, name: String, cc: String, tz: String) {
        if (saved) return
        saved = true
        stopGps()
        store.setLocation(lat, lon, name, cc, tz)
        finish()
    }

    // ===================== الموقع التلقائي =====================

    private fun hasPerm(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun useGps() {
        hideKeyboard()
        if (!hasPerm(Manifest.permission.ACCESS_COARSE_LOCATION) &&
            !hasPerm(Manifest.permission.ACCESS_FINE_LOCATION)
        ) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ), 7
            )
            return
        }
        locate()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7 && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
            locate()
        } else {
            status.text = "لم يُمنح إذن الموقع"
        }
    }

    private fun stopGps() {
        handler.removeCallbacksAndMessages(null)
        val l = gpsListener
        gpsListener = null
        if (l != null) {
            try {
                val lm = getSystemService(LOCATION_SERVICE) as LocationManager
                lm.removeUpdates(l)
            } catch (e: Throwable) {
            }
        }
    }

    @Suppress("MissingPermission", "DEPRECATION")
    private fun locate() {
        gotFix = false
        status.text = "جارٍ تحديد الموقع..."
        try {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager

            // آخر موقع معروف
            var best: Location? = null
            for (p in lm.getProviders(true)) {
                val l = try {
                    lm.getLastKnownLocation(p)
                } catch (e: Throwable) {
                    null
                }
                if (l != null && (best == null || l.time > best.time)) best = l
            }
            if (best != null) {
                resolve(best)
                return
            }

            // لا يوجد موقع محفوظ: اطلب تحديثًا واحدًا بالمزوّد المناسب للإذن الممنوح
            val fine = hasPerm(Manifest.permission.ACCESS_FINE_LOCATION)
            val provider = when {
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                fine && lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                else -> null
            }
            if (provider == null) {
                status.text = "فعّل خدمة الموقع في الهاتف (أو ابحث عن مدينتك يدويًا)"
                return
            }
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    resolve(location)
                }

                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            gpsListener = listener
            lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())

            // مهلة 25 ثانية
            handler.postDelayed({
                if (!gotFix && !isFinishing) {
                    stopGps()
                    status.text = "تعذّر تحديد الموقع. ابحث عن مدينتك يدويًا"
                }
            }, 25_000L)
        } catch (e: Throwable) {
            status.text = "تعذّر تحديد الموقع. ابحث عن مدينتك يدويًا"
        }
    }

    private fun resolve(loc: Location) {
        if (gotFix) return
        gotFix = true
        stopGps()
        status.text = "جارٍ تحديد اسم المدينة..."
        Thread {
            var name = "موقعي الحالي"
            var cc = ""
            try {
                if (Geocoder.isPresent()) {
                    @Suppress("DEPRECATION")
                    val list = Geocoder(this, Locale(PlaceNames.LANG))
                        .getFromLocation(loc.latitude, loc.longitude, 1)
                    val a = list?.firstOrNull()
                    if (a != null) {
                        val n = listOfNotNull(
                            a.locality ?: a.subAdminArea ?: a.adminArea, a.countryName
                        ).distinct().joinToString("، ")
                        if (n.isNotBlank()) name = n
                        cc = a.countryCode ?: ""
                    }
                }
            } catch (e: Throwable) {
            }
            if (name != "موقعي الحالي" && !PlaceNames.hasArabic(name)) {
                PlaceNames.toArabic(name, loc.latitude, loc.longitude)?.let { name = it }
            }
            val finalName = name
            val finalCc = cc
            runOnUiThread {
                if (!isFinishing) {
                    save(loc.latitude, loc.longitude, finalName, finalCc, ZoneId.systemDefault().id)
                }
            }
        }.start()
    }
}
