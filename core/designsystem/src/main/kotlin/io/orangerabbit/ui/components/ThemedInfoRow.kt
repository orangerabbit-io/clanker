package io.orangerabbit.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.orangerabbit.ui.effects.GlitchText
import io.orangerabbit.ui.theme.AppTheme

@Composable
fun ThemedInfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.tertiary,
) {
    val displayLabel = label.uppercase()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = displayLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // The value decrypts on appear and glitches periodically when FX are on,
        // and renders as a plain readout when they're off.
        GlitchText(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            color = valueColor,
        )
    }
}

@Preview
@Composable
private fun ThemedInfoRowPreview() {
    AppTheme {
        Surface {
            ThemedInfoRow(
                label = "version",
                value = "1.0.0",
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}
