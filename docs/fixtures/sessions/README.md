# Sessions and their messages

Captured 2026-10-30 from the dashboard on loffe, v0.22.5, through the tailnet at
`https://hermes.evsyukov.org`, with the token of a finished `native_pkce` sign-in and
`Authorization: Bearer …`. The installation holds 263 sessions at the time; five recent ones are in
`list.json` and one transcript is in `transcript.json`.

## How they were redacted

Every string is replaced by `<text:N>` — N is the original length — unless it is structural:
`role`, `type`, `source`, `model`, `display_kind`, `cost_status`, `finish_reason`, `tool_name` and the
`name` of a tool call's function. Ids (`id`, `session_id`, `message_uid`, `call_id`, `tool_call_id`, …)
are `<id>`. An empty string stays empty, because its emptiness is what a test reads. Numbers, booleans
and nulls are kept as they were, so shapes and sizes survive and nothing a person wrote does. Anything
not listed above as structural was redacted, including titles, previews, prompts, tool arguments and
tool output.

## What the shapes say

`GET /api/sessions` answers `{limit, offset, total, storage, sessions}`. A session object carries about
seventy fields; the ones a client needs to draw a sidebar are in `list.json`: `id`, `title` (null on a
session the server never named), `preview`, `source`, `model`, `message_count`, `started_at`,
`last_active`, `pinned`, `archived`, `is_active`, `unread`, and the spending totals `input_tokens`,
`output_tokens`, `cache_read_tokens`, `cache_write_tokens`, `actual_cost_usd`, `estimated_cost_usd`
and `cost_status` (`included` when the plan bills by subscription, `estimated` when the server priced
it itself).

`GET /api/sessions/{id}/messages` answers `{session_id, profile, pagination, messages}`. A row carries
`role`, `content`, `timestamp` and, depending on the role:

- `user`: the words, in `content` as a plain string.
- `assistant`: often an empty `content` and a `tool_calls` list; `reasoning` and `reasoning_content`
  hold the same text when the model reasoned, `finish_reason` says how the turn ended.
- `tool`: the output in `content`, plus `tool_name` and `tool_call_id` tying it to one call.
- `session_meta`: an empty row — `content` and everything else null — which a client skips.

A tool call is `{call_id, id, response_item_id, type: "function", function: {name, arguments}}`.

Across 773 rows of fifteen sessions, `content` was a plain string every time it was not null; no
array-of-parts content appeared, so the multi-part form the Android client also handles did not show up
here and is not covered by these fixtures. `display_kind` appeared twice as `steer` and once as
`process_complete`.

Two limits of the redaction, for whoever writes a test on these files:

- every id is the same placeholder, so which tool output belongs to which call cannot be read from
  them; a client's matching has to be checked with ids of its own;
- a title, a preview and a tool's arguments are placeholders, so their text cannot be asserted either.

`transcript.json` is a capture of a finished session: the numbers in `list.json` come from a session
that was still running and grow between captures, so a test should compare a parsed field with the
same field of the same file rather than with a number written down once.
