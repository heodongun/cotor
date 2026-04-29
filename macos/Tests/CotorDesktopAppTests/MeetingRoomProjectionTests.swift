import Testing
@testable import CotorDesktopApp

struct MeetingRoomProjectionTests {
    @Test
    func agentCountMatchesActualCompanyAgents() {
        let projection = MeetingRoomProjection.build(
            companyId: "company",
            agents: [
                agent(id: "ceo", title: "CEO"),
                agent(id: "builder", title: "Builder"),
                agent(id: "qa", title: "QA"),
            ],
            issues: [],
            runningSessions: [],
            reviewQueue: [],
            runtime: runtime(),
            activity: [],
            messages: []
        )

        #expect(projection.agents.count == 3)
        #expect(projection.agents.map(\.role) == ["CEO", "Builder", "QA"])
    }

    @Test
    func runningSessionMapsAgentToFocusedRunningState() {
        let projection = MeetingRoomProjection.build(
            companyId: "company",
            agents: [agent(id: "builder", title: "Builder")],
            goals: [goal(id: "goal", title: "Ship feature")],
            orgProfiles: [profile(id: "builder-profile", roleName: "Builder", executionAgentName: "opencode")],
            issues: [issue(id: "issue-1", title: "Build feature", status: "IN_PROGRESS", assigneeProfileId: "builder")],
            runningSessions: [
                session(agentId: "builder", agentName: "opencode", issueId: "issue-1", status: "RUNNING")
            ],
            reviewQueue: [],
            runtime: runtime(status: "RUNNING"),
            activity: [],
            messages: []
        )

        let builder = projection.agents[0]
        #expect(builder.visualState == .running)
        #expect(builder.expression == .focused)
        #expect(builder.zone == .agentDesk)
        #expect(builder.currentIssueTitle == "Build feature")
        #expect(projection.flows.contains { $0.kind == .goalToIssue && $0.title == "Ship feature" })
        #expect(projection.flows.contains { $0.kind == .agentWorking })
    }

    @Test
    func orgProfileAssignmentMapsIssueToCompanyAgent() {
        let projection = MeetingRoomProjection.build(
            companyId: "company",
            agents: [agent(id: "company-builder", title: "Builder")],
            orgProfiles: [profile(id: "org-builder-profile", roleName: "Builder", executionAgentName: "opencode")],
            issues: [
                issue(id: "issue-1", title: "Profile assigned work", status: "IN_PROGRESS", assigneeProfileId: "org-builder-profile")
            ],
            runningSessions: [],
            reviewQueue: [],
            runtime: runtime(),
            activity: [],
            messages: []
        )

        let builder = projection.agents[0]
        #expect(builder.currentIssueTitle == "Profile assigned work")
        #expect(builder.visualState == .idle)
        #expect(builder.detailLine.contains("Profile assigned work"))
    }

    @Test
    func reviewQueueMovesReviewerToReviewDeskAndCreatesReviewFlow() throws {
        let projection = MeetingRoomProjection.build(
            companyId: "company",
            agents: [
                agent(id: "builder", title: "Builder"),
                agent(id: "qa", title: "QA"),
            ],
            issues: [
                issue(id: "issue-1", title: "Review me", status: "IN_REVIEW", assigneeProfileId: "builder", pullRequestState: "OPEN")
            ],
            runningSessions: [],
            reviewQueue: [
                review(issueId: "issue-1", status: "READY_FOR_QA", pullRequestState: "OPEN")
            ],
            runtime: runtime(),
            activity: [],
            messages: []
        )

        let qa = try #require(projection.agents.first { $0.role == "QA" })
        #expect(qa.visualState == .review)
        #expect(qa.zone == .reviewDesk)
        #expect(projection.reviewCount == 1)
        #expect(projection.flows.contains { $0.kind == .agentToReview && $0.to == .reviewDesk })
    }

    @Test
    func actualA2AMessagesBecomeVisibleOfficeFlows() throws {
        let projection = MeetingRoomProjection.build(
            companyId: "company",
            agents: [
                agent(id: "builder", title: "Builder"),
                agent(id: "qa", title: "QA"),
            ],
            issues: [
                issue(id: "issue-1", title: "Coordinate work", status: "IN_PROGRESS", assigneeProfileId: "builder")
            ],
            runningSessions: [],
            reviewQueue: [],
            runtime: runtime(status: "RUNNING"),
            activity: [],
            messages: [
                message(
                    id: "msg-1",
                    from: "Builder",
                    to: "QA",
                    issueId: "issue-1",
                    body: "Please review the validation notes."
                )
            ]
        )

        let a2a = try #require(projection.flows.first { $0.kind == .a2aMessage })
        #expect(a2a.title == "Builder → QA")
        #expect(a2a.issueId == "issue-1")
        #expect(projection.agents.first { $0.role == "Builder" }?.messageCount == 1)
        #expect(projection.agents.first { $0.role == "QA" }?.messageCount == 1)
    }


    @Test
    func projectionCarriesActualIssueAndReviewSummariesForClickDetails() throws {
        let projection = MeetingRoomProjection.build(
            companyId: "company",
            agents: [agent(id: "qa", title: "QA")],
            issues: [
                issue(
                    id: "issue-1",
                    title: "Review actual PR",
                    status: "IN_REVIEW",
                    assigneeProfileId: "qa",
                    pullRequestState: "OPEN"
                )
            ],
            runningSessions: [],
            reviewQueue: [
                review(
                    issueId: "issue-1",
                    status: "READY_FOR_QA",
                    pullRequestState: "OPEN",
                    checksSummary: "PASSING",
                    mergeability: "CLEAN",
                    qaVerdict: "PASS"
                )
            ],
            runtime: runtime(),
            activity: [],
            messages: []
        )

        let issue = try #require(projection.issues.first)
        let review = try #require(projection.reviews.first)
        #expect(issue.title == "Review actual PR")
        #expect(issue.pullRequestNumber == 1)
        #expect(issue.pullRequestState == "OPEN")
        #expect(review.issueId == "issue-1")
        #expect(review.checksSummary == "PASSING")
        #expect(review.mergeability == "CLEAN")
        #expect(review.qaVerdict == "PASS")
    }

    @Test
    func blockedFailedDoneAndCostStatesAreProjectedFromSnapshot() {
        let baseAgents = [
            agent(id: "blocked", title: "Blocked Builder"),
            agent(id: "failed", title: "Failed Builder"),
            agent(id: "done", title: "Done Builder"),
        ]
        let blockedProjection = MeetingRoomProjection.build(
            companyId: "company",
            agents: baseAgents,
            issues: [
                issue(id: "blocked-issue", title: "Blocked", status: "BLOCKED", assigneeProfileId: "blocked"),
                issue(id: "failed-issue", title: "Failed", status: "FAILED", assigneeProfileId: "failed"),
                issue(id: "done-issue", title: "Done", status: "DONE", assigneeProfileId: "done", pullRequestState: "MERGED", mergeResult: "MERGED"),
            ],
            runningSessions: [],
            reviewQueue: [],
            runtime: runtime(),
            activity: [],
            messages: []
        )

        #expect(blockedProjection.agents.first { $0.id == "blocked" }?.visualState == .blocked)
        #expect(blockedProjection.agents.first { $0.id == "failed" }?.visualState == .failed)
        #expect(blockedProjection.agents.first { $0.id == "done" }?.visualState == .done)
        #expect(blockedProjection.flows.contains { $0.kind == .blocked })
        #expect(blockedProjection.flows.contains { $0.kind == .reviewToMerge })

        let costProjection = MeetingRoomProjection.build(
            companyId: "company",
            agents: baseAgents,
            issues: [],
            runningSessions: [],
            reviewQueue: [],
            runtime: runtime(budgetPausedAt: 100),
            activity: [],
            messages: []
        )

        #expect(costProjection.isCostBlocked)
        #expect(costProjection.agents.allSatisfy { $0.visualState == .costBlocked })
    }

    @Test
    func reconnectSnapshotRebuildPreservesProjectedRuntimeState() {
        let agents = [agent(id: "builder", title: "Builder")]
        let issues = [issue(id: "issue-1", title: "Still running", status: "IN_PROGRESS", assigneeProfileId: "builder")]
        let sessions = [session(agentId: "builder", agentName: "opencode", issueId: "issue-1", status: "RUNNING")]
        let snapshotA = MeetingRoomProjection.build(
            companyId: "company",
            agents: agents,
            issues: issues,
            runningSessions: sessions,
            reviewQueue: [],
            runtime: runtime(status: "RUNNING"),
            activity: [],
            messages: []
        )
        let snapshotB = MeetingRoomProjection.build(
            companyId: "company",
            agents: agents,
            issues: issues,
            runningSessions: sessions,
            reviewQueue: [],
            runtime: runtime(status: "RUNNING"),
            activity: [],
            messages: []
        )

        #expect(snapshotA == snapshotB)
        #expect(snapshotB.agents.first?.visualState == .running)
    }

    @Test
    func nilCompanyRuntimeSelectionDoesNotBorrowFirstCompanyRuntime() {
        let selectedRuntime = MeetingRoomProjection.runtime(
            for: nil,
            in: [runtime(companyId: "company-a", status: "RUNNING", budgetPausedAt: 100)]
        )
        let projection = MeetingRoomProjection.build(
            companyId: nil,
            agents: [],
            issues: [],
            runningSessions: [],
            reviewQueue: [],
            runtime: selectedRuntime,
            activity: [],
            messages: []
        )

        #expect(selectedRuntime == nil)
        #expect(projection.runtimeStatus == "STOPPED")
        #expect(projection.todaySpentCents == 0)
        #expect(!projection.isCostBlocked)
    }

    @Test
    func runtimeSelectionScopesToSelectedCompany() throws {
        let selectedRuntime = try #require(MeetingRoomProjection.runtime(
            for: "company-b",
            in: [
                runtime(companyId: "company-a", status: "STOPPED"),
                runtime(companyId: "company-b", status: "RUNNING"),
            ]
        ))

        #expect(selectedRuntime.companyId == "company-b")
        #expect(selectedRuntime.status == "RUNNING")
    }

    @Test
    func projectionCapsLargeIssueAndReviewDetailsForMeetingRoomSnapshotBudget() {
        let issues = (0..<250).map { index in
            issue(
                id: "issue-\(index)",
                title: "Issue \(index)",
                status: index.isMultiple(of: 4) ? "IN_REVIEW" : "PLANNED",
                assigneeProfileId: "builder",
                pullRequestState: index.isMultiple(of: 4) ? "OPEN" : nil
            )
        }
        let reviews = (0..<120).map { index in
            review(issueId: "issue-\(index)", status: "READY_FOR_QA", pullRequestState: "OPEN")
        }
        let projection = MeetingRoomProjection.build(
            companyId: "company",
            agents: [agent(id: "builder", title: "Builder")],
            issues: issues,
            runningSessions: [],
            reviewQueue: reviews,
            runtime: runtime(),
            activity: [],
            messages: []
        )

        #expect(projection.issues.count == MeetingRoomProjection.maxIssueSummaries)
        #expect(projection.reviews.count == MeetingRoomProjection.maxReviewSummaries)
        #expect(projection.flows.count <= 10)
        #expect(projection.activeIssueCount == 250)
        #expect(projection.reviewCount == 120)
    }

    @Test
    func renderPlanGroupsManyAgentsAndDisablesAnimationForLowResourceMode() {
        let agents = (0..<55).map { index in
            agent(id: "agent-\(index)", title: index == 1 ? "Builder" : "Agent \(index)")
        }
        let projection = MeetingRoomProjection.build(
            companyId: "company",
            agents: agents,
            issues: [issue(id: "issue-1", title: "Running", status: "IN_PROGRESS", assigneeProfileId: "Builder")],
            runningSessions: [session(agentId: "agent-1", agentName: "opencode", issueId: "issue-1", status: "RUNNING")],
            reviewQueue: [],
            runtime: runtime(status: "RUNNING"),
            activity: [],
            messages: []
        )

        let grouped = MeetingRoomRenderPlan.build(
            projection: projection,
            isCompact: false,
            reduceMotion: false,
            lowResourceMode: false,
            isSceneActive: true
        )
        let reduced = MeetingRoomRenderPlan.build(
            projection: projection,
            isCompact: false,
            reduceMotion: true,
            lowResourceMode: false,
            isSceneActive: true
        )

        #expect(grouped.mode == .grouped)
        #expect(grouped.visibleAgents.count == 12)
        #expect(grouped.hiddenAgentCount == 43)
        #expect(!grouped.shouldAnimate)
        #expect(reduced.mode == .grouped)
        #expect(!reduced.shouldAnimate)
    }

    @Test
    func renderPlanAnimatesOnlyActualRuntimeMotion() {
        let idleProjection = MeetingRoomProjection.build(
            companyId: "company",
            agents: [agent(id: "idle-builder", title: "Builder")],
            issues: [],
            runningSessions: [],
            reviewQueue: [],
            runtime: runtime(),
            activity: [],
            messages: []
        )
        let idlePlan = MeetingRoomRenderPlan.build(
            projection: idleProjection,
            isCompact: false,
            reduceMotion: false,
            lowResourceMode: false,
            isSceneActive: true
        )
        let blockedProjection = MeetingRoomProjection.build(
            companyId: "company",
            agents: [agent(id: "blocked-builder", title: "Builder")],
            issues: [issue(id: "issue-blocked", title: "Blocked work", status: "BLOCKED", assigneeProfileId: "Builder")],
            runningSessions: [],
            reviewQueue: [],
            runtime: runtime(status: "RUNNING"),
            activity: [],
            messages: []
        )
        let blockedPlan = MeetingRoomRenderPlan.build(
            projection: blockedProjection,
            isCompact: false,
            reduceMotion: false,
            lowResourceMode: false,
            isSceneActive: true
        )

        let activeProjection = MeetingRoomProjection.build(
            companyId: "company",
            agents: [agent(id: "active-builder", title: "Builder")],
            issues: [issue(id: "issue-active", title: "Active work", status: "IN_PROGRESS", assigneeProfileId: "Builder")],
            runningSessions: [session(agentId: "active-builder", agentName: "opencode", issueId: "issue-active", status: "RUNNING")],
            reviewQueue: [],
            runtime: runtime(status: "RUNNING"),
            activity: [],
            messages: []
        )
        let activePlan = MeetingRoomRenderPlan.build(
            projection: activeProjection,
            isCompact: false,
            reduceMotion: false,
            lowResourceMode: false,
            isSceneActive: true
        )

        #expect(!idlePlan.shouldAnimate)
        #expect(idlePlan.frameInterval == 1.0)
        #expect(!blockedPlan.shouldAnimate)
        #expect(blockedPlan.frameInterval == 1.0)
        #expect(activePlan.shouldAnimate)
        #expect(activePlan.frameInterval == 1.0 / 15.0)
    }
}

private func agent(id: String, title: String) -> CompanyAgentDefinitionRecord {
    CompanyAgentDefinitionRecord(
        id: id,
        companyId: "company",
        title: title,
        agentCli: "opencode",
        model: "opencode/nemotron-3-super-free",
        roleSummary: "\(title) role",
        specialties: [],
        collaborationInstructions: nil,
        preferredCollaboratorIds: [],
        memoryNotes: nil,
        enabled: true,
        displayOrder: id == "ceo" ? 0 : 1,
        createdAt: 0,
        updatedAt: 0
    )
}

private func goal(id: String, title: String) -> GoalRecord {
    GoalRecord(
        id: id,
        companyId: "company",
        projectContextId: nil,
        title: title,
        description: title,
        status: "ACTIVE",
        priority: 1,
        successMetrics: [],
        operatingPolicy: nil,
        followUpContext: nil,
        autonomyEnabled: true,
        createdAt: 0,
        updatedAt: 1
    )
}

private func profile(id: String, roleName: String, executionAgentName: String) -> OrgAgentProfileRecord {
    OrgAgentProfileRecord(
        id: id,
        companyId: "company",
        roleName: roleName,
        executionAgentName: executionAgentName,
        capabilities: [],
        linearAssigneeId: nil,
        reviewerPolicy: nil,
        mergeAuthority: roleName.lowercased().contains("ceo"),
        enabled: true
    )
}

private func issue(
    id: String,
    title: String,
    status: String,
    assigneeProfileId: String? = nil,
    pullRequestState: String? = nil,
    mergeResult: String? = nil
) -> IssueRecord {
    IssueRecord(
        id: id,
        companyId: "company",
        projectContextId: nil,
        goalId: "goal",
        workspaceId: "workspace",
        title: title,
        description: title,
        status: status,
        priority: 2,
        kind: "execution",
        assigneeProfileId: assigneeProfileId,
        linearIssueId: nil,
        linearIssueIdentifier: nil,
        linearIssueUrl: nil,
        lastLinearSyncAt: nil,
        blockedBy: [],
        dependsOn: [],
        acceptanceCriteria: [],
        riskLevel: "medium",
        codeProducing: true,
        executionIntent: "CODE_CHANGE",
        branchName: nil,
        worktreePath: nil,
        pullRequestNumber: pullRequestState == nil ? nil : 1,
        pullRequestUrl: pullRequestState == nil ? nil : "https://github.com/example/repo/pull/1",
        pullRequestState: pullRequestState,
        qaVerdict: nil,
        qaFeedback: nil,
        ceoVerdict: nil,
        ceoFeedback: nil,
        mergeResult: mergeResult,
        transitionReason: nil,
        sourceSignal: "test",
        createdAt: 0,
        updatedAt: 1
    )
}

private func session(agentId: String, agentName: String, issueId: String?, status: String) -> RunningAgentSessionRecord {
    RunningAgentSessionRecord(
        companyId: "company",
        runId: "run-\(agentId)",
        taskId: "task-\(agentId)",
        issueId: issueId,
        goalId: "goal",
        agentId: agentId,
        agentName: agentName,
        roleName: agentId,
        status: status,
        branchName: "codex/test",
        model: "opencode/nemotron-3-super-free",
        backendKind: "LOCAL_COTOR",
        processId: 123,
        outputSnippet: "working",
        startedAt: 0,
        updatedAt: 10
    )
}

private func review(
    issueId: String,
    status: String,
    pullRequestState: String?,
    checksSummary: String? = nil,
    mergeability: String? = nil,
    qaVerdict: String? = nil,
    ceoVerdict: String? = nil
) -> ReviewQueueItemRecord {
    ReviewQueueItemRecord(
        id: "review-\(issueId)",
        companyId: "company",
        projectContextId: nil,
        issueId: issueId,
        runId: "run",
        branchName: "codex/test",
        worktreePath: nil,
        pullRequestNumber: 1,
        pullRequestUrl: "https://github.com/example/repo/pull/1",
        pullRequestState: pullRequestState,
        status: status,
        checksSummary: checksSummary,
        mergeability: mergeability,
        requestedReviewers: [],
        qaVerdict: qaVerdict,
        qaFeedback: nil,
        qaReviewedAt: nil,
        qaIssueId: nil,
        ceoVerdict: ceoVerdict,
        ceoFeedback: nil,
        ceoReviewedAt: nil,
        approvalIssueId: nil,
        mergeCommitSha: nil,
        mergedAt: nil,
        createdAt: 0,
        updatedAt: 1
    )
}

private func message(id: String, from: String, to: String?, issueId: String?, body: String) -> AgentMessageRecord {
    AgentMessageRecord(
        id: id,
        companyId: "company",
        fromAgentName: from,
        toAgentName: to,
        issueId: issueId,
        goalId: "goal",
        kind: "handoff",
        subject: "A2A handoff",
        body: body,
        status: "SENT",
        parentMessageId: nil,
        createdAt: 10
    )
}

private func runtime(companyId: String? = "company", status: String = "RUNNING", budgetPausedAt: Int64? = nil) -> CompanyRuntimeSnapshotRecord {
    CompanyRuntimeSnapshotRecord(
        companyId: companyId,
        status: status,
        tickIntervalSeconds: 60,
        activeGoalCount: 1,
        activeIssueCount: 1,
        autonomyEnabledGoalCount: 1,
        lastStartedAt: 0,
        lastStoppedAt: nil,
        manuallyStoppedAt: nil,
        lastTickAt: 10,
        lastAction: "tick",
        lastError: nil,
        backendKind: "LOCAL_COTOR",
        backendHealth: "healthy",
        backendMessage: nil,
        backendLifecycleState: "RUNNING",
        backendPid: 42,
        backendPort: 8787,
        todaySpentCents: 12,
        monthSpentCents: 34,
        budgetPausedAt: budgetPausedAt,
        budgetResetDate: nil
    )
}
