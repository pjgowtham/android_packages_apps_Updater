/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import androidx.core.graphics.ColorUtils
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.framework.theme.SettingsTheme
import org.lineageos.updater.misc.DeviceInfoUtils
import org.lineageos.updater.misc.StringGenerator
import org.lineageos.updater.misc.Utils
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.core.net.toUri

/**
 * Converts a Compose [Color] to an sRGB ARGB8888 [Int].
 * Wide-gamut information is intentionally truncated because both
 * [ColorUtils] and [android.graphics.RuntimeShader.setColorUniform]
 * operate on sRGB integers.
 */
private fun Color.toArgbColor(): Int =
    android.graphics.Color.argb(alpha, red, green, blue)

private const val PATTERN_SATURATION = 0.39f
private const val PATTERN_LIGHTNESS = 0.46f
private const val MARK_X_HEIGHT_RATIO = 0.53f
private const val MARK_WIDTH_MULTIPLIER = 2.5f
private const val TEEN_VERSION_SPACING_RATIO = 0.20f
private const val DEFAULT_VERSION_SPACING_RATIO = 0.10f

/**
 * Derives the pattern color from the brand base.
 * Preserves the hue from [brandColor] and applies fixed saturation and
 * lightness to produce a lighter, softer tonal variant for the
 * decorative shapes.
 */
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

@Composable
fun UpdaterBanner() {
    val context = LocalContext.current
    val isPreview = LocalInspectionMode.current

    val buildVersion = if (isPreview) "23.2" else DeviceInfoUtils.buildVersion
    val androidVersion = if (isPreview) "15" else Build.VERSION.RELEASE
    val locale = StringGenerator.getCurrentLocale(context)
    val buildDate = if (isPreview) "Jan 25" else {
        val instant = Instant.ofEpochSecond(DeviceInfoUtils.buildDateTimestamp)
        DateTimeFormatter.ofPattern("MMM d", locale)
            .format(instant.atZone(ZoneId.systemDefault()))
    }
    val securityPatch = if (isPreview) "Feb 2026" else {
        val patch = Build.VERSION.SECURITY_PATCH
        try {
            val patchDate = LocalDate.parse(patch)
            DateTimeFormatter.ofPattern("MMM yyyy", locale).format(patchDate)
        } catch (_: Exception) {
            patch
        }
    }

    val brandColor = colorResource(R.color.brand_color)
    val onBrandColor = colorResource(R.color.on_brand_color)
    val patternColor = derivePatternColor(brandColor)
    val sheenColor = colorResource(R.color.brand_sheen_color)

    val density = LocalDensity.current
    val tileSizePx = with(density) { 144.dp.toPx() }
    val radiusPx = with(density) { 24.dp.toPx() }

    val uiMode = LocalConfiguration.current.uiMode
    val isTv = (uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION

    val versionStyle = MaterialTheme.typography.displayMedium.copy(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Light,
        letterSpacing = 0.08.em,
        lineHeight = MaterialTheme.typography.displayMedium.fontSize,
    )

    val markHeightDp = with(density) {
        (versionStyle.fontSize.toPx() * MARK_X_HEIGHT_RATIO).toDp()
    }
    val markWidthDp = markHeightDp * MARK_WIDTH_MULTIPLIER

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = SettingsDimension.itemPaddingEnd,
                vertical = SettingsDimension.itemPaddingAround,
            ),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = if (isTv) {
                MaterialTheme.shapes.extraLarge
            } else {
                MaterialTheme.shapes.extraLarge.copy(
                    bottomStart = CornerSize(0.dp),
                    bottomEnd = CornerSize(0.dp),
                )
            },
            colors = CardDefaults.cardColors(
                containerColor = Color.Transparent,
                contentColor = onBrandColor,
            ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(brandColor)
                    .drawWithCache {
                        val shader = buildPatternShader(
                            tileSizePx, radiusPx, patternColor, sheenColor,
                        )
                        val brush = ShaderBrush(shader)
                        onDrawBehind { drawRect(brush) }
                    },
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
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

                        val majorVersion = buildVersion.substringBefore('.').toIntOrNull() ?: 0
                        val spacingRatio = if (majorVersion in 10..19) {
                            TEEN_VERSION_SPACING_RATIO
                        } else {
                            DEFAULT_VERSION_SPACING_RATIO
                        }
                        Spacer(modifier = Modifier.width(markWidthDp * spacingRatio))

                        Text(
                            text = buildVersion,
                            style = versionStyle,
                            modifier = Modifier.alignByBaseline(),
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                    ) {
                        InfoColumn(
                            label = "${stringResource(R.string.brand_name)} $buildVersion",
                            value = stringResource(
                                R.string.header_android_version, androidVersion
                            ),
                            modifier = Modifier.padding(start = 16.dp),
                        )
                        InfoColumn(
                            label = stringResource(R.string.build_date),
                            value = buildDate,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                        InfoColumn(
                            label = stringResource(R.string.security_update),
                            value = securityPatch,
                        )
                    }
                }
            }
        }

        if (!isTv) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(
                        MaterialTheme.shapes.extraLarge.copy(
                            topStart = CornerSize(0.dp),
                            topEnd = CornerSize(0.dp),
                        )
                    )
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(SettingsDimension.itemPaddingAround),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(onClick = {
                    val url = Utils.getChangelogURL(context)
                    val intent = Intent(Intent.ACTION_VIEW, url.toUri())
                    context.startActivity(intent)
                }) {
                    Text(text = stringResource(R.string.show_changelog))
                }
                TextButton(onClick = {
                    val url = context.getString(R.string.report_issue_url)
                    val intent = Intent(Intent.ACTION_VIEW, url.toUri())
                    context.startActivity(intent)
                }) {
                    Text(text = stringResource(R.string.report_issues))
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.header_review_updates),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.header_download_url),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                Text(
                    text = stringResource(R.string.found_bug),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                Image(
                    painter = painterResource(R.drawable.qr_wiki_reportbug),
                    contentDescription = stringResource(R.string.report_issues),
                    modifier = Modifier.size(150.dp),
                )
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
        verticalArrangement = Arrangement.spacedBy(4.dp),
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

@Preview
@Composable
private fun UpdaterBannerPreview() {
    SettingsTheme {
        UpdaterBanner()
    }
}

@Preview(uiMode = Configuration.UI_MODE_TYPE_TELEVISION)
@Composable
private fun UpdaterBannerTvPreview() {
    SettingsTheme {
        UpdaterBanner()
    }
}
