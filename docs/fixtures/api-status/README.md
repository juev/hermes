# `/api/status` and the unauthenticated answer

Captured 2026-10-30 from the dashboard on loffe, server v0.22.5 (`release_date` 2026.9.24,
`config_version` 51), plain dashboard mode (`gateway_mode: "single"`, no `session_authority`).

Both requests went to the dashboard process itself, not through Caddy:

```sh
docker exec -i hermes python - <<'PY'
import urllib.request
req = urllib.request.Request("http://127.0.0.1:9119/api/status",
                             headers={"Host": "hermes.evsyukov.org"})
print(urllib.request.urlopen(req, timeout=10).read().decode())
PY
```

## `dashboard.json`

`GET /api/status`, no `Authorization` header, answered `200`. Every field the dashboard returned is
kept as it was, with one exception: `install_id` is replaced by `REDACTED`, because this file lives in a
public repository and that value identifies one installation. Nothing else was changed, added or
removed.

`auth_required` is `true`, `auth_flows` is `["cookie", "native_pkce"]` — so the browser sign-in of
`docs/protocol.md` §3 works on this server — and `auth_providers` is `["self-hosted"]`. Sections 1 and 3
of the contract used to show `["nous", "github"]` as an example; that is a real provider list of some
other deployment, not this one.

## `unauthorized.json`

The body of `401` from `GET /api/sessions`, `POST /api/auth/ws-ticket` and `GET /api/ws` without a
token. All three answer the same object, and `/api/ws` answers `401` before the upgrade rather than the
`403` the contract described at first.
