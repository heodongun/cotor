import Foundation


// MARK: - File Overview
// DesktopAPI belongs to the native macOS client layer for the Cotor desktop application.
// It collects declarations centered on desktop a p i so the native shell code stays easier to navigate.
// Start with this file when tracing how the desktop client presents, stores, or moves state in this area.

/// Thin HTTP client for the localhost `cotor app-server`.
///
/// Keeping transport concerns here lets the view model stay focused on user intent
/// and state transitions instead of URLSession boilerplate.
struct DesktopAPI {
    static let embeddedAppToken = "cotor-desktop-local-token"
    let baseURL: URL
    let token: String?

    init() {
        // The desktop shell defaults to the standard localhost port but allows
        // overrides so developers can point it at a custom backend process.
        let rawURL = ProcessInfo.processInfo.environment["COTOR_APP_SERVER_URL"] ?? "http://127.0.0.1:8787"
        self.baseURL = URL(string: rawURL) ?? URL(string: "http://127.0.0.1:8787")!
        self.token = ProcessInfo.processInfo.environment["COTOR_APP_TOKEN"] ?? Self.embeddedAppToken
    }

    /// Fetch the full dashboard payload used to bootstrap most of the app state.
    func dashboard() async throws -> DashboardPayload {
        try await get(path: "api/app/dashboard")
    }

    /// Fetch the focused company snapshot used for live company-mode updates.
    func companyDashboard(companyId: String) async throws -> CompanyDashboardPayload {
        try await get(path: "api/app/companies/\(companyId)/dashboard")
    }

    func health() async throws -> Bool {
        struct HealthPayload: Decodable {
            let status: String?
            let ok: Bool?
        }
        let payload: HealthPayload = try await get(path: "api/app/health")
        if let ok = payload.ok {
            return ok
        }
        return payload.status?.lowercased() == "ok"
    }

    /// Return the selectable base branches for the currently focused repository.
    func repositoryBranches(repositoryId: String) async throws -> [String] {
        try await get(path: "api/app/repositories/\(repositoryId)/branches")
    }

    /// Expose the built-in agent roster advertised by the backend.
    func agents() async throws -> [String] {
        try await get(path: "api/app/agents")
    }

    /// Fetch all persisted runs belonging to one task.
    func runs(taskId: String) async throws -> [RunRecord] {
        try await get(path: "api/app/runs", query: [URLQueryItem(name: "taskId", value: taskId)])
    }

    /// Fetch all persisted runs belonging to one company issue.
    func issueRuns(issueId: String) async throws -> [RunRecord] {
        try await get(path: "api/app/issues/\(issueId)/runs")
    }

    func issueExecutionDetails(issueId: String) async throws -> [IssueAgentExecutionDetailRecord] {
        try await get(path: "api/app/issues/\(issueId)/execution-details")
    }

    /// Fetch the git diff summary for one task/agent pair.
    func changes(taskId: String, agentName: String) async throws -> ChangeSummaryPayload {
        try await get(path: "api/app/tasks/\(taskId)/changes/\(agentName)")
    }

    /// Fetch the git diff summary for one concrete run.
    func changes(runId: String) async throws -> ChangeSummaryPayload {
        try await get(path: "api/app/changes", query: [URLQueryItem(name: "runId", value: runId)])
    }

    /// Fetch the nested file tree rooted at one agent worktree.
    func files(taskId: String, agentName: String, path: String?) async throws -> [FileTreeNodePayload] {
        let query = path.flatMap { $0.isEmpty ? nil : URLQueryItem(name: "path", value: $0) }.map { [$0] } ?? []
        return try await get(path: "api/app/tasks/\(taskId)/files/\(agentName)", query: query)
    }

    /// Fetch the nested file tree rooted at one concrete run worktree.
    func files(runId: String, path: String?) async throws -> [FileTreeNodePayload] {
        let query = [URLQueryItem(name: "runId", value: runId)] + (path.flatMap { $0.isEmpty ? nil : URLQueryItem(name: "path", value: $0) }.map { [$0] } ?? [])
        return try await get(path: "api/app/files", query: query)
    }

    /// Fetch ports exposed by the process attached to one agent run.
    func ports(taskId: String, agentName: String) async throws -> [PortEntryPayload] {
        try await get(path: "api/app/tasks/\(taskId)/ports/\(agentName)")
    }

    /// Fetch ports exposed by one concrete run process.
    func ports(runId: String) async throws -> [PortEntryPayload] {
        try await get(path: "api/app/ports", query: [URLQueryItem(name: "runId", value: runId)])
    }

    /// Register an existing local checkout with the desktop backend.
    func openRepository(path: String) async throws -> RepositoryRecord {
        try await post(path: "api/app/repositories/open", body: ["path": path])
    }

    /// Clone a remote repository into the app-managed storage area.
    func cloneRepository(url: String) async throws -> RepositoryRecord {
        try await post(path: "api/app/repositories/clone", body: ["url": url])
    }

    /// Create a workspace pinned to a specific repository/base-branch pair.
    func createWorkspace(repositoryId: String, name: String?, baseBranch: String?) async throws -> WorkspaceRecord {
        try await post(
            path: "api/app/workspaces",
            body: CreateWorkspacePayload(repositoryId: repositoryId, name: name, baseBranch: baseBranch)
        )
    }

    func updateWorkspaceBaseBranch(workspaceId: String, baseBranch: String) async throws -> WorkspaceRecord {
        try await patch(
            path: "api/app/workspaces/\(workspaceId)/base-branch",
            body: UpdateWorkspaceBaseBranchPayload(baseBranch: baseBranch)
        )
    }

    /// Create a new multi-agent task in the selected workspace.
    func createTask(workspaceId: String, title: String?, prompt: String, agents: [String]) async throws -> TaskRecord {
        try await post(
            path: "api/app/tasks",
            body: CreateTaskPayload(workspaceId: workspaceId, title: title, prompt: prompt, agents: agents, issueId: nil)
        )
    }

    func createGoal(companyId: String, title: String, description: String, successMetrics: [String] = [], autonomyEnabled: Bool = true) async throws -> GoalRecord {
        try await post(
            path: "api/app/companies/\(companyId)/goals",
            body: CreateGoalPayload(
                title: title,
                description: description,
                successMetrics: successMetrics,
                autonomyEnabled: autonomyEnabled
            )
        )
    }

    func updateGoal(
        companyId: String,
        goalId: String,
        title: String,
        description: String,
        successMetrics: [String] = [],
        autonomyEnabled: Bool = true
    ) async throws -> GoalRecord {
        try await patch(
            path: "api/app/companies/\(companyId)/goals/\(goalId)",
            body: UpdateGoalPayload(
                title: title,
                description: description,
                successMetrics: successMetrics,
                autonomyEnabled: autonomyEnabled
            )
        )
    }

    func deleteGoal(companyId: String, goalId: String) async throws -> GoalRecord {
        try await delete(path: "api/app/companies/\(companyId)/goals/\(goalId)")
    }

    func createCompany(
        name: String,
        rootPath: String,
        defaultBaseBranch: String?,
        dailyBudgetCents: Int?,
        monthlyBudgetCents: Int?
    ) async throws -> CreateCompanyResponsePayload {
        try await post(
            path: "api/app/companies",
            body: CreateCompanyPayload(
                name: name,
                rootPath: rootPath,
                defaultBaseBranch: defaultBaseBranch,
                autonomyEnabled: true,
                dailyBudgetCents: dailyBudgetCents,
                monthlyBudgetCents: monthlyBudgetCents
            )
        )
    }

    func updateCompany(
        companyId: String,
        name: String? = nil,
        defaultBaseBranch: String? = nil,
        autonomyEnabled: Bool? = nil,
        backendKind: String? = nil,
        dailyBudgetCents: Int? = nil,
        monthlyBudgetCents: Int? = nil
    ) async throws -> CompanyRecord {
        try await patch(
            path: "api/app/companies/\(companyId)",
            body: UpdateCompanyPayload(
                name: name,
                defaultBaseBranch: defaultBaseBranch,
                autonomyEnabled: autonomyEnabled,
                backendKind: backendKind,
                dailyBudgetCents: dailyBudgetCents,
                monthlyBudgetCents: monthlyBudgetCents
            )
        )
    }

    func updateBackendSettings(
        defaultBackendKind: String,
        codePublishMode: String,
        codexLaunchMode: String?,
        codexCommand: String?,
        codexArgs: [String],
        codexWorkingDirectory: String?,
        codexPort: Int?,
        codexStartupTimeoutSeconds: Int?,
        codexAppServerBaseURL: String?,
        codexAuthMode: String?,
        codexToken: String?,
        codexTimeoutSeconds: Int?
    ) async throws -> DesktopSettingsPayload {
        try await patch(
            path: "api/app/settings/backends/default",
            body: UpdateBackendSettingsPayload(
                defaultBackendKind: defaultBackendKind,
                codePublishMode: codePublishMode,
                codexLaunchMode: codexLaunchMode,
                codexCommand: codexCommand,
                codexArgs: codexArgs,
                codexWorkingDirectory: codexWorkingDirectory,
                codexPort: codexPort,
                codexStartupTimeoutSeconds: codexStartupTimeoutSeconds,
                codexAppServerBaseUrl: codexAppServerBaseURL,
                codexAuthMode: codexAuthMode,
                codexToken: codexToken,
                codexTimeoutSeconds: codexTimeoutSeconds
            )
        )
    }

    func testBackend(
        kind: String,
        launchMode: String?,
        command: String?,
        args: [String],
        workingDirectory: String?,
        port: Int?,
        startupTimeoutSeconds: Int?,
        baseURL: String?,
        authMode: String?,
        token: String?,
        timeoutSeconds: Int?
    ) async throws -> ExecutionBackendStatusPayload {
        try await post(
            path: "api/app/settings/backends/test",
            body: TestBackendPayload(
                kind: kind,
                launchMode: launchMode,
                command: command,
                args: args,
                workingDirectory: workingDirectory,
                port: port,
                startupTimeoutSeconds: startupTimeoutSeconds,
                baseUrl: baseURL,
                authMode: authMode,
                token: token,
                timeoutSeconds: timeoutSeconds
            )
        )
    }

    func updateCompanyBackend(
        companyId: String,
        backendKind: String,
        launchMode: String?,
        command: String?,
        args: [String],
        workingDirectory: String?,
        port: Int?,
        startupTimeoutSeconds: Int?,
        baseURL: String?,
        authMode: String?,
        token: String?,
        timeoutSeconds: Int?,
        useGlobalDefault: Bool
    ) async throws -> CompanyRecord {
        try await patch(
            path: "api/app/companies/\(companyId)/backend",
            body: UpdateCompanyBackendPayload(
                backendKind: backendKind,
                launchMode: launchMode,
                command: command,
                args: args,
                workingDirectory: workingDirectory,
                port: port,
                startupTimeoutSeconds: startupTimeoutSeconds,
                baseUrl: baseURL,
                authMode: authMode,
                token: token,
                timeoutSeconds: timeoutSeconds,
                useGlobalDefault: useGlobalDefault
            )
        )
    }

    func companyBackendStatus(companyId: String) async throws -> ExecutionBackendStatusPayload {
        try await get(path: "api/app/companies/\(companyId)/backend")
    }

    func startCompanyBackend(companyId: String) async throws -> ExecutionBackendStatusPayload {
        try await post(path: "api/app/companies/\(companyId)/backend/start", body: EmptyPayload())
    }

    func stopCompanyBackend(companyId: String) async throws -> ExecutionBackendStatusPayload {
        try await post(path: "api/app/companies/\(companyId)/backend/stop", body: EmptyPayload())
    }

    func restartCompanyBackend(companyId: String) async throws -> ExecutionBackendStatusPayload {
        try await post(path: "api/app/companies/\(companyId)/backend/restart", body: EmptyPayload())
    }

    func updateCompanyLinear(
        companyId: String,
        enabled: Bool,
        endpoint: String?,
        apiToken: String?,
        teamId: String?,
        projectId: String?,
        useGlobalDefault: Bool
    ) async throws -> CompanyRecord {
        try await patch(
            path: "api/app/companies/\(companyId)/linear",
            body: UpdateCompanyLinearPayload(
                enabled: enabled,
                endpoint: endpoint,
                apiToken: apiToken,
                teamId: teamId,
                projectId: projectId,
                stateMappings: nil,
                useGlobalDefault: useGlobalDefault
            )
        )
    }

    func resyncCompanyLinear(companyId: String) async throws -> LinearSyncResponsePayload {
        try await post(path: "api/app/companies/\(companyId)/linear/resync", body: EmptyPayload())
    }

    func deleteCompany(companyId: String) async throws -> CompanyRecord {
        try await delete(path: "api/app/companies/\(companyId)")
    }

    func createIssue(
        companyId: String,
        goalId: String,
        title: String,
        description: String,
        priority: Int = 3,
        kind: String = "manual"
    ) async throws -> IssueRecord {
        try await post(
            path: "api/app/companies/\(companyId)/issues",
            body: CreateIssuePayload(
                goalId: goalId,
                title: title,
                description: description,
                priority: priority,
                kind: kind
            )
        )
    }

    func deleteIssue(companyId: String, issueId: String) async throws -> IssueRecord {
        try await delete(path: "api/app/companies/\(companyId)/issues/\(issueId)")
    }

    func createCompanyAgent(
        companyId: String,
        title: String,
        agentCli: String,
        model: String?,
        roleSummary: String,
        specialties: [String],
        collaborationInstructions: String?,
        preferredCollaboratorIds: [String],
        memoryNotes: String?,
        enabled: Bool = true
    ) async throws -> CompanyAgentDefinitionRecord {
        try await post(
            path: "api/app/companies/\(companyId)/agents",
            body: CreateCompanyAgentPayload(
                title: title,
                agentCli: agentCli,
                model: model,
                roleSummary: roleSummary,
                specialties: specialties,
                collaborationInstructions: collaborationInstructions,
                preferredCollaboratorIds: preferredCollaboratorIds,
                memoryNotes: memoryNotes,
                enabled: enabled
            )
        )
    }

    func updateCompanyAgent(
        companyId: String,
        agentId: String,
        title: String,
        agentCli: String,
        model: String?,
        roleSummary: String,
        specialties: [String],
        collaborationInstructions: String?,
        preferredCollaboratorIds: [String],
        memoryNotes: String?,
        enabled: Bool
    ) async throws -> CompanyAgentDefinitionRecord {
        try await patch(
            path: "api/app/companies/\(companyId)/agents/\(agentId)",
            body: UpdateCompanyAgentPayload(
                title: title,
                agentCli: agentCli,
                model: model,
                roleSummary: roleSummary,
                specialties: specialties,
                collaborationInstructions: collaborationInstructions,
                preferredCollaboratorIds: preferredCollaboratorIds,
                memoryNotes: memoryNotes,
                enabled: enabled,
                displayOrder: nil
            )
        )
    }

    func batchUpdateCompanyAgents(
        companyId: String,
        agentIds: [String],
        agentCli: String?,
        model: String?,
        specialties: [String]?,
        enabled: Bool?
    ) async throws -> [CompanyAgentDefinitionRecord] {
        try await patch(
            path: "api/app/companies/\(companyId)/agents/batch",
            body: BatchUpdateCompanyAgentsPayload(
                agentIds: agentIds,
                agentCli: agentCli,
                model: model,
                specialties: specialties,
                enabled: enabled
            )
        )
    }

    func startCompanyRuntime(companyId: String) async throws -> CompanyRuntimeSnapshotRecord {
        try await post(path: "api/app/companies/\(companyId)/runtime/start", body: EmptyPayload())
    }

    func stopCompanyRuntime(companyId: String) async throws -> CompanyRuntimeSnapshotRecord {
        try await post(path: "api/app/companies/\(companyId)/runtime/stop", body: EmptyPayload())
    }

    func runIssue(issueId: String) async throws -> IssueRecord {
        try await post(path: "api/app/issues/\(issueId)/run", body: EmptyPayload())
    }

    /// Ask the backend to start executing an already-created task.
    func runTask(taskId: String) async throws -> TaskRecord {
        try await post(path: "api/app/tasks/\(taskId)/run", body: EmptyPayload())
    }

    /// Open or reuse the interactive TUI session for one workspace.
    func openTuiSession(workspaceId: String, preferredAgent: String?) async throws -> TuiSessionRecord {
        try await post(
            path: "api/app/tui/sessions",
            body: OpenTuiSessionPayload(workspaceId: workspaceId, preferredAgent: preferredAgent)
        )
    }

    /// List every active TUI session so the desktop shell can switch between
    /// multiple folder-backed terminals without relying on company state.
    func listTuiSessions() async throws -> [TuiSessionRecord] {
        try await get(path: "api/app/tui/sessions")
    }

    /// Fetch the latest rolling transcript for an active TUI session.
    func tuiSession(sessionId: String) async throws -> TuiSessionRecord {
        try await get(path: "api/app/tui/sessions/\(sessionId)")
    }

    /// Fetch only the terminal bytes appended after the provided cursor.
    func tuiDelta(sessionId: String, offset: Int64) async throws -> TuiSessionDeltaPayload {
        try await get(
            path: "api/app/tui/sessions/\(sessionId)/delta",
            query: [URLQueryItem(name: "offset", value: String(offset))]
        )
    }

    /// Forward raw terminal input into the running TUI process.
    func sendTuiInput(sessionId: String, input: String) async throws -> TuiSessionRecord {
        try await post(path: "api/app/tui/sessions/\(sessionId)/input", body: TuiInputPayload(input: input))
    }

    /// Gracefully stop the TUI session when the user wants a fresh start.
    func terminateTuiSession(sessionId: String) async throws -> TuiSessionRecord {
        try await post(path: "api/app/tui/sessions/\(sessionId)/terminate", body: EmptyPayload())
    }

    func companyEvents(companyId: String) -> AsyncThrowingStream<CompanyEventEnvelopePayload, Error> {
        AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    var request = URLRequest(url: try makeURL(path: "api/app/companies/\(companyId)/events"))
                    request.httpMethod = "GET"
                    addHeaders(to: &request)
                    let (bytes, response) = try await URLSession.shared.bytes(for: request)
                    guard let http = response as? HTTPURLResponse, (200 ..< 300).contains(http.statusCode) else {
                        throw URLError(.badServerResponse)
                    }
                    for try await line in bytes.lines {
                        let trimmed = line.trimmingCharacters(in: .whitespacesAndNewlines)
                        guard !trimmed.isEmpty else { continue }
                        let data = Data(trimmed.utf8)
                        continuation.yield(try JSONDecoder().decode(CompanyEventEnvelopePayload.self, from: data))
                    }
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { _ in
                task.cancel()
            }
        }
    }

    private func get<T: Decodable>(path: String, query: [URLQueryItem] = []) async throws -> T {
        var request = URLRequest(url: try makeURL(path: path, query: query))
        request.httpMethod = "GET"
        addHeaders(to: &request)
        return try await decode(request)
    }

    private func post<T: Decodable, Body: Encodable>(path: String, body: Body) async throws -> T {
        var request = URLRequest(url: try makeURL(path: path))
        request.httpMethod = "POST"
        request.httpBody = try JSONEncoder().encode(body)
        addHeaders(to: &request)
        return try await decode(request)
    }

    private func patch<T: Decodable, Body: Encodable>(path: String, body: Body) async throws -> T {
        var request = URLRequest(url: try makeURL(path: path))
        request.httpMethod = "PATCH"
        request.httpBody = try JSONEncoder().encode(body)
        addHeaders(to: &request)
        return try await decode(request)
    }

    private func delete<T: Decodable>(path: String) async throws -> T {
        var request = URLRequest(url: try makeURL(path: path))
        request.httpMethod = "DELETE"
        addHeaders(to: &request)
        return try await decode(request)
    }

    private func makeURL(path: String, query: [URLQueryItem] = []) throws -> URL {
        var components = URLComponents(url: baseURL, resolvingAgainstBaseURL: false)
        // All routes are relative to the localhost app-server root; callers only pass
        // the resource path fragment so they do not need to know deployment details.
        components?.path = "/" + path
        components?.queryItems = query.isEmpty ? nil : query
        guard let url = components?.url else {
            throw URLError(.badURL)
        }
        return url
    }

    private func decode<T: Decodable>(_ request: URLRequest) async throws -> T {
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw URLError(.badServerResponse)
        }
        // Bubble server error bodies up to the UI because the desktop app is mostly
        // an orchestration shell and the backend already knows the relevant failure reason.
        guard (200 ..< 300).contains(http.statusCode) else {
            throw APIError.http(http.statusCode, String(data: data, encoding: .utf8) ?? "Unknown server error")
        }
        return try JSONDecoder().decode(T.self, from: data)
    }

    private func addHeaders(to request: inout URLRequest) {
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        // Bearer auth is optional so local development can stay frictionless when desired.
        if let token, !token.isEmpty {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
    }
}

private struct EmptyPayload: Codable {}

private struct UpdateBackendSettingsPayload: Codable {
    let defaultBackendKind: String
    let codePublishMode: String
    let codexLaunchMode: String?
    let codexCommand: String?
    let codexArgs: [String]
    let codexWorkingDirectory: String?
    let codexPort: Int?
    let codexStartupTimeoutSeconds: Int?
    let codexAppServerBaseUrl: String?
    let codexAuthMode: String?
    let codexToken: String?
    let codexTimeoutSeconds: Int?
}

private struct TestBackendPayload: Codable {
    let kind: String
    let launchMode: String?
    let command: String?
    let args: [String]
    let workingDirectory: String?
    let port: Int?
    let startupTimeoutSeconds: Int?
    let baseUrl: String?
    let authMode: String?
    let token: String?
    let timeoutSeconds: Int?
}

private struct UpdateCompanyBackendPayload: Codable {
    let backendKind: String
    let launchMode: String?
    let command: String?
    let args: [String]
    let workingDirectory: String?
    let port: Int?
    let startupTimeoutSeconds: Int?
    let baseUrl: String?
    let authMode: String?
    let token: String?
    let timeoutSeconds: Int?
    let useGlobalDefault: Bool
}

enum APIError: LocalizedError {
    case http(Int, String)

    var statusCode: Int {
        switch self {
        case let .http(code, _):
            return code
        }
    }

    var responseBody: String {
        switch self {
        case let .http(_, message):
            return message
        }
    }

    var errorDescription: String? {
        switch self {
        case let .http(code, message):
            return "Server returned \(code): \(message)"
        }
    }
}
