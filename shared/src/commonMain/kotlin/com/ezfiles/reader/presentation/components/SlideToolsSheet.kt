package com.ezfiles.reader.presentation.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ezfiles.reader.presentation.theme.EzColors
import com.ezfiles.reader.presentation.theme.EzIcons
import com.ezfiles.reader.presentation.theme.EzShapes
import com.ezfiles.reader.presentation.theme.glassPanel

@Composable
fun SlideToolsSheet(
    slideCount: Int,
    saving: Boolean,
    onSave: (keepIndices: List<Int>) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val slides = remember(slideCount) {
        mutableStateListOf(*Array(slideCount) { it })
    }
    var changed by remember { mutableStateOf(false) }

    Column(
        modifier
            .fillMaxWidth()
            .glassPanel(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Manage Slides",
                style = MaterialTheme.typography.titleMedium,
                color = EzColors.Text,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Done",
                color = EzColors.Accent,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable(enabled = !saving) { onDismiss() }.padding(8.dp),
            )
        }

        Text(
            "${slides.size} slide${if (slides.size != 1) "s" else ""}",
            fontSize = 13.sp,
            color = EzColors.TextSecondary,
        )

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f, fill = false),
        ) {
            itemsIndexed(slides, key = { _, original -> original }) { index, originalIndex ->
                SlideRow(
                    position = index + 1,
                    originalNumber = originalIndex + 1,
                    canMoveUp = index > 0 && !saving,
                    canMoveDown = index < slides.lastIndex && !saving,
                    canDelete = slides.size > 1 && !saving,
                    onMoveUp = {
                        val item = slides.removeAt(index)
                        slides.add(index - 1, item)
                        changed = true
                    },
                    onMoveDown = {
                        val item = slides.removeAt(index)
                        slides.add(index + 1, item)
                        changed = true
                    },
                    onDelete = {
                        slides.removeAt(index)
                        changed = true
                    },
                )
            }
        }

        val enabled = changed && !saving && slides.isNotEmpty()
        Box(
            Modifier
                .fillMaxWidth()
                .clip(EzShapes.Pill)
                .background(if (enabled) EzColors.Accent else EzColors.Accent.copy(alpha = 0.3f))
                .clickable(enabled = enabled) { onSave(slides.toList()) }
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (saving) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            } else {
                Text("Save As", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun SlideRow(
    position: Int,
    originalNumber: Int,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    canDelete: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(EzColors.BackgroundSecondary.copy(alpha = 0.6f))
            .border(0.5.dp, EzColors.Hairline, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Slide $originalNumber",
            fontSize = 15.sp,
            color = EzColors.Text,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SmallButton(EzIcons.ChevronUp, "Move up", canMoveUp, onMoveUp)
            SmallButton(EzIcons.ChevronDown, "Move down", canMoveDown, onMoveDown)
            SmallButton(EzIcons.Delete, "Delete", canDelete, onDelete, tint = Color(0xFFFF5969))
        }
    }
}

@Composable
private fun SmallButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    tint: Color = EzColors.Text,
) {
    val color by animateColorAsState(if (enabled) tint else tint.copy(alpha = 0.3f))
    Box(
        Modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = color, modifier = Modifier.size(18.dp))
    }
}
