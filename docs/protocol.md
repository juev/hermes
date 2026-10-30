# The Hermes Agent dashboard contract

What the `hermes dashboard` server of [Hermes Agent](https://github.com/NousResearch/hermes-agent)
sends, expects and rejects, written once for every client of it: the Android app in this repository and
the macOS app in `juev/hermes-macos`.

Status: skeleton, 2026-10-30. Sections carry the headings, what belongs under each and the files that
already know the answer; the facts are filled in as a client needs them. Last server version observed:
v0.22.5.

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

- Which server versions a client may talk to, and what `GET /api/status` answers before and after sign-in
  (`auth_required`, version, features a client may look for).
- The two modes: a plain dashboard that owns its sessions, and the gateway with `session_authority`;
  which routes and methods differ between them.
- `Unknown —` whether the server publishes a compatibility version a client can require.

Source: `../app/src/main/java/org/evsyukov/hermes/data/remote/HermesHttp.kt`,
`../app/src/main/java/org/evsyukov/hermes/data/server/ServerSession.kt`.

## 2. Reaching the server

- Direct, the built-in Tailscale node, SOCKS5 and HTTP proxies: what each route changes in the requests,
  and what stays the same.
- The `Host` header the dashboard accepts and the errors it gives otherwise.
- The local forward proxy the app runs for a route that needs one.

Source: `../app/src/main/java/org/evsyukov/hermes/data/server/` (`HttpClients.kt`,
`LocalForwardProxy.kt`, `ServerProfile.kt`).

## 3. Authentication

- `native_pkce`: the authorize request, the loopback redirect it accepts, the code exchange, the token
  response and what a client stores.
- Sending the token: header or query, for REST and for the socket.
- The socket ticket: `POST /api/auth/ws-ticket`, its lifetime, and what an expired ticket gets.
- `Unknown —` refresh and expiry behaviour.

Source: `../app/src/main/java/org/evsyukov/hermes/data/auth/` (`NativeSignIn.kt`, `TokenCipher.kt`),
`../app/src/main/java/org/evsyukov/hermes/data/server/ServerSession.kt`.

## 4. REST surface

- The routes a client uses outside the socket, with methods, bodies and error shapes.
- Which routes refuse sessions that live in the dashboard process, and the workaround over the socket.

Source: `../app/src/main/java/org/evsyukov/hermes/data/remote/` (`HermesHttp.kt`, `ChatApi.kt`,
`AdminApi.kt`, `Reads.kt`).

## 5. The socket

- Opening `/api/ws`, the framing, request and response identifiers, errors and timeouts.
- Reconnect and join: what a client sends when a connection opens, what it gets back, and what happens
  when the join answer is lost.
- Ping and liveness.

Source: `../app/src/main/java/org/evsyukov/hermes/data/remote/` (`GatewaySocket.kt`, `GatewayChannel.kt`),
`../app/src/main/java/org/evsyukov/hermes/chat/ConnectionOp.kt`.

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
