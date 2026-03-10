import Foundation

/// Thin HTTP client for the localhost `cotor app-server`.
///
/// Keeping transport concerns here lets the view model stay focused on user intent
/// and state transitions instead of URLSession boilerplate.
struct DesktopAPI {
    let baseURL: URL
    let token: String?

    init() {
        // The desktop shell defaults to the standard localhost port but allows
        // overrides so developers can point it at a custom backend process.
        let rawURL = ProcessInfo.processInfo.environment["COTOR_APP_SERVER_URL"] ?? "http://127.0.0.1:8787"
        self.baseURL = URL(string: rawURL) ?? URL(string: "http://127.0.0.1:8787")!
        self.token = ProcessInfo.processInfo.environment["COTOR_APP_TOKEN"]
    }

    /// Fetch the full dashboard payload used to bootstrap most of the app state.
    func dashboard() async throws -> DashboardPayload {
        try await get(path: "api/app/dashboard")
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

    /// Fetch the git diff summary for one task/agent pair.
    func changes(taskId: String, agentName: String) async throws -> ChangeSummaryPayload {
        try await get(path: "api/app/tasks/\(taskId)/changes/\(agentName)")
    }

    /// Fetch the nested file tree rooted at one agent worktree.
    func files(taskId: String, agentName: String, path: String?) async throws -> [FileTreeNodePayload] {
        let query = path.flatMap { $0.isEmpty ? nil : URLQueryItem(name: "path", value: $0) }.map { [$0] } ?? []
        return try await get(path: "api/app/tasks/\(taskId)/files/\(agentName)", query: query)
    }

    /// Fetch ports exposed by the process attached to one agent run.
    func ports(taskId: String, agentName: String) async throws -> [PortEntryPayload] {
        try await get(path: "api/app/tasks/\(taskId)/ports/\(agentName)")
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

    /// Create a new multi-agent task in the selected workspace.
    func createTask(workspaceId: String, title: String?, prompt: String, agents: [String]) async throws -> TaskRecord {
        try await post(
            path: "api/app/tasks",
            body: CreateTaskPayload(workspaceId: workspaceId, title: title, prompt: prompt, agents: agents)
        )
    }

    /// Ask the backend to start executing an already-created task.
    func runTask(taskId: String) async throws -> TaskRecord {
        try await post(path: "api/app/tasks/\(taskId)/run", body: EmptyPayload())
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

enum APIError: LocalizedError {
    case http(Int, String)

    var errorDescription: String? {
        switch self {
        case let .http(code, message):
            return "Server returned \(code): \(message)"
        }
    }
}
