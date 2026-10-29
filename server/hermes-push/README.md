# hermes-push

A Hermes Agent plugin that sends a Web Push to the Hermes Android app when a turn ends:
chats of the app, Telegram, Matrix and cron runs. Messages go through UnifiedPush (for
example the ntfy app and its server) encrypted with the phone's keys (RFC 8291), so the
push server sees only ciphertext. Only the `cryptography` package of Hermes is used.

Subagents that `delegate_task` starts inside a chat send nothing: their turns run with the
platform `subagent`, and the turn of the chat that started them sends the push.

## Parts

- `__init__.py` registers the `post_llm_call` hook; it runs in the process that ran the turn
  and sends on a thread.
- `dashboard/plugin_api.py` adds routes under `/api/plugins/hermes-push`, behind the dashboard
  sign-in: `GET /key` (VAPID public key, made on first use), `POST /subscriptions`,
  `DELETE /subscriptions`, `POST /test`.
- Data: `<HERMES_HOME>/plugin-data/hermes-push/` with `vapid.pem` and `subscriptions.json`.

## Install

1. Put the directory at `<HERMES_HOME>/plugins/hermes-push` (or mount it there read-only).
2. `hermes plugins enable hermes-push`.
3. Restart the gateway and the dashboard: the hook and the routes load at start.
4. In the app: Settings → Push → Send a test push.

## Tests

Offline, with `http_ece` as an independent decryptor:

```sh
uv run --no-project --with cryptography --with http_ece python -m unittest discover -s tests
```
