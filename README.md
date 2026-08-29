# Syekso-API

[![CI](https://github.com/Rodolphe18/AccessControllerServer/actions/workflows/ci.yml/badge.svg)](https://github.com/Rodolphe18/AccessControllerServer/actions/workflows/ci.yml)
![Java](https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0-6DB33F?logo=springboot&logoColor=white)
![Spring Security](https://img.shields.io/badge/Spring%20Security-6DB33F?logo=springsecurity&logoColor=white)
![MongoDB](https://img.shields.io/badge/MongoDB%20Atlas-47A248?logo=mongodb&logoColor=white)
![WebSocket](https://img.shields.io/badge/WebSocket-%2Fws-005571)
![WebRTC](https://img.shields.io/badge/WebRTC-signalling-333333?logo=webrtc&logoColor=white)
![Gradle](https://img.shields.io/badge/Gradle-02303A?logo=gradle&logoColor=white)
![Tests](https://img.shields.io/badge/tests-67%20passing-brightgreen)

**The backend of a connected building-intercom demo — JWT for residents, a shared key for lobby
devices, and a WebSocket relay that carries WebRTC signalling for door calls.**

The backend behind [Syekso](https://github.com/Rodolphe18/AccessControlApp), a building-access demo: residents
open doors from their phone over Bluetooth, hand out temporary codes to visitors, and answer a video
call from the lobby intercom to let someone in remotely.

Spring Boot 4 on Java 21, MongoDB Atlas, and a WebSocket relay carrying WebRTC signalling. It serves
two real Android applications and an ESP32 door lock — not mocks.

---

## What it does

| | |
|---|---|
| `POST /auth/login` | Email and password in, a signed JWT out |
| `GET /me/doors` | The doors a resident may open, with the BLE name each lock advertises |
| `POST /me/activations` | Redeem a building code — the pivot of onboarding |
| `POST` `GET /me/pin-codes` | Issue and list single-use PINs for a visitor |
| `POST` `GET /me/invitations` | Issue and list windowed, reusable invitations |
| `POST /intercom/validate` | The lobby keypad checks a code and claims it in the same breath |
| `POST /intercom/open-result` | The intercom reports whether the door actually opened |
| `GET /intercom/directory` | The residents an intercom may ring |
| `GET /feed/offset` `GET /feed/cursor` | Two pagination strategies, side by side, on purpose |
| `WS /ws` | Call signalling: ring, accept, WebRTC offer/answer/ICE, open, hangup |

Three kinds of caller, three ways of proving who they are: a resident carries a JWT, a lobby intercom
carries a shared key in a header, and the signalling socket authenticates its own handshake in the
first frame — the one place Spring Security cannot help, because identity does not arrive in a header.

## How the pieces fit together

```
  Resident phone  ──HTTP──┐                     ┌──BLE──▶  ESP32 lock
   (Syekso app)           │                     │
                          ▼                     │
                   THIS SERVER ◀──HTTP──  Intercom phone
                     :8080                 (Syekso intercom app)
                          │
                          ├── MongoDB Atlas
                          └── /ws  ◀── WebRTC signalling relay ──▶
```

The server never touches a door. It decides *who may*, hands out the credentials, and relays the call
that lets a resident decide about a stranger. Opening is BLE, phone to lock, and stays that way.

## Architecture

Packages are organized **by feature**:

```
auth/       login, JWT issuing and verification
doors/      buildings, doors, activation codes
access/     single-use PINs and invitations
intercom/   what the lobby device may ask
feed/       the pagination exercise
signaling/  the call state machine and the /ws endpoint
users/      the resident aggregate — shared, and assumed as such
shared/     error vocabulary and the exception advice
config/     security, WebSocket, Mongo, seeding — the composition root
```

The point is not tidiness. Under layer packages every class has to be `public`, because its caller
always lives elsewhere, and Java's default visibility — a real encapsulation tool — protects nothing.
Grouped by feature, most classes drop to package-private and the **compiler** enforces the boundary:
of the types in the four business features, 14 are public and each has a demonstrated consumer outside
its package. The rest cannot be reached at all.

### Decisions worth pointing at

**Two security chains, not one with two branches.** A resident's JWT and an intercom's shared key have
nothing in common, so each gets its own `SecurityFilterChain`, selected by path. They are alternatives,
not layers — a request to `/intercom/**` never meets the JWT filter. Neither filter ever rejects
anything: they answer "who is calling", and the authorisation filter decides. Authentication and
authorisation stay two separate steps, which is exactly what makes them testable separately.

**Some authorisation cannot be a URL rule.** "May this resident issue a code for that door?" depends on
data, not on a path. The lookup starts from the buildings the resident has joined, so a door outside
them can never be found — a resident cannot mint a PIN for someone else's building by guessing an id.
That check lives in the service and is covered by unit tests, because no `requestMatchers` line could
ever express it.

**The database arbitrates races, not the code.** Claiming a single-use PIN and redeeming an activation
code are conditional updates — "set this field only if it still holds that value, and tell me whether
you actually did". Reading first and writing second leaves a window where two callers both see the code
as free. Two intercoms presenting the same PIN at the same instant get one yes and one no.

**The wire format is a contract.** Two deployed Android apps parse this JSON with kotlinx.serialization
and will not be redeployed for the server's benefit. Every field name is fixed, including the eleven
signalling message types. Several modelling choices follow from that alone — boxed types where a
document may legitimately omit a field, and Jackson left at its default null handling.

**Storage does not depend on the source tree.** Spring Data stamps each document with a `_class` type
hint containing a fully qualified class name. That makes stored data depend on your package layout —
move a class and live documents point at one that no longer exists. It is switched off; no collection
here is polymorphic, so the hint bought nothing and cost a coupling.

**Exhaustiveness over a default branch.** The signalling router pattern-matches a sealed hierarchy with
no `default`, listing even the messages it ignores. A `default` makes any switch exhaustive, so the
compiler stops checking — and a new message type would compile cleanly, then be dropped on the floor at
runtime, silently, on both sides.

## It was a Ktor server first

This started as Ktor + Kotlin and was rewritten in Spring Boot 4 / Java 21, iteration by iteration,
with both servers running side by side against the same Atlas cluster so every endpoint could be
compared response by response. That comparison caught three bugs a code review would not have: a null
door id, a Mongo property Boot 4 had silently renamed, and Jackson's move to a new root package.

The Kotlin sources were deleted once the rewrite reached parity on all fifteen routes plus `/ws`. They
remain in the history at `9cedab8`.

Java rather than Kotlin, deliberately: IntelliJ converts Java to Kotlin but never the reverse, so
"write it in Kotlin now, convert later" means writing it twice.

## Two stack choices that were not defaults

**Raw WebSocket frames, not STOMP.** The signalling protocol is a JSON envelope two Android apps
already speak, so a messaging layer on top would buy nothing and impose a broker's vocabulary on a
contract that is already fixed.

**Jackson only, on both sides of the wire.** The Kotlin server used kotlinx.serialization; keeping two
serialisers in one application means two places for a field name to drift. The apps keep kotlinx —
only the JSON has to match, not the library.

## Running it

```bash
# MONGODB_URI must be set — the Atlas credentials never live in the repository
./gradlew bootRun          # port 8080, override with SERVER_PORT
./gradlew test             # 67 tests
```

An empty database seeds itself on first start: resident `rodolphe@example.com` / `password`, building
"Résidence Montmartre" with two doors, and an unredeemed activation code `MONT-2026`. The BLE names are
the ones the ESP32 firmware advertises, so the hardware demo works out of the box.

Against a running instance, `scripts/smoke-cutover.ps1` runs eighteen end-to-end checks — login, the
token round trip, both security chains refusing each other, a PIN issued and spent, pagination.

## Tests

67 of them, in two layers that catch different things.

Unit tests cover what only they can reach: concurrent races simulated by stubbing an update that
matched nothing, cross-building access with a real door id, the call state machine's timeout racing an
incoming accept. Integration tests run over the real filter chain with `@SpringBootTest` and MockMvc —
not `@WebMvcTest`, whose slice would exclude the very `SecurityConfig` they exist to exercise.

They assert on the call rather than on a stubbed payload wherever they can. An unstubbed mock returns
null and the controller still answers 200, so a bare `isOk()` often demonstrates nothing at all.

## Not built (deliberately)

No refresh tokens, no rate limiting, no TURN server — the demo runs on one LAN, where STUN is enough.
No manager application, which is why activation codes are seeded rather than issued. The intercom's
shared key is a weak scheme and known to be one: anyone who reads it becomes the intercom. The honest
upgrade is a per-device credential, and it is out of scope for a portfolio piece whose subject is the
resident's experience.
