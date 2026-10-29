# Hermes for Android

An Android client for the [Hermes Agent](https://github.com/NousResearch/hermes-agent) dashboard
(`hermes dashboard`, tested against v0.22.5). The chat works like the ChatGPT app: chats in a
drawer, a new chat from the pencil button or by pulling the chat up past its last message.

## What it does

- Chats stream over the dashboard's JSON-RPC socket (`/api/ws`): Markdown, reasoning, tool runs
  folded into one line, files the agent delivers (`MEDIA:` lines), approvals and questions of
  the agent. Under the chat title, next to the model, the chat shows the tokens it has spent
  (cached input included) and their cost, as the server totals them after each turn. Answers,
  reasoning and the prose tools return (subagent summaries, skills, pages) render as Markdown; terminal output and diffs stay as printed. What the server writes to the
  model on its own, such as a finished background process, is not shown. Notifications carry
  the text without Markdown syntax. The socket connects while the app checks the server at start,
  and a message sent before it is up says that the app is connecting.
- The drawer lists chats by date and source (Telegram, Matrix), with pinned chats, the archive
  and cron runs in a section of their own. Search uses the server's full-text index.
- Attachments: photos, camera, any file. Pictures, PDF, Markdown, text and code, audio and
  video open inside the app.
- Dictation goes to the speech-to-text provider configured in Hermes.
- The server section shows status and changes the gateway, model, `config.yaml` (by schema or as
  raw YAML), `.env` secrets, cron jobs, skills, toolsets, MCP servers and channels, and shows logs
  and usage. Steps that break or delete something ask first.
- A turn keeps running after the app is left: a foreground service holds the socket, and the
  answer arrives as a notification. Approvals can be answered from the notification.
- Push: with the `server/hermes-push` plugin on the server and a UnifiedPush distributor such as
  ntfy on the phone, every finished turn (chats, Telegram, Matrix, cron) arrives at once, end-to-end
  encrypted. A self-hosted ntfy server works too; how to connect it is in
  `server/hermes-push/README.md`. Without them a periodic check, or an optional permanent connection, reports news.
  While the permanent connection is on, Settings shows whether battery optimization is on for the
  app and opens the system list to change it. Push needs no exemption: the distributor holds the
  connection.
- Chats, the chat list and the server screens open with the copy kept on the phone and update
  when the server answers, so they also show without a network. The cache takes up to 500 MB by
  default, set under Settings → Data; the least recently used answers go first. Secrets,
  `config.yaml`, logs and command output are never kept, and signing out or removing a server
  deletes its copy.
- English interface with a Russian translation; Android 13+ lets the app use its own language.

## Reaching the server

Each server has its own route:

| Route | When |
|---|---|
| Direct | The phone reaches the dashboard: public address, the Tailscale app, home Wi-Fi |
| Tailscale (built in) | The app joins the tailnet itself through `tsnet`; no VPN slot, another VPN keeps running |
| SOCKS5, HTTP | A proxy that reaches the server, with an optional login |

The built-in node takes a Tailscale or Headscale control URL and an optional pre-auth key;
without a key it shows a login link.

A server can also be set up from a link, which fills the form without typing keys on the phone:

```
org.evsyukov.hermes://add-server?url=hermes.example.org&route=tailscale&control=https://hs.example.org&key=<pre-auth key>
```

`route` is `direct`, `tailscale`, `socks5` or `http`; a proxy takes `host`, `port`, `user`, `password`.

## Sign-in

The app uses the dashboard's `native_pkce` flow. The dashboard accepts only a loopback redirect,
so the app listens on `127.0.0.1` while the sign-in page is open.

- A directly reachable server signs in through a Custom Tab, where passkeys work.
- A server behind a proxy or the built-in tailnet signs in inside a WebView whose traffic takes
  the server's route. Passkeys work there only after the identity provider publishes
  `/.well-known/assetlinks.json` with the app's signing certificate; otherwise use the login code
  of the identity provider, or sign in once through the browser.

## Installing

Signed APKs for arm64 phones with Android 12 or later are on the
[releases page](https://github.com/juev/hermes/releases).

## Building

```bash
./scripts/build-tailnet.sh        # optional: app/libs/tailnet.aar, needs Go and the NDK
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

Without `tailnet.aar` the app builds and the built-in Tailscale reports itself unavailable.
Next to the node the script writes the licenses of its Go modules, and AboutLibraries collects
those of the Maven libraries; both appear under Settings → Licenses. A library under any license
but Apache-2.0 fails the build until its copyright notice is added in `app/aboutlibraries/licenses`.
A release is signed from `HERMES_KEYSTORE`, `HERMES_KEYSTORE_PASSWORD`, `HERMES_KEY_ALIAS` and
`HERMES_KEY_PASSWORD`. A tag `v1.2.3` makes CI build the signed APK and publish it as a release;
a tag with a suffix, `v1.2.3-rc1`, becomes a pre-release.

## Known limits

- Without the hermes-push plugin, news needs a permanent connection or waits for the periodic
  check (15 minutes at least); the dashboard has no push of its own.
- On a plain `hermes dashboard` the REST session routes refuse sessions that live in the dashboard
  process, so the app renames, archives and deletes them over the socket. Pinning has no socket
  method, so such a chat is pinned on the phone only: other devices and the desktop do not see
  that pin.
- The dashboard in gateway mode (`session_authority`) is handled for the common calls only.

## License

[MIT](LICENSE).
