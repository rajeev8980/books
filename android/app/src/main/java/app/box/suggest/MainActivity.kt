package app.box.suggest

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewStructure
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : ComponentActivity() {
    private lateinit var web: WebView
    private lateinit var callAudio: CallAudio
    private val retry = Handler(Looper.getMainLooper())
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingCapture: PermissionRequest? = null
    private var pageReady = false

    private val capturePermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val request = pendingCapture
        pendingCapture = null
        if (request == null) return@registerForActivityResult
        val allowed = grantedCapture(request, grants)
        if (allowed.isEmpty()) request.deny() else request.grant(allowed)
    }

    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val callback = fileCallback
        fileCallback = null
        val uris = if (result.resultCode == Activity.RESULT_OK) {
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        } else {
            null
        }
        callback?.onReceiveValue(uris)
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        web = object : WebView(this) {
            override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
                val connection = super.onCreateInputConnection(outAttrs)
                outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                    InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                outAttrs.imeOptions = outAttrs.imeOptions or
                    EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                    EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                return connection
            }

            override fun onProvideAutofillVirtualStructure(structure: ViewStructure?, flags: Int) {
            }
        }.apply {
            setBackgroundColor(Color.BLACK)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        root.addView(web, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bottom = if (ime.bottom > bars.bottom) ime.bottom else bars.bottom
            view.setPadding(bars.left, bars.top, bars.right, bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(root)
        ViewCompat.requestApplyInsets(root)

        CookieManager.getInstance().setAcceptCookie(false)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            databaseEnabled = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            allowFileAccess = false
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = false
        }
        web.setDownloadListener { _, _, _, _, _ -> }
        web.clearCache(true)
        web.clearFormData()
        web.clearHistory()
        CookieManager.getInstance().removeAllCookies(null)
        CacheWiper.wipe(this)
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val host = request.url?.host ?: return true
                return !BuildConfig.SERVER_URL.contains(host)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (url != null && url.startsWith(BuildConfig.SERVER_URL)) {
                    pageReady = true
                    retry.removeCallbacksAndMessages(null)
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) scheduleRetry()
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse,
            ) {
                if (request.isForMainFrame && errorResponse.statusCode >= 500) scheduleRetry()
            }
        }
        callAudio = CallAudio(this)
        web.addJavascriptInterface(callAudio, "BoxAudio")
        web.addJavascriptInterface(BoxWipe(this, web), "BoxWipe")
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                val wantsAudio = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                val wantsVideo = request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                if (!wantsAudio && !wantsVideo) {
                    request.deny()
                    return
                }
                val needed = ArrayList<String>()
                if (wantsAudio && !hasPermission(android.Manifest.permission.RECORD_AUDIO)) {
                    needed.add(android.Manifest.permission.RECORD_AUDIO)
                }
                if (wantsVideo && !hasPermission(android.Manifest.permission.CAMERA)) {
                    needed.add(android.Manifest.permission.CAMERA)
                }
                if (needed.isEmpty()) {
                    request.grant(grantedCapture(request, emptyMap()))
                    return
                }
                pendingCapture?.deny()
                pendingCapture = request
                capturePermission.launch(needed.toTypedArray())
            }

            override fun onShowFileChooser(
                view: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?,
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback
                val intent = try {
                    fileChooserParams?.createIntent()
                } catch (_: Exception) {
                    null
                } ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                }
                return try {
                    picker.launch(intent)
                    true
                } catch (_: Exception) {
                    fileCallback = null
                    filePathCallback?.onReceiveValue(null)
                    false
                }
            }
        }
        loadBox()
        AppUpdate.start(this)
    }

    override fun onResume() {
        super.onResume()
        AppUpdate.onResume(this)
    }

    override fun onPause() {
        web.clearCache(true)
        web.clearFormData()
        web.clearHistory()
        CookieManager.getInstance().removeAllCookies(null)
        super.onPause()
    }

    private fun loadBox() {
        pageReady = false
        web.loadUrl(BuildConfig.SERVER_URL)
    }

    private fun hasPermission(name: String): Boolean {
        return checkSelfPermission(name) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun grantedCapture(request: PermissionRequest, grants: Map<String, Boolean>): Array<String> {
        val allowed = ArrayList<String>()
        if (request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE) &&
            (grants[android.Manifest.permission.RECORD_AUDIO] ?: hasPermission(android.Manifest.permission.RECORD_AUDIO))
        ) {
            allowed.add(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
        }
        if (request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE) &&
            (grants[android.Manifest.permission.CAMERA] ?: hasPermission(android.Manifest.permission.CAMERA))
        ) {
            allowed.add(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
        }
        return allowed.toTypedArray()
    }

    private fun scheduleRetry() {
        if (pageReady) return
        web.stopLoading()
        retry.removeCallbacksAndMessages(null)
        retry.postDelayed({ loadBox() }, 1000)
    }

    override fun onDestroy() {
        if (::callAudio.isInitialized) callAudio.stop()
        retry.removeCallbacksAndMessages(null)
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        pendingCapture?.deny()
        pendingCapture = null
        web.stopLoading()
        web.clearCache(true)
        web.loadUrl("about:blank")
        web.destroy()
        CacheWiper.wipe(this)
        super.onDestroy()
    }
}

class BoxWipe(
    private val activity: MainActivity,
    private val web: WebView,
) {
    @JavascriptInterface
    fun now() {
        activity.runOnUiThread {
            web.clearCache(true)
            web.clearFormData()
            web.clearHistory()
            CookieManager.getInstance().removeAllCookies(null)
        }
    }
}

class CallAudio(activity: ComponentActivity) {
    private val main = Handler(Looper.getMainLooper())
    private val audio = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focus: AudioFocusRequest? = null

    @JavascriptInterface
    fun startVoice() {
        main.post { begin(speaker = false) }
    }

    @JavascriptInterface
    fun startVideo() {
        main.post { begin(speaker = true) }
    }

    @JavascriptInterface
    fun stop() {
        main.post { end() }
    }

    private fun begin(speaker: Boolean) {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { }
            .build()
        audio.requestAudioFocus(request)
        focus = request
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        audio.isSpeakerphoneOn = speaker
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val wanted = if (speaker) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            } else {
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            }
            val device = audio.availableCommunicationDevices.firstOrNull { it.type == wanted }
            if (device != null) audio.setCommunicationDevice(device)
        }
    }

    private fun end() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audio.clearCommunicationDevice()
        audio.isSpeakerphoneOn = false
        audio.mode = AudioManager.MODE_NORMAL
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
    }
}
