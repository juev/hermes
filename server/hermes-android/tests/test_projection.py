"""Synthetic wire-contract tests; no live conversations or model calls."""
import copy
import importlib.util
from pathlib import Path
import unittest
import json

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("android_projection", ROOT / "projection.py")
assert spec is not None and spec.loader is not None
projection = importlib.util.module_from_spec(spec)
spec.loader.exec_module(projection)


def frame(kind, payload, seq=1):
    return {"jsonrpc": "2.0", "method": "event", "params": {
        "session_id": "owned", "type": kind, "seq": seq, "payload": payload}}


class ProjectionTest(unittest.TestCase):
    def test_mixed_turn_sends_readable_answer_without_reasoning_or_tools(self):
        source = [
            frame("message.start", {}, 1),
            frame("reasoning.delta", {"text": "PRIVATE_REASONING"}, 2),
            frame("tool.start", {"name": "terminal", "args": {"command": "PRIVATE_COMMAND"}}, 3),
            frame("tool.complete", {"result": "PRIVATE_RESULT"}, 4),
            frame("message.delta", {"text": "Readable answer", "raw": "PRIVATE_RAW"}, 5),
            frame("message.complete", {"status": "complete", "text": "Readable answer", "rendered": "PRIVATE_RENDERED"}, 6),
        ]
        original = copy.deepcopy(source)
        delivered = [value for value in map(projection.event, source) if value is not None]
        self.assertEqual(["message.start", "message.delta", "message.complete"], [f["params"]["type"] for f in delivered])
        self.assertEqual([1, 5, 6], [f["params"]["seq"] for f in delivered])
        self.assertEqual({"text": "Readable answer"}, delivered[1]["params"]["payload"])
        self.assertEqual({"status": "complete", "text": "Readable answer"}, delivered[2]["params"]["payload"])
        self.assertEqual(source, original, "Projection must not mutate shared native frames")

    def test_session_failure_is_delivered_without_raw_diagnostic_payload(self):
        delivered = projection.event(frame("error", {"message": "Session initialization failed", "raw": "PRIVATE", "tool_result": "PRIVATE"}))
        self.assertIsNotNone(delivered, "A session failure must terminate Android's waiting state instead of disappearing with tool events")
        self.assertEqual({"message": "Session initialization failed"}, delivered["params"]["payload"])

    def test_compact_session_info_keeps_remote_profile_identity_without_inventory(self):
        native = {"profile_name": "default", "model": "selected", "provider": "provider", "system_prompt": "PRIVATE", "tools": {"terminal": ["PRIVATE_SCHEMA"]}}
        expected = {"profile_name": "default", "model": "selected", "provider": "provider"}
        snapshot = projection.result("session.resume", {"info": native})
        self.assertEqual(expected, snapshot["info"], "Remote profile identity is receipt provenance, not optional banner inventory")
        self.assertEqual(expected, projection.event(frame("session.info", native))["params"]["payload"])
        self.assertEqual(expected, projection.result("session.info", native))

    def test_model_only_notices_and_compression_summaries_keep_ids_not_payloads(self):
        rows = [
            {"id": 1, "role": "user", "display_kind": "background_process", "content": "PRIVATE_TOOL_RESULT", "display_content": "PRIVATE_NOTICE"},
            {"id": 2, "role": "assistant", "_compressed_summary": True, "content": "PRIVATE_CONTEXT_SUMMARY"},
            {"id": 3, "role": "user", "content": "User asked"},
        ]
        minimal = projection.history({"messages": rows})
        self.assertNotIn("PRIVATE", json.dumps(minimal), "Hidden model context is not readable chat content")
        self.assertEqual([1, 2, 3], [row["id"] for row in minimal["receipt_rows"]])
        self.assertEqual("User asked", minimal["messages"][2]["content"])
        snapshot = projection.result("session.resume", {"messages": [dict(row, text=row["content"]) for row in rows]})
        self.assertNotIn("PRIVATE", json.dumps(snapshot))
        self.assertEqual([3], [row["id"] for row in snapshot["messages"]])

    def test_history_drops_typed_private_blocks_and_tool_display_payloads(self):
        page = {"messages": [
            {"id": 1, "role": "assistant", "content": [
                {"type": "thinking", "thinking": "PRIVATE_REASONING"},
                {"type": "tool_use", "id": "c1", "input": {"secret": "PRIVATE_ARGS"}},
                {"type": "text", "text": "Visible", "debug": "PRIVATE_DEBUG"},
                {"type": "image_url", "image_url": {"url": "https://example.test/image.png", "debug": "PRIVATE_IMAGE"}},
            ]},
            {"id": 2, "role": "tool", "content": "PRIVATE_RESULT", "display_content": "PRIVATE_DISPLAY", "tool_call_id": "c1"},
        ]}
        minimal = projection.history(page)
        self.assertNotIn("PRIVATE", json.dumps(minimal), "Alternate display and typed content must not reopen the payload boundary")
        self.assertEqual("Visible", minimal["messages"][0]["content"][0]["text"])
        self.assertEqual("https://example.test/image.png", minimal["messages"][0]["content"][1]["image_url"]["url"])
        self.assertEqual("c1", minimal["receipt_rows"][1]["tool_call_id"])

    def test_connection_and_work_state_are_semantic_not_tool_payloads(self):
        ready = projection.event(frame("gateway.ready", {"heartbeat": True, "replay_epoch": "epoch", "raw": "PRIVATE"}))
        self.assertIsNotNone(ready, "A minimal connection must retain its handshake")
        self.assertEqual({"heartbeat": True, "replay_epoch": "epoch"}, ready["params"]["payload"])
        status = projection.event(frame("status.update", {"text": "PRIVATE_TOOL_COMMAND"}))
        self.assertEqual({"text": "Working"}, status["params"]["payload"])
        card = {"op_id": "op1", "seq": 1, "deadline_at": 123, "targets": [{"name": "gmail", "kind": "connector", "state": "pending", "connect_url": "https://example.test/connect", "raw_result": "PRIVATE"}], "raw_tool_frame": "PRIVATE"}
        projected = projection.event(frame("connection.request", card))["params"]["payload"]
        self.assertEqual("op1", projected["op_id"])
        self.assertEqual("https://example.test/connect", projected["targets"][0]["connect_url"])
        self.assertNotIn("raw_result", projected["targets"][0])
        self.assertNotIn("raw_tool_frame", projected)
        snapshot = projection.result("session.resume", {"pending_connection": card, "open_requests": [
            {"id": "srq1", "method": "clarify", "params": {"session_id": "owned", "questions": []}, "answers": {"q1": "Yes"}}]})
        self.assertEqual(projected, snapshot["pending_connection"])
        self.assertEqual({"q1": "Yes"}, snapshot["open_requests"][0]["answers"])

    def test_resume_and_replay_use_the_same_projection_and_raw_watermark(self):
        native = {"session_id": "live", "session_key": "owned", "running": True, "message_count": 3,
                  "messages": [{"role": "assistant", "text": "Answer", "reasoning": "PRIVATE"},
                               {"role": "tool", "name": "terminal", "args": {"command": "PRIVATE"}}],
                  "info": {"model": "fixture-model", "launch_request": {"api_key": "PRIVATE"}},
                  "inflight": {"assistant": "Partial", "streaming": True, "reasoning": "PRIVATE"},
                  "open_requests": [{"id": "srq-1", "method": "clarify", "params": {"session_id": "owned", "questions": []}}]}
        snapshot = projection.result("session.resume", native)
        self.assertEqual([{"role": "assistant", "text": "Answer"}], snapshot["messages"])
        self.assertEqual({"model": "fixture-model"}, snapshot["info"])
        self.assertNotIn("reasoning", snapshot["inflight"])
        self.assertEqual(native["open_requests"], snapshot["open_requests"])
        replay = {"events": [frame("reasoning.delta", {"text": "PRIVATE"}, 99)["params"]],
                  "latest_seq": 99, "count": 1, "epoch": "epoch", "truncated": False}
        page = projection.result("session.events.since", replay)
        self.assertEqual([], page["events"])
        self.assertEqual(0, page["count"])
        self.assertEqual(99, page["latest_seq"], "Invisible events still advance the native cursor")
        self.assertEqual("epoch", page["epoch"])

    def test_history_recovery_has_no_payload_but_retains_verifiable_row_membership(self):
        rows = [
            {"id": 1, "role": "user", "content": "Question", "timestamp": 1},
            {"id": 2, "role": "assistant", "content": "", "reasoning": "PRIVATE_REASONING", "tool_calls": [{"id": "call", "function": {"name": "terminal", "arguments": "PRIVATE_COMMAND"}}], "timestamp": 2},
            {"id": 3, "role": "tool", "content": "PRIVATE_RESULT", "tool_call_id": "call", "tool_name": "terminal", "timestamp": 3},
            {"id": 4, "role": "assistant", "content": "Answer", "reasoning_content": "PRIVATE_REASONING", "timestamp": 4},
        ]
        page = {"session_id": "owned", "profile": "default", "messages": rows, "pagination": {"returned": 4, "offset": 0}}
        original = copy.deepcopy(page)
        projected = projection.history(page)
        self.assertEqual("", projected["messages"][2]["content"], "Tool output must not cross the wire")
        self.assertNotIn("reasoning", projected["messages"][1])
        self.assertNotIn("tool_calls", projected["messages"][1])
        self.assertNotIn("reasoning_content", projected["messages"][3])
        self.assertEqual(4, projected["pagination"]["returned"])
        evidence = projected["receipt_rows"]
        self.assertEqual([1, 2, 3, 4], [r["id"] for r in evidence])
        self.assertEqual("call", evidence[1]["tool_calls"][0]["id"])
        self.assertNotIn("function", evidence[1]["tool_calls"][0])
        self.assertEqual("call", evidence[2]["tool_call_id"])
        self.assertEqual("", evidence[2]["content"])
        self.assertEqual("Answer", evidence[3]["content"])
        self.assertEqual(page, original)

    def test_interactive_lifecycle_and_receipt_survive_minimal_projection(self):
        request = {"jsonrpc": "2.0", "id": "srq-1", "method": "clarify", "params": {
            "session_id": "owned", "questions": [{"qid": "q1", "question": "Proceed?", "choices": ["Yes", "No"]}]}}
        cancel = frame("request.cancel", {"id": "srq-1", "method": "clarify", "reason": "resolved", "debug": "PRIVATE"}, 7)
        complete = frame("message.complete", {"status": "error", "text": "Partial", "error": "Failed", "partial": True,
            "persisted_turn": {"complete": False, "user_row_id": 1, "final_assistant_row_id": 3, "row_ids": [1, 2, 3], "raw": "PRIVATE"}}, 8)
        complete["params"]["replay_epoch"] = "epoch"
        complete["params"]["admission_id"] = "admitted"
        self.assertEqual(request, projection.event(request))
        self.assertEqual({"id": "srq-1", "method": "clarify", "reason": "resolved"}, projection.event(cancel)["params"]["payload"])
        done = projection.event(complete)["params"]
        self.assertEqual("epoch", done["replay_epoch"])
        self.assertEqual("admitted", done["admission_id"])
        self.assertEqual("Failed", done["payload"]["error"])
        self.assertEqual([1, 2, 3], done["payload"]["persisted_turn"]["row_ids"])
        self.assertNotIn("raw", done["payload"]["persisted_turn"])


if __name__ == "__main__":
    unittest.main()
