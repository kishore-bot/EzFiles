package com.ezfiles.reader.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ezfiles.reader.presentation.library.FileType
import com.ezfiles.reader.presentation.theme.EzBadgeColors
import com.ezfiles.reader.presentation.theme.EzColors
import com.ezfiles.reader.presentation.theme.EzIcons
import com.ezfiles.reader.presentation.theme.EzShapes
import com.ezfiles.reader.presentation.theme.EzSpacing
import com.ezfiles.reader.presentation.theme.FileNameStyle
import com.ezfiles.reader.presentation.theme.glassCard
import com.ezfiles.reader.presentation.theme.glossyPrimary
import com.ezfiles.reader.presentation.theme.glass
import com.ezfiles.reader.presentation.theme.pressScale
import com.ezfiles.reader.presentation.theme.LocalHapticsEnabled
import com.ezfiles.reader.presentation.theme.rememberHapticActivation
import com.ezfiles.reader.presentation.theme.floatingGlass
import com.ezfiles.reader.presentation.theme.raised
import com.ezfiles.reader.presentation.theme.rememberEzHaptics
import com.ezfiles.reader.presentation.theme.selectionPop

/** Round glass button holding one icon. */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    tint: Color = EzColors.Text,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val activate = rememberHapticActivation(onClick)
    Box(
        modifier = modifier
            .size(size)
            .pressScale(interaction, 0.9f)
            .floatingGlass(CircleShape)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = activate),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = if (enabled) tint else tint.copy(alpha = 0.35f), modifier = Modifier.size(24.dp))
    }
}

/**
 * Plain icon button for use inside a glass bar. It's drawn at 40dp so a bar
 * of them fits a phone; Compose widens its touch area to 48dp.
 */
@Composable
fun BarIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, enabled: Boolean = true, tint: Color = EzColors.Text) {
    val interaction = remember { MutableInteractionSource() }
    val activate = rememberHapticActivation(onClick)
    Box(
        modifier = Modifier
            .size(40.dp)
            .pressScale(interaction, 0.85f)
            .clip(CircleShape)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = activate),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = if (enabled) tint else tint.copy(alpha = 0.3f), modifier = Modifier.size(EzSpacing.BarIcon))
    }
}

/** Filter chip: glass when idle, glossy red when selected. */
@Composable
fun EzChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    iconTint: Color = EzColors.Text,
) {
    val interaction = remember { MutableInteractionSource() }
    val haptics = rememberEzHaptics()
    val base = Modifier
        .heightIn(min = 40.dp)
        .pressScale(interaction)
        .selectionPop(selected)
    val surface = if (selected) base.glossyPrimary(EzShapes.Pill, 8.dp) else base.glassCard(EzShapes.Pill, 0.4f)
    Row(
        modifier = surface
            .clickable(interaction, indication = null, role = Role.Tab) {
                if (!selected) haptics.select()
                onClick()
            }
            .semantics { this.selected = selected }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (selected) EzColors.OnPrimary else iconTint, modifier = Modifier.size(17.dp))
        }
        Text(
            label,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) EzColors.OnPrimary else EzColors.Text,
            maxLines = 1,
        )
    }
}

/**
 * Coloured file-type badge: a page with a folded corner and the extension,
 * in the type's colour. The colours are the same in both themes.
 */
@Composable
fun FileTypeTile(type: FileType, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(width = 40.dp, height = 46.dp)
            .raised(BadgeShape, 4.dp, type.bottom)
            .clip(BadgeShape)
            .background(Brush.linearGradient(listOf(type.top, type.bottom)))
            .drawBehind {
                val fold = size.width * 0.32f
                drawPath(
                    Path().apply {
                        moveTo(size.width - fold, 0f)
                        lineTo(size.width, fold)
                        lineTo(size.width - fold, fold)
                        close()
                    },
                    BadgeFold,
                )
                // The gloss across the top half.
                drawRect(BadgeGloss, Offset.Zero, size.copy(height = size.height * 0.5f))
            },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Text(
            type.label,
            color = BadgeText,
            fontSize = if (type.label.length > 3) 9.sp else 10.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            modifier = Modifier.padding(bottom = 6.dp),
        )
    }
}

private val BadgeShape = RoundedCornerShape(9.dp)
private val BadgeText = EzBadgeColors.Ink
private val BadgeFold = EzBadgeColors.Ink.copy(alpha = 0.45f)
private val BadgeGloss = Brush.verticalGradient(listOf(EzBadgeColors.Ink.copy(alpha = 0.28f), EzBadgeColors.Ink.copy(alpha = 0.04f)))

/** A library entry. Long-press or the trailing button opens [menu]. */
@Composable
fun FileCard(
    type: FileType,
    name: String,
    meta: String,
    onClick: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    menu: @Composable () -> Unit = {},
) {
    val interaction = remember { MutableInteractionSource() }
    val activate = rememberHapticActivation(onClick)
    val touchFeedback = LocalHapticsEnabled.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .pressScale(interaction, 0.985f)
            .glassCard(EzShapes.Card, 0.5f)
            .combinedClickable(interaction, indication = null, role = Role.Button, onLongClick = onMore,
                hapticFeedbackEnabled = touchFeedback, onClick = activate)
            .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (leading != null) leading() else FileTypeTile(type)
        Column(Modifier.weight(1f)) {
            Text(name, style = FileNameStyle, color = EzColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                meta,
                fontSize = 12.sp,
                color = EzColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        Box {
            BarIconButton(EzIcons.More, "More options for $name", onMore, tint = EzColors.TextSecondary)
            menu()
        }
    }
}

/** The glass secondary action: the one next to the primary, or the only one when it isn't the main action. */
@Composable
fun SecondaryActionButton(
    text: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val activate = rememberHapticActivation(onClick)
    val content = if (enabled) EzColors.Text else EzColors.TextSecondary.copy(alpha = 0.6f)
    Row(
        modifier = modifier
            .heightIn(min = ActionHeight)
            .pressScale(interaction, 0.94f)
            .glass(EzShapes.Pill, 0.85f)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = activate)
            .padding(PaddingValues(start = 18.dp, end = if (icon != null) 22.dp else 18.dp, top = 12.dp, bottom = 12.dp)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, null, tint = if (enabled) EzColors.Primary else content, modifier = Modifier.size(22.dp))
        Text(text, color = content, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}

/** The screen's main action, in the red gradient. Use one per screen. */
@Composable
fun PrimaryActionButton(
    text: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val activate = rememberHapticActivation(onClick)
    val surface = if (enabled) Modifier.glossyPrimary(EzShapes.Pill, 14.dp) else Modifier.glass(EzShapes.Pill, 0.6f)
    val content = if (enabled) EzColors.OnPrimary else EzColors.TextSecondary.copy(alpha = 0.6f)
    Row(
        modifier = modifier
            .heightIn(min = ActionHeight)
            .pressScale(interaction, 0.94f)
            .then(surface)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = activate)
            .padding(PaddingValues(horizontal = if (icon != null) 24.dp else 18.dp, vertical = 12.dp)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, null, tint = content, modifier = Modifier.size(22.dp))
        Text(text, color = content, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}

/** Primary and secondary actions share one height, so they line up side by side. */
private val ActionHeight = 52.dp

/** The floating main action: a glass capsule with a red action medallion and a readable label. */
@Composable
fun EzFloatingAction(text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val activate = rememberHapticActivation(onClick)
    Row(
        modifier = modifier
            .heightIn(min = 56.dp)
            .pressScale(interaction, 0.94f)
            .floatingGlass(EzShapes.Pill, 0.65f)
            .clickable(interaction, indication = null, role = Role.Button, onClick = activate)
            .padding(start = 10.dp, end = 20.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(36.dp).glossyPrimary(CircleShape, 4.dp), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = EzColors.OnPrimary, modifier = Modifier.size(22.dp))
        }
        Text(text, color = EzColors.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * A row of mutually exclusive options in a glass pill (Preview / Source,
 * the theme setting): the selected one in red.
 */
@Composable
fun <T> EzSegmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier, translucent: Boolean = false, vertical: Boolean = false) {
    val haptics = rememberEzHaptics()
    val surface = modifier.then(if (translucent) Modifier.floatingGlass(EzShapes.Pill, 0.7f) else Modifier.glass(EzShapes.Pill, 0.8f)).padding(3.dp)
    val items: @Composable () -> Unit = {
        for (option in options) {
            val on = option == selected
            Text(
                label(option),
                style = MaterialTheme.typography.labelLarge,
                color = if (on && !translucent) EzColors.OnPrimary else EzColors.Text,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier
                    .then(if (vertical) Modifier.fillMaxWidth() else Modifier)
                    .selectionPop(on)
                    .then(if (on && !translucent) Modifier.glossyPrimary(EzShapes.Pill, 4.dp)
                        else if (on) Modifier.background(EzColors.Primary.copy(alpha = 0.12f), EzShapes.Pill).clip(EzShapes.Pill)
                        else Modifier.clip(EzShapes.Pill))
                    .heightIn(min = if (translucent) 48.dp else 0.dp)
                    .clickable(role = Role.Tab, enabled = !on) {
                        haptics.select()
                        onSelect(option)
                    }
                    .semantics { this.selected = on }
                    .padding(horizontal = 16.dp, vertical = 9.dp),
            )
        }
    }
    if (vertical) Column(surface, horizontalAlignment = Alignment.CenterHorizontally) { items() }
    else Row(surface) { items() }
}
