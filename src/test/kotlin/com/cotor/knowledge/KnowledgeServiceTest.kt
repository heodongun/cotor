package com.cotor.knowledge

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class KnowledgeServiceTest : FunSpec({
    test("remember preserves multiple episodes for the same subject and title when content differs") {
        val appHome = Files.createTempDirectory("knowledge-service-test")
        val service = KnowledgeService(KnowledgeStore { appHome })

        service.remember(
            KnowledgeRecord(
                subjectType = "issue",
                subjectId = "issue-1",
                kind = "qa-feedback",
                title = "QA feedback for Issue",
                content = "First feedback",
                createdAt = 1L
            )
        )
        service.remember(
            KnowledgeRecord(
                subjectType = "issue",
                subjectId = "issue-1",
                kind = "qa-feedback",
                title = "QA feedback for Issue",
                content = "Second feedback",
                createdAt = 2L
            )
        )

        val records = service.inspectIssue("issue-1")

        records shouldHaveSize 2
        records[0].content shouldBe "Second feedback"
        records[0].conflictStatus shouldBe KnowledgeConflictStatus.CONFLICTING
        records[1].content shouldBe "First feedback"
    }
})
