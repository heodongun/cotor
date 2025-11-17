package com.cotor.boardservice.dto

import com.cotor.boardservice.domain.Post
import java.time.LocalDateTime

data class PostDetailResponse(
    val id: Long,
    val title: String,
    val content: String,
    val authorId: Long,
    val authorName: String,
    val viewCount: Int,
    val version: Int,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
) {
    companion object {
        fun fromEntity(post: Post): PostDetailResponse {
            return PostDetailResponse(
                id = post.id,
                title = post.title,
                content = post.content,
                authorId = post.authorId,
                authorName = post.authorName,
                viewCount = post.viewCount,
                version = post.version,
                createdAt = post.createdAt,
                updatedAt = post.updatedAt
            )
        }
    }
}
