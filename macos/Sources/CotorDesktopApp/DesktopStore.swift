import AppKit
import Foundation
import SwiftUI


// MARK: - File Overview
// DesktopStore belongs to the native macOS client layer for the Cotor desktop application.
// It collects declarations centered on desktop store so the native shell code stays easier to navigate.
// Start with this file when tracing how the desktop client presents, stores, or moves state in this area.

/// Tracks the high-level backend/runtime state shown in the shell header.
///
/// The visible text is derived later through the active app language so the same
/// state can be re-rendered instantly when the user flips languages.
private enum StatusState {
    case connecting
    case waitingForServer
    case connected(String)
    case offlineMock
    case taskStarted(String)
}

enum AppShellMode: String, CaseIterable, Identifiable {
    case company
    case tui

    var id: String { rawValue }
}

/// Main view model for the macOS shell.
///
/// It coordinates bootstrap, selection state, optimistic actions, and runtime
/// language choice for the live desktop shell.
@MainActor
final class DesktopStore: ObservableObject {
    private static let languageDefaultsKey = "cotor.desktop.language"
    private static let themeDefaultsKey = "cotor.desktop.theme"

    @Published var dashboard: DashboardPayload = .empty
    @Published var runs: [RunRecord] = []
    @Published var tuiSession: TuiSessionRecord?
    @Published var availableBranches: [String] = ["master"]
    @Published var pendingWorkspaceBaseBranch = "master"
    @Published var selectedRepositoryID: String?
    @Published var selectedWorkspaceID: String?
    @Published var selectedCompanyID: String?
    @Published var selectedGoalID: String?
    @Published var selectedIssueID: String?
    @Published var selectedTaskID: String?
    @Published var selectedAgentName: String?
    @Published var shellMode: AppShellMode = .company
    @Published var inspectorTab: InspectorTab = .changes
    @Published var changes: ChangeSummaryPayload = ChangeSummaryPayload(runId: "", branchName: "", baseBranch: "", patch: "", changedFiles: [])
    @Published var files: [FileTreeNodePayload] = []
    @Published var ports: [PortEntryPayload] = []
    @Published var browserURL: URL?
    @Published var language: AppLanguage
    @Published var theme: AppTheme
    @Published var isOffline = false
    @Published var isBusy = false
    @Published var repositoryPathInput = ""
    @Published var cloneURLInput = ""
    @Published var newCompanyName = ""
    @Published var newCompanyRootPath = ""
    @Published var newCompanyAgentTitle = ""
    @Published var newCompanyAgentCli = ""
    @Published var newCompanyAgentRole = ""
    @Published var newCompanyAgentSpecialties = ""
    @Published var newCompanyAgentCollaborationNotes = ""
    @Published var newCompanyAgentMemoryNotes = ""
    @Published var newCompanyAgentPreferredCollaboratorIDs: Set<String> = []
    @Published var newCompanyAgentEnabled = true
    @Published var editingCompanyAgentID: String?
    @Published var editingCompanyAgentCompanyID: String?
    @Published var newWorkspaceName = ""
    @Published var newGoalTitle = ""
    @Published var newGoalDescription = ""
    @Published var editingGoalID: String?
    @Published var newIssueCompanyID: String?
    @Published var newIssueGoalID: String?
    @Published var newIssueTitle = ""
    @Published var newIssueDescription = ""
    @Published var defaultBackendKind = "LOCAL_COTOR"
    @Published var codePublishMode = "REQUIRE_GITHUB_PR"
    @Published var codexLaunchMode = "MANAGED"
    @Published var codexCommand = "codex"
    @Published var codexArgs = "app-server --host 127.0.0.1 --port {port}"
    @Published var codexWorkingDirectory = ""
    @Published var codexPort = ""
    @Published var codexStartupTimeoutSeconds = "15"
    @Published var codexAppServerBaseURL = ""
    @Published var codexBackendStatus: ExecutionBackendStatusPayload?
    @Published var companyLinearSyncEnabled = false
    @Published var companyLinearEndpoint = ""
    @Published var companyLinearTeamID = ""
    @Published var companyLinearProjectID = ""
    @Published var companyLinearStatusMessage: String?
    @Published var newTaskTitle = ""
    @Published var newTaskPrompt = ""
    @Published var agentSelection: Set<String> = ["claude", "codex"]
    @Published var workflowLeadAgent: String
    @Published var showingOpenSheet = false
    @Published var showingCloneSheet = false
    @Published var actionErrorMessage: String?
    @Published var companyStreamStatusMessage: String?
    @Published var backendStatusMessage: String?
    @Published var errorMessage: String?

    let api = DesktopAPI()
    private var statusState: StatusState = .connecting
    private var tuiPollingTask: Task<Void, Never>?
    private var companyEventTask: Task<Void, Never>?
    private var companyPollingTask: Task<Void, Never>?
    private var backendWatchdogTask: Task<Void, Never>?
    private var polledTuiSessionID: String?
    private var didInitializeShellMode = false

    init() {
        let storedLanguage = UserDefaults.standard.string(forKey: Self.languageDefaultsKey)
        language = AppLanguage(rawValue: storedLanguage ?? "") ?? .english
        let storedTheme = UserDefaults.standard.string(forKey: Self.themeDefaultsKey)
        theme = AppTheme(rawValue: storedTheme ?? "") ?? .system
        workflowLeadAgent = ""
    }

    deinit {
        tuiPollingTask?.cancel()
        companyEventTask?.cancel()
        companyPollingTask?.cancel()
        backendWatchdogTask?.cancel()
    }

    /// Header status copy is generated from the current state and active language.
    var statusMessage: String {
        switch statusState {
        case .connecting:
            return text(.connectingToServer)
        case .waitingForServer:
            return text(.waitingForServer)
        case let .connected(url):
            return DesktopStrings.connectedToServer(url, language: language)
        case .offlineMock:
            return text(.offlineMockData)
        case let .taskStarted(title):
            return DesktopStrings.startedTask(title, language: language)
        }
    }

    var repositories: [RepositoryRecord] {
        dashboard.repositories.sorted { lhs, rhs in
            lhs.updatedAt > rhs.updatedAt
        }
    }

    var companies: [CompanyRecord] {
        dashboard.companies.sorted { lhs, rhs in
            lhs.updatedAt > rhs.updatedAt
        }
    }

    var availableCliAgents: [String] {
        let cliAgents = dashboard.settings.availableCliAgents.sorted()
        return cliAgents.isEmpty ? dashboard.settings.availableAgents.sorted() : cliAgents
    }

    var preferredCliAgent: String {
        preferredAgent(from: availableCliAgents) ?? preferredAgent(from: dashboard.settings.availableAgents) ?? ""
    }

    var availableCompanyAgentCollaborators: [CompanyAgentDefinitionRecord] {
        companyAgentDefinitions.filter { $0.id != editingCompanyAgentID }
    }

    var companyAgentDefinitions: [CompanyAgentDefinitionRecord] {
        dashboard.companyAgentDefinitions
            .filter { selectedCompanyID == nil || $0.companyId == selectedCompanyID }
            .sorted { lhs, rhs in
                if lhs.displayOrder == rhs.displayOrder {
                    return lhs.title < rhs.title
                }
                return lhs.displayOrder < rhs.displayOrder
            }
    }

    var projectContexts: [CompanyProjectContextRecord] {
        dashboard.projectContexts
            .filter { selectedCompanyID == nil || $0.companyId == selectedCompanyID }
            .sorted { $0.lastUpdatedAt > $1.lastUpdatedAt }
    }

    var activity: [CompanyActivityItemRecord] {
        dashboard.activity
            .filter { selectedCompanyID == nil || $0.companyId == selectedCompanyID }
            .sorted { $0.createdAt > $1.createdAt }
    }

    var companyRuntimes: [CompanyRuntimeSnapshotRecord] {
        dashboard.companyRuntimes
            .filter { selectedCompanyID == nil || $0.companyId == selectedCompanyID }
            .sorted { ($0.lastTickAt ?? 0) > ($1.lastTickAt ?? 0) }
    }

    var workspaces: [WorkspaceRecord] {
        dashboard.workspaces
            .filter { selectedRepositoryID == nil || $0.repositoryId == selectedRepositoryID }
            .sorted { lhs, rhs in
                lhs.updatedAt > rhs.updatedAt
            }
    }

    var goals: [GoalRecord] {
        dashboard.goals
            .filter { selectedCompanyID == nil || $0.companyId == selectedCompanyID }
            .sorted { lhs, rhs in
            lhs.updatedAt > rhs.updatedAt
            }
    }

    var issues: [IssueRecord] {
        dashboard.issues
            .filter { (selectedCompanyID == nil || $0.companyId == selectedCompanyID) && (selectedGoalID == nil || $0.goalId == selectedGoalID) }
            .sorted { lhs, rhs in
                lhs.updatedAt > rhs.updatedAt
            }
    }

    var orgProfiles: [OrgAgentProfileRecord] {
        dashboard.orgProfiles
            .filter { selectedCompanyID == nil || $0.companyId == selectedCompanyID }
            .sorted { lhs, rhs in
            lhs.roleName < rhs.roleName
            }
    }

    var tasks: [TaskRecord] {
        dashboard.tasks
            .filter { task in
                let workspaceMatch = selectedWorkspaceID == nil || task.workspaceId == selectedWorkspaceID
                guard workspaceMatch else { return false }
                guard let companyID = selectedCompanyID else { return true }
                if let issueID = task.issueId,
                   let issue = dashboard.issues.first(where: { $0.id == issueID }) {
                    return issue.companyId == companyID
                }
                guard let workspace = dashboard.workspaces.first(where: { $0.id == task.workspaceId }),
                      let company = selectedCompany else {
                    return true
                }
                return workspace.repositoryId == company.repositoryId
            }
            .sorted { lhs, rhs in
                lhs.updatedAt > rhs.updatedAt
            }
    }

    var selectedRepository: RepositoryRecord? {
        repositories.first { $0.id == selectedRepositoryID }
    }

    var selectedWorkspace: WorkspaceRecord? {
        dashboard.workspaces.first { $0.id == selectedWorkspaceID }
    }

    var selectedGoal: GoalRecord? {
        goals.first { $0.id == selectedGoalID }
    }

    var selectedCompany: CompanyRecord? {
        companies.first { $0.id == selectedCompanyID }
    }

    var selectedIssue: IssueRecord? {
        dashboard.issues.first { $0.id == selectedIssueID }
    }

    var issueComposerCompany: CompanyRecord? {
        if let newIssueCompanyID,
           let explicit = companies.first(where: { $0.id == newIssueCompanyID }) {
            return explicit
        }
        return selectedCompany
    }

    var issueComposerGoals: [GoalRecord] {
        let companyID = issueComposerCompany?.id
        return dashboard.goals
            .filter { companyID == nil || $0.companyId == companyID }
            .sorted { $0.updatedAt > $1.updatedAt }
    }

    var selectedReviewQueueItem: ReviewQueueItemRecord? {
        guard let selectedIssueID else { return nil }
        return dashboard.reviewQueue
            .filter { $0.issueId == selectedIssueID }
            .sorted { lhs, rhs in lhs.updatedAt > rhs.updatedAt }
            .first
    }

    var selectedIssueAssignee: OrgAgentProfileRecord? {
        guard let profileID = selectedIssue?.assigneeProfileId else { return nil }
        return dashboard.orgProfiles.first { $0.id == profileID }
    }

    var selectedRuntime: CompanyRuntimeSnapshotRecord? {
        companyRuntimes.first { $0.companyId == (selectedCompanyID ?? selectedCompany?.id) }
    }

    var currentWorkspaceBaseBranch: String {
        selectedWorkspace?.baseBranch ?? selectedCompany?.defaultBaseBranch ?? selectedRepository?.defaultBranch ?? pendingWorkspaceBaseBranch
    }

    var selectedTask: TaskRecord? {
        if let selectedTaskID,
           let explicit = dashboard.tasks.first(where: { $0.id == selectedTaskID }) {
            return explicit
        }
        if let selectedIssueID {
            return dashboard.tasks
                .filter { $0.issueId == selectedIssueID }
                .sorted { lhs, rhs in lhs.updatedAt > rhs.updatedAt }
                .first
        }
        return nil
    }

    var selectedRun: RunRecord? {
        let preferredAgent = selectedAgentName ?? selectedTask?.agents.first
        if let selectedTaskID,
           let exact = runs.first(where: { $0.taskId == selectedTaskID && $0.agentName == preferredAgent }) {
            return exact
        }
        if let selectedTaskID,
           let latestForTask = runs.first(where: { $0.taskId == selectedTaskID }) {
            return latestForTask
        }
        return runs.first
    }

    func text(_ key: DesktopTextKey) -> String {
        DesktopStrings.text(key, language: language)
    }

    func setLanguage(_ language: AppLanguage) {
        self.language = language
        UserDefaults.standard.set(language.rawValue, forKey: Self.languageDefaultsKey)
        objectWillChange.send()
    }

    func setTheme(_ theme: AppTheme) {
        self.theme = theme
        UserDefaults.standard.set(theme.rawValue, forKey: Self.themeDefaultsKey)
        objectWillChange.send()
    }

    func openSettings() {
        NSApp.sendAction(Selector(("showSettingsWindow:")), to: nil, from: nil)
        NSApp.activate(ignoringOtherApps: true)
    }

    /// The desktop app treats the first workflow agent as the coordinator that
    /// fans work out to the remaining worker agents. The backend still stores a
    /// simple ordered agent list, so keeping this invariant in the client makes
    /// the workflow authoring UX line up with the product concept.
    func setWorkflowLeadAgent(_ agent: String) {
        workflowLeadAgent = agent
        agentSelection.insert(agent)
        // The embedded terminal is the live "lead AI" console, so when the user
        // switches leaders we immediately re-open the interactive session against
        // that agent instead of leaving the old TUI attached to the workspace.
        if selectedWorkspace != nil, !isOffline {
            Task { await restartTuiSession() }
        }
    }

    /// Keep lead-agent selection coherent while the user edits the workflow roster.
    func toggleWorkflowAgent(_ agent: String) {
        if agentSelection.contains(agent) {
            // Never leave the workflow without a lead agent. If the user removes
            // the current leader, immediately promote the next remaining agent.
            if agent == workflowLeadAgent {
                guard agentSelection.count > 1 else { return }
                agentSelection.remove(agent)
                workflowLeadAgent = agentSelection.sorted().first ?? ""
            } else {
                agentSelection.remove(agent)
            }
        } else {
            agentSelection.insert(agent)
            if workflowLeadAgent.isEmpty {
                workflowLeadAgent = agent
            }
        }
    }

    func statusLabel(_ status: String) -> String {
        DesktopStrings.status(status, language: language)
    }

    func shortcutTitle(_ binding: ShortcutBindingPayload) -> String {
        DesktopStrings.shortcutTitle(id: binding.id, fallback: binding.title, language: language)
    }

    /// Entry point invoked by the app scene once the window becomes active.
    func bootstrap() async {
        // Bootstrap intentionally starts local background observers before the first network call.
        // That way a just-launched app can recover embedded backend state, begin polling, and only
        // then decide whether the shell should present live data or an offline fallback.
        startCompanyStatePolling()
        startEmbeddedBackendWatchdog()
        await EmbeddedBackendLauncher.shared.ensureRunning()
        // Installed app bundles launch the backend lazily, so the first request can
        // arrive before `cotor app-server` has finished binding its localhost port.
        for attempt in 0 ..< 4 {
            await refreshDashboard()
            if !isOffline {
                return
            }
            if attempt < 3 {
                statusState = .waitingForServer
                objectWillChange.send()
                await EmbeddedBackendLauncher.shared.ensureRunning()
                try? await Task.sleep(for: .seconds(1))
            }
        }
    }

    /// Reload the top-level dashboard payload and preserve/repair selection state.
    func refreshDashboard(restartEventStream: Bool = true) async {
        // The dashboard payload is the SwiftUI store's source of truth. Most per-pane selections
        // are repaired immediately after loading so the shell can survive backend restarts, data
        // deletions, and stream reconnects without stranding the user on stale identifiers.
        isBusy = true
        defer { isBusy = false }

        do {
            let fresh = try await runWithEmbeddedBackendRecovery {
                try await api.dashboard()
            }
            dashboard = fresh
            errorMessage = nil
            isOffline = false
            statusState = .connected(api.baseURL.absoluteString)
            if !didInitializeShellMode {
                shellMode = dashboard.settings.defaultLaunchMode.lowercased() == "tui" ? .tui : .company
                didInitializeShellMode = true
            }
            reconcileWorkflowLeadAgent()
            reconcileSelection()
            syncIssueComposerState()
            syncBackendFormState()
            await refreshAvailableBranches()
            await refreshTaskDetails()
            await ensureTuiSession()
            if restartEventStream {
                await restartCompanyEventStream()
            }
        } catch is CancellationError {
            // Dashboard refresh is also used from the live company event stream.
            // When that stream is restarted we can cancel an in-flight refresh as
            // part of normal control flow, which should not be treated as a
            // backend disconnect or a failed goal creation request.
            return
        } catch {
            let backendStillHealthy = (try? await api.health()) == true
            if backendStillHealthy {
                AppLogger.error("Dashboard refresh failed while backend remained healthy: \(error.localizedDescription)")
                isOffline = false
                statusState = .connected(api.baseURL.absoluteString)
                errorMessage = error.localizedDescription
                return
            }
            isOffline = true
            statusState = .offlineMock
            AppLogger.error("Dashboard refresh marked app offline: \(error.localizedDescription)")
            errorMessage = error.localizedDescription
            runs = []
            changes = emptyChangeSummary()
            files = []
            ports = []
            browserURL = nil
            reconcileWorkflowLeadAgent()
            reconcileSelection()
            syncIssueComposerState()
            selectedAgentName = selectedTask?.agents.first
            await refreshAvailableBranches()
            stopTuiPolling()
            companyEventTask?.cancel()
            tuiSession = nil
        }
    }

    /// Repair selection state after a dashboard refresh so every pane still points
    /// at records that exist in the freshly returned payload.
    private func reconcileSelection() {
        if !companies.contains(where: { $0.id == selectedCompanyID }) {
            selectedCompanyID = companies.first?.id
        }
        if !repositories.contains(where: { $0.id == selectedRepositoryID }) {
            selectedRepositoryID = selectedCompany.map(\.repositoryId) ?? repositories.first?.id
        }
        if !workspaces.contains(where: { $0.id == selectedWorkspaceID }) {
            selectedWorkspaceID = workspaces.first?.id
        }
        if !goals.contains(where: { $0.id == selectedGoalID }) {
            selectedGoalID = goals.first?.id
        }
        if !issues.contains(where: { $0.id == selectedIssueID }) {
            selectedIssueID = issues.first?.id
        }
        if !tasks.contains(where: { $0.id == selectedTaskID }) {
            selectedTaskID = tasks.first?.id
        }

        if let issue = selectedIssue {
            if selectedCompanyID != issue.companyId {
                selectedCompanyID = issue.companyId
            }
            if selectedWorkspaceID != issue.workspaceId {
                selectedWorkspaceID = issue.workspaceId
            }
            if selectedTask == nil {
                selectedTaskID = dashboard.tasks
                    .filter { $0.issueId == issue.id }
                    .sorted { lhs, rhs in lhs.updatedAt > rhs.updatedAt }
                    .first?.id
            }
        }

        if let task = selectedTask {
            if !task.agents.contains(selectedAgentName ?? "") {
                selectedAgentName = task.agents.first
            }
        } else {
            selectedAgentName = nil
        }
        if let editingAgentID = editingCompanyAgentID,
           !companyAgentDefinitions.contains(where: { $0.id == editingAgentID && $0.companyId == editingCompanyAgentCompanyID }) {
            resetCompanyAgentComposer()
        }
        pendingWorkspaceBaseBranch = selectedWorkspace?.baseBranch ?? selectedCompany?.defaultBaseBranch ?? selectedRepository?.defaultBranch ?? "master"
    }

    private func syncIssueComposerState() {
        if issueComposerCompany == nil {
            newIssueCompanyID = selectedCompanyID ?? companies.first?.id
        }
        let validCompanyID = issueComposerCompany?.id
        if let currentGoalID = newIssueGoalID,
           !dashboard.goals.contains(where: { $0.id == currentGoalID && $0.companyId == validCompanyID }) {
            newIssueGoalID = nil
        }
        if newIssueGoalID == nil {
            newIssueGoalID = issueComposerGoals.first?.id ?? selectedGoalID
        }
    }

    /// Repair the workflow lead and selected agent roster after bootstrap or
    /// dashboard refresh. This keeps the authoring UI stable even when the
    /// backend roster changes after a live dashboard refresh.
    private func reconcileWorkflowLeadAgent() {
        let availableAgents = dashboard.settings.availableAgents
        let cliAgents = availableCliAgents

        if availableAgents.isEmpty {
            workflowLeadAgent = ""
            agentSelection = []
            newCompanyAgentCli = ""
            return
        }

        if workflowLeadAgent.isEmpty || !availableAgents.contains(workflowLeadAgent) {
            workflowLeadAgent = preferredAgent(from: availableAgents) ?? ""
        }

        if newCompanyAgentCli.isEmpty || !cliAgents.contains(newCompanyAgentCli) {
            newCompanyAgentCli = preferredAgent(from: cliAgents) ?? preferredAgent(from: availableAgents) ?? ""
        }

        let validSelection = Set(agentSelection.filter { availableAgents.contains($0) })
        agentSelection = validSelection.isEmpty ? [workflowLeadAgent] : validSelection
        agentSelection.insert(workflowLeadAgent)
    }

    private func preferredAgent(from agents: [String]) -> String? {
        let normalized = agents.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }
        if let codex = normalized.first(where: { $0.caseInsensitiveCompare("codex") == .orderedSame }) {
            return codex
        }
        return normalized.first
    }

    private func syncBackendFormState() {
        defaultBackendKind = dashboard.settings.backendSettings.defaultBackendKind
        codePublishMode = dashboard.settings.backendSettings.codePublishMode
        if let config = dashboard.settings.backendSettings.backends.first(where: { $0.kind == "CODEX_APP_SERVER" }) {
            codexLaunchMode = config.launchMode
            codexCommand = config.command
            codexArgs = config.args.joined(separator: " ")
            codexWorkingDirectory = config.workingDirectory ?? ""
            codexPort = config.port.map(String.init) ?? ""
            codexStartupTimeoutSeconds = String(config.startupTimeoutSeconds)
            codexAppServerBaseURL = config.baseUrl ?? ""
        } else {
            codexLaunchMode = "MANAGED"
            codexCommand = "codex"
            codexArgs = "app-server --host 127.0.0.1 --port {port}"
            codexWorkingDirectory = ""
            codexPort = ""
            codexStartupTimeoutSeconds = "15"
            codexAppServerBaseURL = ""
        }
        codexBackendStatus = dashboard.backendStatuses.first(where: { $0.kind == "CODEX_APP_SERVER" })
        syncSelectedCompanyLinearFormState()
    }

    private func syncSelectedCompanyLinearFormState() {
        guard let company = selectedCompany else {
            companyLinearSyncEnabled = false
            companyLinearEndpoint = dashboard.settings.linearSettings?.defaultConfig.endpoint ?? ""
            companyLinearTeamID = ""
            companyLinearProjectID = ""
            companyLinearStatusMessage = nil
            return
        }

        let config = company.linearConfigOverride ?? dashboard.settings.linearSettings?.defaultConfig
        companyLinearSyncEnabled = company.linearSyncEnabled ?? false
        companyLinearEndpoint = config?.endpoint ?? ""
        companyLinearTeamID = config?.teamId ?? ""
        companyLinearProjectID = config?.projectId ?? ""
        companyLinearStatusMessage = latestCompanyLinearActivityMessage(companyId: company.id)
    }

    private func latestCompanyLinearActivityMessage(companyId: String) -> String? {
        activity
            .first(where: { $0.companyId == companyId && ($0.source == "linear-sync" || $0.source == "linear") })
            .flatMap { item in
                [item.title, item.detail].compactMap { value in
                    guard let value, !value.isEmpty else { return nil }
                    return value
                }.joined(separator: " · ")
            }
    }

    func saveSelectedCompanyLinearSettings() async {
        guard let company = selectedCompany else { return }
        do {
            _ = try await api.updateCompanyLinear(
                companyId: company.id,
                enabled: companyLinearSyncEnabled,
                endpoint: trimmedOptional(companyLinearEndpoint),
                apiToken: nil,
                teamId: trimmedOptional(companyLinearTeamID),
                projectId: trimmedOptional(companyLinearProjectID),
                useGlobalDefault: false
            )
            await refreshDashboard()
            companyLinearStatusMessage = language("Saved company Linear mirror settings.", "회사 Linear 미러 설정을 저장했습니다.")
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func resyncSelectedCompanyLinear() async {
        guard let company = selectedCompany else { return }
        do {
            let result = try await api.resyncCompanyLinear(companyId: company.id)
            companyLinearStatusMessage = result.message
            await refreshDashboard()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    /// Refresh the right-hand inspector panels for the currently selected task and agent.
    func refreshTaskDetails() async {
        guard let task = selectedTask else {
            runs = []
            changes = emptyChangeSummary()
            files = []
            ports = []
            browserURL = nil
            return
        }
        let agent = selectedAgentName ?? task.agents.first
        selectedAgentName = agent
        guard let agent else { return }

        if isOffline {
            runs = []
            changes = emptyChangeSummary()
            files = []
            ports = []
            browserURL = nil
            return
        }

        do {
            let fetchedRuns: [RunRecord]
            if let issueId = selectedIssueID {
                fetchedRuns = try await api.issueRuns(issueId: issueId).sorted { lhs, rhs in
                    lhs.updatedAt > rhs.updatedAt
                }
            } else {
                fetchedRuns = try await api.runs(taskId: task.id).sorted { lhs, rhs in
                    lhs.updatedAt > rhs.updatedAt
                }
            }
            runs = fetchedRuns

            let latestForTask = fetchedRuns.filter { $0.taskId == task.id }
            let effectiveRun = latestForTask.first { $0.agentName.caseInsensitiveCompare(agent) == .orderedSame }
                ?? latestForTask.first
                ?? fetchedRuns.first { $0.agentName.caseInsensitiveCompare(agent) == .orderedSame }
                ?? fetchedRuns.first
            if let effectiveRun {
                selectedTaskID = effectiveRun.taskId
            }
            let effectiveAgent = effectiveRun?.agentName ?? agent
            selectedAgentName = effectiveAgent

            guard let effectiveRun else {
                changes = emptyChangeSummary()
                files = []
                ports = []
                browserURL = nil
                return
            }

            async let fetchedChanges = safeChanges(runId: effectiveRun.id)
            async let fetchedFiles = safeFiles(runId: effectiveRun.id)
            async let fetchedPorts = safePorts(runId: effectiveRun.id)

            changes = await fetchedChanges
            files = await fetchedFiles
            ports = await fetchedPorts
            browserURL = ports.first.flatMap { URL(string: $0.url) }
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func emptyChangeSummary() -> ChangeSummaryPayload {
        ChangeSummaryPayload(
            runId: "",
            branchName: "",
            baseBranch: selectedWorkspace?.baseBranch ?? selectedCompany?.defaultBaseBranch ?? "",
            patch: "",
            changedFiles: []
        )
    }

    private func safeChanges(runId: String) async -> ChangeSummaryPayload {
        do {
            return try await api.changes(runId: runId)
        } catch {
            return emptyChangeSummary()
        }
    }

    private func safeFiles(runId: String) async -> [FileTreeNodePayload] {
        do {
            return try await api.files(runId: runId, path: nil)
        } catch {
            return []
        }
    }

    private func safePorts(runId: String) async -> [PortEntryPayload] {
        do {
            return try await api.ports(runId: runId)
        } catch {
            return []
        }
    }

    /// Create a workspace using the repository currently focused in the sidebar.
    func createWorkspace() async {
        guard let repository = selectedRepository else { return }
        let name = newWorkspaceName.trimmingCharacters(in: .whitespacesAndNewlines)
        do {
            let created = try await api.createWorkspace(
                repositoryId: repository.id,
                name: name.isEmpty ? nil : name,
                baseBranch: pendingWorkspaceBaseBranch
            )
            newWorkspaceName = ""
            await refreshDashboard()
            selectedWorkspaceID = created.id
            pendingWorkspaceBaseBranch = created.baseBranch
            await ensureTuiSession()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    /// Create a task in the selected workspace from the current composer state.
    func createTask() async {
        guard let workspace = selectedWorkspace else { return }
        let prompt = newTaskPrompt.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !prompt.isEmpty else { return }

        let workerAgents = Array(agentSelection.subtracting([workflowLeadAgent])).sorted()
        let orderedAgents = [workflowLeadAgent].filter { !$0.isEmpty } + workerAgents

        do {
            let created = try await api.createTask(
                workspaceId: workspace.id,
                title: newTaskTitle.isEmpty ? nil : newTaskTitle,
                prompt: prompt,
                agents: orderedAgents
            )
            newTaskTitle = ""
            newTaskPrompt = ""
            await refreshDashboard()
            selectedTaskID = created.id
            selectedAgentName = created.agents.first
            await refreshTaskDetails()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func createGoal() async {
        guard let company = selectedCompany else { return }
        let title = newGoalTitle.trimmingCharacters(in: .whitespacesAndNewlines)
        let description = newGoalDescription.trimmingCharacters(in: .whitespacesAndNewlines)
        let effectiveDescription = description.isEmpty ? title : description
        guard !title.isEmpty else { return }

        do {
            actionErrorMessage = nil
            errorMessage = nil
            AppLogger.info("Saving goal '\(title)' for company \(company.id).")
            let saved: GoalRecord
            if let goalID = editingGoalID {
                saved = try await runWithEmbeddedBackendRecovery {
                    try await api.updateGoal(
                        companyId: company.id,
                        goalId: goalID,
                        title: title,
                        description: effectiveDescription
                    )
                }
            } else {
                saved = try await runWithEmbeddedBackendRecovery {
                    try await api.createGoal(companyId: company.id, title: title, description: effectiveDescription)
                }
            }
            resetGoalComposer()
            selectedGoalID = saved.id
            selectedCompanyID = company.id
            AppLogger.info("Saved goal '\(saved.title)' (\(saved.id)) for company \(company.id).")
            await performNonCriticalGoalRefresh(saved, companyID: company.id)
        } catch is CancellationError {
            actionErrorMessage = nil
            errorMessage = nil
        } catch {
            AppLogger.error("Save goal failed for company \(company.id): \(error.localizedDescription)")
            actionErrorMessage = error.localizedDescription
            errorMessage = error.localizedDescription
        }
    }

    func createIssue() async {
        guard let company = issueComposerCompany else { return }
        let title = newIssueTitle.trimmingCharacters(in: .whitespacesAndNewlines)
        let description = newIssueDescription.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !title.isEmpty, !description.isEmpty, let goalID = newIssueGoalID else { return }

        do {
            actionErrorMessage = nil
            errorMessage = nil
            AppLogger.info("Creating issue '\(title)' for company \(company.id), goal \(goalID).")
            let saved = try await runWithEmbeddedBackendRecovery {
                try await api.createIssue(
                    companyId: company.id,
                    goalId: goalID,
                    title: title,
                    description: description
                )
            }
            selectedCompanyID = saved.companyId
            selectedGoalID = saved.goalId
            selectedIssueID = saved.id
            resetIssueComposer()
            AppLogger.info("Created issue '\(saved.title)' (\(saved.id)) for company \(saved.companyId).")
            await performNonCriticalIssueRefresh(saved)
        } catch is CancellationError {
            actionErrorMessage = nil
            errorMessage = nil
        } catch {
            AppLogger.error("Create issue failed for company \(company.id): \(error.localizedDescription)")
            actionErrorMessage = error.localizedDescription
            errorMessage = error.localizedDescription
        }
    }


    private func performNonCriticalCompanyRefresh(selecting company: CompanyRecord) async {
        await refreshDashboard()
        selectedCompanyID = company.id
        if let repository = repositories.first(where: { $0.id == company.repositoryId }) {
            await selectRepository(repository)
        }
    }

    private func performNonCriticalGoalRefresh(_ goal: GoalRecord, companyID: String) async {
        await refreshDashboard()
        selectedCompanyID = companyID
        selectedGoalID = goal.id
        selectedIssueID = dashboard.issues
            .filter { $0.goalId == goal.id }
            .sorted { $0.updatedAt > $1.updatedAt }
            .first?.id
        await refreshTaskDetails()
        await ensureTuiSession()
    }

    private func performNonCriticalIssueRefresh(_ issue: IssueRecord) async {
        await refreshDashboard()
        selectedCompanyID = issue.companyId
        selectedGoalID = issue.goalId
        selectedIssueID = issue.id
        await refreshTaskDetails()
    }

    func beginEditingGoal(_ goal: GoalRecord) {
        editingGoalID = goal.id
        newGoalTitle = goal.title
        newGoalDescription = goal.description
    }

    func cancelGoalEditing() {
        resetGoalComposer()
    }

    func deleteSelectedGoal() async {
        guard let company = selectedCompany, let goal = selectedGoal else { return }
        do {
            _ = try await api.deleteGoal(companyId: company.id, goalId: goal.id)
            if editingGoalID == goal.id {
                resetGoalComposer()
            }
            selectedGoalID = nil
            selectedIssueID = nil
            selectedTaskID = nil
            await refreshDashboard()
            await refreshTaskDetails()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func createCompany() async {
        let name = newCompanyName.trimmingCharacters(in: .whitespacesAndNewlines)
        let rootPath = newCompanyRootPath.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty, !rootPath.isEmpty else { return }
        do {
            actionErrorMessage = nil
            errorMessage = nil
            AppLogger.info("Creating company '\(name)' with rootPath '\(rootPath)'.")
            let company = try await runWithEmbeddedBackendRecovery {
                try await api.createCompany(name: name, rootPath: rootPath, defaultBaseBranch: pendingWorkspaceBaseBranch)
            }
            newCompanyName = ""
            newCompanyRootPath = ""
            selectedCompanyID = company.id
            AppLogger.info("Created company '\(company.name)' (\(company.id)).")
            await performNonCriticalCompanyRefresh(selecting: company)
        } catch is CancellationError {
            actionErrorMessage = nil
            errorMessage = nil
        } catch {
            actionErrorMessage = error.localizedDescription
            AppLogger.error("Create company failed for '\(name)': \(error.localizedDescription)")
            errorMessage = error.localizedDescription
        }
    }

    func deleteSelectedCompany() async {
        guard let company = selectedCompany else { return }
        do {
            _ = try await api.deleteCompany(companyId: company.id)
            if editingCompanyAgentID != nil {
                resetCompanyAgentComposer()
            }
            if editingGoalID != nil {
                resetGoalComposer()
            }
            selectedCompanyID = nil
            selectedGoalID = nil
            selectedIssueID = nil
            selectedTaskID = nil
            selectedWorkspaceID = nil
            await refreshDashboard()
            if let company = companies.first {
                await selectCompany(company)
            } else if let repository = repositories.first {
                await selectRepository(repository)
            }
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func createCompanyAgent() async {
        let targetCompanyID = editingCompanyAgentCompanyID ?? selectedCompanyID
        guard let targetCompanyID,
              companies.contains(where: { $0.id == targetCompanyID }) else { return }
        let title = newCompanyAgentTitle.trimmingCharacters(in: .whitespacesAndNewlines)
        let cli = newCompanyAgentCli.trimmingCharacters(in: .whitespacesAndNewlines)
        let role = newCompanyAgentRole.trimmingCharacters(in: .whitespacesAndNewlines)
        let specialties = splitAgentMeta(newCompanyAgentSpecialties)
        let collaborationNotes = trimmedOptional(newCompanyAgentCollaborationNotes)
        let memoryNotes = trimmedOptional(newCompanyAgentMemoryNotes)
        let preferredCollaboratorIds = Array(newCompanyAgentPreferredCollaboratorIDs).sorted()
        guard !title.isEmpty, !cli.isEmpty, !role.isEmpty else { return }
        do {
            actionErrorMessage = nil
            errorMessage = nil
            if let agentId = editingCompanyAgentID,
               companyAgentDefinitions.contains(where: { $0.id == agentId && $0.companyId == targetCompanyID }) {
                _ = try await runWithEmbeddedBackendRecovery {
                    try await api.updateCompanyAgent(
                        companyId: targetCompanyID,
                        agentId: agentId,
                        title: title,
                        agentCli: cli,
                        roleSummary: role,
                        specialties: specialties,
                        collaborationInstructions: collaborationNotes,
                        preferredCollaboratorIds: preferredCollaboratorIds,
                        memoryNotes: memoryNotes,
                        enabled: newCompanyAgentEnabled
                    )
                }
            } else {
                _ = try await runWithEmbeddedBackendRecovery {
                    try await api.createCompanyAgent(
                        companyId: targetCompanyID,
                        title: title,
                        agentCli: cli,
                        roleSummary: role,
                        specialties: specialties,
                        collaborationInstructions: collaborationNotes,
                        preferredCollaboratorIds: preferredCollaboratorIds,
                        memoryNotes: memoryNotes,
                        enabled: newCompanyAgentEnabled
                    )
                }
            }
            resetCompanyAgentComposer()
            await refreshDashboard()
        } catch {
            actionErrorMessage = error.localizedDescription
            errorMessage = error.localizedDescription
        }
    }

    func beginEditingCompanyAgent(_ agent: CompanyAgentDefinitionRecord) {
        editingCompanyAgentID = agent.id
        editingCompanyAgentCompanyID = agent.companyId
        selectedCompanyID = agent.companyId
        newCompanyAgentTitle = agent.title
        newCompanyAgentCli = agent.agentCli
        newCompanyAgentRole = agent.roleSummary
        newCompanyAgentSpecialties = agent.specialties.joined(separator: ", ")
        newCompanyAgentCollaborationNotes = agent.collaborationInstructions ?? ""
        newCompanyAgentMemoryNotes = agent.memoryNotes ?? ""
        newCompanyAgentPreferredCollaboratorIDs = Set(agent.preferredCollaboratorIds)
        newCompanyAgentEnabled = agent.enabled
    }

    func cancelEditingCompanyAgent() {
        resetCompanyAgentComposer()
    }

    func startSelectedCompanyRuntime() async {
        guard let company = selectedCompany else { return }
        do {
            _ = try await api.startCompanyRuntime(companyId: company.id)
            await refreshDashboard()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func stopSelectedCompanyRuntime() async {
        guard let company = selectedCompany else { return }
        do {
            _ = try await api.stopCompanyRuntime(companyId: company.id)
            await refreshDashboard()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func deleteSelectedIssue() async {
        guard let company = selectedCompany, let issue = selectedIssue else { return }
        do {
            _ = try await api.deleteIssue(companyId: company.id, issueId: issue.id)
            selectedIssueID = nil
            selectedTaskID = nil
            await refreshDashboard()
            await refreshTaskDetails()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func updateSelectedWorkspaceBaseBranch() async {
        guard let workspace = selectedWorkspace else { return }
        do {
            let updated = try await api.updateWorkspaceBaseBranch(workspaceId: workspace.id, baseBranch: pendingWorkspaceBaseBranch)
            await refreshDashboard()
            selectedWorkspaceID = updated.id
            pendingWorkspaceBaseBranch = updated.baseBranch
            await ensureTuiSession(forceRestart: true)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    /// Start execution for the task that is currently selected in the center pane.
    func runSelectedTask() async {
        if let issue = selectedIssue {
            do {
                _ = try await api.runIssue(issueId: issue.id)
                statusState = .taskStarted(issue.title)
                objectWillChange.send()
                await refreshDashboard()
                await refreshTaskDetails()
                await ensureTuiSession()
            } catch {
                errorMessage = error.localizedDescription
            }
            return
        }
        guard let task = selectedTask else { return }
        do {
            _ = try await api.runTask(taskId: task.id)
            statusState = .taskStarted(task.title)
            objectWillChange.send()
            // A fresh dashboard reload is cheap and ensures task/run status comes back
            // from the source of truth instead of from optimistic local mutation.
            await refreshDashboard()
            await refreshTaskDetails()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    /// Present the native macOS folder picker and register the chosen repository.
    func openRepositoryPicker() async {
        let panel = NSOpenPanel()
        panel.canChooseDirectories = true
        panel.canChooseFiles = false
        panel.allowsMultipleSelection = false
        panel.prompt = text(.openRepositoryPrompt)
        if panel.runModal() == .OK, let url = panel.url {
            repositoryPathInput = url.path
            await submitOpenRepository()
        }
    }

    /// Present a native macOS folder picker for the company root path field.
    func openCompanyRootPicker() {
        let panel = NSOpenPanel()
        panel.canChooseDirectories = true
        panel.canChooseFiles = false
        panel.allowsMultipleSelection = false
        panel.prompt = language("Choose Folder", "폴더 선택")
        if panel.runModal() == .OK, let url = panel.url {
            newCompanyRootPath = url.path
        }
    }

    private func resetCompanyAgentComposer() {
        editingCompanyAgentID = nil
        editingCompanyAgentCompanyID = nil
        newCompanyAgentTitle = ""
        newCompanyAgentCli = preferredCliAgent
        newCompanyAgentRole = ""
        newCompanyAgentSpecialties = ""
        newCompanyAgentCollaborationNotes = ""
        newCompanyAgentMemoryNotes = ""
        newCompanyAgentPreferredCollaboratorIDs = []
        newCompanyAgentEnabled = true
    }

    private func splitAgentMeta(_ raw: String) -> [String] {
        raw
            .split(separator: ",")
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
    }

    private func trimmedOptional(_ raw: String) -> String? {
        let value = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        return value.isEmpty ? nil : value
    }

    private func runWithEmbeddedBackendRecovery<T>(_ action: () async throws -> T) async throws -> T {
        do {
            return try await action()
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            guard shouldAttemptEmbeddedBackendRecovery(for: error) else {
                AppLogger.error("Desktop action failed without backend recovery: \(error.localizedDescription)")
                throw error
            }
            AppLogger.info("Attempting embedded backend recovery after error: \(error.localizedDescription)")
            statusState = .waitingForServer
            await EmbeddedBackendLauncher.shared.ensureRunning()
            do {
                return try await action()
            } catch {
                AppLogger.error("Desktop action failed after backend recovery retry: \(error.localizedDescription)")
                throw error
            }
        }
    }

    private func shouldAttemptEmbeddedBackendRecovery(for error: Error) -> Bool {
        if error is CancellationError {
            return false
        }
        let nsError = error as NSError
        if nsError.domain == NSURLErrorDomain {
            switch nsError.code {
            case NSURLErrorCannotConnectToHost,
                 NSURLErrorCannotFindHost,
                 NSURLErrorNetworkConnectionLost,
                 NSURLErrorNotConnectedToInternet,
                 NSURLErrorTimedOut:
                return true
            default:
                break
            }
        }
        let message = error.localizedDescription.lowercased()
        return message.contains("network connection was lost")
            || message.contains("couldn't connect to server")
            || message.contains("cannot connect to host")
            || message.contains("connection refused")
    }

    private func resetGoalComposer() {
        editingGoalID = nil
        newGoalTitle = ""
        newGoalDescription = ""
    }

    private func resetIssueComposer() {
        newIssueCompanyID = selectedCompanyID ?? companies.first?.id
        newIssueGoalID = issueComposerGoals.first?.id ?? selectedGoalID
        newIssueTitle = ""
        newIssueDescription = ""
    }

    /// Submit the path collected from the picker or from a future manual input flow.
    func submitOpenRepository() async {
        let path = repositoryPathInput.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !path.isEmpty else { return }
        do {
            let repo = try await api.openRepository(path: path)
            repositoryPathInput = ""
            await refreshDashboard()
            if let refreshed = repositories.first(where: { $0.id == repo.id }) {
                await selectRepository(refreshed)
            }
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    /// Clone the repository URL from the clone sheet into the managed repository area.
    func submitCloneRepository() async {
        let url = cloneURLInput.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !url.isEmpty else { return }
        do {
            let repo = try await api.cloneRepository(url: url)
            cloneURLInput = ""
            await refreshDashboard()
            if let refreshed = repositories.first(where: { $0.id == repo.id }) {
                await selectRepository(refreshed)
            }
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    /// Refresh the branch picker options for the selected repository.
    func refreshAvailableBranches() async {
        guard let repository = selectedRepository else {
            availableBranches = []
            return
        }

        if isOffline {
            availableBranches = [selectedWorkspace?.baseBranch ?? selectedCompany?.defaultBaseBranch ?? repository.defaultBranch]
            pendingWorkspaceBaseBranch = selectedWorkspace?.baseBranch ?? selectedCompany?.defaultBaseBranch ?? repository.defaultBranch
            return
        }

        do {
            let branches = try await api.repositoryBranches(repositoryId: repository.id)
            availableBranches = branches.isEmpty ? [repository.defaultBranch] : branches
            if selectedWorkspace == nil, !availableBranches.contains(pendingWorkspaceBaseBranch) {
                pendingWorkspaceBaseBranch = selectedCompany?.defaultBaseBranch ?? repository.defaultBranch
            }
        } catch {
            availableBranches = [repository.defaultBranch]
            pendingWorkspaceBaseBranch = selectedWorkspace?.baseBranch ?? selectedCompany?.defaultBaseBranch ?? repository.defaultBranch
            errorMessage = error.localizedDescription
        }
    }

    /// Update every dependent selection when the repository changes.
    func selectRepository(_ repository: RepositoryRecord) async {
        selectedRepositoryID = repository.id
        if let company = companies.first(where: { $0.repositoryId == repository.id }) {
            selectedCompanyID = company.id
        }
        // Selection cascades from repository -> workspace -> task so every pane stays aligned.
        selectedWorkspaceID = workspaces.first?.id
        selectedGoalID = goals.first?.id
        selectedIssueID = issues.first?.id
        selectedTaskID = tasks.first?.id
        selectedAgentName = selectedTask?.agents.first
        pendingWorkspaceBaseBranch = selectedWorkspace?.baseBranch ?? selectedCompany?.defaultBaseBranch ?? repository.defaultBranch
        await refreshAvailableBranches()
        await refreshTaskDetails()
        await ensureTuiSession()
    }

    /// Switch to a new workspace and reload the task/inspector state derived from it.
    func selectWorkspace(_ workspace: WorkspaceRecord) async {
        selectedWorkspaceID = workspace.id
        if let company = companies.first(where: { $0.repositoryId == workspace.repositoryId }) {
            selectedCompanyID = company.id
        }
        selectedIssueID = dashboard.issues.first(where: { $0.workspaceId == workspace.id && (selectedGoalID == nil || $0.goalId == selectedGoalID) })?.id
        selectedTaskID = tasks.first?.id
        selectedAgentName = selectedTask?.agents.first
        pendingWorkspaceBaseBranch = workspace.baseBranch
        await refreshTaskDetails()
        await ensureTuiSession()
    }

    func selectCompany(_ company: CompanyRecord) async {
        selectedCompanyID = company.id
        newIssueCompanyID = company.id
        selectedRepositoryID = company.repositoryId
        resetCompanyAgentComposer()
        selectedWorkspaceID = dashboard.workspaces.first(where: { $0.repositoryId == company.repositoryId && $0.baseBranch == company.defaultBaseBranch })?.id
        selectedGoalID = goals.first?.id
        selectedIssueID = issues.first?.id
        selectedTaskID = selectedTask?.id
        pendingWorkspaceBaseBranch = selectedWorkspace?.baseBranch ?? company.defaultBaseBranch
        await refreshAvailableBranches()
        await refreshTaskDetails()
        syncIssueComposerState()
        syncSelectedCompanyLinearFormState()
        await restartCompanyEventStream()
    }

    func selectGoal(_ goal: GoalRecord) async {
        selectedCompanyID = goal.companyId
        newIssueCompanyID = goal.companyId
        selectedGoalID = goal.id
        newIssueGoalID = goal.id
        selectedIssueID = issues.first?.id
        if let issue = selectedIssue {
            selectedWorkspaceID = issue.workspaceId
        }
        selectedTaskID = selectedTask?.id
        selectedAgentName = selectedTask?.agents.first
        await refreshTaskDetails()
        await ensureTuiSession()
    }

    func selectIssue(_ issue: IssueRecord) async {
        selectedCompanyID = issue.companyId
        selectedGoalID = issue.goalId
        selectedIssueID = issue.id
        selectedWorkspaceID = issue.workspaceId
        selectedTaskID = dashboard.tasks
            .filter { $0.issueId == issue.id }
            .sorted { lhs, rhs in lhs.updatedAt > rhs.updatedAt }
            .first?.id
        selectedAgentName = selectedTask?.agents.first
        await refreshTaskDetails()
        await ensureTuiSession()
    }

    /// Focus a task row and refresh the right-hand inspector for its default agent.
    func selectTask(_ task: TaskRecord) async {
        selectedTaskID = task.id
        selectedAgentName = task.agents.first
        await refreshTaskDetails()
    }

    /// Switch the inspector to a different agent run within the same task.
    func selectAgent(_ name: String) async {
        selectedAgentName = name
        await refreshTaskDetails()
    }

    /// Open Finder at the most relevant location for the current selection.
    func openSelectedLocation() {
        let path = selectedRun?.worktreePath ?? selectedRepository?.localPath
        guard let path else { return }
        NSWorkspace.shared.open(URL(fileURLWithPath: path))
    }

    /// Promote a discovered local port into the embedded browser tab.
    func openPort(_ port: PortEntryPayload) {
        browserURL = URL(string: port.url)
        inspectorTab = .browser
    }

    func saveBackendSettings() async {
        do {
            backendStatusMessage = nil
            let settings = try await api.updateBackendSettings(
                defaultBackendKind: defaultBackendKind,
                codePublishMode: codePublishMode,
                codexLaunchMode: codexLaunchMode,
                codexCommand: trimmedOptional(codexCommand),
                codexArgs: codexArgs
                    .split(whereSeparator: \.isWhitespace)
                    .map(String.init),
                codexWorkingDirectory: trimmedOptional(codexWorkingDirectory),
                codexPort: Int(trimmedOptional(codexPort) ?? ""),
                codexStartupTimeoutSeconds: Int(trimmedOptional(codexStartupTimeoutSeconds) ?? ""),
                codexAppServerBaseURL: trimmedOptional(codexAppServerBaseURL),
                codexAuthMode: nil,
                codexToken: nil,
                codexTimeoutSeconds: nil
            )
            dashboard = DashboardPayload(
                repositories: dashboard.repositories,
                workspaces: dashboard.workspaces,
                tasks: dashboard.tasks,
                settings: settings,
                companies: dashboard.companies,
                companyAgentDefinitions: dashboard.companyAgentDefinitions,
                projectContexts: dashboard.projectContexts,
                goals: dashboard.goals,
                issues: dashboard.issues,
                reviewQueue: dashboard.reviewQueue,
                orgProfiles: dashboard.orgProfiles,
                workflowTopologies: dashboard.workflowTopologies,
                goalDecisions: dashboard.goalDecisions,
                runningAgentSessions: dashboard.runningAgentSessions,
                backendStatuses: settings.backendStatuses,
                opsMetrics: dashboard.opsMetrics,
                activity: dashboard.activity,
                companyRuntimes: dashboard.companyRuntimes
            )
            syncBackendFormState()
        } catch {
            backendStatusMessage = error.localizedDescription
        }
    }

    func testCodexBackendConnection() async {
        do {
            backendStatusMessage = nil
            codexBackendStatus = try await api.testBackend(
                kind: "CODEX_APP_SERVER",
                launchMode: codexLaunchMode,
                command: trimmedOptional(codexCommand),
                args: codexArgs.split(whereSeparator: \.isWhitespace).map(String.init),
                workingDirectory: trimmedOptional(codexWorkingDirectory),
                port: Int(trimmedOptional(codexPort) ?? ""),
                startupTimeoutSeconds: Int(trimmedOptional(codexStartupTimeoutSeconds) ?? ""),
                baseURL: trimmedOptional(codexAppServerBaseURL),
                authMode: nil,
                token: nil,
                timeoutSeconds: nil
            )
        } catch {
            backendStatusMessage = error.localizedDescription
        }
    }

    func updateSelectedCompanyBackend(kind: String) async {
        guard let company = selectedCompany else { return }
        do {
            backendStatusMessage = nil
            _ = try await api.updateCompanyBackend(
                companyId: company.id,
                backendKind: kind,
                launchMode: codexLaunchMode,
                command: trimmedOptional(codexCommand),
                args: codexArgs.split(whereSeparator: \.isWhitespace).map(String.init),
                workingDirectory: trimmedOptional(codexWorkingDirectory),
                port: Int(trimmedOptional(codexPort) ?? ""),
                startupTimeoutSeconds: Int(trimmedOptional(codexStartupTimeoutSeconds) ?? ""),
                baseURL: trimmedOptional(codexAppServerBaseURL),
                authMode: nil,
                token: nil,
                timeoutSeconds: nil,
                useGlobalDefault: false
            )
            await refreshDashboard()
        } catch {
            backendStatusMessage = error.localizedDescription
        }
    }

    private func restartCompanyEventStream() async {
        // Company events are treated as an eventually consistent acceleration path. If the stream
        // includes an embedded dashboard snapshot we can patch state immediately; otherwise we fall
        // back to a full refresh to keep the native store aligned with the backend contract.
        companyEventTask?.cancel()
        guard !isOffline, shellMode == .company, let companyID = selectedCompanyID else { return }
        companyEventTask = Task { [weak self] in
            guard let self else { return }
            do {
                for try await envelope in api.companyEvents(companyId: companyID) {
                    await MainActor.run {
                        self.companyStreamStatusMessage = nil
                        if let dashboard = envelope.dashboard {
                            self.dashboard = dashboard
                            self.reconcileWorkflowLeadAgent()
                            self.reconcileSelection()
                            self.syncBackendFormState()
                        }
                    }
                    if envelope.dashboard == nil {
                        await self.refreshDashboard(restartEventStream: false)
                    }
                }
            } catch is CancellationError {
            } catch {
                let shouldRetry = await MainActor.run { () -> Bool in
                    guard self.selectedCompanyID == companyID, self.shellMode == .company else { return false }
                    if self.isBenignCompanyEventError(error) {
                        self.companyStreamStatusMessage = self.language(
                            "Reconnecting to company events…",
                            "회사 이벤트를 다시 연결하는 중…"
                        )
                        return true
                    }
                    self.companyStreamStatusMessage = error.localizedDescription
                    return true
                }
                guard shouldRetry else { return }
                try? await Task.sleep(for: .seconds(1))
                guard !Task.isCancelled else { return }
                await self.restartCompanyEventStream()
            }
        }
    }

    private func startCompanyStatePolling() {
        guard companyPollingTask == nil else { return }
        companyPollingTask = Task { [weak self] in
            guard let self else { return }
            while !Task.isCancelled {
                let pollState = await MainActor.run { () -> (shouldRefresh: Bool, isOffline: Bool) in
                    (
                        shouldRefresh: self.shellMode == .company && self.selectedCompanyID != nil && !self.isBusy,
                        isOffline: self.isOffline
                    )
                }
                if pollState.shouldRefresh && pollState.isOffline {
                    await EmbeddedBackendLauncher.shared.ensureRunning()
                }
                if pollState.shouldRefresh {
                    await self.refreshDashboard(restartEventStream: false)
                }
                try? await Task.sleep(for: .seconds(3))
            }
        }
    }

    private func startEmbeddedBackendWatchdog() {
        guard backendWatchdogTask == nil else { return }
        backendWatchdogTask = Task {
            while !Task.isCancelled {
                await EmbeddedBackendLauncher.shared.ensureRunning()
                try? await Task.sleep(for: .seconds(5))
            }
        }
    }

    private func isBenignCompanyEventError(_ error: Error) -> Bool {
        if error is CancellationError {
            return true
        }
        if let urlError = error as? URLError {
            switch urlError.code {
            case .cancelled, .networkConnectionLost, .timedOut, .cannotConnectToHost, .cannotFindHost, .notConnectedToInternet:
                return true
            default:
                return false
            }
        }
        return false
    }

    /// The center pane should always show the real interactive TUI for the
    /// selected workspace, so the store eagerly opens or reuses that session.
    func ensureTuiSession(forceRestart: Bool = false) async {
        if shellMode != .tui && !forceRestart {
            return
        }
        guard let workspace = selectedWorkspace else {
            stopTuiPolling()
            tuiSession = nil
            return
        }

        if isOffline {
            stopTuiPolling()
            tuiSession = nil
            return
        }

        do {
            let preferredAgent = workflowLeadAgent.isEmpty ? selectedAgentName : workflowLeadAgent
            if forceRestart, let session = tuiSession {
                _ = try? await api.terminateTuiSession(sessionId: session.id)
            }
            let session = try await api.openTuiSession(workspaceId: workspace.id, preferredAgent: preferredAgent)
            tuiSession = session
            errorMessage = nil
            startTuiPolling(sessionID: session.id, workspaceID: workspace.id)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    /// Stop the current TUI loop so the user can launch a fresh interactive shell.
    func restartTuiSession() async {
        if let session = tuiSession {
            _ = try? await api.terminateTuiSession(sessionId: session.id)
        }
        await ensureTuiSession()
    }

    private func startTuiPolling(sessionID: String, workspaceID: String) {
        guard polledTuiSessionID != sessionID else { return }

        stopTuiPolling()
        polledTuiSessionID = sessionID
        tuiPollingTask = Task { [weak self] in
            guard let self else { return }

            while !Task.isCancelled {
                do {
                    let latest = try await api.tuiSession(sessionId: sessionID)
                    if self.selectedWorkspaceID == workspaceID {
                        self.tuiSession = latest
                    }

                    if latest.status == "EXITED" || latest.status == "FAILED" {
                        break
                    }
                } catch {
                    if await self.recoverFromStaleTuiSession(error, sessionID: sessionID, workspaceID: workspaceID) {
                        break
                    }
                    self.errorMessage = error.localizedDescription
                    break
                }

                try? await Task.sleep(for: .milliseconds(800))
            }
        }
    }

    private func stopTuiPolling() {
        tuiPollingTask?.cancel()
        tuiPollingTask = nil
        polledTuiSessionID = nil
    }

    /// Backend restarts invalidate the in-memory PTY session table, so an older
    /// embedded terminal can briefly point at a session id that no longer exists.
    /// Treat that as recoverable and open a fresh workspace session instead of
    /// surfacing a sticky HTTP 404/500 alert to the user.
    private func recoverFromStaleTuiSession(_ error: Error, sessionID: String, workspaceID: String) async -> Bool {
        guard selectedWorkspaceID == workspaceID else { return false }
        guard isRecoverableTuiSessionError(error) else { return false }
        guard tuiSession?.id == sessionID || polledTuiSessionID == sessionID else { return false }

        stopTuiPolling()
        tuiSession = nil
        errorMessage = nil
        await ensureTuiSession()
        return true
    }

    private func isRecoverableTuiSessionError(_ error: Error) -> Bool {
        guard let apiError = error as? APIError else { return false }
        if apiError.statusCode == 404 {
            return true
        }

        // Older backend builds returned a blank 500 for missing TUI sessions.
        // Keep the client tolerant until every packaged app is on the structured
        // status-page response path.
        if apiError.statusCode == 500 {
            let body = apiError.responseBody.trimmingCharacters(in: .whitespacesAndNewlines)
            return body.isEmpty || body == "Unknown server error"
        }

        return false
    }
}
