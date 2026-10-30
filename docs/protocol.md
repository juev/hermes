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

- Listing and paging, the sources a chat can come from, pinned and archived chats.
- Search over the server's index.
- Renaming, archiving and deleting over the socket where REST refuses; pinning when the server has no
  method for it.

Source: `../app/src/main/java/org/evsyukov/hermes/chat/` (`ChatMutations.kt`, `LocalPins.kt`),
`../app/src/main/java/org/evsyukov/hermes/ui/sidebar/`, `../app/src/main/java/org/evsyukov/hermes/ui/search/`.

## 7. Streaming a turn

- The events of a running turn and their order: assistant text, reasoning, tool calls and results.
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

- Uploading an attachment and the reference a message carries for it.
- Files the agent delivers, the `MEDIA:` lines and how a client resolves and shows them.
- `Unknown —` size and type limits.

Source: `../app/src/main/java/org/evsyukov/hermes/data/files/AttachmentCache.kt`,
`../app/src/main/java/org/evsyukov/hermes/ui/viewer/`.

## 11. Integrations

- The `connection.request` event and the card it builds: accounts signed in through Nous, MCP servers
  with the keys they ask for, catalog plugins and skills.
- OAuth round trips in the browser: the return link the server gives the desktop client and not the
  phone, and `connectors.operation.wake` that finishes it.

Source: `../app/src/main/java/org/evsyukov/hermes/data/remote/Integrations.kt`,
`../app/src/main/java/org/evsyukov/hermes/ui/chat/ConnectionCard.kt`.

## 12. Push

- The `server/hermes-push` plugin and the UnifiedPush distributor on the phone: subscription, the device
  identity, the encryption envelope.
- What a platform without UnifiedPush needs instead, and what of this is the server's job.

Source: `../server/hermes-push/`, `../app/src/main/java/org/evsyukov/hermes/push/`.

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
