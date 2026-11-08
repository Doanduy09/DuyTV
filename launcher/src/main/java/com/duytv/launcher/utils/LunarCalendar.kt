package com.duytv.launcher.utils

import java.util.Calendar
import kotlin.math.floor
import kotlin.math.sin

object LunarCalendar {

    private const val PI = 3.141592653589793

    private fun jdFromDate(d: Int, m: Int, y: Int): Int {
        val a = (14 - m) / 12
        val y2 = y + 4800 - a
        val m2 = m + 12 * a - 3
        return d + (153 * m2 + 2) / 5 + 365 * y2 + y2 / 4 - y2 / 100 + y2 / 400 - 32045
    }

    private fun getNewMoonDay(k: Int, timeZone: Double): Int {
        val t = k / 1236.85
        val t2 = t * t
        val t3 = t2 * t
        val dr = PI / 180.0
        var jd1 = 2415020.75933 + 29.53058868 * k + 0.0001178 * t2 - 0.000000155 * t3
        jd1 += 0.00033 * sin((166.56 + 132.87 * t - 0.009173 * t2) * dr)
        val m = 359.2242 + 29.10535608 * k - 0.0000333 * t2 - 0.00000347 * t3
        val mpr = 306.0253 + 385.81691806 * k + 0.0107306 * t2 + 0.00001236 * t3
        val f = 21.2964 + 390.67050646 * k - 0.0016528 * t2 - 0.00000239 * t3
        var c1 = (0.1734 - 0.000393 * t) * sin(m * dr) + 0.0021 * sin(2.0 * dr * m)
        c1 -= 0.4068 * sin(mpr * dr) - 0.0161 * sin(2.0 * dr * mpr)
        c1 -= 0.0004 * sin(3.0 * dr * mpr)
        c1 += 0.0104 * sin(2.0 * dr * f) - 0.0051 * sin((m + mpr) * dr)
        c1 -= 0.0074 * sin((m - mpr) * dr) + 0.0004 * sin((2.0 * f + m) * dr)
        c1 -= 0.0004 * sin((2.0 * f - m) * dr) - 0.0006 * sin((2.0 * f + mpr) * dr)
        c1 += 0.0010 * sin((2.0 * f - mpr) * dr) + 0.0005 * sin((2.0 * mpr + m) * dr)
        val deltat = if (t < -11) {
            0.001 + 0.000839 * t + 0.0002261 * t2 - 0.00000845 * t3 - 0.000000081 * t * t3
        } else {
            -0.000078 + 0.000014 * t
        }
        val jdNew = jd1 + c1 - deltat
        return floor(jdNew + 0.5 + timeZone / 24.0).toInt()
    }

    private fun getSunLongitude(jdn: Int, timeZone: Double): Int {
        val t = (jdn - 2451545.0 + 0.5 - timeZone / 24.0) / 36525.0
        val t2 = t * t
        val dr = PI / 180.0
        val l0 = 280.46645 + 36000.76983 * t + 0.0003032 * t2
        val m = 357.52910 + 35999.05030 * t - 0.0001559 * t2 - 0.00000048 * t * t2
        var c = (1.914600 - 0.004817 * t - 0.000014 * t2) * sin(m * dr)
        c += (0.019993 - 0.000101 * t) * sin(2.0 * m * dr) + 0.000290 * sin(3.0 * m * dr)
        var theta = l0 + c
        theta -= 360.0 * floor(theta / 360.0)
        return floor(theta / 30.0).toInt()
    }

    private fun getLunarMonth11(yy: Int, timeZone: Double): Int {
        val off = jdFromDate(31, 12, yy) - 2415021
        val k = floor(off / 29.530588853).toInt()
        var nm = getNewMoonDay(k, timeZone)
        val sunLong = getSunLongitude(nm, timeZone)
        if (sunLong >= 9) {
            nm = getNewMoonDay(k - 1, timeZone)
        }
        return nm
    }

    fun convertSolar2Lunar(dd: Int, mm: Int, yy: Int, timeZone: Double = 7.0): Pair<Int, Int> {
        val dayNumber = jdFromDate(dd, mm, yy)
        val k = floor((dayNumber - 2415021.0769986) / 29.530588853).toInt()
        var monthStart = getNewMoonDay(k + 1, timeZone)
        if (monthStart > dayNumber) {
            monthStart = getNewMoonDay(k, timeZone)
        }
        var a11 = getLunarMonth11(yy, timeZone)
        if (a11 >= monthStart) {
            a11 = getLunarMonth11(yy - 1, timeZone)
        }
        val lunarDay = dayNumber - monthStart + 1
        val diff = floor((monthStart - a11) / 29.0).toInt()
        var lunarMonth = diff + 11
        if (lunarMonth > 12) {
            lunarMonth -= 12
        }
        return Pair(lunarDay, lunarMonth)
    }

    fun getTodayLunarString(): String {
        val cal = Calendar.getInstance()
        val dd = cal.get(Calendar.DAY_OF_MONTH)
        val mm = cal.get(Calendar.MONTH) + 1
        val yy = cal.get(Calendar.YEAR)
        val (lDay, lMonth) = convertSolar2Lunar(dd, mm, yy)
        return "Âm lịch · Ngày $lDay Tháng $lMonth"
    }

    fun getTodaySolarString(): String {
        val cal = Calendar.getInstance()
        val dayOfWeek = when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> "THỨ HAI"
            Calendar.TUESDAY -> "THỨ BA"
            Calendar.WEDNESDAY -> "THỨ TƯ"
            Calendar.THURSDAY -> "THỨ NĂM"
            Calendar.FRIDAY -> "THỨ SÁU"
            Calendar.SATURDAY -> "THỨ BẢY"
            Calendar.SUNDAY -> "CHỦ NHẬT"
            else -> "HÔM NAY"
        }
        val dd = cal.get(Calendar.DAY_OF_MONTH)
        val mm = cal.get(Calendar.MONTH) + 1
        return "$dayOfWeek, NGÀY $dd THÁNG $mm"
    }

    fun getGreetingString(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 4..11 -> "Chào buổi sáng"
            in 12..17 -> "Chào buổi chiều"
            in 18..23 -> "Chào buổi tối"
            else -> "Chào đêm muộn"
        }
    }
}
