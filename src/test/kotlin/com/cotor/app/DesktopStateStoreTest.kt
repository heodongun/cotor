package com.cotor.app

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class DesktopStateStoreTest : FunSpec({
    test("load recovers a state file with one extra trailing brace") {
        val appHome = Files.createTempDirectory("desktop-state-store-home")
        val store = DesktopStateStore { appHome }
        val validState = DesktopAppState(
            companies = listOf(
                Company(
                    id = "company-1",
                    name = "Recovered Company",
                    rootPath = "/tmp/recovered-company",
                    repositoryId = "repo-1",
                    defaultBaseBranch = "master",
                    createdAt = 1L,
                    updatedAt = 1L
                )
            )
        )
        val payload = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        }.encodeToString(DesktopAppState.serializer(), validState) + "\n}"
        Files.createDirectories(appHome)
        Files.writeString(appHome.resolve("state.json"), payload)

        val recovered = store.load()

        recovered.companies.map { it.name } shouldBe listOf("Recovered Company")
        Files.readString(appHome.resolve("state.json")).trimEnd().endsWith("}}") shouldBe false
    }
})
