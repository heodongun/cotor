import AppKit
import SwiftUI

private enum DesktopLayoutMode {
    case wide
    case stacked
    case compact

    init(width: CGFloat) {
        switch width {
        case ..<980:
            self = .compact
        case ..<1440:
            self = .stacked
        default:
            self = .wide
        }
    }
}

enum CompactSurface: CaseIterable, Identifiable {
    case workspace
    case console
    case inspector

    var id: String {
        switch self {
        case .workspace:
            return "workspace"
        case .console:
            return "console"
        case .inspector:
            return "inspector"
        }
    }
}

private enum GoalSurface: CaseIterable, Identifiable {
    case board
    case canvas

    var id: String {
        switch self {
        case .board:
            return "board"
        case .canvas:
            return "canvas"
        }
    }
}

private enum CompanySidebarSurface: CaseIterable, Identifiable {
    case company
    case goals
    case agents
    case issues

    var id: String {
        switch self {
        case .company:
            return "company"
        case .goals:
            return "goals"
        case .agents:
            return "agents"
        case .issues:
            return "issues"
        }
    }
}

@main
struct CotorDesktopApp: App {
    @Environment(\.scenePhase) private var scenePhase
    @NSApplicationDelegateAdaptor(DesktopAppLifecycleDelegate.self) private var appDelegate
    @StateObject private var store = DesktopStore()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(store)
                .frame(minWidth: 920, minHeight: 700)
                .preferredColorScheme(store.theme.colorScheme)
                .task {
                    await store.bootstrap()
                }
        }
        .onChange(of: scenePhase) { _, newPhase in
            guard newPhase == .active else { return }
            Task {
                await store.bootstrap()
            }
        }
        .windowStyle(.hiddenTitleBar)
        .defaultSize(width: 1480, height: 920)
        .commands {
            DesktopCommandMenu(store: store)
        }

        Settings {
            SettingsView()
                .environmentObject(store)
                .frame(width: 720, height: 560)
        }
    }
}

final class DesktopAppLifecycleDelegate: NSObject, NSApplicationDelegate {
    private var terminationInFlight = false

    func applicationDidFinishLaunching(_ notification: Notification) {
        Task {
            await EmbeddedBackendLauncher.shared.ensureRunning()
        }
    }

    func applicationDidBecomeActive(_ notification: Notification) {
        Task {
            await EmbeddedBackendLauncher.shared.ensureRunning()
        }
    }

    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        if terminationInFlight {
            return .terminateLater
        }
        terminationInFlight = true
        AppLogger.info("App termination requested.")
        Task {
            _ = await EmbeddedBackendLauncher.shared.stop(timeoutSeconds: 5)
            await MainActor.run {
                sender.reply(toApplicationShouldTerminate: true)
                self.terminationInFlight = false
            }
        }
        return .terminateLater
    }
}

/// Adaptive desktop shell with a dense admin-console layout: compact sidebar,
/// session strip, TUI-first center workspace, and an optional bottom drawer.
struct ContentView: View {
    @EnvironmentObject private var store: DesktopStore
    @State private var compactSurface: CompactSurface = .console
    @State private var companySurface: CompanySidebarSurface = .company
    @State private var searchText = ""

    private var l: AppLanguage { store.language }

    var body: some View {
        GeometryReader { geometry in
            let layoutMode = DesktopLayoutMode(width: geometry.size.width)

            ZStack {
                ShellCanvas()
                    .ignoresSafeArea()

                VStack(spacing: 12) {
                    DesktopTopBar(searchText: $searchText)

                    shell(for: layoutMode, size: geometry.size)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
                .padding(12)
            }
        }
        .sheet(isPresented: $store.showingCloneSheet) {
            CloneRepositorySheet()
        }
        .alert(l.text(.requestFailed), isPresented: Binding(
            get: { store.actionErrorMessage != nil },
            set: {
                if !$0 {
                    store.actionErrorMessage = nil
                    store.errorMessage = nil
                }
            }
        )) {
            Button(l.text(.close), role: .cancel) {}
        } message: {
            Text(store.actionErrorMessage ?? "")
        }
        .animation(ShellMotion.spring, value: store.selectedGoalID)
        .animation(ShellMotion.spring, value: store.selectedIssueID)
        .animation(ShellMotion.spring, value: store.selectedRepositoryID)
        .animation(ShellMotion.spring, value: store.selectedWorkspaceID)
        .animation(ShellMotion.spring, value: store.selectedTaskID)
        .animation(ShellMotion.spring, value: store.selectedAgentName)
        .animation(ShellMotion.spring, value: store.inspectorTab)
    }

    @ViewBuilder
    private func shell(for layoutMode: DesktopLayoutMode, size: CGSize) -> some View {
        switch layoutMode {
        case .wide:
            HStack(alignment: .top, spacing: 12) {
                SidebarView(searchText: searchText, companySurface: $companySurface)
                    .frame(width: ShellMetrics.sidebarIdealWidth)
                    .frame(maxHeight: .infinity)

                CenterPaneView(layoutMode: layoutMode, searchText: searchText, companySurface: companySurface)
                    .frame(minWidth: ShellMetrics.contentMinWidth, maxWidth: .infinity, maxHeight: .infinity)
            }
        case .stacked:
            HStack(alignment: .top, spacing: 12) {
                SidebarView(searchText: searchText, companySurface: $companySurface)
                    .frame(width: 286)
                    .frame(maxHeight: .infinity)

                CenterPaneView(layoutMode: layoutMode, searchText: searchText, companySurface: companySurface)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        case .compact:
            VStack(spacing: 12) {
                compactHeader

                Picker(l.text(.surface), selection: $compactSurface) {
                    ForEach(CompactSurface.allCases) { surface in
                        Text(l.compactSurface(surface)).tag(surface)
                    }
                }
                .pickerStyle(.segmented)

                Group {
                    switch compactSurface {
                    case .workspace:
                        SidebarView(searchText: searchText, companySurface: $companySurface)
                    case .console:
                        CenterPaneView(layoutMode: layoutMode, searchText: searchText, companySurface: companySurface)
                    case .inspector:
                        InspectorPaneView()
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
    }

    private var compactHeader: some View {
        HStack(spacing: 10) {
            Image(systemName: "hexagon.lefthalf.filled")
                .font(.system(size: 14, weight: .bold))
                .foregroundStyle(ShellPalette.accent)

            VStack(alignment: .leading, spacing: 3) {
                Text(l.text(.appName))
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(ShellPalette.text)
                Text(store.statusMessage)
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(store.isOffline ? ShellPalette.warning : ShellPalette.muted)
                    .lineLimit(1)
            }

            Spacer()

            ShellStatusPill(
                text: store.isOffline ? l.text(.offlinePreview) : l.text(.liveLocalSession),
                tint: store.isOffline ? ShellPalette.warning : ShellPalette.success
            )
        }
        .padding(14)
        .shellCard()
    }
}

private struct DesktopTopBar: View {
    @EnvironmentObject private var store: DesktopStore
    @Binding var searchText: String
    private var l: AppLanguage { store.language }

    var body: some View {
        HStack(spacing: 12) {
            HStack(spacing: 10) {
                Menu {
                    Picker(l("Shell Mode", "셸 모드"), selection: $store.shellMode) {
                        Text(l("Company", "회사")).tag(AppShellMode.company)
                        Text(l("TUI", "TUI")).tag(AppShellMode.tui)
                    }

                    Divider()

                    Menu(l("Language", "언어")) {
                        ForEach(AppLanguage.allCases) { language in
                            Button(language.displayName) {
                                store.setLanguage(language)
                            }
                        }
                    }

                    Menu(l("Appearance", "화면 모드")) {
                        ForEach(AppTheme.allCases) { theme in
                            Button(theme.label(l)) {
                                store.setTheme(theme)
                            }
                        }
                    }

                    Divider()

                    Button {
                        Task { await store.refreshDashboard() }
                    } label: {
                        Label(l("Refresh", "새로고침"), systemImage: "arrow.clockwise")
                    }

                    Button {
                        store.openSettings()
                    } label: {
                        Label(l("Settings", "설정"), systemImage: "gearshape")
                    }
                } label: {
                    Image(systemName: "line.3.horizontal")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(ShellPalette.text)
                        .frame(width: 34, height: 34)
                        .background(
                            RoundedRectangle(cornerRadius: 10, style: .continuous)
                                .fill(ShellPalette.panelAlt)
                        )
                        .overlay(
                            RoundedRectangle(cornerRadius: 10, style: .continuous)
                                .stroke(ShellPalette.line, lineWidth: 1)
                        )
                }
                .menuStyle(.borderlessButton)

                ZStack {
                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .fill(
                            LinearGradient(
                                colors: [ShellPalette.accent, ShellPalette.accentWarm],
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            )
                        )
                    Image(systemName: "hexagon.righthalf.filled")
                        .font(.system(size: 13, weight: .bold))
                        .foregroundStyle(Color.white.opacity(0.95))
                }
                .frame(width: 30, height: 30)

                VStack(alignment: .leading, spacing: 2) {
                    Text("Cotor")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(ShellPalette.text)
                    Text(topBarSubtitle)
                        .font(.system(size: 11, weight: .medium))
                        .foregroundStyle(ShellPalette.muted)
                }
            }

            Spacer(minLength: 0)

            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass")
                    .foregroundStyle(ShellPalette.muted)
                TextField(l("Search goals, issues, or runs", "목표, 이슈, 실행 검색"), text: $searchText)
                    .textFieldStyle(.plain)
                    .foregroundStyle(ShellPalette.text)
                if !searchText.isEmpty {
                    Button {
                        searchText = ""
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundStyle(ShellPalette.muted)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .frame(maxWidth: 320)
            .background(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(ShellPalette.panelAlt)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .stroke(ShellPalette.line, lineWidth: 1)
            )

            if store.shellMode == .company, let company = store.selectedCompany {
                ShellTag(text: company.name, tint: ShellPalette.accent)
            } else if let workspace = store.selectedWorkspace {
                ShellTag(text: workspace.baseBranch, tint: ShellPalette.accent)
            }

            ShellStatusPill(
                text: store.isOffline ? l.text(.offlinePreview) : l.text(.connected),
                tint: store.isOffline ? ShellPalette.warning : ShellPalette.success
            )

            CompactLanguagePicker()

            CompactThemePicker()

            if store.shellMode == .tui {
                Button {
                    Task { await store.runSelectedTask() }
                } label: {
                    Label(l.text(.runSelectedTask), systemImage: "play.fill")
                }
                .buttonStyle(ShellTopBarButtonStyle(prominent: true))
                .disabled(store.selectedTask == nil)
            }
        }
        .padding(10)
        .background(
            RoundedRectangle(cornerRadius: ShellMetrics.radiusMedium, style: .continuous)
                .fill(ShellPalette.panel)
        )
        .overlay(
            RoundedRectangle(cornerRadius: ShellMetrics.radiusMedium, style: .continuous)
                .stroke(ShellPalette.line, lineWidth: 1)
        )
        .shadow(color: Color.black.opacity(0.08), radius: 10, x: 0, y: 6)
    }

    private var topBarSubtitle: String {
        switch store.shellMode {
        case .company:
            return store.selectedGoal?.title ?? store.selectedCompany?.name ?? l("Autonomous company console", "자율 운영 컴퍼니 콘솔")
        case .tui:
            return store.selectedWorkspace?.name ?? store.selectedRepository?.name ?? l("Workspace TUI", "워크스페이스 TUI")
        }
    }
}

private struct SidebarView: View {
    @EnvironmentObject private var store: DesktopStore
    let searchText: String
    @Binding var companySurface: CompanySidebarSurface
    private var l: AppLanguage { store.language }

    private var filteredGoals: [GoalRecord] {
        let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        let base = store.goals
        guard !query.isEmpty else { return base }
        return base.filter {
            matches(query: query, values: [$0.title, $0.description, $0.status, $0.successMetrics.joined(separator: " ")])
        }
    }

    private var filteredIssues: [IssueRecord] {
        let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        let base = store.issues
        guard !query.isEmpty else { return base }
        return base.filter {
            matches(query: query, values: [$0.title, $0.description, $0.status, $0.kind, $0.acceptanceCriteria.joined(separator: " ")])
        }
    }

    private var filteredRepositories: [RepositoryRecord] {
        let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return store.repositories }

        return store.repositories.filter {
            matches(query: query, values: [
                $0.name,
                $0.localPath,
                $0.defaultBranch,
                $0.remoteUrl,
            ])
        }
    }

    private var filteredTasks: [TaskRecord] {
        let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        let base = store.tasks
        guard !query.isEmpty else { return base }

        return base.filter {
            matches(query: query, values: [
                $0.title,
                $0.prompt,
                $0.status,
                $0.agents.joined(separator: " "),
            ])
        }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                switch store.shellMode {
                case .company:
                    companySidebar
                case .tui:
                    tuiSidebar
                }
            }
            .padding(2)
        }
        .scrollIndicators(.hidden)
        .shellCard()
    }

    private var companySidebar: some View {
        VStack(alignment: .leading, spacing: 14) {
            sidebarHeader
            companySidebarNavigation

            switch companySurface {
            case .company:
                companySection
            case .goals:
                VStack(alignment: .leading, spacing: 14) {
                    goalSection
                    goalComposer
                }
            case .agents:
                VStack(alignment: .leading, spacing: 14) {
                    rosterSection
                    companyAgentComposer
                }
            case .issues:
                issueSection
            }
        }
    }

    private var tuiSidebar: some View {
        Group {
            tuiSidebarHeader
            workspaceSection
            workspaceComposer
            workflowSection
            taskSection
        }
    }

    private var sidebarHeader: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(l("Company Console", "회사 콘솔"))
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(ShellPalette.text)
                }
                Spacer()
                Button {
                    Task { await store.refreshDashboard() }
                } label: {
                    Image(systemName: "arrow.clockwise")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(ShellPalette.text)
                        .padding(9)
                }
                .buttonStyle(.plain)
                .background(
                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .fill(ShellPalette.panelAlt)
                )
                .overlay(
                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .stroke(ShellPalette.line, lineWidth: 1)
                )
            }
        }
    }

    private var companySidebarNavigation: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(l("Navigate", "탐색"))
                .font(.system(size: 10, weight: .bold))
                .foregroundStyle(ShellPalette.faint)
                .tracking(0.7)

            VStack(spacing: 8) {
                companyNavButton(
                    .company,
                    title: l("Company", "회사"),
                    subtitle: l("Folder, runtime, and overview", "폴더, 런타임, 개요"),
                    systemImage: "building.2",
                    badge: nil
                )
                companyNavButton(
                    .goals,
                    title: l("Goals", "목표"),
                    subtitle: l("Goals and creation", "목표 보기와 생성"),
                    systemImage: "flag.2.crossed",
                    badge: "\(store.goals.count)"
                )
                companyNavButton(
                    .agents,
                    title: l("Organization", "조직도"),
                    subtitle: l("Org chart and assigned work", "조직도와 담당 이슈"),
                    systemImage: "person.3.sequence",
                    badge: "\(store.companyAgentDefinitions.count)"
                )
                companyNavButton(
                    .issues,
                    title: l("Issues", "이슈"),
                    subtitle: l("Board and execution state", "보드와 실행 상태"),
                    systemImage: "square.stack.3d.down.right",
                    badge: "\(store.issues.count)"
                )
            }
        }
    }

    private func companyNavButton(
        _ surface: CompanySidebarSurface,
        title: String,
        subtitle: String,
        systemImage: String,
        badge: String?
    ) -> some View {
        Button {
            companySurface = surface
        } label: {
            HStack(alignment: .top, spacing: 10) {
                ZStack {
                    RoundedRectangle(cornerRadius: 9, style: .continuous)
                        .fill(companySurface == surface ? ShellPalette.accentSoft : ShellPalette.panel)
                    Image(systemName: systemImage)
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(companySurface == surface ? ShellPalette.accent : ShellPalette.muted)
                }
                .frame(width: 30, height: 30)

                VStack(alignment: .leading, spacing: 3) {
                    Text(title)
                        .font(.system(size: 12, weight: .semibold))
                        .lineLimit(1)
                    Text(subtitle)
                        .font(.system(size: 10, weight: .medium))
                        .foregroundStyle(ShellPalette.muted)
                        .lineLimit(2)
                }

                Spacer(minLength: 8)

                if let badge, !badge.isEmpty, badge != "0" {
                    ShellTag(text: badge, tint: companySurface == surface ? ShellPalette.accentWarm : ShellPalette.panelRaised)
                }
            }
            .foregroundStyle(companySurface == surface ? ShellPalette.text : ShellPalette.muted)
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(companySurface == surface ? ShellPalette.panelRaised : ShellPalette.panelAlt)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .stroke(companySurface == surface ? ShellPalette.lineStrong : ShellPalette.line, lineWidth: 1)
            )
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private var tuiSidebarHeader: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(l("TUI Workspaces", "TUI 워크스페이스"))
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(ShellPalette.text)
                    Text(l("Pick one workspace and stay focused on the live terminal.", "하나의 워크스페이스를 고르고 라이브 터미널에 집중합니다."))
                        .font(.system(size: 11, weight: .medium))
                        .foregroundStyle(ShellPalette.muted)
                        .lineLimit(2)
                }
                Spacer()
                Button {
                    Task { await store.refreshDashboard() }
                } label: {
                    Image(systemName: "arrow.clockwise")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(ShellPalette.text)
                        .padding(9)
                }
                .buttonStyle(.plain)
                .background(
                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .fill(ShellPalette.panelAlt)
                )
                .overlay(
                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .stroke(ShellPalette.line, lineWidth: 1)
                )
            }

        }
    }

    private var companySection: some View {
        VStack(alignment: .leading, spacing: 12) {
            sectionTitle(
                title: l("Company", "회사"),
                subtitle: l("One company maps to one folder and one runtime.", "회사 하나가 폴더 하나와 런타임 하나를 가집니다.")
            )

            HStack(spacing: 8) {
                ShellTag(text: "\(store.goals.count) \(l("goals", "목표"))", tint: ShellPalette.accent)
                ShellTag(text: "\(store.issues.count) \(l("issues", "이슈"))", tint: ShellPalette.accentWarm)
                if store.dashboard.opsMetrics.readyToMergeCount > 0 {
                    ShellTag(text: "\(store.dashboard.opsMetrics.readyToMergeCount) \(l("ready", "병합대기"))", tint: ShellPalette.success)
                }
            }

            if store.companies.isEmpty {
                EmptyStateView(
                    image: "building.2",
                    title: l("No companies yet", "아직 회사가 없습니다"),
                    subtitle: l("Create a company and point it at a working folder.", "회사를 만들고 작업 폴더를 연결하세요.")
                )
                .frame(height: 150)
            } else {
                VStack(spacing: 8) {
                    ForEach(store.companies) { company in
                        CompanyCard(
                            company: company,
                            runtime: store.companyRuntimes.first(where: { $0.companyId == company.id }),
                            language: l,
                            isSelected: store.selectedCompanyID == company.id
                        ) {
                            Task { await store.selectCompany(company) }
                        }
                    }
                }
            }

            TextField(l("Company name", "회사 이름"), text: $store.newCompanyName)
                .textFieldStyle(.roundedBorder)

            HStack(spacing: 8) {
                TextField(l("Company root path", "회사 루트 경로"), text: $store.newCompanyRootPath)
                    .textFieldStyle(.roundedBorder)

                Button {
                    store.openCompanyRootPicker()
                } label: {
                    Label(l("Choose Folder", "폴더 선택"), systemImage: "folder")
                }
                .buttonStyle(ShellTopBarButtonStyle(prominent: false))
            }

            if !store.availableBranches.isEmpty {
                Picker(l("New workspace base branch", "새 워크스페이스 기준 브랜치"), selection: $store.pendingWorkspaceBaseBranch) {
                    ForEach(store.availableBranches, id: \.self) { branch in
                        Text(branch).tag(branch)
                    }
                }
                .pickerStyle(.menu)
            }

            if let company = store.selectedCompany {
                Picker(l("Company execution backend", "회사 실행 백엔드"), selection: Binding(
                    get: { company.backendKind },
                    set: { newValue in
                        Task { await store.updateSelectedCompanyBackend(kind: newValue) }
                    }
                )) {
                    Text("Local Cotor").tag("LOCAL_COTOR")
                    Text("Codex App Server").tag("CODEX_APP_SERVER")
                }
                .pickerStyle(.menu)

                VStack(alignment: .leading, spacing: 10) {
                    Toggle(isOn: $store.companyLinearSyncEnabled) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(l("Optional: share progress to Linear", "선택 사항: 진행 상황을 Linear에 공유"))
                                .font(.system(size: 12, weight: .semibold))
                                .foregroundStyle(ShellPalette.text)
                            Text(l("Turn this on only if you want Cotor issues and updates to appear in Linear too.", "원할 때만 켜서 Cotor 이슈와 업데이트를 Linear에도 보이게 합니다."))
                                .font(.system(size: 11, weight: .medium))
                                .foregroundStyle(ShellPalette.muted)
                        }
                    }
                    .toggleStyle(.switch)

                    TextField(l("Linear endpoint", "Linear 엔드포인트"), text: $store.companyLinearEndpoint)
                        .textFieldStyle(.roundedBorder)

                    HStack(spacing: 8) {
                        TextField(l("Team ID", "팀 ID"), text: $store.companyLinearTeamID)
                            .textFieldStyle(.roundedBorder)
                        TextField(l("Project ID", "프로젝트 ID"), text: $store.companyLinearProjectID)
                            .textFieldStyle(.roundedBorder)
                    }

                    HStack(spacing: 8) {
                        Button {
                            Task { await store.saveSelectedCompanyLinearSettings() }
                        } label: {
                            Label(l("Save Linear", "Linear 저장"), systemImage: "arrow.triangle.branch")
                                .frame(maxWidth: .infinity)
                                .lineLimit(1)
                        }
                        .buttonStyle(ShellTopBarButtonStyle(prominent: false))

                        Button {
                            Task { await store.resyncSelectedCompanyLinear() }
                        } label: {
                            Label(l("Resync", "다시 동기화"), systemImage: "arrow.clockwise")
                                .frame(maxWidth: .infinity)
                                .lineLimit(1)
                        }
                        .buttonStyle(ShellTopBarButtonStyle(prominent: false))
                        .disabled(!(store.selectedCompany?.linearSyncEnabled ?? false) && !store.companyLinearSyncEnabled)
                    }

                    if let message = store.companyLinearStatusMessage, !message.isEmpty {
                        Text(message)
                            .font(.system(size: 11, weight: .medium))
                            .foregroundStyle(ShellPalette.muted)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .padding(12)
                .background(ShellPalette.panelAlt)
                .clipShape(RoundedRectangle(cornerRadius: ShellMetrics.radiusSmall, style: .continuous))
            }

            VStack(spacing: 8) {
                Button {
                    Task { await store.createCompany() }
                } label: {
                    Label(l("Create Company", "회사 생성"), systemImage: "building.2.crop.circle")
                        .frame(maxWidth: .infinity)
                        .lineLimit(1)
                }
                .buttonStyle(.borderedProminent)
                .disabled(store.newCompanyName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || store.newCompanyRootPath.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)

                LazyVGrid(columns: [
                    GridItem(.flexible(), spacing: 8),
                    GridItem(.flexible(), spacing: 8),
                    GridItem(.flexible(), spacing: 8),
                ], spacing: 8) {
                    Button {
                        Task { await store.startSelectedCompanyRuntime() }
                    } label: {
                        Label(l("Start", "시작"), systemImage: "play.fill")
                            .frame(maxWidth: .infinity)
                            .lineLimit(1)
                    }
                    .buttonStyle(ShellTopBarButtonStyle(prominent: false))
                    .disabled(store.selectedCompany == nil)

                    Button {
                        Task { await store.stopSelectedCompanyRuntime() }
                    } label: {
                        Label(l("Stop", "중지"), systemImage: "stop.fill")
                            .frame(maxWidth: .infinity)
                            .lineLimit(1)
                    }
                    .buttonStyle(ShellTopBarButtonStyle(prominent: false))
                    .disabled(store.selectedCompany == nil)

                    Button {
                        Task { await store.deleteSelectedCompany() }
                    } label: {
                        Label(l("Delete", "삭제"), systemImage: "trash")
                            .frame(maxWidth: .infinity)
                            .lineLimit(1)
                    }
                    .buttonStyle(ShellTopBarButtonStyle(prominent: false))
                    .disabled(store.selectedCompany == nil)
                }
            }
        }
        .padding(14)
        .shellInset()
    }

    private var goalSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            sectionTitle(
                title: l("Goals", "목표"),
                subtitle: l("Pick the single business outcome the company should chase next.", "회사가 다음으로 추적할 단일 비즈니스 목표를 고릅니다.")
            )

            if filteredGoals.isEmpty {
                EmptyStateView(
                    image: "flag.2.crossed",
                    title: l("No goals yet", "아직 목표가 없습니다"),
                    subtitle: l("Create one company goal to bootstrap the autonomous workflow.", "자율 워크플로우를 시작하려면 회사 목표를 하나 만드세요.")
                )
                .frame(height: 150)
            } else {
                VStack(spacing: 8) {
                    ForEach(filteredGoals) { goal in
                        GoalRow(goal: goal, language: l, isSelected: store.selectedGoalID == goal.id) {
                            Task { await store.selectGoal(goal) }
                        }
                    }
                }
            }
        }
    }

    private var goalComposer: some View {
        VStack(alignment: .leading, spacing: 12) {
            sectionTitle(
                title: store.editingGoalID == nil ? l("New Goal", "새 목표") : l("Edit Goal", "목표 수정"),
                subtitle: l("Write one clear outcome. The CEO turns it into issues.", "명확한 목표 하나를 쓰고 필요하면 바로 수정하세요.")
            )

            TextField(l("Goal title", "목표 제목"), text: $store.newGoalTitle)
                .textFieldStyle(.roundedBorder)

            TextEditor(text: $store.newGoalDescription)
                .font(.system(size: 12, design: .default))
                .frame(minHeight: 92)
                .padding(10)
                .scrollContentBackground(.hidden)
                .background(ShellPalette.panelAlt)
                .overlay(
                    RoundedRectangle(cornerRadius: ShellMetrics.radiusSmall, style: .continuous)
                        .stroke(ShellPalette.line, lineWidth: 1)
                )
                .clipShape(RoundedRectangle(cornerRadius: ShellMetrics.radiusSmall, style: .continuous))

            HStack(spacing: 8) {
                Button {
                    Task { await store.createGoal() }
                } label: {
                    Label(
                        store.editingGoalID == nil ? l("Create Goal", "목표 생성") : l("Save Goal", "목표 저장"),
                        systemImage: store.editingGoalID == nil ? "plus.circle.fill" : "checkmark.circle.fill"
                    )
                    .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .disabled(store.newGoalTitle.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)

                if store.editingGoalID != nil {
                    Button {
                        store.cancelGoalEditing()
                    } label: {
                        Label(l("Cancel", "취소"), systemImage: "xmark")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(ShellTopBarButtonStyle(prominent: false))
                } else if let goal = store.selectedGoal {
                    Button {
                        store.beginEditingGoal(goal)
                    } label: {
                        Label(l("Edit Selected", "선택 목표 수정"), systemImage: "pencil")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(ShellTopBarButtonStyle(prominent: false))
                }
            }
        }
        .padding(14)
        .shellInset()
    }

    private var rosterSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            sectionTitle(
                title: l("Derived Roster", "파생 조직도"),
                subtitle: l("Hierarchy and routing are inferred from the agents you defined.", "정의한 에이전트 정보를 바탕으로 hierarchy와 라우팅을 추론합니다.")
            )

            VStack(spacing: 8) {
                ForEach(store.orgProfiles) { profile in
                    HStack(spacing: 10) {
                        Circle()
                            .fill(profile.mergeAuthority ? ShellPalette.accentWarm : ShellPalette.accent)
                            .frame(width: 8, height: 8)
                        VStack(alignment: .leading, spacing: 3) {
                            Text(profile.roleName)
                                .font(.system(size: 12, weight: .semibold))
                                .foregroundStyle(ShellPalette.text)
                            Text("\(profile.executionAgentName) · \(profile.capabilities.joined(separator: " · "))")
                                .font(.system(size: 10, weight: .medium))
                                .foregroundStyle(ShellPalette.muted)
                                .lineLimit(2)
                        }
                        Spacer()
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 10)
                    .background(ShellPalette.panelAlt)
                    .overlay(
                        RoundedRectangle(cornerRadius: 10, style: .continuous)
                            .stroke(ShellPalette.line, lineWidth: 1)
                    )
                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                }
            }
        }
    }

    private var companyAgentComposer: some View {
        VStack(alignment: .leading, spacing: 12) {
            sectionTitle(
                title: store.editingCompanyAgentID == nil ? l("Add Agent", "에이전트 추가") : l("Edit Agent", "에이전트 수정"),
                subtitle: l("Define one agent once, then let the CEO route work and handoffs dynamically.", "에이전트를 한 번 정의하면 CEO가 그 정의를 바탕으로 동적으로 handoff와 라우팅을 만듭니다.")
            )

            HStack(spacing: 10) {
                TextField(l("Title", "직함"), text: $store.newCompanyAgentTitle)
                    .textFieldStyle(.roundedBorder)

                Picker(l("AI CLI", "AI CLI"), selection: Binding(
                    get: {
                        if store.newCompanyAgentCli.isEmpty {
                            return store.preferredCliAgent
                        }
                        return store.newCompanyAgentCli
                    },
                    set: { store.newCompanyAgentCli = $0 }
                )) {
                    ForEach(store.availableCliAgents, id: \.self) { agent in
                        Text(agent).tag(agent)
                    }
                }
                .pickerStyle(.menu)
                .frame(width: 150)
                .disabled(store.availableCliAgents.isEmpty)
            }

            TextField(l("Role summary", "역할 설명"), text: $store.newCompanyAgentRole)
                .textFieldStyle(.roundedBorder)

            TextField(l("Specialties (comma separated)", "전문 분야 (쉼표로 구분)"), text: $store.newCompanyAgentSpecialties)
                .textFieldStyle(.roundedBorder)

            VStack(alignment: .leading, spacing: 6) {
                Text(l("A2A handoff notes", "A2A handoff 메모"))
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(ShellPalette.muted)
                TextEditor(text: $store.newCompanyAgentCollaborationNotes)
                    .scrollContentBackground(.hidden)
                    .frame(minHeight: 72)
                    .padding(8)
                    .background(ShellPalette.panelAlt)
                    .overlay(
                        RoundedRectangle(cornerRadius: 10, style: .continuous)
                            .stroke(ShellPalette.line, lineWidth: 1)
                    )
                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
            }

            VStack(alignment: .leading, spacing: 6) {
                Text(l("Agent memory seed", "에이전트 메모 시드"))
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(ShellPalette.muted)
                TextEditor(text: $store.newCompanyAgentMemoryNotes)
                    .scrollContentBackground(.hidden)
                    .frame(minHeight: 72)
                    .padding(8)
                    .background(ShellPalette.panelAlt)
                    .overlay(
                        RoundedRectangle(cornerRadius: 10, style: .continuous)
                            .stroke(ShellPalette.line, lineWidth: 1)
                    )
                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
            }

            if !store.availableCompanyAgentCollaborators.isEmpty {
                VStack(alignment: .leading, spacing: 8) {
                    Text(l("Preferred collaborators", "선호 협업 에이전트"))
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(ShellPalette.muted)

                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: 8)], spacing: 8) {
                        ForEach(store.availableCompanyAgentCollaborators) { collaborator in
                            let isSelected = store.newCompanyAgentPreferredCollaboratorIDs.contains(collaborator.id)
                            Button {
                                if isSelected {
                                    store.newCompanyAgentPreferredCollaboratorIDs.remove(collaborator.id)
                                } else {
                                    store.newCompanyAgentPreferredCollaboratorIDs.insert(collaborator.id)
                                }
                            } label: {
                                HStack(spacing: 8) {
                                    Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                                        .font(.system(size: 11, weight: .semibold))
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(collaborator.title)
                                            .font(.system(size: 11, weight: .semibold))
                                            .lineLimit(1)
                                        Text(collaborator.agentCli)
                                            .font(.system(size: 10, weight: .medium))
                                            .foregroundStyle(ShellPalette.muted)
                                            .lineLimit(1)
                                    }
                                    Spacer(minLength: 0)
                                }
                                .padding(.horizontal, 10)
                                .padding(.vertical, 9)
                                .background(isSelected ? ShellPalette.panelRaised : ShellPalette.panelAlt)
                                .overlay(
                                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                                        .stroke(isSelected ? ShellPalette.accent : ShellPalette.line, lineWidth: 1)
                                )
                            }
                            .buttonStyle(.plain)
                            .contentShape(Rectangle())
                        }
                    }
                }
            }

            Toggle(isOn: $store.newCompanyAgentEnabled) {
                Text(l("Enabled", "활성화"))
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(ShellPalette.text)
            }
            .toggleStyle(.switch)

            HStack(spacing: 8) {
                Button {
                    Task { await store.createCompanyAgent() }
                } label: {
                    Label(
                        store.editingCompanyAgentID == nil ? l("Add Agent", "에이전트 추가") : l("Save Changes", "변경 저장"),
                        systemImage: store.editingCompanyAgentID == nil ? "person.crop.circle.badge.plus" : "checkmark.circle"
                    )
                    .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .disabled(
                    store.selectedCompany == nil ||
                    store.newCompanyAgentTitle.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
                    store.newCompanyAgentCli.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
                    store.newCompanyAgentRole.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                )

                if store.editingCompanyAgentID != nil {
                    Button {
                        store.cancelEditingCompanyAgent()
                    } label: {
                        Label(l("Cancel", "취소"), systemImage: "xmark")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(ShellTopBarButtonStyle(prominent: false))
                }
            }

            if !store.companyAgentDefinitions.isEmpty {
                VStack(spacing: 8) {
                    ForEach(store.companyAgentDefinitions) { agent in
                        HStack(spacing: 10) {
                            Circle()
                                .fill(agent.enabled ? ShellPalette.success : ShellPalette.warning)
                                .frame(width: 8, height: 8)
                            VStack(alignment: .leading, spacing: 3) {
                                HStack(alignment: .firstTextBaseline, spacing: 8) {
                                    Text(agent.title)
                                        .font(.system(size: 12, weight: .semibold))
                                        .foregroundStyle(ShellPalette.text)
                                        .lineLimit(1)
                                    ShellTag(text: agent.agentCli, tint: ShellPalette.accent)
                                    if store.editingCompanyAgentID == agent.id {
                                        ShellTag(text: l("Editing", "수정 중"), tint: ShellPalette.accentWarm)
                                    }
                                }
                                Text(agent.roleSummary)
                                    .font(.system(size: 10, weight: .medium))
                                    .foregroundStyle(ShellPalette.muted)
                                    .lineLimit(2)
                                if !agent.specialties.isEmpty {
                                    Text(agent.specialties.joined(separator: " · "))
                                        .font(.system(size: 10, weight: .medium))
                                        .foregroundStyle(ShellPalette.accentSoft)
                                        .lineLimit(2)
                                }
                                if !agent.preferredCollaboratorIds.isEmpty {
                                    Text(l("Collaborates with", "협업 대상") + " · " + agent.preferredCollaboratorIds.compactMap { collaboratorId in
                                        store.companyAgentDefinitions.first(where: { $0.id == collaboratorId })?.title
                                    }.joined(separator: ", "))
                                    .font(.system(size: 10, weight: .medium))
                                    .foregroundStyle(ShellPalette.muted)
                                    .lineLimit(2)
                                }
                            }
                            Spacer()
                            Button {
                                store.beginEditingCompanyAgent(agent)
                            } label: {
                                Image(systemName: "pencil")
                                    .font(.system(size: 11, weight: .semibold))
                                    .foregroundStyle(ShellPalette.text)
                                    .frame(width: 30, height: 30)
                                    .background(
                                        RoundedRectangle(cornerRadius: 8, style: .continuous)
                                            .fill(ShellPalette.panelRaised)
                                    )
                            }
                            .buttonStyle(.plain)
                        }
                        .padding(.horizontal, 12)
                        .padding(.vertical, 10)
                        .background(ShellPalette.panelAlt)
                        .overlay(
                            RoundedRectangle(cornerRadius: 10, style: .continuous)
                                .stroke(ShellPalette.line, lineWidth: 1)
                        )
                        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                    }
                }
            }
        }
        .padding(14)
        .shellInset()
    }

    private var workspaceSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            sectionTitle(
                title: l.text(.workspaces),
                subtitle: l("Choose a repository, then a workspace under that repository.", "저장소를 선택한 뒤 그 아래 워크스페이스를 고릅니다.")
            )

            if filteredRepositories.isEmpty {
                EmptyStateView(
                    image: "shippingbox",
                    title: l.text(.noRepositories),
                    subtitle: l.text(.noRepositoriesSubtitle)
                )
                .frame(height: 180)
            } else {
                VStack(spacing: 10) {
                    ForEach(filteredRepositories) { repository in
                        RepositoryStackView(repository: repository, searchText: searchText)
                    }
                }
            }
        }
    }

    private var workspaceComposer: some View {
        VStack(alignment: .leading, spacing: 12) {
            sectionTitle(
                title: l.text(.createWorkspace),
                subtitle: l("Lock a new workspace to the pending base branch for the currently selected repository.", "현재 선택된 저장소의 대기 기준 브랜치에 새 워크스페이스를 고정합니다.")
            )

            if !store.availableBranches.isEmpty {
                Picker(l("New workspace base branch", "새 워크스페이스 기준 브랜치"), selection: $store.pendingWorkspaceBaseBranch) {
                    ForEach(store.availableBranches, id: \.self) { branch in
                        Text(branch).tag(branch)
                    }
                }
                .pickerStyle(.menu)
            }

            TextField(l.text(.newWorkspaceName), text: $store.newWorkspaceName)
                .textFieldStyle(.roundedBorder)

            Button {
                Task { await store.createWorkspace() }
            } label: {
                Label(l.text(.createWorkspace), systemImage: "plus")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .disabled(store.selectedRepository == nil)
        }
        .padding(14)
        .shellInset()
    }

    private var workflowSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            sectionTitle(
                title: l("Workflow Routing", "워크플로우 라우팅"),
                subtitle: l("Pick one lead CLI and optional workers from installed agents.", "설치된 에이전트 중 리더 1개와 워커를 고릅니다.")
            )

            if store.dashboard.settings.availableAgents.isEmpty {
                EmptyStateView(
                    image: "person.2.slash",
                    title: l("No CLIs detected", "감지된 CLI 없음"),
                    subtitle: l("Configure at least one installed AI CLI to launch the interactive TUI.", "실시간 TUI를 시작하려면 최소 하나의 설치된 AI CLI가 필요합니다.")
                )
                .frame(height: 140)
            } else {
                VStack(alignment: .leading, spacing: 10) {
                    Picker(l("Lead AI CLI", "리더 AI CLI"), selection: Binding(
                        get: { store.workflowLeadAgent },
                        set: { store.setWorkflowLeadAgent($0) }
                    )) {
                        ForEach(store.dashboard.settings.availableAgents, id: \.self) { agent in
                            Text(agent).tag(agent)
                        }
                    }
                    .pickerStyle(.menu)

                    FlowLayout(items: store.dashboard.settings.availableAgents, selected: store.agentSelection) { agent in
                        store.toggleWorkflowAgent(agent)
                    }

                    HStack(spacing: 8) {
                        ShellTag(text: "\(l("Lead", "리드")): \(store.workflowLeadAgent)", tint: ShellPalette.accent)
                        ShellTag(text: "\(l("Selected", "선택")): \(store.agentSelection.count)", tint: ShellPalette.accentWarm)
                        Spacer()
                    }
                }
            }
        }
        .padding(14)
        .shellInset()
    }

    private var taskSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            sectionTitle(
                title: l.text(.tasks),
                subtitle: l("Tasks remain the execution infrastructure underneath company issues and workspace runs.", "task는 회사 이슈와 워크스페이스 실행 아래에서 계속 실행 인프라 역할을 합니다.")
            )

            if filteredTasks.isEmpty {
                EmptyStateView(
                    image: "list.bullet.rectangle",
                    title: l("No tasks yet", "아직 task가 없습니다"),
                    subtitle: l("Create or run an issue to populate the execution queue.", "이슈를 생성하거나 실행하면 execution queue가 채워집니다.")
                )
                .frame(height: 150)
            } else {
                VStack(spacing: 8) {
                    ForEach(filteredTasks) { task in
                        SidebarTaskRow(task: task, language: l, isSelected: store.selectedTaskID == task.id) {
                            Task { await store.selectTask(task) }
                        }
                    }
                }
            }
        }
    }

    private var issueSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            sectionTitle(
                title: l("Issues", "이슈"),
                subtitle: l("Issues are the execution units the CEO agent plans, delegates, and routes into runs.", "이슈는 CEO 에이전트가 계획하고 위임하여 실행으로 보내는 단위입니다.")
            )

            if filteredIssues.isEmpty {
                EmptyStateView(
                    image: "square.stack.3d.down.right",
                    title: l("No issues yet", "아직 이슈가 없습니다"),
                    subtitle: l("Select or create a goal to populate the issue queue.", "목표를 선택하거나 생성하면 이슈 큐가 채워집니다.")
                )
                .frame(height: 170)
            } else {
                VStack(spacing: 8) {
                    ForEach(filteredIssues) { issue in
                        IssueSidebarRow(issue: issue, language: l, isSelected: store.selectedIssueID == issue.id) {
                            Task { await store.selectIssue(issue) }
                        }
                    }
                }
            }
        }
    }

    private func sectionTitle(title: String, subtitle: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(ShellPalette.text)
            Text(subtitle)
                .font(.system(size: 10, weight: .medium))
                .foregroundStyle(ShellPalette.muted)
                .lineLimit(2)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

private struct CompactLanguagePicker: View {
    @EnvironmentObject private var store: DesktopStore

    var body: some View {
        HStack(spacing: 4) {
            languageButton(.english, label: "EN")
            languageButton(.korean, label: "KO")
        }
        .padding(4)
        .background(
            RoundedRectangle(cornerRadius: 11, style: .continuous)
                .fill(ShellPalette.panelAlt)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 11, style: .continuous)
                .stroke(ShellPalette.line, lineWidth: 1)
        )
    }

    private func languageButton(_ language: AppLanguage, label: String) -> some View {
        Button {
            store.setLanguage(language)
        } label: {
            Text(label)
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(store.language == language ? ShellPalette.text : ShellPalette.muted)
                .frame(width: 34, height: 28)
                .background(
                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                        .fill(store.language == language ? ShellPalette.panelRaised : Color.clear)
                )
        }
        .buttonStyle(.plain)
    }
}

private struct CompactThemePicker: View {
    @EnvironmentObject private var store: DesktopStore
    private var l: AppLanguage { store.language }

    var body: some View {
        HStack(spacing: 4) {
            themeButton(.light)
            themeButton(.dark)
        }
        .padding(4)
        .background(
            RoundedRectangle(cornerRadius: 11, style: .continuous)
                .fill(ShellPalette.panelAlt)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 11, style: .continuous)
                .stroke(ShellPalette.line, lineWidth: 1)
        )
        .help(l("Choose light or dark mode. Use Settings for system mode.", "라이트/다크 모드를 고릅니다. 시스템 모드는 설정에서 선택합니다."))
    }

    private func themeButton(_ theme: AppTheme) -> some View {
        Button {
            store.setTheme(theme)
        } label: {
            Image(systemName: theme.symbolName)
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(store.theme == theme ? ShellPalette.text : ShellPalette.muted)
                .frame(width: 34, height: 28)
                .background(
                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                        .fill(store.theme == theme ? ShellPalette.panelRaised : Color.clear)
                )
        }
        .buttonStyle(.plain)
        .accessibilityLabel(theme.shortLabel(l))
    }
}

private struct GoalRow: View {
    let goal: GoalRecord
    let language: AppLanguage
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 6) {
                Text(goal.title)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(ShellPalette.text)
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)
                    .frame(maxWidth: .infinity, alignment: .leading)

                Text(goal.description)
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(ShellPalette.muted)
                    .lineLimit(2)

                HStack {
                    ShellTag(text: language.status(goal.status), tint: statusTint(for: goal.status))
                    Spacer()
                }
            }
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(isSelected ? ShellPalette.panelRaised : ShellPalette.panelAlt)
            .overlay(
                RoundedRectangle(cornerRadius: ShellMetrics.radiusSmall, style: .continuous)
                    .stroke(isSelected ? ShellPalette.lineStrong : ShellPalette.line, lineWidth: 1)
            )
            .clipShape(RoundedRectangle(cornerRadius: ShellMetrics.radiusSmall, style: .continuous))
        }
        .buttonStyle(.plain)
    }
}

private struct GoalNodeCard: View {
    let goal: GoalRecord
    let language: AppLanguage
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .top) {
                    StatusSummaryPill(text: language.status(goal.status), tint: statusTint(for: goal.status))
                    Spacer()
                    Image(systemName: "arrow.up.right.square")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(ShellPalette.muted)
                }

                Text(goal.title)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(ShellPalette.text)
                    .multilineTextAlignment(.leading)
                    .lineLimit(3)

                Text(goal.description)
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(ShellPalette.muted)
                    .lineLimit(3)

                HStack(spacing: 8) {
                    StatusSummaryPill(text: "\(language("Metrics", "지표")) \(goal.successMetrics.count)", tint: ShellPalette.accentWarm)
                    Spacer()
                }
            }
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(isSelected ? ShellPalette.panelRaised : ShellPalette.panelAlt)
            .overlay(
                RoundedRectangle(cornerRadius: ShellMetrics.radiusMedium, style: .continuous)
                    .stroke(isSelected ? ShellPalette.lineStrong : ShellPalette.line, lineWidth: 1)
            )
            .clipShape(RoundedRectangle(cornerRadius: ShellMetrics.radiusMedium, style: .continuous))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

private struct StatusSummaryPill: View {
    let text: String
    let tint: Color

    var body: some View {
        HStack(spacing: 6) {
            Circle()
                .fill(tint)
                .frame(width: 7, height: 7)
            Text(text)
                .font(.system(size: 10, weight: .semibold))
                .foregroundStyle(ShellPalette.text)
                .lineLimit(1)
        }
        .padding(.horizontal, 9)
        .padding(.vertical, 6)
        .background(
            Capsule(style: .continuous)
                .fill(tint.opacity(0.14))
        )
        .overlay(
            Capsule(style: .continuous)
                .stroke(tint.opacity(0.45), lineWidth: 1)
        )
    }
}

private struct StatusSummaryLine: View {
    let text: String
    let tint: Color

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            Circle()
                .fill(tint)
                .frame(width: 7, height: 7)
                .padding(.top, 4)
            Text(text)
                .font(.system(size: 11, weight: .medium))
                .foregroundStyle(ShellPalette.muted)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

private struct IssueSummaryRow: View {
    let issue: IssueRecord
    let language: AppLanguage
    var assignee: OrgAgentProfileRecord? = nil

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Circle()
                .fill(statusTint(for: issue.status))
                .frame(width: 8, height: 8)
                .padding(.top, 4)
            VStack(alignment: .leading, spacing: 4) {
                HStack(alignment: .top) {
                    Text(issue.title)
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(ShellPalette.text)
                        .lineLimit(2)
                    Spacer()
                    StatusSummaryPill(text: language.status(issue.status), tint: statusTint(for: issue.status))
                }
                Text(issue.description)
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(ShellPalette.muted)
                    .lineLimit(2)
                HStack(spacing: 8) {
                    StatusSummaryPill(text: issue.kind.uppercased(), tint: ShellPalette.accentSoft)
                    if let assignee {
                        StatusSummaryPill(text: assignee.roleName, tint: ShellPalette.accent)
                    }
                    if let linearIdentifier = issue.linearIssueIdentifier, !linearIdentifier.isEmpty {
                        StatusSummaryPill(text: linearIdentifier, tint: ShellPalette.accentWarm)
                    }
                }
            }
        }
        .padding(12)
        .background(ShellPalette.panelAlt)
        .overlay(
            RoundedRectangle(cornerRadius: ShellMetrics.radiusSmall, style: .continuous)
                .stroke(ShellPalette.line, lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: ShellMetrics.radiusSmall, style: .continuous))
    }
}

private func valueLine(label: String, value: String) -> some View {
    HStack(alignment: .top, spacing: 8) {
        Text(label)
            .font(.system(size: 11, weight: .semibold))
            .foregroundStyle(ShellPalette.muted)
        Spacer(minLength: 8)
        Text(value)
            .font(.system(size: 11, weight: .medium))
            .foregroundStyle(ShellPalette.text)
            .multilineTextAlignment(.trailing)
            .textSelection(.enabled)
    }
}

private struct AgentWorkSummaryRow: View {
    let agent: CompanyAgentDefinitionRecord
    let profile: OrgAgentProfileRecord?
    let issues: [IssueRecord]
    let language: AppLanguage

    private var assignedIssues: [IssueRecord] {
        guard let profile else { return [] }
        return issues
            .filter { $0.assigneeProfileId == profile.id }
            .sorted { $0.updatedAt > $1.updatedAt }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(agent.title)
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(ShellPalette.text)
                    Text("\(agent.agentCli) · \(agent.roleSummary)")
                        .font(.system(size: 10, weight: .medium))
                        .foregroundStyle(ShellPalette.muted)
                        .lineLimit(2)
                }
                Spacer()
                StatusSummaryPill(
                    text: agent.enabled ? "ON" : "OFF",
                    tint: agent.enabled ? ShellPalette.success : ShellPalette.warning
                )
            }

            if let issue = assignedIssues.first {
                IssueSummaryRow(issue: issue, language: language, assignee: profile)
            } else {
                Text(language("No assigned issue", "담당 중인 이슈 없음"))
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(ShellPalette.muted)
            }
        }
        .padding(12)
        .background(ShellPalette.panelAlt)
        .overlay(
            RoundedRectangle(cornerRadius: ShellMetrics.radiusSmall, style: .continuous)
                .stroke(ShellPalette.line, lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: ShellMetrics.radiusSmall, style: .continuous))
    }
}

private struct OrgChartNode: View {
    let profile: OrgAgentProfileRecord
    let language: AppLanguage
    var isLeader: Bool = false

    var body: some View {
        VStack(alignment: .center, spacing: 8) {
            Circle()
                .fill(isLeader ? ShellPalette.accentWarm : ShellPalette.accent)
                .frame(width: 34, height: 34)
                .overlay(
                    Image(systemName: isLeader ? "crown.fill" : "person.fill")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(.white)
                )

            Text(profile.roleName)
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(ShellPalette.text)
                .multilineTextAlignment(.center)
                .lineLimit(2)

            Text(profile.executionAgentName)
                .font(.system(size: 10, weight: .medium))
                .foregroundStyle(ShellPalette.muted)

            StatusSummaryPill(
                text: profile.mergeAuthority ? language("CEO", "CEO") : "\(profile.capabilities.count) \(language("skills", "역량"))",
                tint: profile.mergeAuthority ? ShellPalette.success : ShellPalette.accent
            )
        }
        .padding(14)
        .frame(maxWidth: .infinity, minHeight: 132)
        .background(ShellPalette.panelAlt)
        .overlay(
            RoundedRectangle(cornerRadius: ShellMetrics.radiusMedium, style: .continuous)
                .stroke(isLeader ? ShellPalette.success.opacity(0.6) : ShellPalette.line, lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: ShellMetrics.radiusMedium, style: .continuous))
    }
}

private struct GoalDetailSheet: View {
    let goal: GoalRecord
    let issues: [IssueRecord]
    let language: AppLanguage
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    HStack {
                        StatusSummaryPill(text: language.status(goal.status), tint: statusTint(for: goal.status))
                        Spacer()
                        StatusSummaryPill(text: "\(language("Issues", "이슈")) \(issues.count)", tint: ShellPalette.accent)
                    }

                    Text(goal.title)
                        .font(.system(size: 24, weight: .semibold))
                        .foregroundStyle(ShellPalette.text)

                    Text(goal.description)
                        .font(.system(size: 13, weight: .medium))
                        .foregroundStyle(ShellPalette.muted)
                        .fixedSize(horizontal: false, vertical: true)

                    if !goal.successMetrics.isEmpty {
                        VStack(alignment: .leading, spacing: 8) {
                            Text(language("Success Metrics", "성공 지표"))
                                .font(.system(size: 12, weight: .semibold))
                                .foregroundStyle(ShellPalette.text)
                            ForEach(goal.successMetrics, id: \.self) { metric in
                                StatusSummaryLine(text: metric, tint: ShellPalette.accent)
                            }
                        }
                    }

                    VStack(alignment: .leading, spacing: 8) {
                        Text(language("Derived Issues", "파생 이슈"))
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(ShellPalette.text)
                        if issues.isEmpty {
                            Text(language("No derived issues yet.", "아직 파생된 이슈가 없습니다."))
                                .font(.system(size: 11, weight: .medium))
                                .foregroundStyle(ShellPalette.muted)
                        } else {
                            ForEach(issues) { issue in
                                IssueSummaryRow(issue: issue, language: language)
                            }
                        }
                    }
                }
                .padding(20)
            }
            .navigationTitle(language("Goal Detail", "목표 상세"))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(language("Close", "닫기")) { dismiss() }
                }
            }
        }
        .frame(minWidth: 720, minHeight: 560)
    }
}

private struct RepositoryStackView: View {
    @EnvironmentObject private var store: DesktopStore
    let repository: RepositoryRecord
    let searchText: String
    private var l: AppLanguage { store.language }

    private var workspaces: [WorkspaceRecord] {
        let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        let base = store.dashboard.workspaces.filter { $0.repositoryId == repository.id }
        guard !query.isEmpty else {
            return base.sorted { $0.updatedAt > $1.updatedAt }
        }

        return base
            .filter {
                matches(query: query, values: [
                    $0.name,
                    $0.baseBranch,
                ])
            }
            .sorted { $0.updatedAt > $1.updatedAt }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button {
                Task { await store.selectRepository(repository) }
            } label: {
                HStack(alignment: .top, spacing: 10) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 10, style: .continuous)
                            .fill((store.selectedRepositoryID == repository.id ? ShellPalette.accent : ShellPalette.panelRaised).opacity(0.30))
                        Image(systemName: "shippingbox.fill")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(store.selectedRepositoryID == repository.id ? ShellPalette.accentWarm : ShellPalette.muted)
                    }
                    .frame(width: 32, height: 32)

                    VStack(alignment: .leading, spacing: 5) {
                        HStack {
                            Text(repository.name)
                                .font(.system(size: 13, weight: .semibold))
                                .foregroundStyle(ShellPalette.text)
                            Spacer(minLength: 8)
                            ShellTag(
                                text: localizedSourceKind(repository.sourceKind, language: l),
                                tint: repository.sourceKind.uppercased() == "LOCAL" ? ShellPalette.accent : ShellPalette.accentWarm
                            )
                        }

                        Text(repository.localPath)
                            .font(.system(size: 11, weight: .medium, design: .monospaced))
                            .foregroundStyle(ShellPalette.muted)
                            .lineLimit(2)

                        HStack(spacing: 8) {
                            dotLabel(systemImage: "arrow.triangle.branch", text: repository.defaultBranch)
                            if let remote = repository.remoteUrl, !remote.isEmpty {
                                dotLabel(systemImage: "link", text: remote)
                            }
                        }
                    }
                }
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(store.selectedRepositoryID == repository.id ? ShellPalette.panelRaised : ShellPalette.panelAlt)
                .overlay(
                    RoundedRectangle(cornerRadius: ShellMetrics.radiusSmall, style: .continuous)
                        .stroke(store.selectedRepositoryID == repository.id ? ShellPalette.lineStrong : ShellPalette.line, lineWidth: 1)
                )
                .clipShape(RoundedRectangle(cornerRadius: ShellMetrics.radiusSmall, style: .continuous))
            }
            .buttonStyle(.plain)

            if !workspaces.isEmpty {
                VStack(spacing: 7) {
                    ForEach(workspaces) { workspace in
                        Button {
                            Task { await store.selectWorkspace(workspace) }
                        } label: {
                            HStack(spacing: 10) {
                                Circle()
                                    .fill(store.selectedWorkspaceID == workspace.id ? ShellPalette.accent : ShellPalette.lineStrong)
                                    .frame(width: 7, height: 7)
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(workspace.name)
                                        .font(.system(size: 12, weight: .medium))
                                        .foregroundStyle(ShellPalette.text)
                                    Text(workspace.baseBranch)
                                        .font(.system(size: 11, weight: .medium, design: .monospaced))
                                        .foregroundStyle(ShellPalette.muted)
                                }
                                Spacer()
                                Text("\(store.dashboard.tasks.filter { $0.workspaceId == workspace.id }.count)")
                                    .font(.system(size: 10, weight: .semibold))
                                    .foregroundStyle(ShellPalette.muted)
                            }
                            .padding(.horizontal, 12)
                            .padding(.vertical, 10)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(store.selectedWorkspaceID == workspace.id ? ShellPalette.accentSoft.opacity(0.82) : Color.clear)
                            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.leading, 10)
            }
        }
    }

    private func dotLabel(systemImage: String, text: String) -> some View {
        HStack(spacing: 5) {
            Image(systemName: systemImage)
            Text(text)
                .lineLimit(1)
        }
        .font(.system(size: 10, weight: .medium))
        .foregroundStyle(ShellPalette.faint)
    }
}

private struct SidebarTaskRow: View {
    let task: TaskRecord
    let language: AppLanguage
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(alignment: .top, spacing: 10) {
                RoundedRectangle(cornerRadius: 7, style: .continuous)
                    .fill(statusTint(for: task.status))
                    .frame(width: 5)

                VStack(alignment: .leading, spacing: 5) {
                    HStack(alignment: .top) {
                        Text(task.title)
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(ShellPalette.text)
                            .multilineTextAlignment(.leading)
                        Spacer(minLength: 8)
                        Text(language.status(task.status))
                            .font(.system(size: 10, weight: .semibold))
                            .foregroundStyle(ShellPalette.muted)
                    }

                    Text(task.prompt)
                        .font(.system(size: 11, weight: .medium))
                        .foregroundStyle(ShellPalette.muted)
                        .lineLimit(2)

                    Text(task.agents.joined(separator: " · "))
                        .font(.system(size: 10, weight: .medium))
                        .foregroundStyle(ShellPalette.faint)
                }
            }
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(isSelected ? ShellPalette.panelRaised : ShellPalette.panelAlt)
            .overlay(
                RoundedRectangle(cornerRadius: ShellMetrics.radiusSmall, style: .continuous)
                    .stroke(isSelected ? ShellPalette.lineStrong : ShellPalette.line, lineWidth: 1)
            )
            .clipShape(RoundedRectangle(cornerRadius: ShellMetrics.radiusSmall, style: .continuous))
        }
        .buttonStyle(.plain)
    }
}

private struct IssueSidebarRow: View {
    let issue: IssueRecord
    let language: AppLanguage
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(alignment: .top, spacing: 10) {
                RoundedRectangle(cornerRadius: 7, style: .continuous)
                    .fill(statusTint(for: issue.status))
                    .frame(width: 5)

                VStack(alignment: .leading, spacing: 5) {
                    Text(issue.title)
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(ShellPalette.text)
                        .multilineTextAlignment(.leading)
                        .lineLimit(2)
                        .frame(maxWidth: .infinity, alignment: .leading)

                    Text(issue.description)
                        .font(.system(size: 11, weight: .medium))
                        .foregroundStyle(ShellPalette.muted)
                        .lineLimit(1)

                    HStack(spacing: 8) {
                        ShellTag(text: issue.kind.uppercased(), tint: ShellPalette.accentSoft)
                        ShellTag(text: language.status(issue.status), tint: statusTint(for: issue.status))
                        Spacer()
                    }

                    if !issue.dependsOn.isEmpty {
                        HStack(spacing: 8) {
                            ShellTag(text: "\(issue.dependsOn.count) \(language("deps", "의존"))", tint: ShellPalette.warning)
                            Spacer()
                        }
                    }
                }
            }
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(isSelected ? ShellPalette.panelRaised : ShellPalette.panelAlt)
            .overlay(
                RoundedRectangle(cornerRadius: ShellMetrics.radiusSmall, style: .continuous)
                    .stroke(isSelected ? ShellPalette.lineStrong : ShellPalette.line, lineWidth: 1)
            )
            .clipShape(RoundedRectangle(cornerRadius: ShellMetrics.radiusSmall, style: .continuous))
        }
        .buttonStyle(.plain)
    }
}

private struct CenterPaneView: View {
    @EnvironmentObject private var store: DesktopStore
    let layoutMode: DesktopLayoutMode
    let searchText: String
    let companySurface: CompanySidebarSurface
    @State private var goalSurface: GoalSurface = .board
    @State private var detailDrawerOpen = false
    @State private var presentedGoal: GoalRecord?
    private var l: AppLanguage { store.language }

    private var workflowLeader: String {
        if !store.workflowLeadAgent.isEmpty {
            return store.workflowLeadAgent
        }
        return store.dashboard.settings.availableAgents.first ?? "—"
    }

    private var workerAgents: [String] {
        Array(store.agentSelection.subtracting([workflowLeader])).sorted()
    }

    private var filteredGoals: [GoalRecord] {
        let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        let base = store.goals.sorted { $0.updatedAt > $1.updatedAt }
        guard !query.isEmpty else { return base }

        return base.filter {
            matches(query: query, values: [
                $0.title,
                $0.description,
                $0.status,
                $0.successMetrics.joined(separator: " ")
            ])
        }
    }

    private var selectedGoalIssues: [IssueRecord] {
        guard let goal = store.selectedGoal else { return [] }
        return store.dashboard.issues
            .filter { $0.goalId == goal.id }
            .sorted { $0.updatedAt > $1.updatedAt }
    }

    private var selectedIssueTask: TaskRecord? {
        guard let issue = store.selectedIssue else { return nil }
        return store.dashboard.tasks
            .filter { $0.issueId == issue.id }
            .sorted { $0.updatedAt > $1.updatedAt }
            .first
    }

    private var selectedIssueRuns: [RunRecord] {
        store.runs
            .sorted { $0.updatedAt > $1.updatedAt }
    }

    private var selectedIssueLog: String {
        if let run = store.selectedRun, let output = run.output, !output.isEmpty {
            return output
        }
        if let run = selectedIssueRuns.first, let output = run.output, !output.isEmpty {
            return output
        }
        if let run = store.selectedRun, let error = run.error, !error.isEmpty {
            return error
        }
        if let run = selectedIssueRuns.first, let error = run.error, !error.isEmpty {
            return error
        }
        if let issueId = store.selectedIssueID,
           let liveSnippet = runningSessions.first(where: { $0.issueId == issueId })?.outputSnippet,
           !liveSnippet.isEmpty {
            return liveSnippet
        }
        if let issueId = store.selectedIssueID,
           let recentDetail = visibleActivity.first(where: { $0.issueId == issueId })?.detail,
           !recentDetail.isEmpty {
            return recentDetail
        }
        return l("No execution log has been captured for this issue yet.", "이 이슈에 대해 아직 캡처된 실행 로그가 없습니다.")
    }

    private var visibleActivity: [CompanyActivityItemRecord] {
        Array(store.activity.prefix(8))
    }

    private var visibleDecisions: [GoalOrchestrationDecisionRecord] {
        Array(
            store.dashboard.goalDecisions
                .filter { store.selectedCompanyID == nil || $0.companyId == store.selectedCompanyID }
                .sorted { $0.createdAt > $1.createdAt }
                .prefix(3)
        )
    }

    private var runningSessions: [RunningAgentSessionRecord] {
        store.dashboard.runningAgentSessions
            .filter { store.selectedCompanyID == nil || $0.companyId == store.selectedCompanyID }
            .sorted { $0.updatedAt > $1.updatedAt }
    }

    private var filteredIssues: [IssueRecord] {
        let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        let base = store.issues.sorted { $0.updatedAt > $1.updatedAt }
        guard !query.isEmpty else { return base }

        return base.filter {
            matches(query: query, values: [
                $0.title,
                $0.description,
                $0.status,
                $0.kind,
            ])
        }
    }

    private var filteredTasks: [TaskRecord] {
        let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        let base = store.tasks.sorted { $0.updatedAt > $1.updatedAt }
        guard !query.isEmpty else { return base }

        return base.filter {
            matches(query: query, values: [
                $0.title,
                $0.prompt,
                $0.status,
                $0.agents.joined(separator: " "),
            ])
        }
    }

    var body: some View {
        ScrollView {
            content
        }
        .scrollIndicators(.hidden)
        .shellCard()
        .sheet(item: $presentedGoal) { goal in
            GoalDetailSheet(goal: goal, issues: store.dashboard.issues.filter { $0.goalId == goal.id }, language: l)
        }
    }

    private var content: some View {
        VStack(alignment: .leading, spacing: 12) {
            sessionStrip

            if store.shellMode == .tui {
                terminalContextBar
                tuiConsole
                detailDrawer
            } else {
                companyPageContent
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
    }

    @ViewBuilder
    private var companyPageContent: some View {
        switch companySurface {
        case .company:
            companyOverviewPage
        case .goals:
            goalOverviewPage
        case .agents:
            organizationOverviewPage
        case .issues:
            issuePage
        }
    }

    private var sessionStrip: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                if let repository = store.selectedRepository {
                    SessionStripItem(
                        title: repository.name,
                        subtitle: repository.defaultBranch,
                        tint: ShellPalette.accent
                    ) {
                        Task { await store.selectRepository(repository) }
                    }
                }
                if let workspace = store.selectedWorkspace {
                    SessionStripItem(
                        title: workspace.name,
                        subtitle: workspace.baseBranch,
                        tint: ShellPalette.accentWarm
                    ) {
                        Task { await store.selectWorkspace(workspace) }
                    }
                }
                if let goal = store.selectedGoal {
                    SessionStripItem(
                        title: goal.title,
                        subtitle: l.status(goal.status),
                        tint: statusTint(for: goal.status)
                    ) {
                        Task { await store.selectGoal(goal) }
                    }
                }
                if let issue = store.selectedIssue {
                    SessionStripItem(
                        title: issue.title,
                        subtitle: l.status(issue.status),
                        tint: statusTint(for: issue.status)
                    ) {
                        Task { await store.selectIssue(issue) }
                    }
                }
                if let task = store.selectedTask {
                    SessionStripItem(
                        title: task.title,
                        subtitle: task.agents.joined(separator: " · "),
                        tint: statusTint(for: task.status)
                    ) {
                        Task { await store.selectTask(task) }
                    }
                }
                if let reviewItem = store.selectedReviewQueueItem {
                    SessionStripItem(
                        title: l("Review Queue", "리뷰 큐"),
                        subtitle: l.status(reviewItem.status),
                        tint: reviewTint(reviewItem.status)
                    ) {}
                }
            }
            .padding(.vertical, 2)
        }
    }

    private var terminalContextBar: some View {
        HStack(spacing: 10) {
            ShellTag(text: store.selectedGoal?.title ?? l("No goal selected", "선택된 목표 없음"), tint: ShellPalette.accent)
            if let issue = store.selectedIssue {
                ShellTag(text: l.status(issue.status), tint: statusTint(for: issue.status))
            }
            ShellTag(text: "\(l("Lead AI", "리더 AI")): \(workflowLeader)", tint: ShellPalette.accentWarm)
            if let session = store.tuiSession {
                ShellTag(text: session.baseBranch, tint: ShellPalette.success)
            }
            Spacer()
        }
    }

    private var companyOverviewPage: some View {
        VStack(alignment: .leading, spacing: 12) {
            companyPageHeader(
                title: l("Company Summary", "회사 요약"),
                subtitle: l("Use this page as the summary room for what the company is doing right now.", "회사가 지금 무엇을 하고 있는지 보는 요약방입니다.")
            )
            operationsBanner
            if store.companyStreamStatusMessage != nil || store.backendStatusMessage != nil {
                VStack(alignment: .leading, spacing: 8) {
                    if let stream = store.companyStreamStatusMessage, !stream.isEmpty {
                        StatusSummaryPill(text: stream, tint: ShellPalette.warning)
                    }
                    if let backend = store.backendStatusMessage, !backend.isEmpty {
                        StatusSummaryPill(text: backend, tint: ShellPalette.warning)
                    }
                }
            }

            if layoutMode == .wide {
                HStack(alignment: .top, spacing: 12) {
                    companyCurrentIssuesPanel
                    companyAgentWorkPanel
                }
            } else {
                VStack(alignment: .leading, spacing: 12) {
                    companyCurrentIssuesPanel
                    companyAgentWorkPanel
                }
            }

            if layoutMode == .wide {
                HStack(alignment: .top, spacing: 12) {
                    companyRunningAgentsPanel
                    companyDecisionPanel
                }
            } else {
                VStack(alignment: .leading, spacing: 12) {
                    companyRunningAgentsPanel
                    companyDecisionPanel
                }
            }

            companyLinearPanel
            companyActivityFeed
        }
    }

    private var goalOverviewPage: some View {
        VStack(alignment: .leading, spacing: 12) {
            companyPageHeader(
                title: l("Goals", "목표"),
                subtitle: l("View goals, edit the selected one, and open a detailed goal sheet from the nodes below.", "목표를 보고, 선택한 목표를 수정하고, 아래 노드로 상세 모달을 열 수 있습니다.")
            )

            if filteredGoals.isEmpty {
                EmptyStateView(
                    image: "flag.2.crossed",
                    title: l("No goals yet", "아직 목표가 없습니다"),
                    subtitle: l("Create one clear goal from the left panel to start the company workflow.", "왼쪽 패널에서 목표를 하나 만들면 회사 워크플로우가 시작됩니다.")
                )
                .frame(minHeight: 220)
                .shellInset()
            } else {
                VStack(alignment: .leading, spacing: 12) {
                    goalNodeGrid
                    selectedGoalSummaryPanel
                }
            }
        }
    }

    private var organizationOverviewPage: some View {
        VStack(alignment: .leading, spacing: 12) {
            companyPageHeader(
                title: l("Organization", "조직도"),
                subtitle: l("See the inferred org chart and what each agent is working on.", "파생된 조직도와 각 에이전트가 맡은 이슈를 함께 봅니다.")
            )

            if store.orgProfiles.isEmpty {
                EmptyStateView(
                    image: "person.3.sequence",
                    title: l("No org chart yet", "아직 조직도가 없습니다"),
                    subtitle: l("Add agents on the left and Cotor will derive the company structure.", "왼쪽에서 에이전트를 추가하면 Cotor가 회사 구조를 파생합니다.")
                )
                .frame(minHeight: 240)
                .shellInset()
            } else {
                VStack(alignment: .leading, spacing: 12) {
                    organizationChartPanel
                    organizationWorkPanel
                }
            }
        }
    }

    private var issuePage: some View {
        VStack(alignment: .leading, spacing: 12) {
            companyPageHeader(
                title: l("Issues", "이슈"),
                subtitle: l("Select an issue to see its status, linked task, and latest execution log.", "이슈를 선택하면 상태, 연결된 task, 최근 실행 로그를 볼 수 있습니다.")
            )
            issueComposerPanel
            surfaceSwitcher
            issueSurface
            selectedIssueDetailPanel
            detailDrawer
        }
    }

    private var issueComposerPanel: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text(l("Create Issue", "이슈 만들기"))
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(ShellPalette.text)
                Spacer()
                if let company = store.issueComposerCompany {
                    StatusSummaryPill(text: company.name, tint: ShellPalette.accent)
                }
            }

            HStack(spacing: 10) {
                Picker(l("Company", "회사"), selection: Binding(
                    get: { store.newIssueCompanyID ?? store.selectedCompanyID ?? store.companies.first?.id ?? "" },
                    set: {
                        store.newIssueCompanyID = $0
                        if !store.issueComposerGoals.contains(where: { $0.id == store.newIssueGoalID }) {
                            store.newIssueGoalID = store.issueComposerGoals.first?.id
                        }
                    }
                )) {
                    ForEach(store.companies) { company in
                        Text(company.name).tag(company.id)
                    }
                }
                .pickerStyle(.menu)
                .frame(width: 170)

                Picker(l("Goal", "목표"), selection: Binding(
                    get: { store.newIssueGoalID ?? store.issueComposerGoals.first?.id ?? "" },
                    set: { store.newIssueGoalID = $0 }
                )) {
                    ForEach(store.issueComposerGoals) { goal in
                        Text(goal.title).tag(goal.id)
                    }
                }
                .pickerStyle(.menu)
                .disabled(store.issueComposerGoals.isEmpty)
            }

            TextField(l("Issue title", "이슈 제목"), text: $store.newIssueTitle)
                .textFieldStyle(.roundedBorder)

            TextEditor(text: $store.newIssueDescription)
                .font(.system(size: 12, weight: .medium))
                .frame(minHeight: 88)
                .padding(10)
                .scrollContentBackground(.hidden)
                .background(ShellPalette.panelAlt)
                .overlay(
                    RoundedRectangle(cornerRadius: ShellMetrics.radiusSmall, style: .continuous)
                        .stroke(ShellPalette.line, lineWidth: 1)
                )
                .clipShape(RoundedRectangle(cornerRadius: ShellMetrics.radiusSmall, style: .continuous))

            Button {
                Task { await store.createIssue() }
            } label: {
                Label(l("Create Issue", "이슈 만들기"), systemImage: "plus.circle.fill")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .disabled(
                store.issueComposerCompany == nil ||
                store.issueComposerGoals.isEmpty ||
                store.newIssueTitle.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
                store.newIssueDescription.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            )
        }
        .padding(14)
        .shellInset()
    }

    private var goalNodeGrid: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 240), spacing: 12)], spacing: 12) {
            ForEach(filteredGoals) { goal in
                GoalNodeCard(goal: goal, language: l, isSelected: store.selectedGoalID == goal.id) {
                    Task { await store.selectGoal(goal) }
                    presentedGoal = goal
                }
            }
        }
    }

    private var selectedGoalSummaryPanel: some View {
        Group {
            if let goal = store.selectedGoal {
                VStack(alignment: .leading, spacing: 12) {
                    HStack(alignment: .top) {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(goal.title)
                                .font(.system(size: 18, weight: .semibold))
                                .foregroundStyle(ShellPalette.text)
                            Text(goal.description)
                                .font(.system(size: 12, weight: .medium))
                                .foregroundStyle(ShellPalette.muted)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        Spacer()
                        StatusSummaryPill(text: l.status(goal.status), tint: statusTint(for: goal.status))
                    }

                    if !goal.successMetrics.isEmpty {
                        VStack(alignment: .leading, spacing: 6) {
                            Text(l("Success Metrics", "성공 지표"))
                                .font(.system(size: 11, weight: .semibold))
                                .foregroundStyle(ShellPalette.text)
                            ForEach(goal.successMetrics, id: \.self) { metric in
                                StatusSummaryLine(
                                    text: metric,
                                    tint: ShellPalette.accent
                                )
                            }
                        }
                    }

                    VStack(alignment: .leading, spacing: 8) {
                        Text(l("Derived Issues", "파생 이슈"))
                            .font(.system(size: 11, weight: .semibold))
                            .foregroundStyle(ShellPalette.text)

                        if selectedGoalIssues.isEmpty {
                            Text(l("No derived issues yet.", "아직 파생된 이슈가 없습니다."))
                                .font(.system(size: 11, weight: .medium))
                                .foregroundStyle(ShellPalette.muted)
                        } else {
                            ForEach(selectedGoalIssues.prefix(5)) { issue in
                                IssueSummaryRow(issue: issue, language: l)
                            }
                        }
                    }

                    HStack(spacing: 8) {
                        Button {
                            store.beginEditingGoal(goal)
                        } label: {
                            Label(l("Edit Goal", "목표 수정"), systemImage: "pencil")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(ShellTopBarButtonStyle(prominent: false))

                        Button {
                            presentedGoal = goal
                        } label: {
                            Label(l("Open Detail", "상세 보기"), systemImage: "rectangle.expand.vertical")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(ShellTopBarButtonStyle(prominent: false))

                        Button(role: .destructive) {
                            Task { await store.deleteSelectedGoal() }
                        } label: {
                            Label(l("Delete Goal", "목표 삭제"), systemImage: "trash")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(ShellTopBarButtonStyle(prominent: false))
                    }
                }
                .padding(16)
                .shellInset()
            }
        }
    }

    private var companyCurrentIssuesPanel: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(l("Recent Issues", "최근 이슈"))
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(ShellPalette.text)
                Spacer()
                ShellTag(text: "\(filteredIssues.count)", tint: ShellPalette.accent)
            }

            if filteredIssues.isEmpty {
                EmptyStateView(
                    image: "square.stack.3d.down.right",
                    title: l("No issues yet", "아직 이슈가 없습니다"),
                    subtitle: l("Goals will decompose into issues and show up here.", "목표가 이슈로 분해되면 여기에 표시됩니다.")
                )
                .frame(height: 180)
            } else {
                VStack(spacing: 8) {
                    ForEach(filteredIssues.prefix(5)) { issue in
                        IssueSummaryRow(issue: issue, language: l, assignee: store.orgProfiles.first(where: { $0.id == issue.assigneeProfileId }))
                    }
                }
            }
        }
        .padding(14)
        .shellInset()
    }

    private var companyAgentWorkPanel: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(l("Agent Work", "에이전트 작업"))
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(ShellPalette.text)
                Spacer()
            }

            if store.companyAgentDefinitions.isEmpty {
                EmptyStateView(
                    image: "person.2",
                    title: l("No agents yet", "아직 에이전트가 없습니다"),
                    subtitle: l("Add an agent to see current assignments.", "에이전트를 추가하면 현재 담당 이슈가 보입니다.")
                )
                .frame(height: 180)
            } else {
                VStack(spacing: 8) {
                    ForEach(store.companyAgentDefinitions.prefix(5)) { agent in
                        AgentWorkSummaryRow(
                            agent: agent,
                            profile: store.orgProfiles.first(where: {
                                $0.companyId == agent.companyId && $0.executionAgentName == agent.agentCli && $0.roleName == agent.title
                            }),
                            issues: filteredIssues,
                            language: l
                        )
                    }
                }
            }
        }
        .padding(14)
        .shellInset()
    }

    private var companyRunningAgentsPanel: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(l("Running Agents", "실행 중인 에이전트"))
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(ShellPalette.text)
                Spacer()
                ShellTag(text: "\(runningSessions.count)", tint: ShellPalette.accentWarm)
            }

            if runningSessions.isEmpty {
                EmptyStateView(
                    image: "play.circle",
                    title: l("No live runs", "실행 중인 작업이 없습니다"),
                    subtitle: l("When the CEO loop dispatches work, live agent sessions appear here.", "CEO 루프가 작업을 배정하면 여기에서 실시간 세션이 보입니다.")
                )
                .frame(height: 180)
            } else {
                VStack(spacing: 8) {
                    ForEach(runningSessions.prefix(5)) { session in
                        VStack(alignment: .leading, spacing: 6) {
                            HStack {
                                Text(session.roleName ?? session.agentName)
                                    .font(.system(size: 12, weight: .semibold))
                                    .foregroundStyle(ShellPalette.text)
                                Spacer()
                                StatusSummaryPill(text: l.status(session.status), tint: statusTint(for: session.status))
                            }
                            Text(session.outputSnippet ?? session.branchName)
                                .font(.system(size: 11, weight: .medium))
                                .foregroundStyle(ShellPalette.muted)
                                .lineLimit(3)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(12)
                        .background(ShellPalette.panelAlt)
                        .clipShape(RoundedRectangle(cornerRadius: ShellMetrics.radiusSmall, style: .continuous))
                    }
                }
            }
        }
        .padding(14)
        .shellInset()
    }

    private var companyDecisionPanel: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(l("CEO Decisions", "CEO 결정"))
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(ShellPalette.text)
                Spacer()
            }

            if visibleDecisions.isEmpty {
                EmptyStateView(
                    image: "point.topleft.down.curvedto.point.bottomright.up",
                    title: l("No decisions yet", "아직 결정이 없습니다"),
                    subtitle: l("Goal decomposition and follow-up decisions will appear here.", "목표 분해와 후속 조치 결정이 여기에 표시됩니다.")
                )
                .frame(height: 180)
            } else {
                VStack(spacing: 8) {
                    ForEach(visibleDecisions) { decision in
                        VStack(alignment: .leading, spacing: 6) {
                            Text(decision.title)
                                .font(.system(size: 12, weight: .semibold))
                                .foregroundStyle(ShellPalette.text)
                            Text(decision.summary)
                                .font(.system(size: 11, weight: .medium))
                                .foregroundStyle(ShellPalette.muted)
                                .lineLimit(4)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(12)
                        .background(ShellPalette.panelAlt)
                        .clipShape(RoundedRectangle(cornerRadius: ShellMetrics.radiusSmall, style: .continuous))
                    }
                }
            }
        }
        .padding(14)
        .shellInset()
    }

    private var companyLinearPanel: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(l("Linear Mirror", "Linear 미러"))
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(ShellPalette.text)
                Spacer()
                StatusSummaryPill(
                    text: (store.selectedCompany?.linearSyncEnabled ?? false) ? l("Enabled", "사용 중") : l("Optional", "선택 사항"),
                    tint: (store.selectedCompany?.linearSyncEnabled ?? false) ? ShellPalette.success : ShellPalette.warning
                )
            }

            VStack(alignment: .leading, spacing: 8) {
                valueLine(label: l("Endpoint", "엔드포인트"), value: store.companyLinearEndpoint.isEmpty ? "https://api.linear.app/graphql" : store.companyLinearEndpoint)
                valueLine(label: l("Team", "팀"), value: store.companyLinearTeamID.isEmpty ? l("Not set", "미설정") : store.companyLinearTeamID)
                valueLine(label: l("Project", "프로젝트"), value: store.companyLinearProjectID.isEmpty ? l("Not set", "미설정") : store.companyLinearProjectID)

                if let message = store.companyLinearStatusMessage, !message.isEmpty {
                    Text(message)
                        .font(.system(size: 11, weight: .medium))
                        .foregroundStyle(ShellPalette.muted)
                        .fixedSize(horizontal: false, vertical: true)
                } else {
                    Text(l("If you enable it, Linear sync events and failures will appear here.", "켜 두면 Linear 동기화 이벤트와 실패가 여기에 표시됩니다."))
                        .font(.system(size: 11, weight: .medium))
                        .foregroundStyle(ShellPalette.muted)
                }
            }
        }
        .padding(14)
        .shellInset()
    }

    private var organizationChartPanel: some View {
        let leader = store.orgProfiles.first(where: { $0.mergeAuthority }) ?? store.orgProfiles.first
        let others = store.orgProfiles.filter { $0.id != leader?.id }
        let qaProfiles = others.filter { profile in
            let capabilities = profile.capabilities.joined(separator: " ").lowercased()
            return capabilities.contains("qa") || capabilities.contains("review") || capabilities.contains("test") || capabilities.contains("verification")
        }
        let executionProfiles = others.filter { profile in !qaProfiles.contains(profile) }

        return VStack(alignment: .leading, spacing: 14) {
            Text(l("Org Chart", "조직도"))
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(ShellPalette.text)

            if let leader {
                VStack(spacing: 14) {
                    HStack {
                        Spacer()
                        OrgChartNode(profile: leader, language: l, isLeader: true)
                        Spacer()
                    }

                    if !qaProfiles.isEmpty {
                        HStack(alignment: .top, spacing: 12) {
                            ForEach(qaProfiles) { profile in
                                OrgChartNode(profile: profile, language: l)
                            }
                        }
                    }

                    if !executionProfiles.isEmpty {
                        LazyVGrid(columns: [GridItem(.adaptive(minimum: 180), spacing: 12)], spacing: 12) {
                            ForEach(executionProfiles) { profile in
                                OrgChartNode(profile: profile, language: l)
                            }
                        }
                    }
                }
            }
        }
        .padding(16)
        .shellInset()
    }

    private var organizationWorkPanel: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(l("Assignments", "할당된 작업"))
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(ShellPalette.text)

            VStack(spacing: 8) {
                ForEach(store.companyAgentDefinitions) { agent in
                    AgentWorkSummaryRow(
                        agent: agent,
                        profile: store.orgProfiles.first(where: {
                            $0.companyId == agent.companyId && $0.executionAgentName == agent.agentCli && $0.roleName == agent.title
                        }),
                        issues: filteredIssues,
                        language: l
                    )
                }
            }
        }
        .padding(16)
        .shellInset()
    }

    private var selectedIssueDetailPanel: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text(l("Selected Issue", "선택된 이슈"))
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(ShellPalette.text)
                Spacer()
                if let issue = store.selectedIssue {
                    StatusSummaryPill(text: l.status(issue.status), tint: statusTint(for: issue.status))
                }
            }

            if let issue = store.selectedIssue {
                VStack(alignment: .leading, spacing: 10) {
                    Text(issue.title)
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(ShellPalette.text)

                    Text(issue.description)
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(ShellPalette.muted)
                        .fixedSize(horizontal: false, vertical: true)

                    HStack(spacing: 8) {
                        StatusSummaryPill(text: issue.kind.uppercased(), tint: ShellPalette.accentSoft)
                        if let assignee = store.selectedIssueAssignee {
                            StatusSummaryPill(text: assignee.roleName, tint: ShellPalette.accent)
                        }
                        if let linearIdentifier = issue.linearIssueIdentifier, !linearIdentifier.isEmpty {
                            StatusSummaryPill(text: linearIdentifier, tint: ShellPalette.accentWarm)
                        }
                        if let task = selectedIssueTask {
                            StatusSummaryPill(text: l.status(task.status), tint: statusTint(for: task.status))
                        }
                        if let reviewItem = store.selectedReviewQueueItem {
                            StatusSummaryPill(text: l.status(reviewItem.status), tint: reviewTint(reviewItem.status))
                        }
                    }

                    HStack(spacing: 8) {
                        Button(role: .destructive) {
                            Task { await store.deleteSelectedIssue() }
                        } label: {
                            Label(l("Delete Issue", "이슈 삭제"), systemImage: "trash")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(ShellTopBarButtonStyle(prominent: false))
                    }

                    if issue.linearIssueId != nil {
                        VStack(alignment: .leading, spacing: 8) {
                            Text(l("Linear", "Linear"))
                                .font(.system(size: 11, weight: .semibold))
                                .foregroundStyle(ShellPalette.text)
                            if let linearIdentifier = issue.linearIssueIdentifier, !linearIdentifier.isEmpty {
                                valueLine(label: l("Issue", "이슈"), value: linearIdentifier)
                            }
                            if let linearIssueUrl = issue.linearIssueUrl, let url = URL(string: linearIssueUrl) {
                                Link(destination: url) {
                                    Label(l("Open in Linear", "Linear에서 열기"), systemImage: "arrow.up.right.square")
                                        .font(.system(size: 11, weight: .semibold))
                                }
                                .foregroundStyle(ShellPalette.accent)
                            }
                            if let lastSyncAt = issue.lastLinearSyncAt {
                                valueLine(label: l("Last sync", "마지막 동기화"), value: relativeTimestamp(lastSyncAt))
                            }
                        }
                    }

                    if !issue.acceptanceCriteria.isEmpty {
                        VStack(alignment: .leading, spacing: 6) {
                            Text(l("Acceptance", "수용 기준"))
                                .font(.system(size: 11, weight: .semibold))
                                .foregroundStyle(ShellPalette.text)
                            ForEach(issue.acceptanceCriteria, id: \.self) { criterion in
                                StatusSummaryLine(text: criterion, tint: ShellPalette.success)
                            }
                        }
                    }

                    VStack(alignment: .leading, spacing: 8) {
                        Text(l("Execution Log", "실행 로그"))
                            .font(.system(size: 11, weight: .semibold))
                            .foregroundStyle(ShellPalette.text)

                        ScrollView {
                            Text(selectedIssueLog)
                                .font(.system(size: 11, weight: .medium, design: .monospaced))
                                .foregroundStyle(ShellPalette.text)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .textSelection(.enabled)
                                .padding(12)
                        }
                        .frame(minHeight: 170)
                        .background(ShellPalette.panelAlt)
                        .overlay(
                            RoundedRectangle(cornerRadius: ShellMetrics.radiusSmall, style: .continuous)
                                .stroke(ShellPalette.line, lineWidth: 1)
                        )
                        .clipShape(RoundedRectangle(cornerRadius: ShellMetrics.radiusSmall, style: .continuous))
                    }
                }
            } else {
                EmptyStateView(
                    image: "text.alignleft",
                    title: l("Select an issue", "이슈를 선택하세요"),
                    subtitle: l("Choose an issue above to see its linked task and execution output.", "위에서 이슈를 선택하면 연결된 task와 실행 출력을 볼 수 있습니다.")
                )
                .frame(height: 190)
            }
        }
        .padding(14)
        .shellInset()
    }

    private func companyPageHeader(title: String, subtitle: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(ShellPalette.text)
            Text(subtitle)
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(ShellPalette.muted)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(16)
        .shellInset()
    }

    private var operationsBanner: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top, spacing: 16) {
                VStack(alignment: .leading, spacing: 6) {
                    Text(store.selectedGoal?.title ?? l("Choose a goal", "목표를 선택하세요"))
                        .font(.system(size: 20, weight: .semibold))
                        .foregroundStyle(ShellPalette.text)
                        .lineLimit(2)

                    Text(store.selectedGoal?.description ?? store.selectedRepository?.localPath ?? l.text(.openOrCloneRepository))
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(ShellPalette.muted)
                        .lineLimit(2)
                        .textSelection(.enabled)
                }

                Spacer()

                VStack(alignment: .trailing, spacing: 8) {
                    ShellStatusPill(
                        text: store.selectedIssue.map { l.status($0.status) } ?? l("IDLE", "대기"),
                        tint: statusTint(for: store.selectedIssue?.status ?? "IDLE")
                    )

                    if store.selectedWorkspace != nil, !store.availableBranches.isEmpty {
                        Picker(l("Current workspace base branch", "현재 워크스페이스 기준 브랜치"), selection: $store.pendingWorkspaceBaseBranch) {
                            ForEach(store.availableBranches, id: \.self) { branch in
                                Text(branch).tag(branch)
                            }
                        }
                        .pickerStyle(.menu)

                        Button {
                            Task { await store.updateSelectedWorkspaceBaseBranch() }
                        } label: {
                            Label(l("Apply Branch", "브랜치 적용"), systemImage: "arrow.triangle.branch")
                        }
                        .buttonStyle(ShellTopBarButtonStyle(prominent: false))
                    }
                }
            }

            HStack(spacing: 8) {
                ShellTag(text: "\(l("Goals", "목표")) \(store.dashboard.opsMetrics.openGoals)", tint: ShellPalette.accentWarm)
                ShellTag(text: "\(l("Active Issues", "활성 이슈")) \(store.dashboard.opsMetrics.activeIssues)", tint: ShellPalette.accent)
                ShellTag(text: "\(l("Blocked", "차단")) \(store.dashboard.opsMetrics.blockedIssues)", tint: ShellPalette.warning)
                ShellTag(text: "\(l("Merge", "병합")) \(store.dashboard.opsMetrics.readyToMergeCount)", tint: ShellPalette.success)
                Spacer()
            }

            HStack(spacing: 8) {
                ShellTag(text: "\(l("Lead AI", "리더 AI")): \(workflowLeader)", tint: ShellPalette.accent)
                ShellTag(text: "\(l("Workers", "워커")): \(workerAgents.count)", tint: ShellPalette.success)
                Spacer()
            }
        }
        .padding(16)
        .background(
            RoundedRectangle(cornerRadius: ShellMetrics.radiusMedium, style: .continuous)
                .fill(ShellPalette.panelAlt)
        )
        .overlay(
            RoundedRectangle(cornerRadius: ShellMetrics.radiusMedium, style: .continuous)
                .stroke(ShellPalette.line, lineWidth: 1)
        )
    }

    private var companyActivityFeed: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(l("Company Activity", "회사 활동"))
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(ShellPalette.text)
                Spacer()
                if let runtime = store.selectedRuntime {
                    ShellTag(text: l.status(runtime.status), tint: companyRuntimeTint(runtime.status))
                }
            }

            if store.activity.isEmpty {
                EmptyStateView(
                    image: "waveform.path.ecg",
                    title: l("No activity yet", "아직 활동이 없습니다"),
                    subtitle: l("Goal planning, issue delegation, run execution, and review events will appear here.", "목표 계획, 이슈 위임, 실행, 리뷰 이벤트가 여기에 표시됩니다.")
                )
                .frame(height: 150)
            } else {
                CompanyActivityFeedList(items: visibleActivity)
            }
        }
        .padding(14)
        .shellInset()
    }

    private var surfaceSwitcher: some View {
        Picker(l("Surface", "화면"), selection: $goalSurface) {
            Text(l("Board", "보드")).tag(GoalSurface.board)
            Text(l("Canvas", "캔버스")).tag(GoalSurface.canvas)
        }
        .pickerStyle(.segmented)
    }

    @ViewBuilder
    private var issueSurface: some View {
        switch goalSurface {
        case .board:
            issueBoard
        case .canvas:
            issueCanvas
        }
    }

    private var issueBoard: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(alignment: .top, spacing: 12) {
                ForEach(["PLANNED", "DELEGATED", "IN_PROGRESS", "IN_REVIEW", "BLOCKED", "DONE"], id: \.self) { status in
                    IssueBoardLaneView(
                        title: l.status(status),
                        tint: statusTint(for: status),
                        issues: filteredIssues.filter { $0.status == status },
                        assigneeProvider: { issue in
                            store.orgProfiles.first(where: { $0.id == issue.assigneeProfileId })
                        },
                        reviewProvider: { issue in
                            store.dashboard.reviewQueue.first(where: { $0.issueId == issue.id })
                        },
                        selectedIssueID: store.selectedIssueID,
                        language: l,
                        width: layoutMode == .compact ? 260 : 280
                    ) { issue in
                        Task { await store.selectIssue(issue) }
                    }
                }
            }
        }
        .frame(minHeight: 260)
    }

    private var issueCanvas: some View {
        ScrollView {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 240), spacing: 12)], spacing: 12) {
                ForEach(filteredIssues) { issue in
                    VStack(alignment: .leading, spacing: 10) {
                        HStack(alignment: .top) {
                            Text(issue.title)
                                .font(.system(size: 13, weight: .semibold))
                                .foregroundStyle(ShellPalette.text)
                            Spacer()
                            Circle()
                                .fill(statusTint(for: issue.status))
                                .frame(width: 10, height: 10)
                        }

                        Text(issue.description)
                            .font(.system(size: 11, weight: .medium))
                            .foregroundStyle(ShellPalette.muted)
                            .lineLimit(4)

                        if !issue.dependsOn.isEmpty {
                            Text("\(l("Depends on", "의존")): \(issue.dependsOn.count)")
                                .font(.system(size: 10, weight: .medium))
                                .foregroundStyle(ShellPalette.faint)
                        }

                        HStack(spacing: 8) {
                            ShellTag(text: issue.kind, tint: ShellPalette.accent)
                            ShellTag(text: l.status(issue.status), tint: statusTint(for: issue.status))
                        }
                    }
                    .padding(14)
                    .background(store.selectedIssueID == issue.id ? ShellPalette.panelRaised : ShellPalette.panelAlt)
                    .overlay(
                        RoundedRectangle(cornerRadius: ShellMetrics.radiusMedium, style: .continuous)
                            .stroke(store.selectedIssueID == issue.id ? ShellPalette.lineStrong : ShellPalette.line, lineWidth: 1)
                    )
                    .clipShape(RoundedRectangle(cornerRadius: ShellMetrics.radiusMedium, style: .continuous))
                    .onTapGesture {
                        Task { await store.selectIssue(issue) }
                    }
                }
            }
        }
        .frame(minHeight: 260)
    }

    private var composerSurface: some View {
        VStack(alignment: .leading, spacing: 12) {
            ShellSectionHeader(
                eyebrow: l.text(.compose),
                title: l.text(.newTask),
                subtitle: l.text(.newTaskSubtitle)
            )

            if layoutMode == .wide {
                HStack(alignment: .top, spacing: 12) {
                    composerInputs
                    composerActions
                        .frame(width: 248)
                }
            } else {
                VStack(alignment: .leading, spacing: 12) {
                    composerInputs
                    composerActions
                }
            }
        }
        .padding(14)
        .shellInset()
    }

    private var composerInputs: some View {
        VStack(alignment: .leading, spacing: 10) {
            TextField(l.text(.taskTitle), text: $store.newTaskTitle)
                .textFieldStyle(.roundedBorder)

            TextEditor(text: $store.newTaskPrompt)
                .font(.system(size: 13, design: .monospaced))
                .frame(minHeight: 112)
                .padding(10)
                .scrollContentBackground(.hidden)
                .background(ShellPalette.panelAlt)
                .overlay(
                    RoundedRectangle(cornerRadius: ShellMetrics.radiusSmall, style: .continuous)
                        .stroke(ShellPalette.line, lineWidth: 1)
                )
                .clipShape(RoundedRectangle(cornerRadius: ShellMetrics.radiusSmall, style: .continuous))
        }
    }

    private var composerActions: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(l("Lead And Worker Agents", "리더와 워커 에이전트"))
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(ShellPalette.muted)

            Picker(l("Lead AI", "리더 AI"), selection: Binding(
                get: { workflowLeader },
                set: { store.setWorkflowLeadAgent($0) }
            )) {
                ForEach(store.dashboard.settings.availableAgents, id: \.self) { agent in
                    Text(agent).tag(agent)
                }
            }
            .pickerStyle(.menu)

            FlowLayout(items: store.dashboard.settings.availableAgents, selected: store.agentSelection) { agent in
                store.toggleWorkflowAgent(agent)
            }

            VStack(alignment: .leading, spacing: 8) {
                Text(l("Orchestration Model", "오케스트레이션 모델"))
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(ShellPalette.text)

                Text(l("The lead AI receives the workflow from the TUI and delegates work to the selected worker agents. Each worker still runs in its own branch and worktree.", "리더 AI가 TUI로부터 워크플로우를 받고, 선택된 워커 에이전트에게 작업을 분배합니다. 각 워커는 여전히 자신의 브랜치와 워크트리에서 실행됩니다."))
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(ShellPalette.muted)
                    .fixedSize(horizontal: false, vertical: true)

                HStack(spacing: 8) {
                    ShellTag(text: "\(l("Lead", "리드")): \(workflowLeader)", tint: ShellPalette.accent)
                    if workerAgents.isEmpty {
                        ShellTag(text: l("No workers selected", "선택된 워커 없음"), tint: ShellPalette.warning)
                    } else {
                        ShellTag(text: workerAgents.joined(separator: " · "), tint: ShellPalette.accentWarm)
                    }
                }
            }

            Text(l("Use Test Run only as a local preview. The product's primary execution path is still TUI -> leader AI -> worker orchestration.", "테스트 실행은 로컬 미리보기 용도입니다. 제품의 주 실행 경로는 여전히 TUI -> 리더 AI -> 워커 오케스트레이션입니다."))
                .font(.system(size: 11, weight: .medium))
                .foregroundStyle(ShellPalette.muted)
                .fixedSize(horizontal: false, vertical: true)

            Button {
                Task { await store.createTask() }
            } label: {
                Label(l.text(.createTask), systemImage: "wand.and.stars")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .disabled(store.selectedWorkspace == nil)
        }
    }

    private var taskRail: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                ShellSectionHeader(
                    eyebrow: l.text(.queue),
                    title: l.text(.tasks),
                    subtitle: l.text(.tasksSubtitle)
                )
                Spacer()
                ShellTag(text: "\(filteredTasks.count)", tint: ShellPalette.accentWarm)
            }

            if filteredTasks.isEmpty {
                EmptyStateView(
                    image: "rectangle.stack.badge.plus",
                    title: l.text(.noTasks),
                    subtitle: l.text(.noTasksSubtitle)
                )
                .frame(height: 150)
            } else {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 10) {
                        ForEach(filteredTasks) { task in
                            TaskRailCard(task: task, language: l, isSelected: store.selectedTaskID == task.id) {
                                Task { await store.selectTask(task) }
                            }
                            .frame(width: layoutMode == .compact ? 240 : 250)
                        }
                    }
                }
            }
        }
    }

    private var tuiConsole: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text(l("Interactive TUI", "대화형 TUI"))
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(ShellPalette.text)
                    Text(l("This center surface is the real Cotor terminal loop. Define workflows here, then let the lead AI orchestrate worker agents from the TUI.", "가운데 surface는 실제 Cotor 터미널 루프입니다. 여기서 워크플로우를 정의하고, 리더 AI가 TUI에서 워커 에이전트를 오케스트레이션하게 만드세요."))
                        .font(.system(size: 11, weight: .medium))
                        .foregroundStyle(ShellPalette.muted)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer()

                HStack(spacing: 8) {
                    if let session = store.tuiSession {
                        ShellStatusPill(text: l.status(session.status), tint: tuiStatusTint(session.status))
                    }

                    Button {
                        Task { await store.restartTuiSession() }
                    } label: {
                        Label(l("Restart", "재시작"), systemImage: "arrow.clockwise")
                    }
                    .buttonStyle(ShellTopBarButtonStyle(prominent: false))

                    Button {
                        store.openSelectedLocation()
                    } label: {
                        Label(l.text(.openFolder), systemImage: "folder")
                    }
                    .buttonStyle(ShellTopBarButtonStyle(prominent: false))
                }
            }
            .padding(14)

            paneDivider

            Group {
                if store.isOffline {
                    EmptyStateView(
                        image: "wifi.slash",
                        title: l("Local app-server is unavailable", "로컬 app-server에 연결할 수 없습니다"),
                        subtitle: l("Start `cotor app-server` or run `cotor update`, then reopen the TUI workspace.", "`cotor app-server`를 실행하거나 `cotor update` 후 TUI 워크스페이스를 다시 여세요.")
                    )
                } else if let session = store.tuiSession, session.workspaceId == store.selectedWorkspaceID {
                    VStack(alignment: .leading, spacing: 0) {
                        HStack(spacing: 8) {
                            ShellTag(text: session.baseBranch, tint: ShellPalette.accentWarm)
                            if let pid = session.processId {
                                ShellTag(text: "pid \(pid)", tint: ShellPalette.success)
                            }
                            ShellTag(text: session.repositoryPath, tint: ShellPalette.accent)
                            Spacer()
                        }
                        .padding(.horizontal, 14)
                        .padding(.vertical, 12)

                        paneDivider

                        TerminalWebView(
                            sessionId: session.id,
                            baseURL: store.api.baseURL,
                            token: store.api.token
                        )
                            .frame(maxWidth: .infinity, minHeight: 360, maxHeight: .infinity)
                    }
                } else if store.selectedWorkspace != nil {
                    EmptyStateView(
                        image: "terminal",
                        title: l("Open the workspace TUI", "워크스페이스 TUI 열기"),
                        subtitle: l("The center panel keeps one live interactive Cotor session per workspace.", "가운데 패널은 워크스페이스마다 하나의 실시간 Cotor 세션을 유지합니다.")
                    )
                    .padding(14)
                } else {
                    EmptyStateView(
                        image: "terminal",
                        title: l.text(.chooseWorkspace),
                        subtitle: l("Select a workspace to launch the real TUI in the center pane.", "가운데 패널에서 실제 TUI를 띄우려면 워크스페이스를 선택하세요.")
                    )
                    .padding(14)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        }
        .frame(minHeight: layoutMode == .compact ? 420 : 520, maxHeight: .infinity)
        .shellInset()
    }

    private var detailDrawer: some View {
        VStack(alignment: .leading, spacing: 10) {
            Button {
                withAnimation(ShellMotion.spring) {
                    detailDrawerOpen.toggle()
                }
            } label: {
                HStack(spacing: 10) {
                    Image(systemName: detailDrawerOpen ? "chevron.down" : "chevron.right")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(ShellPalette.muted)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(l("Detail Drawer", "상세 드로어"))
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(ShellPalette.text)
                        Text(l("Diffs, files, ports, browser, review metadata", "변경점, 파일, 포트, 브라우저, 리뷰 메타데이터"))
                            .font(.system(size: 11, weight: .medium))
                            .foregroundStyle(ShellPalette.muted)
                    }
                    Spacer()
                    if let item = store.selectedReviewQueueItem {
                        ShellTag(text: l.status(item.status), tint: reviewTint(item.status))
                    }
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
                .background(ShellPalette.panelAlt)
                .overlay(
                    RoundedRectangle(cornerRadius: ShellMetrics.radiusSmall, style: .continuous)
                        .stroke(ShellPalette.line, lineWidth: 1)
                )
                .clipShape(RoundedRectangle(cornerRadius: ShellMetrics.radiusSmall, style: .continuous))
            }
            .buttonStyle(.plain)

            if detailDrawerOpen {
                InspectorPaneView(embedded: true)
                    .frame(minHeight: 320, maxHeight: 520)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
    }

    private func reviewTint(_ status: String) -> Color {
        switch status.uppercased() {
        case "READY_TO_MERGE", "MERGED":
            return ShellPalette.success
        case "FAILED_CHECKS", "CHANGES_REQUESTED":
            return ShellPalette.danger
        default:
            return ShellPalette.warning
        }
    }

    private func tuiStatusTint(_ status: String) -> Color {
        switch status.uppercased() {
        case "RUNNING":
            return ShellPalette.success
        case "STARTING":
            return ShellPalette.accent
        case "FAILED":
            return ShellPalette.danger
        default:
            return ShellPalette.warning
        }
    }

    private var paneDivider: some View {
        Rectangle()
            .fill(ShellPalette.line)
            .frame(height: 1)
    }
}

private struct CompanyCard: View {
    let company: CompanyRecord
    let runtime: CompanyRuntimeSnapshotRecord?
    let language: AppLanguage
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(alignment: .top, spacing: 10) {
                RoundedRectangle(cornerRadius: 8, style: .continuous)
                    .fill(isSelected ? ShellPalette.accent : ShellPalette.panelRaised)
                    .frame(width: 8)

                VStack(alignment: .leading, spacing: 5) {
                    HStack {
                        Text(company.name)
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(ShellPalette.text)
                        Spacer()
                        ShellTag(
                            text: company.autonomyEnabled ? language("AUTO", "자동") : language("MANUAL", "수동"),
                            tint: company.autonomyEnabled ? ShellPalette.success : ShellPalette.warning
                        )
                    }

                    Text(company.rootPath)
                        .font(.system(size: 11, weight: .medium, design: .monospaced))
                        .foregroundStyle(ShellPalette.muted)
                        .lineLimit(2)

                    HStack(spacing: 8) {
                        ShellTag(text: company.defaultBaseBranch, tint: ShellPalette.accentWarm)
                        if let runtime {
                            ShellTag(text: language.status(runtime.status), tint: companyRuntimeTint(runtime.status))
                        }
                    }
                }
            }
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(isSelected ? ShellPalette.panelRaised : ShellPalette.panelAlt)
            .overlay(
                RoundedRectangle(cornerRadius: ShellMetrics.radiusSmall, style: .continuous)
                    .stroke(isSelected ? ShellPalette.lineStrong : ShellPalette.line, lineWidth: 1)
            )
            .clipShape(RoundedRectangle(cornerRadius: ShellMetrics.radiusSmall, style: .continuous))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

private struct CompanyActivityFeedList: View {
    let items: [CompanyActivityItemRecord]

    var body: some View {
        VStack(spacing: 8) {
            if items.indices.contains(0) { activityRow(items[0]) }
            if items.indices.contains(1) { activityRow(items[1]) }
            if items.indices.contains(2) { activityRow(items[2]) }
            if items.indices.contains(3) { activityRow(items[3]) }
            if items.indices.contains(4) { activityRow(items[4]) }
            if items.indices.contains(5) { activityRow(items[5]) }
            if items.indices.contains(6) { activityRow(items[6]) }
            if items.indices.contains(7) { activityRow(items[7]) }
        }
    }

    private func activityRow(_ item: CompanyActivityItemRecord) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Circle()
                .fill(companyActivityTint(item.severity))
                .frame(width: 8, height: 8)
                .padding(.top, 4)
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text(item.title)
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(ShellPalette.text)
                        .lineLimit(2)
                    Spacer()
                    Text(relativeTimestamp(item.createdAt))
                        .font(.system(size: 10, weight: .medium))
                        .foregroundStyle(ShellPalette.faint)
                }
                Text(item.detail ?? item.source)
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(ShellPalette.muted)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(12)
        .background(ShellPalette.panelAlt)
        .overlay(
            RoundedRectangle(cornerRadius: ShellMetrics.radiusSmall, style: .continuous)
                .stroke(ShellPalette.line, lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: ShellMetrics.radiusSmall, style: .continuous))
    }
}

private struct SessionStripItem: View {
    let title: String
    let subtitle: String
    let tint: Color
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(ShellPalette.text)
                    .lineLimit(1)
                Text(subtitle)
                    .font(.system(size: 10, weight: .medium))
                    .foregroundStyle(ShellPalette.muted)
                    .lineLimit(1)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .frame(minWidth: 138, alignment: .leading)
            .background(ShellPalette.panelAlt)
            .overlay(
                RoundedRectangle(cornerRadius: ShellMetrics.radiusSmall, style: .continuous)
                    .stroke(tint.opacity(0.45), lineWidth: 1)
            )
            .clipShape(RoundedRectangle(cornerRadius: ShellMetrics.radiusSmall, style: .continuous))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

private func companyRuntimeTint(_ status: String) -> Color {
    switch status.uppercased() {
    case "RUNNING":
        return ShellPalette.success
    case "FAILED":
        return ShellPalette.danger
    case "STOPPED":
        return ShellPalette.warning
    default:
        return ShellPalette.accent
    }
}

private func companyActivityTint(_ kind: String) -> Color {
    switch kind.uppercased() {
    case "MERGE", "MERGED", "DONE":
        return ShellPalette.success
    case "FAILURE", "FAILED", "BLOCKED":
        return ShellPalette.danger
    case "RUNTIME", "RUN":
        return ShellPalette.accent
    default:
        return ShellPalette.accentWarm
    }
}

private func relativeTimestamp(_ value: Int64) -> String {
    let formatter = RelativeDateTimeFormatter()
    formatter.unitsStyle = .short
    let date = Date(timeIntervalSince1970: TimeInterval(value) / 1000)
    return formatter.localizedString(for: date, relativeTo: Date())
}

private struct TerminalPreviewSurface: View {
    let transcript: String
    let language: AppLanguage

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                Text(
                    transcript.isEmpty
                        ? language("Waiting for the TUI session to start...", "TUI 세션이 시작되기를 기다리는 중입니다...")
                        : transcript
                )
                .font(.system(size: 12, weight: .medium, design: .monospaced))
                .foregroundStyle(ShellPalette.text)
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(18)
                .id("terminal-preview-bottom")
            }
            .background(ShellPalette.panelAlt)
            .onChange(of: transcript) { _, _ in
                withAnimation(ShellMotion.spring) {
                    proxy.scrollTo("terminal-preview-bottom", anchor: .bottom)
                }
            }
        }
    }
}

private struct TaskRailCard: View {
    let task: TaskRecord
    let language: AppLanguage
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .top) {
                    Text(task.title)
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(ShellPalette.text)
                        .multilineTextAlignment(.leading)
                        .lineLimit(2)
                    Spacer(minLength: 8)
                    Circle()
                        .fill(statusTint(for: task.status))
                        .frame(width: 8, height: 8)
                }

                Text(task.prompt)
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(ShellPalette.muted)
                    .lineLimit(3)

                HStack {
                    Text(task.agents.joined(separator: " · "))
                        .font(.system(size: 10, weight: .medium))
                        .foregroundStyle(ShellPalette.faint)
                        .lineLimit(1)
                    Spacer()
                    Text(language.status(task.status))
                        .font(.system(size: 10, weight: .semibold))
                        .foregroundStyle(ShellPalette.muted)
                }
            }
            .padding(12)
            .frame(maxHeight: .infinity, alignment: .leading)
            .background(isSelected ? ShellPalette.panelRaised : ShellPalette.panelAlt)
            .overlay(
                RoundedRectangle(cornerRadius: ShellMetrics.radiusSmall, style: .continuous)
                    .stroke(isSelected ? ShellPalette.lineStrong : ShellPalette.line, lineWidth: 1)
            )
            .clipShape(RoundedRectangle(cornerRadius: ShellMetrics.radiusSmall, style: .continuous))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

private struct IssueBoardLaneView: View {
    let title: String
    let tint: Color
    let issues: [IssueRecord]
    let assigneeProvider: (IssueRecord) -> OrgAgentProfileRecord?
    let reviewProvider: (IssueRecord) -> ReviewQueueItemRecord?
    let selectedIssueID: String?
    let language: AppLanguage
    let width: CGFloat
    let onSelect: (IssueRecord) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(title)
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(ShellPalette.text)
                Spacer()
                ShellTag(text: "\(issues.count)", tint: tint)
            }

            if issues.isEmpty {
                EmptyStateView(
                    image: "tray",
                    title: language("No issues", "이슈 없음"),
                    subtitle: language("The CEO agent has nothing queued in this lane right now.", "이 lane에는 현재 CEO 에이전트가 배치한 이슈가 없습니다.")
                )
                .frame(height: 152)
            } else {
                VStack(spacing: 8) {
                    ForEach(issues) { issue in
                        IssueBoardCard(
                            issue: issue,
                            assignee: assigneeProvider(issue),
                            reviewItem: reviewProvider(issue),
                            language: language,
                            isSelected: selectedIssueID == issue.id
                        ) {
                            onSelect(issue)
                        }
                    }
                }
            }
        }
        .frame(width: width, alignment: .top)
        .padding(12)
        .background(ShellPalette.panelAlt)
        .overlay(
            RoundedRectangle(cornerRadius: ShellMetrics.radiusMedium, style: .continuous)
                .stroke(ShellPalette.line, lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: ShellMetrics.radiusMedium, style: .continuous))
    }
}

private struct IssueBoardCard: View {
    let issue: IssueRecord
    let assignee: OrgAgentProfileRecord?
    let reviewItem: ReviewQueueItemRecord?
    let language: AppLanguage
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .top, spacing: 8) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(issue.title)
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(ShellPalette.text)
                            .multilineTextAlignment(.leading)
                            .lineLimit(3)
                        HStack(spacing: 6) {
                            ShellTag(text: issue.kind.uppercased(), tint: ShellPalette.accentSoft)
                            ShellTag(text: language.status(issue.status), tint: statusTint(for: issue.status))
                        }
                    }
                    Spacer(minLength: 8)
                    Circle()
                        .fill(statusTint(for: issue.status))
                        .frame(width: 8, height: 8)
                }

                Text(issue.description)
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(ShellPalette.muted)
                    .lineLimit(4)

                HStack(spacing: 8) {
                    if let assignee {
                        ShellTag(text: assignee.roleName, tint: ShellPalette.accent)
                    }
                    ShellTag(text: "P\(issue.priority)", tint: ShellPalette.accentWarm)
                    Spacer()
                }

                if !issue.dependsOn.isEmpty {
                    HStack(spacing: 8) {
                        ShellTag(text: "\(issue.dependsOn.count) \(language("deps", "의존"))", tint: ShellPalette.warning)
                        Spacer()
                    }
                }

                if let reviewItem {
                    VStack(alignment: .leading, spacing: 5) {
                        Text(language("Review Queue", "리뷰 큐"))
                            .font(.system(size: 9, weight: .bold))
                            .foregroundStyle(ShellPalette.faint)
                        HStack(spacing: 8) {
                            ShellTag(text: language.status(reviewItem.status), tint: reviewTint(reviewItem.status))
                            if let mergeability = reviewItem.mergeability, !mergeability.isEmpty {
                                ShellTag(text: mergeability, tint: ShellPalette.success)
                            }
                        }
                        if let checksSummary = reviewItem.checksSummary, !checksSummary.isEmpty {
                            Text(checksSummary)
                                .font(.system(size: 10, weight: .medium))
                                .foregroundStyle(ShellPalette.muted)
                                .lineLimit(2)
                        }
                    }
                }
            }
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(isSelected ? ShellPalette.panelRaised : ShellPalette.panel)
            .overlay(
                RoundedRectangle(cornerRadius: ShellMetrics.radiusSmall, style: .continuous)
                    .stroke(isSelected ? ShellPalette.lineStrong : ShellPalette.line, lineWidth: 1)
            )
            .clipShape(RoundedRectangle(cornerRadius: ShellMetrics.radiusSmall, style: .continuous))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func reviewTint(_ status: String) -> Color {
        switch status.uppercased() {
        case "READY_TO_MERGE", "MERGED":
            return ShellPalette.success
        case "FAILED_CHECKS", "CHANGES_REQUESTED":
            return ShellPalette.danger
        default:
            return ShellPalette.warning
        }
    }
}

private struct RunTabButton: View {
    let run: RunRecord
    let language: AppLanguage
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                Circle()
                    .fill(statusTint(for: run.status))
                    .frame(width: 8, height: 8)
                VStack(alignment: .leading, spacing: 2) {
                    Text(run.agentName)
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(ShellPalette.text)
                    Text(language.status(run.status))
                        .font(.system(size: 10, weight: .medium))
                        .foregroundStyle(ShellPalette.muted)
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .background(isSelected ? ShellPalette.panelRaised : ShellPalette.panelAlt)
            .overlay(
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .stroke(isSelected ? ShellPalette.lineStrong : ShellPalette.line, lineWidth: 1)
            )
            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

private struct InspectorPaneView: View {
    @EnvironmentObject private var store: DesktopStore
    var embedded: Bool = false
    private var l: AppLanguage { store.language }

    var body: some View {
        Group {
            inspectorContent
        }
        .modifier(InspectorContainerModifier(embedded: embedded))
    }

    private var inspectorContent: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(store.selectedIssue?.title ?? store.selectedRun?.agentName ?? l.text(.inspect))
                        .font(.system(size: 20, weight: .semibold))
                        .foregroundStyle(ShellPalette.text)
                    Text(
                        store.selectedReviewQueueItem.map { l.status($0.status) }
                            ?? store.selectedTask?.title
                            ?? l.text(.inspectorSubtitle)
                    )
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(ShellPalette.muted)
                        .lineLimit(2)
                }

                Spacer()

                if let selectedTask = store.selectedTask, !selectedTask.agents.isEmpty {
                    Menu(store.selectedAgentName ?? l.text(.selectAgent)) {
                        ForEach(selectedTask.agents, id: \.self) { agent in
                            Button(agent) {
                                Task { await store.selectAgent(agent) }
                            }
                        }
                    }
                    .menuStyle(.borderlessButton)
                }
            }

            inspectorTabBar
            inspectorMetadata

            Group {
                switch store.inspectorTab {
                case .changes:
                    ChangesView(language: l, patch: store.changes.patch, files: store.changes.changedFiles)
                case .files:
                    FilesView(language: l, nodes: store.files)
                case .ports:
                    PortsView(language: l, ports: store.ports) { port in
                        store.openPort(port)
                    }
                case .browser:
                    BrowserView(language: l, url: store.browserURL)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        }
    }

    private var inspectorTabBar: some View {
        HStack(spacing: 8) {
            ForEach(InspectorTab.allCases) { tab in
                Button {
                    store.inspectorTab = tab
                } label: {
                    HStack(spacing: 7) {
                        Image(systemName: inspectorIcon(for: tab))
                        Text(l.inspectorTab(tab))
                    }
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(ShellPalette.text)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 9)
                    .frame(maxWidth: .infinity)
                    .background(store.inspectorTab == tab ? ShellPalette.panelRaised : ShellPalette.panelAlt)
                    .overlay(
                        RoundedRectangle(cornerRadius: 10, style: .continuous)
                            .stroke(store.inspectorTab == tab ? ShellPalette.lineStrong : ShellPalette.line, lineWidth: 1)
                    )
                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                }
                .buttonStyle(.plain)
            }
        }
    }

    private var inspectorMetadata: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let issue = store.selectedIssue {
                metadataRow(label: l("Issue Status", "이슈 상태"), value: l.status(issue.status))
                metadataRow(label: l("Issue Kind", "이슈 종류"), value: issue.kind)
                metadataRow(label: l("Risk", "리스크"), value: issue.riskLevel)
                if let assignee = store.selectedIssueAssignee {
                    metadataRow(label: l("Assignee", "담당"), value: "\(assignee.roleName) · \(assignee.executionAgentName)")
                }
                if !issue.acceptanceCriteria.isEmpty {
                    metadataRow(label: l("Acceptance", "수용 기준"), value: issue.acceptanceCriteria.joined(separator: " · "))
                }
            }

            if let reviewItem = store.selectedReviewQueueItem {
                metadataRow(label: l("Review Queue", "리뷰 큐"), value: l.status(reviewItem.status))
                if let branchName = reviewItem.branchName, !branchName.isEmpty {
                    metadataRow(label: l.text(.branch), value: branchName)
                }
                if let worktreePath = reviewItem.worktreePath, !worktreePath.isEmpty {
                    metadataRow(label: l.text(.worktree), value: worktreePath)
                }
                if let pullRequestUrl = reviewItem.pullRequestUrl, !pullRequestUrl.isEmpty {
                    metadataRow(label: l.text(.pullRequest), value: pullRequestUrl)
                }
                if let pullRequestState = reviewItem.pullRequestState, !pullRequestState.isEmpty {
                    metadataRow(label: l("PR State", "PR 상태"), value: l.status(pullRequestState))
                }
                if let mergeability = reviewItem.mergeability, !mergeability.isEmpty {
                    metadataRow(label: l("Mergeability", "머지 가능성"), value: mergeability)
                }
                if let checksSummary = reviewItem.checksSummary, !checksSummary.isEmpty {
                    metadataRow(label: l("Checks", "체크"), value: checksSummary)
                }
                if !reviewItem.requestedReviewers.isEmpty {
                    metadataRow(label: l("Reviewers", "리뷰어"), value: reviewItem.requestedReviewers.joined(separator: ", "))
                }
                if let qaVerdict = reviewItem.qaVerdict, !qaVerdict.isEmpty {
                    metadataRow(label: l("QA Verdict", "QA 판정"), value: l.status(qaVerdict))
                }
                if let qaFeedback = reviewItem.qaFeedback, !qaFeedback.isEmpty {
                    metadataRow(label: l("QA Feedback", "QA 피드백"), value: qaFeedback)
                }
                if let ceoVerdict = reviewItem.ceoVerdict, !ceoVerdict.isEmpty {
                    metadataRow(label: l("CEO Verdict", "CEO 판정"), value: l.status(ceoVerdict))
                }
                if let ceoFeedback = reviewItem.ceoFeedback, !ceoFeedback.isEmpty {
                    metadataRow(label: l("CEO Feedback", "CEO 피드백"), value: ceoFeedback)
                }
                if let mergeCommitSha = reviewItem.mergeCommitSha, !mergeCommitSha.isEmpty {
                    metadataRow(label: l("Merge Commit", "머지 커밋"), value: mergeCommitSha)
                }
            }

            if let run = store.selectedRun {
                metadataRow(label: l.text(.branch), value: run.branchName)
                metadataRow(label: l.text(.base), value: run.baseBranch)
                metadataRow(label: l.text(.worktree), value: run.worktreePath)
                if let publish = run.publish {
                    if let commitSha = publish.commitSha, !commitSha.isEmpty {
                        metadataRow(label: l.text(.commit), value: commitSha)
                    }
                    if let pushedBranch = publish.pushedBranch, !pushedBranch.isEmpty {
                        metadataRow(label: l.text(.pushedBranch), value: pushedBranch)
                    }
                    if let pullRequestUrl = publish.pullRequestUrl, !pullRequestUrl.isEmpty {
                        metadataRow(label: l.text(.pullRequest), value: pullRequestUrl)
                    }
                    if let pullRequestState = publish.pullRequestState, !pullRequestState.isEmpty {
                        metadataRow(label: l("PR State", "PR 상태"), value: l.status(pullRequestState))
                    }
                    if let reviewState = publish.reviewState, !reviewState.isEmpty {
                        metadataRow(label: l("Publish Review", "퍼블리시 리뷰"), value: reviewState)
                    }
                    if let checksSummary = publish.checksSummary, !checksSummary.isEmpty {
                        metadataRow(label: l("Publish Checks", "퍼블리시 체크"), value: checksSummary)
                    }
                    if let mergeability = publish.mergeability, !mergeability.isEmpty {
                        metadataRow(label: l("Publish Mergeability", "퍼블리시 머지 가능성"), value: mergeability)
                    }
                    if let publishError = publish.error, !publishError.isEmpty {
                        metadataRow(label: l.text(.publishError), value: publishError)
                    }
                }
            } else if store.selectedIssue == nil {
                Text(l.text(.selectTaskAndAgent))
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(ShellPalette.muted)
            }
        }
        .padding(14)
        .shellInset()
    }

    private func metadataRow(label: String, value: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label.uppercased())
                .font(.system(size: 9, weight: .bold))
                .tracking(0.7)
                .foregroundStyle(ShellPalette.faint)
            Text(value)
                .font(.system(size: 11, weight: .medium, design: .monospaced))
                .foregroundStyle(ShellPalette.text)
                .lineLimit(2)
                .textSelection(.enabled)
        }
    }

    private func inspectorIcon(for tab: InspectorTab) -> String {
        switch tab {
        case .changes:
            return "arrow.triangle.branch"
        case .files:
            return "doc.text"
        case .ports:
            return "plugs.connected"
        case .browser:
            return "globe"
        }
    }
}

private struct InspectorContainerModifier: ViewModifier {
    let embedded: Bool

    func body(content: Content) -> some View {
        if embedded {
            content
                .padding(14)
                .background(
                    RoundedRectangle(cornerRadius: ShellMetrics.radiusMedium, style: .continuous)
                        .fill(ShellPalette.panel)
                )
                .overlay(
                    RoundedRectangle(cornerRadius: ShellMetrics.radiusMedium, style: .continuous)
                        .stroke(ShellPalette.line, lineWidth: 1)
                )
        } else {
            content.shellCard()
        }
    }
}

private struct ChangesView: View {
    let language: AppLanguage
    let patch: String
    let files: [String]

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if files.isEmpty, patch.isEmpty {
                EmptyStateView(
                    image: "arrow.triangle.branch",
                    title: language.text(.noChanges),
                    subtitle: language.text(.noChangesSubtitle)
                )
            } else {
                if !files.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(files, id: \.self) { file in
                                ShellTag(text: file, tint: ShellPalette.accent)
                            }
                        }
                    }
                }

                ScrollView {
                    Text(patch)
                        .font(.system(size: 11, weight: .medium, design: .monospaced))
                        .foregroundStyle(ShellPalette.text)
                        .textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(14)
                }
                .shellInset()
            }
        }
    }
}

private struct FilesView: View {
    let language: AppLanguage
    let nodes: [FileTreeNodePayload]

    var body: some View {
        if nodes.isEmpty {
            EmptyStateView(
                image: "doc.text.magnifyingglass",
                title: language.text(.noFileTree),
                subtitle: language.text(.noFileTreeSubtitle)
            )
        } else {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    OutlineGroup(nodes, children: \.optionalChildren) { node in
                        HStack(spacing: 10) {
                            Image(systemName: node.isDirectory ? "folder.fill" : "doc.text")
                                .foregroundStyle(node.isDirectory ? ShellPalette.accentWarm : ShellPalette.accent)
                            Text(node.name)
                                .font(.system(size: 12, weight: .medium))
                                .foregroundStyle(ShellPalette.text)
                            Spacer()
                            if let size = node.sizeBytes, !node.isDirectory {
                                Text(ByteCountFormatter.string(fromByteCount: size, countStyle: .file))
                                    .font(.system(size: 10, weight: .medium))
                                    .foregroundStyle(ShellPalette.muted)
                            }
                        }
                        .padding(.vertical, 6)
                    }
                }
                .padding(14)
            }
            .shellInset()
        }
    }
}

private struct PortsView: View {
    let language: AppLanguage
    let ports: [PortEntryPayload]
    let onOpen: (PortEntryPayload) -> Void

    var body: some View {
        if ports.isEmpty {
            EmptyStateView(
                image: "plugs.connected",
                title: language.text(.noPorts),
                subtitle: language.text(.noPortsSubtitle)
            )
        } else {
            ScrollView {
                VStack(spacing: 10) {
                    ForEach(ports) { port in
                        HStack(alignment: .top, spacing: 12) {
                            VStack(alignment: .leading, spacing: 5) {
                                Text(port.label)
                                    .font(.system(size: 13, weight: .semibold))
                                    .foregroundStyle(ShellPalette.text)
                                Text(port.url)
                                    .font(.system(size: 11, weight: .medium, design: .monospaced))
                                    .foregroundStyle(ShellPalette.muted)
                            }

                            Spacer()

                            Button(language.text(.open)) {
                                onOpen(port)
                            }
                            .buttonStyle(.borderedProminent)
                        }
                        .padding(14)
                        .shellInset()
                    }
                }
            }
        }
    }
}

private struct BrowserView: View {
    let language: AppLanguage
    let url: URL?

    var body: some View {
        if let url {
            WebView(url: url)
                .shellInset()
        } else {
            EmptyStateView(
                image: "globe",
                title: language.text(.browserIdle),
                subtitle: language.text(.browserIdleSubtitle)
            )
        }
    }
}

private struct CloneRepositorySheet: View {
    @EnvironmentObject private var store: DesktopStore
    @Environment(\.dismiss) private var dismiss
    private var l: AppLanguage { store.language }

    var body: some View {
        ZStack {
            ShellCanvas()
                .ignoresSafeArea()

            VStack(alignment: .leading, spacing: 16) {
                ShellSectionHeader(
                    eyebrow: l.text(.clone),
                    title: l.text(.cloneRepository),
                    subtitle: l.text(.cloneRepositorySubtitle)
                )

                TextField(l.text(.cloneRepositoryPlaceholder), text: $store.cloneURLInput)
                    .textFieldStyle(.roundedBorder)

                HStack(spacing: 10) {
                    Spacer()
                    Button(l.text(.cancel)) { dismiss() }
                    Button {
                        Task {
                            await store.submitCloneRepository()
                            dismiss()
                        }
                    } label: {
                        Label(l.text(.clone), systemImage: "square.and.arrow.down")
                    }
                    .buttonStyle(.borderedProminent)
                }
            }
            .padding(24)
            .frame(width: 540)
            .shellCard()
            .padding(24)
        }
    }
}

private struct EmptyStateView: View {
    let image: String
    let title: String
    let subtitle: String

    var body: some View {
        VStack(spacing: 12) {
            ZStack {
                Circle()
                    .fill(ShellPalette.accent.opacity(0.14))
                    .frame(width: 48, height: 48)
                Image(systemName: image)
                    .font(.system(size: 20, weight: .semibold))
                    .foregroundStyle(ShellPalette.accentWarm)
            }

            Text(title)
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(ShellPalette.text)

            Text(subtitle)
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(ShellPalette.muted)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(24)
        .shellInset()
    }
}

private struct FlowLayout: View {
    let items: [String]
    let selected: Set<String>
    let action: (String) -> Void

    var body: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 92), spacing: 8)], alignment: .leading, spacing: 8) {
            ForEach(items, id: \.self) { item in
                Button(item) {
                    action(item)
                }
                .buttonStyle(.plain)
                .padding(.horizontal, 10)
                .padding(.vertical, 8)
                .frame(maxWidth: .infinity)
                .background(selected.contains(item) ? ShellPalette.accentSoft : ShellPalette.panelAlt)
                .overlay(
                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .stroke(selected.contains(item) ? ShellPalette.lineStrong : ShellPalette.line, lineWidth: 1)
                )
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                .foregroundStyle(ShellPalette.text)
                .font(.system(size: 12, weight: .semibold))
            }
        }
    }
}

private struct DesktopCommandMenu: Commands {
    @ObservedObject var store: DesktopStore
    private var l: AppLanguage { store.language }

    var body: some Commands {
        CommandMenu(l.text(.cotorMenu)) {
            Button(l.text(.refreshDashboard)) {
                Task { await store.refreshDashboard() }
            }
            .keyboardShortcut("r", modifiers: [.command])

            Button(l.text(.openRepository)) {
                Task { await store.openRepositoryPicker() }
            }
            .keyboardShortcut("n", modifiers: [.command])

            Button(l.text(.cloneRepositoryMenu)) {
                store.showingCloneSheet = true
            }
            .keyboardShortcut("l", modifiers: [.command])

            Button(l.text(.createTaskMenu)) {
                Task { await store.createTask() }
            }
            .keyboardShortcut("t", modifiers: [.command])

            Button(l.text(.openFolder)) {
                store.openSelectedLocation()
            }

            Divider()

            Button("English") {
                store.setLanguage(.english)
            }

            Button("한국어") {
                store.setLanguage(.korean)
            }
        }

        CommandMenu(l.text(.inspectorMenu)) {
            Button(l.text(.showChanges)) {
                store.inspectorTab = .changes
            }
            .keyboardShortcut("1", modifiers: [.command])

            Button(l.text(.showFiles)) {
                store.inspectorTab = .files
            }
            .keyboardShortcut("2", modifiers: [.command])

            Button(l.text(.showPorts)) {
                store.inspectorTab = .ports
            }
            .keyboardShortcut("3", modifiers: [.command])

            Button(l.text(.showBrowser)) {
                store.inspectorTab = .browser
            }
            .keyboardShortcut("4", modifiers: [.command])

            Divider()

            Button(l.text(.filesTab)) {
                store.inspectorTab = .files
            }
            .keyboardShortcut("f", modifiers: [.command])

            Button(l.text(.browserTab)) {
                store.inspectorTab = .browser
            }
            .keyboardShortcut("b", modifiers: [.command])
        }
    }
}

private func localizedSourceKind(_ sourceKind: String, language: AppLanguage) -> String {
    switch sourceKind.uppercased() {
    case "LOCAL":
        return language("Local", "로컬")
    case "CLONED":
        return language("Cloned", "복제됨")
    default:
        return sourceKind
    }
}

private func matches(query: String, values: [String?]) -> Bool {
    let needle = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    guard !needle.isEmpty else { return true }

    return values.contains { value in
        guard let value else { return false }
        return value.lowercased().contains(needle)
    }
}

private func statusTint(for status: String) -> Color {
    switch status.uppercased() {
    case "DONE", "COMPLETED", "SUCCESS", "MERGED":
        return ShellPalette.success
    case "FAILED", "ERROR", "BLOCKED", "FAILED_CHECKS", "CHANGES_REQUESTED":
        return ShellPalette.danger
    case "RUNNING", "QUEUED", "PLANNED", "BACKLOG", "DELEGATED", "IN_PROGRESS", "IN_REVIEW", "AWAITING_QA", "AWAITING_REVIEW", "READY_TO_MERGE", "STARTING":
        return ShellPalette.warning
    default:
        return ShellPalette.accent
    }
}
