package com.cotor.boardservice.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class PostCreateRequest(
    @field:NotBlank(message = "Title is required")
    @field:Size(max = 255, message = "Title cannot be longer than 255 characters")
    val title: String,

    @field:NotBlank(message = "Content is required")
    val content: String,

    // In a real application, authorId would likely come from the authenticated user's principal
    val authorId: Long,

    @field:NotBlank(message = "Author name is required")
    @field:Size(max = 100, message = "Author name cannot be longer than 100 characters")
    val authorName: String
)
