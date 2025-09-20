/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.statusbar.policy

import android.content.res.Resources
import android.icu.util.Calendar
import android.icu.util.ChineseCalendar
import com.android.systemui.res.R
import java.util.Locale

/**
 * Helper that formats the lunar calendar date for the QS header.
 */
class LunarDateFormatter(private val resources: Resources) {

    private val calendar: ChineseCalendar = ChineseCalendar()
    private val vietnameseLanguage = Locale("vi").language
    private val configEnabled = resources.getBoolean(R.bool.config_show_qs_lunar_calendar)
    private var isEnabled = configEnabled

    /** Sets whether the formatter should output the lunar date in addition to honoring config. */
    fun setEnabled(enabled: Boolean) {
        isEnabled = configEnabled && enabled
    }

    /**
     * Returns the formatted lunar calendar date for [timeInMillis] or `null` when disabled.
     */
    fun getFormattedLunarDate(timeInMillis: Long): String? {
        if (!isEnabled) {
            return null
        }

        calendar.timeInMillis = timeInMillis

        val day = calendar.get(Calendar.DAY_OF_MONTH)
        val month = calendar.get(Calendar.MONTH) + 1
        val isLeapMonth = calendar.get(ChineseCalendar.IS_LEAP_MONTH) == 1

        val locale = primaryLocale()
        return if (locale.language.equals(vietnameseLanguage, ignoreCase = true)) {
            formatVietnamese(day, month, isLeapMonth)
        } else {
            formatDefault(day, month, isLeapMonth)
        }
    }

    private fun formatDefault(day: Int, month: Int, isLeapMonth: Boolean): String {
        val base = resources.getString(R.string.qs_lunar_date_format, day, month)
        return appendLeapSuffixIfNeeded(base, isLeapMonth)
    }

    private fun formatVietnamese(day: Int, month: Int, isLeapMonth: Boolean): String {
        val dayNames = resources.getStringArray(R.array.qs_lunar_vietnamese_day_names)
        val monthNames = resources.getStringArray(R.array.qs_lunar_vietnamese_month_names)
        if (day !in 1..dayNames.size || month !in 1..monthNames.size) {
            return formatDefault(day, month, isLeapMonth)
        }

        val base = resources.getString(
            R.string.qs_lunar_date_vietnamese_format,
            dayNames[day - 1],
            monthNames[month - 1]
        )
        return appendLeapSuffixIfNeeded(base, isLeapMonth)
    }

    private fun appendLeapSuffixIfNeeded(text: String, isLeapMonth: Boolean): String {
        if (!isLeapMonth) {
            return text
        }
        val suffix = resources.getString(R.string.qs_lunar_date_leap_suffix)
        return text + suffix
    }

    private fun primaryLocale(): Locale {
        val locales = resources.configuration.locales
        if (!locales.isEmpty) {
            return locales[0]
        }
        @Suppress("DEPRECATION")
        return resources.configuration.locale
    }
}
