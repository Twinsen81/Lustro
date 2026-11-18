# With a coding agent

A coding agent can add Lustro by itself. Give it the repository, for example "Add Lustro
to this app, following https://github.com/Twinsen81/Lustro, and check the integration".
[`llms.txt`](https://github.com/Twinsen81/Lustro/blob/main/llms.txt) summarizes Lustro for agents and links the rest of the docs, and
[`context7.json`](https://github.com/Twinsen81/Lustro/blob/main/context7.json) tells [Context7](https://context7.com) which parts of this
repository to index. To use a running Lustro from an agent or a script, see
[Agents and automation](../AGENTS.md).

The [`lustro` skill](https://github.com/Twinsen81/Lustro/blob/main/skills/lustro/SKILL.md) teaches an agent to debug the app through the
[`lustro` CLI](install.md#the-lustro-cli): its requests, logs, preferences, databases, and background work.
It also adds Lustro to an app and checks the integration. An agent in your project does not see a
skill that is only in this repository, so install it into the project:

```bash
npx skills add Twinsen81/Lustro --skill lustro
```

[`skills`](https://github.com/vercel-labs/skills) asks which agents you use. You can also copy
`skills/lustro/` to `.claude/skills/` for Claude Code, or to `.agents/skills/` for Codex, Cursor,
GitHub Copilot, and Gemini CLI.

## Agents without a shell: the MCP server

The skill needs an agent that runs shell commands. For an agent that can only call
[MCP](https://modelcontextprotocol.io) tools, such as Claude Desktop, the CLI has an MCP server,
`lustro mcp`. Install the [`lustro` CLI](install.md#the-lustro-cli) first. The agent's client
starts the server when a session starts and stops it when the session ends. An agent with a
shell does better with the skill and the CLI: the client puts the server's tools into the
agent's context in every session, and a CLI command costs context only when the agent runs it.

Claude Desktop (**Settings > Developer > Edit Config**, which opens `claude_desktop_config.json`),
Cursor (`.cursor/mcp.json`), Gemini CLI (`.gemini/settings.json`), and Claude Code (`.mcp.json`)
take the same entry:

```json
{
  "mcpServers": {
    "lustro": {
      "command": "lustro",
      "args": ["mcp"]
    }
  }
}
```

VS Code (`.vscode/mcp.json`):

```json
{
  "servers": {
    "lustro": {
      "type": "stdio",
      "command": "lustro",
      "args": ["mcp"]
    }
  }
}
```

Codex (`~/.codex/config.toml`):

```toml
[mcp_servers.lustro]
command = "lustro"
args = ["mcp"]
```

Claude Code also adds it with `claude mcp add lustro -- lustro mcp`.

- **A desktop app** such as Claude Desktop starts the server with a short `PATH`, which does
  not have the directory of `lustro`. Give `command` the full path that `which lustro` prints
  (`where lustro` on Windows), such as `/Users/you/.local/bin/lustro`. The server finds `adb` in
  `$ANDROID_HOME/platform-tools` or in the default place of the Android SDK; for an SDK in
  another place, add `"env": {"ANDROID_HOME": "/path/to/sdk"}`.
- **Several devices.** Name the device: `"args": ["mcp", "--device", "emulator-5554"]`, or
  `"env": {"ANDROID_SERIAL": "emulator-5554"}`.
- **Android Studio's agent** connects only to MCP servers over HTTP: its docs list the stdio
  transport as not supported, so it can't start `lustro mcp`.

To check the server without an agent, the [MCP Inspector](https://github.com/modelcontextprotocol/inspector)
lists its tools: `npx @modelcontextprotocol/inspector --cli lustro mcp --method tools/list`. Then,
with the app in the foreground, ask the agent for the requests that the app captured.
[The MCP server](../AGENTS.md#the-mcp-server-for-agents-without-a-shell) lists the tools.
