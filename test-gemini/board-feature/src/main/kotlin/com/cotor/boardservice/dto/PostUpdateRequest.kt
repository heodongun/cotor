package com.cotor.boardservice.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

data class PostUpdateRequest(
    @field:NotBlank(message = "Title is required")
    @field:Size(max = 255, message = "Title cannot be longer than 255 characters")
    val title: String,

    @field:NotBlank(message = "Content is required")
    val content: String,

    @field:NotNull(message = "Version is required for optimistic locking")
    val version: Int
)
