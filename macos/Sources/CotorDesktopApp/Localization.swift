import Foundation

/// Supported shell languages for the desktop client.
///
/// The app only needs a small curated language set right now, so a handwritten
/// enum keeps runtime switching simple without introducing `.strings` bundles.
enum AppLanguage: String, CaseIterable, Identifiable {
    case english = "en"
    case korean = "ko"

    var id: String { rawValue }

    /// Display names stay stable across UI languages so users can always recover
    /// the selector even if they accidentally switch to the wrong locale.
    var displayName: String {
        switch self {
        case .english:
            return "English"
        case .korean:
            return "한국어"
        }
    }
}

extension AppLanguage {
    /// Lightweight inline translation helper for places where the code already
    /// has an obvious English/Korean pair and a full text key would add noise.
    func callAsFunction(_ english: String, _ korean: String) -> String {
        switch self {
        case .english:
            return english
        case .korean:
            return korean
        }
    }

    func compactSurface(_ surface: CompactSurface) -> String {
        switch surface {
        case .workspace:
            return self("Workspace", "워크스페이스")
        case .console:
            return self("Console", "콘솔")
        case .inspector:
            return self("Inspector", "인스펙터")
        }
    }

    func inspectorTab(_ tab: InspectorTab) -> String {
        switch tab {
        case .changes:
            return text(.changes)
        case .files:
            return text(.files)
        case .ports:
            return text(.ports)
        case .browser:
            return text(.browser)
        }
    }

    func status(_ status: String) -> String {
        DesktopStrings.status(status, language: self)
    }

    func text(_ key: DesktopTextKey) -> String {
        DesktopStrings.text(key, language: self)
    }
}

/// Enumerates user-facing shell copy so SwiftUI views can render through one
/// runtime language source instead of scattering inline strings everywhere.
enum DesktopTextKey {
    case appName
    case requestFailed
    case close
    case refresh
    case openRepo
    case cloneRepo
    case surface
    case compactWorkspace
    case compactConsole
    case compactInspector
    case offlinePreview
    case liveLocalSession
    case source
    case repositories
    case repositoriesSubtitle
    case noRepositories
    case noRepositoriesSubtitle
    case workspace
    case branchesAndWorkspaces
    case branchesAndWorkspacesSubtitle
    case baseBranch
    case newWorkspaceName
    case createWorkspace
    case noWorkspaces
    case noWorkspacesSubtitle
    case preview
    case workspaceDetail
    case denseDesktopSubtitle
    case mockMode
    case connected
    case overview
    case localOrchestrationSurface
    case localOrchestrationSurfaceSubtitle
    case repos
    case workspaces
    case tasks
    case runs
    case chooseWorkspace
    case openOrCloneRepository
    case idle
    case agents
    case ports
    case openFolder
    case runSelectedTask
    case compose
    case newTask
    case newTaskSubtitle
    case taskTitle
    case prompt
    case createTask
    case queue
    case tasksSubtitle
    case noTasks
    case noTasksSubtitle
    case execution
    case agentRuns
    case agentRunsSubtitle
    case noRuns
    case noRunsSubtitle
    case noOutputYet
    case inspect
    case inspectorSubtitle
    case noAgentSelected
    case branch
    case base
    case worktree
    case commit
    case pushedBranch
    case pullRequest
    case publishError
    case selectTaskAndAgent
    case selectAgent
    case changes
    case files
    case browser
    case noChanges
    case noChangesSubtitle
    case noFileTree
    case noFileTreeSubtitle
    case noPorts
    case noPortsSubtitle
    case open
    case browserIdle
    case browserIdleSubtitle
    case clone
    case cloneRepository
    case cloneRepositorySubtitle
    case cloneRepositoryPlaceholder
    case cancel
    case desktopSettings
    case runtimeOverview
    case runtimeOverviewSubtitle
    case paths
    case localPaths
    case localPathsSubtitle
    case appHome
    case managedRepos
    case catalog
    case availableAgents
    case availableAgentsSubtitle
    case input
    case keyboardShortcuts
    case keyboardShortcutsSubtitle
    case install
    case desktopInstaller
    case desktopInstallerSubtitle
    case installer
    case appLocation
    case downloadArchive
    case language
    case languageSubtitle
    case cotorMenu
    case inspectorMenu
    case refreshDashboard
    case openRepository
    case cloneRepositoryMenu
    case createTaskMenu
    case showChanges
    case showFiles
    case showPorts
    case showBrowser
    case filesTab
    case browserTab
    case connectingToServer
    case waitingForServer
    case offlineMockData
    case openRepositoryPrompt
}

/// Centralized copy table for the desktop shell.
struct DesktopStrings {
    static func text(_ key: DesktopTextKey, language: AppLanguage) -> String {
        switch language {
        case .english:
            return english(key)
        case .korean:
            return korean(key)
        }
    }

    static func connectedToServer(_ url: String, language: AppLanguage) -> String {
        switch language {
        case .english:
            return "Connected to \(url)"
        case .korean:
            return "\(url)에 연결됨"
        }
    }

    static func startedTask(_ title: String, language: AppLanguage) -> String {
        switch language {
        case .english:
            return "Started task \(title)"
        case .korean:
            return "태스크 시작: \(title)"
        }
    }

    static func status(_ status: String, language: AppLanguage) -> String {
        switch (language, status.uppercased()) {
        case (.english, "RUNNING"):
            return "Running"
        case (.english, "QUEUED"):
            return "Queued"
        case (.english, "COMPLETED"), (.english, "SUCCESS"):
            return "Completed"
        case (.english, "FAILED"), (.english, "ERROR"):
            return "Failed"
        case (.english, "STARTING"):
            return "Starting"
        case (.english, "EXITED"):
            return "Exited"
        case (.english, _):
            return status.capitalized
        case (.korean, "RUNNING"):
            return "실행 중"
        case (.korean, "QUEUED"):
            return "대기 중"
        case (.korean, "COMPLETED"), (.korean, "SUCCESS"):
            return "완료됨"
        case (.korean, "FAILED"), (.korean, "ERROR"):
            return "실패"
        case (.korean, "STARTING"):
            return "시작 중"
        case (.korean, "EXITED"):
            return "종료됨"
        case (.korean, _):
            return status
        }
    }

    static func shortcutTitle(id: String, fallback: String, language: AppLanguage) -> String {
        switch (language, id) {
        case (.english, "openRepository"):
            return "Open Repository"
        case (.english, "createTask"):
            return "Create Task"
        case (.english, "showBrowser"):
            return "Show Browser Tab"
        case (.english, "cloneRepository"):
            return "Clone Repository"
        case (.english, "showFiles"):
            return "Show Files Tab"
        case (.english, "settings"):
            return "Open Settings"
        case (.english, "changesTab"):
            return "Show Changes Tab"
        case (.english, "filesTab"):
            return "Show Files Tab"
        case (.english, "portsTab"):
            return "Show Ports Tab"
        case (.english, "browserTab"):
            return "Show Browser Tab"
        case (.korean, "openRepository"):
            return "저장소 열기"
        case (.korean, "createTask"):
            return "태스크 생성"
        case (.korean, "showBrowser"):
            return "브라우저 탭 보기"
        case (.korean, "cloneRepository"):
            return "저장소 복제"
        case (.korean, "showFiles"):
            return "파일 탭 보기"
        case (.korean, "settings"):
            return "설정 열기"
        case (.korean, "changesTab"):
            return "변경점 탭 보기"
        case (.korean, "filesTab"):
            return "파일 탭 보기"
        case (.korean, "portsTab"):
            return "포트 탭 보기"
        case (.korean, "browserTab"):
            return "브라우저 탭 보기"
        default:
            return fallback
        }
    }

    private static func english(_ key: DesktopTextKey) -> String {
        switch key {
        case .appName: return "Cotor Desktop"
        case .requestFailed: return "Request Failed"
        case .close: return "Close"
        case .refresh: return "Refresh"
        case .openRepo: return "Open Repo"
        case .cloneRepo: return "Clone Repo"
        case .surface: return "Surface"
        case .compactWorkspace: return "Workspace"
        case .compactConsole: return "Console"
        case .compactInspector: return "Inspector"
        case .offlinePreview: return "Offline Preview"
        case .liveLocalSession: return "Live Local Session"
        case .source: return "Source"
        case .repositories: return "Repositories"
        case .repositoriesSubtitle: return "Open an existing checkout or clone a remote Git URL."
        case .noRepositories: return "No repositories yet"
        case .noRepositoriesSubtitle: return "Open a local repository or clone one to start a desktop workspace."
        case .workspace: return "Workspace"
        case .branchesAndWorkspaces: return "Branches And Workspaces"
        case .branchesAndWorkspacesSubtitle: return "Pin a workspace to the base branch you want every agent worktree to fork from."
        case .baseBranch: return "Base Branch"
        case .newWorkspaceName: return "New workspace name"
        case .createWorkspace: return "Create Workspace"
        case .noWorkspaces: return "No workspaces yet"
        case .noWorkspacesSubtitle: return "Create a workspace to lock a base branch and start task orchestration."
        case .preview: return "Preview"
        case .workspaceDetail: return "Workspace"
        case .denseDesktopSubtitle: return "Dense local orchestration for multi-agent repository work."
        case .mockMode: return "Mock Mode"
        case .connected: return "Connected"
        case .overview: return "Overview"
        case .localOrchestrationSurface: return "Local Orchestration Surface"
        case .localOrchestrationSurfaceSubtitle: return "Every selected agent gets its own branch and worktree for isolated execution."
        case .repos: return "Repos"
        case .workspaces: return "Workspaces"
        case .tasks: return "Workflows"
        case .runs: return "Runs"
        case .chooseWorkspace: return "Choose A Workspace"
        case .openOrCloneRepository: return "Open or clone a repository to start orchestrating local agent runs."
        case .idle: return "Idle"
        case .agents: return "Agents"
        case .ports: return "Ports"
        case .openFolder: return "Open Folder"
        case .runSelectedTask: return "Test Run"
        case .compose: return "Workflow"
        case .newTask: return "Workflow Definition"
        case .newTaskSubtitle: return "Define the workflow once, then let the TUI hand it to a lead AI that orchestrates worker agents."
        case .taskTitle: return "Workflow name"
        case .prompt: return "Prompt"
        case .createTask: return "Save Workflow"
        case .queue: return "Workflow Library"
        case .tasksSubtitle: return "Workflow definitions stay pinned to one workspace and are meant to be invoked from the TUI."
        case .noTasks: return "No workflows yet"
        case .noTasksSubtitle: return "Create the first workflow definition so the TUI and orchestrator have something to execute."
        case .execution: return "Orchestration"
        case .agentRuns: return "Execution Runs"
        case .agentRunsSubtitle: return "When the TUI invokes a workflow, the lead AI fans work out to worker agents across isolated branches and worktrees."
        case .noRuns: return "No runs yet"
        case .noRunsSubtitle: return "Run the selected task to populate per-agent execution cards."
        case .noOutputYet: return "No output captured yet."
        case .inspect: return "Inspect"
        case .inspectorSubtitle: return "Switch between changes, files, local ports, and the embedded browser."
        case .noAgentSelected: return "No Agent Selected"
        case .branch: return "Branch"
        case .base: return "Base"
        case .worktree: return "Worktree"
        case .commit: return "Commit"
        case .pushedBranch: return "Pushed Branch"
        case .pullRequest: return "Pull Request"
        case .publishError: return "Publish Error"
        case .selectTaskAndAgent: return "Select a task and an agent run to inspect the isolated worktree state."
        case .selectAgent: return "Select Agent"
        case .changes: return "Changes"
        case .files: return "Files"
        case .browser: return "Browser"
        case .noChanges: return "No changes yet"
        case .noChangesSubtitle: return "The selected agent has not produced a diff against the workspace base branch."
        case .noFileTree: return "No file tree yet"
        case .noFileTreeSubtitle: return "The selected run does not have a browsable worktree snapshot yet."
        case .noPorts: return "No local ports"
        case .noPortsSubtitle: return "When an agent run exposes a local service it will appear here with a direct browser jump."
        case .open: return "Open"
        case .browserIdle: return "Browser idle"
        case .browserIdleSubtitle: return "Open a discovered port to keep the preview next to task, diff, and file context."
        case .clone: return "Clone"
        case .cloneRepository: return "Clone Repository"
        case .cloneRepositorySubtitle: return "Import a remote repository into Cotor's managed workspace area."
        case .cloneRepositoryPlaceholder: return "https://github.com/owner/repo.git"
        case .cancel: return "Cancel"
        case .desktopSettings: return "Desktop Settings"
        case .runtimeOverview: return "Runtime Overview"
        case .runtimeOverviewSubtitle: return "The macOS shell currently exposes install paths, bundled agents, keyboard shortcuts, and language selection."
        case .paths: return "Paths"
        case .localPaths: return "Local Paths"
        case .localPathsSubtitle: return "Copy these into terminal commands when you need to inspect the desktop storage."
        case .appHome: return "App Home"
        case .managedRepos: return "Managed Repos"
        case .catalog: return "Catalog"
        case .availableAgents: return "Available Agents"
        case .availableAgentsSubtitle: return "These are the built-in agents currently advertised by the local app-server."
        case .input: return "Input"
        case .keyboardShortcuts: return "Keyboard Shortcuts"
        case .keyboardShortcutsSubtitle: return "The menu bar and the settings screen stay aligned so shortcuts remain discoverable."
        case .install: return "Install"
        case .desktopInstaller: return "Desktop Installer"
        case .desktopInstallerSubtitle: return "Use the shell script below to build a signed local bundle, copy it into Applications, and refresh the Downloads zip."
        case .installer: return "Installer"
        case .appLocation: return "App Location"
        case .downloadArchive: return "Download Archive"
        case .language: return "Language"
        case .languageSubtitle: return "Switch between English and Korean without restarting the desktop app."
        case .cotorMenu: return "Cotor"
        case .inspectorMenu: return "Inspector"
        case .refreshDashboard: return "Refresh Dashboard"
        case .openRepository: return "Open Repository"
        case .cloneRepositoryMenu: return "Clone Repository"
        case .createTaskMenu: return "Save Workflow"
        case .showChanges: return "Show Changes"
        case .showFiles: return "Show Files"
        case .showPorts: return "Show Ports"
        case .showBrowser: return "Show Browser"
        case .filesTab: return "Files Tab"
        case .browserTab: return "Browser Tab"
        case .connectingToServer: return "Connecting to local Cotor app-server..."
        case .waitingForServer: return "Waiting for local Cotor app-server to finish starting..."
        case .offlineMockData: return "App-server unavailable. Showing offline mock data."
        case .openRepositoryPrompt: return "Open Repository"
        }
    }

    private static func korean(_ key: DesktopTextKey) -> String {
        switch key {
        case .appName: return "Cotor Desktop"
        case .requestFailed: return "요청 실패"
        case .close: return "닫기"
        case .refresh: return "새로고침"
        case .openRepo: return "저장소 열기"
        case .cloneRepo: return "저장소 복제"
        case .surface: return "화면"
        case .compactWorkspace: return "워크스페이스"
        case .compactConsole: return "콘솔"
        case .compactInspector: return "인스펙터"
        case .offlinePreview: return "오프라인 미리보기"
        case .liveLocalSession: return "로컬 실시간 세션"
        case .source: return "소스"
        case .repositories: return "저장소"
        case .repositoriesSubtitle: return "기존 체크아웃을 열거나 원격 Git URL을 복제할 수 있습니다."
        case .noRepositories: return "저장소가 아직 없습니다"
        case .noRepositoriesSubtitle: return "로컬 저장소를 열거나 복제해서 데스크톱 워크스페이스를 시작하세요."
        case .workspace: return "워크스페이스"
        case .branchesAndWorkspaces: return "브랜치와 워크스페이스"
        case .branchesAndWorkspacesSubtitle: return "에이전트 워크트리가 갈라질 기준 브랜치를 워크스페이스에 고정하세요."
        case .baseBranch: return "기준 브랜치"
        case .newWorkspaceName: return "새 워크스페이스 이름"
        case .createWorkspace: return "워크스페이스 만들기"
        case .noWorkspaces: return "워크스페이스가 아직 없습니다"
        case .noWorkspacesSubtitle: return "기준 브랜치를 고정하는 워크스페이스를 만든 뒤 태스크 오케스트레이션을 시작하세요."
        case .preview: return "미리보기"
        case .workspaceDetail: return "워크스페이스"
        case .denseDesktopSubtitle: return "멀티 에이전트 저장소 작업을 위한 밀도 높은 로컬 오케스트레이션 셸입니다."
        case .mockMode: return "목업 모드"
        case .connected: return "연결됨"
        case .overview: return "개요"
        case .localOrchestrationSurface: return "로컬 오케스트레이션 화면"
        case .localOrchestrationSurfaceSubtitle: return "선택한 각 에이전트는 독립된 브랜치와 워크트리에서 실행됩니다."
        case .repos: return "저장소"
        case .workspaces: return "워크스페이스"
        case .tasks: return "워크플로우"
        case .runs: return "런"
        case .chooseWorkspace: return "워크스페이스를 선택하세요"
        case .openOrCloneRepository: return "저장소를 열거나 복제해서 로컬 에이전트 런을 시작하세요."
        case .idle: return "유휴"
        case .agents: return "에이전트"
        case .ports: return "포트"
        case .openFolder: return "폴더 열기"
        case .runSelectedTask: return "테스트 실행"
        case .compose: return "워크플로우"
        case .newTask: return "워크플로우 정의"
        case .newTaskSubtitle: return "워크플로우를 한 번 정의하면, TUI가 이를 호출하고 리더 AI가 하위 에이전트를 오케스트레이션합니다."
        case .taskTitle: return "워크플로우 이름"
        case .prompt: return "프롬프트"
        case .createTask: return "워크플로우 저장"
        case .queue: return "워크플로우 라이브러리"
        case .tasksSubtitle: return "워크플로우 정의는 하나의 워크스페이스에 고정되고, 실제 실행은 TUI에서 호출됩니다."
        case .noTasks: return "워크플로우가 아직 없습니다"
        case .noTasksSubtitle: return "첫 워크플로우 정의를 만들어 TUI와 오케스트레이터가 실행할 대상을 준비하세요."
        case .execution: return "오케스트레이션"
        case .agentRuns: return "실행 런"
        case .agentRunsSubtitle: return "TUI가 워크플로우를 호출하면 리더 AI가 하위 에이전트로 작업을 분배하고, 각 에이전트는 독립된 브랜치와 워크트리에서 실행됩니다."
        case .noRuns: return "런이 아직 없습니다"
        case .noRunsSubtitle: return "선택한 태스크를 실행하면 에이전트별 실행 카드가 채워집니다."
        case .noOutputYet: return "아직 수집된 출력이 없습니다."
        case .inspect: return "검토"
        case .inspectorSubtitle: return "변경점, 파일, 로컬 포트, 내장 브라우저 사이를 전환하세요."
        case .noAgentSelected: return "선택된 에이전트가 없습니다"
        case .branch: return "브랜치"
        case .base: return "기준"
        case .worktree: return "워크트리"
        case .commit: return "커밋"
        case .pushedBranch: return "푸시된 브랜치"
        case .pullRequest: return "풀 리퀘스트"
        case .publishError: return "배포 오류"
        case .selectTaskAndAgent: return "태스크와 에이전트 런을 선택하면 격리된 워크트리 상태를 확인할 수 있습니다."
        case .selectAgent: return "에이전트 선택"
        case .changes: return "변경점"
        case .files: return "파일"
        case .browser: return "브라우저"
        case .noChanges: return "아직 변경점이 없습니다"
        case .noChangesSubtitle: return "선택한 에이전트가 기준 브랜치 대비 diff를 아직 만들지 않았습니다."
        case .noFileTree: return "파일 트리가 아직 없습니다"
        case .noFileTreeSubtitle: return "선택한 런의 워크트리 스냅샷이 아직 준비되지 않았습니다."
        case .noPorts: return "로컬 포트가 없습니다"
        case .noPortsSubtitle: return "에이전트 런이 로컬 서비스를 노출하면 여기서 바로 열 수 있습니다."
        case .open: return "열기"
        case .browserIdle: return "브라우저 대기 중"
        case .browserIdleSubtitle: return "포트를 열면 태스크, diff, 파일 맥락 옆에서 바로 미리보기를 볼 수 있습니다."
        case .clone: return "복제"
        case .cloneRepository: return "저장소 복제"
        case .cloneRepositorySubtitle: return "원격 저장소를 Cotor 관리 워크스페이스 영역으로 가져옵니다."
        case .cloneRepositoryPlaceholder: return "https://github.com/owner/repo.git"
        case .cancel: return "취소"
        case .desktopSettings: return "데스크톱 설정"
        case .runtimeOverview: return "실행 환경 개요"
        case .runtimeOverviewSubtitle: return "macOS 셸이 현재 설치 경로, 내장 에이전트, 단축키, 언어 선택을 표시합니다."
        case .paths: return "경로"
        case .localPaths: return "로컬 경로"
        case .localPathsSubtitle: return "데스크톱 저장소를 직접 확인할 때 이 경로를 터미널에서 복사해 사용할 수 있습니다."
        case .appHome: return "앱 홈"
        case .managedRepos: return "관리 저장소"
        case .catalog: return "카탈로그"
        case .availableAgents: return "사용 가능한 에이전트"
        case .availableAgentsSubtitle: return "로컬 app-server가 현재 광고하는 내장 에이전트 목록입니다."
        case .input: return "입력"
        case .keyboardShortcuts: return "키보드 단축키"
        case .keyboardShortcutsSubtitle: return "메뉴 바와 설정 화면이 같은 단축키 정보를 보여주도록 맞춰져 있습니다."
        case .install: return "설치"
        case .desktopInstaller: return "데스크톱 설치기"
        case .desktopInstallerSubtitle: return "아래 스크립트로 서명된 로컬 번들을 만들고 Applications에 복사한 뒤 Downloads 압축본도 갱신할 수 있습니다."
        case .installer: return "설치 스크립트"
        case .appLocation: return "앱 위치"
        case .downloadArchive: return "다운로드 압축본"
        case .language: return "언어"
        case .languageSubtitle: return "앱을 다시 시작하지 않고 영어와 한국어를 즉시 전환할 수 있습니다."
        case .cotorMenu: return "Cotor"
        case .inspectorMenu: return "인스펙터"
        case .refreshDashboard: return "대시보드 새로고침"
        case .openRepository: return "저장소 열기"
        case .cloneRepositoryMenu: return "저장소 복제"
        case .createTaskMenu: return "워크플로우 저장"
        case .showChanges: return "변경점 보기"
        case .showFiles: return "파일 보기"
        case .showPorts: return "포트 보기"
        case .showBrowser: return "브라우저 보기"
        case .filesTab: return "파일 탭"
        case .browserTab: return "브라우저 탭"
        case .connectingToServer: return "로컬 Cotor app-server에 연결 중..."
        case .waitingForServer: return "로컬 Cotor app-server가 시작되기를 기다리는 중..."
        case .offlineMockData: return "app-server에 연결할 수 없어 오프라인 목업 데이터를 표시합니다."
        case .openRepositoryPrompt: return "저장소 열기"
        }
    }
}
