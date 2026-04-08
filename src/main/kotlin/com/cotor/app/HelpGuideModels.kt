package com.cotor.app

import com.cotor.presentation.cli.CliHelpLanguage
import kotlinx.serialization.Serializable

@Serializable
data class HelpGuideItem(
    val command: String,
    val description: String
)

@Serializable
data class HelpGuideSection(
    val title: String,
    val summary: String,
    val items: List<HelpGuideItem>
)

@Serializable
data class HelpGuideTopic(
    val command: String,
    val title: String,
    val description: String
)

@Serializable
data class HelpGuidePayload(
    val title: String,
    val subtitle: String,
    val quickStart: List<HelpGuideItem>,
    val sections: List<HelpGuideSection>,
    val topics: List<HelpGuideTopic>,
    val aiNarrative: String,
    val footer: String
)

object HelpGuideContent {
    fun guide(language: CliHelpLanguage): HelpGuidePayload = when (language) {
        CliHelpLanguage.KOREAN -> HelpGuidePayload(
            title = "Cotor 도움말",
            subtitle = "CLI 명령어 모음집과 빠른 사용 흐름을 한 곳에서 확인합니다.",
            quickStart = listOf(
                HelpGuideItem("cotor", "대화형 TUI를 시작합니다."),
                HelpGuideItem("cotor tui", "interactive의 별칭으로 같은 TUI를 엽니다."),
                HelpGuideItem("cotor help", "기본 상세 도움말을 출력합니다."),
                HelpGuideItem("cotor help ai", "줄글 형태의 추천 사용 흐름을 보여줍니다."),
                HelpGuideItem("cotor help web", "웹 도움말 페이지를 열어 명령어 모음집을 보여줍니다.")
            ),
            sections = listOf(
                HelpGuideSection(
                    title = "시작과 설정",
                    summary = "새 프로젝트를 준비하거나 로컬 환경을 점검할 때 먼저 보는 명령들입니다.",
                    items = listOf(
                        HelpGuideItem("cotor init --starter-template", "starter config와 pipeline/docs 스캐폴드를 만듭니다."),
                        HelpGuideItem("cotor list", "현재 설정/파이프라인 목록을 확인합니다."),
                        HelpGuideItem("cotor doctor", "인증, 실행환경, 의존성 문제를 점검합니다.")
                    )
                ),
                HelpGuideSection(
                    title = "실행과 검증",
                    summary = "파이프라인을 검증하고 실제로 실행하는 기본 루프입니다.",
                    items = listOf(
                        HelpGuideItem("cotor validate <pipeline> -c cotor.yaml", "파이프라인 정의를 검증합니다."),
                        HelpGuideItem("cotor run <pipeline> -c cotor.yaml --output-format text", "파이프라인을 실행합니다."),
                        HelpGuideItem("cotor explain cotor.yaml <pipeline>", "선택한 파이프라인 구조를 설명합니다.")
                    )
                ),
                HelpGuideSection(
                    title = "앱과 웹",
                    summary = "데스크톱 앱, 로컬 app-server, 웹 편집기/도움말 표면을 다루는 명령들입니다.",
                    items = listOf(
                        HelpGuideItem("cotor install | update | delete", "패키지된 데스크톱 앱을 설치/업데이트/삭제합니다."),
                        HelpGuideItem("cotor app-server --port 8787", "앱이 사용하는 localhost 백엔드를 띄웁니다."),
                        HelpGuideItem("cotor web --open", "웹 에디터를 엽니다."),
                        HelpGuideItem("cotor help web", "명령어 모음집과 간단한 사용법을 웹에서 보여줍니다.")
                    )
                ),
                HelpGuideSection(
                    title = "회사 운영",
                    summary = "Company 모드에서 목표/이슈/리뷰/런타임을 다루는 진입점입니다.",
                    items = listOf(
                        HelpGuideItem("cotor company --help", "회사 명령 전체를 확인합니다."),
                        HelpGuideItem("cotor company runtime status", "회사 런타임 상태를 확인합니다."),
                        HelpGuideItem("cotor auth codex-oauth status", "Codex OAuth 인증 상태를 확인합니다.")
                    )
                )
            ),
            topics = listOf(
                HelpGuideTopic("cotor help web", "웹 도움말", "웹 브라우저에서 명령어 모음과 빠른 사용법을 한눈에 보게 합니다."),
                HelpGuideTopic("cotor help ai", "AI 안내문", "처음 쓰는 사람도 바로 따라갈 수 있는 줄글형 사용 가이드를 출력합니다.")
            ),
            aiNarrative = "Cotor를 처음 쓸 때는 먼저 cotor help로 전체 명령 흐름을 보고, cotor init --starter-template으로 기본 골격을 만든 뒤 cotor validate와 cotor run으로 검증과 실행을 반복하는 것이 가장 안전합니다. 데스크톱 앱이나 회사 운영을 쓰려면 cotor install과 cotor app-server로 로컬 표면을 준비하고, Company 모드에서는 목표를 만든 다음 이슈와 리뷰 큐, 런타임 상태를 짧은 루프로 확인하는 방식이 좋습니다. 인증이 필요한 에이전트는 cotor auth codex-oauth status 같은 명령으로 준비 상태를 먼저 확인하고, 문제가 생기면 cotor doctor와 --debug를 우선 사용하세요.",
            footer = "Company는 지속적인 회사 워크플로를 다루고, TUI는 standalone cotor 터미널처럼 동작합니다."
        )
        CliHelpLanguage.ENGLISH -> HelpGuidePayload(
            title = "Cotor Help",
            subtitle = "One place for the CLI command collection and the fastest way to get started.",
            quickStart = listOf(
                HelpGuideItem("cotor", "Start the interactive TUI."),
                HelpGuideItem("cotor tui", "Open the same interactive TUI through its alias."),
                HelpGuideItem("cotor help", "Print the default detailed help."),
                HelpGuideItem("cotor help ai", "Print a narrative guide for using Cotor well."),
                HelpGuideItem("cotor help web", "Open a web help page with the command collection and quick usage notes.")
            ),
            sections = listOf(
                HelpGuideSection(
                    title = "Start and setup",
                    summary = "Use these commands first when bootstrapping a project or checking your local environment.",
                    items = listOf(
                        HelpGuideItem("cotor init --starter-template", "Create a starter config plus pipeline/docs scaffold."),
                        HelpGuideItem("cotor list", "Inspect the current config and pipeline inventory."),
                        HelpGuideItem("cotor doctor", "Check auth, environment, and dependency readiness.")
                    )
                ),
                HelpGuideSection(
                    title = "Run and validate",
                    summary = "This is the basic loop for validating and executing pipelines.",
                    items = listOf(
                        HelpGuideItem("cotor validate <pipeline> -c cotor.yaml", "Validate one pipeline definition."),
                        HelpGuideItem("cotor run <pipeline> -c cotor.yaml --output-format text", "Run one pipeline."),
                        HelpGuideItem("cotor explain cotor.yaml <pipeline>", "Explain the selected pipeline structure.")
                    )
                ),
                HelpGuideSection(
                    title = "App and web",
                    summary = "Use these commands for the desktop app, localhost backend, and browser surfaces.",
                    items = listOf(
                        HelpGuideItem("cotor install | update | delete", "Install, update, or remove the packaged desktop app."),
                        HelpGuideItem("cotor app-server --port 8787", "Start the localhost backend used by the app."),
                        HelpGuideItem("cotor web --open", "Open the browser editor."),
                        HelpGuideItem("cotor help web", "Open the web help page with the command collection and quick usage guidance.")
                    )
                ),
                HelpGuideSection(
                    title = "Company workflows",
                    summary = "These are the main entry points for goals, issues, reviews, and runtime operations.",
                    items = listOf(
                        HelpGuideItem("cotor company --help", "Inspect all company commands."),
                        HelpGuideItem("cotor company runtime status", "Check company runtime state."),
                        HelpGuideItem("cotor auth codex-oauth status", "Check Codex OAuth readiness.")
                    )
                )
            ),
            topics = listOf(
                HelpGuideTopic("cotor help web", "Web help", "Show the command collection and quick usage notes in a browser surface."),
                HelpGuideTopic("cotor help ai", "AI narrative", "Print a prose guide that a new operator can follow from top to bottom.")
            ),
            aiNarrative = "If you are new to Cotor, start with cotor help to understand the overall command surface, then run cotor init --starter-template to create the default project scaffold. After that, stay in a short loop of cotor validate and cotor run until your pipeline behaves the way you want. When you need the desktop or company surfaces, prepare them with cotor install and cotor app-server, then operate Company mode through goals, issues, review queue, and runtime status in small feedback loops. Before relying on authenticated agents, check readiness first with commands like cotor auth codex-oauth status, and when something feels wrong, use cotor doctor and --debug before making larger changes.",
            footer = "Company manages ongoing company workflows, while TUI behaves like the standalone cotor terminal."
        )
    }

    fun aiNarrative(language: CliHelpLanguage): String = guide(language).aiNarrative
}
