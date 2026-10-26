package roma.core

import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.comments.CommentsInfo
import org.schabi.newpipe.extractor.comments.CommentsInfoItem

/**
 * One YouTube comment. [text] is HTML (links, line breaks), as YouTube sends it. [thread] fetches the first page of its
 * replies, null when it has none; YouTube nests replies one level only, so a reply's own [thread] is null.
 */
class Comment(val author: String, val text: String, val likes: String, val ago: String, val replies: Int, val pinned: Boolean, val thread: (() -> CommentPage)?)

/** A page of a song's YouTube comments; [next] fetches the following one, null at the end. */
class CommentPage(val comments: List<Comment>, val disabled: Boolean, val next: (() -> CommentPage)?)

/** Through NewPipe, which the stream lookup already uses. Blocking: call off the main thread. */
object Comments {
    fun of(videoId: String): CommentPage {
        Streams.start()
        val info = CommentsInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
        return page(info, info.relatedItems, info.nextPage)
    }

    private fun page(info: CommentsInfo, items: List<CommentsInfoItem>, next: Page?): CommentPage = CommentPage(
        items.map { Comment(it.uploaderName.orEmpty(), it.commentText?.content.orEmpty(), it.textualLikeCount.orEmpty(), it.textualUploadDate.orEmpty(), it.replyCount, it.isPinned, more(info, it.replies)) },
        info.isCommentsDisabled,
        more(info, next),
    )

    // Replies page on the same way as the comments: a continuation of the same video's comments.
    private fun more(info: CommentsInfo, p: Page?): (() -> CommentPage)? =
        p?.takeIf { Page.isValid(it) }?.let { { CommentsInfo.getMoreItems(info, it).let { r -> page(info, r.items, r.nextPage) } } }
}
