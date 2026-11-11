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
