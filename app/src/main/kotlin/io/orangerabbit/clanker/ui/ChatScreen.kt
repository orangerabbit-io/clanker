package io.orangerabbit.clanker.ui

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mikepenz.markdown.m3.Markdown
import io.orangerabbit.clanker.core.model.ChatMessage
import io.orangerabbit.clanker.core.model.MsgLifecycle
import io.orangerabbit.ui.components.ThemedButton
import io.orangerabbit.ui.components.ThemedButtonAccent
import io.orangerabbit.ui.components.ThemedCard
import io.orangerabbit.ui.components.ThemedStatusIndicator
import io.orangerabbit.ui.components.ThemedTextField
import io.orangerabbit.ui.effects.GlitchText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }
    var zoomed by remember { mutableStateOf<ImageBitmap?>(null) }
    val listState = rememberLazyListState()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val mime = context.contentResolver.getType(uri) ?: "image/png"
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes != null) viewModel.attachImage(bytes, mime)
            }
        }
    }

    // Follow the conversation: re-scroll on a new message and as the last message streams in.
    val lastLen = (state.messages.lastOrNull() as? ChatMessage.Assistant)?.content?.length ?: 0
    LaunchedEffect(state.messages.size, lastLen) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 12.dp)
            .imePadding(),
    ) {
        // --- Banner: glitchy title, relay status, gear ---
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlitchText(
                text = "CLANKER",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.headlineMedium,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ThemedStatusIndicator(label = "", isOnline = state.apiKey.isNotBlank())
                ThemedButton(onClick = onOpenSettings, accent = ThemedButtonAccent.Neutral) {
                    Text("⚙ CFG", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        // Active model + running cost.
        val activeModel = if (state.imageMode) state.effectiveImageModel else state.effectiveChatModel
        Text(
            text = buildString {
                append("// ")
                append(if (state.imageMode) "IMG  " else "CHAT  ")
                append(activeModel)
                if (state.costUsd > 0.0) append("   $${"%.5f".format(state.costUsd)}")
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
        )

        state.error?.let { err ->
            Text(
                text = "!! $err",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items = state.messages, key = { it.id.value }) { msg ->
                MessageBubble(msg, onImageTap = { zoomed = it })
            }
        }

        // Pending vision attachments.
        if (state.pendingImages.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                state.pendingImages.forEach { url ->
                    rememberDataUrlBitmap(url)?.let { bmp ->
                        Image(
                            bitmap = bmp,
                            contentDescription = "attachment",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.heightIn(max = 48.dp).clickable { zoomed = bmp },
                        )
                    }
                }
                ThemedButton(onClick = viewModel::clearPendingImages, accent = ThemedButtonAccent.Neutral) {
                    Text("CLEAR", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        // --- Compose row: image-mode toggle, attach, message field, send/stop ---
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ThemedButton(
                onClick = { viewModel.setImageMode(!state.imageMode) },
                accent = ThemedButtonAccent.Tertiary,
                filled = state.imageMode,
            ) { Text("IMG", style = MaterialTheme.typography.labelMedium) }
            ThemedButton(
                onClick = { imagePicker.launch("image/*") },
                accent = ThemedButtonAccent.Tertiary,
            ) { Text("+", style = MaterialTheme.typography.labelLarge) }
            ThemedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = "message",
                keyboardType = KeyboardType.Text,
                singleLine = false,
                modifier = Modifier.weight(1f),
            )
            if (state.streaming) {
                ThemedButton(onClick = viewModel::stop, accent = ThemedButtonAccent.Secondary, filled = true) {
                    Text("STOP", style = MaterialTheme.typography.labelLarge)
                }
            } else {
                ThemedButton(
                    onClick = {
                        viewModel.send(draft)
                        draft = ""
                    },
                    accent = ThemedButtonAccent.Primary,
                    filled = true,
                    enabled = (draft.isNotBlank() || state.pendingImages.isNotEmpty()) && state.apiKey.isNotBlank(),
                ) { Text("SEND", style = MaterialTheme.typography.labelLarge) }
            }
        }
    }

    zoomed?.let { ZoomableImageDialog(it) { zoomed = null } }
}

@Composable
private fun MessageBubble(message: ChatMessage, onImageTap: (ImageBitmap) -> Unit) {
    val accent = when (message) {
        is ChatMessage.User -> MaterialTheme.colorScheme.secondary
        is ChatMessage.Assistant -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.outline
    }
    val (label, body) = when (message) {
        is ChatMessage.System -> "system" to message.content
        is ChatMessage.User -> "you" to message.content
        is ChatMessage.Assistant -> "assistant" to (message.content ?: "")
        is ChatMessage.Tool -> "tool" to message.content
    }
    val images = when (message) {
        is ChatMessage.User -> message.imageUrls
        is ChatMessage.Assistant -> message.imageUrls
        else -> emptyList()
    }

    ThemedCard(borderColor = accent, glowColor = accent) {
        Column {
            Text(
                text = "// ${label.uppercase()}",
                style = MaterialTheme.typography.labelSmall,
                color = accent,
            )
            when {
                body.isEmpty() && images.isEmpty() && message.lifecycle == MsgLifecycle.Streaming ->
                    Text(text = "▌", style = MaterialTheme.typography.bodyMedium)
                message is ChatMessage.Assistant && body.isNotEmpty() ->
                    Markdown(content = body)
                body.isNotEmpty() ->
                    Text(text = body, style = MaterialTheme.typography.bodyMedium)
            }
            images.forEach { url ->
                rememberDataUrlBitmap(url)?.let { bmp ->
                    Image(
                        bitmap = bmp,
                        contentDescription = "image",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .padding(top = 8.dp)
                            .clickable { onImageTap(bmp) },
                    )
                }
            }
        }
    }
}

/** Full-screen pinch-to-zoom / pan viewer for a tapped image. Tap the backdrop to dismiss. */
@Composable
private fun ZoomableImageDialog(image: ImageBitmap, onDismiss: () -> Unit) {
    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 6f)
        offsetX += panChange.x
        offsetY += panChange.y
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = image,
                contentDescription = "zoomed image",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY,
                    )
                    .transformable(transformState),
            )
        }
    }
}

/** Decodes a base64 `data:` URL to an [ImageBitmap], memoized per URL. Null on decode failure. */
@Composable
private fun rememberDataUrlBitmap(dataUrl: String): ImageBitmap? = remember(dataUrl) {
    runCatching {
        val comma = dataUrl.indexOf(',')
        val b64 = if (comma >= 0) dataUrl.substring(comma + 1) else dataUrl
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }.getOrNull()
}
