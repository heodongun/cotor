import AppKit
import Foundation
import SwiftUI

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

/// Main view model for the macOS shell.
///
/// It coordinates bootstrap, selection state, optimistic actions, runtime
/// language choice, and the offline fallback model that keeps the UI explorable
/// even when the backend is unavailable.
@MainActor
final class DesktopStore: ObservableObject {
    private static let languageDefaultsKey = "cotor.desktop.language"

    @Published var dashboard: DashboardPayload = MockSeed.dashboard
    @Published var runs: [RunRecord] = MockSeed.runs
    @Published var tuiSession: TuiSessionRecord?
    @Published var availableBranches: [String] = ["master"]
    @Published var selectedBaseBranch = "master"
    @Published var selectedRepositoryID: String?
    @Published var selectedWorkspaceID: String?
    @Published var selectedTaskID: String?
    @Published var selectedAgentName: String?
    @Published var inspectorTab: InspectorTab = .changes
    @Published var changes: ChangeSummaryPayload = MockSeed.changes
    @Published var files: [FileTreeNodePayload] = MockSeed.files
    @Published var ports: [PortEntryPayload] = MockSeed.ports
    @Published var browserURL: URL?
    @Published var language: AppLanguage
    @Published var isOffline = false
    @Published var isBusy = false
    @Published var repositoryPathInput = ""
    @Published var cloneURLInput = ""
    @Published var newWorkspaceName = ""
    @Published var newTaskTitle = ""
    @Published var newTaskPrompt = ""
    @Published var agentSelection: Set<String> = ["claude", "codex"]
    @Published var workflowLeadAgent: String
    @Published var showingOpenSheet = false
    @Published var showingCloneSheet = false
    @Published var errorMessage: String?

    let api = DesktopAPI()
    private var statusState: StatusState = .connecting
    private var tuiPollingTask: Task<Void, Never>?
    private var polledTuiSessionID: String?

    init() {
        let storedLanguage = UserDefaults.standard.string(forKey: Self.languageDefaultsKey)
        language = AppLanguage(rawValue: storedLanguage ?? "") ?? .english
        workflowLeadAgent = MockSeed.dashboard.settings.availableAgents.first ?? "claude"
    }

    deinit {
        tuiPollingTask?.cancel()
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

    var workspaces: [WorkspaceRecord] {
        dashboard.workspaces
            .filter { selectedRepositoryID == nil || $0.repositoryId == selectedRepositoryID }
            .sorted { lhs, rhs in
                lhs.updatedAt > rhs.updatedAt
            }
    }

    var tasks: [TaskRecord] {
        dashboard.tasks
            .filter { selectedWorkspaceID == nil || $0.workspaceId == selectedWorkspaceID }
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

    var selectedTask: TaskRecord? {
        dashboard.tasks.first { $0.id == selectedTaskID }
    }

    var selectedRun: RunRecord? {
        runs.first {
            $0.taskId == selectedTaskID &&
            $0.agentName == (selectedAgentName ?? selectedTask?.agents.first)
        }
    }

    func text(_ key: DesktopTextKey) -> String {
        DesktopStrings.text(key, language: language)
    }

    func setLanguage(_ language: AppLanguage) {
        self.language = language
        UserDefaults.standard.set(language.rawValue, forKey: Self.languageDefaultsKey)
        objectWillChange.send()
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
                try? await Task.sleep(for: .seconds(1))
            }
        }
    }

    /// Reload the top-level dashboard payload and preserve/repair selection state.
    func refreshDashboard() async {
        isBusy = true
        defer { isBusy = false }

        do {
            let fresh = try await api.dashboard()
            dashboard = fresh
            errorMessage = nil
            isOffline = false
            statusState = .connected(api.baseURL.absoluteString)
            reconcileWorkflowLeadAgent()
            reconcileSelection()
            selectedBaseBranch = selectedWorkspace?.baseBranch ?? selectedRepository?.defaultBranch ?? "master"
            await refreshAvailableBranches()
            await refreshTaskDetails()
            await ensureTuiSession()
        } catch {
            // The desktop shell intentionally stays usable offline with mock data so
            // UI development and demos do not hard fail when the local server is down.
            isOffline = true
            statusState = .offlineMock
            reconcileWorkflowLeadAgent()
            reconcileSelection()
            selectedAgentName = selectedTask?.agents.first
            selectedBaseBranch = selectedWorkspace?.baseBranch ?? selectedRepository?.defaultBranch ?? "master"
            await refreshAvailableBranches()
            stopTuiPolling()
            tuiSession = MockSeed.tuiSession
        }
    }

    /// Repair selection state after a dashboard refresh so every pane still points
    /// at records that exist in the freshly returned payload.
    private func reconcileSelection() {
        if !repositories.contains(where: { $0.id == selectedRepositoryID }) {
            selectedRepositoryID = repositories.first?.id
        }
        if !workspaces.contains(where: { $0.id == selectedWorkspaceID }) {
            selectedWorkspaceID = workspaces.first?.id
        }
        if !tasks.contains(where: { $0.id == selectedTaskID }) {
            selectedTaskID = tasks.first?.id
        }

        if let task = selectedTask {
            if !task.agents.contains(selectedAgentName ?? "") {
                selectedAgentName = task.agents.first
            }
        } else {
            selectedAgentName = nil
        }
    }

    /// Repair the workflow lead and selected agent roster after bootstrap or
    /// dashboard refresh. This keeps the authoring UI stable even when the
    /// backend roster changes or the app falls back to offline mock data.
    private func reconcileWorkflowLeadAgent() {
        let availableAgents = dashboard.settings.availableAgents

        if availableAgents.isEmpty {
            workflowLeadAgent = ""
            agentSelection = []
            return
        }

        if workflowLeadAgent.isEmpty || !availableAgents.contains(workflowLeadAgent) {
            workflowLeadAgent = availableAgents.first ?? ""
        }

        let validSelection = Set(agentSelection.filter { availableAgents.contains($0) })
        agentSelection = validSelection.isEmpty ? [workflowLeadAgent] : validSelection
        agentSelection.insert(workflowLeadAgent)
    }

    /// Refresh the right-hand inspector panels for the currently selected task and agent.
    func refreshTaskDetails() async {
        guard let task = selectedTask else { return }
        let agent = selectedAgentName ?? task.agents.first
        selectedAgentName = agent
        guard let agent else { return }

        if isOffline {
            // Keep every inspector panel populated in offline mode so the shell still
            // communicates the intended product structure.
            runs = MockSeed.runs
            changes = MockSeed.changes
            files = MockSeed.files
            ports = MockSeed.ports
            browserURL = URL(string: MockSeed.ports.first?.url ?? "http://127.0.0.1:8787")
            tuiSession = MockSeed.tuiSession
            return
        }

        do {
            async let fetchedRuns = api.runs(taskId: task.id)
            async let fetchedChanges = api.changes(taskId: task.id, agentName: agent)
            async let fetchedFiles = api.files(taskId: task.id, agentName: agent, path: nil)
            async let fetchedPorts = api.ports(taskId: task.id, agentName: agent)

            // Fetch these in parallel because they back separate inspector panels and
            // none of them depend on the others.
            runs = try await fetchedRuns.sorted { lhs, rhs in
                lhs.updatedAt > rhs.updatedAt
            }
            changes = try await fetchedChanges
            files = try await fetchedFiles
            ports = try await fetchedPorts
            browserURL = ports.first.flatMap { URL(string: $0.url) }
        } catch {
            errorMessage = error.localizedDescription
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
                baseBranch: selectedBaseBranch
            )
            newWorkspaceName = ""
            await refreshDashboard()
            selectedWorkspaceID = created.id
            selectedBaseBranch = created.baseBranch
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

    /// Start execution for the task that is currently selected in the center pane.
    func runSelectedTask() async {
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
            availableBranches = [selectedWorkspace?.baseBranch ?? repository.defaultBranch]
            selectedBaseBranch = selectedWorkspace?.baseBranch ?? repository.defaultBranch
            return
        }

        do {
            let branches = try await api.repositoryBranches(repositoryId: repository.id)
            availableBranches = branches.isEmpty ? [repository.defaultBranch] : branches
            if let workspace = selectedWorkspace {
                selectedBaseBranch = workspace.baseBranch
            } else if !availableBranches.contains(selectedBaseBranch) {
                selectedBaseBranch = repository.defaultBranch
            }
        } catch {
            availableBranches = [repository.defaultBranch]
            selectedBaseBranch = selectedWorkspace?.baseBranch ?? repository.defaultBranch
            errorMessage = error.localizedDescription
        }
    }

    /// Update every dependent selection when the repository changes.
    func selectRepository(_ repository: RepositoryRecord) async {
        selectedRepositoryID = repository.id
        // Selection cascades from repository -> workspace -> task so every pane stays aligned.
        selectedWorkspaceID = workspaces.first?.id
        selectedTaskID = tasks.first?.id
        selectedAgentName = selectedTask?.agents.first
        selectedBaseBranch = selectedWorkspace?.baseBranch ?? repository.defaultBranch
        await refreshAvailableBranches()
        await refreshTaskDetails()
        await ensureTuiSession()
    }

    /// Switch to a new workspace and reload the task/inspector state derived from it.
    func selectWorkspace(_ workspace: WorkspaceRecord) async {
        selectedWorkspaceID = workspace.id
        selectedTaskID = tasks.first?.id
        selectedAgentName = selectedTask?.agents.first
        selectedBaseBranch = workspace.baseBranch
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

    /// The center pane should always show the real interactive TUI for the
    /// selected workspace, so the store eagerly opens or reuses that session.
    func ensureTuiSession() async {
        guard let workspace = selectedWorkspace else {
            stopTuiPolling()
            tuiSession = nil
            return
        }

        if isOffline {
            stopTuiPolling()
            tuiSession = MockSeed.tuiSession
            return
        }

        do {
            let preferredAgent = workflowLeadAgent.isEmpty ? selectedAgentName : workflowLeadAgent
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
