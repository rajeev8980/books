package app.box.suggest.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.box.suggest.R
import androidx.compose.ui.res.stringResource

@Composable
fun AddressScreen(
    initialUrl: String,
    error: String?,
    onOpen: (String) -> Unit,
) {
    var address by remember(initialUrl) { mutableStateOf(initialUrl) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .padding(horizontal = 24.dp, vertical = 28.dp),
    ) {
        Text(
            text = stringResource(R.string.title),
            style = MaterialTheme.typography.headlineLarge,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.vanish),
            color = Cream,
            fontFamily = FontFamily.Serif,
            fontStyle = FontStyle.Italic,
            fontSize = 18.sp,
        )
        Spacer(Modifier.height(28.dp))
        Text(
            text = stringResource(R.string.server_label),
            color = Muted,
            style = MaterialTheme.typography.labelMedium,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text(stringResource(R.string.server_hint), color = MutedInk) },
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = InkText, fontSize = 16.sp),
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = InkText,
                unfocusedTextColor = InkText,
                cursorColor = Wax,
                focusedBorderColor = Wax,
                unfocusedBorderColor = Muted.copy(alpha = 0.4f),
                focusedContainerColor = Paper,
                unfocusedContainerColor = Paper,
            ),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.server_help),
            color = Muted,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (!error.isNullOrBlank()) {
            Spacer(Modifier.height(14.dp))
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = { onOpen(address) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Wax, contentColor = Cream),
        ) {
            Text(stringResource(R.string.open_box))
        }
    }
}
