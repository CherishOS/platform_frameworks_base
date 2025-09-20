/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.icu.text.DateFormat
import android.icu.text.DisplayContext
import android.icu.util.Calendar
import android.os.Handler
import android.os.HandlerExecutor
import android.os.UserHandle
import android.text.TextUtils
import android.util.Log
import android.view.View.MeasureSpec
import android.net.Uri
import android.provider.Settings
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.Dependency
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.shade.ShadeLogger
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.util.ViewController
import com.android.systemui.util.time.SystemClock
import java.text.FieldPosition
import java.text.ParsePosition
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Named

@VisibleForTesting
internal fun getTextForFormat(date: Date?, format: DateFormat): String {
    return if (format === EMPTY_FORMAT) { // Check if same object
        ""
    } else format.format(date)
}

@VisibleForTesting
internal fun getFormatFromPattern(pattern: String?): DateFormat {
    if (TextUtils.equals(pattern, "")) {
        return EMPTY_FORMAT
    }
    val l = Locale.getDefault()
    val format = DateFormat.getInstanceForSkeleton(pattern, l)
    // The use of CAPITALIZATION_FOR_BEGINNING_OF_SENTENCE instead of
    // CAPITALIZATION_FOR_STANDALONE is to address
    // https://unicode-org.atlassian.net/browse/ICU-21631
    // TODO(b/229287642): Switch back to CAPITALIZATION_FOR_STANDALONE
    format.setContext(DisplayContext.CAPITALIZATION_FOR_BEGINNING_OF_SENTENCE)
    return format
}

private val EMPTY_FORMAT: DateFormat = object : DateFormat() {
    override fun format(
        cal: Calendar,
        toAppendTo: StringBuffer,
        fieldPosition: FieldPosition
    ): StringBuffer? {
        return null
    }

    override fun parse(text: String, cal: Calendar, pos: ParsePosition) {}
}

private const val DEBUG = false
private const val TAG = "VariableDateViewController"

class VariableDateViewController(
    private val systemClock: SystemClock,
    private val broadcastDispatcher: BroadcastDispatcher,
    private val shadeInteractor: ShadeInteractor,
    private val shadeLogger: ShadeLogger,
    private val timeTickHandler: Handler,
    view: VariableDateView
) : ViewController<VariableDateView>(view) {

    private val lunarDateFormatter = LunarDateFormatter(view.resources)
    private val contentResolver = view.context.contentResolver
    private val lunarDateSettingUri: Uri =
        Settings.System.getUriFor(Settings.System.QS_SHOW_LUNAR_DATE)
    private var lunarDateEnabled = view.resources.getBoolean(R.bool.config_show_qs_lunar_calendar)
    private val lunarDateObserver =
        object : ContentObserver(timeTickHandler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                updateLunarDateEnabled(triggerRefresh = true)
            }
        }
    private var lunarDateObserverRegistered = false

    private var dateFormat: DateFormat? = null
    private var datePattern = view.longerPattern
        set(value) {
            if (field == value) return
            field = value
            dateFormat = null
            if (isAttachedToWindow) {
                post(::updateClock)
            }
        }
    private var isQsExpanded = false
    private var lastWidth = Integer.MAX_VALUE
    private var lastText = ""
    private var currentTime = Date()

    // View class easy accessors
    private val longerPattern: String
        get() = mView.longerPattern
    private val shorterPattern: String
        get() = mView.shorterPattern
    private fun post(block: () -> Unit) = mView.handler?.post(block)

    private val intentReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (
                    Intent.ACTION_LOCALE_CHANGED == action ||
                    Intent.ACTION_TIMEZONE_CHANGED == action
            ) {
                // need to get a fresh date format
                dateFormat = null
                shadeLogger.d("VariableDateViewController received intent to refresh date format")
            }

            val handler = mView.handler

            // If the handler is null, it means we received a broadcast while the view has not
            // finished being attached or in the process of being detached.
            // In that case, do not post anything.
            if (handler == null) {
                shadeLogger.d("VariableDateViewController received intent but handler was null")
            } else if (Intent.ACTION_USER_SWITCHED == action) {
                handler.post { updateLunarDateEnabled(triggerRefresh = true) }
            } else if (
                    Intent.ACTION_TIME_TICK == action ||
                    Intent.ACTION_TIME_CHANGED == action ||
                    Intent.ACTION_TIMEZONE_CHANGED == action ||
                    Intent.ACTION_LOCALE_CHANGED == action
            ) {
                handler.post(::updateClock)
            }
        }
    }

    private val onMeasureListener = object : VariableDateView.OnMeasureListener {
        override fun onMeasureAction(availableWidth: Int, widthMeasureSpec: Int) {
            if (!isQsExpanded && MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.AT_MOST) {
                // ignore measured width from AT_MOST passes when in QQS (b/289489856)
                return
            }
            if (availableWidth != lastWidth) {
                // maybeChangeFormat will post if the pattern needs to change.
                maybeChangeFormat(availableWidth)
                lastWidth = availableWidth
            }
        }
    }

    private fun onQsExpansionFractionChanged(qsExpansionFraction: Float) {
        val newIsQsExpanded = qsExpansionFraction > 0.5
        if (newIsQsExpanded != isQsExpanded) {
            isQsExpanded = newIsQsExpanded
            // manually trigger a measure pass midway through the transition from QS to QQS
            post { mView.measure(0, 0) }
        }
    }

    override fun onInit() {
        super.onInit()
        updateLunarDateEnabled(triggerRefresh = false)
    }

    override fun onViewAttached() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_LOCALE_CHANGED)
            addAction(Intent.ACTION_USER_SWITCHED)
        }

        broadcastDispatcher.registerReceiver(intentReceiver, filter,
                HandlerExecutor(timeTickHandler), UserHandle.SYSTEM)
        if (
            mView.resources.getBoolean(R.bool.config_show_qs_lunar_calendar) &&
                !lunarDateObserverRegistered
        ) {
            contentResolver.registerContentObserver(
                lunarDateSettingUri,
                false,
                lunarDateObserver,
                UserHandle.USER_ALL
            )
            lunarDateObserverRegistered = true
        }
        updateLunarDateEnabled(triggerRefresh = false)
        mView.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                shadeInteractor.qsExpansion.collect(::onQsExpansionFractionChanged)
            }
        }
        post(::updateClock)
        mView.onAttach(onMeasureListener)
    }

    override fun onViewDetached() {
        dateFormat = null
        if (lunarDateObserverRegistered) {
            contentResolver.unregisterContentObserver(lunarDateObserver)
            lunarDateObserverRegistered = false
        }
        mView.onAttach(null)
        broadcastDispatcher.unregisterReceiver(intentReceiver)
    }

    private fun updateClock() {
        if (dateFormat == null) {
            dateFormat = getFormatFromPattern(datePattern)
        }

        currentTime.time = systemClock.currentTimeMillis()

        val text = getDisplayTextForCurrentPattern()
        if (text != lastText) {
            mView.setText(text)
            lastText = text
        }
    }

    private fun updateLunarDateEnabled(triggerRefresh: Boolean) {
        val configEnabled = mView.resources.getBoolean(R.bool.config_show_qs_lunar_calendar)
        val defaultValue = if (configEnabled) 1 else 0
        val enabled =
            configEnabled &&
                Settings.System.getIntForUser(
                    contentResolver,
                    Settings.System.QS_SHOW_LUNAR_DATE,
                    defaultValue,
                    UserHandle.USER_CURRENT
                ) == 1
        val changed = enabled != lunarDateEnabled
        lunarDateEnabled = enabled
        lunarDateFormatter.setEnabled(enabled)

        if ((changed || triggerRefresh) && isAttachedToWindow) {
            post {
                if (lastWidth != Integer.MAX_VALUE) {
                    maybeChangeFormat(lastWidth)
                }
                updateClock()
            }
        }
    }

    private fun getDisplayTextForCurrentPattern(): String {
        val pattern = datePattern
        if (pattern.isEmpty()) {
            return ""
        }

        val format = dateFormat ?: getFormatFromPattern(pattern)
        val dateText = getTextForFormat(currentTime, format)
        if (dateText.isEmpty()) {
            return dateText
        }

        val lunarText =
            if (lunarDateEnabled) {
                lunarDateFormatter.getFormattedLunarDate(currentTime.time)
            } else {
                null
            }
        return if (lunarText.isNullOrEmpty()) {
            dateText
        } else {
            mView.resources.getString(R.string.qs_lunar_date_combination, dateText, lunarText)
        }
    }

    private fun getCombinedTextForPattern(pattern: String): String {
        if (pattern.isEmpty()) {
            return ""
        }

        val format = getFormatFromPattern(pattern)
        val dateText = getTextForFormat(currentTime, format)
        if (dateText.isEmpty()) {
            return ""
        }

        val lunarText =
            if (lunarDateEnabled) {
                lunarDateFormatter.getFormattedLunarDate(currentTime.time)
            } else {
                null
            }
        return if (lunarText.isNullOrEmpty()) {
            dateText
        } else {
            mView.resources.getString(R.string.qs_lunar_date_combination, dateText, lunarText)
        }
    }

    private fun maybeChangeFormat(availableWidth: Int) {
        if (mView.freezeSwitching ||
                availableWidth > lastWidth && datePattern == longerPattern ||
                availableWidth < lastWidth && datePattern == ""
        ) {
            // Nothing to do
            return
        }
        if (DEBUG) Log.d(TAG, "Width changed. Maybe changing pattern")
        // Start with longer pattern and see what fits
        currentTime.time = systemClock.currentTimeMillis()
        var text = getCombinedTextForPattern(longerPattern)
        var length = mView.getDesiredWidthForText(text)
        if (length <= availableWidth) {
            changePattern(longerPattern)
            return
        }

        text = getCombinedTextForPattern(shorterPattern)
        length = mView.getDesiredWidthForText(text)
        if (length <= availableWidth) {
            changePattern(shorterPattern)
            return
        }

        changePattern("")
    }

    private fun changePattern(newPattern: String) {
        if (newPattern.equals(datePattern)) return
        if (DEBUG) Log.d(TAG, "Changing pattern to $newPattern")
        datePattern = newPattern
    }

    class Factory @Inject constructor(
        private val systemClock: SystemClock,
        private val broadcastDispatcher: BroadcastDispatcher,
        private val shadeInteractor: ShadeInteractor,
        private val shadeLogger: ShadeLogger,
        @Named(Dependency.TIME_TICK_HANDLER_NAME) private val handler: Handler
    ) {
        fun create(view: VariableDateView): VariableDateViewController {
            return VariableDateViewController(
                systemClock,
                broadcastDispatcher,
                shadeInteractor,
                shadeLogger,
                handler,
                view
            )
        }
    }
}
