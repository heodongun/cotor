package com.cotor.boardservice.dto

import com.cotor.boardservice.domain.Post
import java.time.LocalDateTime

data class PostSummaryResponse(
    val id: Long,
    val title: String,
    val authorId: Long,
    val authorName: String,
    val viewCount: Int,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
) {
    companion object {
        fun fromEntity(post: Post): PostSummaryResponse {
            return PostSummaryResponse(
                id = post.id,
                title = post.title,
                authorId = post.authorId,
                authorName = post.authorName,
                viewCount = post.viewCount,
                createdAt = post.createdAt,
                updatedAt = post.updatedAt
            )
        }
    }
}
