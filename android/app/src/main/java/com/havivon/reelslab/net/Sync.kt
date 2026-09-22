package com.havivon.reelslab.net

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.havivon.reelslab.data.Account
import com.havivon.reelslab.data.Db
import java.util.concurrent.Executors
import kotlin.random.Random

data class SyncOutcome(
    val account: Account? = null,
    val fetched: Int = 0,
    val fresh: Int = 0,
    val error: String? = null,
)

/**
 * All Instagram traffic funnels through one background thread, with a slow
 * randomized gap between page requests. Bursts of parallel calls are the
 * traffic shape that gets an account flagged.
 */
object Sync {

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private const val PAGE_SIZE = 12
    private val PACING_MILLIS = 2_000L..6_000L

    @Volatile
    var running: Boolean = false
        private set

    fun addAccount(context: Context, username: String, pages: Int, onDone: (SyncOutcome) -> Unit) {
        submit(context, onDone) { session, db ->
            val account = IgApi.profile(session, username)
            db.upsertAccount(account)
            fetchPages(session, db, account, pages)
        }
    }

    fun syncAccount(context: Context, account: Account, pages: Int, onDone: (SyncOutcome) -> Unit) {
        submit(context, onDone) { session, db ->
            fetchPages(session, db, account, pages)
        }
    }

    fun syncAll(context: Context, pages: Int, onDone: (SyncOutcome) -> Unit) {
        submit(context, onDone) { session, db ->
            var fetched = 0
            var fresh = 0
            for (account in db.accounts()) {
                val outcome = fetchPages(session, db, account, pages)
                fetched += outcome.fetched
                fresh += outcome.fresh
                pace()
            }
            SyncOutcome(fetched = fetched, fresh = fresh)
        }
    }

    private fun fetchPages(
        session: IgSession,
        db: Db,
        account: Account,
        pages: Int,
    ): SyncOutcome {
        var maxId: String? = null
        var fetched = 0
        var fresh = 0

        for (page in 0 until pages.coerceIn(1, 20)) {
            if (page > 0) pace()
            val result = IgApi.clips(session, account, maxId, PAGE_SIZE)
            fetched += result.reels.size
            fresh += db.upsertReels(result.reels)
            maxId = result.nextMaxId ?: break
        }
        db.markSynced(account.pk)
        return SyncOutcome(account = account, fetched = fetched, fresh = fresh)
    }

    private fun pace() {
        Thread.sleep(Random.nextLong(PACING_MILLIS.first, PACING_MILLIS.last))
    }

    private fun submit(
        context: Context,
        onDone: (SyncOutcome) -> Unit,
        work: (IgSession, Db) -> SyncOutcome,
    ) {
        val appContext = context.applicationContext
        running = true
        executor.execute {
            val outcome = try {
                work(IgSession(appContext), Db.get(appContext))
            } catch (error: Exception) {
                SyncOutcome(error = error.message ?: error.javaClass.simpleName)
            }
            running = false
            main.post { onDone(outcome) }
        }
    }
}
