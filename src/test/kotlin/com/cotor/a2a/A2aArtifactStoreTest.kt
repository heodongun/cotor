package com.cotor.a2a

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class A2aArtifactStoreTest : FunSpec({
    test("artifact store persists and filters artifacts by tenant and issue") {
        val appHome = Files.createTempDirectory("a2a-artifact-store-test")
        val store = A2aArtifactStore(appHomeProvider = { appHome }, maxArtifacts = 10)

        store.append(
            A2aArtifactRegistration(
                id = "artifact-1",
                tenant = A2aTenant(companyId = "company-1"),
                kind = "log",
                label = "artifact-1",
                issueId = "issue-1",
                createdAt = 1L
            )
        )
        store.append(
            A2aArtifactRegistration(
                id = "artifact-2",
                tenant = A2aTenant(companyId = "company-1"),
                kind = "report",
                label = "artifact-2",
                issueId = "issue-2",
                createdAt = 2L
            )
        )
        store.append(
            A2aArtifactRegistration(
                id = "artifact-3",
                tenant = A2aTenant(companyId = "company-2"),
                kind = "log",
                label = "artifact-3",
                issueId = "issue-1",
                createdAt = 3L
            )
        )

        store.count() shouldBe 3
        store.list(A2aTenant(companyId = "company-1"), issueId = "issue-1").map { it.id } shouldBe listOf("artifact-1")
        store.list(A2aTenant(companyId = "company-2")).map { it.id } shouldBe listOf("artifact-3")
    }

    test("artifact store keeps only the latest bounded number of artifacts") {
        val appHome = Files.createTempDirectory("a2a-artifact-store-bounded")
        val store = A2aArtifactStore(appHomeProvider = { appHome }, maxArtifacts = 2)

        repeat(3) { index ->
            store.append(
                A2aArtifactRegistration(
                    id = "artifact-$index",
                    tenant = A2aTenant(companyId = "company-1"),
                    kind = "log",
                    label = "artifact-$index",
                    createdAt = index.toLong()
                )
            )
        }

        store.count() shouldBe 2
        store.list(A2aTenant(companyId = "company-1")).map { it.id } shouldBe listOf("artifact-1", "artifact-2")
    }
})
