package app.box.suggest

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.box.suggest.ui.AddressScreen
import app.box.suggest.ui.BoxScreen
import app.box.suggest.ui.BoxTheme
import androidx.compose.ui.res.stringResource

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(0xFF000000.toInt()),
            navigationBarStyle = SystemBarStyle.dark(0xFF000000.toInt()),
        )
        setContent {
            val model: BoxViewModel = viewModel()
            val state by model.state.collectAsStateWithLifecycle()
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
                if (uri != null) model.dropMedia(uri)
            }
            BoxTheme {
                if (state.phase == Phase.Setup || state.phase == Phase.Offline) {
                    AddressScreen(
                        initialUrl = state.serverUrl,
                        error = state.banner ?: if (state.phase == Phase.Offline) {
                            stringResource(R.string.offline)
                        } else {
                            null
                        },
                        onOpen = model::openBox,
                    )
                } else {
                    BoxScreen(
                        state = state,
                        onDropText = model::dropText,
                        onPick = {
                            picker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                        onRetry = model::retry,
                    )
                }
            }
        }
    }
}
