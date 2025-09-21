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
import com.android.systemui.res.R
import java.util.Calendar
import java.util.GregorianCalendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Utility class for lunar calendar calculations and formatting.
 * Provides conversion from Gregorian to Vietnamese lunar calendar.
 */
@Singleton
class LunarUtils @Inject constructor(private val context: Context) {

    companion object {
        // Base year for lunar calendar calculations (year 1900 in Gregorian)
        private const val BASE_YEAR = 1900
        
        // Reference point for lunar calculations (January 31, 1900 was Tet)
        private const val REFERENCE_JULIAN = 2415021
        
        // Average lunar month in days
        private const val LUNAR_MONTH_DAYS = 29.530588853
    }

    /**
     * Converts a Gregorian date to lunar date
     */
    fun convertToLunar(gregorianCalendar: Calendar): LunarDate {
        val year = gregorianCalendar.get(Calendar.YEAR)
        val month = gregorianCalendar.get(Calendar.MONTH) + 1 // Calendar.MONTH is 0-based
        val day = gregorianCalendar.get(Calendar.DAY_OF_MONTH)
        
        val julianDay = toJulianDay(year, month, day)
        
        // Calculate lunar date based on Julian day
        val lunarDate = calculateLunarDate(julianDay, year)
        
        return lunarDate
    }

    /**
     * Formats lunar date for display
     */
    fun formatLunarDate(lunarDate: LunarDate, locale: String = "vi"): String {
        return when (locale.lowercase()) {
            "vi", "vi-vn" -> formatVietnamese(lunarDate)
            else -> formatEnglish(lunarDate)
        }
    }

    /**
     * Gets current lunar date
     */
    fun getCurrentLunarDate(): LunarDate {
        return convertToLunar(Calendar.getInstance())
    }

    /**
     * Formats current lunar date for display
     */
    fun getCurrentLunarDateFormatted(locale: String = "vi"): String {
        val lunarDate = getCurrentLunarDate()
        return formatLunarDate(lunarDate, locale)
    }

    private fun formatVietnamese(lunarDate: LunarDate): String {
        // Format: "30/07 ÂL" - compact Vietnamese format
        val dayStr = String.format("%02d", lunarDate.day)
        val monthStr = String.format("%02d", lunarDate.month)
        
        return "$dayStr/$monthStr ÂL"
    }

    private fun formatEnglish(lunarDate: LunarDate): String {
        // Format: "30/07 LC" - compact English format (LC = Lunar Calendar)
        val dayStr = String.format("%02d", lunarDate.day)
        val monthStr = String.format("%02d", lunarDate.month)
        
        return "$dayStr/$monthStr LC"
    }

    private fun toJulianDay(year: Int, month: Int, day: Int): Int {
        val a = (14 - month) / 12
        val y = year - a
        val m = month + 12 * a - 3
        
        return day + (153 * m + 2) / 5 + 365 * y + y / 4 - y / 100 + y / 400 + 1721119
    }

    private fun calculateLunarDate(julianDay: Int, gregorianYear: Int): LunarDate {
        // Simple approximation for lunar calendar conversion
        // This is a simplified version - a full implementation would require
        // more complex astronomical calculations
        
        val daysSinceReference = julianDay - REFERENCE_JULIAN
        val lunarMonths = (daysSinceReference / LUNAR_MONTH_DAYS).toInt()
        
        var lunarYear = BASE_YEAR + lunarMonths / 12
        var lunarMonth = (lunarMonths % 12) + 1
        
        if (lunarMonth <= 0) {
            lunarMonth += 12
            lunarYear--
        }
        
        val daysIntoMonth = (daysSinceReference - lunarMonths * LUNAR_MONTH_DAYS).toInt()
        var lunarDay = daysIntoMonth + 1
        
        // Adjust for actual lunar calendar
        if (lunarDay <= 0) {
            lunarDay = 29 + lunarDay
            lunarMonth--
            if (lunarMonth <= 0) {
                lunarMonth = 12
                lunarYear--
            }
        } else if (lunarDay > 30) {
            lunarDay = lunarDay - 30
            lunarMonth++
            if (lunarMonth > 12) {
                lunarMonth = 1
                lunarYear++
            }
        }
        
        // Align with current Gregorian year approximately
        lunarYear = gregorianYear
        
        return LunarDate(lunarYear, lunarMonth, lunarDay)
    }

    /**
     * Data class representing a lunar date
     */
    data class LunarDate(
        val year: Int,
        val month: Int,
        val day: Int
    )
}