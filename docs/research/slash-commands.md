# Slash commands on Android: inventory and transport feasibility (#156)

Investigation for the first acceptance item of #156: which Hermes chat commands exist on the
pinned supported version, which surface executes them, and what Android can reach over its two
authenticated transports. Source inspection only — no command was submitted to a live agent and
no gateway was restarted.

**Pinned version:** `NousResearch/hermes-agent` `2bd378ed1b8d6bf2192cb26e0a55ff3091e6bd19`
(the CI pin, Hermes v0.23.0 line). Files cited below are from that commit.

## 1. How a slash command executes

There is no single "command API". Four distinct paths exist:

1. **Messaging dispatch** (`gateway/run.py`, handlers in `gateway/slash_commands*.py`).
   The gateway's message pipeline recognizes a leading `/command` in a platform message
   (Telegram, Matrix, Discord, …) and runs the handler with the message's `SessionSource`.
   This is the only path that runs the `gateway_only` commands, including `/restart`.
   Per-platform authorization: `gateway/slash_access.py` (`allow_admin_from`,
   `user_allowed_commands`; floor `help`+`whoami`; fails closed for an uncached secondary
   profile under `multiplex_profiles`).
2. **Local `slash.exec`** (dashboard/TUI/Desktop/ACP → `tui_gateway/methods_slash.py`,
   `gateway/session_commands.py`). Runs a reviewed subset against the owned local session:
   reads and admission-fenced controls; everything else is refused with `unsupported_command`.
   The generated parity table (`website/docs/developer-guide/gateway-command-parity.md`,
   enforced by `tests/hermes_cli/test_gateway_command_parity.py`) counts **105 registry
   commands: 29 control, 19 read, 57 refused** on this path.
3. **Canonical client verbs** (`session.create`, `session.mutate`, `session.interrupt`,
   `prompt.submit`, …). Local clients implement some commands themselves against these RPCs
   instead of `slash.exec` (e.g. `/new` → `session.create`, `/stop` → interrupt,
   `/model` → the model picker, `/title` → `session.mutate rename`).
4. **Shared executors** (`hermes_cli/slash_exec.py`, `EXECUTORS`). A small set of
   surface-independent commands (`version`, `profile`, `egress`, `help`, `commands`, …)
   whose output depends only on args/options, never on the surface.

The registry is `hermes_cli/commands.py` `COMMAND_REGISTRY` (`CommandDef`: `cli_only`,
`gateway_only`, `aliases`, `busy_policy` ∈ {`reject`, `dispatch`, `interrupt_then_dispatch`},
`busy_handler`, `execute`). `busy_policy` is the mid-run policy of the **messaging** dispatcher;
the local path has its own guards (`_MUTATES_WHILE_RUNNING` = model/personality/compress).

**The stock `api_server` platform has no slash dispatch at all.** Its chat input goes to the
agent; it is explicitly non-messaging (`/update` refuses it, `gateway/slash_commands.py`
"Block non-messaging platforms (API server, webhooks, ACP)"). A `/command` typed into a channel
conversation today is ordinary prompt text.

## 2. Android's two transports

- **Dashboard mode** (`GatewaySocket`, JSON-RPC): reaches path 2 (`slash.exec`), path 3
  (canonical verbs) and the dashboard REST admin routes (`AdminApi`, e.g.
  `POST api/gateway/restart`). It does **not** reach path 1: `slash.exec` refuses the
  messaging-only commands.
- **Channel mode** (`ApiServerGateway` over the stock `api_server`, #155/#165): reaches only
  the stock REST routes (sessions, runs, run controls). No `slash.exec`, no canonical RPCs,
  no dashboard admin routes. Commands can only be offered where a stock REST equivalent
  exists; everything else is unavailable on this transport.

## 3. Inventory of the commands that matter for Android

Classification: **supported** = an authenticated path exists on the named transport;
**client-side** = Android can implement it from canonical routes/its own UI;
**refused** = the server path exists but refuses this surface; **unavailable** = no path.

| Command | Messaging semantics | Dashboard mode | Channel mode |
|---|---|---|---|
| `/help` | lists commands, paginated | supported (`slash.exec` read) | client-side catalog only |
| `/status` | session/model/token/context info | supported (`slash.exec` read) | partial: session + run status REST |
| `/new` (`/reset`) | fresh session, interrupts a running turn | client-side (`session.create`) | client-side (`POST api/sessions`) |
| `/model` | switch model, session-scoped; `--global` persists | client-side (picker / `api/model/set`); `slash.exec` refused | unavailable (no stock model route) |
| `/compress` (`/compact`) | compress context; `here [N]`, `--preview` | refused by `slash.exec`; canonical `session.mutate compress` exists | unavailable |
| `/stop` | interrupt the running turn | client-side (`session.interrupt`) | client-side (`POST v1/runs/{id}/stop`) |
| `/restart` | **graceful drain**, restart, notify requester | **refused** by `slash.exec`; admin REST is a **force** restart (§4) | unavailable |
| `/title` | rename session | supported (`slash.exec` control) / `session.mutate rename` | client-side (PATCH) |
| `/approve`, `/deny` | resolve a pending dangerous-command approval | supported (`slash.exec` control, admission-fenced) | client-side (run approval route) |
| `/pause` | global emergency stop of new work | refused (messaging-only) | unavailable |
| `/undo`, `/retry` | back up turns / resend | refused by `slash.exec`; canonical `session.mutate rewind` | unavailable (no undo on stock) |
| `/steer`, `/queue`, `/btw`, `/bg` | mid-run input variants | refused by `slash.exec`; canonical routes exist for some | unavailable |
| `/whoami`, `/version`, `/profile`, `/usage`, `/context`, `/diff`, `/insights` | reads | supported (`slash.exec` read) | mostly unavailable |
| `/personality`, `/reasoning`, `/fast`, `/yolo`, `/approvals`, `/verbose`, `/footer`, `/busy` | per-session settings | supported (`slash.exec` control) | unavailable |
| `/sethome`, `/topic`, `/start`, `/commands`, `/platform` | messaging administration | refused (messaging-only) | unavailable |
| `/clear`, `/config`, `/history`, `/snapshot`, `/export`, `/import`, `/skin`, `/wake`, … | terminal/host features (`cli_only`) | refused | unavailable |

The full 105-command matrix is the upstream parity table; the rows above are the Android-relevant
subset plus the issue's named examples. Aliases resolve through `resolve_command()`
(e.g. `/compact`→`compress`, `/reset`→`new`, `/q`→`queue`, `/s`→`steer`, `/ctx`→`context`).

## 4. `/restart` in detail

Messaging handler `_handle_restart_command` (`gateway/slash_commands.py`):

- idempotent against platform redelivery: `.restart_last_processed.json` (platform + update_id)
  makes a redelivered `/restart` a no-op; `.restart_notify.json` stores the requester's routing
  so the **new** gateway process sends the confirmation back to the same chat/thread;
- if a restart/drain is already under way, replies "draining N" / "restart in progress";
- drains active runs, then `request_restart(...)`; under systemd/launchd/Docker it exits 75 so
  the supervisor restarts it (a detached spawn would be killed with the cgroup).

The dashboard admin route `POST api/gateway/restart` (`hermes_cli/web_server.py`,
`_spawn_gateway_restart`) is a **different semantic**: it spawns `hermes gateway restart
--interrupt` — the user-commanded **force** path that supersedes a drain (safe-shutdown plan D6).
It coalesces: an in-flight restart child is reused, and requests within
`GATEWAY_RESTART_COOLDOWN_SECONDS` for the same profile coalesce onto the last spawn. The
non-interrupt variant (config-save auto-restart) refuses while sessions are live.

**Concrete blocker:** no authenticated remote seam performs the *graceful-drain* restart.
`slash.exec` refuses `/restart` on every local surface (parity table: CLI/TUI/Desktop/ACP all
`refused`/`unavailable`); the only remote restart is the force admin route; the stock api_server
has neither. Adding a drain-restart route would be a Hermes core change, which #155/#156
forbid. The issue explicitly anticipates this: record the blocker rather than substitute
model/tool execution or assume the similarly named admin action is equivalent.

What Android *can* do today, honestly labelled:

- dashboard mode: dispatch `/restart` to the existing admin route (force restart, server-side
  coalescing), show the acknowledgement, expect the disconnect, reconnect, confirm the gateway
  is back (uptime/status), and never blindly resubmit after a lost acknowledgement;
- channel mode: report `/restart` unavailable (no admin route on a pure api_server install).

Whether the force semantics are acceptable for the in-chat `/restart` is an owner decision (§6).

## 5. Dispatch design proposed for the client

For both modes, in the composer/send path:

1. **Parse** a leading `/name [args]` only when the text starts with `/` and the first token
   resolves against the registry (names + aliases, case-insensitive, leading slashes stripped —
   parity with `resolve_command`). Slash-containing prose (`path/to/file`, `http://…`), text
   with a leading space, and attachments stay ordinary prompts. Unknown command → a visible
   "unknown command" reply in the conversation, never a model prompt.
2. **Route** recognized commands by mode:
   - dashboard: canonical verbs where the app already has them (`/new`, `/stop`, `/title`,
     `/model` picker), `slash.exec` for the read/control subset, admin route for `/restart`
     (pending §6 decision); surface the server's `unsupported_command` refusal verbatim for
     the rest;
   - channel: stock REST equivalents only (`/new`, `/stop`, `/title`, `/approve`/`/deny`,
     a client-side `/help`); everything else reported unavailable on this transport.
3. **Never** create a phantom assistant turn or a local recovery observation for a control
   command: the command's output/refusal/state is shown as a command result in the originating
   conversation, with the session/profile isolation, drafts and cancellation unchanged.
4. **Busy policy**: respect the server's mid-run rules (messaging `busy_policy` is not ours to
   reuse blindly; the local path refuses model/personality/compress while running). Verify each
   command idle and mid-run against the actual server response.
5. **`/restart` recovery**: acknowledgement → expected disconnect → reconnect → confirmed
   outcome; a lost acknowledgement is an *unknown* outcome — check gateway status before any
   retry, and rely on the server's coalescing; never auto-resubmit.

## 6. Decisions for the owner

1. **`/restart` semantics.** Accept the force admin restart as the in-chat `/restart` in
   dashboard mode (honestly labelled, with the recovery flow above), or defer `/restart` until
   a graceful-drain seam exists upstream and ship the remaining commands first?
2. **Channel scope.** Confirm the channel offers only the stock-equivalent subset and reports
   the rest unavailable (no plugin, no core change — the #165 decision).
3. **Surface of record.** The parity table is generated upstream and may drift between pins;
   the client should discover refusals from the server at runtime rather than hard-code the
   57-command refusal list.

## Sources

- `hermes_cli/commands.py` — `COMMAND_REGISTRY`, `CommandDef`, `resolve_command`.
- `gateway/slash_commands.py` — messaging handlers, `_handle_restart_command`, non-messaging block.
- `gateway/slash_access.py` — per-platform command authorization.
- `tui_gateway/methods_slash.py`, `tui_gateway/command_discovery.py`,
  `gateway/session_command_sink.py` — local `slash.exec` helpers and discovery.
- `website/docs/developer-guide/gateway-command-parity.md` — generated surface matrix
  (105 commands: 29 control / 19 read / 57 refused on `slash.exec`).
- `hermes_cli/web_server.py` — `_spawn_gateway_restart`, force vs drain, coalescing.
- `hermes_cli/slash_exec.py` — shared surface-independent executors.
- Official slash-command reference (hermes-agent.nousresearch.com/docs/reference/slash-commands),
  read 2026-11-09: `/restart` documented as messaging-only.
