#!/usr/bin/env python3

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request

from typing import Any


LINEAR_GRAPHQL_URL = "https://api.linear.app/graphql"
ISSUE_KEY_RE = re.compile(r"\b[A-Z][A-Z0-9]+-\d+\b")

ISSUE_QUERY = """
query IssueForFailureGate($id: String!) {
  issue(id: $id) {
    id
    identifier
    title
    assignee {
      id
      name
    }
    state {
      id
      name
      type
    }
    team {
      states {
        nodes {
          id
          name
          type
          position
        }
      }
    }
  }
}
"""

ISSUE_UPDATE_MUTATION = """
mutation UpdateIssueForFailureGate($id: String!, $input: IssueUpdateInput!) {
  issueUpdate(id: $id, input: $input) {
    success
    issue {
      id
      identifier
      assignee {
        id
      }
      state {
        id
        name
        type
      }
    }
  }
}
"""


def extract_issue_identifiers(*values: str | None) -> list[str]:
    identifiers: list[str] = []
    seen: set[str] = set()
    for value in values:
        if not value:
            continue
        for match in ISSUE_KEY_RE.findall(value.upper()):
            if match in seen:
                continue
            seen.add(match)
            identifiers.append(match)
    return identifiers


def select_reopened_state_id(current_state: dict[str, Any], team_states: list[dict[str, Any]]) -> str | None:
    state_name = (current_state.get("name") or "").strip().lower()
    state_type = (current_state.get("type") or "").strip().lower()
    if state_type != "completed" and "review" not in state_name:
        return None

    working_states = sorted(
        (
            state
            for state in team_states
            if (state.get("type") or "").strip().lower() == "started"
            and "review" not in (state.get("name") or "").strip().lower()
        ),
        key=lambda state: state.get("position") or 0,
    )
    if not working_states:
        return None

    for state in working_states:
        if (state.get("name") or "").strip().lower() == "in progress":
            return state.get("id")
    return working_states[0].get("id")


def build_issue_update_input(issue: dict[str, Any]) -> dict[str, Any]:
    update_input: dict[str, Any] = {}
    assignee = issue.get("assignee")
    if assignee:
        update_input["assigneeId"] = None

    target_state_id = select_reopened_state_id(
        current_state=issue.get("state") or {},
        team_states=((issue.get("team") or {}).get("states") or {}).get("nodes") or [],
    )
    if target_state_id and target_state_id != ((issue.get("state") or {}).get("id")):
        update_input["stateId"] = target_state_id

    return update_input


def load_pull_request_identifiers(event_payload: dict[str, Any]) -> list[str]:
    pull_request = event_payload.get("pull_request") or {}
    head = pull_request.get("head") or {}
    return extract_issue_identifiers(
        pull_request.get("title"),
        pull_request.get("body"),
        head.get("ref"),
        event_payload.get("ref"),
        os.environ.get("GITHUB_HEAD_REF"),
    )


def call_linear(query: str, variables: dict[str, Any], api_key: str, api_url: str) -> dict[str, Any]:
    request = urllib.request.Request(
        api_url,
        data=json.dumps({"query": query, "variables": variables}).encode("utf-8"),
        headers={
            "Authorization": api_key,
            "Content-Type": "application/json",
        },
        method="POST",
    )

    try:
        with urllib.request.urlopen(request) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"Linear API request failed with HTTP {exc.code}: {detail}") from exc

    if payload.get("errors"):
        raise RuntimeError(f"Linear API returned errors: {payload['errors']}")
    return payload["data"]


def update_issue_for_failure(identifier: str, api_key: str, api_url: str, dry_run: bool) -> bool:
    issue = call_linear(ISSUE_QUERY, {"id": identifier}, api_key, api_url)["issue"]
    if not issue:
        print(f"[linear-failure-gate] issue {identifier} was not found; skipping", file=sys.stderr)
        return False

    update_input = build_issue_update_input(issue)
    if not update_input:
        print(
            f"[linear-failure-gate] {identifier}: no reopen/unassign changes required",
            file=sys.stderr,
        )
        return False

    print(
        f"[linear-failure-gate] {identifier}: prepared update {json.dumps(update_input, ensure_ascii=True, sort_keys=True)}",
        file=sys.stderr,
    )
    if dry_run:
        return True

    result = call_linear(
        ISSUE_UPDATE_MUTATION,
        {"id": issue["id"], "input": update_input},
        api_key,
        api_url,
    )["issueUpdate"]
    if not result.get("success"):
        raise RuntimeError(f"Linear issue update did not succeed for {identifier}")

    updated_issue = result["issue"]
    print(
        f"[linear-failure-gate] {identifier}: updated to state={updated_issue['state']['name']} assignee={updated_issue['assignee']}",
        file=sys.stderr,
    )
    return True


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Reopen and unassign linked Linear issues when PR tests fail.",
    )
    parser.add_argument(
        "--event-path",
        default=os.environ.get("GITHUB_EVENT_PATH"),
        help="Path to the GitHub Actions event payload JSON.",
    )
    parser.add_argument(
        "--linear-api-key",
        default=os.environ.get("LINEAR_API_KEY") or os.environ.get("LINEAR_TOKEN"),
        help="Linear API key. Defaults to LINEAR_API_KEY/LINEAR_TOKEN.",
    )
    parser.add_argument(
        "--linear-api-url",
        default=LINEAR_GRAPHQL_URL,
        help="Linear GraphQL endpoint.",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Resolve identifiers and planned updates without writing to Linear.",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if not args.event_path:
        print("[linear-failure-gate] missing --event-path / GITHUB_EVENT_PATH", file=sys.stderr)
        return 1

    with open(args.event_path, "r", encoding="utf-8") as handle:
        event_payload = json.load(handle)

    identifiers = load_pull_request_identifiers(event_payload)
    if not identifiers:
        print("[linear-failure-gate] no linked Linear identifiers found in PR context", file=sys.stderr)
        return 0

    if not args.linear_api_key:
        print(
            "[linear-failure-gate] linked Linear issue detected but LINEAR_API_KEY is missing",
            file=sys.stderr,
        )
        return 1

    updated_any = False
    for identifier in identifiers:
        updated_any = update_issue_for_failure(
            identifier=identifier,
            api_key=args.linear_api_key,
            api_url=args.linear_api_url,
            dry_run=args.dry_run,
        ) or updated_any

    if not updated_any:
        print("[linear-failure-gate] completed without changes", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
