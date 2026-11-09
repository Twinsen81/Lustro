"""`lustro screen tree`: the windows and nodes of the Screen tab, against a local server."""

from __future__ import annotations

import json
import threading
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

from lustro_cli import cli, wire

TOKEN = "screen-token"


class _Handler(BaseHTTPRequestHandler):
    requests = []

    def log_message(self, *args):  # silence
        pass

    def do_GET(self):
        url = urllib.parse.urlsplit(self.path)
        _Handler.requests.append((url.path, dict(urllib.parse.parse_qsl(url.query))))
        if self.headers.get("Authorization") != "Bearer " + TOKEN:
            body, status = {"error": "unauthorized", "message": "missing token"}, 401
        elif url.path == "/api/v1/screen/tree":
            fixture = "screen-tree-views.json" if "views" in url.query else "screen-tree.json"
            body, status = wire.load_golden(fixture), 200
        else:
            body, status = wire.load_golden("error-envelope.json"), 404
        data = json.dumps(body).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


@pytest.fixture
def lustro():
    httpd = ThreadingHTTPServer(("127.0.0.1", 0), _Handler)
    thread = threading.Thread(target=httpd.serve_forever, daemon=True)
    thread.start()
    host, port = httpd.server_address
    _Handler.requests = []

    def run(*argv):
        return cli.main(["--host", host, "--port", str(port), "--token", TOKEN, "screen", *argv])

    try:
        yield run
    finally:
        httpd.shutdown()
        httpd.server_close()
        thread.join(timeout=5)


def test_tree_prints_each_window_and_the_nodes_with_something_to_say(lustro, capsys):
    assert lustro("tree") == 0
    out = capsys.readouterr().out.splitlines()
    windows = [line for line in out if line.startswith("window ")]
    assert len(windows) == 2
    assert windows[0].startswith("window 0  activity  ") and windows[1].startswith("window 1  dialog  ")
    save = next(line for line in out if "#save" in line)
    assert '"Save"' in save and "Button" in save and "click" in save and save.rstrip().endswith("]")
    secret = next(line for line in out if "#secret" in line)
    assert 'value="•••••••"' in secret and "password" in secret and "set-text" in secret
    assert any("#dark" in line and " off " in line for line in out)
    assert any("#list" in line and "scroll-v 0/100" in line for line in out)
    # The containers and the nodes that are not on the screen are left out.
    assert not any("DecorView" in line or "#avatar" in line for line in out)
    # The rows of the list are indented under it.
    row = next(line for line in out if "#row 0" in line)
    listed = next(line for line in out if "#list" in line)
    assert len(row) - len(row.lstrip()) > len(listed) - len(listed.lstrip())
    assert 'context: {"route":"profile","stack":["home","profile"]}' in out
    # No window of the app has the focus in the fixture.
    assert any(line.startswith("no window of the app has the focus") for line in out)
    assert _Handler.requests == [("/api/v1/screen/tree", {})]


def test_tree_all_prints_every_node(lustro, capsys):
    assert lustro("tree", "--all") == 0
    out = capsys.readouterr().out
    golden = wire.load_golden("screen-tree.json")
    for window in golden["windows"]:
        for node in window["nodes"]:
            assert "  " + node["id"] + "  " in out
    assert "(not on screen)" in out


def test_tree_asks_for_the_unmerged_tree_and_a_settle(lustro, capsys):
    assert lustro("tree", "--unmerged", "--settle", "500") == 0
    capsys.readouterr()
    assert _Handler.requests == [("/api/v1/screen/tree", {"merged": "false", "settle": "500"})]


def test_tree_json_prints_the_response(lustro, capsys):
    assert lustro("tree", "--json") == 0
    assert json.loads(capsys.readouterr().out) == wire.load_golden("screen-tree.json")


@pytest.mark.parametrize("value", ["-1", "10001", "soon"])
def test_a_settle_out_of_range_is_a_usage_error(lustro, capsys, value):
    with pytest.raises(SystemExit) as exit_info:
        lustro("tree", "--settle", value)
    assert exit_info.value.code == 2
    assert "--settle" in capsys.readouterr().err
    assert _Handler.requests == []


def test_a_view_node_names_its_class_and_id(capsys):
    tree = wire.load_golden("screen-tree-views.json")
    cli._print_tree(tree, show_all=False)
    out = capsys.readouterr().out
    assert any("Button" in line and 'desc="Go on"' in line for line in out.splitlines())
    assert any("ScrollView" in line and "scroll-v 0/280" in line for line in out.splitlines())
    assert any("CheckBox" in line and " on " in line for line in out.splitlines())
