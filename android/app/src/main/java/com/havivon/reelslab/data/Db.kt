package com.havivon.reelslab.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Local store. Instagram exposes no "already watched" flag for reels, so
 * watch_state is the app's own record: a reel is seen once it was played here
 * or marked by hand.
 */
class Db private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, NAME, null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE accounts (
                pk TEXT PRIMARY KEY,
                username TEXT NOT NULL UNIQUE,
                full_name TEXT,
                is_private INTEGER DEFAULT 0,
                follower_count INTEGER DEFAULT 0,
                media_count INTEGER DEFAULT 0,
                profile_pic_url TEXT,
                added_at INTEGER NOT NULL,
                last_synced_at INTEGER
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE reels (
                pk TEXT PRIMARY KEY,
                account_pk TEXT NOT NULL REFERENCES accounts(pk) ON DELETE CASCADE,
                code TEXT,
                taken_at INTEGER,
                caption TEXT,
                play_count INTEGER DEFAULT 0,
                like_count INTEGER DEFAULT 0,
                comment_count INTEGER DEFAULT 0,
                duration REAL DEFAULT 0,
                thumbnail_url TEXT,
                video_url TEXT,
                music_title TEXT,
                first_seen_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_reels_account ON reels(account_pk)")
        db.execSQL("CREATE INDEX idx_reels_taken_at ON reels(taken_at)")
        db.execSQL(
            """
            CREATE TABLE watch_state (
                reel_pk TEXT PRIMARY KEY REFERENCES reels(pk) ON DELETE CASCADE,
                watched_at INTEGER,
                is_favorite INTEGER DEFAULT 0
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS watch_state")
        db.execSQL("DROP TABLE IF EXISTS reels")
        db.execSQL("DROP TABLE IF EXISTS accounts")
        onCreate(db)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    // -- accounts ---------------------------------------------------------
    fun upsertAccount(account: Account) {
        val values = ContentValues().apply {
            put("pk", account.pk)
            put("username", account.username)
            put("full_name", account.fullName)
            put("is_private", if (account.isPrivate) 1 else 0)
            put("follower_count", account.followerCount)
            put("media_count", account.mediaCount)
            put("profile_pic_url", account.profilePicUrl)
            put("added_at", now())
        }
        writableDatabase.insertWithOnConflict(
            "accounts", null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun accounts(): List<Account> {
        val sql = """
            SELECT a.*,
                   COUNT(r.pk) AS reel_count,
                   SUM(CASE WHEN w.watched_at IS NULL AND r.pk IS NOT NULL THEN 1 ELSE 0 END) AS unseen_count
            FROM accounts a
            LEFT JOIN reels r ON r.account_pk = a.pk
            LEFT JOIN watch_state w ON w.reel_pk = r.pk
            GROUP BY a.pk
            ORDER BY LOWER(a.username)
        """.trimIndent()
        return readableDatabase.rawQuery(sql, null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        Account(
                            pk = cursor.str("pk"),
                            username = cursor.str("username"),
                            fullName = cursor.str("full_name"),
                            isPrivate = cursor.int("is_private") == 1,
                            followerCount = cursor.long("follower_count"),
                            mediaCount = cursor.long("media_count"),
                            profilePicUrl = cursor.str("profile_pic_url"),
                            reelCount = cursor.int("reel_count"),
                            unseenCount = cursor.int("unseen_count"),
                        )
                    )
                }
            }
        }
    }

    fun findAccount(username: String): Account? =
        accounts().firstOrNull { it.username.equals(username, ignoreCase = true) }

    fun deleteAccount(pk: String) {
        writableDatabase.delete("accounts", "pk = ?", arrayOf(pk))
    }

    fun markSynced(pk: String) {
        val values = ContentValues().apply { put("last_synced_at", now()) }
        writableDatabase.update("accounts", values, "pk = ?", arrayOf(pk))
    }

    // -- reels ------------------------------------------------------------
    /** Returns how many of these reels had not been stored before. */
    fun upsertReels(reels: List<Reel>): Int {
        var fresh = 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (reel in reels) {
                val known = db.rawQuery(
                    "SELECT 1 FROM reels WHERE pk = ?", arrayOf(reel.pk)
                ).use { it.moveToFirst() }
                if (!known) fresh++
                val values = ContentValues().apply {
                    put("pk", reel.pk)
                    put("account_pk", reel.accountPk)
                    put("code", reel.code)
                    put("taken_at", reel.takenAt)
                    put("caption", reel.caption)
                    put("play_count", reel.playCount)
                    put("like_count", reel.likeCount)
                    put("comment_count", reel.commentCount)
                    put("duration", reel.duration)
                    put("thumbnail_url", reel.thumbnailUrl)
                    put("video_url", reel.videoUrl)
                    put("music_title", reel.musicTitle)
                    put("first_seen_at", now())
                }
                if (known) {
                    values.remove("first_seen_at")
                    db.update("reels", values, "pk = ?", arrayOf(reel.pk))
                } else {
                    db.insert("reels", null, values)
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return fresh
    }

    fun reels(filter: ReelFilter): List<Reel> {
        val where = mutableListOf<String>()
        val args = mutableListOf<String>()

        filter.accountPk?.let {
            where += "r.account_pk = ?"
            args += it
        }
        when (filter.status) {
            ReelStatus.UNSEEN -> where += "w.watched_at IS NULL"
            ReelStatus.SEEN -> where += "w.watched_at IS NOT NULL"
            ReelStatus.FAVORITES -> where += "COALESCE(w.is_favorite, 0) = 1"
            ReelStatus.ALL -> Unit
        }
        if (filter.query.isNotBlank()) {
            where += "(r.caption LIKE ? OR COALESCE(r.music_title, '') LIKE ? OR a.username LIKE ?)"
            repeat(3) { args += "%${filter.query}%" }
        }
        if (filter.minViews > 0) {
            where += "r.play_count >= ?"
            args += filter.minViews.toString()
        }
        if (filter.maxDurationSeconds > 0) {
            where += "r.duration <= ?"
            args += filter.maxDurationSeconds.toString()
        }

        val clause = if (where.isEmpty()) "" else "WHERE " + where.joinToString(" AND ")
        val tiebreak = if (filter.sort == ReelSort.RANDOM) "" else ", r.pk DESC"
        val sql = """
            SELECT r.*, a.username AS account_username,
                   w.watched_at, COALESCE(w.is_favorite, 0) AS is_favorite
            FROM reels r
            JOIN accounts a ON a.pk = r.account_pk
            LEFT JOIN watch_state w ON w.reel_pk = r.pk
            $clause
            ORDER BY ${filter.sort.orderBy}$tiebreak
            LIMIT 500
        """.trimIndent()

        return readableDatabase.rawQuery(sql, args.toTypedArray()).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toReel())
            }
        }
    }

    // -- watch state ------------------------------------------------------
    fun setWatched(reelPk: String, watched: Boolean) {
        ensureWatchRow(reelPk)
        val values = ContentValues()
        if (watched) values.put("watched_at", now()) else values.putNull("watched_at")
        writableDatabase.update("watch_state", values, "reel_pk = ?", arrayOf(reelPk))
    }

    fun setFavorite(reelPk: String, favorite: Boolean) {
        ensureWatchRow(reelPk)
        val values = ContentValues().apply { put("is_favorite", if (favorite) 1 else 0) }
        writableDatabase.update("watch_state", values, "reel_pk = ?", arrayOf(reelPk))
    }

    /** Clears the backlog so "unseen" starts meaning something from today on. */
    fun markAllWatched(reelPks: List<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (pk in reelPks) {
                ensureWatchRow(pk, db)
                val values = ContentValues().apply { put("watched_at", now()) }
                db.update("watch_state", values, "reel_pk = ? AND watched_at IS NULL", arrayOf(pk))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun ensureWatchRow(reelPk: String, db: SQLiteDatabase = writableDatabase) {
        val values = ContentValues().apply { put("reel_pk", reelPk) }
        db.insertWithOnConflict("watch_state", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    private fun now() = System.currentTimeMillis()

    companion object {
        private const val NAME = "reels_lab.db"
        private const val VERSION = 1

        const val ENGAGEMENT =
            "((r.like_count + r.comment_count) * 1.0 / MAX(r.play_count, 1))"

        @Volatile
        private var instance: Db? = null

        /**
         * One shared connection for the whole process. The sync thread writes
         * while the UI thread reads, and separate helper instances would
         * contend for the file lock instead of serializing inside SQLite.
         */
        fun get(context: Context): Db = instance ?: synchronized(this) {
            instance ?: Db(context).also { instance = it }
        }
    }
}

private fun Cursor.str(name: String): String =
    getColumnIndex(name).let { if (it < 0 || isNull(it)) "" else getString(it) }

private fun Cursor.strOrNull(name: String): String? =
    getColumnIndex(name).let { if (it < 0 || isNull(it)) null else getString(it) }

private fun Cursor.int(name: String): Int =
    getColumnIndex(name).let { if (it < 0 || isNull(it)) 0 else getInt(it) }

private fun Cursor.long(name: String): Long =
    getColumnIndex(name).let { if (it < 0 || isNull(it)) 0L else getLong(it) }

private fun Cursor.longOrNull(name: String): Long? =
    getColumnIndex(name).let { if (it < 0 || isNull(it)) null else getLong(it) }

private fun Cursor.dbl(name: String): Double =
    getColumnIndex(name).let { if (it < 0 || isNull(it)) 0.0 else getDouble(it) }

private fun Cursor.toReel() = Reel(
    pk = str("pk"),
    accountPk = str("account_pk"),
    accountUsername = str("account_username"),
    code = str("code"),
    takenAt = long("taken_at"),
    caption = str("caption"),
    playCount = long("play_count"),
    likeCount = long("like_count"),
    commentCount = long("comment_count"),
    duration = dbl("duration"),
    thumbnailUrl = str("thumbnail_url"),
    videoUrl = str("video_url"),
    musicTitle = strOrNull("music_title"),
    watchedAt = longOrNull("watched_at"),
    isFavorite = int("is_favorite") == 1,
)
