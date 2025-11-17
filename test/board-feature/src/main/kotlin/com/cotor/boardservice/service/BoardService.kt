package com.cotor.boardservice.service

import com.cotor.boardservice.domain.Post
import com.cotor.boardservice.dto.PostCreateRequest
import com.cotor.boardservice.dto.PostDetailResponse
import com.cotor.boardservice.dto.PostSummaryResponse
import com.cotor.boardservice.dto.PostUpdateRequest
import com.cotor.boardservice.exception.PermissionDeniedException
import com.cotor.boardservice.exception.PostNotFoundException
import com.cotor.boardservice.exception.VersionConflictException
import com.cotor.boardservice.repository.PostRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class BoardService(
    private val postRepository: PostRepository
) {

    @Transactional
    fun createPost(request: PostCreateRequest): PostDetailResponse {
        val post = Post(
            title = request.title,
            content = request.content,
            authorId = request.authorId,
            authorName = request.authorName
        )
        val savedPost = postRepository.save(post)
        return PostDetailResponse.fromEntity(savedPost)
    }

    @Transactional(readOnly = true)
    fun getPosts(pageable: Pageable, authorId: Long?): Page<PostSummaryResponse> {
        val posts = authorId?.let {
            postRepository.findByAuthorId(it, pageable)
        } ?: postRepository.findAll(pageable)
        
        return posts.map { PostSummaryResponse.fromEntity(it) }
    }

    @Transactional
    fun getPostById(id: Long): PostDetailResponse {
        val post = findPostByIdOrThrow(id)
        post.viewCount++
        return PostDetailResponse.fromEntity(post)
    }

    @Transactional
    fun updatePost(id: Long, request: PostUpdateRequest, userId: Long): PostDetailResponse {
        val post = findPostByIdOrThrow(id)

        if (post.authorId != userId) {
            throw PermissionDeniedException("You do not have permission to edit this post.")
        }

        if (post.version != request.version) {
            throw VersionConflictException("This post has been modified by someone else. Please refresh and try again.")
        }

        post.title = request.title
        post.content = request.content
        
        return PostDetailResponse.fromEntity(post)
    }

    @Transactional
    fun deletePost(id: Long, userId: Long) {
        val post = findPostByIdOrThrow(id)

        if (post.authorId != userId) {
            throw PermissionDeniedException("You do not have permission to delete this post.")
        }

        postRepository.delete(post)
    }

    private fun findPostByIdOrThrow(id: Long): Post {
        return postRepository.findById(id)
            .orElseThrow { PostNotFoundException("Post with ID $id not found.") }
    }
}
