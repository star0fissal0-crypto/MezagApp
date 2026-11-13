package com.mezagapp.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.roundToInt

object Http {
    /** يجب استدعاؤها من خيط خلفي فقط */
    fun get(url: String): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        c.requestMethod = "GET"
        if (c.responseCode != 200) {
            c.disconnect()
            null
        } else {
            val t = c.inputStream.bufferedReader().use { it.readText() }
            c.disconnect()
            t
        }
    } catch (e: Exception) {
        null
    }
}

object PrayerManager {
    /** 0 الفجر، 1 الشروق، 2 الظهر، 3 العصر، 4 المغرب، 5 العشاء */
    val names = listOf("الفجر", "الشروق", "الظهر", "العصر", "المغرب", "العشاء")

    sealed class State {
        class Now(val prayer: String) : State()
        class Next(val index: Int, val minutes: Long) : State()
    }

    class Result(
        val times: List<ZonedDateTime>,
        val state: State,
        val nextIndex: Int,
        val stale: Boolean
    )

    fun zone(store: Store): ZoneId = try {
        if (store.tz.isNotEmpty()) ZoneId.of(store.tz) else ZoneId.systemDefault()
    } catch (e: Exception) {
        ZoneId.systemDefault()
    }

    fun displayName(i: Int, day: LocalDate): String =
        if (i == 2 && day.dayOfWeek == DayOfWeek.FRIDAY) "الجمعة" else names[i]

    private fun method(store: Store): Int {
        if (store.methodChoice != 0) return store.methodChoice
        return when (store.country.uppercase()) {
            "TR" -> 13
            "SA" -> 4
            "EG" -> 5
            "PK", "IN", "BD", "AF" -> 1
            "KW" -> 9
            "QA" -> 10
            "AE", "BH", "OM" -> 8
            "US", "CA" -> 2
            else -> 3
        }
    }

    /** يجلب الشهر الحالي والقادم ويحفظهما سطرًا لكل يوم: yyyy-MM-dd ثم 6 أوقات */
    fun fetch(store: Store) {
        val zone = zone(store)
        val today = LocalDate.now(zone)
        val sb = StringBuilder()
        var ok = false
        val keys = listOf("Fajr", "Sunrise", "Dhuhr", "Asr", "Maghrib", "Isha")
        for ((idx, d) in listOf(today, today.plusMonths(1)).withIndex()) {
            val url = "https://api.aladhan.com/v1/calendar/${d.year}/${d.monthValue}" +
                "?latitude=${store.lat}&longitude=${store.lon}" +
                "&method=${method(store)}&timezonestring=${zone.id}"
            val body = Http.get(url) ?: continue
            try {
                val arr = JSONObject(body).getJSONArray("data")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val g = o.getJSONObject("date").getJSONObject("gregorian")
                        .getString("date").split("-")
                    sb.append("${g[2]}-${g[1]}-${g[0]}")
                    val t = o.getJSONObject("timings")
                    for (k in keys) sb.append(' ').append(t.getString(k).substring(0, 5))
                    sb.append('\n')
                }
                if (idx == 0) ok = true
            } catch (e: Exception) {
            }
        }
        if (ok) {
            store.prayerData = sb.toString()
            store.prayerFetched = System.currentTimeMillis()
        }
    }

    fun compute(store: Store, now: ZonedDateTime): Result? {
        val data = store.prayerData
        if (data.isEmpty()) return null
        val zone = now.zone
        val map = HashMap<LocalDate, List<ZonedDateTime>>()
        try {
            for (line in data.lines()) {
                val p = line.trim().split(" ")
                if (p.size < 7) continue
                val day = LocalDate.parse(p[0])
                val list = ArrayList<ZonedDateTime>()
                for (i in 0..5) {
                    val hm = p[i + 1].split(":")
                    val mins = hm[0].toInt() * 60 + hm[1].toInt() + store.offset(i)
                    list.add(day.atStartOfDay(zone).plusMinutes(mins.toLong()))
                }
                map[day] = list
            }
        } catch (e: Exception) {
            return null
        }

        val today = now.toLocalDate()
        val times = map[today] ?: return null

        var next = -1
        for (i in 0..5) {
            if (times[i].isAfter(now)) {
                next = i
                break
            }
        }

        // نافذة "حان الآن موعد الصلاة": 15 دقيقة بعد دخول الوقت (ما عدا الشروق)
        var nowIdx = -1
        for (i in intArrayOf(0, 2, 3, 4, 5)) {
            if (!now.isBefore(times[i]) && now.isBefore(times[i].plusMinutes(15))) {
                nowIdx = i
            }
        }

        val state: State
        val nextIdx: Int
        if (nowIdx >= 0) {
            state = State.Now(displayName(nowIdx, today))
            nextIdx = if (next >= 0) next else 0
        } else if (next >= 0) {
            val secs = Duration.between(now, times[next]).seconds
            state = State.Next(next, (secs + 59) / 60)
            nextIdx = next
        } else {
            val tm = map[today.plusDays(1)]
            val mins = if (tm != null) (Duration.between(now, tm[0]).seconds + 59) / 60 else 0L
            state = State.Next(0, mins)
            nextIdx = 0
        }

        val stale = System.currentTimeMillis() - store.prayerFetched > 36L * 3600_000L
        return Result(times, state, nextIdx, stale)
    }

    fun text(st: State): String = when (st) {
        is State.Now -> "حان الآن موعد صلاة ${st.prayer}"
        is State.Next -> {
            val h = st.minutes / 60
            val m = st.minutes % 60
            val dur = if (h > 0) "$h ساعة و $m دقيقة" else "$m دقيقة"
            "متبقٍ على ${names[st.index]}: $dur"
        }
    }
}

object WeatherManager {
    class Day(val date: LocalDate, val max: Int, val code: Int)
    class Slot(val label: String, val temp: Int, val code: Int)
    class Cond(val icon: String, val label: String)

    fun cond(code: Int): Cond = when (code) {
        0 -> Cond("☀️", "صافٍ")
        1 -> Cond("🌤️", "غالبًا صافٍ")
        2 -> Cond("⛅", "غائم جزئيًا")
        3 -> Cond("☁️", "غائم")
        45, 48 -> Cond("🌫️", "ضباب")
        in 51..57 -> Cond("🌦️", "رذاذ")
        in 61..67 -> Cond("🌧️", "مطر")
        in 71..77 -> Cond("❄️", "ثلج")
        in 80..82 -> Cond("🌧️", "زخات مطر")
        85, 86 -> Cond("🌨️", "زخات ثلج")
        in 95..99 -> Cond("⛈️", "عاصفة رعدية")
        else -> Cond("🌡️", "—")
    }

    fun fetch(store: Store) {
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=${store.lat}&longitude=${store.lon}" +
            "&hourly=temperature_2m,weather_code" +
            "&daily=weather_code,temperature_2m_max" +
            "&timezone=auto&forecast_days=4"
        val body = Http.get(url) ?: return
        if (!body.contains("\"daily\"")) return
        store.weatherJson = body
        store.weatherFetched = System.currentTimeMillis()
    }

    /** أول ثلاثة أيام ابتداءً من اليوم */
    fun days(store: Store, today: LocalDate): List<Day> {
        val out = ArrayList<Day>()
        try {
            val d = JSONObject(store.weatherJson).getJSONObject("daily")
            val time = d.getJSONArray("time")
            val mx = d.getJSONArray("temperature_2m_max")
            val code = d.getJSONArray("weather_code")
            for (i in 0 until time.length()) {
                val date = LocalDate.parse(time.getString(i))
                if (date.isBefore(today)) continue
                out.add(Day(date, mx.getDouble(i).roundToInt(), code.getInt(i)))
                if (out.size == 3) break
            }
        } catch (e: Exception) {
        }
        return out
    }

    private fun hourLabel(h: Int): String {
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "$h12 ${if (h < 12) "ص" else "م"}"
    }

    /** خمس فترات: الآن ثم كل 4 ساعات */
    fun slots(store: Store, now: LocalDateTime): List<Slot> {
        val out = ArrayList<Slot>()
        try {
            val h = JSONObject(store.weatherJson).getJSONObject("hourly")
            val time = h.getJSONArray("time")
            val temp = h.getJSONArray("temperature_2m")
            val code = h.getJSONArray("weather_code")
            val start = now.withMinute(0).withSecond(0).withNano(0)
            var first = -1
            for (i in 0 until time.length()) {
                if (!LocalDateTime.parse(time.getString(i)).isBefore(start)) {
                    first = i
                    break
                }
            }
            if (first < 0) return out
            for (k in 0..4) {
                val i = first + k * 4
                if (i >= time.length()) break
                val t = LocalDateTime.parse(time.getString(i))
                out.add(
                    Slot(
                        if (k == 0) "الآن" else hourLabel(t.hour),
                        temp.getDouble(i).roundToInt(),
                        code.getInt(i)
                    )
                )
            }
        } catch (e: Exception) {
        }
        return out
    }
}
