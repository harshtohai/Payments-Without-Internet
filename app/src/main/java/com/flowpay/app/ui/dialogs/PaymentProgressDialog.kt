// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Flowpay

package com.flowpay.app.ui.dialogs

import androidx.annotation.StringRes
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.flowpay.app.R
import com.flowpay.app.ui.theme.FlowpayDarkGray
import com.flowpay.app.ui.theme.FlowpayLightGray
import com.flowpay.app.ui.theme.FlowpayOutlineGray
import com.flowpay.app.ui.theme.FlowpayTextGray
import com.flowpay.app.ui.theme.FlowpayTextLightGray
import com.flowpay.app.ui.theme.FlowpayTextPale
import com.flowpay.app.ui.theme.LocalFlowpayAccentTheme
import kotlinx.coroutines.delay

/**
 * Setup/progress dialog for a payment rail ([PaymentRail.Ussd],
 * [PaymentRail.Upi123]).
 *
 * Merges the former Ussd/Upi123 twins, which shared their pulse animation,
 * skeleton, two-button configuration branch and 3-second shortcut reveal;
 * everything that differed lives on [rail]. Zero behavior change: each rail
 * renders exactly what its former dedicated dialog rendered.
 *
 * The rail's accent is routed through the theme seam: a rail with its own
 * [PaymentRail.accentTheme] (UPI 123's green branding) provides it for the
 * content, one without (USSD) inherits the ambient theme, and every accent
 * usage inside reads [LocalFlowpayAccentTheme] — so rail branding can be
 * re-themed without touching this file.
 *
 * [progressMessage] overrides the rail's default progress line (USSD shows a
 * live, caller-supplied message); [onDoesNotWork] is USSD-only — rails
 * without that shortcut never render the button.
 */
@Suppress("LongParameterList") // exact union of the two merged twins' parameters
@Composable
fun PaymentProgressDialog(
    rail: PaymentRail,
    isVisible: Boolean,
    progressMessage: String? = null,
    showConfigurationOptions: Boolean = false,
    onConfigured: () -> Unit = {},
    onNotConfigured: () -> Unit = {},
    onDismiss: () -> Unit = {},
    onDoesNotWork: () -> Unit = {}
) {
    if (isVisible) {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false
            )
        ) {
            val themedContent: @Composable () -> Unit = {
                PaymentProgressDialogContent(
                    rail = rail,
                    progressMessage = progressMessage,
                    showConfigurationOptions = showConfigurationOptions,
                    onConfigured = onConfigured,
                    onNotConfigured = onNotConfigured,
                    onDismiss = onDismiss,
                    onDoesNotWork = onDoesNotWork
                )
            }
            val accentTheme = rail.accentTheme
            if (accentTheme != null) {
                CompositionLocalProvider(LocalFlowpayAccentTheme provides accentTheme) {
                    themedContent()
                }
            } else {
                themedContent()
            }
        }
    }
}

/**
 * The per-rail copy of [PaymentProgressDialog]. The two rails' dialogs are
 * otherwise identical, so the merged skeleton switches on the rail exactly
 * once, here.
 */
private data class RailCopy(
    @StringRes val iconContentDescription: Int,
    @StringRes val closeContentDescription: Int,
    @StringRes val settingUpTitle: Int,
    @StringRes val settingUpBody: Int,
    @StringRes val setupCompleteTitle: Int,
    @StringRes val setupCompleteBody: Int,
    @StringRes val defaultProgressMessage: Int,
    @StringRes val notYetLabel: Int,
    @StringRes val yesLabel: Int,
    @StringRes val alreadySetUpLabel: Int
)

private fun PaymentRail.railCopy(): RailCopy = when (this) {
    PaymentRail.Ussd -> RailCopy(
        iconContentDescription = R.string.ussd_dlg_cd_ussd_setup,
        closeContentDescription = R.string.ussd_dlg_cd_close,
        settingUpTitle = R.string.ussd_dlg_setting_up_title,
        settingUpBody = R.string.ussd_dlg_setting_up_body,
        setupCompleteTitle = R.string.ussd_dlg_setup_complete_title,
        setupCompleteBody = R.string.ussd_dlg_setup_complete_body,
        defaultProgressMessage = R.string.ussd_dlg_initializing,
        notYetLabel = R.string.ussd_dlg_not_yet,
        yesLabel = R.string.ussd_dlg_yes,
        alreadySetUpLabel = R.string.ussd_dlg_already_set_up
    )

    PaymentRail.Upi123 -> RailCopy(
        iconContentDescription = R.string.upi123_dlg_setup_icon_desc,
        closeContentDescription = R.string.upi123_dlg_close,
        settingUpTitle = R.string.upi123_dlg_setting_up_title,
        settingUpBody = R.string.upi123_dlg_ivr_triggered_message,
        setupCompleteTitle = R.string.upi123_dlg_setup_complete_title,
        setupCompleteBody = R.string.upi123_dlg_setup_complete_message,
        defaultProgressMessage = R.string.upi123_dlg_configuring,
        notYetLabel = R.string.upi123_dlg_not_yet,
        yesLabel = R.string.upi123_dlg_yes,
        alreadySetUpLabel = R.string.upi123_dlg_already_set_up
    )
}

// Verbatim merge of the two former twins: every line reproduces one of them
// exactly, including their one remaining pixel delta (noted below).
// Splitting it back into sub-composables would just re-create the twin
// split this file exists to delete.
@Suppress("LongMethod", "LongParameterList")
@Composable
private fun PaymentProgressDialogContent(
    rail: PaymentRail,
    progressMessage: String?,
    showConfigurationOptions: Boolean,
    onConfigured: () -> Unit,
    onNotConfigured: () -> Unit,
    onDismiss: () -> Unit,
    onDoesNotWork: () -> Unit
) {
    val copy = rail.railCopy()
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = FlowpayDarkGray),
        border = BorderStroke(1.dp, FlowpayLightGray)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp)
                    .padding(top = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .background(
                            color = LocalFlowpayAccentTheme.current.accent.copy(alpha = alpha * 0.2f),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = rail.icon,
                        contentDescription = stringResource(copy.iconContentDescription),
                        tint = LocalFlowpayAccentTheme.current.accent,
                        modifier = Modifier.size(48.dp)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = if (showConfigurationOptions) {
                        stringResource(copy.setupCompleteTitle)
                    } else {
                        stringResource(copy.settingUpTitle)
                    },
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = if (showConfigurationOptions) {
                        stringResource(copy.setupCompleteBody)
                    } else {
                        stringResource(copy.settingUpBody)
                    },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = FlowpayTextLightGray,
                    textAlign = TextAlign.Center,
                    lineHeight = 24.sp
                )

                Spacer(modifier = Modifier.height(32.dp))

                if (showConfigurationOptions) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Button(
                            onClick = onNotConfigured,
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp),
                            shape = RoundedCornerShape(15.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = FlowpayLightGray
                            )
                        ) {
                            Text(
                                text = stringResource(copy.notYetLabel),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                                textAlign = TextAlign.Center
                            )
                        }

                        Button(
                            onClick = onConfigured,
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp),
                            shape = RoundedCornerShape(15.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = LocalFlowpayAccentTheme.current.accent
                            )
                        ) {
                            Text(
                                text = stringResource(copy.yesLabel),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp),
                        color = LocalFlowpayAccentTheme.current.accent,
                        trackColor = FlowpayLightGray
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = progressMessage ?: stringResource(copy.defaultProgressMessage),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Light,
                        color = FlowpayTextGray.copy(alpha = alpha),
                        textAlign = TextAlign.Center
                    )

                    // After a short delay, offer a shortcut to users who
                    // already have this rail set up: confirm without waiting
                    // for the whole call flow — and, on USSD only, report it
                    // isn't working. Delayed so neither tempts an action
                    // before the rail has had time to respond. Neither
                    // shortcut touches the ongoing call.
                    var showShortcuts by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) {
                        delay(3000)
                        showShortcuts = true
                    }
                    if (showShortcuts) {
                        Spacer(modifier = Modifier.height(20.dp))

                        // The twins' only pixel deltas, preserved exactly:
                        // UPI 123 drew this button 50dp tall with centered
                        // text, USSD 48dp with start-aligned text.
                        OutlinedButton(
                            onClick = onConfigured,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(if (rail == PaymentRail.Upi123) 50.dp else 48.dp),
                            shape = RoundedCornerShape(15.dp),
                            border = BorderStroke(1.dp, LocalFlowpayAccentTheme.current.accent),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = LocalFlowpayAccentTheme.current.accent
                            )
                        ) {
                            Text(
                                text = stringResource(copy.alreadySetUpLabel),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = if (rail == PaymentRail.Upi123) TextAlign.Center else null
                            )
                        }

                        if (rail == PaymentRail.Ussd) {
                            Spacer(modifier = Modifier.height(12.dp))

                            OutlinedButton(
                                onClick = onDoesNotWork,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                                shape = RoundedCornerShape(15.dp),
                                border = BorderStroke(1.dp, FlowpayOutlineGray),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = FlowpayTextPale
                                )
                            ) {
                                Text(
                                    text = stringResource(R.string.ussd_dlg_does_not_work),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }

            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(copy.closeContentDescription),
                    tint = FlowpayTextLightGray,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}
