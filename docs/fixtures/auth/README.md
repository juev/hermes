# The native sign-in of the dashboard

Captured 2026-10-30 from the dashboard on loffe, v0.22.5, plain dashboard mode, by calling the process
directly (`docker exec hermes python`) with `Host: hermes.evsyukov.org`. No sign-in was completed: the
identity provider needs a person, so the last hop — the dashboard redirecting the browser back to the
loopback address with a code — is still unverified.

`native-redirect-refusals.json` holds the four answers of `/auth/native/authorize` for four
`redirect_uri` values, the `302` that starts the browser flow, and the two refusals of
`/auth/callback` that show it is the identity provider's landing page and not the client's.

What this settles, and what it does not:

- Settled: only `http://127.0.0.1:<port>/…` or `http://[::1]:<port>/…` is accepted; `https`, a custom
  scheme and the `localhost` name are refused, each with its own `400 detail`.
- Settled: the browser is sent to the identity provider with the dashboard's own `/auth/callback` and
  with the client's `state` and `code_challenge` passed through.
- Settled: the pending sign-in is held in the `hermes_session_pkce` cookie for the browser, so a client
  needs no cookie jar of its own.
- Not settled: the shape of the loopback callback after a real sign-in — whether it carries `code` and
  `state` as the Android client expects. The observation that would settle it is one completed sign-in.
