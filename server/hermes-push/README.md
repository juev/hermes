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

## Your own ntfy server

The app does not know which push server it uses: the ntfy app on the phone is the UnifiedPush
distributor and gives the app an endpoint on its own default server, such as
`https://ntfy.example.org/upAbC123?up=1`. The plugin posts the encrypted message there. Any
ntfy server works, including one with access control. Checked with ntfy 2.34.0.

### Server

A server open to everyone needs nothing. With `auth-default-access: deny-all` the plugin
cannot sign in, so anonymous clients need write access to the UnifiedPush topics, which all
start with `up`:

```sh
ntfy access '*' 'up*' write-only
```

Without this rule ntfy answers `403 forbidden` and no push arrives. The rule gives write
access only: reading a topic still needs a user. The `Authorization: vapid …` header the
plugin sends does not get in the way, ntfy treats it as no sign-in.

The phone reads its topics as a user of the server:

```sh
ntfy user add phone
ntfy access phone 'up*' read-only
```

`upstream-base-url` is not needed: it serves iOS, and the Android app connects to the server
directly.

### Phone

1. Install ntfy, from F-Droid or Google Play.
2. In ntfy: Settings → Default server, enter the server's URL.
3. If the server has access control: Settings → Manage users → Add new user, with the same
   URL, the user name and the password.
4. In Hermes: Settings → Push → Push from the server. With several distributors installed,
   pick ntfy.
5. Settings → Push shows `On: the server pushes to ntfy`. Send a test push.

### Troubleshooting

- **Sent to 0 phone(s).** The plugin could not post to the endpoint. The dashboard log has
  `hermes-push: send to <phone> failed` with the HTTP status. A 403 means the `up*` rule is
  missing.
- **Sent, but nothing arrives.** ntfy on the phone cannot read the topic: check the user, its
  password and its `up*` read access in Manage users.
- **The default server of ntfy changed.** Existing endpoints stay on the old server. Switch
  Push from the server off and on in Hermes: the app drops the old endpoint and gets a new one.
- **Push did not connect.** The app could not reach the plugin or ntfy gave no endpoint. The
  app tries again at the next start; until then it checks for news by itself.
- **The server has no hermes-push plugin.** The dashboard has not loaded the plugin: see
  Install.

## Tests

Offline, with `http_ece` as an independent decryptor:

```sh
uv run --no-project --with cryptography --with http_ece python -m unittest discover -s tests
```
