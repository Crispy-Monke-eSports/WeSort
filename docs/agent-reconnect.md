# Agent connection resilience

The signed control channel has its own HTTP client, bounded worker and in-flight flag. Physical requests remain serialized on the work lane. All game state and response application remain on the client thread.

While the WeSort module is active, configured and a Minecraft player/world exists, the agent polls control every two seconds following a verified response. On failure it invalidates the control registration and tries hello at fixed 15-second start intervals (10-second complete-response timeout). A signed HTTP 204 from an idle poll is normal success, not a disconnection. Successful hello re-establishes the epoch; changed epochs invalidate old job receipts and manifest hints. Neither order backoff nor sorting, inventory recovery, navigation pauses, quiet-mode gates or a slow physical request delay this retry schedule.

Missing/invalid URL, missing key, disabled module, disconnected Minecraft world, suspended game or blocked client thread cannot produce a functioning control session. The first eligible tick resumes the connection; authentication rejection, TLS errors, clock skew and incompatible/wrong Manager configuration still fail closed. No automatic credential replacement or physical reservation release occurs.

Control presence is based on verified control replies, not a successful warehouse request. Server-side accepted settings/receipts also refresh the control session's activity. Scope and generation checks discard stale replies.

## Restart acceptance cases (isolated environment)

- Restart Manager during idle, scanning, sorting, packing and rollback. Verify signed hello recovers without toggling the module and the existing physical journal is preserved.
- Drop DNS/TCP or stall a response after headers. Observe retry starts 15 seconds apart while eligible, then successful registration when restored.
- Return 401/403, an unsigned/tampered 200, 409 session conflict, 429, 5xx, malformed JSON; verify no unauthenticated jobs or manifest acknowledgements are applied and retry continues.
- Change endpoint/key/world while a response is in flight. Verify stale results are ignored and a new scope is registered.
- Keep the work lane blocked, inventory unready and sorting denied: the control channel must still poll.
- Disable the module or leave the world: no new control requests until eligible.

## Dedicated signed heartbeat (negotiated)

A modern agent advertises `heartbeat` in hello. The Manager acknowledges support in its signed hello response. `/api/v1/bot/heartbeat` accepts only the current signed bot identity, session and epoch, and returns the exact random `ping` challenge as `pong`. It does not consult the order book, stash index, automation lease, or job queue. The client uses its own HTTP client and worker for ping/pong, separate from both control and physical traffic. Normal pings run every 5 seconds after a verified response; complete response timeout is 5 seconds; failure retries start at 15-second intervals and trigger fresh hello if the control session was registered. A fresh epoch pings immediately. Modern Manager presence requires a real heartbeat within 35 seconds; hello and work polls cannot manufacture it. Legacy peers without negotiated support retain signed control polling. Both versions must be deployed for dedicated heartbeat.
