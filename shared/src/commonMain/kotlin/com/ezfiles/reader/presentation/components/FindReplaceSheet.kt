package com.ezfiles.reader.presentation.components

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ezfiles.reader.presentation.theme.EzColors
import com.ezfiles.reader.presentation.theme.EzShapes
import com.ezfiles.reader.presentation.theme.glassPanel

@Composable
fun FindReplaceSheet(
    onReplace: (find: String, replace: String, caseSensitive: Boolean) -> Unit,
    onDismiss: () -> Unit,
    matchCount: Int? = null,
    replacing: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var findText by remember { mutableStateOf("") }
    var replaceText by remember { mutableStateOf("") }
    var caseSensitive by remember { mutableStateOf(false) }

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
                "Find & Replace",
                style = MaterialTheme.typography.titleMedium,
                color = EzColors.Text,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Done",
                color = EzColors.Accent,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable(enabled = !replacing) { onDismiss() }.padding(8.dp),
            )
        }

        GlassTextField(value = findText, onValueChange = { findText = it }, placeholder = "Find text…", enabled = !replacing)
        GlassTextField(value = replaceText, onValueChange = { replaceText = it }, placeholder = "Replace with…", enabled = !replacing)

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Aa",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (caseSensitive) EzColors.Accent else EzColors.TextSecondary,
                    modifier = Modifier
                        .clip(EzShapes.Pill)
                        .then(if (caseSensitive) Modifier.background(EzColors.Accent.copy(alpha = 0.15f)) else Modifier)
                        .clickable(enabled = !replacing) { caseSensitive = !caseSensitive }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
                Text("Case sensitive", fontSize = 13.sp, color = EzColors.TextSecondary)
            }
            if (matchCount != null) {
                Text(
                    if (matchCount == 0) "No matches" else "$matchCount match${if (matchCount != 1) "es" else ""}",
                    fontSize = 13.sp,
                    color = if (matchCount == 0) EzColors.TextSecondary else EzColors.Accent,
                )
            }
        }

        val enabled = findText.isNotEmpty() && !replacing
        Box(
            Modifier
                .fillMaxWidth()
                .clip(EzShapes.Pill)
                .background(if (enabled) EzColors.Accent else EzColors.Accent.copy(alpha = 0.3f))
                .clickable(enabled = enabled) { onReplace(findText, replaceText, caseSensitive) }
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (replacing) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            } else {
                Text("Replace All & Save As", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun GlassTextField(value: String, onValueChange: (String) -> Unit, placeholder: String, enabled: Boolean = true) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = TextStyle(fontSize = 15.sp, color = EzColors.Text),
        singleLine = true,
        enabled = enabled,
        cursorBrush = SolidColor(EzColors.Primary),
        decorationBox = { innerTextField ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(EzColors.BackgroundSecondary.copy(alpha = 0.6f))
                    .border(0.5.dp, EzColors.Hairline, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                if (value.isEmpty()) Text(placeholder, fontSize = 15.sp, color = EzColors.TextSecondary)
                innerTextField()
            }
        },
    )
}
