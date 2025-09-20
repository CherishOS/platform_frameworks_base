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

import android.content.res.Configuration
import android.icu.util.ChineseCalendar
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.res.R
import com.google.common.truth.Truth.assertThat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidTestingRunner::class)
@SmallTest
class LunarDateFormatterTest : SysuiTestCase() {

    @Test
    fun getFormattedLunarDate_returnsNullWhenDisabled() {
        overrideResource(R.bool.config_show_qs_lunar_calendar, false)

        val formatter = LunarDateFormatter(mContext.resources)

        assertThat(formatter.getFormattedLunarDate(0)).isNull()
    }

    @Test
    fun getFormattedLunarDate_formatsNumericValuesByDefault() {
        overrideResource(R.bool.config_show_qs_lunar_calendar, true)
        val timeZone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
        val originalTimeZone = TimeZone.getDefault()

        try {
            TimeZone.setDefault(timeZone)
            val timestamp = timestampForLunarNewYear(timeZone)

            val formatter = LunarDateFormatter(mContext.resources)
            val text = formatter.getFormattedLunarDate(timestamp)

            val expected = mContext.getString(R.string.qs_lunar_date_format, 1, 1)
            assertThat(text).isEqualTo(expected)
        } finally {
            TimeZone.setDefault(originalTimeZone)
        }
    }

    @Test
    fun getFormattedLunarDate_formatsVietnameseTerms() {
        overrideResource(R.bool.config_show_qs_lunar_calendar, true)
        val timeZone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
        val originalTimeZone = TimeZone.getDefault()

        try {
            TimeZone.setDefault(timeZone)
            val configuration = Configuration(mContext.resources.configuration)
            configuration.setLocale(Locale("vi", "VN"))
            val vietnameseContext = mContext.createConfigurationContext(configuration)

            val formatter = LunarDateFormatter(vietnameseContext.resources)
            val timestamp = timestampForLunarNewYear(timeZone)
            val text = formatter.getFormattedLunarDate(timestamp)

            val dayName = vietnameseContext.resources
                .getStringArray(R.array.qs_lunar_vietnamese_day_names)[0]
            val monthName = vietnameseContext.resources
                .getStringArray(R.array.qs_lunar_vietnamese_month_names)[0]
            val expected = vietnameseContext.getString(
                R.string.qs_lunar_date_vietnamese_format,
                dayName,
                monthName
            )
            assertThat(text).isEqualTo(expected)
        } finally {
            TimeZone.setDefault(originalTimeZone)
        }
    }

    @Test
    fun getFormattedLunarDate_appendsLeapSuffixForLeapMonths() {
        overrideResource(R.bool.config_show_qs_lunar_calendar, true)
        val timeZone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
        val originalTimeZone = TimeZone.getDefault()

        try {
            TimeZone.setDefault(timeZone)
            val timestamp = findLeapMonthTimestamp(timeZone)

            val formatter = LunarDateFormatter(mContext.resources)
            val text = formatter.getFormattedLunarDate(timestamp)

            val suffix = mContext.getString(R.string.qs_lunar_date_leap_suffix)
            assertThat(text).isNotNull()
            assertThat(text).endsWith(suffix)
        } finally {
            TimeZone.setDefault(originalTimeZone)
        }
    }

    private fun timestampForLunarNewYear(timeZone: TimeZone): Long {
        val calendar = Calendar.getInstance(timeZone)
        calendar.set(2024, Calendar.FEBRUARY, 10, 12, 0, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    private fun findLeapMonthTimestamp(timeZone: TimeZone): Long {
        val start = Calendar.getInstance(timeZone).apply {
            set(2023, Calendar.JANUARY, 1, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val end = Calendar.getInstance(timeZone).apply {
            set(2024, Calendar.JANUARY, 1, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val chineseCalendar = ChineseCalendar()
        var time = start.timeInMillis
        while (time < end.timeInMillis) {
            chineseCalendar.timeInMillis = time
            if (chineseCalendar.get(ChineseCalendar.IS_LEAP_MONTH) == 1) {
                return time
            }
            time += TimeUnit.DAYS.toMillis(1)
        }
        throw AssertionError("No leap month detected in search window")
    }
}
