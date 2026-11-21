package com.ezfiles.reader.presentation.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ezfiles.reader.presentation.components.EzNavItem
import com.ezfiles.reader.presentation.components.GlassIconButton
import com.ezfiles.reader.presentation.theme.EzColors
import com.ezfiles.reader.presentation.theme.EzIcons
import com.ezfiles.reader.presentation.theme.EzShapes
import com.ezfiles.reader.presentation.theme.floatingGlass
import com.ezfiles.reader.presentation.theme.pressScale
import com.ezfiles.reader.presentation.theme.rememberEzHaptics

/** Side navigation uses width rather than consuming the limited height of a landscape phone. */
@Composable
internal fun LandscapeLibraryRail(items: List<EzNavItem>, onOpenFile: () -> Unit, onOpenAbout: (() -> Unit)?, modifier: Modifier = Modifier) {
    val haptics = rememberEzHaptics()
    Box(modifier.fillMaxHeight().width(112.dp).padding(4.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.width(80.dp).floatingGlass(EzShapes.Bar, 0.7f).padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                for (item in items) {
                    val interaction = remember { MutableInteractionSource() }
                    Column(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).pressScale(interaction)
                            .clip(EzShapes.Medium)
                            .then(if (item.selected) Modifier.background(EzColors.Primary.copy(alpha = 0.14f)) else Modifier)
                            .clickable(interaction, indication = null, role = Role.Tab) {
                                if (!item.selected) haptics.navigate()
                                item.onClick()
                            }
                            .semantics { selected = item.selected }
                            .padding(horizontal = 2.dp, vertical = 5.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Icon(item.icon, null, tint = if (item.selected) EzColors.Accent else EzColors.Text, modifier = Modifier.size(22.dp))
                        Text(item.label, color = EzColors.Text, fontSize = 11.sp, textAlign = TextAlign.Center)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassIconButton(EzIcons.Folder, "Open file", onOpenFile)
                if (onOpenAbout != null) GlassIconButton(EzIcons.Info, "About Xfiles", onOpenAbout)
            }
        }
    }
}
