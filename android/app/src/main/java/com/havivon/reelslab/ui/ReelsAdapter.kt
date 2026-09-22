package com.havivon.reelslab.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import com.havivon.reelslab.R
import com.havivon.reelslab.data.Reel

class ReelsAdapter(context: Context) : BaseAdapter() {

    private val inflater = LayoutInflater.from(context)
    private var items: List<Reel> = emptyList()

    fun submit(reels: List<Reel>) {
        items = reels
        notifyDataSetChanged()
    }

    override fun getCount() = items.size

    override fun getItem(position: Int): Reel = items[position]

    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: inflater.inflate(R.layout.item_reel, parent, false)
        val holder = view.tag as? Holder ?: Holder(view).also { view.tag = it }
        val reel = items[position]

        ImageLoader.load(holder.thumb, reel.pk, reel.thumbnailUrl)
        holder.thumb.alpha = if (reel.isSeen) 0.45f else 1f
        holder.badgeUnseen.visibility = if (reel.isSeen) View.GONE else View.VISIBLE
        holder.badgeFavorite.visibility = if (reel.isFavorite) View.VISIBLE else View.GONE
        holder.badgeDuration.text = Fmt.duration(reel.duration)
        holder.account.text = "@${reel.accountUsername} · ${Fmt.dateFromSeconds(reel.takenAt)}"
        holder.metrics.text = "▶ ${Fmt.count(reel.playCount)}   ♥ ${Fmt.count(reel.likeCount)}   " +
            Fmt.percent(reel.engagementRate)
        return view
    }

    private class Holder(view: View) {
        val thumb: AspectImageView = view.findViewById(R.id.thumb)
        val badgeUnseen: TextView = view.findViewById(R.id.badge_unseen)
        val badgeFavorite: TextView = view.findViewById(R.id.badge_favorite)
        val badgeDuration: TextView = view.findViewById(R.id.badge_duration)
        val account: TextView = view.findViewById(R.id.account)
        val metrics: TextView = view.findViewById(R.id.metrics)
    }
}
