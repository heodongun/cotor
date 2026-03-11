import Foundation

/// Mirrors the repository DTO returned by `/api/app/dashboard`.
struct RepositoryRecord: Codable, Identifiable, Hashable {
    let id: String
    let name: String
    let localPath: String
    let sourceKind: String
    let remoteUrl: String?
    let defaultBranch: String
    let createdAt: Int64
    let updatedAt: Int64
}

/// Desktop workspace summary bound to one repository/base branch combination.
struct WorkspaceRecord: Codable, Identifiable, Hashable {
    let id: String
    let repositoryId: String
    let name: String
    let baseBranch: String
    let createdAt: Int64
    let updatedAt: Int64
}

/// User-authored task record shown in the center pane.
struct TaskRecord: Codable, Identifiable, Hashable {
    let id: String
    let workspaceId: String
    let title: String
    let prompt: String
    let agents: [String]
    let status: String
    let createdAt: Int64
    let updatedAt: Int64
}

/// Per-agent run record displayed in the run list and inspector.
struct RunPublishInfoRecord: Codable, Hashable {
    let status: String
    let remoteBranch: String?
    let commitSha: String?
    let pullRequestUrl: String?
    let pullRequestNumber: Int?
    let summary: String?
}

/// Per-agent run record displayed in the run list and inspector.
struct RunRecord: Codable, Identifiable, Hashable {
    let id: String
    let taskId: String
    let workspaceId: String
    let repositoryId: String
    let agentId: String
    let agentName: String
    let repoRoot: String
    let baseBranch: String
    let branchName: String
    let worktreePath: String
    let status: String
    let processId: Int64?
    let output: String?
    let error: String?
    let durationMs: Int64?
    let publishInfo: RunPublishInfoRecord?
    let createdAt: Int64
    let updatedAt: Int64
}

/// Live TUI session snapshot rendered in the center terminal surface.
struct TuiSessionRecord: Codable, Identifiable, Hashable {
    let id: String
    let workspaceId: String
    let repositoryId: String
    let repositoryPath: String
    let agentName: String?
    let baseBranch: String
    let status: String
    let transcript: String
    let transcriptStartOffset: Int64
    let transcriptEndOffset: Int64
    let processId: Int64?
    let exitCode: Int?
    let createdAt: Int64
    let updatedAt: Int64
}

/// Incremental chunk consumed by the embedded terminal emulator.
struct TuiSessionDeltaPayload: Codable, Hashable {
    let sessionId: String
    let status: String
    let offset: Int64
    let nextOffset: Int64
    let reset: Bool
    let chunk: String
    let exitCode: Int?
}

/// Serialized git diff summary for one agent worktree.
struct ChangeSummaryPayload: Codable, Hashable {
    let runId: String
    let branchName: String
    let baseBranch: String
    let patch: String
    let changedFiles: [String]
}

/// Recursive file tree node used by the inspector's outline view.
struct FileTreeNodePayload: Codable, Hashable, Identifiable {
    var id: String { relativePath }
    /// `OutlineGroup` expects `nil` for leaf nodes, not an empty array.
    var optionalChildren: [FileTreeNodePayload]? { children.isEmpty ? nil : children }

    let name: String
    let relativePath: String
    let isDirectory: Bool
    let sizeBytes: Int64?
    let children: [FileTreeNodePayload]
}

/// Represents a local service exposed by the running task.
struct PortEntryPayload: Codable, Hashable, Identifiable {
    var id: String { "\(port)-\(url)" }

    let port: Int
    let url: String
    let label: String
}

/// Desktop bootstrap settings sent by the backend.
struct DesktopSettingsPayload: Codable, Hashable {
    let appHome: String
    let managedReposRoot: String
    let availableAgents: [String]
    let shortcuts: ShortcutConfigPayload
}

/// Settings metadata for the currently active keyboard shortcuts.
struct ShortcutConfigPayload: Codable, Hashable {
    let bindings: [ShortcutBindingPayload]
}

/// One visible shortcut entry in the settings screen.
struct ShortcutBindingPayload: Codable, Hashable, Identifiable {
    let id: String
    let title: String
    let shortcut: String
}

/// Combined payload fetched during initial load and refresh.
struct DashboardPayload: Codable {
    let repositories: [RepositoryRecord]
    let workspaces: [WorkspaceRecord]
    let tasks: [TaskRecord]
    let settings: DesktopSettingsPayload
}

/// Request body for creating a workspace from the macOS client.
struct CreateWorkspacePayload: Codable {
    let repositoryId: String
    let name: String?
    let baseBranch: String?
}

/// Request body for creating a multi-agent task from the macOS client.
struct CreateTaskPayload: Codable {
    let workspaceId: String
    let title: String?
    let prompt: String
    let agents: [String]
}

/// Request body for opening or reusing the TUI session tied to a workspace.
struct OpenTuiSessionPayload: Codable {
    let workspaceId: String
    let preferredAgent: String?
}

/// Request body for sending one line into the interactive TUI loop.
struct TuiInputPayload: Codable {
    let input: String
}

/// The inspector's right-hand tabs.
enum InspectorTab: CaseIterable, Identifiable {
    case changes
    case files
    case ports
    case browser

    var id: String {
        switch self {
        case .changes:
            return "changes"
        case .files:
            return "files"
        case .ports:
            return "ports"
        case .browser:
            return "browser"
        }
    }

    var textKey: DesktopTextKey {
        switch self {
        case .changes:
            return .changes
        case .files:
            return .files
        case .ports:
            return .ports
        case .browser:
            return .browser
        }
    }
}

/// Curated fallback content used when the localhost backend is unavailable.
struct MockSeed {
    static let dashboard = DashboardPayload(
        repositories: [
            RepositoryRecord(
                id: "repo-demo",
                name: "cotor",
                localPath: "/Users/demo/cotor",
                sourceKind: "LOCAL",
                remoteUrl: "https://github.com/heodongun/cotor.git",
                defaultBranch: "master",
                createdAt: 0,
                updatedAt: 0
            )
        ],
        workspaces: [
            WorkspaceRecord(
                id: "ws-demo",
                repositoryId: "repo-demo",
                name: "cotor · master",
                baseBranch: "master",
                createdAt: 0,
                updatedAt: 0
            )
        ],
        tasks: [
            TaskRecord(
                id: "task-demo",
                workspaceId: "ws-demo",
                title: "Superset shell parity",
                prompt: "Build the desktop shell",
                agents: ["claude", "codex", "gemini"],
                status: "RUNNING",
                createdAt: 0,
                updatedAt: 0
            )
        ],
        settings: DesktopSettingsPayload(
            appHome: "~/Library/Application Support/CotorDesktop",
            managedReposRoot: "~/Library/Application Support/CotorDesktop/ManagedRepos",
            availableAgents: ["claude", "codex", "gemini", "cursor", "opencode"],
            shortcuts: ShortcutConfigPayload(bindings: [
                ShortcutBindingPayload(id: "openRepository", title: "Open Repository", shortcut: "Command-N"),
                ShortcutBindingPayload(id: "createTask", title: "Create Task", shortcut: "Command-T"),
                ShortcutBindingPayload(id: "showBrowser", title: "Show Browser Tab", shortcut: "Command-B"),
                ShortcutBindingPayload(id: "cloneRepository", title: "Clone Repository", shortcut: "Command-L"),
                ShortcutBindingPayload(id: "showFiles", title: "Show Files Tab", shortcut: "Command-F"),
                ShortcutBindingPayload(id: "settings", title: "Open Settings", shortcut: "Command-,"),
                ShortcutBindingPayload(id: "changesTab", title: "Show Changes Tab", shortcut: "Command-1"),
                ShortcutBindingPayload(id: "filesTab", title: "Show Files Tab", shortcut: "Command-2"),
                ShortcutBindingPayload(id: "portsTab", title: "Show Ports Tab", shortcut: "Command-3"),
                ShortcutBindingPayload(id: "browserTab", title: "Show Browser Tab", shortcut: "Command-4")
            ])
        )
    )

    static let runs = [
        RunRecord(
            id: "run-claude",
            taskId: "task-demo",
            workspaceId: "ws-demo",
            repositoryId: "repo-demo",
            agentId: "claude",
            agentName: "claude",
            repoRoot: "/Users/demo/cotor",
            baseBranch: "master",
            branchName: "codex/cotor/superset-shell/claude",
            worktreePath: "/Users/demo/cotor/.cotor/worktrees/task-demo/claude",
            status: "RUNNING",
            processId: 1001,
            output: "Working on shell layout and task orchestration...",
            error: nil,
            durationMs: 1200,
            publishInfo: RunPublishInfoRecord(
                status: "NOT_STARTED",
                remoteBranch: nil,
                commitSha: nil,
                pullRequestUrl: nil,
                pullRequestNumber: nil,
                summary: nil
            ),
            createdAt: 0,
            updatedAt: 0
        ),
        RunRecord(
            id: "run-codex",
            taskId: "task-demo",
            workspaceId: "ws-demo",
            repositoryId: "repo-demo",
            agentId: "codex",
            agentName: "codex",
            repoRoot: "/Users/demo/cotor",
            baseBranch: "master",
            branchName: "codex/cotor/superset-shell/codex",
            worktreePath: "/Users/demo/cotor/.cotor/worktrees/task-demo/codex",
            status: "COMPLETED",
            processId: 1002,
            output: "Added app-server APIs and worktree scaffolding.",
            error: nil,
            durationMs: 1800,
            publishInfo: RunPublishInfoRecord(
                status: "PR_CREATED",
                remoteBranch: "codex/cotor/superset-shell/codex",
                commitSha: "8d4a1d9",
                pullRequestUrl: "https://github.com/heodongun/cotor/pull/140",
                pullRequestNumber: 140,
                summary: "Created pull request #140"
            ),
            createdAt: 0,
            updatedAt: 0
        )
    ]

    static let tuiSession = TuiSessionRecord(
        id: "tui-demo",
        workspaceId: "ws-demo",
        repositoryId: "repo-demo",
        repositoryPath: "/Users/demo/cotor",
        agentName: "claude",
        baseBranch: "master",
        status: "RUNNING",
        transcript: """
Launching Cotor TUI in /Users/demo/cotor
Workspace base branch: master

◎ Cotor Interactive
Type ':help' for commands, ':exit' to quit.

you> open the workflow registry and summarize active agents
cotor>
Lead AI: claude
Workers: codex, gemini
Ready to orchestrate workflow execution from the TUI.

you> 
""",
        transcriptStartOffset: 0,
        transcriptEndOffset: 256,
        processId: 4242,
        exitCode: nil,
        createdAt: 0,
        updatedAt: 0
    )

    static let changes = ChangeSummaryPayload(
        runId: "run-codex",
        branchName: "codex/cotor/superset-shell/codex",
        baseBranch: "master",
        patch: """
diff --git a/src/main/kotlin/com/cotor/app/AppServer.kt b/src/main/kotlin/com/cotor/app/AppServer.kt
+ added desktop dashboard routes
+ added worktree-aware run APIs
""",
        changedFiles: [
            "src/main/kotlin/com/cotor/app/AppServer.kt",
            "src/main/kotlin/com/cotor/presentation/cli/AppServerCommand.kt"
        ]
    )

    static let files = [
        FileTreeNodePayload(
            name: ".cotor",
            relativePath: ".cotor",
            isDirectory: true,
            sizeBytes: nil,
            children: [
                FileTreeNodePayload(
                    name: "worktrees",
                    relativePath: ".cotor/worktrees",
                    isDirectory: true,
                    sizeBytes: nil,
                    children: []
                )
            ]
        ),
        FileTreeNodePayload(
            name: "src",
            relativePath: "src",
            isDirectory: true,
            sizeBytes: nil,
            children: []
        )
    ]

    static let ports = [
        PortEntryPayload(port: 8787, url: "http://127.0.0.1:8787", label: "Cotor app-server")
    ]
}
