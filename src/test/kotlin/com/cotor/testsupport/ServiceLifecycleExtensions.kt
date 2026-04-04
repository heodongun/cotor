package com.cotor.testsupport

import com.cotor.app.DesktopAppService

/**
 * Guarantees DesktopAppService background jobs are stopped even when a test fails.
 */
suspend fun <T> withDesktopServiceShutdown(
    service: DesktopAppService,
    block: suspend () -> T
): T =
    try {
        block()
    } finally {
        service.shutdown()
    }
