package com.cotor.di

import com.cotor.app.DesktopAppService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldNotBe
import org.koin.core.context.GlobalContext
import org.koin.core.context.stopKoin

class KoinResolutionSmokeTest : FunSpec({
    test("desktop app service resolves from the main koin module") {
        runCatching { stopKoin() }
        initializeCotor()
        try {
            val service = GlobalContext.get().get<DesktopAppService>()
            service shouldNotBe null
        } finally {
            stopKoin()
        }
    }
})
