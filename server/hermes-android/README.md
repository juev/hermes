# hermes-android 0.1.0 — opt-in connection channel

An installable Hermes backend extension for the Android client in this repository.
It owns a conversation registry and projects readable messages before client-bound
serialization. It does not patch Hermes, start another agent, change models/toolsets,
import private gateway implementation modules, or migrate existing history.

## Compatibility and activation boundary

Verified native gateway: **Hermes v0.23.0, upstream
`2bd378ed1b8d6bf2192cb26e0a55ff3091e6bd19`**. The wire protocol is
`channel_version: 1`, not a promise of compatibility with every v0.23 build.
Earlier gateways and future protocol changes require a fresh compatibility check.
The existing dashboard connection remains the default in Android.

Prerequisites for this first version:

- An authenticated dashboard with native bearer sign-in and canonical
  `session_authority` WebSocket support.
- The plugin and dashboard use the **same Hermes profile** and persistent plugin
  storage. Multiplexed `?profile=` selection is rejected: use that profile's own
  dashboard listener/address instead.
- User backend plugins run **in process**. Hermes' isolated plugin-host HTTP proxy
  does not mount this WebSocket implementation. Do not silently weaken an existing
  isolation policy; an operator must approve a compatible deployment explicitly.
- The dashboard exposes a plaintext loopback listener on the same port reported
  by its ASGI server scope; TLS terminates at the authenticated reverse proxy.
  Container/Unix-socket-only or direct-TLS listeners are not verified here.
- Runtime packages: FastAPI, httpx, websockets 15+, cryptography. The pinned test
  environment is in `requirements-test.txt`.

The supported installation seam is the [Hermes dashboard backend SDK](https://hermes-agent.nousresearch.com/docs/user-guide/features/extending-the-dashboard):
`dashboard/plugin_api.py` exports a router under `/api/plugins/hermes-android`.
The adjacent CLI plugin registers documented completion and compaction hooks.

## Install (operator action, not performed by this change)

1. Review the prerequisites and back up configuration. Copy this complete directory
   to `<HERMES_HOME>/plugins/hermes-android`, or mount it there read-only. Preserve
   `<HERMES_HOME>/plugin-data/hermes-android/` across upgrades.
2. On the **intended profile**, run `hermes plugins enable hermes-android`.
3. Activate the backend routes and completion hooks by the deployment's normal
   external gateway/dashboard reload or restart procedure. Do not restart the
   container from an agent running inside it, interrupt unrelated active turns, or
   change plugin isolation as an incidental installation step.
4. In Android's server editor, select **Android channel plugin** and sign in as
   usual. There is no fallback to the shared dashboard conversation routes.
5. Create a new conversation, answer a question, reconnect while waiting, refresh
   history, and test push. Check that an existing independent platform chat is
   absent. This operator acceptance is separate from the tests below.

## Authentication and ownership

HTTP requests require a bearer already verified by Hermes middleware. Membership
is keyed by provider, issuer and native user identity in the profile-local SQLite
registry. Only a successful server-created native conversation may be claimed;
client `source`, titles and guessed IDs never grant membership. Two phones signed
into the same principal/profile intentionally share this Android channel.

The HTTP facade uses the same authenticated native ASGI routes internally, not
an arbitrary Host-header URL or an operator token. A one-use 20-second plugin
ticket binds that principal to one native ticket. The backend connects only to
its configured loopback listener. Foreign HTTP reads/mutations return 404 before
calling native; foreign socket joins/actions fail before native dispatch.

Normal chat uses plugin list/detail/history/search/mutations, ticket and socket
routes. Explicit account settings, authentication and authenticated file/audio
fetches remain native operations; this is channel isolation, not a sandbox against
an administrator deliberately using the ordinary API.

## Delivery, recovery and notifications

See [protocol §16](../../docs/protocol.md#16-android-channel-plugin-v1) and
`../../docs/fixtures/android-channel/v1.json`.

Reasoning and ordinary tool frames are discarded before the bounded relay queue.
User-facing questions, approvals, secret prompts, connection cards/withdrawals,
work state and final replies survive. Typed history drops private blocks and
model-only notices. Receipts retain row IDs and tool-call **IDs only**, never
arguments/results; evidence rows are distinct from visible transcript rows.

Canonical create uses a fresh request ID without title resolution. An optional
label is a revision-fenced `session.mutate rename`, not legacy `session.title`.
Retry uses fenced `rewind`; model selection uses fenced `model` and preserves the
explicit confirmation requirement. The plugin does not lower reasoning effort.

SQLite keeps channel membership, compaction aliases, per-principal VAPID keys and
push subscriptions. The completion hook can notify the owner while the phone is
closed. Delivery is bounded best effort, not a durable retry queue. Only the
owning principal's endpoints receive its readable preview. Android retires only
this phone's old global endpoint before switching and rejects unscoped/old-owner
pushes. With push off, background reads use the same channel ChatApi paths.

Android also separates response-cache paths, local pins and recovery lineage from
the old connection. Old history, pins and observations are preserved, not imported
or deleted. Turning the mode off restores the dashboard view; the reverse push
transition retires the channel endpoint before registering a global one.

## Diagnostics and rollback

- `401`: sign in again; anonymous dashboards are deliberately unsupported.
- `404` on plugin ticket/key: package missing, disabled, or backend not mounted.
  Fix activation; do not switch to global chat automatically.
- Incompatible `channel_version`: update matching client/plugin versions.
- `1011` after upgrade: verify the profile-local plaintext loopback listener and
  native ticket route. Reconnect/refresh instead of reusing a ticket.
- Empty list immediately after create can mean the canonical row has not yet been
  persisted. A foreign guessed ID still returns 404.
- Action refused after reconnect: restore the current native request/card first.
  An account-settings operation opened on an old transport must be reopened.
- Push test sent to zero: verify that profile/principal's subscription and the
  distributor's access policy. Logs intentionally omit payloads, keys and URLs.

Rollback: choose dashboard mode in Android; let this phone retire/register its
push endpoint, then `hermes plugins disable hermes-android` on the intended
profile and use the operator's normal external reload procedure. Preserve plugin
storage and canonical history. Do not delete another phone's subscriptions or
change the legacy `hermes-push` plugin for every client.

## Verification and limits

```sh
# Isolated SDK test dependencies; Python 3.14. Fetch the pinned SDK source
# archive as in .github/workflows/ci.yml (Hermes does not distribute wheels).
python3.14 -m venv .venv
.venv/bin/pip install -r requirements-test.txt
PYTHONPATH=/path/to/pinned/hermes-sdk .venv/bin/python -B -m unittest discover -s tests -v
```

The tests exercise real SQLite/crypto, ASGI routing, the installed dashboard loader
and real loopback WebSockets with explicitly synthetic identities/native peers.
`test_wire.py` measures actual WebSocket application payloads for identical
synthetic reasoning/tool-heavy workloads, including positive question and final
reply. It excludes HTTP, TLS/TCP framing and device energy.

A separate opt-in diagnostic `../android-channel-probe.py --bridge` exercises the
product NativeClient/Bridge against an existing native gateway: create, submit,
clarify, disconnect, restore, answer and detach. Its operator-only bootstrap is
**not imported by the installed package**. It is a native transport proof, not
proof of deployed native-PKCE-to-plugin sign-in or a phone installation. Raw probe
receipts can include local runtime identities; keep them private.

Still required before production acceptance: activate the plugin on an approved
profile and exercise the complete native sign-in → mounted facade → Android path,
with independent platform actors, real distributor delivery and an actual phone.
No production installation/restart or application release is part of this change.
