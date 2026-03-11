const test = require("node:test");
const assert = require("node:assert/strict");

const {
    BOT_MARKER_PREFIX,
    buildCommentBody,
    extractIssueNumbers,
    extractTicketTokens,
    matchesWorkflowName,
    reduceWorkflowRunState,
    summarizeCiState,
    summarizePrState,
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
