/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.data

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

enum class CheckInterval(val value: String, val duration: Duration, val id: Int) {
    DAILY("daily", 1.days, 1),
    WEEKLY("weekly", 7.days, 2),
    MONTHLY("monthly", 30.days, 3);

    companion object {
        fun fromId(id: Int): CheckInterval? = entries.firstOrNull { it.id == id }

        fun fromValue(value: String?): CheckInterval =
            entries.firstOrNull { it.value == value } ?: WEEKLY
    }
}
