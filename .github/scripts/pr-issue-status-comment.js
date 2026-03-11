const BOT_MARKER_PREFIX = "<!-- cotor:pr-ci-status pr=";

function unique(values) {
    return [...new Set(values)];
}

function extractIssueNumbers(text) {
    if (!text) {
        return [];
    }

    const matches = text.matchAll(/(^|[^A-Za-z0-9_])#(\d+)/g);
    return unique(
        [...matches]
            .map((match) => Number.parseInt(match[2], 10))
            .filter((value) => Number.isInteger(value) && value > 0),
    );
}

function extractTicketTokens(...values) {
    const tokenPattern = /\b[A-Z][A-Z0-9]+-\d+\b/g;
    return unique(
        values
            .filter(Boolean)
            .flatMap((value) => [...value.toUpperCase().matchAll(tokenPattern)].map((match) => match[0])),
    );
}

function issueCommentMarker(prNumber) {
    return `${BOT_MARKER_PREFIX}${prNumber} -->`;
}

function matchesWorkflowName(context, workflowName) {
    const target = workflowName.trim().toLowerCase();
    if (!target) {
        return false;
    }

    const candidates = [context.workflowName, context.name, context.context]
        .filter(Boolean)
        .map((value) => value.toLowerCase());

    return candidates.some((value) => {
        return (
            value === target ||
            value.startsWith(`${target} /`) ||
            value.startsWith(`${target}:`) ||
            value.includes(` ${target} `) ||
            value.endsWith(` ${target}`) ||
            value.includes(`/${target}`)
        );
    });
}

function reduceCheckContextState(context) {
    if (context.__typename === "CheckRun") {
        if (context.status !== "COMPLETED") {
            return "pending";
        }

        switch ((context.conclusion || "").toUpperCase()) {
            case "SUCCESS":
            case "NEUTRAL":
            case "SKIPPED":
                return "success";
            case "CANCELLED":
                return "cancelled";
            case "FAILURE":
            case "TIMED_OUT":
            case "ACTION_REQUIRED":
            case "STARTUP_FAILURE":
            case "STALE":
                return "failure";
            default:
                return "pending";
        }
    }

    const state = (context.state || "").toUpperCase();
    switch (state) {
        case "SUCCESS":
            return "success";
        case "ERROR":
        case "FAILURE":
            return "failure";
        case "EXPECTED":
        case "PENDING":
            return "pending";
        default:
            return "pending";
    }
}

function reduceWorkflowRunState(status, conclusion) {
    const normalizedStatus = (status || "").toUpperCase();
    const normalizedConclusion = (conclusion || "").toUpperCase();

    if (normalizedStatus && normalizedStatus !== "COMPLETED") {
        return "pending";
    }

    switch (normalizedConclusion) {
        case "SUCCESS":
        case "NEUTRAL":
        case "SKIPPED":
            return "success";
        case "CANCELLED":
            return "cancelled";
        case "FAILURE":
        case "TIMED_OUT":
        case "ACTION_REQUIRED":
        case "STARTUP_FAILURE":
        case "STALE":
            return "failure";
        default:
            return "not_started";
    }
}

function summarizeCiState(contexts, workflowName, workflowRun) {
    const relevantContexts = (contexts || []).filter((context) => matchesWorkflowName(context, workflowName));
    const derivedStates = relevantContexts.map(reduceCheckContextState);

    let state = "not_started";
    if (derivedStates.includes("failure")) {
        state = "failure";
    } else if (derivedStates.includes("cancelled")) {
        state = "cancelled";
    } else if (derivedStates.includes("pending")) {
        state = "pending";
    } else if (derivedStates.includes("success")) {
        state = "success";
    } else if (workflowRun) {
        state = reduceWorkflowRunState(workflowRun.status, workflowRun.conclusion);
    }

    switch (state) {
        case "success":
            return { state, label: "🟢 성공" };
        case "failure":
            return { state, label: "🔴 실패" };
        case "cancelled":
            return { state, label: "⚫ 취소됨" };
        case "pending":
            return { state, label: "🟡 진행 중" };
        default:
            return { state: "not_started", label: "⚪ 미실행" };
    }
}

function summarizePrState(pr) {
    if (pr.state === "MERGED" || pr.mergedAt) {
        return "merged";
    }
    if (pr.state === "CLOSED") {
        return "closed";
    }
    if (pr.isDraft) {
        return "draft";
    }
    return "open";
}

function buildCommentBody({ pr, ciStatus, workflowName, timestamp }) {
    return [
        issueCommentMarker(pr.number),
        "## PR / CI 상태",
        "",
        `- PR 링크: [#${pr.number}](${pr.url})`,
        `- 브랜치: \`${pr.headRefName}\``,
        `- PR 상태: \`${summarizePrState(pr)}\``,
        `- ${workflowName} 상태: ${ciStatus.label}`,
        `- 마지막 갱신: ${timestamp}`,
    ].join("\n");
}

async function fetchIssue(github, owner, repo, issueNumber) {
    const response = await github.rest.issues.get({
        owner,
        repo,
        issue_number: issueNumber,
    });

    if (response.data.pull_request) {
        return null;
    }

    return {
        number: response.data.number,
        title: response.data.title,
        url: response.data.html_url,
    };
}

async function searchIssuesByToken(github, owner, repo, token) {
    const response = await github.rest.search.issuesAndPullRequests({
        q: `repo:${owner}/${repo} is:issue "${token}" in:title,body`,
        per_page: 20,
    });

    return response.data.items
        .filter((item) => !item.pull_request)
        .map((item) => ({
            number: item.number,
            title: item.title,
            url: item.html_url,
        }));
}

async function resolveLinkedIssues({ github, owner, repo, pr }) {
    const issues = new Map();

    for (const issue of pr.closingIssuesReferences || []) {
        issues.set(issue.number, issue);
    }

    const explicitIssueNumbers = extractIssueNumbers(`${pr.title || ""}\n${pr.body || ""}`);
    for (const issueNumber of explicitIssueNumbers) {
        if (issues.has(issueNumber)) {
            continue;
        }

        const issue = await fetchIssue(github, owner, repo, issueNumber);
        if (issue) {
            issues.set(issue.number, issue);
        }
    }

    const tokens = extractTicketTokens(pr.title || "", pr.body || "", pr.headRefName || "");
    for (const token of tokens) {
        const matches = await searchIssuesByToken(github, owner, repo, token);
        for (const issue of matches) {
            issues.set(issue.number, issue);
        }
    }

    return [...issues.values()].sort((left, right) => left.number - right.number);
}

async function fetchPullRequest({ github, owner, repo, number }) {
    const result = await github.graphql(
        `
            query PullRequestWithChecks($owner: String!, $repo: String!, $number: Int!) {
              repository(owner: $owner, name: $repo) {
                pullRequest(number: $number) {
                  number
                  url
                  title
                  body
                  headRefName
                  state
                  isDraft
                  mergedAt
                  closingIssuesReferences(first: 20) {
                    nodes {
                      number
                      title
                      url
                    }
                  }
                  commits(last: 1) {
                    nodes {
                      commit {
                        statusCheckRollup {
                          contexts(first: 100) {
                            nodes {
                              __typename
                              ... on CheckRun {
                                name
                                status
                                conclusion
                                workflowName
                              }
                              ... on StatusContext {
                                context
                                state
                              }
                            }
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
        `,
        { owner, repo, number },
    );

    const pullRequest = result.repository.pullRequest;
    const latestCommit = pullRequest.commits.nodes[pullRequest.commits.nodes.length - 1];
    const checkContexts = latestCommit?.commit?.statusCheckRollup?.contexts?.nodes || [];

    return {
        number: pullRequest.number,
        url: pullRequest.url,
        title: pullRequest.title,
        body: pullRequest.body || "",
        headRefName: pullRequest.headRefName,
        state: pullRequest.state,
        isDraft: pullRequest.isDraft,
        mergedAt: pullRequest.mergedAt,
        closingIssuesReferences: pullRequest.closingIssuesReferences.nodes || [],
        checkContexts,
    };
}

async function upsertIssueComment({ github, owner, repo, issueNumber, prNumber, body }) {
    const marker = issueCommentMarker(prNumber);
    const comments = await github.paginate(github.rest.issues.listComments, {
        owner,
        repo,
        issue_number: issueNumber,
        per_page: 100,
    });

    const existing = comments.find((comment) => comment.body && comment.body.includes(marker));
    if (existing) {
        await github.rest.issues.updateComment({
            owner,
            repo,
            comment_id: existing.id,
            body,
        });
        return "updated";
    }

    await github.rest.issues.createComment({
        owner,
        repo,
        issue_number: issueNumber,
        body,
    });
    return "created";
}

function resolvePullRequestNumbers(context) {
    if (context.eventName === "pull_request_target") {
        return [context.payload.pull_request.number];
    }

    if (context.eventName === "workflow_run") {
        return unique((context.payload.workflow_run.pull_requests || []).map((pullRequest) => pullRequest.number));
    }

    return [];
}

async function run({ github, context, core }) {
    const workflowName = process.env.TARGET_WORKFLOW_NAME || "CI";
    const { owner, repo } = context.repo;
    const workflowRun = context.payload.workflow_run || null;
    const pullRequestNumbers = resolvePullRequestNumbers(context);

    if (!pullRequestNumbers.length) {
        core.info("No pull requests associated with this event. Skipping.");
        return;
    }

    for (const pullRequestNumber of pullRequestNumbers) {
        const pr = await fetchPullRequest({
            github,
            owner,
            repo,
            number: pullRequestNumber,
        });

        const linkedIssues = await resolveLinkedIssues({
            github,
            owner,
            repo,
            pr,
        });

        if (!linkedIssues.length) {
            core.info(`No linked issues found for PR #${pullRequestNumber}.`);
            continue;
        }

        const ciStatus = summarizeCiState(pr.checkContexts, workflowName, workflowRun);
        const timestamp = new Date().toISOString();
        const body = buildCommentBody({
            pr,
            ciStatus,
            workflowName,
            timestamp,
        });

        for (const issue of linkedIssues) {
            const result = await upsertIssueComment({
                github,
                owner,
                repo,
                issueNumber: issue.number,
                prNumber: pullRequestNumber,
                body,
            });
            core.info(`${result} bot comment on issue #${issue.number} for PR #${pullRequestNumber}.`);
        }
    }
}

module.exports = {
    BOT_MARKER_PREFIX,
    buildCommentBody,
    extractIssueNumbers,
    extractTicketTokens,
    issueCommentMarker,
    matchesWorkflowName,
    reduceCheckContextState,
    reduceWorkflowRunState,
    resolvePullRequestNumbers,
    summarizeCiState,
    summarizePrState,
    run,
};
