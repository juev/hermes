# Message delivery and history reconciliation

What the app can and cannot promise about a message the user sends, checked against hermes-agent
v0.22.5. It is the contract the delivery states in `ChatController` and the history cache are built
on.

## What a submission is

- The chat sends `prompt.submit` over the socket with `session_id` and `text`. The answer is
  `{"status": "streaming"}` when the turn starts, or `{"status": "queued", "admission_id": ...}`
  when the session is busy; an error comes back as a JSON-RPC error.
- The server appends the user message to its transcript and starts the turn. The socket events of
  the turn (`message.start`, `message.delta`, `tool.start`, `message.complete`, …) carry the live
  session id, not the submission.
- **`prompt.submit` has no client message id and no idempotency key.** `session.create` has an
  `idempotency_key` and `session.mutate` takes a `request_id` with a revision fence, but a prompt
  does not. A retried prompt is a second turn on the server.
- Message row ids are assigned by the server when it stores a message; the client learns them only
  from a later history read. They identify a stored message, not the submission that made it.

## What the app does

- The user's message is shown at once with the state `SENDING`. When `prompt.submit` returns it
  becomes `SENT`. If the server answers with an error, or the submission fails before the prompt is
  sent, it becomes `FAILED` and the server's message is shown. If the answer is lost after the
  prompt may have gone out (a timeout or a dropped connection), it becomes `UNKNOWN`.
- `UNKNOWN` and `FAILED` messages stay in the chat with a one-line note; the text is not moved back
  into the composer and is never lost while the chat is open. The app **never resends a prompt on
  its own**: a reconnect or a reload does not repeat it, because that could run the turn twice.
  The user can edit the message (which rewinds and resubmits) or send it again deliberately.
- The server's history is authoritative when it is read: a chat reopened or refreshed shows the
  stored transcript. The phone keeps that transcript in the response cache (see `ChatController`'s
  history warm-up), so a chat made in the app opens again without waiting for the server.
- A message the server has stored and one still only on the phone cannot be matched by an id, because
  the client never chose one. The app therefore does not merge them automatically: the local copy is
  kept until the history replaces the chat.
- A submission still only on the phone does not survive a restart of the app: the chat history cache
  holds what the server stored, and there is no local store for a prompt the server may not have.
  Writing one needs a key for the chat before its first message and is left for the server-support
  step below.

## What would make retries and reconciliation exact

Server support is needed; the client cannot invent identity safely.

- `prompt.submit` accepting a `client_message_id` and echoing it in the stored user row and in the
  `message.start` / `message.complete` events. A retry with the same id could then be idempotent,
  and a client could find its message in any history page.
- A cursor over the stored transcript (`messages.since(seq)`) with stable row ids and tombstones for
  edits and deletions, so a phone can reconcile concurrently changed history instead of replacing
  the whole transcript.
- Acknowledging a submission with the stored row id (or the client id) as soon as the row exists,
  which would let the app confirm `UNKNOWN` submissions without guessing from text or position.

Until then the contract is: no automatic retry, keep the user's words, say when delivery is not
confirmed, and let the stored transcript win.
