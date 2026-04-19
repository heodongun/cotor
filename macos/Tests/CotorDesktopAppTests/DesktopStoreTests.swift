import Testing
@testable import CotorDesktopApp

@MainActor
struct DesktopStoreTests {
    @Test
    func orgProfileShiftSelectionAndClearWorkAcrossRanges() {
        let store = DesktopStore()
        store.dashboard = DashboardPayload(
            repositories: [],
            workspaces: [],
            tasks: [],
            settings: DashboardPayload.empty.settings,
            companies: [],
            companyAgentDefinitions: [],
            projectContexts: [],
            goals: [],
            issues: [],
            reviewQueue: [],
            orgProfiles: [
                OrgAgentProfileRecord(id: "a", companyId: "company", roleName: "CEO", executionAgentName: "opencode", capabilities: ["planning"], linearAssigneeId: nil, reviewerPolicy: nil, mergeAuthority: true, enabled: true),
                OrgAgentProfileRecord(id: "b", companyId: "company", roleName: "Builder", executionAgentName: "opencode", capabilities: ["implementation"], linearAssigneeId: nil, reviewerPolicy: nil, mergeAuthority: false, enabled: true),
                OrgAgentProfileRecord(id: "c", companyId: "company", roleName: "QA", executionAgentName: "opencode", capabilities: ["qa"], linearAssigneeId: nil, reviewerPolicy: "review-queue", mergeAuthority: false, enabled: true),
            ],
            workflowTopologies: [],
            goalDecisions: [],
            runningAgentSessions: [],
            backendStatuses: [],
            opsMetrics: DashboardPayload.empty.opsMetrics,
            activity: [],
            companyRuntimes: [],
            agentContextEntries: [],
            agentMessages: []
        )

        store.toggleOrgProfileSelection(id: "a", shiftKey: false)
        #expect(store.selectedOrgProfileIDs == Set(["a"]))

        store.toggleOrgProfileSelection(id: "c", shiftKey: true)
        #expect(store.selectedOrgProfileIDs == Set(["a", "c"]))

        store.clearOrgProfileSelection()
        #expect(store.selectedOrgProfileIDs.isEmpty)
    }

    @Test
    func companyAgentSelectionIsAdditiveAndOrgProfilesResolveToBatchEditableAgents() {
        let store = DesktopStore()
        store.selectedCompanyID = "company"
        store.dashboard = DashboardPayload(
            repositories: [],
            workspaces: [],
            tasks: [],
            settings: DashboardPayload.empty.settings,
            companies: [
                CompanyRecord(
                    id: "company",
                    name: "Test Company",
                    rootPath: "/tmp/company",
                    repositoryId: "repo",
                    defaultBaseBranch: "master",
                    backendKind: "LOCAL_COTOR",
                    linearSyncEnabled: false,
                    linearConfigOverride: nil,
                    autonomyEnabled: true,
                    dailyBudgetCents: nil,
                    monthlyBudgetCents: nil,
                    createdAt: 0,
                    updatedAt: 0
                )
            ],
            companyAgentDefinitions: [
                CompanyAgentDefinitionRecord(
                    id: "agent-qa",
                    companyId: "company",
                    title: "QA",
                    agentCli: "opencode",
                    model: "opencode/qwen3.6-plus-free",
                    roleSummary: "review",
                    specialties: ["qa", "review"],
                    collaborationInstructions: nil,
                    preferredCollaboratorIds: [],
                    memoryNotes: nil,
                    enabled: true,
                    displayOrder: 0,
                    createdAt: 0,
                    updatedAt: 0
                ),
                CompanyAgentDefinitionRecord(
                    id: "agent-builder",
                    companyId: "company",
                    title: "Builder",
                    agentCli: "opencode",
                    model: "opencode/qwen3.6-plus-free",
                    roleSummary: "implementation",
                    specialties: ["build"],
                    collaborationInstructions: nil,
                    preferredCollaboratorIds: [],
                    memoryNotes: nil,
                    enabled: true,
                    displayOrder: 1,
                    createdAt: 0,
                    updatedAt: 0
                ),
            ],
            projectContexts: [],
            goals: [],
            issues: [],
            reviewQueue: [],
            orgProfiles: [
                OrgAgentProfileRecord(id: "profile-qa", companyId: "company", roleName: "QA", executionAgentName: "opencode", capabilities: ["qa"], linearAssigneeId: nil, reviewerPolicy: "review-queue", mergeAuthority: false, enabled: true),
                OrgAgentProfileRecord(id: "profile-builder", companyId: "company", roleName: "Builder", executionAgentName: "opencode", capabilities: ["build"], linearAssigneeId: nil, reviewerPolicy: nil, mergeAuthority: false, enabled: true),
            ],
            workflowTopologies: [],
            goalDecisions: [],
            runningAgentSessions: [],
            backendStatuses: [],
            opsMetrics: DashboardPayload.empty.opsMetrics,
            activity: [],
            companyRuntimes: [],
            agentContextEntries: [],
            agentMessages: []
        )

        store.toggleCompanyAgentSelection(id: "agent-qa", shiftKey: false)
        store.toggleCompanyAgentSelection(id: "agent-builder", shiftKey: false)
        #expect(store.selectedCompanyAgentDefinitionIDs == Set(["agent-qa", "agent-builder"]))

        store.clearCompanyAgentSelection()
        store.toggleOrgProfileSelection(id: "profile-qa", shiftKey: false)

        #expect(store.selectedBatchEditableAgents.map(\.id) == ["agent-qa"])
    }

    @Test
    func shellModeDefaultsToCompanyAndCanSwitchToTui() {
        let store = DesktopStore()

        #expect(store.shellMode == .company)

        store.setShellMode(.tui)
        #expect(store.shellMode == .tui)

        store.setShellMode(.company)
        #expect(store.shellMode == .company)
    }

    @Test
    func chatGoalProposalUsesFirstMeaningfulLineAsTitle() {
        let store = DesktopStore()

        let proposal = store.chatGoalProposal(from: "Goal: Stabilize company chat control\nWire explicit confirmation into the desktop rail.")

        #expect(proposal == ChatGoalProposal(
            title: "Stabilize company chat control",
            description: "Goal: Stabilize company chat control\nWire explicit confirmation into the desktop rail."
        ))
    }

    @Test
    func chatGoalProposalStripsBulletPrefixes() {
        let store = DesktopStore()

        let proposal = store.chatGoalProposal(from: "- Goal: Ship approval-first goal creation\nKeep issue and review actions preview-only.")

        #expect(proposal?.title == "Ship approval-first goal creation")
    }

    @Test
    func chatReviewProposalDefaultsQaDraftToPass() {
        let store = DesktopStore()

        let proposal = store.chatReviewProposal(from: "QA looks good. Regression coverage passed.", kind: "qa")

        #expect(proposal == ChatReviewProposal(stage: .qa, verdict: "PASS", feedback: "QA looks good. Regression coverage passed."))
    }

    @Test
    func chatReviewProposalMapsChangeRequests() {
        let store = DesktopStore()

        let proposal = store.chatReviewProposal(from: "Request changes before CEO approval.", kind: "ceo")

        #expect(proposal == ChatReviewProposal(stage: .ceo, verdict: "CHANGES_REQUESTED", feedback: "Request changes before CEO approval."))
    }

    @Test
    func chatIssueProposalUsesSelectedGoalAndFirstMeaningfulLine() {
        let store = DesktopStore()
        store.selectedGoalID = "goal-1"

        let proposal = store.chatIssueProposal(from: "Issue: Wire the chat rail to real issue creation\nUse the current goal as the parent.")

        #expect(proposal == ChatIssueProposal(
            goalId: "goal-1",
            title: "Wire the chat rail to real issue creation",
            description: "Issue: Wire the chat rail to real issue creation\nUse the current goal as the parent."
        ))
    }

    @Test
    func chatMergeProposalRecognizesExplicitMergeRequest() {
        let store = DesktopStore()

        let proposal = store.chatMergeProposal(from: "Approve and merge this PR now.")

        #expect(proposal == ChatMergeProposal(summary: "Approve and merge this PR now."))
    }

    @Test
    func chatRuntimeProposalRecognizesStartRequest() {
        let store = DesktopStore()

        let proposal = store.chatRuntimeProposal(from: "Start runtime for this company.")

        #expect(proposal == ChatRuntimeProposal(action: .start, summary: "Start runtime for this company."))
    }

    @Test
    func chatAgentProposalCreatesQaAgentFromStaffingRequest() {
        let store = DesktopStore()
        store.dashboard = DashboardPayload(
            repositories: DashboardPayload.empty.repositories,
            workspaces: DashboardPayload.empty.workspaces,
            tasks: DashboardPayload.empty.tasks,
            settings: DesktopSettingsPayload(
                appHome: "/tmp",
                managedReposRoot: "/tmp",
                availableAgents: ["opencode"],
                availableCliAgents: [],
                recentCompanies: [],
                defaultLaunchMode: "company",
                backendSettings: DashboardPayload.empty.settings.backendSettings,
                githubPublishStatus: DashboardPayload.empty.settings.githubPublishStatus,
                linearSettings: DashboardPayload.empty.settings.linearSettings,
                backendStatuses: DashboardPayload.empty.settings.backendStatuses,
                shortcuts: DashboardPayload.empty.settings.shortcuts
            ),
            companies: DashboardPayload.empty.companies,
            companyAgentDefinitions: DashboardPayload.empty.companyAgentDefinitions,
            projectContexts: DashboardPayload.empty.projectContexts,
            goals: DashboardPayload.empty.goals,
            issues: DashboardPayload.empty.issues,
            reviewQueue: DashboardPayload.empty.reviewQueue,
            orgProfiles: DashboardPayload.empty.orgProfiles,
            workflowTopologies: DashboardPayload.empty.workflowTopologies,
            goalDecisions: DashboardPayload.empty.goalDecisions,
            runningAgentSessions: DashboardPayload.empty.runningAgentSessions,
            backendStatuses: DashboardPayload.empty.backendStatuses,
            opsMetrics: DashboardPayload.empty.opsMetrics,
            activity: DashboardPayload.empty.activity,
            companyRuntimes: DashboardPayload.empty.companyRuntimes,
            agentContextEntries: DashboardPayload.empty.agentContextEntries,
            agentMessages: DashboardPayload.empty.agentMessages
        )

        let proposal = store.chatAgentProposal(from: "Create a QA agent for this company.")

        #expect(proposal?.title == "QA Agent")
        #expect(proposal?.specialties == ["qa", "review", "verification"])
        #expect(proposal?.agentCli == "opencode")
    }

    @Test
    func chatBackendProposalRecognizesRestartRequest() {
        let store = DesktopStore()

        let proposal = store.chatBackendProposal(from: "Restart backend for this company.")

        #expect(proposal == ChatBackendProposal(action: .restart, summary: "Restart backend for this company."))
    }

    @Test
    func chatExecutionProposalRecognizesRunIssueRequest() {
        let store = DesktopStore()

        let proposal = store.chatExecutionProposal(from: "Run this issue now.")

        #expect(proposal == ChatExecutionProposal(summary: "Run this issue now."))
    }

    @Test
    func chatDelegationProposalRecognizesDelegateIssueRequest() {
        let store = DesktopStore()

        let proposal = store.chatDelegationProposal(from: "Delegate this issue to the company roster.")

        #expect(proposal == ChatDelegationProposal(summary: "Delegate this issue to the company roster."))
    }

    @Test
    func chatGoalDecompositionProposalRecognizesGoalBreakdownRequest() {
        let store = DesktopStore()

        let proposal = store.chatGoalDecompositionProposal(from: "Break this goal into issues.")

        #expect(proposal == ChatGoalDecompositionProposal(summary: "Break this goal into issues."))
    }

    @Test
    func chatGoalAutonomyProposalRecognizesAutonomyToggleRequest() {
        let store = DesktopStore()

        let proposal = store.chatGoalAutonomyProposal(from: "Enable autonomy for this goal.")

        #expect(proposal == ChatGoalAutonomyProposal(mode: .enable, summary: "Enable autonomy for this goal."))
    }
}
