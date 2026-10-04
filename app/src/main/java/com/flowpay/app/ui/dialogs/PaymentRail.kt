// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Flowpay

package com.flowpay.app.ui.dialogs

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import com.flowpay.app.ui.theme.FlowpayAccentGreen
import com.flowpay.app.ui.theme.FlowpayAccentTheme
import com.flowpay.app.ui.theme.GreenAccentTheme

/**
 * The payment rail a setup/progress dialog is running on.
 *
 * The USSD and UPI 123 dialogs were ~90% copy-paste twins; this type holds
 * everything that actually differed between them, so the shared
 * [PaymentProgressDialog] needs nothing but the rail. [accentTheme] routes
 * per-rail branding through the theme seam instead of hardcoded colors:
 * UPI 123's green is deliberate rail branding, so it carries its own
 * [FlowpayAccentTheme]; USSD inherits the ambient theme by carrying null.
 */
sealed interface PaymentRail {

    /** Badge icon shown in the dialog's accent circle. */
    val icon: ImageVector

    /**
     * The accent theme to theme this rail's dialog with, or null to
     * inherit the ambient [com.flowpay.app.ui.theme.LocalFlowpayAccentTheme].
     */
    val accentTheme: FlowpayAccentTheme?

    /** USSD over *99#: themed by whatever accent the caller provides. */
    data object Ussd : PaymentRail {
        override val icon: ImageVector get() = PhoneIcon
        override val accentTheme: FlowpayAccentTheme? get() = null
    }

    /** UPI 123 IVR pay: deliberately green-branded. */
    data object Upi123 : PaymentRail {
        override val icon: ImageVector get() = UpiIcon
        override val accentTheme: FlowpayAccentTheme? get() = GreenAccentTheme
    }
}

// Custom Phone Icon (moved verbatim from the former UssdProgressDialog).
@Suppress("MagicNumber") // vector path data is inherently numeric
private val PhoneIcon: ImageVector
    get() {
        return ImageVector.Builder(
            name = "phone",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                fill = null,
                stroke = androidx.compose.ui.graphics.SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
                strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round
            ) {
                moveTo(22f, 16.92f)
                verticalLineTo(19.92f)
                curveTo(22.0011f, 20.1985f, 21.9441f, 20.4742f, 21.8325f, 20.7293f)
                curveTo(21.7209f, 20.9844f, 21.5573f, 21.2136f, 21.3521f, 21.4019f)
                curveTo(21.1468f, 21.5901f, 20.9046f, 21.7335f, 20.6407f, 21.8227f)
                curveTo(20.3769f, 21.9119f, 20.0974f, 21.9451f, 19.82f, 21.92f)
                curveTo(16.7428f, 21.5856f, 13.787f, 20.5341f, 11.19f, 18.85f)
                curveTo(8.77382f, 17.3147f, 6.72533f, 15.2662f, 5.19f, 12.85f)
                curveTo(3.49996f, 10.2412f, 2.44824f, 7.271f, 2.12f, 4.18f)
                curveTo(2.095f, 3.90347f, 2.12787f, 3.62476f, 2.21649f, 3.36162f)
                curveTo(2.30512f, 3.09849f, 2.44766f, 2.85669f, 2.63476f, 2.65162f)
                curveTo(2.82185f, 2.44655f, 3.04924f, 2.28271f, 3.302f, 2.17f)
                curveTo(3.55476f, 2.05729f, 3.82777f, 2.00026f, 4.1f, 2f)
                horizontalLineTo(7.1f)
                curveTo(7.59565f, 1.99522f, 8.06622f, 2.16708f, 8.43306f, 2.48353f)
                curveTo(8.7999f, 2.79999f, 9.04224f, 3.23945f, 9.12f, 3.72f)
                curveTo(9.28573f, 4.68007f, 9.51567f, 5.62273f, 9.81f, 6.55f)
                curveTo(9.94454f, 6.97953f, 9.97366f, 7.43697f, 9.89482f, 7.88189f)
                curveTo(9.81598f, 8.32681f, 9.63151f, 8.74707f, 9.36f, 9.11f)
                lineTo(8.09f, 10.81f)
                curveTo(9.51315f, 13.2072f, 11.3868f, 15.2809f, 13.62f, 16.91f)
                lineTo(15.32f, 15.64f)
                curveTo(15.6829f, 15.3685f, 16.1032f, 15.184f, 16.5481f, 15.1052f)
                curveTo(16.993f, 15.0264f, 17.4504f, 15.0555f, 17.88f, 15.19f)
                curveTo(18.8073f, 15.4843f, 19.7499f, 15.7143f, 20.71f, 15.88f)
                curveTo(21.1906f, 15.9578f, 21.6301f, 16.2001f, 21.9465f, 16.5669f)
                curveTo(22.263f, 16.9338f, 22.4348f, 17.4044f, 22.43f, 17.9f)
                lineTo(22.43f, 17.9f)
                close()
            }
        }.build()
    }

// Custom UPI Icon (moved verbatim from the former Upi123ProgressDialog; the
// green path fill is deliberate rail branding).
@Suppress("MagicNumber") // vector path data is inherently numeric
private val UpiIcon: ImageVector
    get() {
        return ImageVector.Builder(
            name = "upi",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                fill = androidx.compose.ui.graphics.SolidColor(FlowpayAccentGreen),
                fillAlpha = 1f,
                stroke = null,
                strokeAlpha = 1f,
                strokeLineWidth = 1f,
                strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
                strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round,
                strokeLineMiter = 1f,
                pathFillType = androidx.compose.ui.graphics.PathFillType.NonZero
            ) {
                moveTo(6f, 8f)
                verticalLineTo(16f)
                curveTo(6f, 18.2f, 7.8f, 20f, 10f, 20f)
                horizontalLineTo(14f)
                curveTo(16.2f, 20f, 18f, 18.2f, 18f, 16f)
                verticalLineTo(8f)
                moveTo(8f, 8f)
                verticalLineTo(16f)
                curveTo(8f, 17.1f, 8.9f, 18f, 10f, 18f)
                horizontalLineTo(14f)
                curveTo(15.1f, 18f, 16f, 17.1f, 16f, 16f)
                verticalLineTo(8f)
                moveTo(10f, 12f)
                horizontalLineTo(14f)
                moveTo(10f, 14f)
                horizontalLineTo(14f)
            }
        }.build()
    }
