/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private val DefaultBringIntoViewPadding = 16.dp

@OptIn(ExperimentalFoundationApi::class)
fun Modifier.bringIntoViewOnFocus(
    padding: Dp = DefaultBringIntoViewPadding,
    includeChildren: Boolean = false,
): Modifier = composed {
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val coroutineScope = rememberCoroutineScope()
    val paddingPx = with(LocalDensity.current) { padding.toPx() }
    var size by remember { mutableStateOf(IntSize.Zero) }

    bringIntoViewRequester(bringIntoViewRequester)
        .onSizeChanged { size = it }
        .onFocusChanged { focusState ->
            if (focusState.isFocused || (includeChildren && focusState.hasFocus)) {
                coroutineScope.launch {
                    if (size == IntSize.Zero || paddingPx == 0f) {
                        bringIntoViewRequester.bringIntoView()
                    } else {
                        bringIntoViewRequester.bringIntoView(
                            Rect(
                                left = -paddingPx,
                                top = -paddingPx,
                                right = size.width + paddingPx,
                                bottom = size.height + paddingPx,
                            )
                        )
                    }
                }
            }
        }
}
