package com.cotor.presentation.cli

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption.COPY_ATTRIBUTES
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.readSymbolicLink

internal enum class DesktopInstallLayoutKind {
    SOURCE_CHECKOUT,
    PACKAGED_INSTALL
}

internal enum class DesktopInstallAction(
    val scriptName: String,
    val actionLabel: String
) {
    INSTALL("install-desktop-app.sh", "install"),
    UPDATE("update-desktop-app.sh", "update"),
    DELETE("delete-desktop-app.sh", "delete")
}

internal data class DesktopInstallLayout(
    val kind: DesktopInstallLayoutKind,
    val root: Path,
    val desktopBundle: Path? = null
)

internal fun detectDesktopInstallLayout(
    environment: Map<String, String> = System.getenv(),
    cwd: Path = Paths.get("").toAbsolutePath().normalize(),
    codeSourcePath: Path? = defaultCodeSourcePath()
): DesktopInstallLayout? {
    detectPackagedInstallLayout(environment)?.let { return it }

    val explicitRoot = environment["COTOR_PROJECT_ROOT"]
        ?.takeIf { it.isNotBlank() }
        ?.let { Paths.get(it).toAbsolutePath().normalize() }
        ?.takeIf(::isSourceProjectRoot)
    if (explicitRoot != null) {
        return DesktopInstallLayout(DesktopInstallLayoutKind.SOURCE_CHECKOUT, explicitRoot)
    }

    locateSourceProjectRoot(cwd)?.let {
        return DesktopInstallLayout(DesktopInstallLayoutKind.SOURCE_CHECKOUT, it)
    }

    codeSourcePath?.let(::locateSourceProjectRoot)?.let {
        return DesktopInstallLayout(DesktopInstallLayoutKind.SOURCE_CHECKOUT, it)
    }

    return null
}

internal fun detectProjectRoot(
    environment: Map<String, String> = System.getenv(),
    cwd: Path = Paths.get("").toAbsolutePath().normalize(),
    codeSourcePath: Path? = defaultCodeSourcePath()
): Path? {
    return detectDesktopInstallLayout(environment, cwd, codeSourcePath)
        ?.takeIf { it.kind == DesktopInstallLayoutKind.SOURCE_CHECKOUT }
        ?.root
}

internal fun runPackagedDesktopAction(
    layout: DesktopInstallLayout,
    action: DesktopInstallAction,
    environment: Map<String, String> = System.getenv(),
    homeDirectoryProvider: () -> Path = {
        Paths.get(System.getProperty("user.home")).toAbsolutePath().normalize()
    }
): DesktopScriptResult {
    val desktopBundle = layout.desktopBundle ?: layout.root.resolve("desktop").resolve(BUNDLED_DESKTOP_APP_NAME)
    if (!desktopBundle.isDirectory()) {
        return DesktopScriptResult(
            exitCode = 1,
            output = "Missing packaged desktop bundle: $desktopBundle\n"
        )
    }

    return runCatching {
        when (action) {
            DesktopInstallAction.INSTALL, DesktopInstallAction.UPDATE -> {
                val installRoot = resolveDesktopInstallRoot(environment, homeDirectoryProvider)
                installRoot.createDirectories()
                val targetBundle = installRoot.resolve(BUNDLED_DESKTOP_APP_NAME)
                deleteRecursively(targetBundle)
                copyRecursively(desktopBundle, targetBundle)
                DesktopScriptResult(
                    exitCode = 0,
                    output = buildString {
                        appendLine("✅ Cotor Desktop is ready.")
                        appendLine("   Source:    $desktopBundle")
                        appendLine("   Installed: $targetBundle")
                    }
                )
            }

            DesktopInstallAction.DELETE -> {
                val removed = linkedSetOf<Path>()
                val explicitInstallRoot = environment["COTOR_DESKTOP_INSTALL_ROOT"]
                    ?.takeIf { it.isNotBlank() }
                    ?.let { Paths.get(it).toAbsolutePath().normalize() }

                val candidateRoots = buildList {
                    if (explicitInstallRoot != null) {
                        add(explicitInstallRoot)
                    } else {
                        add(Paths.get("/Applications"))
                        add(homeDirectoryProvider().resolve("Applications"))
                    }
                }

                candidateRoots.forEach { root ->
                    val appPath = root.resolve(BUNDLED_DESKTOP_APP_NAME)
                    if (appPath.exists()) {
                        deleteRecursively(appPath)
                        removed.add(appPath)
                    }
                }

                val downloadsDir = homeDirectoryProvider().resolve("Downloads")
                listOf(
                    downloadsDir.resolve(BUNDLED_DESKTOP_APP_NAME),
                    downloadsDir.resolve(BUNDLED_DESKTOP_ZIP_NAME)
                ).forEach { artifact ->
                    if (artifact.exists()) {
                        deleteRecursively(artifact)
                        removed.add(artifact)
                    }
                }

                DesktopScriptResult(
                    exitCode = 0,
                    output = if (removed.isEmpty()) {
                        "ℹ️  No installed Cotor Desktop app or download artifacts were found.\n"
                    } else {
                        buildString {
                            removed.forEach { appendLine("🗑️  Removed $it") }
                            appendLine("✅ Cotor Desktop artifacts were removed.")
                        }
                    }
                )
            }
        }
    }.getOrElse { error ->
        DesktopScriptResult(
            exitCode = 1,
            output = "${error.message ?: error::class.java.simpleName}\n"
        )
    }
}

internal fun resolveDesktopInstallRoot(
    environment: Map<String, String> = System.getenv(),
    homeDirectoryProvider: () -> Path = {
        Paths.get(System.getProperty("user.home")).toAbsolutePath().normalize()
    }
): Path {
    val overrideRoot = environment["COTOR_DESKTOP_INSTALL_ROOT"]
        ?.takeIf { it.isNotBlank() }
        ?.let { Paths.get(it).toAbsolutePath().normalize() }
    if (overrideRoot != null) {
        return overrideRoot
    }

    val systemRoot = Paths.get("/Applications")
    return if (Files.isWritable(systemRoot)) {
        systemRoot
    } else {
        homeDirectoryProvider().resolve("Applications")
    }
}

private fun detectPackagedInstallLayout(environment: Map<String, String>): DesktopInstallLayout? {
    val installKind = environment["COTOR_INSTALL_KIND"]?.trim()?.lowercase()
    if (installKind != "packaged") {
        return null
    }

    val installRoot = environment["COTOR_INSTALL_ROOT"]
        ?.takeIf { it.isNotBlank() }
        ?.let { Paths.get(it).toAbsolutePath().normalize() }
        ?: return null

    val desktopBundle = installRoot.resolve("desktop").resolve(BUNDLED_DESKTOP_APP_NAME)
    if (!installRoot.isDirectory() || !desktopBundle.isDirectory()) {
        return null
    }

    return DesktopInstallLayout(
        kind = DesktopInstallLayoutKind.PACKAGED_INSTALL,
        root = installRoot,
        desktopBundle = desktopBundle
    )
}

private fun defaultCodeSourcePath(): Path? {
    return runCatching {
        Paths.get(InstallCommand::class.java.protectionDomain.codeSource.location.toURI())
            .toAbsolutePath()
            .normalize()
    }.getOrNull()
}

private fun locateSourceProjectRoot(start: Path): Path? {
    var current: Path? = if (Files.isDirectory(start)) start else start.parent
    repeat(8) {
        val candidate = current ?: return null
        if (isSourceProjectRoot(candidate)) return candidate
        current = candidate.parent
    }
    return null
}

private fun isSourceProjectRoot(path: Path): Boolean =
    Files.exists(path.resolve("gradlew")) &&
        Files.exists(path.resolve("build.gradle.kts")) &&
        Files.exists(path.resolve("shell/install-desktop-app.sh")) &&
        Files.exists(path.resolve("macos/Package.swift"))

private fun copyRecursively(source: Path, target: Path) {
    Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
            val relative = source.relativize(dir)
            Files.createDirectories(target.resolve(relative))
            return FileVisitResult.CONTINUE
        }

        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            val destination = target.resolve(source.relativize(file))
            if (file.isSymbolicLink()) {
                Files.createDirectories(destination.parent)
                Files.deleteIfExists(destination)
                Files.createSymbolicLink(destination, file.readSymbolicLink())
            } else {
                Files.copy(file, destination, REPLACE_EXISTING, COPY_ATTRIBUTES)
            }
            return FileVisitResult.CONTINUE
        }
    })
}

private fun deleteRecursively(path: Path) {
    if (!path.exists()) {
        return
    }

    Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            Files.deleteIfExists(file)
            return FileVisitResult.CONTINUE
        }

        override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
            Files.deleteIfExists(dir)
            return FileVisitResult.CONTINUE
        }
    })
}

internal const val BUNDLED_DESKTOP_APP_NAME = "Cotor Desktop.app"
internal const val BUNDLED_DESKTOP_ZIP_NAME = "Cotor-Desktop-macOS.zip"
