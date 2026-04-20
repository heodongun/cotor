package com.cotor.app

/**
 * File overview for AppServerInstanceGuardTest.
 *
 * This file belongs to the test suite that documents expected behavior and protects against regressions.
 * It groups declarations around app server instance guard test so readers can find the owning runtime area quickly.
 * Read here first when tracing behavior that flows through this part of the codebase.
 */

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.ReadableByteChannel
import java.nio.channels.WritableByteChannel
import java.nio.file.Files
import java.nio.file.Path

class AppServerInstanceGuardTest : FunSpec({
    test("desktop app-server lock allows only one live process per app home") {
        val appHome = Files.createTempDirectory("desktop-app-server-lock-home")
        val first = DesktopAppServerInstanceGuard(appHomeProvider = { appHome })
        val second = DesktopAppServerInstanceGuard(appHomeProvider = { appHome })

        val firstRecord = first.acquire(host = "127.0.0.1", port = 8787)
        firstRecord.appHome shouldBe appHome
        Files.exists(firstRecord.metadataPath) shouldBe true

        val error = shouldThrow<IllegalStateException> {
            second.acquire(host = "127.0.0.1", port = 50109)
        }
        error.message shouldBe "Desktop app-server lock is already held in this process for $appHome. Lock=${firstRecord.lockPath}"

        first.release()

        val third = DesktopAppServerInstanceGuard(appHomeProvider = { appHome })
        val secondRecord = third.acquire(host = "127.0.0.1", port = 8787)
        secondRecord.lockPath shouldBe firstRecord.lockPath
        third.release()
    }

    test("desktop app-server lock surfaces tryLock IO failures distinctly") {
        val appHome = Files.createTempDirectory("desktop-app-server-lock-io-home")
        val guard = DesktopAppServerInstanceGuard(
            appHomeProvider = { appHome },
            channelOpener = { lockPath -> IOExceptionFileChannel(lockPath) }
        )

        val error = shouldThrow<IllegalStateException> {
            guard.acquire(host = "127.0.0.1", port = 8787)
        }

        error.message shouldBe "Failed to acquire desktop app-server lock at ${appHome.resolve("runtime").resolve("backend").resolve("app-server.instance.lock")} for $appHome."
        (error.cause is IOException) shouldBe true
    }
})

private class IOExceptionFileChannel(private val lockPath: Path) : FileChannel() {
    private var closed = false

    override fun tryLock(position: Long, size: Long, shared: Boolean): FileLock {
        throw IOException("simulated tryLock failure for $lockPath")
    }

    override fun lock(position: Long, size: Long, shared: Boolean): FileLock {
        throw UnsupportedOperationException()
    }

    override fun implCloseChannel() {
        closed = true
    }

    override fun read(dst: ByteBuffer): Int = throw UnsupportedOperationException()
    override fun read(dsts: Array<out ByteBuffer>, offset: Int, length: Int): Long = throw UnsupportedOperationException()
    override fun write(src: ByteBuffer): Int = throw UnsupportedOperationException()
    override fun write(srcs: Array<out ByteBuffer>, offset: Int, length: Int): Long = throw UnsupportedOperationException()
    override fun position(): Long = 0L
    override fun position(newPosition: Long): FileChannel = this
    override fun size(): Long = 0L
    override fun truncate(size: Long): FileChannel = this
    override fun force(metaData: Boolean) {}
    override fun transferTo(position: Long, count: Long, target: WritableByteChannel): Long = throw UnsupportedOperationException()
    override fun transferFrom(src: ReadableByteChannel, position: Long, count: Long): Long = throw UnsupportedOperationException()
    override fun read(dst: ByteBuffer, position: Long): Int = throw UnsupportedOperationException()
    override fun write(src: ByteBuffer, position: Long): Int = throw UnsupportedOperationException()
    override fun map(mode: MapMode, position: Long, size: Long): MappedByteBuffer = throw UnsupportedOperationException()
}
