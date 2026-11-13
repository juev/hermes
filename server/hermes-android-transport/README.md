# hermes-android-transport 0.1.0 — Android as a gateway platform

The Android client is handled like every other chat platform: the phone connects
to this adapter's WebSocket, sends messages, and receives the agent's readable
answers. One gateway session per `chat_id`, streamed drafts, clarify and
execution-approval answers, slash commands, `deliver=android` cron routing and
`send_message` all come from the standard platform layer.

This replaces the previous `server/hermes-android` package. That one had to
re-create the platform layer in front of the stock dashboard API: a dashboard
router, one-use tickets, a plugin-owned session registry, a JSON-RPC bridge into
the native socket and a projection that removed reasoning and tool frames before
serialization. None of that exists here:

- reasoning and tool payloads never reach a platform layer, so nothing has to be
  filtered before the wire;
- the gateway owns session identity, history, authorization, clarify and
  approvals, so there is no registry and no bridge;
- the adapter owns its listener, so it does not import dashboard internals, does
  not need to run in-process in the dashboard and does not depend on its ASGI
  scope.

The one thing the adapter keeps is a bounded per-chat outbox, because a phone can
be asleep when a turn finishes.

## Install (operator action)

1. Copy this directory to `<HERMES_HOME>/plugins/hermes-android-transport` (or
   mount it there read-only) and keep `<HERMES_HOME>/plugin-data/hermes-android-transport/`
   across upgrades.
2. `hermes plugins enable hermes-android-transport`.
3. Set the channel token in the profile's `.env` (`ANDROID_CHANNEL_TOKEN`) and
   enable the platform:

   ```yaml
   gateway:
     platforms:
       android:
         enabled: true
         extra:
           host: 127.0.0.1     # TLS terminates at the reverse proxy
           port: 8799
   ```

4. Reload/restart the gateway by the deployment's own procedure. Never restart
   the container from inside its own agent session.
5. Point the client at `wss://<host>/channel` with `Authorization: Bearer <token>`.

Without `ANDROID_CHANNEL_TOKEN` the adapter refuses to start (`connect()` returns
False) instead of serving an unauthenticated channel.

## Wire

One JSON object per WebSocket text frame. The path is `/channel`; anything else
answers 404 and a wrong bearer answers 401 before the handshake completes.

Client → server:

- `{"type":"hello","chats":["c1"],"after_seq":12}` — subscribe to chats and replay
  everything newer than `after_seq`. Answers with
  `{"type":"hello","platform":"android","path":"/channel","chats":[{"chat_id","name","last_seq"}]}`
  followed by the replayed frames.
- `{"type":"message","chat_id":"c1","text":"…","chat_name":"Work","message_id":"…"}` —
  one user turn. Answers `{"type":"accepted","chat_id","message_id"}`.
- `{"type":"clarify","clarify_id":"…","choice":"…"}` — answers a clarify question
  that was delivered to this connection.
- `{"type":"approval","request_id":"…","choice":"once|session|always|deny"}` —
  answers an execution-approval card.
- `{"type":"list"}` — the chats this profile has seen, with their `last_seq`.
- `{"type":"ping"}` — `{"type":"pong","time":…}`.

Server → client:

- `{"type":"message","chat_id","text","seq","reply_to"?}` — a readable message.
  Reasoned-about content is not a separate frame type because the platform layer
  never carries it.
- `{"type":"draft","chat_id","draft_id","text"}` — streaming draft for the message
  being produced. Ephemeral: drafts are never stored or replayed.
- `{"type":"typing","chat_id","state":"working"|"idle"}` — one working indicator,
  never token-frequency reasoning.
- `{"type":"clarify","chat_id","clarify_id","question","choices","multi","seq"}`
- `{"type":"approval","chat_id","request_id","text","actions":[["label","choice","style"]],"seq"}`
- `{"type":"error","error"}` — a rejected frame; the connection stays open.

Every stored frame carries a per-chat monotonic `seq`. A `message`, `clarify` or
`approval` is written before it is pushed, so a phone that reconnects with
`after_seq` receives exactly the frames it missed; the outbox keeps the newest
500 frames per chat. `send()` reports `raw_response.delivered=false,
queued=true` when no client of that chat is connected — accepted and stored, not
lost.

## Identity and limits

`chat_id` is the conversation; `user_id` is the configured principal
(`ANDROID_CHANNEL_PRINCIPAL`, default `android`). The bearer token is the only
credential: there is no per-user allowlist, so the token belongs to the owner of
that profile. Sessions created here are ordinary `platform=android` gateway
sessions, so the dashboard still sees them and other platforms never appear in
this channel.

## Not in this slice

Deliberately absent, and the reason this package ships before the client switch:

- server-side history, search, model picker and file/audio upload routes — the
  existing chat mode keeps them in dashboard mode until decided;
- notifications while the socket is closed (the old `server/hermes-push` Web Push
  route is untouched);
- an out-of-process cron sender (`standalone_sender_fn`), so `deliver=android`
  works only with a co-resident gateway.

## Tests

```bash
PYTHONPATH=<hermes-sdk> python3 -m unittest discover -s server/hermes-android-transport/tests -v
```

The suite drives a real loopback WebSocket against the real core seam:
registration through `ctx.register_platform`, construction through
`platform_registry.create_adapter`, bearer and path rejection, inbound
`MessageEvent` shape, sequenced sends, draft/typing frames, per-chat
subscription isolation, offline outbox replay, clarify and approval round trips,
and outbox retention. `handle_message` is replaced on the instance — that is the
same seam the gateway installs.
