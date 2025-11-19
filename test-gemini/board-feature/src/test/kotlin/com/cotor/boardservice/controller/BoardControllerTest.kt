package com.cotor.boardservice.controller

import com.cotor.boardservice.domain.Post
import com.cotor.boardservice.dto.PostCreateRequest
import com.cotor.boardservice.dto.PostDetailResponse
import com.cotor.boardservice.dto.PostSummaryResponse
import com.cotor.boardservice.dto.PostUpdateRequest
import com.cotor.boardservice.exception.PermissionDeniedException
import com.cotor.boardservice.exception.PostNotFoundException
import com.cotor.boardservice.exception.VersionConflictException
import com.cotor.boardservice.service.BoardService
import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import java.time.LocalDateTime

@WebMvcTest(BoardController::class)
class BoardControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @MockkBean
    private lateinit var boardService: BoardService

    private val baseUrl = "/api/posts"

    private fun createDummyPost(
        id: Long = 0L,
        title: String = "Test Title",
        content: String = "Test Content",
        authorId: Long = 1L,
        authorName: String = "Tester",
        viewCount: Int = 0,
        version: Int = 0
    ): Post {
        return Post(id, title, content, authorId, authorName, viewCount, version)
    }

    @Test
    @DisplayName("게시글 생성 - 성공")
    fun `createPost should return 201 Created with post details`() {
        val request = PostCreateRequest("New Post Title", "New Post Content", 1L, "Author1")
        val dummyPost = createDummyPost(id = 1L, title = request.title, content = request.content, authorId = request.authorId, authorName = request.authorName)
        val response = PostDetailResponse.fromEntity(dummyPost)

        every { boardService.createPost(any()) } returns response

        mockMvc.perform(post(baseUrl)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").value(1L))
            .andExpect(jsonPath("$.title").value("New Post Title"))
    }

    @Test
    @DisplayName("게시글 생성 - 유효성 검사 실패")
    fun `createPost with invalid request should return 400 Bad Request`() {
        val invalidRequest = PostCreateRequest("", "Content", 1L, "Author") // Empty title

        mockMvc.perform(post(baseUrl)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(invalidRequest)))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.details[0].field").value("title"))
    }

    @Test
    @DisplayName("모든 게시글 조회 - 성공")
    fun `getAllPosts should return 200 OK with paginated posts`() {
        val dummyPost1 = createDummyPost(id = 1L, title = "Title 1", content = "Content 1", authorId = 1L, authorName = "Author A", viewCount = 10)
        val dummyPost2 = createDummyPost(id = 2L, title = "Title 2", content = "Content 2", authorId = 2L, authorName = "Author B", viewCount = 5)
        val post1 = PostSummaryResponse.fromEntity(dummyPost1)
        val post2 = PostSummaryResponse.fromEntity(dummyPost2)
        val page = PageImpl(listOf(post1, post2), PageRequest.of(0, 10), 2)

        every { boardService.getPosts(any()) } returns page

        mockMvc.perform(get(baseUrl)
            .param("page", "0")
            .param("size", "10"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content[0].id").value(1L))
            .andExpect(jsonPath("$.content[1].id").value(2L))
            .andExpect(jsonPath("$.totalElements").value(2))
    }

    @Test
    @DisplayName("ID로 게시글 조회 - 성공")
    fun `getPostById should return 200 OK with post details`() {
        val postId = 1L
        val dummyPost = createDummyPost(id = postId, title = "Title", content = "Content", authorId = 1L, authorName = "Author", viewCount = 5)
        val response = PostDetailResponse.fromEntity(dummyPost)

        every { boardService.getPostById(postId) } returns response

        mockMvc.perform(get("$baseUrl/{id}", postId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(postId))
            .andExpect(jsonPath("$.title").value("Title"))
    }

    @Test
    @DisplayName("ID로 게시글 조회 - 게시글 없음")
    fun `getPostById should return 404 Not Found when post does not exist`() {
        val postId = 99L
        every { boardService.getPostById(postId) } throws PostNotFoundException("Post not found with id: $postId")

        mockMvc.perform(get("$baseUrl/{id}", postId))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("POST_NOT_FOUND"))
    }

    @Test
    @DisplayName("게시글 수정 - 성공")
    fun `updatePost should return 200 OK with updated post details`() {
        val postId = 1L
        val userId = 1L
        val request = PostUpdateRequest("Updated Title", "Updated Content", 1)
        val dummyPost = createDummyPost(id = postId, title = request.title, content = request.content, authorId = userId, authorName = "Author", viewCount = 5, version = request.version)
        val response = PostDetailResponse.fromEntity(dummyPost)

        every { boardService.updatePost(postId, any(), userId) } returns response

        mockMvc.perform(put("$baseUrl/{id}", postId)
            .param("userId", userId.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(postId))
            .andExpect(jsonPath("$.title").value("Updated Title"))
    }

    @Test
    @DisplayName("게시글 수정 - 권한 없음")
    fun `updatePost should return 403 Forbidden for permission denied`() {
        val postId = 1L
        val userId = 1L
        val request = PostUpdateRequest("Updated Title", "Updated Content", 1)

        every { boardService.updatePost(postId, any(), userId) } throws PermissionDeniedException("Permission denied")

        mockMvc.perform(put("$baseUrl/{id}", postId)
            .param("userId", userId.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"))
    }

    @Test
    @DisplayName("게시글 수정 - 버전 충돌")
    fun `updatePost should return 409 Conflict for version mismatch`() {
        val postId = 1L
        val userId = 1L
        val request = PostUpdateRequest("Updated Title", "Updated Content", 1)

        every { boardService.updatePost(postId, any(), userId) } throws VersionConflictException("Version conflict")

        mockMvc.perform(put("$baseUrl/{id}", postId)
            .param("userId", userId.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
    }

    @Test
    @DisplayName("게시글 삭제 - 성공")
    fun `deletePost should return 204 No Content`() {
        val postId = 1L
        val userId = 1L

        every { boardService.deletePost(postId, userId) } answers { nothing }

        mockMvc.perform(delete("$baseUrl/{id}", postId)
            .param("userId", userId.toString()))
            .andExpect(status().isNoContent)
    }

    @Test
    @DisplayName("게시글 삭제 - 권한 없음")
    fun `deletePost should return 403 Forbidden for permission denied`() {
        val postId = 1L
        val userId = 1L

        every { boardService.deletePost(postId, userId) } throws PermissionDeniedException("Permission denied")

        mockMvc.perform(delete("$baseUrl/{id}", postId)
            .param("userId", userId.toString()))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"))
    }
}
