package com.ezfiles.reader.presentation.tidy

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ezfiles.reader.application.SensitiveCheck
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.domain.SensitiveFindings
import com.ezfiles.reader.platform.SecureWindow
import com.ezfiles.reader.presentation.components.BarIconButton
import com.ezfiles.reader.presentation.components.EzTextButton
import com.ezfiles.reader.presentation.theme.EzColors
import com.ezfiles.reader.presentation.theme.EzIcons
import com.ezfiles.reader.presentation.theme.EzMotion
import com.ezfiles.reader.presentation.theme.EzShapes
import com.ezfiles.reader.presentation.theme.glass
import com.ezfiles.reader.presentation.theme.rememberEzHaptics
import kotlinx.coroutines.delay

/** The reader's sensitive-number check; `null` where the app has none (previews, iOS). */
val LocalSensitiveCheck = staticCompositionLocalOf<SensitiveCheck?> { null }

/**
 * A small glass hint over an open document that shows a card, ID or bank
 * account number, offering to keep it out of screenshots while it is open.
 * The check starts only after the document has been on screen for a moment
 * and runs in the background, so opening is never slower; the numbers found
 * are never shown.
 */
@Composable
fun SensitiveFileHint(ref: DocumentRef?, visible: Boolean, modifier: Modifier = Modifier) {
    val check = LocalSensitiveCheck.current ?: return
    if (ref == null) return
    var findings by remember(ref.identity) { mutableStateOf<SensitiveFindings?>(null) }
    var dismissed by remember(ref.identity) { mutableStateOf(false) }
    var secure by remember(ref.identity) { mutableStateOf(false) }
    val haptics = rememberEzHaptics()
    LaunchedEffect(ref.identity) {
        // Let the document draw and settle first.
        delay(CHECK_DELAY_MS)
        findings = check.check(ref)
    }
    LaunchedEffect(secure) {
        if (secure) {
            delay(CONFIRMATION_MS)
            dismissed = true
        }
    }
    // Stays on for as long as this file is open, after the hint has gone.
    SecureWindow(secure)
    val found = findings
    AnimatedVisibility(
        visible = visible && !dismissed && found?.any == true,
        enter = slideInVertically(EzMotion.defaultSpatial()) { -it / 2 } + fadeIn(EzMotion.defaultEffects()),
        exit = slideOutVertically(EzMotion.defaultSpatial()) { -it / 2 } + fadeOut(EzMotion.defaultEffects()),
        modifier = modifier,
    ) {
        Column(
            Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                // Frosted like the sheets: a near-solid base so the document never shows through the words.
                .clip(EzShapes.Medium)
                .background(Brush.verticalGradient(listOf(EzColors.SheetSurface.copy(alpha = 0.94f), EzColors.DialogSurface.copy(alpha = 0.97f))))
                .glass(EzShapes.Medium, 0.35f)
                .padding(start = 14.dp, top = 6.dp, bottom = 4.dp, end = 4.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(TidyIcons.Shield, null, tint = EzColors.Primary, modifier = Modifier.size(22.dp))
                Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                    Text(
                        "This file shows ${found?.describe().orEmpty()}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = EzColors.Text,
                    )
                    Text(
                        if (secure) "Screenshots are off while it's open." else "Keep it out of screenshots and the recent apps view?",
                        style = MaterialTheme.typography.bodySmall,
                        color = EzColors.TextSecondary,
                    )
                }
                BarIconButton(EzIcons.Close, "Dismiss", { dismissed = true }, tint = EzColors.TextSecondary)
            }
            if (!secure) {
                EzTextButton(
                    "Hide from screenshots",
                    onClick = {
                        secure = true
                        haptics.toggle(true)
                    },
                    modifier = Modifier.align(Alignment.End).padding(end = 8.dp),
                )
            }
        }
    }
}

private const val CHECK_DELAY_MS = 1_200L
private const val CONFIRMATION_MS = 2_500L
