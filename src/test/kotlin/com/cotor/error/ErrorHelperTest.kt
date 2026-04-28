package com.cotor.error

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain

class ErrorHelperTest : FunSpec({
    test("generic unexpected errors point users to the real issue tracker") {
        val error = ErrorHelper.getErrorMessage(IllegalStateException("boom"))

        error.suggestions shouldContain "Report this issue if it persists: https://github.com/bssm-oss/cotor/issues"
        val staleIssueUrl = listOf("https://github.com/", "your", "username/cotor/issues").joinToString("")
        error.suggestions shouldNotContain "Report this issue if it persists: $staleIssueUrl"
    }
})
