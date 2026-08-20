# Bot API Reference

> **This document has moved to the Dice Chess Bot API documentation site:**
>
> ## 👉 https://bots.jc.id.lv/
>
> A navigable, searchable site for third-party bot developers — the same content, split into
> pages, plus a new English **provably-fair dice verification** guide.

The site is built from Markdown under [`docs/src/content/docs/`](./src/content/docs/) with
Astro + Starlight and deployed to an assets-only Cloudflare Worker on every docs change (see
[`.github/workflows/deploy-docs.yaml`](../.github/workflows/deploy-docs.yaml)). Run it locally
with `mise run docs:dev`.

## Quick links

- **A Bot in Five Minutes** — https://bots.jc.id.lv/quickstart/
- **Authentication & Identity** — https://bots.jc.id.lv/authentication/
- **Game Mechanics** (DFEN, legal-move tree, time controls) — https://bots.jc.id.lv/game-mechanics/
- **Connection Modes** (poll · stream · webhook) — https://bots.jc.id.lv/connection-modes/
- **Provably-Fair Dice** — https://bots.jc.id.lv/provably-fair/
- **REST Endpoints** — https://bots.jc.id.lv/reference/rest/
- **Event Streams** — https://bots.jc.id.lv/reference/streaming/
- **Webhooks** — https://bots.jc.id.lv/reference/webhooks/
- **Data Shapes** — https://bots.jc.id.lv/reference/data-shapes/

A minimal, dependency-free reference bot lives at [`examples/random_bot.py`](./examples/random_bot.py).
