/*
 * SPDX-FileCopyrightText: 2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.misc

import android.content.Context
import org.lineageos.updater.R
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object StringGenerator {

    private const val SECOND_IN_MILLIS = 1000L
    private const val MINUTE_IN_MILLIS = SECOND_IN_MILLIS * 60
    private const val HOUR_IN_MILLIS = MINUTE_IN_MILLIS * 60

    @JvmStatic
    fun formatETA(context: Context, millis: Long): String {
        val res = context.resources
        return when {
            millis >= HOUR_IN_MILLIS -> {
                val hours = ((millis + 1800000) / HOUR_IN_MILLIS).toInt()
                res.getQuantityString(R.plurals.eta_hours, hours, hours)
            }

            millis >= MINUTE_IN_MILLIS -> {
                val minutes = ((millis + 30000) / MINUTE_IN_MILLIS).toInt()
                res.getQuantityString(R.plurals.eta_minutes, minutes, minutes)
            }

            else -> {
                val seconds = ((millis + 500) / SECOND_IN_MILLIS).toInt()
                res.getQuantityString(R.plurals.eta_seconds, seconds, seconds)
            }
        }
    }

    @JvmStatic
    fun getDateLocalized(context: Context, dateFormat: Int, unixTimestamp: Long): String {
        val f = DateFormat.getDateInstance(dateFormat, getCurrentLocale(context))
        val date = Date(unixTimestamp * 1000)
        return f.format(date)
    }

    @JvmStatic
    fun getDateLocalizedUTC(context: Context, dateFormat: Int, unixTimestamp: Long): String {
        val f = DateFormat.getDateInstance(dateFormat, getCurrentLocale(context)).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val date = Date(unixTimestamp * 1000)
        return f.format(date)
    }

    @JvmStatic
    fun getCurrentLocale(context: Context): Locale {
        val locales = context.resources.configuration.locales
        return locales.getFirstMatch(context.resources.assets.locales) ?: Locale.getDefault()
    }

    @JvmStatic
    fun getTimeLocalized(context: Context, unixTimestamp: Long): String {
        val f = DateFormat.getTimeInstance(DateFormat.SHORT, getCurrentLocale(context))
        val date = Date(unixTimestamp * 1000)
        return f.format(date)
    }
}
