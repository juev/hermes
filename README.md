# Hermes for Android

An Android client for the [Hermes Agent](https://github.com/NousResearch/hermes-agent) dashboard
(`hermes dashboard`, tested against v0.22.5). The chat works like the ChatGPT app: chats in a
drawer, a new chat from the pencil button or by pulling the chat up past its last message.

## What it does

- Chats stream over the dashboard's JSON-RPC socket (`/api/ws`): Markdown, reasoning, tool runs
  folded into one line, files the agent delivers (`MEDIA:` lines), approvals and questions of
  the agent.
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
  encrypted. Without them a periodic check, or an optional permanent connection, reports news.
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
A release is signed from `HERMES_KEYSTORE`, `HERMES_KEYSTORE_PASSWORD`, `HERMES_KEY_ALIAS` and
`HERMES_KEY_PASSWORD`. A tag `v1.2.3` makes CI build the signed APK and publish it as a release;
a tag with a suffix, `v1.2.3-rc1`, becomes a pre-release.

## Known limits

- Without the hermes-push plugin, news needs a permanent connection or waits for the periodic
  check (15 minutes at least); the dashboard has no push of its own.
- On a plain `hermes dashboard` the REST session routes refuse sessions that live in the dashboard
  process, so the app renames, archives and deletes them over the socket. Pinning has no socket
  method and may fail for such chats.
- The dashboard in gateway mode (`session_authority`) is handled for the common calls only.

## License

[MIT](LICENSE).
