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

struct CompanyRecord: Codable, Identifiable, Hashable {
    let id: String
    let name: String
    let rootPath: String
    let repositoryId: String
    let defaultBaseBranch: String
    let autonomyEnabled: Bool
    let createdAt: Int64
    let updatedAt: Int64
}

struct CompanyAgentDefinitionRecord: Codable, Identifiable, Hashable {
    let id: String
    let companyId: String
    let title: String
    let agentCli: String
    let roleSummary: String
    let enabled: Bool
    let displayOrder: Int
    let createdAt: Int64
    let updatedAt: Int64
}

struct CompanyProjectContextRecord: Codable, Identifiable, Hashable {
    let id: String
    let companyId: String
    let name: String
    let slug: String
    let status: String
    let contextDocPath: String
    let lastUpdatedAt: Int64
}

struct CompanyActivityItemRecord: Codable, Identifiable, Hashable {
    let id: String
    let companyId: String
    let projectContextId: String?
    let goalId: String?
    let issueId: String?
    let source: String
    let title: String
    let detail: String?
    let severity: String
    let createdAt: Int64
}

/// User-authored task record shown in the center pane.
struct TaskRecord: Codable, Identifiable, Hashable {
    let id: String
    let workspaceId: String
    let issueId: String?
    let title: String
    let prompt: String
    let agents: [String]
    let status: String
    let createdAt: Int64
    let updatedAt: Int64
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
    let publish: PublishMetadataRecord?
    let durationMs: Int64?
    let createdAt: Int64
    let updatedAt: Int64
}

/// Publish metadata returned for a completed desktop run.
struct PublishMetadataRecord: Codable, Hashable {
    let commitSha: String?
    let pushedBranch: String?
    let pullRequestNumber: Int?
    let pullRequestUrl: String?
    let reviewState: String?
    let requestedReviewers: [String]
    let checksSummary: String?
    let mergeability: String?
    let lastSyncTime: Int64?
    let error: String?
}

struct GoalRecord: Codable, Identifiable, Hashable {
    let id: String
    let companyId: String
    let projectContextId: String?
    let title: String
    let description: String
    let status: String
    let priority: Int
    let successMetrics: [String]
    let operatingPolicy: String?
    let autonomyEnabled: Bool
    let createdAt: Int64
    let updatedAt: Int64
}

struct IssueRecord: Codable, Identifiable, Hashable {
    let id: String
    let companyId: String
    let projectContextId: String?
    let goalId: String
    let workspaceId: String
    let title: String
    let description: String
    let status: String
    let priority: Int
    let kind: String
    let assigneeProfileId: String?
    let linearIssueId: String?
    let blockedBy: [String]
    let dependsOn: [String]
    let acceptanceCriteria: [String]
    let riskLevel: String
    let sourceSignal: String
    let createdAt: Int64
    let updatedAt: Int64
}

struct OrgAgentProfileRecord: Codable, Identifiable, Hashable {
    let id: String
    let companyId: String
    let roleName: String
    let executionAgentName: String
    let capabilities: [String]
    let linearAssigneeId: String?
    let reviewerPolicy: String?
    let mergeAuthority: Bool
    let enabled: Bool
}

struct ReviewQueueItemRecord: Codable, Identifiable, Hashable {
    let id: String
    let companyId: String
    let projectContextId: String?
    let issueId: String
    let runId: String
    let pullRequestNumber: Int?
    let pullRequestUrl: String?
    let status: String
    let checksSummary: String?
    let mergeability: String?
    let requestedReviewers: [String]
    let createdAt: Int64
    let updatedAt: Int64
}

struct OpsMetricSnapshotRecord: Codable, Hashable {
    let openGoals: Int
    let activeIssues: Int
    let blockedIssues: Int
    let readyToMergeCount: Int
    let mergedCount: Int
    let lastUpdatedAt: Int64
}

struct CompanyRuntimeSnapshotRecord: Codable, Hashable {
    let companyId: String?
    let status: String
    let tickIntervalSeconds: Int64
    let activeGoalCount: Int
    let activeIssueCount: Int
    let autonomyEnabledGoalCount: Int
    let lastStartedAt: Int64?
    let lastStoppedAt: Int64?
    let lastTickAt: Int64?
    let lastAction: String?
    let lastError: String?
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
    let availableCliAgents: [String]
    let recentCompanies: [String]
    let defaultLaunchMode: String
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
    let companies: [CompanyRecord]
    let companyAgentDefinitions: [CompanyAgentDefinitionRecord]
    let projectContexts: [CompanyProjectContextRecord]
    let goals: [GoalRecord]
    let issues: [IssueRecord]
    let reviewQueue: [ReviewQueueItemRecord]
    let orgProfiles: [OrgAgentProfileRecord]
    let opsMetrics: OpsMetricSnapshotRecord
    let activity: [CompanyActivityItemRecord]
    let companyRuntimes: [CompanyRuntimeSnapshotRecord]
}

/// Request body for creating a workspace from the macOS client.
struct CreateWorkspacePayload: Codable {
    let repositoryId: String
    let name: String?
    let baseBranch: String?
}

struct UpdateWorkspaceBaseBranchPayload: Codable {
    let baseBranch: String
}

/// Request body for creating a multi-agent task from the macOS client.
struct CreateTaskPayload: Codable {
    let workspaceId: String
    let title: String?
    let prompt: String
    let agents: [String]
    let issueId: String?
}

struct CreateGoalPayload: Codable {
    let title: String
    let description: String
    let successMetrics: [String]
    let autonomyEnabled: Bool
}

struct CreateCompanyPayload: Codable {
    let name: String
    let rootPath: String
    let defaultBaseBranch: String?
    let autonomyEnabled: Bool
}

struct CreateCompanyAgentPayload: Codable {
    let title: String
    let agentCli: String
    let roleSummary: String
    let enabled: Bool
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
                issueId: "issue-demo-build",
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
            availableCliAgents: ["claude", "codex", "gemini", "opencode"],
            recentCompanies: ["company-demo"],
            defaultLaunchMode: "company",
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
        ),
        companies: [
            CompanyRecord(
                id: "company-demo",
                name: "Cotor",
                rootPath: "/Users/demo/cotor",
                repositoryId: "repo-demo",
                defaultBaseBranch: "master",
                autonomyEnabled: true,
                createdAt: 0,
                updatedAt: 0
            )
        ],
        companyAgentDefinitions: [
            CompanyAgentDefinitionRecord(
                id: "agent-def-ceo",
                companyId: "company-demo",
                title: "CEO",
                agentCli: "claude",
                roleSummary: "lead strategy, planning, triage, final merge",
                enabled: true,
                displayOrder: 0,
                createdAt: 0,
                updatedAt: 0
            ),
            CompanyAgentDefinitionRecord(
                id: "agent-def-builder",
                companyId: "company-demo",
                title: "Builder",
                agentCli: "codex",
                roleSummary: "implementation, integration, delivery",
                enabled: true,
                displayOrder: 1,
                createdAt: 0,
                updatedAt: 0
            ),
            CompanyAgentDefinitionRecord(
                id: "agent-def-qa",
                companyId: "company-demo",
                title: "QA",
                agentCli: "gemini",
                roleSummary: "qa, review, verification",
                enabled: true,
                displayOrder: 2,
                createdAt: 0,
                updatedAt: 0
            )
        ],
        projectContexts: [
            CompanyProjectContextRecord(
                id: "project-demo",
                companyId: "company-demo",
                name: "Cotor Core",
                slug: "cotor-core",
                status: "ACTIVE",
                contextDocPath: "/Users/demo/.cotor/companies/cotor/projects/default.md",
                lastUpdatedAt: 0
            )
        ],
        goals: [
            GoalRecord(
                id: "goal-demo",
                companyId: "company-demo",
                projectContextId: "project-demo",
                title: "Autonomous AI company workflow",
                description: "Decompose one company goal into executable issues and keep PR feedback moving without manual orchestration.",
                status: "ACTIVE",
                priority: 1,
                successMetrics: ["Issue board visible", "Review queue updates from PRs"],
                operatingPolicy: nil,
                autonomyEnabled: true,
                createdAt: 0,
                updatedAt: 0
            )
        ],
        issues: [
            IssueRecord(
                id: "issue-demo-plan",
                companyId: "company-demo",
                projectContextId: "project-demo",
                goalId: "goal-demo",
                workspaceId: "ws-demo",
                title: "Define orchestration model",
                description: "Map the CEO planner, delegation rules, and PR review loop.",
                status: "IN_REVIEW",
                priority: 1,
                kind: "planning",
                assigneeProfileId: "profile-ceo",
                linearIssueId: nil,
                blockedBy: [],
                dependsOn: [],
                acceptanceCriteria: ["Goal decomposes into issues", "Roles are explicit"],
                riskLevel: "medium",
                sourceSignal: "goal-decomposition",
                createdAt: 0,
                updatedAt: 0
            ),
            IssueRecord(
                id: "issue-demo-build",
                companyId: "company-demo",
                projectContextId: "project-demo",
                goalId: "goal-demo",
                workspaceId: "ws-demo",
                title: "Ship goal to issue board UI",
                description: "Replace the task rail with a goal-driven issue board and execution drawer.",
                status: "IN_PROGRESS",
                priority: 2,
                kind: "implementation",
                assigneeProfileId: "profile-builder",
                linearIssueId: nil,
                blockedBy: ["issue-demo-plan"],
                dependsOn: ["issue-demo-plan"],
                acceptanceCriteria: ["Goal sidebar exists", "Issue board is visible"],
                riskLevel: "medium",
                sourceSignal: "goal-decomposition",
                createdAt: 0,
                updatedAt: 0
            ),
            IssueRecord(
                id: "issue-demo-qa",
                companyId: "company-demo",
                projectContextId: "project-demo",
                goalId: "goal-demo",
                workspaceId: "ws-demo",
                title: "Validate review queue loop",
                description: "Confirm PR metadata flows into QA and merge stages.",
                status: "PLANNED",
                priority: 3,
                kind: "qa",
                assigneeProfileId: "profile-qa",
                linearIssueId: nil,
                blockedBy: ["issue-demo-build"],
                dependsOn: ["issue-demo-build"],
                acceptanceCriteria: ["Review queue item appears", "Merge action is visible"],
                riskLevel: "low",
                sourceSignal: "goal-decomposition",
                createdAt: 0,
                updatedAt: 0
            )
        ],
        reviewQueue: [
            ReviewQueueItemRecord(
                id: "rq-demo",
                companyId: "company-demo",
                projectContextId: "project-demo",
                issueId: "issue-demo-plan",
                runId: "run-codex",
                pullRequestNumber: 42,
                pullRequestUrl: "https://github.com/heodongun/cotor/pull/42",
                status: "READY_TO_MERGE",
                checksSummary: "desktop smoke checks passed",
                mergeability: "clean",
                requestedReviewers: ["qa"],
                createdAt: 0,
                updatedAt: 0
            )
        ],
        orgProfiles: [
            OrgAgentProfileRecord(
                id: "profile-ceo",
                companyId: "company-demo",
                roleName: "CEO",
                executionAgentName: "claude",
                capabilities: ["planning", "triage", "goal-decomposition"],
                linearAssigneeId: nil,
                reviewerPolicy: nil,
                mergeAuthority: true,
                enabled: true
            ),
            OrgAgentProfileRecord(
                id: "profile-builder",
                companyId: "company-demo",
                roleName: "Builder",
                executionAgentName: "codex",
                capabilities: ["implementation", "backend", "frontend"],
                linearAssigneeId: nil,
                reviewerPolicy: nil,
                mergeAuthority: false,
                enabled: true
            ),
            OrgAgentProfileRecord(
                id: "profile-qa",
                companyId: "company-demo",
                roleName: "QA",
                executionAgentName: "gemini",
                capabilities: ["qa", "review", "verification"],
                linearAssigneeId: nil,
                reviewerPolicy: "review-queue",
                mergeAuthority: false,
                enabled: true
            )
        ],
        opsMetrics: OpsMetricSnapshotRecord(
            openGoals: 1,
            activeIssues: 3,
            blockedIssues: 0,
            readyToMergeCount: 1,
            mergedCount: 0,
            lastUpdatedAt: 0
        ),
        activity: [
            CompanyActivityItemRecord(
                id: "activity-demo-1",
                companyId: "company-demo",
                projectContextId: "project-demo",
                goalId: "goal-demo",
                issueId: nil,
                source: "goal",
                title: "Created goal",
                detail: "Autonomous AI company workflow",
                severity: "info",
                createdAt: 0
            ),
            CompanyActivityItemRecord(
                id: "activity-demo-2",
                companyId: "company-demo",
                projectContextId: "project-demo",
                goalId: "goal-demo",
                issueId: "issue-demo-build",
                source: "issue-run",
                title: "Started issue run",
                detail: "Ship goal to issue board UI",
                severity: "info",
                createdAt: 0
            )
        ],
        companyRuntimes: [
            CompanyRuntimeSnapshotRecord(
                companyId: "company-demo",
                status: "RUNNING",
                tickIntervalSeconds: 60,
                activeGoalCount: 1,
                activeIssueCount: 3,
                autonomyEnabledGoalCount: 1,
                lastStartedAt: 0,
                lastStoppedAt: nil,
                lastTickAt: 0,
                lastAction: "started:issue-demo-build",
                lastError: nil
            )
        ]
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
            publish: nil,
            durationMs: 1200,
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
            publish: PublishMetadataRecord(
                commitSha: "864046d9bb40e7b3d1f6d1d12a7e8f9a0b1c2d3e",
                pushedBranch: "codex/cotor/superset-shell/codex",
                pullRequestNumber: 42,
                pullRequestUrl: "https://github.com/heodongun/cotor/pull/42",
                reviewState: "awaiting-review",
                requestedReviewers: ["qa"],
                checksSummary: "desktop smoke checks passed",
                mergeability: "clean",
                lastSyncTime: 0,
                error: nil
            ),
            durationMs: 1800,
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
