"""Tests for the cursor-polling state machine (delta/unchanged/reset + unknown)."""

from __future__ import annotations

from lustro_cli import wire
from lustro_cli.client import CursorState


def test_reset_replaces_list_and_captures_cursor_and_state():
    state = CursorState()
    data = wire.load_golden("cursor-reset.json")
    new = state.apply(data)
    assert state.cursor == "c:42"
    assert [tx["id"] for tx in state.items] == ["tx_9f3c1a8e", "tx_4b1d77a0"]
    # First reset: every item is "new".
    assert [tx["id"] for tx in new] == ["tx_9f3c1a8e", "tx_4b1d77a0"]
    assert state.state == {"paused": False, "overwriteMode": False, "throttleDelayMs": 0}


def test_delta_returns_only_newly_seen_items():
    state = CursorState()
    state.apply(wire.load_golden("cursor-reset.json"))
    new = state.apply(wire.load_golden("cursor-delta.json"))
    # Delta carries the authoritative full list; only the new id is "new".
    assert [tx["id"] for tx in new] == ["tx_77e2c014"]
    assert len(state.items) == 3
    assert state.cursor == "c:43"
    # State updates flow through (throttle changed to 250).
    assert state.state["throttleDelayMs"] == 250


def test_unchanged_does_not_mutate_list_and_advances_cursor_when_present():
    state = CursorState()
    state.apply(wire.load_golden("cursor-reset.json"))
    before = list(state.items)
    new = state.apply(wire.load_golden("cursor-unchanged.json"))
    assert new == []
    assert state.items == before
    assert state.cursor == "c:43"  # unchanged fixture still carries a cursor


def test_unchanged_ignores_items_if_present_defensively():
    state = CursorState()
    state.apply({"cursor": "a", "status": "reset", "items": [{"id": "1"}]})
    new = state.apply(
        {"cursor": "b", "status": "unchanged", "items": [{"id": "2"}]}
    )
    assert new == []
    assert [i["id"] for i in state.items] == ["1"]


def test_unknown_status_is_treated_as_reset():
    state = CursorState()
    state.apply({"cursor": "a", "status": "reset", "items": [{"id": "1"}, {"id": "2"}]})
    new = state.apply(
        {"cursor": "b", "status": "totally-unknown", "items": [{"id": "3"}]}
    )
    # Treated as reset → whole list replaced; the lone item is new.
    assert [i["id"] for i in state.items] == ["3"]
    assert [i["id"] for i in new] == ["3"]
    assert state.cursor == "b"


def test_missing_status_is_treated_as_reset():
    state = CursorState()
    new = state.apply({"cursor": "x", "items": [{"id": "1"}]})
    assert [i["id"] for i in state.items] == ["1"]
    assert [i["id"] for i in new] == ["1"]


def test_delta_without_items_does_not_mutate_list():
    state = CursorState()
    state.apply({"cursor": "a", "status": "reset", "items": [{"id": "1"}]})
    new = state.apply({"cursor": "b", "status": "delta"})
    assert new == []
    assert [i["id"] for i in state.items] == ["1"]
    assert state.cursor == "b"


def test_cursor_not_overwritten_when_absent():
    state = CursorState()
    state.apply({"cursor": "keep", "status": "reset", "items": []})
    state.apply({"status": "unchanged"})
    assert state.cursor == "keep"


def test_reset_to_empty_clears_items():
    state = CursorState()
    state.apply({"cursor": "a", "status": "reset", "items": [{"id": "1"}]})
    state.apply({"cursor": "b", "status": "reset", "items": []})
    assert state.items == []


def test_apply_handles_none():
    state = CursorState()
    assert state.apply(None) == []
    assert state.items == []


def test_persistence_across_full_sequence():
    """reset -> unchanged -> delta -> unknown(reset): cursor + list track the wire."""
    state = CursorState()
    state.apply(wire.load_golden("cursor-reset.json"))
    assert state.last_status == "reset"
    state.apply(wire.load_golden("cursor-unchanged.json"))
    assert state.last_status == "unchanged"
    state.apply(wire.load_golden("cursor-delta.json"))
    assert state.last_status == "delta"
    new = state.apply({"cursor": "c:99", "status": "weird", "items": [{"id": "only"}]})
    assert state.cursor == "c:99"
    assert [i["id"] for i in new] == ["only"]
