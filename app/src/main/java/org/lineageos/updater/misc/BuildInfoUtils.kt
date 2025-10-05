/*
 * SPDX-FileCopyrightText: 2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.misc

import android.os.SystemProperties

object BuildInfoUtils {
    @JvmStatic
    val buildDateTimestamp: Long
        get() = SystemProperties.getLong(Constants.PROP_BUILD_DATE, 0)

    @JvmStatic
    val buildVersion: String
        get() = SystemProperties.get(Constants.PROP_BUILD_VERSION)
}
