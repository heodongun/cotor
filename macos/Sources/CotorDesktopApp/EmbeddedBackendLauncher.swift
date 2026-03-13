import Foundation

actor EmbeddedBackendLauncher {
    static let shared = EmbeddedBackendLauncher()

    private let port = 8787
    private var process: Process?

    func ensureRunning() async {
        if await healthCheck() {
            AppLogger.info("Embedded backend already healthy on port \(port).")
            return
        }
        if let process, process.isRunning {
            let becameHealthy = await waitForHealth(timeoutSeconds: 8)
            if becameHealthy {
                AppLogger.info("Embedded backend process recovered without restart.")
                return
            }
            AppLogger.error("Embedded backend process unhealthy. Restarting.")
            await terminateCurrentProcess()
        }
        guard let javaPath = resolveJavaExecutablePath(),
              let jarPath = resolveBundledBackendJarPath() else {
            AppLogger.error("Embedded backend launch failed: missing java or bundled jar.")
            return
        }

        let runtimeDir = defaultDesktopAppHome()
            .appendingPathComponent("runtime", isDirectory: true)
            .appendingPathComponent("backend", isDirectory: true)
        try? FileManager.default.createDirectory(at: runtimeDir, withIntermediateDirectories: true)
        let stdoutPath = runtimeDir.appendingPathComponent("app-server.out.log").path
        let stderrPath = runtimeDir.appendingPathComponent("app-server.err.log").path
        FileManager.default.createFile(atPath: stdoutPath, contents: nil)
        FileManager.default.createFile(atPath: stderrPath, contents: nil)

        let process = Process()
        process.executableURL = URL(fileURLWithPath: javaPath)
        process.arguments = [
            "-jar",
            jarPath,
            "app-server",
            "--port",
            "\(port)",
            "--token",
            DesktopAPI.embeddedAppToken
        ]
        process.environment = mergedEnvironment(javaPath: javaPath)
        process.standardOutput = FileHandle(forWritingAtPath: stdoutPath)
        process.standardError = FileHandle(forWritingAtPath: stderrPath)

        do {
            AppLogger.info("Launching embedded backend on port \(port) with jar \(jarPath).")
            try process.run()
            self.process = process
            let started = await waitForHealth(timeoutSeconds: 10)
            if !started {
                AppLogger.error("Embedded backend failed health check after launch.")
                await terminateCurrentProcess()
            } else {
                AppLogger.info("Embedded backend became healthy on port \(port).")
            }
        } catch {
            self.process = nil
            AppLogger.error("Embedded backend launch threw error: \(error.localizedDescription)")
        }
    }

    func restart() async {
        AppLogger.info("Restarting embedded backend.")
        await terminateCurrentProcess()
        terminateExternalBundledBackendProcesses()
        await ensureRunning()
    }

    func stop() async {
        AppLogger.info("Stopping embedded backend.")
        await terminateCurrentProcess()
        terminateExternalBundledBackendProcesses()
    }

    private func waitForHealth(timeoutSeconds: Int) async -> Bool {
        for _ in 0 ..< timeoutSeconds * 5 {
            if await healthCheck() {
                return true
            }
            try? await Task.sleep(for: .milliseconds(200))
        }
        return false
    }

    private func healthCheck() async -> Bool {
        guard let url = URL(string: "http://127.0.0.1:\(port)/api/app/health") else {
            return false
        }
        var request = URLRequest(url: url)
        request.timeoutInterval = 1.5
        request.setValue("Bearer \(DesktopAPI.embeddedAppToken)", forHTTPHeaderField: "Authorization")
        do {
            let (_, response) = try await URLSession.shared.data(for: request)
            if let http = response as? HTTPURLResponse {
                return (200 ..< 300).contains(http.statusCode)
            }
        } catch {
            return false
        }
        return false
    }

    private func mergedEnvironment(javaPath: String) -> [String: String] {
        var env = ProcessInfo.processInfo.environment
        let javaHome = URL(fileURLWithPath: javaPath).deletingLastPathComponent().deletingLastPathComponent().path
        env["JAVA_HOME"] = env["JAVA_HOME"] ?? javaHome
        let defaultPath = "/usr/bin:/bin:/usr/sbin:/sbin:/opt/homebrew/bin:/usr/local/bin"
        env["PATH"] = [env["PATH"], defaultPath].compactMap { $0 }.joined(separator: ":")
        return env
    }

    private func resolveJavaExecutablePath() -> String? {
        if let javaHome = ProcessInfo.processInfo.environment["JAVA_HOME"], !javaHome.isEmpty {
            let candidate = URL(fileURLWithPath: javaHome).appendingPathComponent("bin/java").path
            if FileManager.default.isExecutableFile(atPath: candidate) {
                return candidate
            }
        }
        let helper = Process()
        helper.executableURL = URL(fileURLWithPath: "/usr/libexec/java_home")
        let pipe = Pipe()
        helper.standardOutput = pipe
        helper.standardError = Pipe()
        do {
            try helper.run()
            helper.waitUntilExit()
            let output = String(data: pipe.fileHandleForReading.readDataToEndOfFile(), encoding: .utf8)?
                .trimmingCharacters(in: .whitespacesAndNewlines)
            if let output, !output.isEmpty {
                let candidate = URL(fileURLWithPath: output).appendingPathComponent("bin/java").path
                if FileManager.default.isExecutableFile(atPath: candidate) {
                    return candidate
                }
            }
        } catch {
            return nil
        }
        let shellCandidates = [
            "/opt/homebrew/bin/java",
            "/usr/local/bin/java",
            "/usr/bin/java"
        ]
        for candidate in shellCandidates where FileManager.default.isExecutableFile(atPath: candidate) {
            return candidate
        }
        return nil
    }

    private func resolveBundledBackendJarPath() -> String? {
        let candidates = [
            Bundle.main.resourceURL?.appendingPathComponent("backend/cotor-backend.jar"),
            Bundle.main.bundleURL.appendingPathComponent("Contents/Resources/backend/cotor-backend.jar"),
            URL(fileURLWithPath: FileManager.default.currentDirectoryPath).appendingPathComponent("build/libs/cotor-backend.jar")
        ]
        for candidate in candidates.compactMap({ $0 }) where FileManager.default.fileExists(atPath: candidate.path) {
            return candidate.path
        }
        return nil
    }

    private func terminateExternalBundledBackendProcesses() {
        guard let jarPath = resolveBundledBackendJarPath() else {
            return
        }
        let killer = Process()
        killer.executableURL = URL(fileURLWithPath: "/usr/bin/pkill")
        killer.arguments = ["-f", jarPath]
        killer.standardOutput = Pipe()
        killer.standardError = Pipe()
        do {
            try killer.run()
            killer.waitUntilExit()
        } catch {
            // Best-effort cleanup only.
        }
    }

    private func defaultDesktopAppHome() -> URL {
        let userHome = FileManager.default.homeDirectoryForCurrentUser
        return userHome
            .appendingPathComponent("Library", isDirectory: true)
            .appendingPathComponent("Application Support", isDirectory: true)
            .appendingPathComponent("CotorDesktop", isDirectory: true)
    }

    private func terminateCurrentProcess() async {
        guard let process else { return }
        if process.isRunning {
            process.terminate()
            for _ in 0 ..< 10 {
                if !process.isRunning {
                    break
                }
                try? await Task.sleep(for: .milliseconds(200))
            }
            if process.isRunning {
                process.interrupt()
            }
        }
        self.process = nil
    }
}
