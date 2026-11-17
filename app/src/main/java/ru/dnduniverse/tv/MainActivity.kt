package ru.dnduniverse.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher

/**
 * Thin fullscreen WebView shell around the dnd-universe `/tv` page.
 *
 * The page does all the work (pairing, board, music, chat). This activity only keeps the
 * screen on, lets media autoplay, persists the `dnd_display` pairing cookie and shows an
 * offline screen with auto-retry when the main page can't be loaded.
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var errorView: View
    private lateinit var errorMessage: TextView
    private lateinit var errorCountdown: TextView
    private lateinit var retryButton: Button

    private val handler = Handler(Looper.getMainLooper())

    /** True between a main-frame load error and the next successful main-frame load. */
    private var mainFrameFailed = false
    private var retryInSeconds = 0
    private var exitDialog: AlertDialog? = null
    private var backCallback: Any? = null
    private var rendererGone = false

    private val retryTick = object : Runnable {
        override fun run() {
            if (errorView.visibility != View.VISIBLE) return
            retryInSeconds--
            if (retryInSeconds <= 0) {
                retry()
            } else {
                updateCountdown()
                handler.postDelayed(this, 1_000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.web_view)
        errorView = findViewById(R.id.error_view)
        errorMessage = findViewById(R.id.error_message)
        errorCountdown = findViewById(R.id.error_countdown)
        retryButton = findViewById(R.id.retry_button)
        retryButton.setOnClickListener { retry() }

        enterImmersiveMode()
        setUpWebView()
        registerBackHandler()

        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl(BuildConfig.TV_URL)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setUpWebView() {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false // music must start without a click
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
            // Lets the /tv page detect the app shell if it ever needs to.
            userAgentString = "$userAgentString DndUniverseTV/${BuildConfig.VERSION_NAME}"
        }

        // The TV stays paired through the HttpOnly first-party `dnd_display` cookie.
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            // Helps embedded players (e.g. the YouTube iframe) that rely on their own cookies.
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                mainFrameFailed = false
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (!mainFrameFailed) hideError()
                CookieManager.getInstance().flush()
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (request.isForMainFrame) {
                    showError(error.description?.toString())
                }
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse,
            ) {
                // A 5xx on the page itself (e.g. proxy up, upstream down) is "offline" too.
                if (request.isForMainFrame && errorResponse.statusCode >= 500) {
                    showError("HTTP ${errorResponse.statusCode} ${errorResponse.reasonPhrase.orEmpty()}".trim())
                }
            }

            override fun onRenderProcessGone(
                view: WebView,
                detail: android.webkit.RenderProcessGoneDetail,
            ): Boolean {
                // Renderer crashed or was killed for memory: restart cleanly instead of dying.
                rendererGone = true
                recreate()
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            // Without this, paused/loading <video> elements show WebView's grey play-button poster.
            override fun getDefaultVideoPoster(): Bitmap =
                Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }
    }

    // ---- Offline / error screen -------------------------------------------------------------

    private fun showError(description: String?) {
        mainFrameFailed = true
        errorMessage.text = if (description.isNullOrBlank()) {
            getString(R.string.error_generic)
        } else {
            "${getString(R.string.error_generic)}\n($description)"
        }
        webView.visibility = View.INVISIBLE
        errorView.visibility = View.VISIBLE
        retryButton.requestFocus()
        scheduleAutoRetry()
    }

    private fun hideError() {
        handler.removeCallbacks(retryTick)
        if (errorView.visibility == View.VISIBLE) {
            errorView.visibility = View.GONE
            webView.visibility = View.VISIBLE
            webView.requestFocus()
        }
    }

    private fun scheduleAutoRetry() {
        handler.removeCallbacks(retryTick)
        retryInSeconds = AUTO_RETRY_SECONDS
        updateCountdown()
        handler.postDelayed(retryTick, 1_000)
    }

    private fun updateCountdown() {
        errorCountdown.text = getString(R.string.error_retry_in, retryInSeconds)
    }

    private fun retry() {
        handler.removeCallbacks(retryTick)
        // Keep the error screen up until the reload either succeeds (hideError) or fails again
        // (showError restarts the countdown). Reload the original URL, not WebView's error page.
        val current = webView.url
        if (current.isNullOrBlank() || current.startsWith("about:") || current.startsWith("data:")) {
            webView.loadUrl(BuildConfig.TV_URL)
        } else {
            webView.loadUrl(current)
        }
        // If the load hangs with no callback at all, try again on the next cycle.
        scheduleAutoRetry()
    }

    // ---- Back button --------------------------------------------------------------------------

    private fun registerBackHandler() {
        // The manifest opts into OnBackInvokedCallback (enableOnBackInvokedCallback), and
        // targetSdk 36 makes it mandatory on Android 16, so onBackPressed() is only used < 33.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val callback = OnBackInvokedCallback { handleBack() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                callback,
            )
            backCallback = callback
        }
    }

    @Deprecated("Used only below API 33; see registerBackHandler()")
    override fun onBackPressed() {
        handleBack()
    }

    private fun handleBack() {
        if (errorView.visibility != View.VISIBLE && webView.canGoBack()) {
            webView.goBack()
        } else {
            confirmExit()
        }
    }

    private fun confirmExit() {
        if (exitDialog?.isShowing == true) return
        exitDialog = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(R.string.exit_title)
            .setPositiveButton(R.string.exit_confirm) { _, _ -> finish() }
            .setNegativeButton(R.string.exit_cancel, null)
            .setOnDismissListener { enterImmersiveMode() }
            .show()
            .also { it.getButton(AlertDialog.BUTTON_NEGATIVE)?.requestFocus() }
    }

    // ---- Fullscreen ---------------------------------------------------------------------------

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    private fun enterImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Edge-to-edge is already enforced on API 35+ (hence deprecated); still needed on 30-34.
            @Suppress("DEPRECATION")
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.apply {
                hide(WindowInsets.Type.systemBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                )
        }
    }

    // ---- Lifecycle ----------------------------------------------------------------------------

    override fun onResume() {
        super.onResume()
        webView.onResume()
        // Back from the home screen while offline: don't make the user wait for the countdown.
        if (errorView.visibility == View.VISIBLE) retry()
    }

    override fun onPause() {
        // Persist the pairing cookie before the process may be killed.
        CookieManager.getInstance().flush()
        webView.onPause()
        handler.removeCallbacks(retryTick)
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!rendererGone) webView.saveState(outState)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        exitDialog?.dismiss()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            (backCallback as? OnBackInvokedCallback)?.let {
                onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it)
            }
        }
        webView.apply {
            stopLoading()
            webChromeClient = null
            (parent as? android.view.ViewGroup)?.removeView(this)
            destroy()
        }
        super.onDestroy()
    }

    private companion object {
        const val AUTO_RETRY_SECONDS = 15
    }
}
