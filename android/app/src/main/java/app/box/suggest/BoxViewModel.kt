package app.box.suggest

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil3.memory.MemoryCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

class BoxViewModel(application: Application) : AndroidViewModel(application) {
    private val serverUrl = BuildConfig.SERVER_URL
    private val _state = MutableStateFlow(BoxUiState(serverLabel = Urls.label(serverUrl)))
    val state: StateFlow<BoxUiState> = _state.asStateFlow()

    private var selfId: String? = null
    private val purgeLock = Mutex()

    private val session = BoxSession(
        httpBase = serverUrl,
        scope = viewModelScope,
        onEvent = ::onEvent,
    )

    init {
        session.connect()
    }

    fun dropText(raw: String) {
        val text = raw.trim()
        val current = _state.value
        if (!current.canDrop) return
        if (text.isEmpty()) return
        if (text.length > Limits.MAX_TEXT) {
            _state.update { it.copy(banner = "Keep it under 2000 characters.") }
            return
        }
        val id = UUID.randomUUID().toString()
        _state.update {
            it.copy(
                yours = Suggestion(id, true, Kind.Text, text, null, null),
                banner = null,
            )
        }
        session.sendText(id, text)
        viewModelScope.launch {
            delay(Limits.VANISH_MS)
            clearYours(id)
        }
    }

    fun dropMedia(uri: Uri) {
        if (!_state.value.canDrop) return
        val started = SystemClock.elapsedRealtime()
        _state.update { it.copy(busy = true, banner = null) }
        viewModelScope.launch {
            when (val read = withContext(Dispatchers.IO) { MediaReader.read(getApplication(), uri) }) {
                ReadResult.Unreadable ->
                    _state.update { it.copy(busy = false, banner = "Couldn't read that image.") }
                ReadResult.TooBig ->
                    _state.update { it.copy(busy = false, banner = "Images and GIFs need to be under 8 MB.") }
                ReadResult.Unsupported ->
                    _state.update { it.copy(busy = false, banner = "Use a JPEG, PNG, WEBP, or GIF.") }
                is ReadResult.Ok -> {
                    val id = UUID.randomUUID().toString()
                    _state.update {
                        it.copy(
                            busy = false,
                            banner = null,
                            yours = Suggestion(id, true, read.kind, null, read.bytes, read.mime),
                        )
                    }
                    session.upload(id, read.bytes, "suggestion.${read.ext}", read.mime)
                    val remain = Limits.VANISH_MS - (SystemClock.elapsedRealtime() - started)
                    if (remain > 0) delay(remain)
                    clearYours(id)
                }
            }
        }
    }

    fun retry() {
        _state.value = BoxUiState(
            phase = Phase.Connecting,
            serverLabel = Urls.label(serverUrl),
        )
        selfId = null
        session.connect()
        viewModelScope.launch { purgeEverything() }
    }

    private fun onEvent(event: SessionEvent) {
        when (event) {
            is SessionEvent.Welcome -> {
                selfId = event.you
                _state.update {
                    it.copy(phase = Phase.InRoom, occupancy = event.occupancy, notice = null)
                }
            }
            is SessionEvent.Presence -> {
                _state.update { it.copy(phase = Phase.InRoom, occupancy = event.occupancy) }
            }
            is SessionEvent.Rejected -> {
                _state.update {
                    it.copy(
                        phase = Phase.Rejected,
                        notice = event.message,
                        yours = null,
                        theirs = null,
                        busy = false,
                    )
                }
                viewModelScope.launch { purgeEverything() }
            }
            SessionEvent.Offline -> {
                _state.update { current ->
                    if (current.phase == Phase.Rejected) current else current.copy(phase = Phase.Offline)
                }
            }
            is SessionEvent.Failed -> _state.update { it.copy(banner = event.message) }
            is SessionEvent.Incoming -> showTheirs(event)
        }
    }

    private fun showTheirs(event: SessionEvent.Incoming) {
        if (event.sender == selfId) return
        if (_state.value.theirs != null) return
        val suggestion = Suggestion(
            id = event.id,
            mine = false,
            kind = event.kind,
            text = event.text,
            bytes = event.bytes,
            mime = event.mime,
        )
        _state.update { it.copy(theirs = suggestion) }
        viewModelScope.launch {
            delay(Limits.VANISH_MS)
            clearTheirs(event.id)
        }
    }

    private suspend fun clearYours(id: String) {
        if (_state.value.yours?.id != id) return
        _state.update { it.copy(yours = null) }
        forget(id)
    }

    private suspend fun clearTheirs(id: String) {
        if (_state.value.theirs?.id != id) return
        _state.update { it.copy(theirs = null) }
        forget(id)
    }

    private suspend fun forget(id: String) {
        purgeLock.withLock {
            val loader = getApplication<BoxApplication>().imageLoader
            loader.memoryCache?.remove(MemoryCache.Key(id))
            if (_state.value.yours == null && _state.value.theirs == null) {
                loader.memoryCache?.clear()
            }
            withContext(Dispatchers.IO) { CacheWiper.wipe(getApplication()) }
        }
    }

    private suspend fun purgeEverything() {
        val loader = getApplication<BoxApplication>().imageLoader
        loader.memoryCache?.clear()
        withContext(Dispatchers.IO) { CacheWiper.wipe(getApplication()) }
    }

    override fun onCleared() {
        session.close()
        getApplication<BoxApplication>().imageLoader.memoryCache?.clear()
        CacheWiper.wipe(getApplication())
    }
}
