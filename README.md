<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="art/lustro-wordmark-dark.png">
    <img src="art/lustro-wordmark.png" alt="Lustro" width="420">
  </picture>
</p>

<p align="center"><b>A browser-based, agent-friendly debugging library for Android.</b></p>

Lustro embeds a small web server in your app's debug builds and serves its tools as tabs you
open in a desktop browser — so you inspect on a full screen instead of a cramped on-device
overlay. The built-in network inspector captures traffic and lets you mock responses, throttle
connections, and replay requests. Every tab is also a JSON API, so AI agents and scripts can
drive Lustro directly rather than scraping HTML.

It's built as an extensible tab platform: the network inspector ships in the box, and you add
your own tabs against a stable plugin contract.

> **Status:** early development. Pre-1.0 and not yet published — APIs will change.

## License

[Apache License 2.0](LICENSE)
