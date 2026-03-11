const test = require("node:test");
const assert = require("node:assert/strict");

const {
    BOT_MARKER_PREFIX,
    buildCommentBody,
    extractIssueNumbers,
    extractTicketTokens,
    matchesWorkflowName,
    reduceWorkflowRunState,
    resolveLinkedIssues,
    resolvePullRequestNumbers,
    summarizeCiState,
    summarizePrState,
    upsertIssueComment,
} = require("./pr-issue-status-comment");

test("extractIssueNumbers deduplicates explicit issue references", () => {
    assert.deepEqual(
        extractIssueNumbers("Fixes #144 and refs #144, follow-up in (#170)."),
        [144, 170],
    );
});

test("extractTicketTokens reads tokens from title, body, and branch names", () => {
    assert.deepEqual(
        extractTicketTokens("[COT-21] Bot", "linked to HEO-81", "feat/cot-21-heo-81"),
        ["COT-21", "HEO-81"],
    );
});

test("matchesWorkflowName recognizes workflow and job-style names", () => {
    assert.equal(matchesWorkflowName({ workflowName: "CI" }, "CI"), true);
    assert.equal(matchesWorkflowName({ name: "CI / verify" }, "CI"), true);
    assert.equal(matchesWorkflowName({ context: "docker / build" }, "CI"), false);
});

test("summarizeCiState prefers CI contexts over unrelated checks", () => {
    const ciStatus = summarizeCiState(
        [
            {
                __typename: "CheckRun",
                workflowName: "Docker Image",
                name: "docker",
                status: "COMPLETED",
                conclusion: "FAILURE",
            },
            {
                __typename: "CheckRun",
                workflowName: "CI",
                name: "verify",
                status: "COMPLETED",
                conclusion: "SUCCESS",
            },
        ],
        "CI",
        null,
    );

    assert.deepEqual(ciStatus, { state: "success", label: "🟢 성공" });
});

test("summarizeCiState falls back to workflow_run status when CI checks are absent", () => {
    const ciStatus = summarizeCiState([], "CI", {
        status: "in_progress",
        conclusion: null,
    });

    assert.deepEqual(ciStatus, { state: "pending", label: "🟡 진행 중" });
});

test("reduce workflow_run failure states", () => {
    assert.equal(reduceWorkflowRunState("completed", "failure"), "failure");
    assert.equal(reduceWorkflowRunState("completed", "cancelled"), "cancelled");
    assert.equal(reduceWorkflowRunState("requested", null), "pending");
});

test("resolveLinkedIssues combines closing refs, explicit refs, and ticket token search results", async () => {
    const github = {
        rest: {
            issues: {
                get: async ({ issue_number }) => {
                    if (issue_number === 170) {
                        return {
                            data: {
                                number: 170,
                                title: "Explicit issue",
                                html_url: "https://github.com/heodongun/cotor/issues/170",
                            },
                        };
                    }

                    if (issue_number === 188) {
                        return {
                            data: {
                                number: 188,
                                title: "Actually a PR",
                                html_url: "https://github.com/heodongun/cotor/pull/188",
                                pull_request: {},
                            },
                        };
                    }

                    throw new Error(`Unexpected issue lookup: ${issue_number}`);
                },
            },
            search: {
                issuesAndPullRequests: async ({ q }) => {
                    if (!q.includes("\"HEO-81\"")) {
                        throw new Error(`Unexpected search query: ${q}`);
                    }

                    return {
                        data: {
                            items: [
                                {
                                    number: 144,
                                    title: "Migrated issue",
                                    html_url: "https://github.com/heodongun/cotor/issues/144",
                                },
                                {
                                    number: 188,
                                    title: "Ignore PR search hit",
                                    html_url: "https://github.com/heodongun/cotor/pull/188",
                                    pull_request: {},
                                },
                            ],
                        },
                    };
                },
            },
        },
    };

    const linkedIssues = await resolveLinkedIssues({
        github,
        owner: "heodongun",
        repo: "cotor",
        pr: {
            title: "HEO-81: add automation",
            body: "Refs #170 and follow-up in #188",
            headRefName: "feat/heo-81-comment-bot",
            closingIssuesReferences: [
                {
                    number: 144,
                    title: "Migrated issue",
                    url: "https://github.com/heodongun/cotor/issues/144",
                },
            ],
        },
    });

    assert.deepEqual(linkedIssues, [
        {
            number: 144,
            title: "Migrated issue",
            url: "https://github.com/heodongun/cotor/issues/144",
        },
        {
            number: 170,
            title: "Explicit issue",
            url: "https://github.com/heodongun/cotor/issues/170",
        },
    ]);
});

test("resolvePullRequestNumbers supports workflow_run payloads with multiple PRs", () => {
    const pullRequestNumbers = resolvePullRequestNumbers({
        eventName: "workflow_run",
        payload: {
            workflow_run: {
                pull_requests: [{ number: 27 }, { number: 31 }, { number: 27 }],
            },
        },
    });

    assert.deepEqual(pullRequestNumbers, [27, 31]);
});

test("upsertIssueComment updates the existing sticky comment", async () => {
    const calls = [];
    const github = {
        paginate: async () => [
            {
                id: 9001,
                body: "<!-- cotor:pr-ci-status pr=27 -->\nold body",
            },
        ],
        rest: {
            issues: {
                listComments: Symbol("listComments"),
                updateComment: async (input) => calls.push({ type: "update", input }),
                createComment: async (input) => calls.push({ type: "create", input }),
            },
        },
    };

    const result = await upsertIssueComment({
        github,
        owner: "heodongun",
        repo: "cotor",
        issueNumber: 144,
        prNumber: 27,
        body: "new body",
    });

    assert.equal(result, "updated");
    assert.deepEqual(calls, [
        {
            type: "update",
            input: {
                owner: "heodongun",
                repo: "cotor",
                comment_id: 9001,
                body: "new body",
            },
        },
    ]);
});

test("upsertIssueComment creates a sticky comment when one does not exist", async () => {
    const calls = [];
    const github = {
        paginate: async () => [],
        rest: {
            issues: {
                listComments: Symbol("listComments"),
                updateComment: async (input) => calls.push({ type: "update", input }),
                createComment: async (input) => calls.push({ type: "create", input }),
            },
        },
    };

    const result = await upsertIssueComment({
        github,
        owner: "heodongun",
        repo: "cotor",
        issueNumber: 144,
        prNumber: 27,
        body: "new body",
    });

    assert.equal(result, "created");
    assert.deepEqual(calls, [
        {
            type: "create",
            input: {
                owner: "heodongun",
                repo: "cotor",
                issue_number: 144,
                body: "new body",
            },
        },
    ]);
});

test("buildCommentBody includes sticky marker and readable status lines", () => {
    const body = buildCommentBody({
        pr: {
            number: 27,
            url: "https://github.com/heodongun/cotor/pull/27",
            headRefName: "heo-81-pr-ci-comment-bot",
            state: "OPEN",
            isDraft: false,
            mergedAt: null,
        },
        ciStatus: { state: "pending", label: "🟡 진행 중" },
        workflowName: "CI",
        timestamp: "2026-03-11T06:44:00.000Z",
    });

    assert.match(body, new RegExp(`^${BOT_MARKER_PREFIX.replace(/[.*+?^${}()|[\\]\\\\]/g, "\\$&")}27 -->`));
    assert.match(body, /PR 링크: \[#27\]/);
    assert.match(body, /CI 상태: 🟡 진행 중/);
    assert.match(body, /PR 상태: `open`/);
});

test("summarizePrState recognizes merged, closed, draft, and open PRs", () => {
    assert.equal(summarizePrState({ state: "MERGED", isDraft: false, mergedAt: "2026-03-11T00:00:00Z" }), "merged");
    assert.equal(summarizePrState({ state: "CLOSED", isDraft: false, mergedAt: null }), "closed");
    assert.equal(summarizePrState({ state: "OPEN", isDraft: true, mergedAt: null }), "draft");
    assert.equal(summarizePrState({ state: "OPEN", isDraft: false, mergedAt: null }), "open");
});
