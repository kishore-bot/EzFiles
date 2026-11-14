package com.ezfiles.reader.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ezfiles.reader.presentation.theme.EzColors
import com.ezfiles.reader.presentation.theme.EzShapes
import com.ezfiles.reader.presentation.theme.floatingGlass
import com.ezfiles.reader.presentation.theme.rememberEzHaptics

/** A 40 dp visual pill within 48 dp touch targets; labels are centred rather than top-aligned. */
@Composable
fun FloatingReaderModeSwitch(source: Boolean, onSwitch: () -> Unit, modifier: Modifier = Modifier, previewLabel: String = "Preview") {
    val haptics = rememberEzHaptics()
    val width = 176.dp * LocalDensity.current.fontScale.coerceIn(1f, 1.6f)
    Box(modifier.width(width).height(48.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.matchParentSize().padding(vertical = 4.dp).floatingGlass(EzShapes.Pill, 0.55f))
        Row(Modifier.fillMaxSize().padding(horizontal = 3.dp)) {
            for (option in listOf(false, true)) {
                val active = source == option
                Box(
                    Modifier.weight(1f).height(48.dp)
                        .clip(EzShapes.Pill)
                        .clickable(role = Role.Tab, enabled = !active) { haptics.select(); onSwitch() }
                        .semantics { selected = active },
                    contentAlignment = Alignment.Center,
                ) {
                    if (active) Box(Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 2.dp)
                        .background(EzColors.Primary.copy(alpha = 0.15f), EzShapes.Pill))
                    Text(if (option) "Source" else previewLabel, color = EzColors.Text,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                        textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp))
                }
            }
        }
    }
}
