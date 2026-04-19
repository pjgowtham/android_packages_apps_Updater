/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.deviceinfo

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.core.graphics.ColorUtils
import com.android.settingslib.spa.debug.UiModePreviews
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.framework.theme.SettingsRadius
import com.android.settingslib.spa.framework.theme.SettingsShape.CornerExtraLarge1
import com.android.settingslib.spa.framework.theme.SettingsSpace
import com.android.settingslib.spa.framework.theme.SettingsTheme
import org.lineageos.updater.R
import kotlin.math.max
import kotlin.math.roundToInt

private fun Color.toArgbColor(): Int =
    android.graphics.Color.argb(alpha, red, green, blue)

private const val PATTERN_SATURATION = 0.39f
private const val PATTERN_LIGHTNESS = 0.46f
private const val MARK_X_HEIGHT_RATIO = 0.53f
private const val MARK_WIDTH_MULTIPLIER = 2.5f
private const val TEEN_VERSION_SPACING_RATIO = 0.20f
private const val DEFAULT_VERSION_SPACING_RATIO = 0.10f
private const val TILE_TO_RADIUS_RATIO = 6f

private fun derivePatternColor(brandColor: Color): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(brandColor.toArgbColor(), hsl)
    hsl[1] = PATTERN_SATURATION
    hsl[2] = PATTERN_LIGHTNESS
    return Color(ColorUtils.HSLToColor(hsl))
}

private val HEADER_PATTERN_AGSL = """
    uniform float  tileSize;
    uniform float  radius;
    uniform float2 iResolution;
    layout(color) uniform half4 patternColor;
    layout(color) uniform half4 sheenColor;

    float circleAlpha(float2 p, float2 c, float r) {
        return smoothstep(r + 1.0, r - 1.0, length(p - c));
    }
    float bottomSemiAlpha(float2 p, float2 c, float r) {
        return circleAlpha(p, c, r) * step(c.y, p.y);
    }
    float rightSemiAlpha(float2 p, float2 c, float r) {
        return circleAlpha(p, c, r) * step(c.x, p.x);
    }

    half4 main(float2 fragCoord) {
        float  T     = tileSize;
        float  r     = radius;
        float2 local = mod(fragCoord, T);
        float  hit   = 0.0;

        /* Accumulate coverage from this tile and its 8 neighbours. */
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                float2 p = local - float2(float(dx), float(dy)) * T;

                /* Full circles */
                hit = max(hit, circleAlpha(p, float2(T / 3.0,       0.0          ), r));
                hit = max(hit, circleAlpha(p, float2(T * 2.0 / 3.0, T / 3.0      ), r));
                hit = max(hit, circleAlpha(p, float2(0.0,           T * 2.0 / 3.0), r));

                /* Bottom semicircles */
                hit = max(hit, bottomSemiAlpha(p, float2(T / 3.0,       T * 2.0 / 3.0), r));
                hit = max(hit, bottomSemiAlpha(p, float2(T * 2.0 / 3.0, 0.0          ), r));
                hit = max(hit, bottomSemiAlpha(p, float2(0.0,           T / 3.0      ), r));
                hit = max(hit, bottomSemiAlpha(p, float2(T / 2.0,       T / 2.0      ), r));
                hit = max(hit, bottomSemiAlpha(p, float2(T * 5.0 / 6.0, T * 5.0 / 6.0), r));
                hit = max(hit, bottomSemiAlpha(p, float2(T / 6.0,       T / 6.0      ), r));

                /* Right semicircles */
                hit = max(hit, rightSemiAlpha(p, float2(T / 2.0,       T * 5.0 / 6.0), r));
                hit = max(hit, rightSemiAlpha(p, float2(T * 5.0 / 6.0, T / 6.0      ), r));
                hit = max(hit, rightSemiAlpha(p, float2(T / 6.0,       T / 2.0      ), r));
                hit = max(hit, rightSemiAlpha(p, float2(T * 2.0 / 3.0, T * 2.0 / 3.0), r));
                hit = max(hit, rightSemiAlpha(p, float2(0.0,           0.0          ), r));
                hit = max(hit, rightSemiAlpha(p, float2(T / 3.0,       T / 3.0      ), r));
            }
        }

        /*
         * Layer 1 — Pattern: tiled shapes at 10% opacity.
         */
        float pA = 0.10 * hit;
        half4 cPattern = half4(patternColor.rgb * pA, pA);

        /*
         * Layer 2 — Masked Sheen: linear gradient, evaluated across the full card.
         * Masked by hit (so it only appears on the shapes), and multiplied by
         * an overall 16% opacity as defined by the SVG mask group.
         */
        float2 gradEnd = float2(iResolution.x * 1.0314, iResolution.y * 0.9208);
        float2 dir = gradEnd;
        float t = clamp(dot(fragCoord, dir) / dot(dir, dir), 0.0, 1.0);

        float gradAlpha;
        if (t <= 0.326923) {
            gradAlpha = 0.16;
        } else if (t <= 0.66) {
            gradAlpha = mix(0.16, 1.0, (t - 0.326923) / (0.66 - 0.326923));
        } else {
            gradAlpha = mix(1.0, 0.08, (t - 0.66) / (1.0 - 0.66));
        }

        float sA = gradAlpha * 0.16 * hit * sheenColor.a;
        half4 cSheen = half4(sheenColor.rgb * sA, sA);

        /* Composite: sheen (top layer) over pattern (bottom layer). */
        half4 cOut = cSheen + cPattern * (1.0 - cSheen.a);
        return cOut;
    }
""".trimIndent()

private fun CacheDrawScope.buildPatternShader(
    tileSizePx: Float,
    radiusPx: Float,
    patternColor: Color,
    sheenColor: Color,
): android.graphics.RuntimeShader = android.graphics.RuntimeShader(HEADER_PATTERN_AGSL).apply {
    setFloatUniform("tileSize", tileSizePx)
    setFloatUniform("radius", radiusPx)
    setFloatUniform("iResolution", size.width, size.height)
    setColorUniform("patternColor", patternColor.toArgbColor())
    setColorUniform("sheenColor", sheenColor.toArgbColor())
}

/**
 * A card container providing the standard Updater card shape, padding, and transparent
 * background for wrapping UI sections.
 *
 * Derived from [com.android.settingslib.spa.widget.card.SettingsCard].
 */
@Composable
fun UpdaterCard(
    buildVersion: String,
    androidVersion: String,
    buildDate: String,
    securityPatch: String,
    modifier: Modifier = Modifier,
    shape: Shape = CornerExtraLarge1
) {
    val brandColor = colorResource(R.color.brand_color)
    val onBrandColor = colorResource(R.color.on_brand_color)
    val patternColor = remember(brandColor) { derivePatternColor(brandColor) }
    val sheenColor = colorResource(R.color.brand_sheen_color)

    val density = LocalDensity.current
    val displayMedium = MaterialTheme.typography.displayMedium
    val versionStyle = remember(displayMedium) {
        displayMedium.copy(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Light,
            letterSpacing = 0.08.em,
            lineHeight = displayMedium.fontSize,
        )
    }

    val markHeightDp = remember(versionStyle, density) {
        with(density) { (versionStyle.fontSize.toPx() * MARK_X_HEIGHT_RATIO).toDp() }
    }
    val markWidthDp = markHeightDp * MARK_WIDTH_MULTIPLIER

    val majorVersion =
        remember(buildVersion) { buildVersion.substringBefore('.').toIntOrNull() ?: 0 }
    val spacingRatio =
        if (majorVersion in 10..19) TEEN_VERSION_SPACING_RATIO else DEFAULT_VERSION_SPACING_RATIO

    Card(
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = Color.Transparent,
            contentColor = onBrandColor,
        ),
        modifier = modifier
            .fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(brandColor)
                .drawWithCache {
                    /*
                     * Keep vertical crop lines aligned to circle geometry.
                     * By deriving the radius from the rendered height (in an integer number of
                     * radius units), the top and bottom edges always land on circle centers or
                     * tangents, never arbitrary slices.
                     */
                    val baseRadiusPx = with(density) { 24.dp.toPx() }
                    val radiusSteps = max(1, (size.height / baseRadiusPx).roundToInt())
                    val alignedRadiusPx = size.height / radiusSteps
                    val alignedTileSizePx = alignedRadiusPx * TILE_TO_RADIUS_RATIO
                    val shader = buildPatternShader(
                        alignedTileSizePx, alignedRadiusPx, patternColor, sheenColor,
                    )
                    val brush = ShaderBrush(shader)
                    onDrawBehind { drawRect(brush) }
                },
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(SettingsDimension.paddingLarge),
                ) {
                    Image(
                        painter = painterResource(R.drawable.lineage_mark_tight),
                        contentDescription = stringResource(R.string.brand_name),
                        modifier = Modifier
                            .width(markWidthDp)
                            .alignBy { it.measuredHeight },
                        contentScale = ContentScale.FillWidth,
                        colorFilter = ColorFilter.tint(onBrandColor),
                    )

                    Spacer(modifier = Modifier.width(markWidthDp * spacingRatio))

                    Text(
                        text = buildVersion,
                        style = versionStyle,
                        modifier = Modifier.alignByBaseline(),
                    )
                }

                Spacer(modifier = Modifier.height(SettingsSpace.medium5))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = SettingsDimension.paddingLarge),
                ) {
                    InfoColumn(
                        label = stringResource(R.string.header_build_version, buildVersion),
                        value = stringResource(
                            R.string.header_android_version, androidVersion
                        ),
                        modifier = Modifier.padding(start = SettingsDimension.paddingLarge),
                    )
                    InfoColumn(
                        label = stringResource(R.string.build_date),
                        value = buildDate,
                        modifier = Modifier.padding(start = SettingsDimension.paddingLarge),
                    )
                    InfoColumn(
                        label = stringResource(R.string.security_update),
                        value = securityPatch,
                        modifier = Modifier.padding(start = SettingsDimension.paddingLarge),
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoColumn(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(SettingsSpace.extraSmall2),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall.copy(
                fontWeight = FontWeight.Normal,
            ),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@UiModePreviews
@Composable
private fun UpdaterCardPreview() {
    SettingsTheme {
        UpdaterCard(
            buildVersion = "23.2",
            androidVersion = "16",
            buildDate = "Feb 20",
            securityPatch = "Feb 2026",
            modifier = Modifier.padding(SettingsDimension.itemPadding),
        )
    }
}
