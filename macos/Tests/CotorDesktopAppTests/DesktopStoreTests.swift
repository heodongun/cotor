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
            companyRuntimes: []
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
            companyRuntimes: []
        )

        store.toggleCompanyAgentSelection(id: "agent-qa", shiftKey: false)
        store.toggleCompanyAgentSelection(id: "agent-builder", shiftKey: false)
        #expect(store.selectedCompanyAgentDefinitionIDs == Set(["agent-qa", "agent-builder"]))

        store.clearCompanyAgentSelection()
        store.toggleOrgProfileSelection(id: "profile-qa", shiftKey: false)

        #expect(store.selectedBatchEditableAgents.map(\.id) == ["agent-qa"])
    }
}
