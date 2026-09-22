package com.havivon.reelslab

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.havivon.reelslab.net.IgSession

/**
 * Sign-in happens on Instagram's own page, inside a WebView. That keeps the
 * password out of this app entirely, and lets Instagram run two-factor and
 * verification challenges exactly as it normally would. All the app keeps
 * afterwards is the session cookie.
 */
class LoginActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var session: IgSession

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        session = IgSession(this)

        webView = findViewById(R.id.login_web)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) = captureIfSignedIn()

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) =
                captureIfSignedIn()
        }
        webView.loadUrl(IgSession.LOGIN_URL)
    }

    private fun captureIfSignedIn() {
        CookieManager.getInstance().flush()
        if (session.captureFromWebView()) {
            setResult(RESULT_OK)
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
