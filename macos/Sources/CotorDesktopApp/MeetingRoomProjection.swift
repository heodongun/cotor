import Foundation

enum MeetingRoomVisualState: String, Hashable {
    case idle
    case running
    case review
    case blocked
    case failed
    case done
    case costBlocked
}

enum MeetingRoomExpression: String, Hashable {
    case idle
    case focused
    case confused
    case sad
    case happy
    case warning
}

enum MeetingRoomOfficeZone: String, Hashable {
    case agentDesk
    case planningBoard
    case reviewDesk
    case blockerZone
    case costPanel
    case activityWall
    case mergeLane
}

enum MeetingRoomFlowKind: String, Hashable {
    case goalToIssue
    case issueToAgent
    case agentWorking
    case a2aMessage
    case agentToReview
    case reviewToMerge
    case blocked
    case costBlocked
}

struct MeetingRoomProjectionAgent: Identifiable, Hashable {
    let id: String
    let companyAgentId: String
    let name: String
    let role: String
    let cli: String
    let currentIssueId: String?
    let currentIssueTitle: String?
    let status: String
    let visualState: MeetingRoomVisualState
    let expression: MeetingRoomExpression
    let zone: MeetingRoomOfficeZone
    let actionLine: String
    let detailLine: String
    let progress: Double
    let messageCount: Int
    let pullRequestState: String?
}

struct MeetingRoomFlowItem: Identifiable, Hashable {
    let id: String
    let kind: MeetingRoomFlowKind
    let title: String
    let detail: String
    let issueId: String?
    let from: MeetingRoomOfficeZone
    let to: MeetingRoomOfficeZone
    let progress: Double
}

struct MeetingRoomIssueSummary: Identifiable, Hashable {
    let id: String
    let title: String
    let status: String
    let kind: String
    let assigneeProfileId: String?
    let pullRequestNumber: Int?
    let pullRequestUrl: String?
    let pullRequestState: String?
    let transitionReason: String?
}

struct MeetingRoomReviewSummary: Identifiable, Hashable {
    let id: String
    let issueId: String
    let status: String
    let branchName: String?
    let pullRequestNumber: Int?
    let pullRequestUrl: String?
    let pullRequestState: String?
    let checksSummary: String?
    let mergeability: String?
    let qaVerdict: String?
    let ceoVerdict: String?
}

struct MeetingRoomProjection: Hashable {
    static let maxIssueSummaries = 120
    static let maxReviewSummaries = 60

    let companyId: String?
    let agents: [MeetingRoomProjectionAgent]
    let flows: [MeetingRoomFlowItem]
    let issues: [MeetingRoomIssueSummary]
    let reviews: [MeetingRoomReviewSummary]
    let runtimeStatus: String
    let runtimeBackendHealth: String
    let todaySpentCents: Int
    let monthSpentCents: Int
    let isCostBlocked: Bool
    let activeIssueCount: Int
    let blockedIssueCount: Int
    let reviewCount: Int
    let activityCount: Int
    let pullRequestStates: [String]

    static func runtime(
        for companyId: String?,
        in runtimes: [CompanyRuntimeSnapshotRecord]
    ) -> CompanyRuntimeSnapshotRecord? {
        guard let companyId else { return nil }
        return runtimes.first { $0.companyId == companyId }
    }

    static func build(
        companyId: String?,
        agents allAgents: [CompanyAgentDefinitionRecord],
        goals allGoals: [GoalRecord] = [],
        orgProfiles allOrgProfiles: [OrgAgentProfileRecord] = [],
        issues allIssues: [IssueRecord],
        runningSessions allRunningSessions: [RunningAgentSessionRecord],
        reviewQueue allReviewQueue: [ReviewQueueItemRecord],
        runtime: CompanyRuntimeSnapshotRecord?,
        activity allActivity: [CompanyActivityItemRecord],
        messages allMessages: [AgentMessageRecord]
    ) -> MeetingRoomProjection {
        let scopedAgents = scoped(allAgents, companyId: companyId)
            .sorted { $0.displayOrder < $1.displayOrder }
        let scopedGoals = scoped(allGoals, companyId: companyId)
        let scopedProfiles = scoped(allOrgProfiles, companyId: companyId)
        let scopedIssues = scoped(allIssues, companyId: companyId)
        let scopedSessions = scoped(allRunningSessions, companyId: companyId)
        let scopedReviews = scoped(allReviewQueue, companyId: companyId)
        let scopedActivity = scoped(allActivity, companyId: companyId)
        let scopedMessages = scoped(allMessages, companyId: companyId)
        let runtime = runtime ?? CompanyRuntimeSnapshotRecord(companyId: companyId)
        let issuesById = Dictionary(uniqueKeysWithValues: scopedIssues.map { ($0.id, $0) })
        let latestIssueByAssignee = Dictionary(
            grouping: scopedIssues.filter { $0.assigneeProfileId != nil },
            by: { $0.assigneeProfileId ?? "" }
        ).compactMapValues { issues in
            issues.sorted { $0.updatedAt > $1.updatedAt }.first
        }
        let latestIssueByNormalizedAssignee = Dictionary(
            grouping: scopedIssues.filter { $0.assigneeProfileId != nil },
            by: { ($0.assigneeProfileId ?? "").normalizedMeetingRoomKey }
        ).compactMapValues { issues in
            issues.sorted { $0.updatedAt > $1.updatedAt }.first
        }
        let profileByRole = Dictionary(
            scopedProfiles.map { ($0.roleName.normalizedMeetingRoomKey, $0) },
            uniquingKeysWith: { first, _ in first }
        )
        let sessionByAgentId = Dictionary(scopedSessions.map { ($0.agentId, $0) }, uniquingKeysWith: { first, _ in first })
        let sessionByAgentName = Dictionary(scopedSessions.map { ($0.agentName.lowercased(), $0) }, uniquingKeysWith: { first, _ in first })
        let messageCountByAgentKey = messageCountMap(messages: scopedMessages)
        let hasReviewWork = !scopedReviews.isEmpty
        let latestReviewIssue = scopedReviews
            .sorted { $0.updatedAt > $1.updatedAt }
            .lazy
            .compactMap { issuesById[$0.issueId] }
            .first
        let isCostBlocked = runtime.isBudgetPaused

        let projectedAgents = scopedAgents.map { agent in
            let profile = profileByRole[agent.title.normalizedMeetingRoomKey]
                ?? profileByRole[agent.agentCli.normalizedMeetingRoomKey]
            let session = sessionByAgentId[agent.id]
                ?? sessionByAgentName[agent.title.lowercased()]
                ?? sessionByAgentName[agent.agentCli.lowercased()]
            let assignmentKeys = [agent.id, agent.title, agent.agentCli, profile?.id, profile?.roleName, profile?.executionAgentName]
                .compactMap { $0 }
            let assignedIssue = assignmentKeys.lazy.compactMap { latestIssueByAssignee[$0] }.first
                ?? assignmentKeys.lazy.compactMap { latestIssueByNormalizedAssignee[$0.normalizedMeetingRoomKey] }.first
            let issue = session.flatMap { $0.issueId }.flatMap { issuesById[$0] } ?? assignedIssue ?? reviewIssue(for: agent, latestReviewIssue: latestReviewIssue)
            let messageCount = Set([agent.agentCli.normalizedMeetingRoomKey, agent.title.normalizedMeetingRoomKey])
                .reduce(0) { count, key in count + (messageCountByAgentKey[key] ?? 0) }

            let visualState = visualState(
                agent: agent,
                session: session,
                issue: issue,
                hasReviewWork: hasReviewWork,
                isCostBlocked: isCostBlocked
            )
            return MeetingRoomProjectionAgent(
                id: agent.id,
                companyAgentId: agent.id,
                name: agent.title,
                role: agent.title,
                cli: agent.agentCli,
                currentIssueId: issue?.id ?? session?.issueId,
                currentIssueTitle: issue?.title,
                status: session?.status ?? issue?.status ?? (agent.enabled ? "IDLE" : "PAUSED"),
                visualState: visualState,
                expression: expression(for: visualState),
                zone: zone(for: visualState),
                actionLine: actionLine(for: visualState, role: agent.title),
                detailLine: detailLine(agent: agent, session: session, issue: issue, reviewCount: scopedReviews.count, runtime: runtime),
                progress: progress(for: visualState, session: session, issue: issue, enabled: agent.enabled),
                messageCount: messageCount,
                pullRequestState: issue?.pullRequestState
            )
        }

        let flows = buildFlows(
            goals: scopedGoals,
            issues: scopedIssues,
            runningSessions: scopedSessions,
            reviewQueue: scopedReviews,
            messages: scopedMessages,
            runtime: runtime
        )
        let issueSummaries = scopedIssues
            .sorted { $0.updatedAt > $1.updatedAt }
            .prefix(maxIssueSummaries)
            .map(MeetingRoomIssueSummary.init(issue:))
        let reviewSummaries = scopedReviews
            .sorted { $0.updatedAt > $1.updatedAt }
            .prefix(maxReviewSummaries)
            .map(MeetingRoomReviewSummary.init(review:))

        return MeetingRoomProjection(
            companyId: companyId,
            agents: projectedAgents,
            flows: flows,
            issues: issueSummaries,
            reviews: reviewSummaries,
            runtimeStatus: runtime.status,
            runtimeBackendHealth: runtime.backendHealth,
            todaySpentCents: runtime.todaySpentCents,
            monthSpentCents: runtime.monthSpentCents,
            isCostBlocked: isCostBlocked,
            activeIssueCount: scopedIssues.filter { !terminalStatuses.contains($0.status.uppercased()) }.count,
            blockedIssueCount: scopedIssues.filter { blockedStatuses.contains($0.status.uppercased()) }.count,
            reviewCount: scopedReviews.count,
            activityCount: scopedActivity.count,
            pullRequestStates: scopedIssues.compactMap(\.pullRequestState)
        )
    }

    private static let terminalStatuses: Set<String> = ["DONE", "MERGED", "CLOSED", "CANCELLED", "CANCELED"]
    private static let blockedStatuses: Set<String> = ["BLOCKED", "FAILED", "CHANGES_REQUESTED"]

    private static func messageCountMap(messages: [AgentMessageRecord]) -> [String: Int] {
        var counts: [String: Int] = [:]
        for message in messages {
            counts[message.fromAgentName.normalizedMeetingRoomKey, default: 0] += 1
            if let toAgentName = message.toAgentName {
                counts[toAgentName.normalizedMeetingRoomKey, default: 0] += 1
            }
        }
        return counts
    }

    private static func scoped<T>(_ values: [T], companyId: String?) -> [T] {
        guard let companyId else { return values }
        return values.filter { value in
            switch value {
            case let agent as CompanyAgentDefinitionRecord:
                return agent.companyId == companyId
            case let goal as GoalRecord:
                return goal.companyId == companyId
            case let profile as OrgAgentProfileRecord:
                return profile.companyId == companyId
            case let issue as IssueRecord:
                return issue.companyId == companyId
            case let session as RunningAgentSessionRecord:
                return session.companyId == companyId
            case let review as ReviewQueueItemRecord:
                return review.companyId == companyId
            case let activity as CompanyActivityItemRecord:
                return activity.companyId == companyId
            case let message as AgentMessageRecord:
                return message.companyId == companyId
            default:
                return true
            }
        }
    }

    private static func reviewIssue(
        for agent: CompanyAgentDefinitionRecord,
        latestReviewIssue: IssueRecord?
    ) -> IssueRecord? {
        let role = agent.title.lowercased()
        guard role.contains("qa") || role.contains("review") || role.contains("ceo") || role.contains("approval") else {
            return nil
        }
        return latestReviewIssue
    }

    private static func visualState(
        agent: CompanyAgentDefinitionRecord,
        session: RunningAgentSessionRecord?,
        issue: IssueRecord?,
        hasReviewWork: Bool,
        isCostBlocked: Bool
    ) -> MeetingRoomVisualState {
        if isCostBlocked {
            return .costBlocked
        }
        if let session {
            let status = session.status.uppercased()
            if status.contains("FAIL") || status.contains("ERROR") {
                return .failed
            }
            if status.contains("BLOCK") {
                return .blocked
            }
            if status.contains("DONE") || status.contains("COMPLETE") || status.contains("SUCCESS") {
                return .done
            }
            return .running
        }
        if let issue {
            let status = issue.status.uppercased()
            if status == "DONE" || issue.mergeResult?.uppercased() == "MERGED" || issue.pullRequestState?.uppercased() == "MERGED" {
                return .done
            }
            if status.contains("FAIL") {
                return .failed
            }
            if status.contains("BLOCK") || status == "CHANGES_REQUESTED" {
                return .blocked
            }
            if status.contains("REVIEW") || issue.qaVerdict != nil || issue.ceoVerdict != nil {
                return .review
            }
        }
        let role = agent.title.lowercased()
        if hasReviewWork && (role.contains("qa") || role.contains("review") || role.contains("ceo") || role.contains("approval")) {
            return .review
        }
        return .idle
    }

    private static func expression(for state: MeetingRoomVisualState) -> MeetingRoomExpression {
        switch state {
        case .idle:
            return .idle
        case .running:
            return .focused
        case .review:
            return .confused
        case .blocked:
            return .confused
        case .failed:
            return .sad
        case .done:
            return .happy
        case .costBlocked:
            return .warning
        }
    }

    private static func zone(for state: MeetingRoomVisualState) -> MeetingRoomOfficeZone {
        switch state {
        case .idle, .running, .done:
            return .agentDesk
        case .review:
            return .reviewDesk
        case .blocked, .failed:
            return .blockerZone
        case .costBlocked:
            return .costPanel
        }
    }

    private static func actionLine(for state: MeetingRoomVisualState, role: String) -> String {
        switch state {
        case .idle:
            return "ready at desk"
        case .running:
            return "typing on assigned work"
        case .review:
            return role.lowercased().contains("ceo") ? "checking approval" : "reviewing work"
        case .blocked:
            return "blocked, needs help"
        case .failed:
            return "failed, reading logs"
        case .done:
            return "done, handing off"
        case .costBlocked:
            return "paused by cost guardrail"
        }
    }

    private static func detailLine(
        agent: CompanyAgentDefinitionRecord,
        session: RunningAgentSessionRecord?,
        issue: IssueRecord?,
        reviewCount: Int,
        runtime: CompanyRuntimeSnapshotRecord
    ) -> String {
        if runtime.isBudgetPaused {
            return "Budget paused after \(runtime.todaySpentCents)c today."
        }
        if let session {
            return "\(session.status) · \(issue?.title ?? session.branchName)"
        }
        if let issue {
            return "\(issue.status) · \(issue.title)"
        }
        if reviewCount > 0 && agent.title.lowercased().contains("qa") {
            return "\(reviewCount) review item(s) waiting."
        }
        return agent.roleSummary
    }

    private static func progress(
        for state: MeetingRoomVisualState,
        session: RunningAgentSessionRecord?,
        issue: IssueRecord?,
        enabled: Bool
    ) -> Double {
        guard enabled else { return 0.05 }
        switch state {
        case .idle:
            return 0.12
        case .running:
            return session == nil ? 0.45 : 0.62
        case .review:
            return 0.74
        case .blocked:
            return 0.22
        case .failed:
            return 0.18
        case .done:
            return 1.0
        case .costBlocked:
            return 0.08
        }
    }

    private static func buildFlows(
        goals: [GoalRecord],
        issues: [IssueRecord],
        runningSessions: [RunningAgentSessionRecord],
        reviewQueue: [ReviewQueueItemRecord],
        messages: [AgentMessageRecord],
        runtime: CompanyRuntimeSnapshotRecord
    ) -> [MeetingRoomFlowItem] {
        var flows: [MeetingRoomFlowItem] = []
        let goalsById = Dictionary(uniqueKeysWithValues: goals.map { ($0.id, $0) })
        let recentIssues = Array(issues.sorted(by: { $0.updatedAt > $1.updatedAt }).prefix(5))

        for issue in recentIssues.prefix(4) {
            guard let goal = goalsById[issue.goalId] else { continue }
            flows.append(
                MeetingRoomFlowItem(
                    id: "goal-\(goal.id)-issue-\(issue.id)",
                    kind: .goalToIssue,
                    title: goal.title,
                    detail: "Decomposed into: \(issue.title)",
                    issueId: issue.id,
                    from: .activityWall,
                    to: .planningBoard,
                    progress: 0.28
                )
            )
        }

        for issue in recentIssues {
            let status = issue.status.uppercased()
            if runtime.isBudgetPaused {
                flows.append(
                    MeetingRoomFlowItem(
                        id: "cost-\(issue.id)",
                        kind: .costBlocked,
                        title: issue.title,
                        detail: "Cost guardrail paused runtime.",
                        issueId: issue.id,
                        from: .costPanel,
                        to: .blockerZone,
                        progress: 0.12
                    )
                )
            } else if status.contains("BLOCK") || status.contains("FAIL") {
                flows.append(
                    MeetingRoomFlowItem(
                        id: "block-\(issue.id)",
                        kind: .blocked,
                        title: issue.title,
                        detail: issue.transitionReason ?? issue.providerBlockReasonFallback,
                        issueId: issue.id,
                        from: .agentDesk,
                        to: .blockerZone,
                        progress: 0.25
                    )
                )
            } else if status.contains("REVIEW") || issue.qaVerdict != nil || issue.ceoVerdict != nil {
                flows.append(
                    MeetingRoomFlowItem(
                        id: "review-\(issue.id)",
                        kind: .agentToReview,
                        title: issue.title,
                        detail: issue.pullRequestState ?? "review queue",
                        issueId: issue.id,
                        from: .agentDesk,
                        to: .reviewDesk,
                        progress: 0.72
                    )
                )
            } else if status == "DONE" || issue.pullRequestState?.uppercased() == "MERGED" || issue.mergeResult?.uppercased() == "MERGED" {
                flows.append(
                    MeetingRoomFlowItem(
                        id: "merge-\(issue.id)",
                        kind: .reviewToMerge,
                        title: issue.title,
                        detail: issue.pullRequestState ?? issue.mergeResult ?? "done",
                        issueId: issue.id,
                        from: .reviewDesk,
                        to: .mergeLane,
                        progress: 1.0
                    )
                )
            } else if status.contains("PLAN") || status.contains("BACKLOG") {
                flows.append(
                    MeetingRoomFlowItem(
                        id: "assign-\(issue.id)",
                        kind: .issueToAgent,
                        title: issue.title,
                        detail: "Issue is ready to dispatch.",
                        issueId: issue.id,
                        from: .planningBoard,
                        to: .agentDesk,
                        progress: 0.35
                    )
                )
            }
        }

        for session in runningSessions.sorted(by: { $0.updatedAt > $1.updatedAt }).prefix(4) {
            flows.append(
                MeetingRoomFlowItem(
                    id: "run-\(session.runId)",
                    kind: .agentWorking,
                    title: session.agentName,
                    detail: session.status,
                    issueId: session.issueId,
                    from: .planningBoard,
                    to: .agentDesk,
                    progress: 0.62
                )
            )
        }

        for message in messages.sorted(by: { $0.createdAt > $1.createdAt }).prefix(4) {
            let target = message.toAgentName ?? "room"
            flows.append(
                MeetingRoomFlowItem(
                    id: "a2a-\(message.id)",
                    kind: .a2aMessage,
                    title: "\(message.fromAgentName) → \(target)",
                    detail: message.body,
                    issueId: message.issueId,
                    from: .agentDesk,
                    to: message.kind.lowercased().contains("escalation") ? .blockerZone : .activityWall,
                    progress: 0.58
                )
            )
        }

        for review in reviewQueue.sorted(by: { $0.updatedAt > $1.updatedAt }).prefix(4) {
            flows.append(
                MeetingRoomFlowItem(
                    id: "queue-\(review.id)",
                    kind: review.pullRequestState?.uppercased() == "MERGED" ? .reviewToMerge : .agentToReview,
                    title: review.pullRequestUrl ?? review.branchName ?? review.issueId,
                    detail: review.status,
                    issueId: review.issueId,
                    from: .agentDesk,
                    to: review.pullRequestState?.uppercased() == "MERGED" ? .mergeLane : .reviewDesk,
                    progress: review.pullRequestState?.uppercased() == "MERGED" ? 1.0 : 0.78
                )
            )
        }

        return Array(flows.prefix(10))
    }
}

private extension MeetingRoomIssueSummary {
    init(issue: IssueRecord) {
        self.init(
            id: issue.id,
            title: issue.title,
            status: issue.status,
            kind: issue.kind,
            assigneeProfileId: issue.assigneeProfileId,
            pullRequestNumber: issue.pullRequestNumber,
            pullRequestUrl: issue.pullRequestUrl,
            pullRequestState: issue.pullRequestState,
            transitionReason: issue.transitionReason
        )
    }
}

private extension MeetingRoomReviewSummary {
    init(review: ReviewQueueItemRecord) {
        self.init(
            id: review.id,
            issueId: review.issueId,
            status: review.status,
            branchName: review.branchName,
            pullRequestNumber: review.pullRequestNumber,
            pullRequestUrl: review.pullRequestUrl,
            pullRequestState: review.pullRequestState,
            checksSummary: review.checksSummary,
            mergeability: review.mergeability,
            qaVerdict: review.qaVerdict,
            ceoVerdict: review.ceoVerdict
        )
    }
}

private extension String {
    var normalizedMeetingRoomKey: String {
        trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }
}

private extension IssueRecord {
    var providerBlockReasonFallback: String {
        transitionReason ?? pullRequestState ?? "Blocked issue"
    }
}
