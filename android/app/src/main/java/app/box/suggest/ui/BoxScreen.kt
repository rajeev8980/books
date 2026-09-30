package app.box.suggest.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(
            text = stringResource(R.string.title),
            style = MaterialTheme.typography.headlineLarge,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = statusLine(state),
            style = MaterialTheme.typography.bodyMedium,
            color = Muted,
        )
        if (state.serverLabel.isNotBlank()) {
            Text(
                text = state.serverLabel,
                style = MaterialTheme.typography.labelMedium,
                color = Muted.copy(alpha = 0.75f),
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            SuggestionBox(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .heightIn(max = 520.dp),
            ) {
                BoxInterior(state, imageLoader)
            }
        }

        if (state.phase == Phase.InRoom) {
            Text(
                text = stringResource(R.string.vanish),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                color = Cream,
                fontFamily = FontFamily.Serif,
                fontStyle = FontStyle.Italic,
                fontSize = 16.sp,
            )
            Spacer(Modifier.height(12.dp))
        }

        state.banner?.let { banner ->
            Text(
                text = banner,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        if (state.phase == Phase.InRoom) {
            DropTray(
                draft = draft,
                onDraft = { if (it.length <= Limits.MAX_TEXT) draft = it },
                canDrop = state.canDrop,
                onDrop = {
                    val text = draft
                    draft = ""
                    onDropText(text)
                },
                onPick = onPick,
            )
        } else if (state.phase == Phase.Offline || state.phase == Phase.Rejected) {
            Button(
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Wax, contentColor = Cream),
            ) {
                Text(stringResource(R.string.retry))
            }
        }
    }
}

@Composable
private fun statusLine(state: BoxUiState): String {
    return when (state.phase) {
        Phase.Setup -> stringResource(R.string.server_label)
        Phase.Connecting -> stringResource(R.string.connecting)
        Phase.Offline -> stringResource(R.string.offline)
        Phase.Rejected -> stringResource(R.string.room_full)
        Phase.InRoom -> if (state.occupancy >= 2) {
            stringResource(R.string.paired)
        } else {
            stringResource(R.string.waiting)
        }
    }
}

@Composable
private fun SuggestionBox(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val boxLabel = stringResource(R.string.title)
    Column(
        modifier = modifier
            .shadow(12.dp, RoundedCornerShape(28.dp))
            .clip(RoundedCornerShape(28.dp))
            .background(Wood)
            .padding(12.dp)
            .semantics { contentDescription = boxLabel },
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .width(92.dp)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Slot),
        )
        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(18.dp))
                .background(Paper),
            contentAlignment = Alignment.Center,
            content = content,
        )
    }
}

@Composable
private fun BoxScope.BoxInterior(
    state: BoxUiState,
    imageLoader: coil3.ImageLoader,
) {
    when {
        state.phase == Phase.Rejected -> CenterNote(state.notice ?: stringResource(R.string.room_full))
        state.phase == Phase.Offline -> CenterNote(stringResource(R.string.offline))
        state.phase == Phase.Connecting -> CenterNote(stringResource(R.string.connecting))
        state.busy && state.yours == null && state.theirs == null -> CenterNote(stringResource(R.string.dropping))
        state.yours == null && state.theirs == null -> {
            Text(
                text = stringResource(R.string.empty),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = InkText,
                modifier = Modifier.padding(24.dp),
            )
        }
        else -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                state.yours?.let { note ->
                    NoteCard(note, imageLoader, Modifier.weight(1f))
                }
                state.theirs?.let { note ->
                    NoteCard(note, imageLoader, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun BoxScope.CenterNote(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(24.dp),
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        color = InkText,
    )
}

@Composable
private fun NoteCard(
    suggestion: Suggestion,
    imageLoader: coil3.ImageLoader,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(if (suggestion.mine) R.string.yours else R.string.theirs)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(PaperCard)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelMedium,
        )
        Spacer(Modifier.height(6.dp))
        when (suggestion.kind) {
            Kind.Text -> {
                Text(
                    text = suggestion.text.orEmpty(),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Kind.Image, Kind.Gif -> {
                val context = LocalContext.current
                val description = stringResource(
                    if (suggestion.kind == Kind.Gif) R.string.animated_gif else R.string.photo,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
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
                            .fillMaxSize()
                            .clip(RoundedCornerShape(12.dp)),
                    )
                    if (suggestion.kind == Kind.Gif) {
                        Text(
                            text = stringResource(R.string.gif),
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(8.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Ink.copy(alpha = 0.72f))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            color = Cream,
                            fontSize = 10.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DropTray(
    draft: String,
    onDraft: (String) -> Unit,
    canDrop: Boolean,
    onDrop: () -> Unit,
    onPick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Paper)
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onPick,
            enabled = canDrop,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_image),
                contentDescription = stringResource(R.string.pick),
                tint = InkText,
            )
        }
        OutlinedTextField(
            value = draft,
            onValueChange = onDraft,
            modifier = Modifier.weight(1f),
            enabled = canDrop,
            singleLine = true,
            placeholder = {
                Text(stringResource(R.string.hint), color = MutedInk)
            },
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = InkText, fontSize = 16.sp),
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = InkText,
                unfocusedTextColor = InkText,
                disabledTextColor = MutedInk,
                cursorColor = Wax,
                focusedBorderColor = Wax,
                unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                disabledBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                focusedContainerColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.35f),
                unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                disabledContainerColor = androidx.compose.ui.graphics.Color.Transparent,
            ),
        )
        Spacer(Modifier.width(6.dp))
        Button(
            onClick = onDrop,
            enabled = canDrop && draft.isNotBlank(),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Wax,
                contentColor = Cream,
                disabledContainerColor = Wax.copy(alpha = 0.35f),
                disabledContentColor = Cream.copy(alpha = 0.7f),
            ),
        ) {
            Text(stringResource(R.string.drop))
        }
    }
}
