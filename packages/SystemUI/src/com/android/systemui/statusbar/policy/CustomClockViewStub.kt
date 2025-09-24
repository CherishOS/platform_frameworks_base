/*
 * Copyright (C) 2023-2024 the risingOS Android Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.systemui.statusbar.policy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

import com.android.systemui.res.R
import com.android.systemui.util.LunarUtils

class CustomClockViewStub @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val settingsObserver: ContentObserver
    private var currentClockView: View? = null
    private var clockStyle = 0
    private var lunarDateEnabled = false
    
    // LunarUtils for lunar date calculation
    private val lunarUtils = LunarUtils(context)
    
    // BroadcastReceiver for date/time changes
    private val dateChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_DATE_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED -> {
                    updateLunarDate()
                }
            }
        }
    }

    init {
        settingsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                updateLayout()
            }
        }
        updateLayout()
    }

    private fun updateLayout() {
        clockStyle = Settings.System.getIntForUser(
            context.contentResolver, "qs_header_clock_style", 0, UserHandle.USER_CURRENT
        )
        lunarDateEnabled = Settings.System.getIntForUser(
            context.contentResolver, "qs_header_lunar_date", 0, UserHandle.USER_CURRENT
        ) != 0
        
        currentClockView?.let {
            removeView(it)
            currentClockView = null
        }
        
        when (clockStyle) {
            0 -> {
                // Default clock - hide custom clock, let ShadeHeaderController handle
                visibility = View.GONE
                currentClockView?.visibility = View.GONE
                return
            }
            1 -> {
                // Chip clock
                currentClockView = LayoutInflater.from(context).inflate(R.layout.qs_header_clock_chip, this, false)
            }
            2 -> {
                // OOS clock
                currentClockView = LayoutInflater.from(context).inflate(R.layout.qs_header_clock_oos, this, false)
            }
            3 -> {
                // Analog clock
                currentClockView = LayoutInflater.from(context).inflate(R.layout.qs_header_clock_analog, this, false)
            }
            4 -> {
                // Simple clock (separate from default)
                currentClockView = LayoutInflater.from(context).inflate(R.layout.qs_header_clock_simple, this, false)
            }
        }
        
        if (clockStyle != 0) {
            currentClockView?.let { 
                addView(it)
                updateLunarDate()
            }
            visibility = View.VISIBLE
            currentClockView?.visibility = View.VISIBLE
        }
    }
    
    private fun updateLunarDate() {
        currentClockView?.findViewById<TextView>(R.id.custom_clock_lunar_date)?.let { lunarDateView ->
            if (lunarDateEnabled) {
                val currentLocale = context.resources.configuration.locales.get(0)
                val localeString = currentLocale?.toString() ?: 
                    context.resources.configuration.locale?.toString() ?: "en"
                
                val testLocale = if (localeString.contains("vi", ignoreCase = true) || 
                                    localeString.contains("VN", ignoreCase = true)) {
                    "vi-VN"
                } else {
                    localeString
                }
                
                val lunarDateText = lunarUtils.getCurrentLunarDateFormatted(testLocale)
                lunarDateView.text = lunarDateText
                lunarDateView.visibility = View.VISIBLE
            } else {
                lunarDateView.text = ""
                lunarDateView.visibility = View.GONE
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor("qs_header_clock_style"),
            false,
            settingsObserver
        )
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor("qs_header_lunar_date"),
            false,
            settingsObserver
        )
        
        // Register BroadcastReceiver for date/time changes
        val dateChangeFilter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        context.registerReceiver(dateChangeReceiver, dateChangeFilter)
        
        updateLayout()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        context.contentResolver.unregisterContentObserver(settingsObserver)
        
        // Unregister BroadcastReceiver
        try {
            context.unregisterReceiver(dateChangeReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver was not registered, ignore
        }
    }
}
