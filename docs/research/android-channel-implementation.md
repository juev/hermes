# Android channel implementation evidence (#155)

## Candidate and scope

This change adds an opt-in Android connection plus the installable
`server/hermes-android` 0.1.0 package. It does not replace the existing dashboard
connection, patch Hermes core, deploy a server, publish an app release, or migrate
old conversations. See [installation/rollback](../../server/hermes-android/README.md)
and [protocol v1](../protocol.md#16-android-channel-plugin-v1).

The package uses the documented dashboard router and completion/compaction hook
seams. HTTP/WS conversation membership comes from a server-owned registry, not
client source labels. Cache keys, pin lookup, retained-observation lineage and
push registration are separate from dashboard mode. Scoped push works without a
phone socket; the phone rejects old/unscoped owner markers. Native model context
and tool availability are not changed.

## Executed checks

- **Android:** the complete `testDebugUnitTest` task executed, with **1,020 tests,
  0 failures, 0 errors, 0 skips** across 127 XML suite reports. This includes the
  actual plugin socket decoder, saved-profile Session wiring, owned chat resume,
  separate receipt metadata, canonical admission correlation, old pin separation
  and push transition/owner checks, as well as the unchanged ordinary-mode cases.
- **Lint:** `lintDebug` succeeded, **0 errors and 28 warnings**.
- **Builds:** debug APK and unsigned, R8-processed release APK produced/verified.
  The real tailnet AAR and licenses were generated with the repository's pinned
  NDK; both APKs passed `validate-native-artifact.py` with actual SDK zipalign.
  No signed release or 16-KiB-device runtime execution is claimed.
- **Native guard tests:** **22 passed**.
- **Server:** **42 tests passed** in a fresh Python 3.14 venv, with the exact
  official Hermes SDK source archive selected by CI on `PYTHONPATH`. These use
  real SQLite, crypto, ASGI and loopback WebSockets; authentication/native actors
  are explicitly synthetic where stated in each test. The real SDK backend
  loader mounted the package and refused unauthenticated requests.
- **Live native bridge:** on Hermes v0.23.0 upstream
  `2bd378ed1b8d6bf2192cb26e0a55ff3091e6bd19`, the product NativeClient/Bridge
  created an owned conversation, submitted a real turn, received clarify,
  disconnected, restored that exact unanswered question, answered it, received
  the exact requested final reply and detached. This used an operator-only
  diagnostic bootstrap, not a deployed native-sign-in plugin session.

The initial combined Android gate lost its daemon before finishing. The container
reached its unchanged 8-GiB memory limit; exact kill attribution was unavailable.
That failed attempt is retained privately. Without changing source, aggregate
limits or other processes, separate ordinary Gradle unit/lint/assembly invocations
completed successfully. The unit result above is from the successful full run,
not an aggregation of focused attempts.

## Measured client-bound payloads

`server/hermes-android/tests/test_wire.py` sends the same deterministic synthetic
reasoning/tool-heavy workload over actual loopback WebSockets through the product
NativeClient/Bridge/relay, alongside the full-delivery baseline. Compression is
disabled. The client receives a required clarification and the final readable
reply in both cases.

- Full delivery: **460,789 bytes, 119 frames**.
- Minimal channel: **1,066 bytes, 8 frames**.
- Reasoning, ordinary arguments/results and a foreign conversation marker are
  absent from the minimal client-bound payloads.

This is WebSocket application-payload accounting for this fixture, not a real-model
performance benchmark, HTTP/TLS/TCP accounting or a device battery measurement.
The live native probe's native/event counters have a different boundary and are
not substituted for these whole-WebSocket measurements.

## Review findings resolved during implementation

Test-first regressions cover title-as-identity adoption, native canonical mutation
translation, model confirmation, operation ownership/restoration, terminal errors,
legacy gateway refusal, exact receipt correlation, typed/private history blocks,
compaction aliases across HTTP and socket recovery, foreign selectors and ticket
replay. Canonical `session.title` returned `-32601` live; creation labels now use
revision-fenced `session.mutate rename`. Native history may return a compaction tip;
only a trusted alias of the same logical conversation is accepted and normalized.

Review was performed in the original implementation conversation. No independent
reviewer/agent was launched; independent human review is still pending.

## Acceptance still pending — do not deploy or close #155 on these tests alone

1. Operator-approved activation on a compatible profile/listener, without silently
   changing plugin isolation or restarting unrelated work.
2. The full real native-PKCE sign-in → mounted plugin facade → Android path, with
   real independent platform actors on that same server/account. The live native
   bridge and authenticated synthetic boundary tests are separate evidence, not a
   substitute for this deployment-specific run.
3. Real distributor/phone notification delivery, offline recovery and profile
   switching in that installed environment. No measured device energy claim.
4. Independent review and exact published-head CI. This candidate is a reviewable
   draft, not production acceptance, a merged feature or a published release.

Raw execution logs, local runtime IDs and probe receipts remain private ignored
build artifacts. Public [fixtures](../fixtures/android-channel/v1.json) are labelled
synthetic examples, not fabricated production captures.
