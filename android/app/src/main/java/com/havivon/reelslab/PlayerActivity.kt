package com.havivon.reelslab

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import com.havivon.reelslab.data.Account
import com.havivon.reelslab.data.Db
import com.havivon.reelslab.data.Reel
import com.havivon.reelslab.net.IgApi
import com.havivon.reelslab.net.IgSession
import com.havivon.reelslab.ui.FilterIntent
import com.havivon.reelslab.ui.Fmt
import java.util.concurrent.Executors

/**
 * Full-screen reel playback. Swipe up for the next one, down for the previous,
 * as in the Instagram app itself.
 */
class PlayerActivity : Activity() {

    private lateinit var db: Db
    private lateinit var session: IgSession
    private lateinit var video: VideoView
    private lateinit var loading: TextView
    private lateinit var accountView: TextView
    private lateinit var captionView: TextView
    private lateinit var statsView: TextView
    private lateinit var watchedButton: Button
    private lateinit var favoriteButton: Button

    private val progress = Handler(Looper.getMainLooper())
    private val background = Executors.newSingleThreadExecutor()

    private var reels: List<Reel> = emptyList()
    private var index = 0
    private var autoMarked = false
    private var refreshedUrlFor: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        db = Db.get(this)
        session = IgSession(this)
        video = findViewById(R.id.video)
        loading = findViewById(R.id.loading)
        accountView = findViewById(R.id.account)
        captionView = findViewById(R.id.caption)
        statsView = findViewById(R.id.stats)
        watchedButton = findViewById(R.id.toggle_watched)
        favoriteButton = findViewById(R.id.toggle_favorite)

        reels = db.reels(FilterIntent.read(intent))
        index = intent.getIntExtra(FilterIntent.POSITION, 0).coerceIn(0, maxOf(reels.size - 1, 0))
        if (reels.isEmpty()) {
            finish()
            return
        }

        watchedButton.setOnClickListener { setWatched(!current().isSeen) }
        favoriteButton.setOnClickListener { toggleFavorite() }
        findViewById<Button>(R.id.open_instagram).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(current().instagramUrl)))
        }

        setUpGestures()
        video.setOnPreparedListener { player ->
            player.isLooping = true
            loading.text = ""
        }
        video.setOnErrorListener { _, _, _ -> onPlaybackFailed() }

        show(index)
    }

    private fun current() = reels[index]

    private fun setUpGestures() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent) = true

            override fun onSingleTapUp(event: MotionEvent): Boolean {
                if (video.isPlaying) video.pause() else video.start()
                return true
            }

            override fun onFling(
                down: MotionEvent?,
                up: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                val start = down ?: return false
                val dx = up.x - start.x
                val dy = up.y - start.y
                if (kotlin.math.abs(dy) < SWIPE_THRESHOLD && kotlin.math.abs(dx) < SWIPE_THRESHOLD) {
                    return false
                }
                if (kotlin.math.abs(dy) > kotlin.math.abs(dx)) {
                    step(if (dy < 0) 1 else -1)
                } else {
                    step(if (dx < 0) 1 else -1)
                }
                return true
            }
        })
        findViewById<View>(R.id.player_root).setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
        }
    }

    private fun step(delta: Int) {
        val next = index + delta
        if (next !in reels.indices) {
            Toast.makeText(this, R.string.end_of_list, Toast.LENGTH_SHORT).show()
            return
        }
        index = next
        show(index)
    }

    private fun show(position: Int) {
        val reel = reels[position]
        autoMarked = false
        refreshedUrlFor = null

        accountView.text = "@${reel.accountUsername} · ${Fmt.dateFromSeconds(reel.takenAt)}"
        captionView.text = reel.caption
        statsView.text = listOf(
            "▶ ${Fmt.count(reel.playCount)}",
            "♥ ${Fmt.count(reel.likeCount)}",
            "💬 ${Fmt.count(reel.commentCount)}",
            Fmt.percent(reel.engagementRate),
            Fmt.duration(reel.duration),
        ).joinToString("   ")
        renderButtons()

        if (reel.videoUrl.isEmpty()) {
            onPlaybackFailed()
            return
        }
        loading.text = getString(R.string.loading)
        video.setVideoURI(Uri.parse(reel.videoUrl))
        video.start()
        watchProgress()
    }

    private fun renderButtons() {
        val reel = current()
        watchedButton.text = getString(
            if (reel.isSeen) R.string.mark_unwatched else R.string.mark_watched
        )
        favoriteButton.text = if (reel.isFavorite) "★" else "☆"
    }

    /** Marks the reel seen once most of it has actually played. */
    private fun watchProgress() {
        progress.removeCallbacksAndMessages(null)
        progress.postDelayed(object : Runnable {
            override fun run() {
                val reel = current()
                if (!autoMarked && !reel.isSeen && video.duration > 0) {
                    val played = video.currentPosition.toDouble() / video.duration
                    if (played >= WATCHED_RATIO) {
                        autoMarked = true
                        setWatched(true)
                    }
                }
                progress.postDelayed(this, 500)
            }
        }, 500)
    }

    private fun setWatched(watched: Boolean) {
        val reel = current()
        db.setWatched(reel.pk, watched)
        reel.watchedAt = if (watched) System.currentTimeMillis() else null
        renderButtons()
    }

    private fun toggleFavorite() {
        val reel = current()
        reel.isFavorite = !reel.isFavorite
        db.setFavorite(reel.pk, reel.isFavorite)
        renderButtons()
    }

    /**
     * Signed CDN links expire. Re-resolve this one reel and try again, once.
     */
    private fun onPlaybackFailed(): Boolean {
        val reel = current()
        if (refreshedUrlFor == reel.pk) {
            loading.text = getString(R.string.video_unavailable)
            return true
        }
        refreshedUrlFor = reel.pk
        loading.text = getString(R.string.refreshing_link)

        background.execute {
            val account = Account(pk = reel.accountPk, username = reel.accountUsername)
            val fresh = runCatching { IgApi.mediaInfo(session, reel.pk, account) }.getOrNull()
            runOnUiThread {
                if (isFinishing || current().pk != reel.pk) return@runOnUiThread
                if (fresh == null || fresh.videoUrl.isEmpty()) {
                    loading.text = getString(R.string.video_unavailable)
                } else {
                    db.upsertReels(listOf(fresh))
                    loading.text = ""
                    video.setVideoURI(Uri.parse(fresh.videoUrl))
                    video.start()
                }
            }
        }
        return true
    }

    override fun onPause() {
        super.onPause()
        video.pause()
        progress.removeCallbacksAndMessages(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        progress.removeCallbacksAndMessages(null)
        background.shutdownNow()
    }

    private companion object {
        const val SWIPE_THRESHOLD = 120f
        const val WATCHED_RATIO = 0.6
    }
}
