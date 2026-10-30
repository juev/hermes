# Search over the chats

Captured 2026-10-30 from the dashboard on loffe, v0.22.5, through the tailnet with the token of a
finished sign-in: `GET /api/sessions/search?q=hermes&limit=5`.

Redacted like the other payloads: every string is `<text:N>` unless it is structural (`role`,
`source`, `model`), and ids are `<id>`.

What it settles:

- the answer is `{results: [...]}` — there is no `total` to page with, so a client asks for a limit
  and shows what came back;
- a hit repeats the session fields beside the match, under both `id` and `session_id`;
- `role` is the role of the message the match was found in — `tool` in every hit of this capture;
- `snippet` keeps the text around the match as it was, newlines and all, so a one-line row has to
  fold it;
- the hit carries no `pinned` and no `unread`, unlike the list of chats: a row built from a hit reads
  those as false.
