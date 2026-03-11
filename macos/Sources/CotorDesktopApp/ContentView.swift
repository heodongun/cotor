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

/// Adaptive desktop shell with a dense workbench layout inspired by modern
/// multi-pane developer tools: thin chrome, compact navigation, and one central
/// console surface that stays visually dominant.
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

                InspectorPaneView()
                    .frame(width: ShellMetrics.inspectorMinWidth)
                    .frame(maxHeight: .infinity)
            }
        case .stacked:
            HStack(alignment: .top, spacing: 12) {
                SidebarView(searchText: searchText)
                    .frame(width: 286)
                    .frame(maxHeight: .infinity)

                VStack(spacing: 12) {
                    CenterPaneView(layoutMode: layoutMode, searchText: searchText)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)

                    InspectorPaneView()
                        .frame(maxWidth: .infinity, minHeight: max(310, size.height * 0.34))
                }
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
                    Text(store.selectedRepository?.name ?? l("Local Workflow Console", "로컬 워크플로우 콘솔"))
                        .font(.system(size: 11, weight: .medium))
                        .foregroundStyle(ShellPalette.muted)
                }
            }

            Spacer(minLength: 0)

            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass")
                    .foregroundStyle(ShellPalette.muted)
                TextField(l("Search repos, workspaces, or workflows", "저장소, 워크스페이스, 워크플로우 검색"), text: $searchText)
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

            if let workspace = store.selectedWorkspace {
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
                Label(l.text(.runSelectedTask), systemImage: "play.fill")
            }
            .buttonStyle(ShellTopBarButtonStyle(prominent: true))
            .disabled(store.selectedTask == nil)
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
}

private struct SidebarView: View {
    @EnvironmentObject private var store: DesktopStore
    let searchText: String
    private var l: AppLanguage { store.language }

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
                sidebarHeader
                workspaceSection
                workspaceComposer
                taskSection
            }
            .padding(2)
        }
        .scrollIndicators(.hidden)
        .shellCard()
    }

    private var sidebarHeader: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(l.text(.workspaces))
                        .font(.system(size: 20, weight: .semibold))
                        .foregroundStyle(ShellPalette.text)
                    Text(l("Author workflow definitions here, then let the TUI invoke them through isolated agent worktrees.", "여기서 워크플로우 정의를 만들고, TUI가 격리된 에이전트 워크트리를 통해 이를 호출하도록 합니다."))
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
                ShellStatChip(title: l.text(.repos), value: "\(store.repositories.count)")
                ShellStatChip(title: l.text(.workspaces), value: "\(store.workspaces.count)")
                ShellStatChip(title: l.text(.tasks), value: "\(store.tasks.count)")
            }
        }
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
                subtitle: l("Lock a new workspace to the currently selected base branch.", "현재 선택된 기준 브랜치에 새 워크스페이스를 고정합니다.")
            )

            if !store.availableBranches.isEmpty {
                Picker(l.text(.baseBranch), selection: $store.selectedBaseBranch) {
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

    private var taskSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            sectionTitle(
                title: l.text(.tasks),
                subtitle: l("Workflow definitions stay scoped to the selected workspace and drive the orchestration console.", "워크플로우 정의는 선택된 워크스페이스에 묶이고 오케스트레이션 콘솔을 구동합니다.")
            )

            if filteredTasks.isEmpty {
                EmptyStateView(
                    image: "list.bullet.rectangle",
                    title: l.text(.noTasks),
                    subtitle: l.text(.noTasksSubtitle)
                )
                .frame(height: 170)
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

private struct CenterPaneView: View {
    @EnvironmentObject private var store: DesktopStore
    let layoutMode: DesktopLayoutMode
    let searchText: String
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
            workspaceBanner
            tuiConsole
            composerSurface
            taskRail
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
    }

    private var workspaceBanner: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top, spacing: 16) {
                VStack(alignment: .leading, spacing: 6) {
                    Text(store.selectedWorkspace?.name ?? l.text(.chooseWorkspace))
                        .font(.system(size: 28, weight: .semibold))
                        .foregroundStyle(ShellPalette.text)

                    Text(store.selectedRepository?.localPath ?? l.text(.openOrCloneRepository))
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(ShellPalette.muted)
                        .lineLimit(2)
                        .textSelection(.enabled)
                }

                Spacer()

                VStack(alignment: .trailing, spacing: 8) {
                    ShellStatusPill(
                        text: l.status(store.selectedTask?.status ?? "IDLE"),
                        tint: statusTint(for: store.selectedTask?.status ?? "IDLE")
                    )

                    if !store.availableBranches.isEmpty {
                        Picker(l.text(.baseBranch), selection: $store.selectedBaseBranch) {
                            ForEach(store.availableBranches, id: \.self) { branch in
                                Text(branch).tag(branch)
                            }
                        }
                        .pickerStyle(.menu)
                    }
                }
            }

            LazyVGrid(columns: [GridItem(.adaptive(minimum: 108), spacing: 10)], spacing: 10) {
                ShellStatChip(title: l.text(.tasks), value: "\(store.tasks.count)")
                ShellStatChip(title: l.text(.agents), value: "\(store.selectedTask?.agents.count ?? 0)")
                ShellStatChip(title: l.text(.runs), value: "\(store.runs.count)")
                ShellStatChip(title: l.text(.ports), value: "\(store.ports.count)")
            }

            HStack(spacing: 8) {
                ShellTag(text: l("TUI Invoked", "TUI 호출"), tint: ShellPalette.accentWarm)
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
        }
        .buttonStyle(.plain)
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
        }
        .buttonStyle(.plain)
    }
}

private struct InspectorPaneView: View {
    @EnvironmentObject private var store: DesktopStore
    private var l: AppLanguage { store.language }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(store.selectedRun?.agentName ?? l.text(.inspect))
                        .font(.system(size: 20, weight: .semibold))
                        .foregroundStyle(ShellPalette.text)
                    Text(store.selectedTask?.title ?? l.text(.inspectorSubtitle))
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(ShellPalette.muted)
                        .lineLimit(2)
                }

                Spacer()

                Menu(store.selectedAgentName ?? l.text(.selectAgent)) {
                    ForEach(store.selectedTask?.agents ?? [], id: \.self) { agent in
                        Button(agent) {
                            Task { await store.selectAgent(agent) }
                        }
                    }
                }
                .menuStyle(.borderlessButton)
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
        .shellCard()
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
                    if let publishError = publish.error, !publishError.isEmpty {
                        metadataRow(label: l.text(.publishError), value: publishError)
                    }
                }
            } else {
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
