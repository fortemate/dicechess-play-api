---
title: Play Against Your Bot
description: Verify a freshly deployed bot end to end by challenging it yourself from the public lobby — no ladder, no waiting, one request.
---

You've deployed a bot from a starter template and registered it. Before trusting it to the
[rating ladder](../rating/), the fastest way to know it actually plays a legal game is to
challenge it yourself, as a human, from the same lobby every guest sees.

## Post a seek

`POST /bot/seeks` puts a standing offer in the public lobby — the same one
[`GET /lobby/seeks`](../reference/rest/#accept-a-lobby-seek) serves to the website:

```bash
curl -X POST "$BASE/bot/seeks" -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"timeControl":{"Fischer":{"initialSeconds":300,"incrementSeconds":3}}}'
# → 201 {"seekId":"seek-12","secret":"capability-secret"}
```

That's the whole setup. No code change, no extra configuration — delivery attaches to a game
the same way regardless of how it started: a challenge, a seek, or the ladder scheduler. A
passive webhook bot that has never done anything but answer `yourTurn` deliveries plays a
seek-originated game exactly as it would a ladder one.

## Accept it as a human

Open [play.jc.id.lv/lobby](https://play.jc.id.lv/lobby) as an ordinary guest — no account
needed. Your bot's seek appears in the same list as everyone else's. Accept it and play.

## The seek expires — that's fine for a one-off test

A bot seek lives for **~2 minutes without a poll**. Polling
[`GET /lobby/seeks/{id}?secret=<secret>`](../reference/rest/#post-a-lobby-seek) both keeps it
alive and is how you'd learn it was matched — but for a single manual test, you don't need any
of that: post the seek, switch to the lobby tab, accept within two minutes, done. Cap: 3 open
seeks per bot (`429` beyond).

## Keeping a seek open indefinitely

If you want your bot standing in the lobby at all times rather than testing it once, that needs
a small loop re-posting (or polling) the seek every ~45 seconds — comfortably under the 2-minute
TTL. This is exactly what the *standing-seek keeper* in
[`dicechess-reference-bot`](https://github.com/rabestro/dicechess-reference-bot) does
(`BOT_OPEN_SEEKS`), and it fits naturally there: that bot already holds long-lived streams, so
the keep-alive is one more thing the same process already does. A purely passive webhook bot has
no natural place to run a background loop — see [Webhooks](../reference/webhooks/) for what that
model can and can't do on its own.

## See also

- [Connection Modes](../connection-modes/) — polling, streaming, or webhooks; which fits your bot.
- [Rating & Ladder](../rating/) — what happens once you trust it enough to join.
- [Authentication & Identity](../authentication/) — where `<token>` comes from.
