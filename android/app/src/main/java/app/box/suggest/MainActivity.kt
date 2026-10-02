package app.box.suggest

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewStructure
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.CookieManager
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
    private val retry = Handler(Looper.getMainLooper())
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pageReady = false

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

    @SuppressLint("SetJavaScriptEnabled")
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
                outAttrs.inputType = outAttrs.inputType or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                outAttrs.imeOptions = outAttrs.imeOptions or EditorInfo.IME_FLAG_NO_EXTRACT_UI
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
            allowFileAccess = true
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = false
        }
        web.clearCache(true)
        web.clearFormData()
        web.clearHistory()
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
        web.webChromeClient = object : WebChromeClient() {
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

    private fun loadBox() {
        pageReady = false
        web.loadUrl(BuildConfig.SERVER_URL)
    }

    private fun scheduleRetry() {
        if (pageReady) return
        web.stopLoading()
        retry.removeCallbacksAndMessages(null)
        retry.postDelayed({ loadBox() }, 1000)
    }

    override fun onDestroy() {
        retry.removeCallbacksAndMessages(null)
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        web.stopLoading()
        web.loadUrl("about:blank")
        web.destroy()
        super.onDestroy()
    }
}
