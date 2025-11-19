package com.cotor.presentation.web

import com.cotor.data.config.ConfigRepository
import com.cotor.data.registry.AgentRegistry
import com.cotor.domain.orchestrator.PipelineOrchestrator
import com.cotor.event.EventBus
import com.cotor.monitoring.TimelineCollector
import com.cotor.presentation.timeline.StageTimelineEntry
import com.cotor.presentation.timeline.StageTimelineState
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.html.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.html.*
import kotlinx.serialization.Serializable
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.io.path.Path

/**
 * Web UI Server for Cotor
 */
@Serializable
data class PipelineSummary(
    val name: String,
    val description: String,
    val configFile: String,
    val stageCount: Int,
    val executionMode: String,
    val agents: List<String>,
    val lastModified: Long
)

class WebServer : KoinComponent {
    private val configRepository: ConfigRepository by inject()
    private val agentRegistry: AgentRegistry by inject()
    private val orchestrator: PipelineOrchestrator by inject()
    private val eventBus: EventBus by inject()
    private val timelineCollector by lazy { TimelineCollector(eventBus) }

    fun start(port: Int = 8080) {
        embeddedServer(Netty, port = port) {
            install(ContentNegotiation) {
                json()
            }
            install(CORS) {
                anyHost()
            }

            routing {
                                // Home page
                get("/") {
                    call.respondHtml {
                        head {
                            title { +"Cotor · Pipeline Dashboard" }
                            meta {
                                name = "viewport"
                                content = "width=device-width, initial-scale=1.0"
                            }
                            link(rel = "preconnect", href = "https://fonts.googleapis.com")
                            link(rel = "preconnect", href = "https://fonts.gstatic.com")
                            link(rel = "stylesheet", href = "https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&display=swap")
                            style {
                                unsafe {
                                    raw("""
                                        :root {
                                            --bg: #0f172a;
                                            --bg-muted: #1e293b;
                                            --panel: #ffffff;
                                            --primary: #5f6af8;
                                            --primary-dark: #4b55d1;
                                            --border: #e2e8f0;
                                            --text: #0f172a;
                                            --muted: #64748b;
                                            --success: #10b981;
                                            --error: #ef4444;
                                            --warm: #f97316;
                                        }
                                        * { box-sizing: border-box; }
                                        body {
                                            margin: 0;
                                            font-family: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
                                            background: radial-gradient(circle at top, rgba(95,106,248,0.25), transparent 60%), #0b1120;
                                            min-height: 100vh;
                                            color: var(--text);
                                            padding: 32px 16px 48px;
                                        }
                                        .page {
                                            max-width: 1320px;
                                            margin: 0 auto;
                                        }
                                        .hero {
                                            background: linear-gradient(135deg, #1f1f3d, #131624);
                                            border-radius: 24px;
                                            padding: 32px 36px;
                                            color: white;
                                            display: flex;
                                            flex-wrap: wrap;
                                            gap: 24px;
                                            align-items: center;
                                            box-shadow: 0 30px 80px rgba(14,21,37,0.5);
                                        }
                                        .hero h1 {
                                            font-size: clamp(2rem, 4vw, 2.75rem);
                                            margin: 0;
                                        }
                                        .hero p {
                                            margin: 8px 0 0;
                                            color: rgba(255,255,255,0.8);
                                            max-width: 560px;
                                        }
                                        .hero-actions {
                                            margin-left: auto;
                                            display: flex;
                                            gap: 12px;
                                            flex-wrap: wrap;
                                        }
                                        .hero-btn {
                                            border: none;
                                            border-radius: 999px;
                                            padding: 12px 22px;
                                            font-weight: 600;
                                            cursor: pointer;
                                            transition: transform 0.15s ease, box-shadow 0.15s ease;
                                        }
                                        .hero-btn.primary {
                                            background: white;
                                            color: var(--primary);
                                        }
                                        .hero-btn.secondary {
                                            background: rgba(255,255,255,0.1);
                                            color: white;
                                        }
                                        .hero-btn:hover {
                                            transform: translateY(-1px);
                                            box-shadow: 0 8px 20px rgba(15,23,42,0.25);
                                        }
                                        .layout {
                                            display: grid;
                                            grid-template-columns: minmax(320px, 360px) minmax(0, 1fr);
                                            gap: 24px;
                                            margin-top: 28px;
                                        }
                                        .panel {
                                            background: var(--panel);
                                            border-radius: 22px;
                                            padding: 24px;
                                            box-shadow: 0 20px 60px rgba(15,23,42,0.08);
                                        }
                                        .panel h3 {
                                            margin: 0 0 18px;
                                            font-size: 1.1rem;
                                            color: var(--text);
                                        }
                                        .builder-panel form {
                                            display: flex;
                                            flex-direction: column;
                                            gap: 16px;
                                        }
                                        .field-group label {
                                            display: flex;
                                            justify-content: space-between;
                                            font-weight: 600;
                                            font-size: 0.92rem;
                                            margin-bottom: 6px;
                                            color: var(--text);
                                        }
                                        .field-group input,
                                        .field-group textarea,
                                        .field-group select {
                                            width: 100%;
                                            border-radius: 14px;
                                            border: 1px solid var(--border);
                                            padding: 12px 14px;
                                            font-size: 0.95rem;
                                            font-family: inherit;
                                            transition: border 0.15s ease, box-shadow 0.15s ease;
                                        }
                                        .field-group input:focus,
                                        .field-group textarea:focus,
                                        .field-group select:focus {
                                            outline: none;
                                            border-color: var(--primary);
                                            box-shadow: 0 0 0 3px rgba(95,106,248,0.15);
                                        }
                                        .task-stack {
                                            display: flex;
                                            flex-direction: column;
                                            gap: 14px;
                                        }
                                        .agent-task-item {
                                            display: flex;
                                            gap: 12px;
                                            background: #f8fafc;
                                            border-radius: 14px;
                                            padding: 14px;
                                            align-items: stretch;
                                        }
                                        .agent-task-item textarea {
                                            resize: vertical;
                                            min-height: 60px;
                                        }
                                        .task-actions {
                                            display: flex;
                                            gap: 12px;
                                        }
                                        .ghost-btn,
                                        .solid-btn {
                                            border-radius: 14px;
                                            border: none;
                                            padding: 12px 16px;
                                            font-size: 0.95rem;
                                            font-weight: 600;
                                            cursor: pointer;
                                        }
                                        .ghost-btn {
                                            background: rgba(95,106,248,0.08);
                                            color: var(--primary);
                                        }
                                        .ghost-btn:hover { background: rgba(95,106,248,0.12); }
                                        .solid-btn {
                                            background: var(--primary);
                                            color: white;
                                        }
                                        .solid-btn:hover { background: var(--primary-dark); }
                                        .stats-grid {
                                            display: grid;
                                            gap: 16px;
                                            grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
                                        }
                                        .stat-card {
                                            background: #f8fafc;
                                            border-radius: 16px;
                                            padding: 16px;
                                        }
                                        .stat-label {
                                            color: var(--muted);
                                            font-size: 0.85rem;
                                            margin-bottom: 4px;
                                        }
                                        .stat-value {
                                            font-size: 1.8rem;
                                            font-weight: 700;
                                        }
                                        .search-bar {
                                            display: flex;
                                            align-items: center;
                                            gap: 12px;
                                            padding: 12px 16px;
                                            border-radius: 16px;
                                            border: 1px solid var(--border);
                                        }
                                        .search-bar input {
                                            flex: 1;
                                            border: none;
                                            font-size: 1rem;
                                            font-family: inherit;
                                        }
                                        .search-bar input:focus { outline: none; }
                                        .pipeline-grid {
                                            display: grid;
                                            gap: 18px;
                                            grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
                                        }
                                        .pipeline-card {
                                            border: 1px solid var(--border);
                                            border-radius: 18px;
                                            padding: 18px;
                                            display: flex;
                                            flex-direction: column;
                                            gap: 12px;
                                            transition: box-shadow 0.2s ease, border-color 0.2s ease;
                                        }
                                        .pipeline-card:hover {
                                            border-color: rgba(95,106,248,0.4);
                                            box-shadow: 0 12px 30px rgba(15,23,42,0.12);
                                        }
                                        .pipeline-meta {
                                            display: flex;
                                            gap: 8px;
                                            flex-wrap: wrap;
                                        }
                                        .badge {
                                            background: rgba(99,102,241,0.1);
                                            color: var(--primary-dark);
                                            padding: 4px 10px;
                                            border-radius: 999px;
                                            font-size: 0.8rem;
                                            font-weight: 600;
                                        }
                                        .badge.neutral { background: rgba(100,116,139,0.15); color: var(--muted); }
                                        .badge.small { font-size: 0.72rem; padding: 3px 8px; }
                                        .results-panel {
                                            display: none;
                                            flex-direction: column;
                                            gap: 16px;
                                        }
                                        .results-panel.visible { display: flex; }
                                        .status-pill {
                                            display: inline-flex;
                                            align-items: center;
                                            gap: 6px;
                                            padding: 6px 12px;
                                            border-radius: 999px;
                                            font-weight: 600;
                                            font-size: 0.85rem;
                                        }
                                        .status-pill.running { background: rgba(249,115,22,0.12); color: var(--warm); }
                                        .status-pill.success { background: rgba(16,185,129,0.15); color: var(--success); }
                                        .status-pill.error { background: rgba(239,68,68,0.15); color: var(--error); }
                                        .timeline {
                                            display: flex;
                                            flex-direction: column;
                                            gap: 12px;
                                        }
                                        .timeline-entry {
                                            border: 1px solid var(--border);
                                            border-radius: 14px;
                                            padding: 14px;
                                            display: flex;
                                            gap: 12px;
                                        }
                                        .timeline-entry.success { border-color: rgba(16,185,129,0.3); }
                                        .timeline-entry.fail { border-color: rgba(239,68,68,0.3); }
                                        .timeline-entry pre {
                                            margin: 0;
                                            background: #f8fafc;
                                            padding: 10px;
                                            border-radius: 10px;
                                            overflow-x: auto;
                                            max-height: 220px;
                                        }
                                        .loader {
                                            width: 28px;
                                            height: 28px;
                                            border-radius: 50%;
                                            border: 3px solid rgba(15,23,42,0.1);
                                            border-top-color: var(--primary);
                                            animation: spin 1s linear infinite;
                                        }
                                        .empty-state {
                                            border: 1px dashed var(--border);
                                            border-radius: 16px;
                                            padding: 32px;
                                            text-align: center;
                                            color: var(--muted);
                                        }
                                        @keyframes spin { to { transform: rotate(360deg); } }
                                        @media (max-width: 1080px) {
                                            .layout { grid-template-columns: 1fr; }
                                        }
                                    """.trimIndent())
                                }
                            }
                        }
                        body {
                            div("page") {
                                div("hero") {
                                    div {
                                        h1 { +"Cotor Pipeline Studio" }
                                        p { +"설정 파일을 뒤지지 않고도 파이프라인을 만들고 실행 상황을 한눈에 확인하세요." }
                                    }
                                    div("hero-actions") {
                                        button(classes = "hero-btn primary") {
                                            attributes["onclick"] = "document.getElementById('nameInput').focus()"
                                            +"+ 새 파이프라인"
                                        }
                                        button(classes = "hero-btn secondary") {
                                            attributes["onclick"] = "loadPipelines(true)"
                                            +"↻ 목록 새로고침"
                                        }
                                    }
                                }

                                div("layout") {
                                    div("panel builder-panel") {
                                        h3 { +"파이프라인 빌더" }
                                        form {
                                            id = "createForm"
                                            div("field-group") {
                                                label {
                                                    +"이름"
                                                    span { +"· 필수" }
                                                }
                                                input {
                                                    id = "nameInput"
                                                    type = InputType.text
                                                    name = "name"
                                                    placeholder = "ex) codex-board-review"
                                                    required = true
                                                }
                                            }
                                            div("field-group") {
                                                label { +"설명" }
                                                input {
                                                    type = InputType.text
                                                    name = "description"
                                                    placeholder = "파이프라인 목적을 간단히 적어주세요"
                                                }
                                            }
                                            div {
                                                classes = setOf("field-group")
                                                label { +"AI 작업" }
                                            div(classes = "task-stack") {
                                                id = "agentTasks"
                                                div("agent-task-item") {
                                                    select {
                                                        name = "agent_0"
                                                            classes = setOf("agent-select-dropdown")
                                                            listOf("claude", "codex", "gemini", "copilot", "cursor", "opencode").forEach { agent ->
                                                                option {
                                                                    value = agent
                                                                    +agent.replaceFirstChar { it.uppercase() }
                                                                }
                                                            }
                                                        }
                                                        textArea {
                                                            name = "prompt_0"
                                                            placeholder = "이 AI가 수행할 작업을 입력하세요"
                                                            rows = "2"
                                                            required = true
                                                        }
                                                    }
                                                }
                                                div("task-actions") {
                                                    button(classes = "ghost-btn") {
                                                        type = ButtonType.button
                                                        attributes["onclick"] = "addAgentTask()"
                                                        +"＋ 작업 추가"
                                                    }
                                                    button(classes = "solid-btn") {
                                                        type = ButtonType.submit
                                                        +"생성 후 실행"
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    div {
                                        div(classes = "panel") {
                                            h3 { +"대시보드" }
                                            div(classes = "stats-grid") {
                                                div("stat-card") {
                                                    div("stat-label") { +"파이프라인" }
                                                    div("stat-value") {
                                                        id = "statPipelines"
                                                        +"0"
                                                    }
                                                }
                                                div("stat-card") {
                                                    div("stat-label") { +"에이전트" }
                                                    div("stat-value") {
                                                        id = "statAgents"
                                                        +"0"
                                                    }
                                                }
                                                div("stat-card") {
                                                    div("stat-label") { +"구성 파일" }
                                                    div("stat-value") {
                                                        id = "statConfigs"
                                                        +"0"
                                                    }
                                                }
                                                div("stat-card") {
                                                    div("stat-label") { +"최근 실행" }
                                                    div("stat-value") {
                                                        id = "statLastRun"
                                                        +"–"
                                                    }
                                                }
                                            }
                                        }

                                        div(classes = "panel") {
                                            h3 { +"파이프라인 탐색" }
                                            div(classes = "search-bar") {
                                                span { +"🔍" }
                                                input {
                                                    id = "pipelineSearch"
                                                    placeholder = "이름·설명·파일 경로로 검색"
                                                }
                                            }
                                            div(classes = "pipeline-grid") {
                                                id = "pipelineCards"
                                                div("empty-state") {
                                                    +"파이프라인 정보를 불러오는 중입니다..."
                                                }
                                            }
                                        }

                                        div(classes = "panel results-panel") {
                                            id = "resultsPanel"
                                            h3 {
                                                id = "resultsHeader"
                                                +"파이프라인 결과"
                                            }
                                            div {
                                                id = "resultsSummary"
                                                div("empty-state") {
                                                    +"실행할 파이프라인을 선택하면 상태가 표시됩니다."
                                                }
                                            }
                                            div(classes = "timeline") {
                                                id = "resultsTimeline"
                                            }
                                        }
                                    }
                                }
                            }

                            script {
                                unsafe {
                                    raw("""
                                        let taskCount = 1;
                                        const state = {
                                            pipelines: [],
                                            filtered: []
                                        };
                                        const stats = {
                                            pipelines: document.getElementById('statPipelines'),
                                            agents: document.getElementById('statAgents'),
                                            configs: document.getElementById('statConfigs'),
                                            lastRun: document.getElementById('statLastRun')
                                        };
                                        const pipelineCards = document.getElementById('pipelineCards');
                                        const searchInput = document.getElementById('pipelineSearch');
                                        const resultsPanel = document.getElementById('resultsPanel');
                                        const resultsHeader = document.getElementById('resultsHeader');
                                        const resultsSummary = document.getElementById('resultsSummary');
                                        const resultsTimeline = document.getElementById('resultsTimeline');

                                        function addAgentTask() {
                                            const container = document.getElementById('agentTasks');
                                            const item = document.createElement('div');
                                            item.className = 'agent-task-item';
                                            item.innerHTML = `
                                                <select name="agent_${'$'}{taskCount}" class="agent-select-dropdown">
                                                    <option value="claude">Claude</option>
                                                    <option value="codex">Codex</option>
                                                    <option value="gemini">Gemini</option>
                                                    <option value="copilot">Copilot</option>
                                                    <option value="cursor">Cursor</option>
                                                    <option value="opencode">OpenCode</option>
                                                </select>
                                                <textarea name="prompt_${'$'}{taskCount}" placeholder="이 AI가 수행할 작업" rows="2" required></textarea>
                                            `;
                                            container.appendChild(item);
                                            taskCount++;
                                        }

                                        document.getElementById('createForm').addEventListener('submit', async (event) => {
                                            event.preventDefault();
                                            const formData = new FormData(event.target);
                                            const tasks = [];
                                            for (let i = 0; i < taskCount; i++) {
                                                const agent = formData.get('agent_' + i);
                                                const prompt = formData.get('prompt_' + i);
                                                if (agent && prompt) tasks.push({ agent, prompt });
                                            }
                                            if (!tasks.length) {
                                                alert('최소 1개의 작업이 필요합니다.');
                                                return;
                                            }
                                            const payload = {
                                                name: formData.get('name'),
                                                description: formData.get('description'),
                                                tasks
                                            };
                                            try {
                                                const res = await fetch('/api/pipelines/create', {
                                                    method: 'POST',
                                                    headers: { 'Content-Type': 'application/json' },
                                                    body: JSON.stringify(payload)
                                                });
                                                const data = await res.json();
                                                if (!res.ok || !data.success) throw new Error(data.error || '생성 실패');
                                                event.target.reset();
                                                taskCount = 1;
                                                document.getElementById('agentTasks').innerHTML = '';
                                                addAgentTask();
                                                alert('파이프라인 생성 완료: ' + data.file);
                                                await loadPipelines(true);
                                                runPipeline(payload.name);
                                            } catch (err) {
                                                alert('에러: ' + err.message);
                                            }
                                        });

                                        if (searchInput) {
                                            searchInput.addEventListener('input', (e) => {
                                                filterPipelines(e.target.value);
                                            });
                                        }

                                        function filterPipelines(keyword) {
                                            const term = keyword.trim().toLowerCase();
                                            state.filtered = state.pipelines.filter(p => {
                                                if (!term) return true;
                                                return (
                                                    p.name.toLowerCase().includes(term) ||
                                                    (p.description && p.description.toLowerCase().includes(term)) ||
                                                    (p.configFile && p.configFile.toLowerCase().includes(term))
                                                );
                                            });
                                            renderPipelines();
                                        }

                                        async function loadPipelines(force = false) {
                                            if (force) {
                                                pipelineCards.innerHTML = '<div class="empty-state">파이프라인을 새로 불러오는 중입니다...</div>';
                                            }
                                            try {
                                                const res = await fetch('/api/pipelines');
                                                const data = await res.json();
                                                state.pipelines = data;
                                                state.filtered = data;
                                                updateStats();
                                                renderPipelines();
                                            } catch (err) {
                                                pipelineCards.innerHTML = '<div class="empty-state">목록을 불러오지 못했습니다.</div>';
                                            }
                                        }

                                        function updateStats() {
                                            stats.pipelines.textContent = state.pipelines.length;
                                            const agents = new Set();
                                            const configs = new Set();
                                            state.pipelines.forEach(p => {
                                                (p.agents || []).forEach(a => agents.add(a));
                                                configs.add(p.configFile);
                                            });
                                            stats.agents.textContent = agents.size;
                                            stats.configs.textContent = configs.size;
                                        }

                                        function renderPipelines() {
                                            if (!state.filtered.length) {
                                                pipelineCards.innerHTML = '<div class="empty-state">조건에 맞는 파이프라인이 없습니다.</div>';
                                                return;
                                            }
                                            pipelineCards.innerHTML = state.filtered.map(p => `
                                                <div class="pipeline-card">
                                                    <div>
                                                        <div style="font-weight:700;font-size:1.1rem;">${'$'}{p.name}</div>
                                                        <div style="color:var(--muted);font-size:0.9rem;">${'$'}{p.description || '설명 없음'}</div>
                                                    </div>
                                                    <div class="pipeline-meta">
                                                        <span class="badge">${'$'}{p.executionMode}</span>
                                                        <span class="badge neutral">${'$'}{p.stageCount} stages</span>
                                                        <span class="badge neutral small">${'$'}{p.configFile}</span>
                                                    </div>
                                                    <div style="font-size:0.85rem;color:var(--muted);">
                                                        Agents · ${'$'}{(p.agents || []).join(', ')}
                                                    </div>
                                                    <div style="display:flex;gap:10px;">
                                                        <button class="ghost-btn" onclick="runPipeline('${'$'}{p.name}')">실행</button>
                                                    </div>
                                                </div>
                                            `).join('');
                                        }

                                        function setResultsState(stateText, options = {}) {
                                            resultsPanel.classList.add('visible');
                                            resultsHeader.textContent = stateText;
                                            if (options.type === 'loading') {
                                                resultsSummary.innerHTML = `
                                                    <div class="status-pill running">실행 중</div>
                                                    <div class="loader"></div>
                                                `;
                                                resultsTimeline.innerHTML = '';
                                            } else if (options.type === 'error') {
                                                resultsSummary.innerHTML = `
                                                    <div class="status-pill error">실패</div>
                                                    <p style="color:var(--error);margin:8px 0 0;">${'$'}{options.message}</p>
                                                `;
                                                resultsTimeline.innerHTML = '';
                                            }
                                        }

                                        async function runPipeline(name) {
                                            setResultsState('실행 중 · ' + name, { type: 'loading' });
                                            try {
                                                const res = await fetch('/api/run/' + encodeURIComponent(name), { method: 'POST' });
                                                const data = await res.json();
                                                if (!res.ok || data.error) throw new Error(data.error || '실행 실패');
                                                renderResults(name, data);
                                                stats.lastRun.textContent = new Date().toLocaleTimeString();
                                            } catch (err) {
                                                setResultsState('실패 · ' + name, { type: 'error', message: err.message });
                                            }
                                        }

                                        function renderResults(name, data) {
                                            resultsPanel.classList.add('visible');
                                            resultsHeader.textContent = '실행 결과 · ' + name;
                                            const success = data.successCount || 0;
                                            const total = data.totalAgents || 0;
                                            resultsSummary.innerHTML = `
                                                <div class="status-pill success">완료</div>
                                                <div>총 ${'$'}{total}단계 중 ${'$'}{success}단계 성공 · ${'$'}{data.totalDuration}ms</div>
                                            `;
                                            const timelineEntries = (data.timeline && data.timeline.length) ? data.timeline : null;
                                            if (timelineEntries) {
                                                resultsTimeline.innerHTML = timelineEntries.map(entry => {
                                                    const css = entry.state === 'FAILED' ? 'fail' : (entry.state === 'COMPLETED' ? 'success' : '');
                                                    return `
                                                        <div class="timeline-entry ${'$'}{css}">
                                                            <div>
                                                                <strong>${'$'}{entry.stageId}</strong>
                                                                <div style="color:var(--muted);font-size:0.85rem;">${'$'}{entry.durationMs || '-'}ms</div>
                                                            </div>
                                                            <pre>${'$'}{entry.outputPreview || entry.message}</pre>
                                                        </div>
                                                    `;
                                                }).join('');
                                            } else {
                                                resultsTimeline.innerHTML = (data.results || []).map(r => {
                                                    const ok = r.isSuccess !== false && !r.error;
                                                    const content = r.output || r.error || '결과가 비어있습니다.';
                                                    return `
                                                        <div class="timeline-entry ${'$'}{ok ? 'success' : 'fail'}">
                                                            <div>
                                                                <strong>${'$'}{r.agentName}</strong>
                                                                <div style="color:var(--muted);font-size:0.85rem;">${'$'}{r.duration}ms</div>
                                                            </div>
                                                            <pre>${'$'}{content}</pre>
                                                        </div>
                                                    `;
                                                }).join('');
                                            }
                                        }

                                        window.runPipeline = runPipeline;
                                        window.addAgentTask = addAgentTask;
                                        loadPipelines();
                                    """.trimIndent())
                                }
                            }
                        }
                    }
                }

// API endpoints
                get("/api/pipelines") {
                    try {
                        val summaries = mutableListOf<PipelineSummary>()

                        val yamlFiles = java.io.File(".").walkTopDown()
                            .filter { it.extension in listOf("yaml", "yml") }
                            .filter { !it.path.contains("node_modules") && !it.path.contains(".git") }
                            .filter { file ->
                                file.path.contains("test/") ||
                                    file.readText().contains("# cotor-pipeline") ||
                                    file.readText().contains("version: \"1.0\"")
                            }
                            .toList()

                        for (yamlFile in yamlFiles) {
                            try {
                                val config = configRepository.loadConfig(yamlFile.toPath())
                                config.pipelines.forEach { pipeline ->
                                    val agents = pipeline.stages.map { it.agent.name }.distinct()
                                    summaries.add(
                                        PipelineSummary(
                                            name = pipeline.name,
                                            description = pipeline.description.ifBlank { "설명 없음" },
                                            configFile = yamlFile.path,
                                            stageCount = pipeline.stages.size,
                                            executionMode = pipeline.executionMode.name,
                                            agents = agents,
                                            lastModified = yamlFile.lastModified()
                                        )
                                    )
                                }
                            } catch (_: Exception) {
                                // Ignore individual config errors
                            }
                        }

                        call.respond(summaries)
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
                    }
                }

                post("/api/run/{name}") {
                    val name = call.parameters["name"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                    
                    try {
                        // Find config file with this pipeline
                        val configPaths = listOf(
                            Path("cotor.yaml"),
                            Path("test/multi-compare.yaml"),
                            Path("test/creative-collab.yaml")
                        )
                        
                        var foundConfig: com.cotor.model.CotorConfig? = null
                        var foundPipeline: com.cotor.model.Pipeline? = null
                        
                        for (configPath in configPaths) {
                            try {
                                val config = configRepository.loadConfig(configPath)
                                val pipeline = config.pipelines.find { it.name == name }
                                if (pipeline != null) {
                                    foundConfig = config
                                    foundPipeline = pipeline
                                    break
                                }
                            } catch (e: Exception) {
                                // Skip
                            }
                        }
                        
                        if (foundConfig == null || foundPipeline == null) {
                            return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Pipeline not found"))
                        }
                        
                        foundConfig.agents.forEach { agentRegistry.registerAgent(it) }
                        val timelineResult = timelineCollector.runWithTimeline(name) {
                            orchestrator.executePipeline(foundPipeline)
                        }
                        val result = timelineResult.result
                        
                        call.respond(mapOf(
                            "totalAgents" to result.totalAgents,
                            "successCount" to result.successCount,
                            "totalDuration" to result.totalDuration,
                            "results" to result.results.map {
                                mapOf(
                                    "agentName" to it.agentName,
                                    "isSuccess" to it.isSuccess,
                                    "duration" to it.duration,
                                    "output" to it.output,
                                    "error" to it.error
                                )
                            },
                            "timeline" to timelineResult.timeline.map { it.toPayload() }
                        ))
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
                    }
                }
                
                // Create new pipeline
                post("/api/pipelines/create") {
                    try {
                        @Serializable
                        data class TaskData(val agent: String, val prompt: String)
                        @Serializable
                        data class CreateRequest(val name: String, val description: String, val tasks: List<TaskData>)
                        
                        val request = call.receive<CreateRequest>()
                        val pipelineName = request.name
                        val description = request.description
                        val tasks = request.tasks
                        
                        if (tasks.isEmpty()) {
                            return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "At least one task required"))
                        }
                        
                        val agents = tasks.map { it.agent }.distinct()
                        
                        // Create YAML content
                        val yamlContent = """
# cotor-pipeline
version: "1.0"

agents:
${agents.joinToString("\n") { """  - name: $it
    pluginClass: com.cotor.data.plugin.${it.replaceFirstChar { c -> c.uppercase() }}Plugin
    timeout: 90000""" }}

pipelines:
  - name: $pipelineName
    description: "$description"
    executionMode: PARALLEL
    stages:
${tasks.mapIndexed { i, task -> 
    val enhancedPrompt = "${task.prompt} - 반드시 파일을 생성하고 코드를 작성해주세요. 설명만 하지 말고 실제 파일을 만들어주세요."
    """      - id: ${task.agent}-task-${i+1}
        agent:
          name: ${task.agent}
        input: "$enhancedPrompt""""
}.joinToString("\n")}

security:
  useWhitelist: true
  allowedExecutables: [${agents.joinToString(", ")}]
  allowedDirectories: [/usr/local/bin, /opt/homebrew/bin, /Users/heodongun/Desktop/cotor/test]
                        """.trimIndent()
                        
                        // Save to file
                        val fileName = "test/${pipelineName}.yaml"
                        java.io.File(fileName).writeText(yamlContent)
                        
                        call.respond(mapOf(
                            "success" to true,
                            "message" to "Pipeline created: $fileName",
                            "file" to fileName
                        ))
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Unknown error")))
                    }
                }
            }
        }.start(wait = true)
    }
}

private fun StageTimelineEntry.toPayload(): Map<String, Any?> = mapOf(
    "stageId" to stageId,
    "state" to state.name,
    "timestamp" to timestamp.toString(),
    "message" to message,
    "durationMs" to durationMs,
    "outputPreview" to outputPreview
)
