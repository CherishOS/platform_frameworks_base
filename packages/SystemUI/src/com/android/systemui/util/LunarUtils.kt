/*
 * Copyright (C) 2025 The CherishOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.util

import android.content.Context
import java.time.LocalDate
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

/**
 * Utility class for Vietnamese lunar calendar calculations and formatting.
 * Uses accurate Vietnamese lunar calendar algorithm with timezone GMT+7.
 */
@Singleton
class LunarUtils @Inject constructor(private val context: Context) {

    companion object {
        private const val PI = Math.PI
        
        // Get current system timezone offset in hours
        private fun getCurrentTimeZoneOffset(): Double {
            val calendar = Calendar.getInstance()
            val offsetMs = calendar.timeZone.getOffset(calendar.timeInMillis)
            return offsetMs / (1000.0 * 60.0 * 60.0) // Convert milliseconds to hours
        }
    }

    data class LunarDate(
        val day: Int,
        val month: Int,
        val year: Int,
        val isLeapMonth: Boolean = false
    )

    /**
     * Gets current lunar date formatted for display
     */
    fun getCurrentLunarDateFormatted(locale: String = "vi"): String {
        val lunar = getCurrentLunarDate()
        return when {
            locale.startsWith("vi") || locale.contains("VN") -> formatVietnamese(lunar)
            else -> formatEnglish(lunar)
        }
    }

    /**
     * Gets current lunar date - uses system date (not real current time)
     */
    fun getCurrentLunarDate(): LunarDate {
        // Use system date (which can be changed in Settings)
        val today = Calendar.getInstance()
        val timeZone = getCurrentTimeZoneOffset()
        return convertSolar2Lunar(
            today.get(Calendar.DAY_OF_MONTH),
            today.get(Calendar.MONTH) + 1,
            today.get(Calendar.YEAR),
            timeZone
        )
    }

    /**
     * Gets lunar date for specific date
     */
    fun getLunarDate(calendar: Calendar): LunarDate {
        val timeZone = getCurrentTimeZoneOffset()
        return convertSolar2Lunar(
            calendar.get(Calendar.DAY_OF_MONTH),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.YEAR),
            timeZone
        )
    }

    /**
     * Convert LocalDate to Lunar date
     */
    fun fromLocalDate(date: LocalDate): LunarDate {
        val timeZone = getCurrentTimeZoneOffset()
        return convertSolar2Lunar(date.dayOfMonth, date.monthValue, date.year, timeZone)
    }

    /**
     * Convert solar date to lunar date using Vietnamese lunar calendar algorithm
     */
    private fun convertSolar2Lunar(day: Int, month: Int, year: Int, timeZone: Double = getCurrentTimeZoneOffset()): LunarDate {
        val dayNumber = jdFromDate(day, month, year)
        val k = ((dayNumber - 2415021.076998695) / 29.530588853).toInt()
        var monthStart = getNewMoonDay(k + 1, timeZone)
        if (monthStart > dayNumber) {
            monthStart = getNewMoonDay(k, timeZone)
        }
        
        var a11 = getLunarMonth11(year, timeZone)
        val b11 = a11
        val lunarYear: Int
        
        if (a11 >= monthStart) {
            lunarYear = year
            a11 = getLunarMonth11(year - 1, timeZone)
        } else {
            lunarYear = year + 1
            // b11 is already set
        }
        
        val lunarDay = dayNumber - monthStart + 1
        val diff = ((monthStart - a11) / 29).toInt()
        var lunarMonth = diff + 11
        var leap = false
        
        if (b11 - a11 > 365) {
            val leapMonthDiff = getLeapMonthOffset(a11, timeZone)
            if (diff >= leapMonthDiff) {
                lunarMonth = diff + 10
                if (diff == leapMonthDiff) leap = true
            }
        }
        
        if (lunarMonth > 12) lunarMonth -= 12
        var finalYear = lunarYear
        if (lunarMonth >= 11 && diff < 4) finalYear -= 1
        
        return LunarDate(lunarDay, lunarMonth, finalYear, leap)
    }

    private fun jdFromDate(day: Int, month: Int, year: Int): Int {
        val a = (14 - month) / 12
        val y = year + 4800 - a
        val m = month + 12 * a - 3
        return day + (153 * m + 2) / 5 + 365 * y + y / 4 - y / 100 + y / 400 - 32045
    }

    private fun getNewMoonDay(k: Int, timeZone: Double): Int {
        return floor(jdFromNewMoon(k) + 0.5 + timeZone / 24).toInt()
    }

    private fun jdFromNewMoon(k: Int): Double {
        val t = k / 1236.85
        val t2 = t * t
        val t3 = t2 * t
        val dr = PI / 180
        val jd1 = 2415020.75933 + 29.53058868 * k + 0.0001178 * t2 - 0.000000155 * t3 + 0.00033 * sin((166.56 + 132.87 * t - 0.009173 * t2) * dr)
        val m = 359.2242 + 29.10535608 * k - 0.0000333 * t2 - 0.00000347 * t3
        val mpr = 306.0253 + 385.81691806 * k + 0.0107306 * t2 + 0.00001236 * t3
        val f = 21.2964 + 390.67050646 * k - 0.0016528 * t2 - 0.00000239 * t3
        val c1 = (0.1734 - 0.000393 * t) * sin(m * dr) + 0.0021 * sin(2 * dr * m) - 0.4068 * sin(mpr * dr) + 0.0161 * sin(dr * 2 * mpr) - 0.0004 * sin(dr * 3 * mpr) + 0.0104 * sin(dr * 2 * f) - 0.0051 * sin(dr * (m + mpr)) - 0.0074 * sin(dr * (m - mpr)) + 0.0004 * sin(dr * (2 * f + m)) - 0.0004 * sin(dr * (2 * f - m)) - 0.0006 * sin(dr * (2 * f + mpr)) + 0.0010 * sin(dr * (2 * f - mpr)) + 0.0005 * sin(dr * (2 * mpr + m))
        val deltaT = if (t < -11) 0.001 + 0.000839 * t + 0.0002261 * t2 - 0.00000845 * t3 - 0.000000081 * t * t3 else -0.000278 + 0.000265 * t + 0.000262 * t2
        return jd1 + c1 - deltaT
    }

    private fun getLunarMonth11(year: Int, timeZone: Double): Int {
        val off = jdFromDate(31, 12, year) - 2415021
        val k = (off / 29.530588853).toInt()
        var nm = getNewMoonDay(k, timeZone)
        val sunLong = getSunLongitude(nm, timeZone)
        if (sunLong >= 9) {
            nm = getNewMoonDay(k - 1, timeZone)
        }
        return nm
    }

    private fun getSunLongitude(jdn: Int, timeZone: Double): Int {
        return (sunLongitude(jdn - 0.5 - timeZone / 24) / PI * 6).toInt()
    }

    private fun sunLongitude(jdn: Double): Double {
        val t = (jdn - 2451545.0) / 36525
        val t2 = t * t
        val dr = PI / 180
        val m = 357.52910 + 35999.05030 * t - 0.0001559 * t2 - 0.00000048 * t * t2
        val l0 = 280.46645 + 36000.76983 * t + 0.0003032 * t2
        val dl = (1.914600 - 0.004817 * t - 0.000014 * t2) * sin(dr * m) + (0.019993 - 0.000101 * t) * sin(dr * 2 * m) + 0.000290 * sin(dr * 3 * m)
        var l = l0 + dl
        l *= dr
        return l - PI * 2 * (l / (PI * 2)).toInt()
    }

    private fun getLeapMonthOffset(a11: Int, timeZone: Double): Int {
        val k = ((a11 - 2415021.076998695) / 29.530588853 + 0.5).toInt()
        var last = getSunLongitude(getNewMoonDay(k, timeZone), timeZone)
        var i = 1
        var arc = getSunLongitude(getNewMoonDay(k + i, timeZone), timeZone)
        while (arc != last && i < 14) {
            last = arc
            i++
            arc = getSunLongitude(getNewMoonDay(k + i, timeZone), timeZone)
        }
        return i - 1
    }

    private fun formatVietnamese(lunar: LunarDate): String {
        // Format: "- 1 thg 8 ÂL" - add dash prefix for better readability  
        val monthStr = if (lunar.isLeapMonth) {
            "thg ${lunar.month} (N) ÂL" // N = Nhuận
        } else {
            "thg ${lunar.month} ÂL"
        }
        
        return "- ${lunar.day} $monthStr"
    }

    private fun formatEnglish(lunar: LunarDate): String {
        // Format: "- 5th Aug LC" - add dash prefix for consistency
        val daySuffix = when (lunar.day % 10) {
            1 -> if (lunar.day == 11) "th" else "st"
            2 -> if (lunar.day == 12) "th" else "nd"
            3 -> if (lunar.day == 13) "th" else "rd"
            else -> "th"
        }
        
        val monthNames = arrayOf(
            "Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
        )
        val monthStr = monthNames[lunar.month - 1]
        val leapStr = if (lunar.isLeapMonth) " (L)" else "" // L = Leap
        
        return "- ${lunar.day}$daySuffix $monthStr$leapStr LC"
    }
}