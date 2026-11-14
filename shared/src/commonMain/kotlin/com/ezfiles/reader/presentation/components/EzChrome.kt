package com.ezfiles.reader.presentation.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import com.ezfiles.reader.platform.PlatformBackHandler
import com.ezfiles.reader.presentation.theme.EzMotion
import com.ezfiles.reader.presentation.theme.pressScale
import com.ezfiles.reader.presentation.theme.appSafeArea
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ezfiles.reader.presentation.theme.EzColors
import com.ezfiles.reader.presentation.theme.EzIcons
import com.ezfiles.reader.presentation.theme.EzShapes
import com.ezfiles.reader.presentation.theme.EzSpacing
import com.ezfiles.reader.presentation.theme.glass
import com.ezfiles.reader.presentation.theme.glassBar
import com.ezfiles.reader.presentation.theme.floatingGlass
import com.ezfiles.reader.presentation.theme.glassPanel
import com.ezfiles.reader.presentation.theme.rememberEzHaptics
import com.ezfiles.reader.presentation.theme.rememberHapticActivation
import com.ezfiles.reader.presentation.theme.selectionPop

/*
 * The app's chrome: bars, dialogs, sheets, menus, buttons and status panels.
 * Screens build their controls from these, so every screen shares one look.
 *
 * Button hierarchy: one PrimaryActionButton (red gradient) per screen for the
 * main action, SecondaryActionButton (glass) for the next most likely one,
 * EzTextButton for the rest (Cancel, Start over, Change).
 */

/**
 * The glass top bar: back, a one-line title (with an optional line beneath),
 * then [actions]. Its icon buttons are [BarIconButton]s.
 */
@Composable
fun EzTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    backLabel: String = "Back",
    gutter: Dp = EzSpacing.Gutter,
    subtitle: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val compact = isShortWindow()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = gutter, vertical = if (compact) 4.dp else 8.dp)
            .glassBar()
            .padding(if (compact) 4.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarIconButton(EzIcons.Back, backLabel, onBack)
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = EzColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            subtitle?.invoke()
        }
        actions()
    }
}

/** A tertiary action: red text, no container. */
@Composable
fun EzTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = EzColors.Accent,
) {
    val activate = rememberHapticActivation(onClick)
    TextButton(onClick = activate, enabled = enabled, modifier = modifier.heightIn(min = EzSpacing.Touch)) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) color else EzColors.TextSecondary.copy(alpha = 0.6f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A glass dialog with the slots of Material's AlertDialog. The text slot
 * shrinks and scrolls when it would push the buttons off a short screen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EzDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
    scrollBody: Boolean = true,
) {
    Dialog(onDismissRequest = onDismissRequest, properties = properties) {
        Column(
            modifier
                .widthIn(min = 280.dp, max = 560.dp)
                .glassPanel(EzShapes.Dialog)
                .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 12.dp),
        ) {
            if (title != null) {
                CompositionLocalProvider(LocalContentColor provides EzColors.Text) {
                    ProvideTextStyle(MaterialTheme.typography.titleLarge) {
                        Box(Modifier.padding(bottom = 12.dp).semantics { heading() }) { title() }
                    }
                }
            }
            if (text != null) {
                CompositionLocalProvider(LocalContentColor provides EzColors.TextSecondary) {
                    ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                        Box(Modifier.weight(1f, fill = false).then(if (scrollBody) Modifier.verticalScroll(rememberScrollState()) else Modifier)) { text() }
                    }
                }
            }
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                verticalArrangement = Arrangement.Center,
            ) {
                dismissButton?.invoke()
                confirmButton()
            }
        }
    }
}

/** A glass bottom sheet with a drag handle and an optional title. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EzSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(),
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        shape = EzShapes.Sheet,
        containerColor = Color.Transparent,
        contentColor = EzColors.Text,
        tonalElevation = 0.dp,
        scrimColor = EzColors.Scrim.copy(alpha = 0.22f),
        dragHandle = null,
    ) {
        // Frosted glass: a mostly solid tint, so rows stay readable over any screen,
        // with the same glossy fill, glow and bright edge as every other glass panel.
        // Near-solid: over a bright page even 10% show-through makes the page's words readable behind the rows.
        val frost = Brush.verticalGradient(listOf(EzColors.SheetSurface.copy(alpha = 0.97f), EzColors.DialogSurface.copy(alpha = 0.985f)))
        Column(Modifier.fillMaxWidth().background(frost, EzShapes.Sheet).glass(EzShapes.Sheet, 0.35f).appSafeArea()) {
            SheetHandle(Modifier.align(Alignment.CenterHorizontally))
            if (title != null) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    color = EzColors.Text,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp).semantics { heading() },
                )
            }
            content()
        }
    }
}

@Composable
private fun SheetHandle(modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(top = 12.dp, bottom = 14.dp)
            .size(width = 40.dp, height = 4.dp)
            .background(EzColors.TextSecondary.copy(alpha = 0.35f), EzShapes.Pill),
    )
}

/** A glass dropdown menu; fill it with [EzMenuItem]s. */
@Composable
fun EzDropdownMenu(expanded: Boolean, onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        shape = EzShapes.Menu,
        containerColor = Color.Transparent,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier = Modifier.glassPanel(EzShapes.Menu),
        content = content,
    )
}

/** A menu entry with an optional leading icon; [destructive] entries are red. */
@Composable
fun EzMenuItem(label: String, onClick: () -> Unit, icon: ImageVector? = null, enabled: Boolean = true, destructive: Boolean = false) {
    val activate = rememberHapticActivation(onClick)
    val color = when {
        !enabled -> EzColors.TextSecondary.copy(alpha = 0.6f)
        destructive -> EzColors.Accent
        else -> EzColors.Text
    }
    DropdownMenuItem(
        text = { Text(label, style = MaterialTheme.typography.bodyLarge, color = color) },
        onClick = activate,
        enabled = enabled,
        leadingIcon = if (icon == null) null else ({ Icon(icon, null, tint = color, modifier = Modifier.size(EzSpacing.BarIcon)) }),
        modifier = Modifier.heightIn(min = EzSpacing.Touch),
    )
}

/** A centred glass card for loading, empty, error and finished states. Scrolls on short screens. */
@Composable
fun EzStatusCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier.fillMaxSize().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .glass(EzShapes.Large, 0.6f)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

/** The heading and explanation of a status card. */
@Composable
fun EzStatusText(title: String, body: String? = null, icon: ImageVector? = null, iconTint: Color = EzColors.Primary) {
    if (icon != null) Icon(icon, null, tint = iconTint, modifier = Modifier.size(36.dp))
    Text(
        title,
        style = MaterialTheme.typography.titleLarge,
        color = EzColors.Text,
        textAlign = TextAlign.Center,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
    if (body != null) {
        Text(body, style = MaterialTheme.typography.bodyMedium, color = EzColors.TextSecondary, textAlign = TextAlign.Center)
    }
}

/** Waiting for something the user can stop. */
@Composable
fun EzProgressCard(label: String, onCancel: (() -> Unit)?, modifier: Modifier = Modifier) {
    EzStatusCard(modifier) {
        CircularProgressIndicator(color = EzColors.Primary)
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = EzColors.Text,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (onCancel != null) EzTextButton("Cancel", onCancel)
    }
}

/** The glass panel at the bottom of a tool, holding its progress or its actions. */
@Composable
fun EzActionPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 8.dp).floatingGlass(EzShapes.Bar).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/** A label, Cancel, and a bar: determinate when [progress] is known. */
@Composable
fun EzProgressRow(label: String, progress: Float?, onCancel: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = EzColors.Text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (onCancel != null) EzTextButton("Cancel", onCancel)
    }
    if (progress != null) {
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
            color = EzColors.Primary,
            trackColor = EzColors.Hairline,
        )
    } else {
        LinearProgressIndicator(Modifier.fillMaxWidth(), color = EzColors.Primary, trackColor = EzColors.Hairline)
    }
}

/** A note across the top of a screen, with a close button when it can be dismissed. */
@Composable
fun EzNotice(text: String, onDismiss: (() -> Unit)?, modifier: Modifier = Modifier, warning: Boolean = false) {
    Row(
        modifier
            .fillMaxWidth()
            .glass(EzShapes.Medium, 0.8f)
            .padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = if (onDismiss != null) 6.dp else 16.dp)
            .heightIn(min = 40.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (warning) EzColors.Accent else EzColors.Text,
            modifier = Modifier.weight(1f),
        )
        if (onDismiss != null) BarIconButton(EzIcons.Close, "Dismiss", onDismiss, tint = EzColors.TextSecondary)
    }
}

/** A section heading over a group of cards or settings, with an optional trailing control. */
@Composable
fun EzSectionTitle(title: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = EzColors.Text,
            modifier = Modifier.semantics { heading() },
        )
        trailing()
    }
}

/** The small label over one setting's controls. */
@Composable
fun EzSettingLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = EzColors.TextSecondary, modifier = modifier.semantics { heading() })
}

/** Blocks the screen while a save that can't be interrupted runs. */
@Composable
fun EzBlockingProgress(label: String) {
    Dialog(onDismissRequest = {}) {
        Row(
            Modifier.glassPanel(EzShapes.Dialog).padding(24.dp).semantics { liveRegion = LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(color = EzColors.Primary)
            Text(label, style = MaterialTheme.typography.bodyLarge, color = EzColors.Text)
        }
    }
}

/** One destination of [EzNavigationBar]. */
class EzNavItem(val label: String, val icon: ImageVector, val selected: Boolean, val onClick: () -> Unit)

/**
 * The glass bottom navigation bar of the home screen: icon over label, the
 * selected destination in a red-tinted pill.
 */
@Composable
fun EzNavigationBar(items: List<EzNavItem>, modifier: Modifier = Modifier) {
    val haptics = rememberEzHaptics()
    val selectedIndex = items.indexOfFirst { it.selected }
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .floatingGlass(EzShapes.Bar)
            .padding(horizontal = 6.dp, vertical = 6.dp),
    ) {
        val itemWidth = maxWidth / items.size.coerceAtLeast(1)
        // The red pill slides to the chosen tab on an expressive spring rather than jumping.
        val pillOffset by animateDpAsState(itemWidth * selectedIndex.coerceAtLeast(0), EzMotion.defaultSpatial(), label = "navPill")
        if (selectedIndex >= 0) {
            Box(Modifier.matchParentSize()) {
                Box(
                    Modifier
                        .offset(x = pillOffset)
                        .width(itemWidth)
                        .fillMaxHeight()
                        .background(SelectedPill(), EzShapes.Large),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            for (item in items) {
                val interaction = remember { MutableInteractionSource() }
                val tint by animateColorAsState(if (item.selected) EzColors.Accent else EzColors.Text, EzMotion.defaultEffects(), label = "navTint")
                Column(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 56.dp)
                        .pressScale(interaction, 0.92f)
                        .clip(EzShapes.Large)
                        .clickable(interaction, indication = null, role = Role.Tab) {
                            if (!item.selected) haptics.navigate()
                            item.onClick()
                        }
                        .semantics { selected = item.selected }
                        .padding(vertical = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
                ) {
                    Icon(item.icon, null, tint = tint, modifier = Modifier.size(EzSpacing.BarIcon).selectionPop(item.selected, dip = 0.8f))
                    Text(
                        item.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (item.selected) FontWeight.Bold else FontWeight.Medium,
                        color = tint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** A setting that is on or off: title, a line of explanation and a switch, all one touch target. */
@Composable
fun EzSwitchRow(title: String, body: String?, checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberEzHaptics()
    val change = { on: Boolean ->
        // One tick for the change itself, so the user feels what they chose (turning off is the last one).
        haptics.toggle(on)
        onCheckedChange(on)
    }
    Row(
        modifier
            .fillMaxWidth()
            .clip(EzShapes.Field)
            .toggleable(value = checked, role = Role.Switch, onValueChange = change)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = EzColors.Text)
            if (body != null) Text(body, style = MaterialTheme.typography.bodySmall, color = EzColors.TextSecondary)
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = EzColors.OnPrimary,
                checkedTrackColor = EzColors.Primary,
                checkedBorderColor = EzColors.Primary,
                uncheckedThumbColor = EzColors.TextSecondary,
                uncheckedTrackColor = EzColors.SliderTrack,
                uncheckedBorderColor = EzColors.FieldOutline,
            ),
        )
    }
}

/** The red tint behind the selected tab, lighter at the top like the glass. */
@Composable
private fun SelectedPill(): Brush {
    val selected = EzColors.Selected
    return remember(selected) { Brush.verticalGradient(listOf(selected, selected.copy(alpha = 0.7f))) }
}

/**
 * A glass panel that slides in from the start edge over a dimmed screen.
 * Tapping outside, or Back, calls [onDismiss].
 */
@Composable
fun EzSideDrawer(visible: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    PlatformBackHandler(enabled = visible, onBack = onDismiss)
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(DrawerScrim)
                    .clickable(interactionSource = null, indication = null, onClickLabel = "Close menu", onClick = onDismiss),
            )
        }
        AnimatedVisibility(
            visible,
            enter = slideInHorizontally(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)) { -it },
            exit = slideOutHorizontally { -it },
        ) {
            Column(
                Modifier
                    .fillMaxHeight()
                    .widthIn(max = 320.dp)
                    .fillMaxWidth(0.84f)
                    .glassPanel(EzShapes.Drawer)
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 16.dp),
                content = content,
            )
        }
    }
}

private val DrawerScrim = EzColors.Scrim.copy(alpha = 0.32f)

/** A heading inside a drawer or sheet. */
@Composable
fun EzDrawerHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = EzColors.TextSecondary,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 6.dp).semantics { heading() },
    )
}

/** A tappable row in a drawer: icon and label. */
@Composable
fun EzDrawerItem(label: String, icon: ImageVector, onClick: () -> Unit) {
    val activate = rememberHapticActivation(onClick)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(horizontal = 12.dp)
            .clip(EzShapes.Medium)
            .clickable(role = Role.Button, onClick = activate)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, null, tint = EzColors.Accent, modifier = Modifier.size(EzSpacing.BarIcon))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = EzColors.Text)
    }
}
