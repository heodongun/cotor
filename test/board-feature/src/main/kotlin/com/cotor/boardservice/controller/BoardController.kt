package com.cotor.boardservice.controller

import com.cotor.boardservice.dto.PostCreateRequest
import com.cotor.boardservice.dto.PostDetailResponse
import com.cotor.boardservice.dto.PostSummaryResponse
import com.cotor.boardservice.dto.PostUpdateRequest
import com.cotor.boardservice.service.BoardService
import jakarta.validation.Valid
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/posts")
class BoardController(
    private val boardService: BoardService
) {

    @PostMapping
    fun createPost(@Valid @RequestBody request: PostCreateRequest): ResponseEntity<PostDetailResponse> {
        // In a real app, authorId would be from the security context, not the request body.
        val post = boardService.createPost(request)
        return ResponseEntity.status(HttpStatus.CREATED).body(post)
    }

    @GetMapping
    fun getPosts(
        @PageableDefault(size = 20, sort = ["createdAt"]) pageable: Pageable,
        @RequestParam(required = false) authorId: Long?
    ): ResponseEntity<Page<PostSummaryResponse>> {
        val posts = boardService.getPosts(pageable, authorId)
        return ResponseEntity.ok(posts)
    }

    @GetMapping("/{id}")
    fun getPostById(@PathVariable id: Long): ResponseEntity<PostDetailResponse> {
        val post = boardService.getPostById(id)
        return ResponseEntity.ok(post)
    }

    @PutMapping("/{id}")
    fun updatePost(
        @PathVariable id: Long,
        @Valid @RequestBody request: PostUpdateRequest,
        @RequestHeader("X-USER-ID") userId: Long // Simplified auth
    ): ResponseEntity<PostDetailResponse> {
        val updatedPost = boardService.updatePost(id, request, userId)
        return ResponseEntity.ok(updatedPost)
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deletePost(
        @PathVariable id: Long,
        @RequestHeader("X-USER-ID") userId: Long // Simplified auth
    ) {
        boardService.deletePost(id, userId)
    }
}
