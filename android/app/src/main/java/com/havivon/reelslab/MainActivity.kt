package com.havivon.reelslab

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.Toast

/**
 * A shell around the Reels Lab web UI served by the machine running the Python
 * app. It holds no Instagram logic of its own - the server does all the work,
 * so this has to stay reachable on the same network.
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var setupView: ScrollView
    private lateinit var urlInput: EditText
    private lateinit var settingsButton: Button
    private lateinit var videoContainer: FrameLayout

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.web)
        setupView = findViewById(R.id.setup)
        urlInput = findViewById(R.id.url)
        settingsButton = findViewById(R.id.settings)
        videoContainer = findViewById(R.id.video_container)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true          // the UI remembers its theme in localStorage
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        // The access token is handed over as a cookie, so it has to survive restarts.
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    Toast.makeText(this@MainActivity, R.string.load_failed, Toast.LENGTH_LONG).show()
                    showSetup(savedUrl())
                }
            }
        }
        webView.webChromeClient = FullscreenVideoClient()

        findViewById<Button>(R.id.connect).setOnClickListener { connect(urlInput.text.toString()) }
        settingsButton.setOnClickListener { showSetup(savedUrl()) }

        val saved = savedUrl()
        if (saved.isEmpty()) showSetup("") else load(saved)
    }

    private fun prefs(): SharedPreferences =
        getSharedPreferences("reels_lab", Context.MODE_PRIVATE)

    private fun savedUrl(): String = prefs().getString(KEY_URL, "").orEmpty()

    private fun connect(raw: String) {
        val url = raw.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            Toast.makeText(this, R.string.bad_url, Toast.LENGTH_LONG).show()
            return
        }
        prefs().edit().putString(KEY_URL, url).apply()
        load(url)
    }

    private fun load(url: String) {
        setupView.visibility = View.GONE
        webView.visibility = View.VISIBLE
        settingsButton.visibility = View.VISIBLE
        webView.loadUrl(url)
    }

    private fun showSetup(prefill: String) {
        urlInput.setText(prefill)
        webView.visibility = View.GONE
        settingsButton.visibility = View.GONE
        setupView.visibility = View.VISIBLE
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            customView != null -> hideCustomView()
            webView.visibility == View.VISIBLE && webView.canGoBack() -> webView.goBack()
            else -> super.onBackPressed()
        }
    }

    private fun hideCustomView() {
        val view = customView ?: return
        videoContainer.removeView(view)
        videoContainer.visibility = View.GONE
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        webView.visibility = View.VISIBLE
        settingsButton.visibility = View.VISIBLE
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** Lets a reel go fullscreen instead of staying boxed inside the page. */
    private inner class FullscreenVideoClient : WebChromeClient() {
        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (customView != null) {
                callback.onCustomViewHidden()
                return
            }
            customView = view
            customViewCallback = callback
            videoContainer.addView(
                view,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            videoContainer.visibility = View.VISIBLE
            webView.visibility = View.GONE
            settingsButton.visibility = View.GONE
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        override fun onHideCustomView() = hideCustomView()
    }

    private companion object {
        const val KEY_URL = "server_url"
    }
}
