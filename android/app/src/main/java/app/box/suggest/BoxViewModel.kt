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
    private val savedUrl = ServerAddress.load(application)
    private val _state = MutableStateFlow(
        BoxUiState(
            phase = Phase.Setup,
            serverUrl = savedUrl,
            serverLabel = Urls.label(savedUrl),
        ),
    )
    val state: StateFlow<BoxUiState> = _state.asStateFlow()

    private var selfId: String? = null
    private var userLeft = false
    private var reconnects = 0
    private var reads = 0
    private val purgeLock = Mutex()

    private val session = BoxSession(
        httpBase = savedUrl,
        scope = viewModelScope,
        onEvent = ::onEvent,
    )

    init {
        openBox(savedUrl)
    }

    fun openBox(raw: String) {
        userLeft = false
        reconnects = 0
        val url = ServerAddress.normalize(raw)
        if (url == null) {
            _state.update { it.copy(banner = "Use an address like http://192.168.1.20:43123") }
            return
        }
        ServerAddress.save(getApplication(), url)
        session.httpBase = url
        reads = 0
        selfId = null
        _state.update {
            it.copy(
                phase = Phase.Connecting,
                serverUrl = url,
                serverLabel = Urls.label(url),
                banner = null,
                notice = null,
                notes = emptyList(),
                busy = false,
            )
        }
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
                notes = it.notes + Suggestion(id, true, Kind.Text, text, null, null),
                banner = null,
            )
        }
        session.sendText(id, text)
        viewModelScope.launch {
            delay(Limits.VANISH_MS)
            clearNote(id)
        }
    }

    fun dropMedia(uri: Uri) {
        if (!_state.value.canDrop) return
        val started = SystemClock.elapsedRealtime()
        reads += 1
        _state.update { it.copy(busy = true, banner = null) }
        viewModelScope.launch {
            val read = withContext(Dispatchers.IO) { MediaReader.read(getApplication(), uri) }
            reads = (reads - 1).coerceAtLeast(0)
            if (_state.value.phase == Phase.InRoom) {
                _state.update { it.copy(busy = reads > 0) }
            }
            if (_state.value.phase != Phase.InRoom) return@launch
            when (read) {
                ReadResult.Unreadable ->
                    _state.update { it.copy(banner = "Couldn't read that image.") }
                ReadResult.TooBig ->
                    _state.update { it.copy(banner = "Images and GIFs need to be under 8 MB.") }
                ReadResult.Unsupported ->
                    _state.update { it.copy(banner = "Use a JPEG, PNG, WEBP, or GIF.") }
                is ReadResult.Ok -> {
                    val id = UUID.randomUUID().toString()
                    _state.update {
                        it.copy(
                            banner = null,
                            notes = it.notes + Suggestion(id, true, read.kind, null, read.bytes, read.mime),
                        )
                    }
                    session.upload(id, read.bytes, "suggestion.${read.ext}", read.mime)
                    val remain = Limits.VANISH_MS - (SystemClock.elapsedRealtime() - started)
                    if (remain > 0) delay(remain)
                    clearNote(id)
                }
            }
        }
    }

    fun retry() {
        openBox(_state.value.serverUrl)
        viewModelScope.launch { purgeEverything() }
    }

    fun leave() {
        userLeft = true
        reads = 0
        session.disconnect()
        selfId = null
        _state.update {
            it.copy(
                phase = Phase.Setup,
                occupancy = 0,
                notes = emptyList(),
                busy = false,
                banner = null,
                notice = null,
            )
        }
        viewModelScope.launch { purgeEverything() }
    }

    private fun onEvent(event: SessionEvent) {
        when (event) {
            is SessionEvent.Welcome -> {
                selfId = event.you
                reconnects = 0
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
                        notes = emptyList(),
                        busy = false,
                    )
                }
                viewModelScope.launch { purgeEverything() }
            }
            SessionEvent.Offline -> {
                if (userLeft || _state.value.phase == Phase.Rejected) return
                if (reconnects < 5) {
                    reconnects += 1
                    val wait = 2000L * reconnects
                    viewModelScope.launch {
                        delay(wait)
                        if (!userLeft) session.connect()
                    }
                } else {
                    _state.update { current ->
                        if (current.phase == Phase.Rejected) current else current.copy(phase = Phase.Offline)
                    }
                }
            }
            is SessionEvent.Failed -> _state.update { it.copy(banner = event.message) }
            is SessionEvent.Incoming -> showTheirs(event)
        }
    }

    private fun showTheirs(event: SessionEvent.Incoming) {
        if (event.sender == selfId) return
        if (_state.value.notes.any { it.id == event.id }) return
        val suggestion = Suggestion(
            id = event.id,
            mine = false,
            kind = event.kind,
            text = event.text,
            bytes = event.bytes,
            mime = event.mime,
        )
        _state.update { it.copy(notes = it.notes + suggestion) }
        viewModelScope.launch {
            delay(Limits.VANISH_MS)
            clearNote(event.id)
        }
    }

    private suspend fun clearNote(id: String) {
        if (_state.value.notes.none { it.id == id }) return
        _state.update { it.copy(notes = it.notes.filter { note -> note.id != id }) }
        forget(id)
    }

    private suspend fun forget(id: String) {
        purgeLock.withLock {
            val loader = getApplication<BoxApplication>().imageLoader
            loader.memoryCache?.remove(MemoryCache.Key(id))
            if (_state.value.notes.isEmpty()) {
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
