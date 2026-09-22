package com.havivon.reelslab

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.GridView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.havivon.reelslab.data.Account
import com.havivon.reelslab.data.Db
import com.havivon.reelslab.data.Reel
import com.havivon.reelslab.data.ReelFilter
import com.havivon.reelslab.data.ReelSort
import com.havivon.reelslab.data.ReelStatus
import com.havivon.reelslab.net.IgSession
import com.havivon.reelslab.net.Sync
import com.havivon.reelslab.net.SyncOutcome
import com.havivon.reelslab.ui.FilterIntent
import com.havivon.reelslab.ui.Fmt
import com.havivon.reelslab.ui.ReelsAdapter

class MainActivity : Activity() {

    private lateinit var db: Db
    private lateinit var session: IgSession
    private lateinit var adapter: ReelsAdapter

    private lateinit var grid: GridView
    private lateinit var emptyView: TextView
    private lateinit var statusLine: TextView
    private lateinit var accountSpinner: Spinner
    private lateinit var sortSpinner: Spinner
    private lateinit var chipRow: android.widget.LinearLayout
    private lateinit var search: EditText

    private val searchDebounce = Handler(Looper.getMainLooper())
    private var accounts: List<Account> = emptyList()
    private var filter = ReelFilter()
    private var reels: List<Reel> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        db = Db.get(this)
        session = IgSession(this)
        adapter = ReelsAdapter(this)

        grid = findViewById(R.id.grid)
        emptyView = findViewById(R.id.empty)
        statusLine = findViewById(R.id.status_line)
        accountSpinner = findViewById(R.id.account_spinner)
        sortSpinner = findViewById(R.id.sort_spinner)
        chipRow = findViewById(R.id.status_chips)
        search = findViewById(R.id.search)

        grid.adapter = adapter
        grid.setOnItemClickListener { _, _, position, _ -> openPlayer(position) }
        grid.setOnItemLongClickListener { _, _, position, _ ->
            toggleFavorite(reels[position])
            true
        }

        buildStatusChips()
        buildSortSpinner()
        wireSearch()

        findViewById<Button>(R.id.add_account).setOnClickListener { promptForAccount() }
        findViewById<Button>(R.id.sync_all).setOnClickListener { syncAll() }
        findViewById<Button>(R.id.logout).setOnClickListener { confirmLogout() }
        findViewById<Button>(R.id.mark_all_seen).setOnClickListener { markAllSeen() }

        if (!session.isLoggedIn) startLogin()
    }

    override fun onResume() {
        super.onResume()
        if (session.isLoggedIn) refresh()
    }

    private fun startLogin() {
        startActivityForResult(Intent(this, LoginActivity::class.java), REQUEST_LOGIN)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_LOGIN && resultCode == RESULT_OK) {
            toast(getString(R.string.logged_in))
            refresh()
        }
    }

    // -- filters ----------------------------------------------------------
    private fun buildStatusChips() {
        chipRow.removeAllViews()
        for (status in ReelStatus.values()) {
            val chip = Button(this).apply {
                text = status.label
                textSize = 12f
                minWidth = 0
                minimumWidth = 0
                setOnClickListener {
                    filter = filter.copy(status = status)
                    highlightChips()
                    refresh()
                }
            }
            chipRow.addView(chip)
        }
        highlightChips()
    }

    private fun highlightChips() {
        for (index in 0 until chipRow.childCount) {
            val chip = chipRow.getChildAt(index) as Button
            val selected = ReelStatus.values()[index] == filter.status
            chip.alpha = if (selected) 1f else 0.5f
        }
    }

    private fun buildSortSpinner() {
        val labels = ReelSort.values().map { it.label }
        sortSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, labels
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        sortSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                filter = filter.copy(sort = ReelSort.values()[position])
                refresh()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun wireSearch() {
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                searchDebounce.removeCallbacksAndMessages(null)
                searchDebounce.postDelayed({
                    filter = filter.copy(query = s?.toString()?.trim().orEmpty())
                    refresh()
                }, 350)
            }
        })
    }

    private fun buildAccountSpinner() {
        val labels = mutableListOf(getString(R.string.all_accounts))
        labels += accounts.map { account ->
            val unseen = if (account.unseenCount > 0) " · ${account.unseenCount} חדשים" else ""
            "@${account.username}$unseen"
        }
        val selected = accounts.indexOfFirst { it.pk == filter.accountPk } + 1

        accountSpinner.onItemSelectedListener = null
        accountSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, labels
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        accountSpinner.setSelection(selected, false)

        accountSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val pk = if (position == 0) null else accounts[position - 1].pk
                if (pk != filter.accountPk) {
                    filter = filter.copy(accountPk = pk)
                    refresh()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    // -- data -------------------------------------------------------------
    private fun refresh() {
        accounts = db.accounts()
        buildAccountSpinner()
        reels = db.reels(filter)
        adapter.submit(reels)

        val hasReels = reels.isNotEmpty()
        grid.visibility = if (hasReels) View.VISIBLE else View.GONE
        emptyView.visibility = if (hasReels) View.GONE else View.VISIBLE
        emptyView.text = if (accounts.isEmpty()) {
            getString(R.string.empty_no_accounts)
        } else {
            getString(R.string.empty_no_matches)
        }

        val unseen = accounts.sumOf { it.unseenCount }
        statusLine.text = getString(
            R.string.status_line, Fmt.count(reels.size.toLong()), Fmt.count(unseen.toLong())
        )
    }

    private fun promptForAccount() {
        val input = EditText(this).apply {
            hint = getString(R.string.add_account_hint)
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.add_account_title)
            .setView(input)
            .setPositiveButton(R.string.add) { _, _ ->
                val username = input.text.toString().trim().removePrefix("@")
                if (username.isNotEmpty()) addAccount(username)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun addAccount(username: String) {
        toast(getString(R.string.fetching, username))
        Sync.addAccount(this, username, PAGES_PER_SYNC) { outcome -> onSyncDone(outcome) }
    }

    private fun syncAll() {
        if (accounts.isEmpty()) {
            toast(getString(R.string.empty_no_accounts))
            return
        }
        if (Sync.running) {
            toast(getString(R.string.sync_busy))
            return
        }
        toast(getString(R.string.syncing))
        Sync.syncAll(this, PAGES_PER_SYNC) { outcome -> onSyncDone(outcome) }
    }

    private fun onSyncDone(outcome: SyncOutcome) {
        if (outcome.error != null) {
            toast(outcome.error)
            if (!session.isLoggedIn) startLogin()
        } else {
            toast(getString(R.string.sync_done, outcome.fetched, outcome.fresh))
        }
        refresh()
    }

    private fun markAllSeen() {
        if (reels.isEmpty()) return
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.mark_all_confirm, reels.size))
            .setPositiveButton(R.string.ok) { _, _ ->
                db.markAllWatched(reels.map { it.pk })
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun toggleFavorite(reel: Reel) {
        db.setFavorite(reel.pk, !reel.isFavorite)
        refresh()
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setMessage(R.string.logout_confirm)
            .setPositiveButton(R.string.ok) { _, _ ->
                session.clear()
                startLogin()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openPlayer(position: Int) {
        val intent = Intent(this, PlayerActivity::class.java)
        FilterIntent.write(intent, filter, position)
        startActivity(intent)
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private companion object {
        const val REQUEST_LOGIN = 1
        const val PAGES_PER_SYNC = 3
    }
}
