---
title: Machine-Readable Specs
description: The OpenAPI and AsyncAPI documents behind this API — browse the generated REST reference, download the specs, or generate a client.
---

Everything on this site is backed by two machine-readable specifications. They are the source the narrative pages and the generated **[REST API (OpenAPI)](../api/)** reference are checked against — and the input you feed a code generator to get a typed client in minutes.

## The two documents

| Spec | Covers | File |
| --- | --- | --- |
| **OpenAPI 3.1** | the REST Bot API — every endpoint, request, and response | [`openapi.yaml`](../openapi.yaml) |
| **AsyncAPI 3.1** | the two ndjson event streams and their message payloads | [`asyncapi.yaml`](../asyncapi.yaml) |

The rendered REST reference in the sidebar (**REST API (OpenAPI)**) is generated from `openapi.yaml` at build time, so it can never drift from the file you download.

## Generate a REST client

The OpenAPI document works with any generator. A few starting points:

```bash
# TypeScript types (openapi-typescript)
npx openapi-typescript https://bots.fortemate.com/openapi.yaml -o dicechess.d.ts

# A full client in any language (openapi-generator)
openapi-generator-cli generate \
  -i https://bots.fortemate.com/openapi.yaml \
  -g python -o ./dicechess-client

# Interactive browsing / try-it-out
npx @scalar/cli reference https://bots.fortemate.com/openapi.yaml
```

Prefer a ready-to-run starter over generating from scratch? Two MIT-licensed "Use this template" bots build on these contracts — clone and run in a minute:

- **[dicechess-bot-python](https://github.com/rabestro/dicechess-bot-python)** — dependency-free Python.
- **[dicechess-bot-typescript](https://github.com/rabestro/dicechess-bot-typescript)** — zero-dependency TypeScript.

See [Licensing for Bots](../licensing/) for how they are licensed.

## Browse the event streams

The ndjson streams are documented narratively under [Event Streams](../reference/streaming/). For the machine-readable version, download [`asyncapi.yaml`](../asyncapi.yaml) and open it in [AsyncAPI Studio](https://studio.asyncapi.com/), or render it locally:

```bash
npx @asyncapi/cli start studio -f asyncapi.yaml
```

:::note[ndjson, not WebSocket]
The event streams are plain HTTP `GET` requests returning `application/x-ndjson` — one JSON object per line, blank lines are keep-alives. No WebSocket upgrade is involved; any HTTP client that can read a response body line by line can consume them.
:::
