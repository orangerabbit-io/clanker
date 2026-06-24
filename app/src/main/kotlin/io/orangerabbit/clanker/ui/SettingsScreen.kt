package io.orangerabbit.clanker.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.orangerabbit.clanker.core.network.Capability
import io.orangerabbit.clanker.core.network.ModelInfo
import io.orangerabbit.ui.components.ThemedButton
import io.orangerabbit.ui.components.ThemedButtonAccent
import io.orangerabbit.ui.components.ThemedSectionHeader
import io.orangerabbit.ui.components.ThemedSliderRow
import io.orangerabbit.ui.effects.GlitchText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val USE_DEFAULT = "(use default)"

@Composable
fun SettingsScreen(viewModel: ChatViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val cardPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes != null) viewModel.applyCardBytes(bytes)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 12.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlitchText(
                text = "SETTINGS",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.headlineMedium,
            )
            ThemedButton(onClick = onBack, accent = ThemedButtonAccent.Neutral) {
                Text("← BACK", style = MaterialTheme.typography.labelMedium)
            }
        }

        ThemedSectionHeader(title = "uplink")
        OutlinedTextField(
            value = state.apiKey,
            onValueChange = viewModel::setApiKey,
            label = { Text("OPENROUTER KEY", style = MaterialTheme.typography.labelMedium) },
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            textStyle = MaterialTheme.typography.bodyLarge,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )

        ThemedSectionHeader(title = "models", accentColor = MaterialTheme.colorScheme.tertiary)
        ModelDropdown(
            label = "default chat model",
            selected = state.defaultChatModel,
            models = state.availableModels,
            loading = state.modelsLoading,
            onRequestModels = viewModel::loadModels,
            onSelect = viewModel::setDefaultChatModel,
        )
        ModelDropdown(
            label = "default image model",
            selected = state.defaultImageModel,
            models = state.availableModels,
            loading = state.modelsLoading,
            onRequestModels = viewModel::loadModels,
            onSelect = viewModel::setDefaultImageModel,
            imageOnly = true,
        )

        // Per-character overrides — only meaningful once a persona is loaded.
        state.character?.let { card ->
            ThemedSectionHeader(
                title = "${card.name} overrides",
                accentColor = MaterialTheme.colorScheme.secondary,
            )
            ModelDropdown(
                label = "chat model",
                selected = state.characterChatModel ?: USE_DEFAULT,
                models = state.availableModels,
                loading = state.modelsLoading,
                onRequestModels = viewModel::loadModels,
                includeDefaultOption = true,
                onSelect = { viewModel.setCharacterModels(it, state.characterImageModel) },
            )
            ModelDropdown(
                label = "image model",
                selected = state.characterImageModel ?: USE_DEFAULT,
                models = state.availableModels,
                loading = state.modelsLoading,
                onRequestModels = viewModel::loadModels,
                includeDefaultOption = true,
                imageOnly = true,
                onSelect = { viewModel.setCharacterModels(state.characterChatModel, it) },
            )
        }

        ThemedSectionHeader(title = "persona", accentColor = MaterialTheme.colorScheme.secondary)
        ThemedButton(
            onClick = { cardPicker.launch("*/*") },
            accent = ThemedButtonAccent.Secondary,
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Text(
                state.character?.let { "PERSONA: ${it.name}" } ?: "IMPORT PERSONA (PNG/JSON)",
                style = MaterialTheme.typography.labelMedium,
            )
        }

        ThemedSectionHeader(title = "fx")
        ThemedSliderRow(
            label = "fx intensity",
            value = state.fxIntensity,
            onValueChange = viewModel::setFxIntensity,
            accentColor = MaterialTheme.colorScheme.tertiary,
            valueLabel = "${(state.fxIntensity * 100).toInt()}%",
            modifier = Modifier.padding(top = 4.dp),
        )

        state.error?.let {
            Text(
                text = "!! $it",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }

        Text(
            text = "// secrets are Tink-encrypted; models persisted; v0.1.0",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 16.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelDropdown(
    label: String,
    selected: String,
    models: List<ModelInfo>,
    loading: Boolean,
    onRequestModels: () -> Unit,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    includeDefaultOption: Boolean = false,
    imageOnly: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    val shown = if (imageOnly) models.filter { Capability.ImageOutput in it.capabilities } else models

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = {
            expanded = it
            if (it && models.isEmpty()) onRequestModels()
        },
        modifier = modifier.fillMaxWidth().padding(top = 6.dp),
    ) {
        OutlinedTextField(
            value = selected,
            onValueChange = { onSelect(it) },
            label = { Text(label.uppercase(), style = MaterialTheme.typography.labelMedium) },
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            textStyle = MaterialTheme.typography.bodyLarge,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (loading) {
                DropdownMenuItem(text = { Text("Loading models…") }, onClick = {}, enabled = false)
            }
            if (includeDefaultOption) {
                DropdownMenuItem(
                    text = { Text(USE_DEFAULT, style = MaterialTheme.typography.bodyMedium) },
                    onClick = { onSelect(""); expanded = false },
                )
            }
            shown.forEach { model ->
                DropdownMenuItem(
                    text = { Text(model.id, style = MaterialTheme.typography.bodyMedium) },
                    onClick = { onSelect(model.id); expanded = false },
                )
            }
        }
    }
}
