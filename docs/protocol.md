# The Hermes Agent dashboard contract

What the `hermes dashboard` server of [Hermes Agent](https://github.com/NousResearch/hermes-agent)
sends, expects and rejects, written once for every client of it: the Android app in this repository and
the macOS app in `juev/hermes-macos`.

Status: skeleton, 2026-10-30. §1, §3 and the framing of §5 are filled. §1 and the unauthenticated half of
§3 were observed against a live v0.22.5 dashboard and their payloads are under
`docs/fixtures/api-status/`; the rest was read from the Android client, which is the source of truth
here, and says so. Last server version observed: v0.22.5.

## How a section is finished

A section is done when it states:

- the exact route, socket method, event name or field, spelled as the server spells it;
- the server version and the mode it was observed on — a plain dashboard, or the gateway with
  `session_authority`;
- what a client has to do about it, and where that happens in the code;
- one captured payload under `docs/fixtures/` when the shape is not obvious.

A guess is marked `Unknown —` together with the observation that would settle it. Do not describe what
a client happens to do as what the server does.

## 1. Versions and modes

- `GET /api/status` answers without a token, in either mode. Observed on v0.22.5 on 2026-10-30, plain
  dashboard, captured whole in `docs/fixtures/api-status/dashboard.json`:

  ```json
  {
    "auth_required": true,
    "auth_flows": ["cookie", "native_pkce"],
    "auth_providers": ["self-hosted"],
    "version": "0.22.5",
    "release_date": "2026.9.24",
    "gateway_mode": "single",
    "gateway_running": true,
    "gateway_state": "running",
    "active_sessions": 0,
    "overall": "ok"
  }
  ```

  The payload carries forty-odd fields at the top level; the ones above are what a client needs.
  `auth_required` says whether a token is needed at all; `native_pkce` in `auth_flows` says the browser
  sign-in of §3 works; `auth_providers` lists what the browser may be sent to — `self-hosted` here,
  `nous` and others on other deployments, so a client reads the list and never assumes it. `version` and
  `release_date` are display strings. `gateway_running`, `gateway_state`, `active_sessions`, `overall`
  and `components` are what a connect screen may show; `disk`, `memory`, `profiles`, `install_id` and the
  per-platform gateway state belong to admin screens (§4).
- Without a token every REST route that needs one answers `401` with the same body. The socket is the
  exception: a real WebSocket handshake on `/api/ws` is refused with `403`, an empty body and no JSON to
  read (`docs/fixtures/socket/refusal.json`). The same address without an upgrade gets the `401` of REST
  instead — over HTTP/2 it always does, because HTTP/2 has no upgrade — so a client that probes the
  socket with a plain GET learns the wrong thing. The first reading of the Android client was right
  about the `403` and wrong about the reason: it is the handshake that is denied. The `401` body is in
  `docs/fixtures/api-status/unauthorized.json`:

  ```json
  {"error": "unauthenticated", "detail": "Unauthorized", "reason": "no_cookie", "login_url": "/login"}
  ```

  `reason` says what was missing; `no_cookie` is what a request with no token at all gets. `login_url` is
  the dashboard's own sign-in page, for a client that would rather open a browser than run §3.
- A plain dashboard owns its sessions. A gateway with `session_authority` owns them for the client: the
  socket reports it in `gateway.ready` (§5), and the calls that differ are the ones the gateway refuses
  on behalf of a session inside its own process (§4).
- `Unknown —` whether the server publishes a compatibility version a client can require. Neither client
  checks `version` before talking; the observation that would settle it is a dashboard older than the
  routes in §3 denying them with a plain 404.

Source: `../app/src/main/java/org/evsyukov/hermes/data/server/ServerSession.kt` (`authInfo`) for the
fields a client reads; observed on v0.22.5 against the dashboard on loffe, 2026-10-30.

## 2. Reaching the server

- Direct, the built-in Tailscale node, SOCKS5 and HTTP proxies: what each route changes in the requests,
  and what stays the same.
- The `Host` header: the dashboard itself does not check it. Observed on v0.22.5 on 2026-10-30, a
  request straight to the process with `Host: example.test` answered `200`, so any rejection of a foreign
  `Host` belongs to whatever proxy stands in front of it and not to the contract.
- The local forward proxy the app runs for a route that needs one.

Source: `../app/src/main/java/org/evsyukov/hermes/data/server/` (`HttpClients.kt`,
`LocalForwardProxy.kt`, `ServerProfile.kt`).

## 3. Authentication

- `native_pkce`. The browser gets the code, a listener on the loopback address of the client receives
  it, and the client trades it for tokens; the client never sees a password.
  - `GET /auth/native/authorize` — no `/api` prefix — with `code_challenge`,
    `code_challenge_method=S256`, `redirect_uri`, `state`, and `provider` when one of `auth_providers`
    is chosen. The dashboard answers with a redirect to the identity provider and the browser follows
    it.
  - `redirect_uri` is `http://127.0.0.1:<port>/<path>` and nothing else. Observed on v0.22.5 on
    2026-10-30: an `https` address or a custom scheme answers
    `400 {"detail":"native redirect_uri must be http:// on the loopback interface"}`, and a `localhost`
    name answers `400 {"detail":"native redirect_uri host must be a loopback IP literal (127.0.0.1 / ::1)"}`.
    Only the literal `127.0.0.1` or `::1` passes. Bodies in
    `docs/fixtures/auth/native-redirect-refusals.json`.
  - The same call answers `302` to the identity provider, with the dashboard's own `/auth/callback` as
    the OIDC `redirect_uri` and the client's `state` and `code_challenge` carried through. The client
    does not follow that answer itself: it opens the browser on it and waits on the loopback address for
    the browser to come back with the code.
  - The pending sign-in lives in a browser cookie, `hermes_session_pkce`, that the dashboard sets on the
    authorize answer; `/auth/callback` refuses without it (`400 OAuth state mismatch (CSRF check
    failed)`). The browser carries that cookie, so the client's own HTTP calls never need it.
  - The callback carries `code` and `state`, or `error` and `error_description` instead. The client
    compares `state` with its own and drops the code when they differ.
  - `POST /auth/native/token` with `{"code": "…", "code_verifier": "…"}` answers the token object:
    `{"access_token", "refresh_token", "expires_at", "provider", "user_id"}`. `expires_at` is unix
    seconds and written either as a number or as a string; `refresh_token` may be empty or absent,
    which leaves the previous one valid.
  - The code lives two minutes; a listener that waits longer than that cannot use it.
- Refresh: `POST /auth/native/refresh` with `{"refresh_token", "provider"}` answers the same token
  object. `400` and `401` mean the session is over and only a new sign-in helps; `503` means the
  identity provider is unreachable, and the stored tokens stay for a later try. A client refreshes a
  minute before `expires_at`.
- REST calls carry `Authorization: Bearer <access_token>`. A `401` is retried once after a forced
  refresh; a second `401` ends the session.
- The socket takes no header: `POST /api/auth/ws-ticket` with the bearer answers `{"ticket": "…"}`,
  the ticket lives 30 seconds and opens one socket (§5). A dashboard that needs no sign-in takes no
  ticket.
- Without a token the REST routes answer `401` with the body of §1; the socket handshake is refused with
  `403` and an empty body instead (§5). A client reads `reason` and, if it wants a browser instead of
  §3's flow, follows `login_url`. A socket the dashboard refuses after the upgrade closes with the
  WebSocket code `4401` — that line is from the Android client and was not yet reproduced.
- `Unknown —` what `expires_at` is when the identity provider gives no expiry (both clients read `0`
  as "no expiry"), whether a refresh token expires, and what an expired ticket on `/api/ws` answers
  besides the close code.

Source, read on v0.22.5 and not yet reproduced against a live server:
`../app/src/main/java/org/evsyukov/hermes/data/auth/` (`NativeSignIn.kt` — the PKCE pair, the authorize
address, the loopback listener), `../app/src/main/java/org/evsyukov/hermes/data/remote/HermesHttp.kt`
(tokens, refresh, the bearer and the retry), `.../data/remote/GatewaySocket.kt` (the ticket),
`../app/src/main/java/org/evsyukov/hermes/data/server/ServerSession.kt` (the exchange). The `401`, `403`,
`4401` and `Host` lines come from the first session's findings and are the least checked of the three.

## 4. REST surface

- The routes a client uses outside the socket, with methods, bodies and error shapes.
- Which routes refuse sessions that live in the dashboard process, and the workaround over the socket.

Source: `../app/src/main/java/org/evsyukov/hermes/data/remote/` (`HermesHttp.kt`, `ChatApi.kt`,
`AdminApi.kt`, `Reads.kt`).

## 5. The socket

- `/api/ws` speaks JSON-RPC 2.0 as one JSON object per text frame, in both directions.
- A call: `{"jsonrpc": "2.0", "id": "a1", "method": "gateway.ping", "params": {}}`. `id` is a string the
  client picks and keeps unique while the connection lives; the Kotlin client numbers its own `a1`, `a2`.
- An answer: `{"jsonrpc": "2.0", "id": "a1", "result": …}` or
  `{"jsonrpc": "2.0", "id": "a1", "error": {"code": …, "message": …, "data": …}}`. `result` is `null`
  when the method answers nothing.
- A notification: `{"jsonrpc": "2.0", "method": "event", "params": {"type": "…", "session_id": "…",
  "seq": …, "payload": {…}}}`. `type` names the event; `session_id` ties it to a chat and `seq` orders
  the events of that chat.
- The first event of every connection is `gateway.ready`, whose `payload` carries `replay_epoch` (the
  window a reconnecting client may have missed), `session_authority` (§1) and `heartbeat`.
- Right after ready the client calls `client.capabilities` with `{"server_requests": true}`. Without it
  the dashboard fails approvals at once instead of asking the client (§9).
- A request from the server carries an `id` and a `method`; the client answers with the same `id` and a
  `result`, or with an `error` whose `code` is `-32000`, which means "the person said no" and not a
  protocol error.
- Heartbeat: when `gateway.ready` says `heartbeat`, the client sends `gateway.ping` every 15 seconds
  with a 10 second timeout and treats 45 seconds without a frame as a lost connection. A gateway
  without the flag is not pinged.
- A chat is joined, not opened: `session.resume {session_id, close_on_disconnect, omit_messages,
  inline_images}` answers with the live session under `session_id` — **not** the stored id the client
  holds, which comes back as `session_key` — and with `running`, `status`, `info`, `inflight`,
  `message_count`, `started_at` and `todo_state` beside it. Observed on v0.22.5 on 2026-10-30; the
  answer's keys and the opening events are in `docs/fixtures/socket/resume.json`.
- `running` arrives as `0` or `1`, not as a boolean. A client that reads only `true` sees every joined
  chat as idle.
- A prompt goes into the live session: `prompt.submit {session_id, text}`. A turn is ended with
  `session.interrupt {session_id}`, and the live session is released with `session.close {session_id}`.
- Events that follow a join are not all turn events: `session.info` and `platforms.changed` arrive
  for the session itself and say what the chat is doing, not what the answer says.
- A handshake the dashboard will not accept — no ticket on a dashboard that wants one, an expired
  ticket — is answered `403` with an empty body, before any JSON-RPC frame is exchanged. The client
  cannot tell an expired ticket from a missing one by the answer; it fetches a fresh ticket and, when
  the `403` comes back three times in a row, stops and says the sign-in is over.
- `Unknown —` what an expired ticket answers exactly, as opposed to no ticket at all, and which methods
  take a timeout of their own. The observation that would settle the second is a call that outlives the
  client's default.

Source, read on v0.22.5 and not yet reproduced against a live server:
`../app/src/main/java/org/evsyukov/hermes/data/remote/` (`GatewaySocket.kt`, `GatewayChannel.kt`).

Still to fill here: reconnect and join — what a client sends when a connection opens, what it gets
back, and what happens when the join answer is lost — and ping and liveness beyond the heartbeat above.
The observation that settles the join question is a reconnect whose previous join answer never arrived.

## 6. Sessions, the sidebar and search

- `GET /api/sessions` lists chats, newest activity first, as
  `{limit, offset, total, storage, sessions}`. The parameters a client sends: `limit`, `offset`,
  `order=recent`, `archived` (`exclude`, `only` or neither), `min_messages` — `1` hides the chats that
  only hold a greeting — and `sources` / `exclude_sources`. Observed on v0.22.5 on 2026-10-30;
  `docs/fixtures/sessions/list.json` is a capture of five.
- A session object carries about seventy fields. The ones a sidebar needs: `id`, `title` (null until
  the dashboard names the chat — the clients then show `preview`), `preview`, `source`, `model`,
  `message_count`, `started_at`, `last_active`, `pinned`, `archived`, `is_active`, `unread`. A source is
  what the chat came in through — `android`, `cron`, `matrix`, `telegram` were all in one capture.
- Spending travels with the session, not with a turn: `input_tokens`, `output_tokens`,
  `cache_read_tokens`, `cache_write_tokens`, `actual_cost_usd`, `estimated_cost_usd` and `cost_status`
  (`included` when the plan bills by subscription, `estimated` when the dashboard priced it itself, and
  a price of zero means it knows no price).
- `GET /api/sessions/{id}/messages?limit=&order=oldest|latest&inline_images=false` answers
  `{session_id, profile, pagination, messages}`; see §7 for a row.
- `GET /api/sessions/search?q=&limit=` answers `{results: [...]}` — no `total`, so a client asks for a
  limit and shows what came back. A hit repeats the session fields beside the match, under both `id`
  and `session_id`, and adds `snippet` — the text around the match as it was, newlines included — and
  `role`, the role of the message the match was found in. Unlike the list, a hit carries no `pinned`
  and no `unread`. Observed on v0.22.5 on 2026-10-30; `docs/fixtures/search/results.json`.
- Flags arrive as SQLite wrote them: `true` and `1` both mean yes.
- Search, renaming, archiving and deleting over the socket where REST refuses, and pinning when the
  server has no method for it — still to fill, from the Android client.

Source: `../app/src/main/java/org/evsyukov/hermes/data/remote/ChatApi.kt` (`list`, `messages`) for the
parameters a client sends; the shapes were observed on v0.22.5 against the dashboard on loffe on
2026-10-30 and captured in `docs/fixtures/sessions/`.

## 7. Streaming a turn

- A stored row of `GET /api/sessions/{id}/messages` carries `role` (`user`, `assistant`, `tool`, and a
  `session_meta` row that is empty and skipped), `content` (a plain string in all 773 rows sampled; the
  multi-part list with `image_url` is what the Android client also handles but was not seen here),
  `timestamp` in unix seconds and, by role: `tool_calls` on an assistant row as
  `{call_id, id, response_item_id, type: "function", function: {name, arguments}}`, and `tool_name` and
  `tool_call_id` on a tool row. Reasoning comes twice, in `reasoning` and `reasoning_content`, with the
  same text. `display_kind` was seen as `steer` and `process_complete`; a `user` row with any other kind
  is what the dashboard wrote for the model and is not shown.
- The events of a running turn, as the Android client reads them: `message.start` opens the answer,
  `message.delta {text}` and `message.interim {text, already_streamed}` grow it, `reasoning.delta
  {text}` and `reasoning.available {text}` carry the model's reasoning, `tool.start {tool_id, name,
  context, preview, args_text, args}` and `tool.complete {tool_id, duration_s, summary, result,
  result_text}` open and close a tool run, and `message.complete {status, text, rendered, error,
  failure_reason}` says how the turn ended. `status.update {text}` and `thinking.delta` are what the
  chat shows while it waits, not part of the answer. These payloads are read from the client and were
  not yet observed on a live turn.
- How a tool run is identified and folded into a line, and why reasoning between two tool calls belongs
  to the group.
- Usage and cost totals, cached input, and when they settle.
- `Unknown —` cancellation and its event.

Source: `../app/src/main/java/org/evsyukov/hermes/chat/` (`Transcript.kt`, `ToolOutput.kt`),
`../app/src/main/java/org/evsyukov/hermes/ui/chat/ToolGroups.kt`.

## 8. Service messages

- Lines the server writes into the conversation for the model, and how they differ from what a person
  wrote: the expanded attachment context, finished background work, cron deliveries.
- Which of them a client shows, and in what shape.

Source: `../app/src/main/java/org/evsyukov/hermes/chat/` (`PlainText.kt`, `Transcript.kt`),
test `../app/src/test/java/org/evsyukov/hermes/chat/ServiceMessagesTest.kt`.

## 9. Approvals and questions

- The approval request: its fields, how the kind of request is told apart, how an answer is sent.
- Questions the agent asks, and how their answers travel.
- `Unknown —` how a request is withdrawn when the turn ends elsewhere.

Source: `../app/src/main/java/org/evsyukov/hermes/chat/Approval.kt`,
`../app/src/main/java/org/evsyukov/hermes/background/Notifications.kt`.

## 10. Attachments and files

- Files the agent delivers are named in its answer by `MEDIA:` lines, one file per line, as an absolute
  path on the dashboard: `MEDIA: /opt/hermes/reports/build.md`. The line is not part of the words a
  person reads — a client cuts it out and draws the file instead.
- The directive can appear in prose that talks about it, so a client has to check that what follows is
  a path and not a sentence: a value with a line break in it, or one longer than a few hundred
  characters, is text.
- `GET /api/files/download?path=<absolute path>` answers the bytes. Observed on v0.22.5 on 2026-10-30:
  with `Authorization: Bearer …` it answers `200` and the file's bytes (15 609 for a Markdown file); a
  path the dashboard does not have answers `404 {"detail":"Path not found"}`, and a path it cannot read
  answers `400`. A client that opens the file in a browser instead of fetching it puts the token in the
  query, `?path=…&token=…`, as the Android client does — the header is what an app should use.
- Uploading: `POST /api/files/upload-stream` takes a multipart body with `path`, `overwrite` and
  `file`, and answers the path it wrote. Read from the Android client; not yet observed.
- Images: a row's `content` can carry parts of type `image_url` with a `data:` URL, and a user message
  names its pictures with `@image:` and its files with `@file:` lines, which the dashboard follows with
  the content it read in `--- Attached Context ---`. No such row appeared in the captures of
  `docs/fixtures/sessions/`, so that form is read from the client and not from the server.
- `Unknown —` size and type limits, and what a download of a directory does.

Source: `../app/src/main/java/org/evsyukov/hermes/data/files/AttachmentCache.kt`,
`../app/src/main/java/org/evsyukov/hermes/ui/viewer/` for the client side; the download route was
observed on v0.22.5 against the dashboard on loffe, 2026-10-30.

## 11. Integrations

- The `connection.request` event and the card it builds: accounts signed in through Nous, MCP servers
  with the keys they ask for, catalog plugins and skills.
- OAuth round trips in the browser: the return link the server gives the desktop client and not the
  phone, and `connectors.operation.wake` that finishes it.

Source: `../app/src/main/java/org/evsyukov/hermes/data/remote/Integrations.kt`,
`../app/src/main/java/org/evsyukov/hermes/ui/chat/ConnectionCard.kt`.

## 12. Push

- The plugin sends after every finished turn, from the process that ran it: the dashboard for its own
  chats, the gateway for Telegram, Matrix and cron. A turn of a `subagent` platform sends nothing; the
  turn that started it does.
- Its routes sit under `/api/plugins/hermes-push`, behind the dashboard sign-in: `GET /key` answers the
  VAPID public key (`{"key": "<base64url, uncompressed P-256>"}`), `POST /subscriptions` takes
  `{endpoint, p256dh, auth, name, device}`, `DELETE /subscriptions` takes `{endpoint}`, and
  `POST /test` pushes to every subscription. Observed on v0.22.5 on 2026-10-30: the plugin is loaded on
  the dashboard and `/key` answers.
- `endpoint` must be `https`. A subscription is RFC 8291 Web Push: the client's P-256 public key
  (`p256dh`, uncompressed, base64url) and a 16-byte `auth` secret; the body is aes128gcm for one record
  under VAPID (RFC 8292). `device` is a stable id of the client, and a new subscription of the same
  device replaces the older ones, so a reinstalled client does not get each push twice.
- What arrives is `{"type": "turn", "session_id": …, "platform": …, "job": …, "preview": …}`, the
  preview shortened to 600 characters.
- Nothing in the envelope is UnifiedPush's own: the plugin only posts to the endpoint it was given, so a
  platform without a distributor can be a subscriber too, as long as it can read that endpoint. A
  macOS client registers an ntfy topic as its endpoint, keeps the private half of its P-256 pair and
  the auth secret, reads the topic and opens the body itself — the receiving half of RFC 8291 in
  CryptoKit was held against this plugin's own ciphertext and opens it. What that needs beyond the
  protocol is a push service the client can read: on this deployment ntfy refuses to serve a topic to
  an anonymous reader (`403 forbidden`), so the client needs a user with read access to the `up*`
  topics.
- The plugin drops a subscription when the push service answers `404` or `410`, which is its answer to
  a phone that was wiped; ntfy does not, so a client that stops reading should delete its subscription
  on the way out.

Source: `../server/hermes-push/` (`plugin_api.py`, `webpush.py`, `store.py`), observed on v0.22.5 against
the dashboard on loffe, 2026-10-30.

## 13. Failure modes

- The errors a client actually meets, with the state that causes them and the recovery that works:
  a refused `Host`, an expired token, a refused socket, a chat that cannot be pinned.
- Behaviour a client must not depend on.

Source: the Android client's issues, and the tests under `../app/src/test/`.

## 14. Fixtures

- Captured payloads in `docs/fixtures/<area>/<scenario>.json`, untrimmed, each with the server version
  and the request that produced it in a `README.md` beside them.
- A scenario both clients exercise is captured here as JSON, not only as Kotlin behind the fake gateway,
  so the macOS tests can replay it too.

## 15. Change log

Dated entries: the server version, what changed in behaviour, and the sections it touched.
