/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.util

import android.content.Context
import com.android.settingslib.utils.StringUtil as SettingsLibStringUtil
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

val Context.currentLocale: Locale
    get() = resources.configuration.locales[0]

object StringUtil {

    @JvmStatic
    fun getTimeLocalized(context: Context, unixTimestamp: Long): String =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
            .withLocale(context.currentLocale)
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochSecond(unixTimestamp))

    @JvmStatic
    fun getDateLocalized(context: Context, style: FormatStyle, unixTimestamp: Long): String =
        DateTimeFormatter.ofLocalizedDate(style)
            .withLocale(context.currentLocale)
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochSecond(unixTimestamp))

    @JvmStatic
    fun getDateLocalizedUTC(context: Context, style: FormatStyle, unixTimestamp: Long): String =
        DateTimeFormatter.ofLocalizedDate(style)
            .withLocale(context.currentLocale)
            .withZone(ZoneOffset.UTC)
            .format(Instant.ofEpochSecond(unixTimestamp))

    @JvmStatic
    fun formatETA(context: Context, millis: Long): CharSequence =
        SettingsLibStringUtil.formatElapsedTime(context, millis.toDouble(), true, true)
}
