/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.ui

import android.content.Context
import androidx.annotation.StringRes

/**
 * Carrier for a string that is either a resolved raw value or a resource reference with
 * optional format arguments. Resolved lazily at the UI layer via [resolve].
 *
 * [Resource] is intentionally a plain class rather than a data class because vararg
 * parameters in data class primary constructors are prohibited — array fields break
 * structural equality (equals/hashCode/copy).
 *
 * TODO: Replace with a plain [@StringRes Int] + args pair once the View-side adapter is
 *       removed, or with [androidx.compose.ui.text.AnnotatedString] where rich text is needed.
 */
sealed class StringWrapper {

    class Resource(
        @param:StringRes val stringRes: Int,
        vararg val args: Any,
    ) : StringWrapper()

    data class Raw(val text: String) : StringWrapper()

    fun resolve(context: Context): String = when (this) {
        is Resource -> context.getString(stringRes, *args)
        is Raw -> text
    }
}
