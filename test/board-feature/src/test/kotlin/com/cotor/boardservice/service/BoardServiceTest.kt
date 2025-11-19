package com.cotor.boardservice.service

import com.cotor.boardservice.domain.Post
import com.cotor.boardservice.dto.PostCreateRequest
import com.cotor.boardservice.dto.PostUpdateRequest
import com.cotor.boardservice.exception.PermissionDeniedException
import com.cotor.boardservice.exception.PostNotFoundException
import com.cotor.boardservice.exception.VersionConflictException
import com.cotor.boardservice.repository.PostRepository
import io.mockk.every
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.justRun
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.data.repository.findByIdOrNull
import java.util.*

@ExtendWith(MockKExtension::class)
class BoardServiceTest {

    @MockK
    private lateinit var postRepository: PostRepository

    @InjectMockKs
    private lateinit var boardService: BoardService

    @Test
    @DisplayName("게시글 생성 - 성공")
    fun `createPost should save a new post and return its details`() {
        // Given
        val request = PostCreateRequest("Test Title", "Test Content", 1L, "Tester")
        val post = Post(
            id = 1L,
            title = request.title,
            content = request.content,
            authorId = request.authorId,
            authorName = request.authorName
        )
        every { postRepository.save(any()) } returns post

        // When
        val result = boardService.createPost(request)

        // Then
        assertEquals(post.id, result.id)
        assertEquals(request.title, result.title)
        verify(exactly = 1) { postRepository.save(any()) }
    }

    @Test
    @DisplayName("ID로 게시글 상세 조회 - 성공")
    fun `getPostById should return post details and increment view count`() {
        // Given
        val postId = 1L
        val post = Post(id = postId, title = "Title", content = "Content", authorId = 1L, authorName = "Tester", viewCount = 5)
        every { postRepository.findById(postId) } returns Optional.of(post)

        // When
        val result = boardService.getPostById(postId)

        // Then
        assertEquals(postId, result.id)
        assertEquals(6, result.viewCount) // View count should be incremented
        verify(exactly = 1) { postRepository.findById(postId) }
    }

    @Test
    @DisplayName("ID로 게시글 상세 조회 - 게시글 없음")
    fun `getPostById should throw PostNotFoundException when post does not exist`() {
        // Given
        val postId = 99L
        every { postRepository.findById(postId) } returns Optional.empty()

        // When & Then
        assertThrows<PostNotFoundException> {
            boardService.getPostById(postId)
        }
        verify(exactly = 1) { postRepository.findById(postId) }
    }

    @Test
    @DisplayName("게시글 수정 - 성공")
    fun `updatePost should update the post successfully`() {
        // Given
        val postId = 1L
        val userId = 1L
        val request = PostUpdateRequest("Updated Title", "Updated Content", 1)
        val post = Post(id = postId, title = "Old Title", content = "Old Content", authorId = userId, authorName = "Tester", version = 1)
        
        every { postRepository.findById(postId) } returns Optional.of(post)

        // When
        val result = boardService.updatePost(postId, request, userId)

        // Then
        assertEquals("Updated Title", result.title)
        assertEquals("Updated Content", result.content)
        verify(exactly = 1) { postRepository.findById(postId) }
    }

    @Test
    @DisplayName("게시글 수정 - 권한 없음")
    fun `updatePost should throw PermissionDeniedException for wrong user`() {
        // Given
        val postId = 1L
        val ownerId = 1L
        val attackerId = 2L
        val request = PostUpdateRequest("Updated Title", "Updated Content", 1)
        val post = Post(id = postId, title = "Title", content = "Content", authorId = ownerId, authorName = "Owner", version = 1)

        every { postRepository.findById(postId) } returns Optional.of(post)

        // When & Then
        assertThrows<PermissionDeniedException> {
            boardService.updatePost(postId, request, attackerId)
        }
        verify(exactly = 1) { postRepository.findById(postId) }
    }

    @Test
    @DisplayName("게시글 수정 - 버전 충돌")
    fun `updatePost should throw VersionConflictException for version mismatch`() {
        // Given
        val postId = 1L
        val userId = 1L
        val request = PostUpdateRequest("Updated Title", "Updated Content", 1) // User has version 1
        val post = Post(id = postId, title = "Title", content = "Content", authorId = userId, authorName = "Tester", version = 2) // DB has version 2

        every { postRepository.findById(postId) } returns Optional.of(post)

        // When & Then
        assertThrows<VersionConflictException> {
            boardService.updatePost(postId, request, userId)
        }
        verify(exactly = 1) { postRepository.findById(postId) }
    }

    @Test
    @DisplayName("게시글 삭제 - 성공")
    fun `deletePost should call delete on repository`() {
        // Given
        val postId = 1L
        val userId = 1L
        val post = Post(id = postId, title = "Title", content = "Content", authorId = userId, authorName = "Tester")

        every { postRepository.findById(postId) } returns Optional.of(post)
        justRun { postRepository.delete(post) }

        // When
        boardService.deletePost(postId, userId)

        // Then
        verify(exactly = 1) { postRepository.findById(postId) }
        verify(exactly = 1) { postRepository.delete(post) }
    }

    @Test
    @DisplayName("게시글 삭제 - 권한 없음")
    fun `deletePost should throw PermissionDeniedException for wrong user`() {
        // Given
        val postId = 1L
        val ownerId = 1L
        val attackerId = 2L
        val post = Post(id = postId, title = "Title", content = "Content", authorId = ownerId, authorName = "Owner")

        every { postRepository.findById(postId) } returns Optional.of(post)

        // When & Then
        assertThrows<PermissionDeniedException> {
            boardService.deletePost(postId, attackerId)
        }
        verify(exactly = 1) { postRepository.findById(postId) }
        verify(exactly = 0) { postRepository.delete(any()) }
    }
}
