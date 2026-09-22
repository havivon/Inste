package com.havivon.reelslab.ui

import android.content.Intent
import com.havivon.reelslab.data.ReelFilter
import com.havivon.reelslab.data.ReelSort
import com.havivon.reelslab.data.ReelStatus

/**
 * The player re-runs the same query rather than receiving the reel list, so
 * only the filter travels between activities.
 */
object FilterIntent {

    private const val ACCOUNT = "filter_account"
    private const val STATUS = "filter_status"
    private const val SORT = "filter_sort"
    private const val QUERY = "filter_query"
    private const val MIN_VIEWS = "filter_min_views"
    private const val MAX_DURATION = "filter_max_duration"
    const val POSITION = "start_position"

    fun write(intent: Intent, filter: ReelFilter, position: Int) {
        intent.putExtra(ACCOUNT, filter.accountPk)
        intent.putExtra(STATUS, filter.status.name)
        intent.putExtra(SORT, filter.sort.name)
        intent.putExtra(QUERY, filter.query)
        intent.putExtra(MIN_VIEWS, filter.minViews)
        intent.putExtra(MAX_DURATION, filter.maxDurationSeconds)
        intent.putExtra(POSITION, position)
    }

    fun read(intent: Intent) = ReelFilter(
        accountPk = intent.getStringExtra(ACCOUNT),
        status = enumValueOf(intent.getStringExtra(STATUS), ReelStatus.ALL),
        sort = enumValueOf(intent.getStringExtra(SORT), ReelSort.NEWEST),
        query = intent.getStringExtra(QUERY).orEmpty(),
        minViews = intent.getLongExtra(MIN_VIEWS, 0),
        maxDurationSeconds = intent.getIntExtra(MAX_DURATION, 0),
    )

    private inline fun <reified T : Enum<T>> enumValueOf(name: String?, fallback: T): T =
        runCatching { java.lang.Enum.valueOf(T::class.java, name.orEmpty()) }.getOrDefault(fallback)
}
