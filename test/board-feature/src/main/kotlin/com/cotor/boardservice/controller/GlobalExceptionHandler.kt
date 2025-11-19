package com.cotor.boardservice.controller

import com.cotor.boardservice.exception.PermissionDeniedException
import com.cotor.boardservice.exception.PostNotFoundException
import com.cotor.boardservice.exception.VersionConflictException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {

    data class ErrorResponse(val code: String, val message: String, val details: Any? = null)

    @ExceptionHandler(PostNotFoundException::class)
    fun handlePostNotFound(ex: PostNotFoundException): ResponseEntity<ErrorResponse> {
        val error = ErrorResponse("POST_NOT_FOUND", ex.message ?: "Post not found")
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error)
    }

    @ExceptionHandler(PermissionDeniedException::class)
    fun handlePermissionDenied(ex: PermissionDeniedException): ResponseEntity<ErrorResponse> {
        val error = ErrorResponse("PERMISSION_DENIED", ex.message ?: "Permission denied")
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error)
    }

    @ExceptionHandler(VersionConflictException::class)
    fun handleVersionConflict(ex: VersionConflictException): ResponseEntity<ErrorResponse> {
        val error = ErrorResponse("VERSION_CONFLICT", ex.message ?: "Version conflict")
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error)
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationExceptions(ex: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val details = ex.bindingResult.fieldErrors.map {
            mapOf(
                "field" to it.field,
                "constraint" to (it.defaultMessage ?: "Invalid")
            )
        }
        val error = ErrorResponse("VALIDATION_ERROR", "Input data is invalid", details)
        return ResponseEntity.badRequest().body(error)
    }

    @ExceptionHandler(Exception::class)
    fun handleGenericException(ex: Exception): ResponseEntity<ErrorResponse> {
        // Log the exception in a real application
        ex.printStackTrace()
        val error = ErrorResponse("INTERNAL_SERVER_ERROR", "An unexpected error occurred")
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error)
    }
}
