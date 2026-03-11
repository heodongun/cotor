import unittest

from scripts.linear_pr_failure_gate import build_issue_update_input
from scripts.linear_pr_failure_gate import extract_issue_identifiers
from scripts.linear_pr_failure_gate import load_pull_request_identifiers
from scripts.linear_pr_failure_gate import select_reopened_state_id


TEAM_STATES = [
    {"id": "backlog", "name": "Backlog", "type": "backlog", "position": 0},
    {"id": "todo", "name": "Todo", "type": "unstarted", "position": 1},
    {"id": "in-progress", "name": "In Progress", "type": "started", "position": 2},
    {"id": "in-review", "name": "In Review", "type": "started", "position": 3},
    {"id": "done", "name": "Done", "type": "completed", "position": 4},
]


class LinearPrFailureGateTest(unittest.TestCase):
    def test_extract_issue_identifiers_deduplicates_and_normalizes(self) -> None:
        identifiers = extract_issue_identifiers(
            "[heo-79] fix gate for HEO-80",
            "Body repeats heo-79 and references hEO-81.",
            "heodongun08/heo-82-failure-gate",
        )

        self.assertEqual(["HEO-79", "HEO-80", "HEO-81", "HEO-82"], identifiers)

    def test_load_pull_request_identifiers_uses_pr_fields(self) -> None:
        identifiers = load_pull_request_identifiers(
            {
                "pull_request": {
                    "title": "[HEO-79] gate enforcement",
                    "body": "Also relates to HEO-80",
                    "head": {"ref": "heodongun08/heo-81-branch"},
                },
            },
        )

        self.assertEqual(["HEO-79", "HEO-80", "HEO-81"], identifiers)

    def test_select_reopened_state_prefers_in_progress_for_review_state(self) -> None:
        state_id = select_reopened_state_id(
            current_state={"id": "in-review", "name": "In Review", "type": "started"},
            team_states=TEAM_STATES,
        )

        self.assertEqual("in-progress", state_id)

    def test_select_reopened_state_uses_started_fallback_when_in_progress_missing(self) -> None:
        state_id = select_reopened_state_id(
            current_state={"id": "done", "name": "Done", "type": "completed"},
            team_states=[
                {"id": "todo", "name": "Todo", "type": "unstarted", "position": 1},
                {"id": "in-review", "name": "In Review", "type": "started", "position": 2},
                {"id": "dev-ready", "name": "Ready For Dev", "type": "started", "position": 3},
            ],
        )

        self.assertEqual("dev-ready", state_id)

    def test_select_reopened_state_returns_none_for_working_state(self) -> None:
        state_id = select_reopened_state_id(
            current_state={"id": "in-progress", "name": "In Progress", "type": "started"},
            team_states=TEAM_STATES,
        )

        self.assertIsNone(state_id)

    def test_build_issue_update_input_reopens_and_unassigns(self) -> None:
        update_input = build_issue_update_input(
            {
                "assignee": {"id": "user-1", "name": "허동운"},
                "state": {"id": "done", "name": "Done", "type": "completed"},
                "team": {"states": {"nodes": TEAM_STATES}},
            },
        )

        self.assertEqual({"assigneeId": None, "stateId": "in-progress"}, update_input)

    def test_build_issue_update_input_skips_noop(self) -> None:
        update_input = build_issue_update_input(
            {
                "assignee": None,
                "state": {"id": "in-progress", "name": "In Progress", "type": "started"},
                "team": {"states": {"nodes": TEAM_STATES}},
            },
        )

        self.assertEqual({}, update_input)


if __name__ == "__main__":
    unittest.main()
