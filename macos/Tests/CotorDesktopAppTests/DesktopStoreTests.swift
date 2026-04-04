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
}
