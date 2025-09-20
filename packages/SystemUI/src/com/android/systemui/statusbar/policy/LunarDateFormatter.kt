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

/**
 * Helper that formats the lunar calendar date for the QS header.
 */
class LunarDateFormatter(private val resources: Resources) {

    /**
     * Returns the formatted lunar calendar date for [timeInMillis] or `null` when disabled.
     */
    fun getFormattedLunarDate(timeInMillis: Long): String? {
        if (!resources.getBoolean(R.bool.config_show_qs_lunar_calendar)) {
            return null
        }

        val calendar = ChineseCalendar()
        calendar.timeInMillis = timeInMillis

        val day = calendar.get(Calendar.DAY_OF_MONTH)
        val month = calendar.get(Calendar.MONTH) + 1

        return resources.getString(R.string.qs_lunar_date_format, day, month)
    }
}
