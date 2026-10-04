// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Flowpay

package com.flowpay.app.ui.theme

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

data class FlowpayAccentTheme(
    val primary: Color,
    val primaryDark: Color,
    val headerGradientStart: Color,
    val headerGradientEnd: Color,
    val accent: Color,
    val accentLight: Color
)

val BlueAccentTheme = FlowpayAccentTheme(
    primary = Color(0xFF5B8DEF),
    primaryDark = Color(0xFF1976D2),
    headerGradientStart = Color(0xFF7BA8F5),
    headerGradientEnd = Color(0xFF6A96EE),
    accent = Color(0xFF4A90E2),
    accentLight = Color(0xFF4A9EFF)
)

/**
 * UPI 123's rail branding. The green palette defines no distinct ramp shades
 * (blue splits these roles across four tones; green has two tokens), so
 * every slot but the bright one collapses onto [FlowpayAccentGreen], with
 * [FlowpayAccentGreenBright] standing in where BlueAccentTheme uses its
 * bright variant. PaymentRail.Upi123 provides this through
 * [LocalFlowpayAccentTheme] so the shared payment dialogs read the accent
 * seam instead of hardcoding the color.
 */
val GreenAccentTheme = FlowpayAccentTheme(
    primary = FlowpayAccentGreen,
    primaryDark = FlowpayAccentGreen,
    headerGradientStart = FlowpayAccentGreen,
    headerGradientEnd = FlowpayAccentGreen,
    accent = FlowpayAccentGreen,
    accentLight = FlowpayAccentGreenBright
)

val LocalFlowpayAccentTheme = compositionLocalOf { BlueAccentTheme }
