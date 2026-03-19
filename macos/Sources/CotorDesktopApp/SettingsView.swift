import SwiftUI


// MARK: - File Overview
// SettingsView belongs to the native macOS client layer for the Cotor desktop application.
// It collects declarations centered on settings view so the native shell code stays easier to navigate.
// Start with this file when tracing how the desktop client presents, stores, or moves state in this area.

/// Read-only settings surface for the current desktop milestone plus the small
/// amount of app-local preference state that does not belong in the backend.
///
/// Language switching is handled here because it is purely a shell concern and
/// should not require a round trip through the localhost app-server.
struct SettingsView: View {
    @EnvironmentObject private var store: DesktopStore

    var body: some View {
        ZStack {
            ShellCanvas()
                .ignoresSafeArea()

            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    hero

                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 280), spacing: 16)], spacing: 16) {
                        languageCard
                        themeCard
                        executionBackendCard
                        logsCard
                        localPathsCard
                        availableAgentsCard
                        shortcutsCard
                        installCard
                    }
                }
                .padding(24)
            }
        }
    }

    private var hero: some View {
        VStack(alignment: .leading, spacing: 14) {
            ShellSectionHeader(
                eyebrow: store.text(.desktopSettings),
                title: store.text(.runtimeOverview),
                subtitle: store.text(.runtimeOverviewSubtitle)
            )

            LazyVGrid(columns: [GridItem(.adaptive(minimum: 120), spacing: 10)], spacing: 10) {
                ShellStatChip(title: store.text(.agents), value: "\(store.dashboard.settings.availableAgents.count)")
                ShellStatChip(title: store.text(.keyboardShortcuts), value: "\(store.dashboard.settings.shortcuts.bindings.count)")
                ShellStatChip(title: store.text(.repos), value: "\(store.dashboard.repositories.count)")
            }
        }
        .shellCard(accent: ShellPalette.borderStrong)
    }

    private var languageCard: some View {
        settingsCard(
            eyebrow: store.text(.language),
            title: store.text(.language),
            subtitle: store.text(.languageSubtitle)
        ) {
            Picker(store.text(.language), selection: Binding(
                get: { store.language },
                set: { store.setLanguage($0) }
            )) {
                ForEach(AppLanguage.allCases) { language in
                    Text(language.displayName).tag(language)
                }
            }
            .pickerStyle(.segmented)
        }
    }

    private var themeCard: some View {
        settingsCard(
            eyebrow: store.language("Appearance", "화면 모드"),
            title: store.language("Appearance", "화면 모드"),
            subtitle: store.language("Choose system, light, or dark mode for the desktop shell.", "데스크톱 셸의 시스템/라이트/다크 모드를 선택합니다.")
        ) {
            Picker(store.language("Appearance", "화면 모드"), selection: Binding(
                get: { store.theme },
                set: { store.setTheme($0) }
            )) {
                ForEach(AppTheme.allCases) { theme in
                    Text(theme.label(store.language)).tag(theme)
                }
            }
            .pickerStyle(.segmented)
        }
    }

    private var localPathsCard: some View {
        settingsCard(
            eyebrow: store.text(.paths),
            title: store.text(.localPaths),
            subtitle: store.text(.localPathsSubtitle)
        ) {
            valueRow(store.text(.appHome), store.dashboard.settings.appHome)
            valueRow(store.text(.managedRepos), store.dashboard.settings.managedReposRoot)
        }
    }

    private var logsCard: some View {
        settingsCard(
            eyebrow: store.language("Logs", "로그"),
            title: store.language("Runtime Logs", "런타임 로그"),
            subtitle: store.language(
                "Use these files to debug company creation, goal execution, and backend recovery failures.",
                "회사 생성, 목표 실행, 백엔드 복구 실패를 디버깅할 때 이 파일을 확인하세요."
            )
        ) {
            valueRow(store.language("Desktop app log", "데스크톱 앱 로그"), AppLogger.path())
            valueRow(store.language("Backend stdout", "백엔드 표준 출력"), "~/Library/Application Support/CotorDesktop/runtime/backend/app-server.out.log")
            valueRow(store.language("Backend stderr", "백엔드 표준 오류"), "~/Library/Application Support/CotorDesktop/runtime/backend/app-server.err.log")
        }
    }

    private var executionBackendCard: some View {
        settingsCard(
            eyebrow: store.language("Execution Backend", "실행 백엔드"),
            title: store.language("Execution Backend", "실행 백엔드"),
            subtitle: store.language(
                "Choose which runtime executes company agents and test Codex app server connectivity.",
                "회사 에이전트를 어떤 런타임으로 실행할지 고르고 Codex app server 연결을 확인합니다."
            )
        ) {
            VStack(alignment: .leading, spacing: 12) {
                Picker(store.language("Default backend", "기본 백엔드"), selection: Binding(
                    get: { store.defaultBackendKind },
                    set: { store.defaultBackendKind = $0 }
                )) {
                    Text("Local Cotor").tag("LOCAL_COTOR")
                    Text("Codex App Server").tag("CODEX_APP_SERVER")
                }
                .pickerStyle(.segmented)

                VStack(alignment: .leading, spacing: 8) {
                    Text(store.language("Code publish policy", "코드 배포 정책").uppercased())
                        .font(.system(size: 10, weight: .bold, design: .rounded))
                        .foregroundStyle(ShellPalette.muted)
                    Picker(store.language("Code publish policy", "코드 배포 정책"), selection: $store.codePublishMode) {
                        Text(store.language("Require GitHub PR", "GitHub PR 필수")).tag("REQUIRE_GITHUB_PR")
                        Text(store.language("Allow local git", "로컬 git 허용")).tag("ALLOW_LOCAL_GIT")
                    }
                    .pickerStyle(.segmented)
                    Text(
                        store.codePublishMode == "REQUIRE_GITHUB_PR"
                        ? store.language(
                            "Code issues block when origin or gh auth is missing.",
                            "origin 또는 gh auth가 없으면 코드 이슈를 막습니다."
                        )
                        : store.language(
                            "If GitHub publish is unavailable, Cotor may complete code work with local git only.",
                            "GitHub 배포가 안 되면 로컬 git만으로 코드 작업을 완료할 수 있습니다."
                        )
                    )
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(ShellPalette.muted)
                }

                VStack(alignment: .leading, spacing: 10) {
                    Text(store.language("GitHub readiness", "GitHub 준비 상태").uppercased())
                        .font(.system(size: 10, weight: .bold, design: .rounded))
                        .foregroundStyle(ShellPalette.muted)
                    valueRow(store.language("Policy", "정책"), store.codePublishMode == "REQUIRE_GITHUB_PR" ? store.language("Require GitHub PR", "GitHub PR 필수") : store.language("Allow local git fallback", "로컬 git fallback 허용"))
                    valueRow(store.language("gh CLI", "gh CLI"), store.dashboard.settings.githubPublishStatus.ghInstalled ? store.language("Installed", "설치됨") : store.language("Missing", "없음"))
                    valueRow(store.language("gh auth", "gh 인증"), store.dashboard.settings.githubPublishStatus.ghAuthenticated ? store.language("Authenticated", "인증됨") : store.language("Not authenticated", "인증 안 됨"))
                    valueRow(store.language("Origin remote", "origin remote"), store.dashboard.settings.githubPublishStatus.originConfigured ? (store.dashboard.settings.githubPublishStatus.originUrl ?? store.language("Configured", "설정됨")) : store.language("Not configured", "설정 안 됨"))
                    if let companyName = store.dashboard.settings.githubPublishStatus.companyName, !companyName.isEmpty {
                        valueRow(store.language("Checked company", "확인한 회사"), companyName)
                    }
                    if let repositoryPath = store.dashboard.settings.githubPublishStatus.repositoryPath, !repositoryPath.isEmpty {
                        valueRow(store.language("Checked repo", "확인한 저장소"), repositoryPath)
                    }
                    if let message = store.dashboard.settings.githubPublishStatus.message, !message.isEmpty {
                        Text(message)
                            .font(.system(size: 12, weight: .medium))
                            .foregroundStyle(
                                store.dashboard.settings.githubPublishStatus.originConfigured || store.codePublishMode == "ALLOW_LOCAL_GIT"
                                ? ShellPalette.muted
                                : ShellPalette.warning
                            )
                    }
                }

                VStack(alignment: .leading, spacing: 6) {
                    Text(store.language("Codex runtime", "Codex 실행기").uppercased())
                        .font(.system(size: 10, weight: .bold, design: .rounded))
                        .foregroundStyle(ShellPalette.muted)
                    Picker(store.language("Launch mode", "실행 모드"), selection: $store.codexLaunchMode) {
                        Text(store.language("Managed by Cotor", "Cotor가 직접 관리")).tag("MANAGED")
                        Text(store.language("Attached URL", "이미 실행 중인 서버")).tag("ATTACHED")
                    }
                    .pickerStyle(.segmented)
                }

                VStack(alignment: .leading, spacing: 6) {
                    Text(store.language("Command", "명령").uppercased())
                        .font(.system(size: 10, weight: .bold, design: .rounded))
                        .foregroundStyle(ShellPalette.muted)
                    TextField("codex", text: $store.codexCommand)
                        .textFieldStyle(.plain)
                        .font(.system(size: 13, weight: .medium, design: .monospaced))
                        .foregroundStyle(ShellPalette.text)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 10)
                        .background(
                            RoundedRectangle(cornerRadius: ShellMetrics.radiusSmall, style: .continuous)
                                .fill(ShellPalette.panelAlt)
                        )
                        .overlay(
                            RoundedRectangle(cornerRadius: ShellMetrics.radiusSmall, style: .continuous)
                                .stroke(ShellPalette.border)
                        )
                }

                VStack(alignment: .leading, spacing: 6) {
                    Text(store.language("Arguments", "인자").uppercased())
                        .font(.system(size: 10, weight: .bold, design: .rounded))
                        .foregroundStyle(ShellPalette.muted)
                    TextField("app-server --host 127.0.0.1 --port {port}", text: $store.codexArgs)
                        .textFieldStyle(.plain)
                        .font(.system(size: 13, weight: .medium, design: .monospaced))
                        .foregroundStyle(ShellPalette.text)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 10)
                        .background(
                            RoundedRectangle(cornerRadius: ShellMetrics.radiusSmall, style: .continuous)
                                .fill(ShellPalette.panelAlt)
                        )
                        .overlay(
                            RoundedRectangle(cornerRadius: ShellMetrics.radiusSmall, style: .continuous)
                                .stroke(ShellPalette.border)
                        )
                }

                HStack(spacing: 12) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(store.language("Port", "포트").uppercased())
                            .font(.system(size: 10, weight: .bold, design: .rounded))
                            .foregroundStyle(ShellPalette.muted)
                        TextField("8788", text: $store.codexPort)
                            .textFieldStyle(.roundedBorder)
                    }
                    VStack(alignment: .leading, spacing: 6) {
                        Text(store.language("Startup timeout", "기동 제한 시간").uppercased())
                            .font(.system(size: 10, weight: .bold, design: .rounded))
                            .foregroundStyle(ShellPalette.muted)
                        TextField("15", text: $store.codexStartupTimeoutSeconds)
                            .textFieldStyle(.roundedBorder)
                    }
                }

                if store.codexLaunchMode == "ATTACHED" {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(store.language("Attached URL", "연결할 URL").uppercased())
                            .font(.system(size: 10, weight: .bold, design: .rounded))
                            .foregroundStyle(ShellPalette.muted)
                        TextField("http://127.0.0.1:8788", text: $store.codexAppServerBaseURL)
                            .textFieldStyle(.roundedBorder)
                    }
                }

                HStack(spacing: 10) {
                    Button(store.language("Save", "저장")) {
                        Task { await store.saveBackendSettings() }
                    }
                    .buttonStyle(.borderedProminent)

                    Button(store.language("Test connection", "연결 테스트")) {
                        Task { await store.testCodexBackendConnection() }
                    }
                    .buttonStyle(.bordered)

                    if let status = store.codexBackendStatus {
                        Text(status.lifecycleState.uppercased())
                            .font(.system(size: 11, weight: .bold))
                            .foregroundStyle(ShellPalette.text)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 6)
                            .background(ShellPalette.panelAlt)
                            .clipShape(Capsule())
                    }
                }

                if let status = store.backendStatusMessage, !status.isEmpty {
                    Text(status)
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(ShellPalette.warning)
                }

                if let status = store.codexBackendStatus?.message, !status.isEmpty {
                    Text(status)
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(ShellPalette.muted)
                }
            }
        }
    }

    private var availableAgentsCard: some View {
        settingsCard(
            eyebrow: store.text(.catalog),
            title: store.text(.availableAgents),
            subtitle: store.text(.availableAgentsSubtitle)
        ) {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 120), spacing: 10)], spacing: 10) {
                ForEach(store.dashboard.settings.availableAgents, id: \.self) { agent in
                    Text(agent)
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(ShellPalette.text)
                        .frame(maxWidth: .infinity)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 10)
                        .background(
                            RoundedRectangle(cornerRadius: ShellMetrics.radiusSmall, style: .continuous)
                                .fill(ShellPalette.panelAlt)
                        )
                        .overlay(
                            RoundedRectangle(cornerRadius: ShellMetrics.radiusSmall, style: .continuous)
                                .stroke(ShellPalette.border)
                        )
                }
            }
        }
    }

    private var shortcutsCard: some View {
        settingsCard(
            eyebrow: store.text(.input),
            title: store.text(.keyboardShortcuts),
            subtitle: store.text(.keyboardShortcutsSubtitle)
        ) {
            VStack(spacing: 10) {
                ForEach(store.dashboard.settings.shortcuts.bindings) { binding in
                    HStack(spacing: 12) {
                        Text(store.shortcutTitle(binding))
                            .font(.system(size: 13, weight: .medium))
                            .foregroundStyle(ShellPalette.text)
                        Spacer()
                        Text(binding.shortcut)
                            .font(.system(size: 12, weight: .semibold, design: .monospaced))
                            .foregroundStyle(ShellPalette.text)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 6)
                            .background(ShellPalette.panelAlt)
                            .clipShape(Capsule())
                    }
                }
            }
        }
    }

    private var installCard: some View {
        settingsCard(
            eyebrow: store.text(.install),
            title: store.text(.desktopInstaller),
            subtitle: store.text(.desktopInstallerSubtitle)
        ) {
            valueRow(store.text(.installer), "cotor install / cotor update / cotor delete")
            valueRow(store.text(.appLocation), "/Applications/Cotor Desktop.app or ~/Applications/Cotor Desktop.app")
            valueRow(store.text(.downloadArchive), "~/Downloads/Cotor-Desktop-macOS.zip")
        }
    }

    @ViewBuilder
    private func settingsCard<Content: View>(
        eyebrow: String,
        title: String,
        subtitle: String,
        @ViewBuilder content: () -> Content
    ) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            ShellSectionHeader(eyebrow: eyebrow, title: title, subtitle: subtitle)
            content()
        }
        .shellCard()
    }

    @ViewBuilder
    private func valueRow(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label.uppercased())
                .font(.system(size: 10, weight: .bold, design: .rounded))
                .foregroundStyle(ShellPalette.muted)
            Text(value)
                .font(.system(size: 12, design: .monospaced))
                .foregroundStyle(ShellPalette.text)
                .textSelection(.enabled)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(
            RoundedRectangle(cornerRadius: ShellMetrics.radiusSmall, style: .continuous)
                .fill(ShellPalette.panelAlt)
        )
        .overlay(
            RoundedRectangle(cornerRadius: ShellMetrics.radiusSmall, style: .continuous)
                .stroke(ShellPalette.border)
        )
    }
}
