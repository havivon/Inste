package com.havivon.reelslab.data

data class Account(
    val pk: String,
    val username: String,
    val fullName: String = "",
    val isPrivate: Boolean = false,
    val followerCount: Long = 0,
    val mediaCount: Long = 0,
    val profilePicUrl: String = "",
    val reelCount: Int = 0,
    val unseenCount: Int = 0,
)

data class Reel(
    val pk: String,
    val accountPk: String,
    val accountUsername: String,
    val code: String,
    val takenAt: Long,
    val caption: String,
    val playCount: Long,
    val likeCount: Long,
    val commentCount: Long,
    val duration: Double,
    val thumbnailUrl: String,
    val videoUrl: String,
    val musicTitle: String?,
    var watchedAt: Long?,
    var isFavorite: Boolean,
) {
    val isSeen: Boolean get() = watchedAt != null

    /** Engagement relative to reach: the ordering the Instagram app never offers. */
    val engagementRate: Double
        get() = (likeCount + commentCount).toDouble() / maxOf(playCount, 1L)

    val instagramUrl: String get() = "https://www.instagram.com/reel/$code/"
}

enum class ReelStatus(val label: String) {
    ALL("הכל"),
    UNSEEN("לא נצפו"),
    SEEN("נצפו"),
    FAVORITES("מועדפים"),
}

enum class ReelSort(val label: String, val orderBy: String) {
    NEWEST("החדשים ביותר", "r.taken_at DESC"),
    OLDEST("הישנים ביותר", "r.taken_at ASC"),
    VIEWS("הכי הרבה צפיות", "r.play_count DESC"),
    VIEWS_ASC("הכי מעט צפיות", "r.play_count ASC"),
    LIKES("הכי הרבה לייקים", "r.like_count DESC"),
    COMMENTS("הכי הרבה תגובות", "r.comment_count DESC"),
    ENGAGEMENT("אחוז מעורבות", "${Db.ENGAGEMENT} DESC"),
    HIDDEN_GEMS("פנינים נסתרות", "${Db.ENGAGEMENT} DESC, r.play_count ASC"),
    SHORTEST("הקצרים ביותר", "r.duration ASC"),
    LONGEST("הארוכים ביותר", "r.duration DESC"),
    RANDOM("אקראי", "RANDOM()"),
}

data class ReelFilter(
    val accountPk: String? = null,
    val status: ReelStatus = ReelStatus.ALL,
    val sort: ReelSort = ReelSort.NEWEST,
    val query: String = "",
    val minViews: Long = 0,
    val maxDurationSeconds: Int = 0,
)

data class ClipsPage(val reels: List<Reel>, val nextMaxId: String?)
