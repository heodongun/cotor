const express = require('express');
const path = require('path');

const app = express();
const PORT = process.env.PORT || 4310;

app.use(express.json({ limit: '1mb' }));
app.use(express.static(path.join(__dirname, 'public')));

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const AGENTS = {
  researcher: {
    id: 'researcher',
    name: 'Research Agent',
    role: '요구사항/자료 조사',
    run: async ({ input, context }) => {
      await sleep(200);
      return `연구 요약: ${input}\n핵심 컨텍스트: ${Object.keys(context).join(', ') || '없음'}`;
    },
  },
  planner: {
    id: 'planner',
    name: 'Planner Agent',
    role: '작업 계획 수립',
    run: async ({ input, context }) => {
      await sleep(200);
      const deps = Object.values(context).join('\n').slice(0, 200);
      return `실행 계획:\n1) 범위 정의\n2) 구현\n3) 검증\n입력: ${input}\n참조: ${deps || '없음'}`;
    },
  },
  coder: {
    id: 'coder',
    name: 'Coder Agent',
    role: '코드 작성',
    run: async ({ input, context }) => {
      await sleep(250);
      return `코드 산출물(초안):\n// task: ${input}\n// using context keys: ${Object.keys(context).join(', ') || 'none'}`;
    },
  },
  tester: {
    id: 'tester',
    name: 'Tester Agent',
    role: '테스트/검증',
    run: async ({ input, context }) => {
      await sleep(180);
      const hasCode = Object.values(context).some((v) => String(v).includes('코드 산출물'));
      return hasCode
        ? `테스트 결과: 기본 시나리오 PASS\n추가 점검: 엣지케이스 필요\n요청: ${input}`
        : `테스트 불가: 코드 산출물 의존성이 필요합니다.\n요청: ${input}`;
    },
  },
  reviewer: {
    id: 'reviewer',
    name: 'Reviewer Agent',
    role: '품질/리스크 리뷰',
    run: async ({ input, context }) => {
      await sleep(180);
      return `리뷰 요약: 큰 구조는 양호.\n개선 제안: 로깅 강화, 예외 처리 추가.\n검토 요청: ${input}\n수집 결과 수: ${Object.keys(context).length}`;
    },
  },
};

function topologicalOrder(nodes, edges) {
  const indegree = new Map();
  const graph = new Map();

  for (const n of nodes) {
    indegree.set(n.id, 0);
    graph.set(n.id, []);
  }

  for (const e of edges) {
    if (!graph.has(e.from) || !graph.has(e.to)) {
      throw new Error(`invalid edge: ${e.from} -> ${e.to}`);
    }
    graph.get(e.from).push(e.to);
    indegree.set(e.to, indegree.get(e.to) + 1);
  }

  const q = [...nodes.filter((n) => indegree.get(n.id) === 0).map((n) => n.id)];
  const out = [];

  while (q.length) {
    const cur = q.shift();
    out.push(cur);
    for (const next of graph.get(cur)) {
      indegree.set(next, indegree.get(next) - 1);
      if (indegree.get(next) === 0) q.push(next);
    }
  }

  if (out.length !== nodes.length) {
    throw new Error('workflow has cycle');
  }

  return out;
}

app.get('/api/agents', (req, res) => {
  res.json({
    agents: Object.values(AGENTS).map(({ id, name, role }) => ({ id, name, role })),
  });
});

app.post('/api/workflows/execute', async (req, res) => {
  try {
    const { nodes = [], edges = [] } = req.body || {};
    if (!Array.isArray(nodes) || nodes.length === 0) {
      return res.status(400).json({ error: 'nodes must be non-empty array' });
    }

    for (const n of nodes) {
      if (!n.id || !n.agentId) {
        return res.status(400).json({ error: 'each node requires id and agentId' });
      }
      if (!AGENTS[n.agentId]) {
        return res.status(400).json({ error: `unknown agentId: ${n.agentId}` });
      }
    }

    const order = topologicalOrder(nodes, edges);
    const nodeMap = new Map(nodes.map((n) => [n.id, n]));
    const incoming = new Map(nodes.map((n) => [n.id, []]));

    for (const e of edges) incoming.get(e.to).push(e.from);

    const results = {};
    const logs = [];

    for (const nodeId of order) {
      const n = nodeMap.get(nodeId);
      const deps = incoming.get(nodeId);
      const context = Object.fromEntries(deps.map((id) => [id, results[id]?.output ?? '']));

      logs.push({ nodeId, status: 'started', agentId: n.agentId, at: new Date().toISOString() });
      const output = await AGENTS[n.agentId].run({ input: n.input || '', context });
      results[nodeId] = {
        agentId: n.agentId,
        output,
        dependencies: deps,
      };
      logs.push({ nodeId, status: 'completed', at: new Date().toISOString() });
    }

    res.json({ order, results, logs });
  } catch (err) {
    res.status(400).json({ error: err.message || 'workflow execution failed' });
  }
});

app.listen(PORT, () => {
  console.log(`A2A Lab running: http://localhost:${PORT}`);
});
