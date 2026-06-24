package io.orangerabbit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.orangerabbit.ui.effects.GlitchText
import io.orangerabbit.ui.theme.AppTheme

@Composable
fun ThemedSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    accentColor: Color = MaterialTheme.colorScheme.primary,
) {
    // Tight letter-spacing keeps the "//" prefix close to the title.
    val titleStyle = MaterialTheme.typography.labelLarge.copy(
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.5.sp,
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlitchText(
            text = "//${title.uppercase()}",
            color = accentColor,
            style = titleStyle,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Box(
            modifier = Modifier
                .height(1.dp)
                .weight(1f)
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(accentColor, Color.Transparent),
                    )
                )
        )
    }
}

@Preview
@Composable
private fun ThemedSectionHeaderPreview() {
    AppTheme {
        Surface {
            ThemedSectionHeader(title = "system", modifier = Modifier.padding(16.dp))
        }
    }
}
