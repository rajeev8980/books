package app.box.suggest.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.box.suggest.BoxApplication
import app.box.suggest.BoxUiState
import app.box.suggest.Kind
import app.box.suggest.Limits
import app.box.suggest.Phase
import app.box.suggest.R
import app.box.suggest.Suggestion
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import coil3.request.CachePolicy
import coil3.request.ImageRequest

@Composable
fun BoxScreen(
    state: BoxUiState,
    onDropText: (String) -> Unit,
    onPick: () -> Unit,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    val imageLoader = (context.applicationContext as BoxApplication).imageLoader
    var draft by remember { mutableStateOf("") }
    val canSend = state.canDrop && draft.isNotBlank()
    val scroll = rememberScrollState()
    LaunchedEffect(state.notes.size, state.busy) {
        delay(32)
        scroll.scrollTo(scroll.maxValue)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Black)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (state.phase) {
                Phase.Rejected -> BubbleText(state.notice ?: stringResource(R.string.room_full))
                else -> {
                    state.notes.forEach { note ->
                        key(note.id) {
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = if (note.mine) Alignment.CenterEnd else Alignment.CenterStart,
                            ) {
                                NoteBubble(note, imageLoader)
                            }
                        }
                    }
                    if (state.busy) BubbleText(stringResource(R.string.dropping))
                }
            }
            state.banner?.let { banner ->
                Spacer(Modifier.height(12.dp))
                Text(text = banner, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text(
            text = statusLine(state),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp),
            color = Muted,
            style = MaterialTheme.typography.labelMedium,
        )
        if (state.phase == Phase.Rejected) {
            Text(
                text = stringResource(R.string.retry),
                color = White,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .border(1.dp, White, RoundedCornerShape(20.dp))
                    .clickable(onClick = onRetry)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
            Spacer(Modifier.height(8.dp))
        }
        Composer(
            draft = draft,
            onDraft = { if (it.length <= Limits.MAX_TEXT) draft = it },
            enabled = state.canDrop,
            canSend = canSend,
            onSend = {
                val text = draft
                draft = ""
                onDropText(text)
            },
            onPick = onPick,
        )
    }
}

@Composable
private fun statusLine(state: BoxUiState): String {
    return when (state.phase) {
        Phase.Rejected -> stringResource(R.string.room_full)
        Phase.InRoom -> stringResource(R.string.members, state.occupancy)
        else -> stringResource(R.string.connecting)
    }
}

@Composable
private fun BubbleText(text: String) {
    Text(
        text = text,
        color = White,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth(0.92f)
            .clip(RoundedCornerShape(16.dp))
            .background(Bubble)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    )
}

@Composable
private fun NoteBubble(
    suggestion: Suggestion,
    imageLoader: coil3.ImageLoader,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth(0.92f)
            .clip(RoundedCornerShape(16.dp))
            .background(Bubble)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = stringResource(if (suggestion.mine) R.string.yours else R.string.theirs),
            color = Muted,
            style = MaterialTheme.typography.labelMedium,
        )
        Spacer(Modifier.height(6.dp))
        when (suggestion.kind) {
            Kind.Text -> Text(
                text = suggestion.text.orEmpty(),
                color = White,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
            )
            Kind.Image, Kind.Gif -> {
                val context = LocalContext.current
                val description = stringResource(
                    if (suggestion.kind == Kind.Gif) R.string.animated_gif else R.string.photo,
                )
                Box {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(suggestion.bytes)
                            .memoryCacheKey(suggestion.id)
                            .diskCachePolicy(CachePolicy.DISABLED)
                            .memoryCachePolicy(CachePolicy.ENABLED)
                            .build(),
                        imageLoader = imageLoader,
                        contentDescription = description,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                    if (suggestion.kind == Kind.Gif) {
                        Text(
                            text = stringResource(R.string.gif),
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(8.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Black.copy(alpha = 0.72f))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            color = White,
                            fontSize = 10.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Composer(
    draft: String,
    onDraft: (String) -> Unit,
    enabled: Boolean,
    canSend: Boolean,
    onSend: () -> Unit,
    onPick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPick, enabled = enabled) {
            Icon(
                painter = painterResource(R.drawable.ic_image),
                contentDescription = stringResource(R.string.pick),
                tint = if (enabled) White else Muted,
            )
        }
        OutlinedTextField(
            value = draft,
            onValueChange = onDraft,
            modifier = Modifier.weight(1f),
            enabled = enabled,
            singleLine = true,
            placeholder = { Text(stringResource(R.string.hint), color = Muted) },
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = White),
            shape = RoundedCornerShape(28.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = White,
                unfocusedTextColor = White,
                disabledTextColor = Muted,
                cursorColor = White,
                focusedBorderColor = White,
                unfocusedBorderColor = Line,
                disabledBorderColor = Line,
                focusedContainerColor = Black,
                unfocusedContainerColor = Black,
                disabledContainerColor = Black,
            ),
        )
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(if (canSend) White else Line)
                .clickable(enabled = canSend, onClick = onSend),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_send),
                contentDescription = stringResource(R.string.drop),
                tint = if (canSend) Black else Muted,
            )
        }
    }
}
