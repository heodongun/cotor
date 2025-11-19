package com.cotor.boardservice.repository

import com.cotor.boardservice.domain.Post
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface PostRepository : JpaRepository<Post, Long> {
    fun findByAuthorId(authorId: Long, pageable: Pageable): Page<Post>
}
