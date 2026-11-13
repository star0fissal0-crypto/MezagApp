package com.mezagapp.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
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

    private class Place(
        val title: String,
        val lat: Double,
        val lon: Double,
        val cc: String,
        val tz: String
    )

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun btn(s: String, onClick: () -> Unit) = TextView(this).apply {
        text = s
        textSize = 18f
        setTextColor(th.accent)
        background = GradientDrawable().apply {
            setColor(th.card)
            cornerRadius = dp(14).toFloat()
        }
        setPadding(dp(18), dp(14), dp(18), dp(14))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        th = ThemeManager.get(SettingsRepository(this).themeIndex)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(20), dp(28), dp(20), dp(40))
        }

        col.addView(TextView(this).apply {
            text = "اختيار الموقع"
            textSize = 28f
            setTextColor(th.text)
            setPadding(0, 0, 0, dp(12))
        })

        val input = EditText(this).apply {
            hint = "ابحث عن مدينة (عربي أو إنجليزي)"
            setTextColor(th.text)
            setHintTextColor(th.sub)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        col.addView(input)

        col.addView(btn("🔍 بحث") { search(input.text.toString().trim()) })
        col.addView(btn("📍 استخدام موقعي الحالي") { useGps() })

        status = TextView(this).apply {
            textSize = 14f
            setTextColor(th.sub)
            setPadding(0, dp(8), 0, dp(8))
        }
        col.addView(status)

        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(results)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(th.bg)
            addView(col)
        })
    }

    private fun search(q: String) {
        if (q.length < 2) {
            status.text = "اكتب حرفين على الأقل"
            return
        }
        status.text = "جارٍ البحث..."
        results.removeAllViews()
        Thread {
            val body = Http.get(
                "https://geocoding-api.open-meteo.com/v1/search?name=" +
                    URLEncoder.encode(q, "UTF-8") + "&count=10&language=ar&format=json"
            )
            val items = ArrayList<Place>()
            try {
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
            } catch (e: Exception) {
            }
            val reached = body != null
            runOnUiThread { showResults(reached, items) }
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

    private fun save(lat: Double, lon: Double, name: String, cc: String, tz: String) {
        store.setLocation(lat, lon, name, cc, tz)
        val app = applicationContext
        Thread {
            val s = Store(app)
            PrayerManager.fetch(s)
            WeatherManager.fetch(s)
            WidgetUpdateManager.updateAll(app, false)
        }.start()
        finish()
    }

    private fun useGps() {
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
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

    @Suppress("MissingPermission")
    private fun locate() {
        status.text = "جارٍ تحديد الموقع..."
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        var best: Location? = null
        for (p in lm.getProviders(true)) {
            val l = try {
                lm.getLastKnownLocation(p)
            } catch (e: Exception) {
                null
            }
            if (l != null && (best == null || l.time > best.time)) best = l
        }
        if (best != null) {
            resolve(best)
            return
        }
        val provider = when {
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> null
        }
        if (provider == null) {
            status.text = "فعّل خدمة الموقع في الهاتف"
            return
        }
        @Suppress("DEPRECATION")
        lm.requestSingleUpdate(provider, object : LocationListener {
            override fun onLocationChanged(location: Location) {
                resolve(location)
            }

            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }, mainLooper)
    }

    private fun resolve(loc: Location) {
        Thread {
            var name = "موقعي الحالي"
            var cc = ""
            try {
                @Suppress("DEPRECATION")
                val list = Geocoder(this, Locale("ar")).getFromLocation(loc.latitude, loc.longitude, 1)
                val a = list?.firstOrNull()
                if (a != null) {
                    val n = listOfNotNull(
                        a.locality ?: a.subAdminArea ?: a.adminArea, a.countryName
                    ).distinct().joinToString("، ")
                    if (n.isNotBlank()) name = n
                    cc = a.countryCode ?: ""
                }
            } catch (e: Exception) {
            }
            val finalName = name
            val finalCc = cc
            runOnUiThread {
                save(loc.latitude, loc.longitude, finalName, finalCc, ZoneId.systemDefault().id)
            }
        }.start()
    }
}
