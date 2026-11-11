# Export

Each row of the Network tab has a checkbox at its left, which shows on hover, and on every row once
one is selected. The checkbox in the column header selects every request the filters show, and a
shift-click selects the rows from the last checkbox you clicked. While requests are selected, a bar
above the list has **Export HAR**, which saves them as a HAR 1.2 file that browser devtools and
most HTTP tools import, **Copy Markdown**, which copies them as one Markdown document for a bug
report, a pull request, or a chat, and **Clear selection**. The detail's **Markdown** button copies
one request. A filter or search change keeps only the selected requests that it still shows, so an
export has the rows on screen, and the bar says how many it deselected. Both formats have the
redacted values that the tab shows, and say when the capture cut a body short. Agents and scripts
get the HAR from `GET /api/v1/network/transactions/_/export` or `lustro net export --har FILE`.

**WebSocket traffic.** A HAR file has no entry for a WebSocket message, so the export puts the
messages of a socket in the entry of its handshake request, in `_webSocketMessages`, the field
that Chrome DevTools writes and reads. The entry has the messages that the app still holds, with
the redacted text of a text message and the base64 bytes of a binary one, and leaves out a
message that `send()` refused. A selection that has a handshake row exports its messages too. In
the **WebSockets** view, **Export HAR** saves the handshake of the selected connection with its
messages, and **Markdown** copies the connection and its last 100 events. See
[WebSocket messages](websockets.md).
