# Bot API Reference

> **This document has moved to the Dice Chess Bot API documentation site:**
>
> ## 👉 https://bots.fortemate.com/
>
> A navigable, searchable site for third-party bot developers — the same content, split into
> pages, plus a new English **provably-fair dice verification** guide.

The site is built from Markdown under [`docs/src/content/docs/`](./src/content/docs/) with
Astro + Starlight and deployed to an assets-only Cloudflare Worker on every docs change (see
[`.github/workflows/deploy-docs.yaml`](../.github/workflows/deploy-docs.yaml)). Run it locally
with `mise run docs:dev`.

## Quick links

- **A Bot in Five Minutes** — https://bots.fortemate.com/quickstart/
- **Authentication & Identity** — https://bots.fortemate.com/authentication/
- **Game Mechanics** (DFEN, legal-move tree, time controls) — https://bots.fortemate.com/game-mechanics/
- **Connection Modes** (poll · stream · webhook) — https://bots.fortemate.com/connection-modes/
- **Provably-Fair Dice** — https://bots.fortemate.com/provably-fair/
- **REST Endpoints** — https://bots.fortemate.com/reference/rest/
- **Event Streams** — https://bots.fortemate.com/reference/streaming/
- **Webhooks** — https://bots.fortemate.com/reference/webhooks/
- **Data Shapes** — https://bots.fortemate.com/reference/data-shapes/

A minimal, dependency-free reference bot lives at [`examples/random_bot.py`](./examples/random_bot.py).
