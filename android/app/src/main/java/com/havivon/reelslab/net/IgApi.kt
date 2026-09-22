package com.havivon.reelslab.net

import com.havivon.reelslab.data.Account
import com.havivon.reelslab.data.ClipsPage
import com.havivon.reelslab.data.Reel
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class IgApiException(message: String) : IOException(message)

/**
 * The two calls this app needs, against the same web endpoints instagram.com
 * itself uses from a logged-in browser. Nothing here is signed or encrypted:
 * the session cookie is the whole of the authentication.
 */
object IgApi {

    private const val APP_ID = "936619743392459"
    private const val ASBD_ID = "129477"
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"

    fun profile(session: IgSession, username: String): Account {
        val handle = username.trim().removePrefix("@")
        val url = "${IgSession.ORIGIN}/api/v1/users/web_profile_info/?username=" +
            URLEncoder.encode(handle, "UTF-8")
        val root = JSONObject(get(session, url))
        val user = root.optJSONObject("data")?.optJSONObject("user")
            ?: throw IgApiException("לא נמצא חשבון בשם @$handle")

        return Account(
            pk = user.optString("id"),
            username = user.optString("username", handle),
            fullName = user.optString("full_name"),
            isPrivate = user.optBoolean("is_private"),
            followerCount = user.optJSONObject("edge_followed_by")?.optLong("count") ?: 0,
            mediaCount = user.optJSONObject("edge_owner_to_timeline_media")?.optLong("count") ?: 0,
            profilePicUrl = user.optString("profile_pic_url"),
        )
    }

    /**
     * One page of an account's reels. [maxId] comes from the previous page;
     * null starts from the newest.
     */
    fun clips(
        session: IgSession,
        account: Account,
        maxId: String? = null,
        pageSize: Int = 12,
    ): ClipsPage {
        val body = buildString {
            append("target_user_id=").append(URLEncoder.encode(account.pk, "UTF-8"))
            append("&page_size=").append(pageSize)
            append("&include_feed_video=true")
            if (!maxId.isNullOrEmpty()) {
                append("&max_id=").append(URLEncoder.encode(maxId, "UTF-8"))
            }
        }
        val root = JSONObject(post(session, "${IgSession.ORIGIN}/api/v1/clips/user/", body))

        val items = root.optJSONArray("items") ?: JSONArray()
        val reels = buildList {
            for (index in 0 until items.length()) {
                val media = items.optJSONObject(index)?.optJSONObject("media") ?: continue
                add(parseReel(media, account))
            }
        }
        val paging = root.optJSONObject("paging_info")
        val next = paging?.optString("max_id").orEmpty()
        val more = paging?.optBoolean("more_available") ?: false

        return ClipsPage(reels, if (more && next.isNotEmpty()) next else null)
    }

    /**
     * Re-resolves one reel. Instagram's CDN links are signed and expire, so a
     * stored video URL eventually stops playing and has to be fetched again.
     */
    fun mediaInfo(session: IgSession, reelPk: String, account: Account): Reel? {
        val url = "${IgSession.ORIGIN}/api/v1/media/$reelPk/info/"
        val items = JSONObject(get(session, url)).optJSONArray("items") ?: return null
        val media = items.optJSONObject(0) ?: return null
        return parseReel(media, account)
    }

    private fun parseReel(media: JSONObject, account: Account) = Reel(
        pk = media.optString("pk").ifEmpty { media.optString("id") },
        accountPk = account.pk,
        accountUsername = account.username,
        code = media.optString("code"),
        takenAt = media.optLong("taken_at"),
        caption = media.optJSONObject("caption")?.optString("text").orEmpty(),
        playCount = firstPositive(media, "play_count", "video_play_count", "view_count", "video_view_count"),
        likeCount = media.optLong("like_count"),
        commentCount = media.optLong("comment_count"),
        duration = media.optDouble("video_duration", 0.0),
        thumbnailUrl = bestUrl(media.optJSONObject("image_versions2")?.optJSONArray("candidates")),
        videoUrl = bestUrl(media.optJSONArray("video_versions")),
        musicTitle = musicTitle(media),
        watchedAt = null,
        isFavorite = false,
    )

    private fun firstPositive(media: JSONObject, vararg keys: String): Long {
        for (key in keys) {
            val value = media.optLong(key, 0)
            if (value > 0) return value
        }
        return 0
    }

    /** Instagram returns several renditions; take the largest. */
    private fun bestUrl(candidates: JSONArray?): String {
        if (candidates == null) return ""
        var best = ""
        var bestArea = -1L
        for (index in 0 until candidates.length()) {
            val candidate = candidates.optJSONObject(index) ?: continue
            val area = candidate.optLong("width") * candidate.optLong("height")
            if (area > bestArea) {
                bestArea = area
                best = candidate.optString("url")
            }
        }
        return best
    }

    private fun musicTitle(media: JSONObject): String? {
        val clips = media.optJSONObject("clips_metadata") ?: return null
        val asset = clips.optJSONObject("music_info")
            ?.optJSONObject("music_asset_info")
            ?.optString("title")
            .orEmpty()
        if (asset.isNotEmpty()) return asset
        val original = clips.optJSONObject("original_sound_info")
            ?.optString("original_audio_title")
            .orEmpty()
        return original.ifEmpty { null }
    }

    // -- transport --------------------------------------------------------
    private fun get(session: IgSession, url: String): String =
        request(session, url, null)

    private fun post(session: IgSession, url: String, body: String): String =
        request(session, url, body)

    private fun request(session: IgSession, url: String, body: String?): String {
        if (!session.isLoggedIn) throw IgApiException("אין חיבור פעיל לאינסטגרם")

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = if (body == null) "GET" else "POST"
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            setRequestProperty("Cookie", session.cookie)
            setRequestProperty("X-IG-App-ID", APP_ID)
            setRequestProperty("X-ASBD-ID", ASBD_ID)
            setRequestProperty("X-IG-WWW-Claim", "0")
            setRequestProperty("X-Requested-With", "XMLHttpRequest")
            setRequestProperty("Referer", "${IgSession.ORIGIN}/")
            if (body != null) {
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("Origin", IgSession.ORIGIN)
                setRequestProperty("X-CSRFToken", session.csrfToken)
                doOutput = true
            }
        }

        try {
            if (body != null) {
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            if (code == 401 || code == 403) {
                throw IgApiException("החיבור לאינסטגרם פג. התחבר מחדש.")
            }
            if (code == 429) {
                throw IgApiException("אינסטגרם הגבילה את קצב הבקשות. המתן כמה דקות.")
            }
            if (code !in 200..299) {
                throw IgApiException("אינסטגרם החזירה שגיאה $code")
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
