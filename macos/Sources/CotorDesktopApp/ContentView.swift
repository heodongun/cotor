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

@main
struct CotorDesktopApp: App {
    @StateObject private var store = DesktopStore()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(store)
                .frame(minWidth: 920, minHeight: 700)
                .preferredColorScheme(.dark)
                .task {
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

/// Adaptive desktop shell with a dense admin-console layout: compact sidebar,
/// session strip, TUI-first center workspace, and an optional bottom drawer.
struct ContentView: View {
    @EnvironmentObject private var store: DesktopStore
    @State private var compactSurface: CompactSurface = .console
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
            get: { store.errorMessage != nil },
            set: { if !$0 { store.errorMessage = nil } }
        )) {
            Button(l.text(.close), role: .cancel) {}
        } message: {
            Text(store.errorMessage ?? "")
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
                SidebarView(searchText: searchText)
                    .frame(width: ShellMetrics.sidebarIdealWidth)
                    .frame(maxHeight: .infinity)

                CenterPaneView(layoutMode: layoutMode, searchText: searchText)
                    .frame(minWidth: ShellMetrics.contentMinWidth, maxWidth: .infinity, maxHeight: .infinity)
            }
        case .stacked:
            HStack(alignment: .top, spacing: 12) {
                SidebarView(searchText: searchText)
                    .frame(width: 286)
                    .frame(maxHeight: .infinity)

                CenterPaneView(layoutMode: layoutMode, searchText: searchText)
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
                        SidebarView(searchText: searchText)
                    case .console:
                        CenterPaneView(layoutMode: layoutMode, searchText: searchText)
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

                    Button {
                        Task { await store.refreshDashboard() }
                    } label: {
                        Label(l("Refresh", "새로고침"), systemImage: "arrow.clockwise")
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
            .padding(.vertical, 10)
            .frame(maxWidth: 380)
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

            Button {
                Task { await store.openRepositoryPicker() }
            } label: {
                Label(l.text(.openRepo), systemImage: "folder")
            }
            .buttonStyle(ShellTopBarButtonStyle(prominent: false))

            Button {
                store.showingCloneSheet = true
            } label: {
                Label(l.text(.cloneRepo), systemImage: "square.and.arrow.down")
            }
            .buttonStyle(ShellTopBarButtonStyle(prominent: false))

            Button {
                Task { await store.runSelectedTask() }
            } label: {
                Label(store.selectedIssue == nil ? l.text(.runSelectedTask) : l("Run Issue", "이슈 실행"), systemImage: "play.fill")
            }
            .buttonStyle(ShellTopBarButtonStyle(prominent: true))
            .disabled(store.selectedIssue == nil && store.selectedTask == nil)
        }
        .padding(12)
        .background(
            RoundedRectangle(cornerRadius: ShellMetrics.radiusMedium, style: .continuous)
                .fill(ShellPalette.panel)
        )
        .overlay(
            RoundedRectangle(cornerRadius: ShellMetrics.radiusMedium, style: .continuous)
                .stroke(ShellPalette.line, lineWidth: 1)
        )
        .shadow(color: Color.black.opacity(0.18), radius: 16, x: 0, y: 10)
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
        Group {
            sidebarHeader
            companySection
            goalSection
            goalComposer
            rosterSection
            companyAgentComposer
            issueSection
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
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(l("Company Goals", "회사 목표"))
                        .font(.system(size: 20, weight: .semibold))
                        .foregroundStyle(ShellPalette.text)
                    Text(l("Set a company goal, inspect the issue graph, and let execution runs feed the review queue.", "회사 목표를 설정하고 이슈 그래프를 확인한 뒤, 실행 결과가 리뷰 큐로 흘러가게 합니다."))
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(ShellPalette.muted)
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

            LazyVGrid(columns: [GridItem(.adaptive(minimum: 92), spacing: 10)], spacing: 10) {
                ShellStatChip(title: l("Goals", "목표"), value: "\(store.goals.count)")
                ShellStatChip(title: l("Issues", "이슈"), value: "\(store.issues.count)")
                ShellStatChip(title: l("Merge", "병합 대기"), value: "\(store.dashboard.opsMetrics.readyToMergeCount)")
            }
        }
    }

    private var tuiSidebarHeader: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(l("TUI Workspaces", "TUI 워크스페이스"))
                        .font(.system(size: 20, weight: .semibold))
                        .foregroundStyle(ShellPalette.text)
                    Text(l("Choose the repo and workspace that should own the live terminal session.", "실시간 터미널 세션을 소유할 저장소와 워크스페이스를 고릅니다."))
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(ShellPalette.muted)
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

            LazyVGrid(columns: [GridItem(.adaptive(minimum: 92), spacing: 10)], spacing: 10) {
                ShellStatChip(title: l.text(.repositories), value: "\(store.repositories.count)")
                ShellStatChip(title: l.text(.workspaces), value: "\(store.workspaces.count)")
                ShellStatChip(title: l("Runs", "실행"), value: "\(store.runs.count)")
            }
        }
    }

    private var companySection: some View {
        VStack(alignment: .leading, spacing: 12) {
            sectionTitle(
                title: l("Company", "회사"),
                subtitle: l("Each company owns one root folder, one operating context, and one autonomous runtime.", "각 회사는 하나의 루트 폴더, 하나의 운영 컨텍스트, 하나의 자율 런타임을 가집니다.")
            )

            if store.companies.isEmpty {
                EmptyStateView(
                    image: "building.2",
                    title: l("No companies yet", "아직 회사가 없습니다"),
                    subtitle: l("Create a company to bind one working folder and start a goal-driven AI organization.", "하나의 작업 폴더를 묶고 goal-driven AI 조직을 시작하려면 회사를 생성하세요.")
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
            TextField(l("Company root path", "회사 루트 경로"), text: $store.newCompanyRootPath)
                .textFieldStyle(.roundedBorder)

            if !store.availableBranches.isEmpty {
                Picker(l("New workspace base branch", "새 워크스페이스 기준 브랜치"), selection: $store.pendingWorkspaceBaseBranch) {
                    ForEach(store.availableBranches, id: \.self) { branch in
                        Text(branch).tag(branch)
                    }
                }
                .pickerStyle(.menu)
            }

            HStack(spacing: 8) {
                Button {
                    Task { await store.createCompany() }
                } label: {
                    Label(l("Create Company", "회사 생성"), systemImage: "building.2.crop.circle")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .disabled(store.newCompanyName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || store.newCompanyRootPath.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)

                Button {
                    Task { await store.startSelectedCompanyRuntime() }
                } label: {
                    Label(l("Start", "시작"), systemImage: "play.fill")
                }
                .buttonStyle(ShellTopBarButtonStyle(prominent: false))
                .disabled(store.selectedCompany == nil)

                Button {
                    Task { await store.stopSelectedCompanyRuntime() }
                } label: {
                    Label(l("Stop", "중지"), systemImage: "stop.fill")
                }
                .buttonStyle(ShellTopBarButtonStyle(prominent: false))
                .disabled(store.selectedCompany == nil)
            }
        }
        .padding(14)
        .shellInset()
    }

    private var goalSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            sectionTitle(
                title: l("Goals", "목표"),
                subtitle: l("Select the top-level company objective that drives issue decomposition.", "이슈 분해를 구동하는 최상위 회사 목표를 선택합니다.")
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
                title: l("New Goal", "새 목표"),
                subtitle: l("Define one business outcome and let the CEO agent break it into issues.", "하나의 비즈니스 목표를 정의하면 CEO 에이전트가 이슈로 분해합니다.")
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

            Button {
                Task { await store.createGoal() }
            } label: {
                Label(l("Create Goal", "목표 생성"), systemImage: "plus.circle.fill")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .disabled(store.newGoalTitle.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || store.newGoalDescription.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        }
        .padding(14)
        .shellInset()
    }

    private var rosterSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            sectionTitle(
                title: l("Derived Roster", "파생 조직도"),
                subtitle: l("The CEO derives hierarchy and routing from your agent titles, CLIs, and role summaries.", "CEO가 에이전트 직함, CLI, 역할 설명을 읽고 hierarchy와 라우팅을 파생합니다.")
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
                            Text("\(profile.executionAgentName) · \(profile.capabilities.joined(separator: ", "))")
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
                title: l("Add Agent", "에이전트 추가"),
                subtitle: l("Define only title, CLI, and role summary. Cotor derives hierarchy and workflow from there.", "직함, CLI, 역할 설명만 정의하세요. Cotor가 hierarchy와 workflow를 거기서 파생합니다.")
            )

            TextField(l("Title", "직함"), text: $store.newCompanyAgentTitle)
                .textFieldStyle(.roundedBorder)

            TextField(l("AI CLI", "AI CLI"), text: $store.newCompanyAgentCli)
                .textFieldStyle(.roundedBorder)

            TextField(l("Role summary", "역할 설명"), text: $store.newCompanyAgentRole)
                .textFieldStyle(.roundedBorder)

            Button {
                Task { await store.createCompanyAgent() }
            } label: {
                Label(l("Add Agent", "에이전트 추가"), systemImage: "person.crop.circle.badge.plus")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .disabled(
                store.selectedCompany == nil ||
                store.newCompanyAgentTitle.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
                store.newCompanyAgentCli.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
                store.newCompanyAgentRole.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            )

            if !store.companyAgentDefinitions.isEmpty {
                VStack(spacing: 8) {
                    ForEach(store.companyAgentDefinitions) { agent in
                        HStack(spacing: 10) {
                            Circle()
                                .fill(agent.enabled ? ShellPalette.success : ShellPalette.warning)
                                .frame(width: 8, height: 8)
                            VStack(alignment: .leading, spacing: 3) {
                                Text("\(agent.title) · \(agent.agentCli)")
                                    .font(.system(size: 12, weight: .semibold))
                                    .foregroundStyle(ShellPalette.text)
                                Text(agent.roleSummary)
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
                subtitle: l("Choose the lead CLI for the live terminal. Additional selected agents join task execution behind the lead.", "실시간 터미널의 리드 CLI를 고르세요. 추가 선택된 에이전트는 리더 뒤에서 task execution에 합류합니다.")
            )

            if store.dashboard.settings.availableAgents.isEmpty {
                EmptyStateView(
                    image: "person.2.slash",
                    title: l("No CLIs detected", "감지된 CLI 없음"),
                    subtitle: l("Configure at least one installed AI CLI to launch the interactive TUI.", "실시간 TUI를 시작하려면 최소 하나의 설치된 AI CLI가 필요합니다.")
                )
                .frame(height: 140)
            } else {
                VStack(spacing: 8) {
                    ForEach(store.dashboard.settings.availableAgents, id: \.self) { agent in
                        Button {
                            store.setWorkflowLeadAgent(agent)
                        } label: {
                            HStack(spacing: 10) {
                                Circle()
                                    .fill(store.workflowLeadAgent == agent ? ShellPalette.accent : ShellPalette.lineStrong)
                                    .frame(width: 8, height: 8)
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(agent)
                                        .font(.system(size: 12, weight: .semibold))
                                        .foregroundStyle(ShellPalette.text)
                                    Text(store.agentSelection.contains(agent) ? l("Included in workflow", "워크플로우에 포함") : l("Not selected", "선택 안 됨"))
                                        .font(.system(size: 10, weight: .medium))
                                        .foregroundStyle(ShellPalette.muted)
                                }
                                Spacer()
                                if store.workflowLeadAgent == agent {
                                    ShellTag(text: l("Lead", "리드"), tint: ShellPalette.accentWarm)
                                }
                            }
                            .padding(.horizontal, 12)
                            .padding(.vertical, 10)
                            .background(ShellPalette.panelAlt)
                            .overlay(
                                RoundedRectangle(cornerRadius: 10, style: .continuous)
                                    .stroke(store.workflowLeadAgent == agent ? ShellPalette.lineStrong : ShellPalette.line, lineWidth: 1)
                            )
                            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
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
                .font(.system(size: 11, weight: .medium))
                .foregroundStyle(ShellPalette.muted)
                .fixedSize(horizontal: false, vertical: true)
        }
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
                HStack(alignment: .top) {
                    Text(goal.title)
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(ShellPalette.text)
                        .lineLimit(2)
                    Spacer(minLength: 8)
                    ShellTag(text: goal.status.replacingOccurrences(of: "_", with: " "), tint: statusTint(for: goal.status))
                }

                Text(goal.description)
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(ShellPalette.muted)
                    .lineLimit(3)
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
                    HStack(alignment: .top) {
                        Text(issue.title)
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(ShellPalette.text)
                            .multilineTextAlignment(.leading)
                        Spacer(minLength: 8)
                        Text(issue.kind.uppercased())
                            .font(.system(size: 9, weight: .bold))
                            .foregroundStyle(ShellPalette.faint)
                    }

                    Text(issue.description)
                        .font(.system(size: 11, weight: .medium))
                        .foregroundStyle(ShellPalette.muted)
                        .lineLimit(2)

                    HStack(spacing: 8) {
                        ShellTag(text: issue.status.replacingOccurrences(of: "_", with: " "), tint: statusTint(for: issue.status))
                        if !issue.dependsOn.isEmpty {
                            ShellTag(text: "\(issue.dependsOn.count) deps", tint: ShellPalette.warning)
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
    @State private var goalSurface: GoalSurface = .board
    @State private var detailDrawerOpen = false
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

    private var visibleActivity: [CompanyActivityItemRecord] {
        Array(store.activity.prefix(8))
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
    }

    private var content: some View {
        VStack(alignment: .leading, spacing: 12) {
            sessionStrip

            if store.shellMode == .tui {
                terminalContextBar
                tuiConsole
            } else {
                operationsBanner
                surfaceSwitcher
                issueSurface
                companyActivityFeed
            }

            detailDrawer
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
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
                        subtitle: goal.status.replacingOccurrences(of: "_", with: " "),
                        tint: statusTint(for: goal.status)
                    ) {
                        Task { await store.selectGoal(goal) }
                    }
                }
                if let issue = store.selectedIssue {
                    SessionStripItem(
                        title: issue.title,
                        subtitle: issue.status.replacingOccurrences(of: "_", with: " "),
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
                        subtitle: reviewItem.status.replacingOccurrences(of: "_", with: " "),
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
                ShellTag(text: issue.status.replacingOccurrences(of: "_", with: " "), tint: statusTint(for: issue.status))
            }
            ShellTag(text: "\(l("Lead AI", "리더 AI")): \(workflowLeader)", tint: ShellPalette.accentWarm)
            if let session = store.tuiSession {
                ShellTag(text: session.baseBranch, tint: ShellPalette.success)
            }
            Spacer()
        }
    }

    private var operationsBanner: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top, spacing: 16) {
                VStack(alignment: .leading, spacing: 6) {
                    Text(store.selectedGoal?.title ?? l("Choose a goal", "목표를 선택하세요"))
                        .font(.system(size: 28, weight: .semibold))
                        .foregroundStyle(ShellPalette.text)

                    Text(store.selectedGoal?.description ?? store.selectedRepository?.localPath ?? l.text(.openOrCloneRepository))
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(ShellPalette.muted)
                        .lineLimit(2)
                        .textSelection(.enabled)
                }

                Spacer()

                VStack(alignment: .trailing, spacing: 8) {
                    ShellStatusPill(
                        text: store.selectedIssue?.status.replacingOccurrences(of: "_", with: " ") ?? l("IDLE", "대기"),
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

            LazyVGrid(columns: [GridItem(.adaptive(minimum: 108), spacing: 10)], spacing: 10) {
                ShellStatChip(title: l("Goals", "목표"), value: "\(store.dashboard.opsMetrics.openGoals)")
                ShellStatChip(title: l("Active Issues", "활성 이슈"), value: "\(store.dashboard.opsMetrics.activeIssues)")
                ShellStatChip(title: l("Blocked", "차단"), value: "\(store.dashboard.opsMetrics.blockedIssues)")
                ShellStatChip(title: l("Ready To Merge", "병합 대기"), value: "\(store.dashboard.opsMetrics.readyToMergeCount)")
            }

            HStack(spacing: 8) {
                ShellTag(text: l("Board Driven", "보드 중심"), tint: ShellPalette.accentWarm)
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
                    ShellTag(text: runtime.status, tint: companyRuntimeTint(runtime.status))
                }
            }

            if store.activity.isEmpty {
                EmptyStateView(
                    image: "waveform.path.ecg",
                    title: l("No activity yet", "아직 활동이 없습니다"),
                    subtitle: l("Goal planning, issue delegation, run execution, and review events will appear here.", "goal planning, issue delegation, run execution, review 이벤트가 여기에 표시됩니다.")
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
                        title: status.replacingOccurrences(of: "_", with: " "),
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
                            ShellTag(text: issue.status.replacingOccurrences(of: "_", with: " "), tint: statusTint(for: issue.status))
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
                    TerminalPreviewSurface(
                        transcript: store.tuiSession?.transcript ?? MockSeed.tuiSession.transcript,
                        language: l
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
                        ShellTag(text: item.status.replacingOccurrences(of: "_", with: " "), tint: reviewTint(item.status))
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
                            ShellTag(text: runtime.status, tint: companyRuntimeTint(runtime.status))
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
                        Text(issue.kind.uppercased())
                            .font(.system(size: 9, weight: .bold))
                            .foregroundStyle(ShellPalette.faint)
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
                    if !issue.dependsOn.isEmpty {
                        ShellTag(text: "\(issue.dependsOn.count) deps", tint: ShellPalette.warning)
                    }
                }

                if let reviewItem {
                    VStack(alignment: .leading, spacing: 5) {
                        Text(language("Review Queue", "리뷰 큐"))
                            .font(.system(size: 9, weight: .bold))
                            .foregroundStyle(ShellPalette.faint)
                        HStack(spacing: 8) {
                            ShellTag(text: reviewItem.status.replacingOccurrences(of: "_", with: " "), tint: reviewTint(reviewItem.status))
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
                        store.selectedReviewQueueItem?.status.replacingOccurrences(of: "_", with: " ")
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
                metadataRow(label: l("Issue Status", "이슈 상태"), value: issue.status)
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
                metadataRow(label: l("Review Queue", "리뷰 큐"), value: reviewItem.status)
                if let pullRequestUrl = reviewItem.pullRequestUrl, !pullRequestUrl.isEmpty {
                    metadataRow(label: l.text(.pullRequest), value: pullRequestUrl)
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
    case "RUNNING", "QUEUED":
        return ShellPalette.warning
    case "COMPLETED", "SUCCESS":
        return ShellPalette.success
    case "FAILED", "ERROR":
        return ShellPalette.danger
    default:
        return ShellPalette.accent
    }
}
