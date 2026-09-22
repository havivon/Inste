package com.havivon.reelslab.net

import android.content.Context
import android.webkit.CookieManager

/**
 * The Instagram session, owned entirely by Instagram's own login page.
 *
 * The app never sees or stores a password: [LoginActivity] shows the real
 * instagram.com sign-in inside a WebView - which is also what handles 2FA and
 * verification challenges - and all that is kept afterwards is the resulting
 * cookie string.
 */
class IgSession(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("reels_lab", Context.MODE_PRIVATE)

    var cookie: String
        get() = prefs.getString(KEY_COOKIE, "").orEmpty()
        private set(value) = prefs.edit().putString(KEY_COOKIE, value).apply()

    val csrfToken: String get() = valueOf("csrftoken")
    val userId: String get() = valueOf("ds_user_id")
    val isLoggedIn: Boolean get() = valueOf("sessionid").isNotEmpty()

    /** Copies whatever instagram.com has set in the WebView cookie jar. */
    fun captureFromWebView(): Boolean {
        val captured = CookieManager.getInstance().getCookie(ORIGIN).orEmpty()
        if (!captured.contains("sessionid=")) return false
        cookie = captured
        return true
    }

    fun clear() {
        prefs.edit().remove(KEY_COOKIE).apply()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }

    private fun valueOf(name: String): String = cookie
        .split(';')
        .map { it.trim() }
        .firstOrNull { it.startsWith("$name=") }
        ?.substringAfter('=')
        .orEmpty()

    companion object {
        const val ORIGIN = "https://www.instagram.com"
        const val LOGIN_URL = "$ORIGIN/accounts/login/"
        private const val KEY_COOKIE = "ig_cookie"
    }
}
