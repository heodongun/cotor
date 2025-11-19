package com.cotor.boardservice.domain

import jakarta.persistence.*
import org.hibernate.annotations.SoftDelete
import java.time.LocalDateTime

@Entity
@Table(name = "posts")
@SoftDelete
class Post(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false, length = 255)
    var title: String,

    @Lob
    @Column(nullable = false)
    var content: String,

    @Column(name = "author_id", nullable = false)
    val authorId: Long,

    @Column(name = "author_name", nullable = false, length = 100)
    val authorName: String,

    @Column(name = "view_count")
    var viewCount: Int = 0,

    @Version
    @Column(nullable = false)
    var version: Int = 1,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),

    // @SoftDelete handles this field automatically.
    // It expects a boolean, timestamp, or integer.
    // We can remove the explicit deletedAt field if we let Hibernate manage it,
    // or map it to the column Hibernate uses. Let's keep it simple and remove it
    // as the annotation handles the state. The column will be added by Hibernate.

) {
    @PreUpdate
    fun onPreUpdate() {
        this.updatedAt = LocalDateTime.now()
    }
}
