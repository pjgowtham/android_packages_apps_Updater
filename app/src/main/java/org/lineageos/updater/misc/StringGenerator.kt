/*
 * Copyright (C) 2017-2022 The LineageOS Project
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

@file:JvmName("StringGenerator")

package org.lineageos.updater.misc

import android.content.Context
import android.content.res.Resources
import org.lineageos.updater.R
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.text.DateFormat as JavaDateFormat

/**
 * Gets the current locale from the application resources, handling API level differences.
 * This helper function uses the recommended way to retrieve the primary locale.
 *
 * @param context the context
 * @return the current primary locale
 */
fun getCurrentLocale(context: Context): Locale {
    return context.resources.configuration.locales.get(0)
}

/**
 * Converts a Unix timestamp to a localized time string.
 *
 * @param context the context
 * @param unixTimestamp the timestamp in seconds
 * @return the localized time string
 */
fun getTimeLocalized(context: Context, unixTimestamp: Long): String {
    // We use JavaDateFormat (aliased) as it is the standard for Android compatibility
    val f = JavaDateFormat.getTimeInstance(JavaDateFormat.SHORT, getCurrentLocale(context))
    val date = Date(unixTimestamp * 1000)
    return f.format(date)
}

/**
 * Converts a Unix timestamp to a localized date string.
 *
 * @param context the context
 * @param dateFormat the format style (e.g., DateFormat.SHORT)
 * @param unixTimestamp the timestamp in seconds
 * @return the localized date string
 */
fun getDateLocalized(context: Context, dateFormat: Int, unixTimestamp: Long): String {
    val f = JavaDateFormat.getDateInstance(dateFormat, getCurrentLocale(context))
    val date = Date(unixTimestamp * 1000)
    return f.format(date)
}

/**
 * Converts a Unix timestamp to a localized UTC date string.
 *
 * @param context the context
 * @param dateFormat the format style (e.g., DateFormat.SHORT)
 * @param unixTimestamp the timestamp in seconds
 * @return the localized UTC date string
 */
fun getDateLocalizedUTC(context: Context, dateFormat: Int, unixTimestamp: Long): String {
    val f = JavaDateFormat.getDateInstance(dateFormat, getCurrentLocale(context))
    f.timeZone = TimeZone.getTimeZone("UTC")
    val date = Date(unixTimestamp * 1000)
    return f.format(date)
}

/**
 * Formats a time in milliseconds into a string showing the estimated time remaining
 * (e.g., "5 hours", "20 minutes", "35 seconds").
 *
 * @param context the context
 * @param millis the time in milliseconds
 * @return the formatted ETA string
 */
fun formatETA(context: Context, millis: Long): String {
    val secondInMillis = 1000L
    val minuteInMillis = secondInMillis * 60L
    val hourInMillis = minuteInMillis * 60L

    val res: Resources = context.resources

    return when {
        // If more than 30 minutes, round up to the nearest hour
        millis >= hourInMillis -> {
            val hours = ((millis + minuteInMillis * 30) / hourInMillis).toInt()
            res.getQuantityString(R.plurals.eta_hours, hours, hours)
        }
        // If more than 30 seconds, round up to the nearest minute
        millis >= minuteInMillis -> {
            val minutes = ((millis + secondInMillis * 30) / minuteInMillis).toInt()
            res.getQuantityString(R.plurals.eta_minutes, minutes, minutes)
        }
        // Otherwise, round up to the nearest second
        else -> {
            val seconds = ((millis + 500) / secondInMillis).toInt()
            res.getQuantityString(R.plurals.eta_seconds, seconds, seconds)
        }
    }
}
