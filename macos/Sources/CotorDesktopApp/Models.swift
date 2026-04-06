import Foundation


// MARK: - File Overview
// RepositoryRecord belongs to the native macOS client layer for the Cotor desktop application.
// It collects declarations centered on models so the native shell code stays easier to navigate.
// Start with this file when tracing how the desktop client presents, stores, or moves state in this area.

private extension KeyedDecodingContainer {
    func decodeValue<T: Decodable>(_ type: T.Type, forKey key: Key, default defaultValue: T) throws -> T {
        try decodeIfPresent(type, forKey: key) ?? defaultValue
    }
}

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
    let backendKind: String
    let linearSyncEnabled: Bool?
    let linearConfigOverride: LinearConnectionConfigPayload?
    let autonomyEnabled: Bool
    let dailyBudgetCents: Int?
    let monthlyBudgetCents: Int?
    let createdAt: Int64
    let updatedAt: Int64
}

struct CreateCompanyResponsePayload: Codable, Hashable {
    let company: CompanyRecord
    let githubPublishStatus: GitHubPublishStatusPayload
}

struct LinearStateMappingPayload: Codable, Hashable {
    let localStatus: String
    let linearStateName: String
}

struct LinearConnectionConfigPayload: Codable, Hashable {
    let endpoint: String
    let apiToken: String?
    let teamId: String?
    let projectId: String?
    let stateMappings: [LinearStateMappingPayload]
}

struct BackendConnectionConfigPayload: Codable, Hashable {
    let kind: String
    let launchMode: String
    let command: String
    let args: [String]
    let workingDirectory: String?
    let port: Int?
    let startupTimeoutSeconds: Int
    let baseUrl: String?
    let healthCheckPath: String
    let authMode: String
    let token: String?
    let timeoutSeconds: Int
    let enabled: Bool
}

struct ExecutionBackendCapabilitiesPayload: Codable, Hashable {
    let canStreamEvents: Bool
    let canResumeRuns: Bool
    let canSpawnParallelAgents: Bool
    let canPublishPullRequests: Bool
}

struct ExecutionBackendStatusPayload: Codable, Hashable, Identifiable {
    var id: String { kind }

    let kind: String
    let displayName: String
    let health: String
    let message: String?
    let lifecycleState: String
    let managed: Bool
    let pid: Int64?
    let port: Int?
    let lastError: String?
    let config: BackendConnectionConfigPayload
    let capabilities: ExecutionBackendCapabilitiesPayload
}

struct DesktopBackendSettingsPayload: Codable, Hashable {
    let defaultBackendKind: String
    let codePublishMode: String
    let backends: [BackendConnectionConfigPayload]
}

struct GitHubPublishStatusPayload: Codable, Hashable {
    let policy: String
    let ghInstalled: Bool
    let ghAuthenticated: Bool
    let originConfigured: Bool
    let originUrl: String?
    let bootstrapAvailable: Bool
    let repositoryPath: String?
    let companyId: String?
    let companyName: String?
    let message: String?
}

struct CompanyAgentDefinitionRecord: Codable, Identifiable, Hashable {
    let id: String
    let companyId: String
    let title: String
    let agentCli: String
    let model: String?
    let roleSummary: String
    let specialties: [String]
    let collaborationInstructions: String?
    let preferredCollaboratorIds: [String]
    let memoryNotes: String?
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

    private enum CodingKeys: String, CodingKey {
        case id
        case companyId
        case projectContextId
        case goalId
        case issueId
        case source
        case title
        case detail
        case severity
        case createdAt
    }

    init(
        id: String,
        companyId: String,
        projectContextId: String? = nil,
        goalId: String? = nil,
        issueId: String? = nil,
        source: String,
        title: String,
        detail: String? = nil,
        severity: String = "info",
        createdAt: Int64
    ) {
        self.id = id
        self.companyId = companyId
        self.projectContextId = projectContextId
        self.goalId = goalId
        self.issueId = issueId
        self.source = source
        self.title = title
        self.detail = detail
        self.severity = severity
        self.createdAt = createdAt
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        companyId = try container.decode(String.self, forKey: .companyId)
        projectContextId = try container.decodeIfPresent(String.self, forKey: .projectContextId)
        goalId = try container.decodeIfPresent(String.self, forKey: .goalId)
        issueId = try container.decodeIfPresent(String.self, forKey: .issueId)
        source = try container.decode(String.self, forKey: .source)
        title = try container.decode(String.self, forKey: .title)
        detail = try container.decodeIfPresent(String.self, forKey: .detail)
        severity = try container.decodeValue(String.self, forKey: .severity, default: "info")
        createdAt = try container.decode(Int64.self, forKey: .createdAt)
    }
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
    let model: String?
    let backendKind: String?
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
    let pullRequestState: String?
    let reviewState: String?
    let requestedReviewers: [String]
    let checksSummary: String?
    let mergeability: String?
    let lastSyncTime: Int64?
    let error: String?
}

struct FollowUpContextRecord: Codable, Hashable {
    let rootGoalId: String
    let triggerIssueId: String?
    let reviewQueueItemId: String?
    let pullRequestNumber: Int?
    let failureClass: String
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
    let followUpContext: FollowUpContextRecord?
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
    let linearIssueIdentifier: String?
    let linearIssueUrl: String?
    let lastLinearSyncAt: Int64?
    let blockedBy: [String]
    let dependsOn: [String]
    let acceptanceCriteria: [String]
    let riskLevel: String
    let codeProducing: Bool?
    let executionIntent: String?
    let branchName: String?
    let worktreePath: String?
    let pullRequestNumber: Int?
    let pullRequestUrl: String?
    let pullRequestState: String?
    let qaVerdict: String?
    let qaFeedback: String?
    let ceoVerdict: String?
    let ceoFeedback: String?
    let mergeResult: String?
    let transitionReason: String?
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
    let branchName: String?
    let worktreePath: String?
    let pullRequestNumber: Int?
    let pullRequestUrl: String?
    let pullRequestState: String?
    let status: String
    let checksSummary: String?
    let mergeability: String?
    let requestedReviewers: [String]
    let qaVerdict: String?
    let qaFeedback: String?
    let qaReviewedAt: Int64?
    let qaIssueId: String?
    let ceoVerdict: String?
    let ceoFeedback: String?
    let ceoReviewedAt: Int64?
    let approvalIssueId: String?
    let mergeCommitSha: String?
    let mergedAt: Int64?
    let createdAt: Int64
    let updatedAt: Int64
}

struct IssueAgentExecutionDetailRecord: Codable, Identifiable, Hashable {
    var id: String { runId ?? taskId }

    let roleName: String
    let agentName: String
    let agentCli: String
    let model: String?
    let assignedPrompt: String
    let taskId: String
    let taskStatus: String
    let runId: String?
    let runStatus: String?
    let backendKind: String?
    let processId: Int64?
    let stdout: String?
    let stderr: String?
    let branchName: String?
    let pullRequestUrl: String?
    let updatedAt: Int64
    let publishSummary: String?
}

struct IssueDependencyRecord: Codable, Identifiable, Hashable {
    let id: String
    let issueId: String
    let dependsOnIssueId: String
    let relation: String
}

struct OpsMetricSnapshotRecord: Codable, Hashable {
    let openGoals: Int
    let activeIssues: Int
    let blockedIssues: Int
    let readyToMergeCount: Int
    let mergedCount: Int
    let lastUpdatedAt: Int64

    init(
        openGoals: Int = 0,
        activeIssues: Int = 0,
        blockedIssues: Int = 0,
        readyToMergeCount: Int = 0,
        mergedCount: Int = 0,
        lastUpdatedAt: Int64 = 0
    ) {
        self.openGoals = openGoals
        self.activeIssues = activeIssues
        self.blockedIssues = blockedIssues
        self.readyToMergeCount = readyToMergeCount
        self.mergedCount = mergedCount
        self.lastUpdatedAt = lastUpdatedAt
    }
}

struct OpsSignalRecord: Codable, Identifiable, Hashable {
    let id: String
    let companyId: String?
    let projectContextId: String?
    let source: String
    let message: String
    let severity: String
    let goalId: String?
    let issueId: String?
    let createdAt: Int64
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
    let manuallyStoppedAt: Int64?
    let lastTickAt: Int64?
    let lastAction: String?
    let lastError: String?
    let backendKind: String
    let backendHealth: String
    let backendMessage: String?
    let backendLifecycleState: String
    let backendPid: Int64?
    let backendPort: Int?
    let todaySpentCents: Int
    let monthSpentCents: Int
    let budgetPausedAt: Int64?
    let budgetResetDate: String?

    private enum CodingKeys: String, CodingKey {
        case companyId
        case status
        case tickIntervalSeconds
        case activeGoalCount
        case activeIssueCount
        case autonomyEnabledGoalCount
        case lastStartedAt
        case lastStoppedAt
        case manuallyStoppedAt
        case lastTickAt
        case lastAction
        case lastError
        case backendKind
        case backendHealth
        case backendMessage
        case backendLifecycleState
        case backendPid
        case backendPort
        case todaySpentCents
        case monthSpentCents
        case budgetPausedAt
        case budgetResetDate
    }

    init(
        companyId: String? = nil,
        status: String = "STOPPED",
        tickIntervalSeconds: Int64 = 60,
        activeGoalCount: Int = 0,
        activeIssueCount: Int = 0,
        autonomyEnabledGoalCount: Int = 0,
        lastStartedAt: Int64? = nil,
        lastStoppedAt: Int64? = nil,
        manuallyStoppedAt: Int64? = nil,
        lastTickAt: Int64? = nil,
        lastAction: String? = nil,
        lastError: String? = nil,
        backendKind: String = "LOCAL_COTOR",
        backendHealth: String = "unknown",
        backendMessage: String? = nil,
        backendLifecycleState: String = "STOPPED",
        backendPid: Int64? = nil,
        backendPort: Int? = nil,
        todaySpentCents: Int = 0,
        monthSpentCents: Int = 0,
        budgetPausedAt: Int64? = nil,
        budgetResetDate: String? = nil
    ) {
        self.companyId = companyId
        self.status = status
        self.tickIntervalSeconds = tickIntervalSeconds
        self.activeGoalCount = activeGoalCount
        self.activeIssueCount = activeIssueCount
        self.autonomyEnabledGoalCount = autonomyEnabledGoalCount
        self.lastStartedAt = lastStartedAt
        self.lastStoppedAt = lastStoppedAt
        self.manuallyStoppedAt = manuallyStoppedAt
        self.lastTickAt = lastTickAt
        self.lastAction = lastAction
        self.lastError = lastError
        self.backendKind = backendKind
        self.backendHealth = backendHealth
        self.backendMessage = backendMessage
        self.backendLifecycleState = backendLifecycleState
        self.backendPid = backendPid
        self.backendPort = backendPort
        self.todaySpentCents = todaySpentCents
        self.monthSpentCents = monthSpentCents
        self.budgetPausedAt = budgetPausedAt
        self.budgetResetDate = budgetResetDate
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        companyId = try container.decodeIfPresent(String.self, forKey: .companyId)
        status = try container.decodeValue(String.self, forKey: .status, default: "STOPPED")
        tickIntervalSeconds = try container.decodeValue(Int64.self, forKey: .tickIntervalSeconds, default: 60)
        activeGoalCount = try container.decodeValue(Int.self, forKey: .activeGoalCount, default: 0)
        activeIssueCount = try container.decodeValue(Int.self, forKey: .activeIssueCount, default: 0)
        autonomyEnabledGoalCount = try container.decodeValue(Int.self, forKey: .autonomyEnabledGoalCount, default: 0)
        lastStartedAt = try container.decodeIfPresent(Int64.self, forKey: .lastStartedAt)
        lastStoppedAt = try container.decodeIfPresent(Int64.self, forKey: .lastStoppedAt)
        manuallyStoppedAt = try container.decodeIfPresent(Int64.self, forKey: .manuallyStoppedAt)
        lastTickAt = try container.decodeIfPresent(Int64.self, forKey: .lastTickAt)
        lastAction = try container.decodeIfPresent(String.self, forKey: .lastAction)
        lastError = try container.decodeIfPresent(String.self, forKey: .lastError)
        backendKind = try container.decodeValue(String.self, forKey: .backendKind, default: "LOCAL_COTOR")
        backendHealth = try container.decodeValue(String.self, forKey: .backendHealth, default: "unknown")
        backendMessage = try container.decodeIfPresent(String.self, forKey: .backendMessage)
        backendLifecycleState = try container.decodeValue(String.self, forKey: .backendLifecycleState, default: "STOPPED")
        backendPid = try container.decodeIfPresent(Int64.self, forKey: .backendPid)
        backendPort = try container.decodeIfPresent(Int.self, forKey: .backendPort)
        todaySpentCents = try container.decodeValue(Int.self, forKey: .todaySpentCents, default: 0)
        monthSpentCents = try container.decodeValue(Int.self, forKey: .monthSpentCents, default: 0)
        budgetPausedAt = try container.decodeIfPresent(Int64.self, forKey: .budgetPausedAt)
        budgetResetDate = try container.decodeIfPresent(String.self, forKey: .budgetResetDate)
    }

    var isManuallyStopped: Bool {
        status.uppercased() == "STOPPED" && manuallyStoppedAt != nil
    }

    var isBudgetPaused: Bool {
        budgetPausedAt != nil
    }
}

struct AgentCollaborationEdgeRecord: Codable, Hashable, Identifiable {
    var id: String { "\(companyId)-\(fromAgentId)-\(toAgentId)-\(handoffType)" }

    let companyId: String
    let fromAgentId: String
    let toAgentId: String
    let reason: String
    let handoffType: String
}

struct WorkflowTopologySnapshotRecord: Codable, Hashable, Identifiable {
    var id: String { companyId }

    let companyId: String
    let agents: [String]
    let edges: [AgentCollaborationEdgeRecord]
    let activeLoops: [String]
    let updatedAt: Int64
}

struct GoalOrchestrationDecisionRecord: Codable, Hashable, Identifiable {
    let id: String
    let companyId: String
    let goalId: String?
    let issueId: String?
    let title: String
    let summary: String
    let createdIssues: [String]
    let assignments: [String]
    let escalations: [String]
    let createdAt: Int64
}

struct RunningAgentSessionRecord: Codable, Hashable, Identifiable {
    var id: String { runId }

    let companyId: String
    let runId: String
    let taskId: String
    let issueId: String?
    let goalId: String?
    let agentId: String
    let agentName: String
    let roleName: String?
    let status: String
    let branchName: String
    let model: String?
    let backendKind: String?
    let processId: Int64?
    let outputSnippet: String?
    let startedAt: Int64
    let updatedAt: Int64
}

struct CompanyEventRecord: Codable, Hashable, Identifiable {
    let id: String
    let companyId: String
    let type: String
    let title: String
    let detail: String?
    let goalId: String?
    let issueId: String?
    let runId: String?
    let createdAt: Int64
}

struct CompanyEventEnvelopePayload: Codable {
    let event: CompanyEventRecord
    let dashboard: DashboardPayload?
    let companyDashboard: CompanyDashboardPayload?
}

struct CompanyDashboardPayload: Codable {
    let companies: [CompanyRecord]
    let companyAgentDefinitions: [CompanyAgentDefinitionRecord]
    let projectContexts: [CompanyProjectContextRecord]
    let goals: [GoalRecord]
    let issues: [IssueRecord]
    let tasks: [TaskRecord]
    let issueDependencies: [IssueDependencyRecord]
    let reviewQueue: [ReviewQueueItemRecord]
    let orgProfiles: [OrgAgentProfileRecord]
    let workflowTopologies: [WorkflowTopologySnapshotRecord]
    let goalDecisions: [GoalOrchestrationDecisionRecord]
    let runningAgentSessions: [RunningAgentSessionRecord]
    let backendStatuses: [ExecutionBackendStatusPayload]
    let opsMetrics: OpsMetricSnapshotRecord
    let runtime: CompanyRuntimeSnapshotRecord
    let signals: [OpsSignalRecord]
    let activity: [CompanyActivityItemRecord]

    private enum CodingKeys: String, CodingKey {
        case companies
        case companyAgentDefinitions
        case projectContexts
        case goals
        case issues
        case tasks
        case issueDependencies
        case reviewQueue
        case orgProfiles
        case workflowTopologies
        case goalDecisions
        case runningAgentSessions
        case backendStatuses
        case opsMetrics
        case runtime
        case signals
        case activity
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        companies = try container.decodeValue([CompanyRecord].self, forKey: .companies, default: [])
        companyAgentDefinitions = try container.decodeValue([CompanyAgentDefinitionRecord].self, forKey: .companyAgentDefinitions, default: [])
        projectContexts = try container.decodeValue([CompanyProjectContextRecord].self, forKey: .projectContexts, default: [])
        goals = try container.decodeValue([GoalRecord].self, forKey: .goals, default: [])
        issues = try container.decodeValue([IssueRecord].self, forKey: .issues, default: [])
        tasks = try container.decodeValue([TaskRecord].self, forKey: .tasks, default: [])
        issueDependencies = try container.decodeValue([IssueDependencyRecord].self, forKey: .issueDependencies, default: [])
        reviewQueue = try container.decodeValue([ReviewQueueItemRecord].self, forKey: .reviewQueue, default: [])
        orgProfiles = try container.decodeValue([OrgAgentProfileRecord].self, forKey: .orgProfiles, default: [])
        workflowTopologies = try container.decodeValue([WorkflowTopologySnapshotRecord].self, forKey: .workflowTopologies, default: [])
        goalDecisions = try container.decodeValue([GoalOrchestrationDecisionRecord].self, forKey: .goalDecisions, default: [])
        runningAgentSessions = try container.decodeValue([RunningAgentSessionRecord].self, forKey: .runningAgentSessions, default: [])
        backendStatuses = try container.decodeValue([ExecutionBackendStatusPayload].self, forKey: .backendStatuses, default: [])
        opsMetrics = try container.decodeValue(OpsMetricSnapshotRecord.self, forKey: .opsMetrics, default: OpsMetricSnapshotRecord())
        runtime = try container.decodeValue(CompanyRuntimeSnapshotRecord.self, forKey: .runtime, default: CompanyRuntimeSnapshotRecord())
        signals = try container.decodeValue([OpsSignalRecord].self, forKey: .signals, default: [])
        activity = try container.decodeValue([CompanyActivityItemRecord].self, forKey: .activity, default: [])
    }
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
struct FileTreeNodePayload: Codable, Hashable, Identifiable, Sendable {
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
    let backendSettings: DesktopBackendSettingsPayload
    let githubPublishStatus: GitHubPublishStatusPayload
    let linearSettings: DesktopLinearSettingsPayload?
    let backendStatuses: [ExecutionBackendStatusPayload]
    let shortcuts: ShortcutConfigPayload
}

struct DesktopLinearSettingsPayload: Codable, Hashable {
    let defaultConfig: LinearConnectionConfigPayload
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
    let workflowTopologies: [WorkflowTopologySnapshotRecord]
    let goalDecisions: [GoalOrchestrationDecisionRecord]
    let runningAgentSessions: [RunningAgentSessionRecord]
    let backendStatuses: [ExecutionBackendStatusPayload]
    let opsMetrics: OpsMetricSnapshotRecord
    let activity: [CompanyActivityItemRecord]
    let companyRuntimes: [CompanyRuntimeSnapshotRecord]
}

extension DashboardPayload {
    static let empty = DashboardPayload(
        repositories: [],
        workspaces: [],
        tasks: [],
        settings: DesktopSettingsPayload(
            appHome: "",
            managedReposRoot: "",
            availableAgents: [],
            availableCliAgents: [],
            recentCompanies: [],
            defaultLaunchMode: "company",
            backendSettings: DesktopBackendSettingsPayload(defaultBackendKind: "LOCAL_COTOR", codePublishMode: "REQUIRE_GITHUB_PR", backends: []),
            githubPublishStatus: GitHubPublishStatusPayload(
                policy: "REQUIRE_GITHUB_PR",
                ghInstalled: false,
                ghAuthenticated: false,
                originConfigured: false,
                originUrl: nil,
                bootstrapAvailable: false,
                repositoryPath: nil,
                companyId: nil,
                companyName: nil,
                message: nil
            ),
            linearSettings: nil,
            backendStatuses: [],
            shortcuts: ShortcutConfigPayload(bindings: [])
        ),
        companies: [],
        companyAgentDefinitions: [],
        projectContexts: [],
        goals: [],
        issues: [],
        reviewQueue: [],
        orgProfiles: [],
        workflowTopologies: [],
        goalDecisions: [],
        runningAgentSessions: [],
        backendStatuses: [],
        opsMetrics: OpsMetricSnapshotRecord(
            openGoals: 0,
            activeIssues: 0,
            blockedIssues: 0,
            readyToMergeCount: 0,
            mergedCount: 0,
            lastUpdatedAt: 0
        ),
        activity: [],
        companyRuntimes: []
    )
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

struct UpdateGoalPayload: Codable {
    let title: String?
    let description: String?
    let successMetrics: [String]?
    let autonomyEnabled: Bool?
}

struct CreateIssuePayload: Codable {
    let goalId: String
    let title: String
    let description: String
    let priority: Int
    let kind: String
}

struct CreateCompanyPayload: Codable {
    let name: String
    let rootPath: String
    let defaultBaseBranch: String?
    let autonomyEnabled: Bool
    let dailyBudgetCents: Int?
    let monthlyBudgetCents: Int?
}

struct UpdateCompanyPayload: Codable {
    let name: String?
    let defaultBaseBranch: String?
    let autonomyEnabled: Bool?
    let backendKind: String?
    let dailyBudgetCents: Int?
    let monthlyBudgetCents: Int?
}

struct UpdateCompanyLinearPayload: Codable {
    let enabled: Bool
    let endpoint: String?
    let apiToken: String?
    let teamId: String?
    let projectId: String?
    let stateMappings: [LinearStateMappingPayload]?
    let useGlobalDefault: Bool
}

struct LinearSyncResponsePayload: Codable, Hashable {
    let ok: Bool
    let message: String
    let syncedIssues: Int
    let createdIssues: Int
    let commentedIssues: Int
    let failedIssues: [String]
}

struct CreateCompanyAgentPayload: Codable {
    let title: String
    let agentCli: String
    let model: String?
    let roleSummary: String
    let specialties: [String]
    let collaborationInstructions: String?
    let preferredCollaboratorIds: [String]
    let memoryNotes: String?
    let enabled: Bool
}

struct UpdateCompanyAgentPayload: Codable {
    let title: String?
    let agentCli: String?
    let model: String?
    let roleSummary: String?
    let specialties: [String]?
    let collaborationInstructions: String?
    let preferredCollaboratorIds: [String]?
    let memoryNotes: String?
    let enabled: Bool?
    let displayOrder: Int?
}

struct BatchUpdateCompanyAgentsPayload: Codable {
    let agentIds: [String]
    let agentCli: String?
    let model: String?
    let specialties: [String]?
    let enabled: Bool?
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
@MainActor
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
            backendSettings: DesktopBackendSettingsPayload(
                defaultBackendKind: "LOCAL_COTOR",
                codePublishMode: "REQUIRE_GITHUB_PR",
                backends: []
            ),
            githubPublishStatus: GitHubPublishStatusPayload(
                policy: "REQUIRE_GITHUB_PR",
                ghInstalled: true,
                ghAuthenticated: true,
                originConfigured: true,
                originUrl: "https://github.com/heodongun/cotor.git",
                bootstrapAvailable: true,
                repositoryPath: "/Users/demo/cotor",
                companyId: "company-demo",
                companyName: "Cotor",
                message: "Origin remote is configured for this repository."
            ),
            linearSettings: nil,
            backendStatuses: [],
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
                backendKind: "LOCAL_COTOR",
                linearSyncEnabled: false,
                linearConfigOverride: nil,
                autonomyEnabled: true,
                dailyBudgetCents: 2500,
                monthlyBudgetCents: 20000,
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
                model: "claude-sonnet-4-20250514",
                roleSummary: "lead strategy, planning, triage, final merge",
                specialties: ["planning", "triage", "approval"],
                collaborationInstructions: "Break goals into issues and route work to the strongest specialists first.",
                preferredCollaboratorIds: ["agent-def-builder", "agent-def-qa"],
                memoryNotes: "Own final merge decisions.",
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
                model: "gpt-5.4",
                roleSummary: "implementation, integration, delivery",
                specialties: ["implementation", "integration"],
                collaborationInstructions: "Hand off risky or ambiguous work back to CEO and route finished work to QA.",
                preferredCollaboratorIds: ["agent-def-ceo", "agent-def-qa"],
                memoryNotes: "Keep branches narrowly scoped.",
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
                model: nil,
                roleSummary: "qa, review, verification",
                specialties: ["qa", "review", "verification"],
                collaborationInstructions: "Review implementation output and escalate blockers to CEO.",
                preferredCollaboratorIds: ["agent-def-builder", "agent-def-ceo"],
                memoryNotes: "Track regressions before merge.",
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
                followUpContext: nil,
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
                linearIssueIdentifier: nil,
                linearIssueUrl: nil,
                lastLinearSyncAt: nil,
                blockedBy: [],
                dependsOn: [],
                acceptanceCriteria: ["Goal decomposes into issues", "Roles are explicit"],
                riskLevel: "medium",
                codeProducing: false,
                executionIntent: nil,
                branchName: nil,
                worktreePath: nil,
                pullRequestNumber: nil,
                pullRequestUrl: nil,
                pullRequestState: nil,
                qaVerdict: nil,
                qaFeedback: nil,
                ceoVerdict: nil,
                ceoFeedback: nil,
                mergeResult: nil,
                transitionReason: nil,
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
                linearIssueIdentifier: nil,
                linearIssueUrl: nil,
                lastLinearSyncAt: nil,
                blockedBy: ["issue-demo-plan"],
                dependsOn: ["issue-demo-plan"],
                acceptanceCriteria: ["Goal sidebar exists", "Issue board is visible"],
                riskLevel: "medium",
                codeProducing: true,
                executionIntent: "CODE_CHANGE",
                branchName: "codex/cotor/demo-build",
                worktreePath: "/Users/demo/cotor/.cotor/worktrees/issue-demo-build/codex",
                pullRequestNumber: 77,
                pullRequestUrl: "https://github.com/example/cotor/pull/77",
                pullRequestState: "OPEN",
                qaVerdict: nil,
                qaFeedback: nil,
                ceoVerdict: nil,
                ceoFeedback: nil,
                mergeResult: nil,
                transitionReason: "Execution branch is active.",
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
                linearIssueIdentifier: nil,
                linearIssueUrl: nil,
                lastLinearSyncAt: nil,
                blockedBy: ["issue-demo-build"],
                dependsOn: ["issue-demo-build"],
                acceptanceCriteria: ["Review queue item appears", "Merge action is visible"],
                riskLevel: "low",
                codeProducing: false,
                executionIntent: nil,
                branchName: "codex/cotor/demo-build",
                worktreePath: "/Users/demo/cotor/.cotor/worktrees/issue-demo-build/codex",
                pullRequestNumber: 77,
                pullRequestUrl: "https://github.com/example/cotor/pull/77",
                pullRequestState: "OPEN",
                qaVerdict: "PASS",
                qaFeedback: "Checks and UX review passed.",
                ceoVerdict: nil,
                ceoFeedback: nil,
                mergeResult: nil,
                transitionReason: "QA is reviewing the execution PR.",
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
                branchName: "codex/cotor/superset-shell/codex",
                worktreePath: "/Users/demo/cotor/.cotor/worktrees/task-demo/codex",
                pullRequestNumber: 42,
                pullRequestUrl: "https://github.com/heodongun/cotor/pull/42",
                pullRequestState: "OPEN",
                status: "READY_TO_MERGE",
                checksSummary: "desktop smoke checks passed",
                mergeability: "clean",
                requestedReviewers: ["qa"],
                qaVerdict: nil,
                qaFeedback: nil,
                qaReviewedAt: nil,
                qaIssueId: nil,
                ceoVerdict: nil,
                ceoFeedback: nil,
                ceoReviewedAt: nil,
                approvalIssueId: nil,
                mergeCommitSha: nil,
                mergedAt: nil,
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
        workflowTopologies: [],
        goalDecisions: [],
        runningAgentSessions: [],
        backendStatuses: [],
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
                manuallyStoppedAt: nil,
                lastTickAt: 0,
                lastAction: "started:issue-demo-build",
                lastError: nil,
                backendKind: "LOCAL_COTOR",
                backendHealth: "healthy",
                backendMessage: "Using local Cotor app-server and CLI execution.",
                backendLifecycleState: "RUNNING",
                backendPid: nil,
                backendPort: 8787,
                todaySpentCents: 145,
                monthSpentCents: 1260,
                budgetPausedAt: nil,
                budgetResetDate: "2026-03-28"
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
            model: "claude-sonnet-4-20250514",
            backendKind: "LOCAL_COTOR",
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
            model: "gpt-5.4",
            backendKind: "LOCAL_COTOR",
            processId: 1002,
            output: "Added app-server APIs and worktree scaffolding.",
            error: nil,
            publish: PublishMetadataRecord(
                commitSha: "864046d9bb40e7b3d1f6d1d12a7e8f9a0b1c2d3e",
                pushedBranch: "codex/cotor/superset-shell/codex",
                pullRequestNumber: 42,
                pullRequestUrl: "https://github.com/heodongun/cotor/pull/42",
                pullRequestState: "OPEN",
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
