# The deepening pass — what changed, why, and what it traded

This document records the architectural pass on branch
`refactor/deepen-modules`: eight candidates, each turning a shallow seam
around the app's two deep modules ([`SmsTransactionParser`](../app/src/main/java/com/flowpay/app/payment/sms/SmsTransactionParser.kt)
and [`PaymentSessionManager`](../app/src/main/java/com/flowpay/app/payment/PaymentSessionManager.kt))
into one interface a reader can hold in their head. It answers the questions
the commit messages can't: why each change was made that way, what it buys,
what it costs, and what was deliberately left alone.

Read [ARCHITECTURE.md](ARCHITECTURE.md) for the map of how everything now
fits; this document is the story of how it got there. The two load-bearing
decisions have their own ADRs: [ADR-0001](adr/0001-transactionstatus-is-the-canonical-status-vocabulary.md)
(canonical status words) and [ADR-0002](adr/0002-parser-keeps-its-own-sms-matching-keywords.md)
(parser keeps its own matching keywords).

## The problem this pass solved

A single-module Android app drifts the same way every time: logic that
belongs to one concept gets smeared across every file that touches it. The
architecture audit found eight such smears:

- **Status words** had no canonical home — three `when` tables of 12 arms
  re-derived label, color, and notification title per call site, plus a
  `PaymentStatus` enum with arms (`SUCCESSFUL`, `COMPLETED`, `DECLINED`) that
  no producer ever wrote.
- **The money path's ordering rule** — read the session txn id before any
  window-consuming call — was enforced only by a KDoc comment.
- **~360 lines of dead surface**: a logger with one real caller, uncalled
  repository → viewmodel → DAO read chains, mirror flags nothing consumed.
- **Two in-call dialogs that were ~90% copy-paste** of each other, with
  UPI 123's green hardcoded in seven places.
- **The call overlay** routed every payment state inline in a 995-line
  service, with zero tests on its routing and five near-duplicate
  permission-check names in three files.
- **Bank display names** lived in a `strings.xml` section that had sat empty
  since the tokenization pass, so each screen kept its own copy.
- **~40 hardcoded user-visible strings**, invisible to the line-oriented CI
  greps wherever a literal opened on a following line.
- **The test-configuration screen** echoed state back through a 15-method
  callback interface — untestable by construction.

Each candidate below collapses one smear into one place.

---

## 1. Status words: `TransactionStatus` is canonical

**What changed.** The `PaymentStatus` enum is deleted. The canonical words
live in `TransactionStatus` (the database's six words), and a single module —
[`ui/status/PaymentStatus`](../app/src/main/java/com/flowpay/app/ui/status/PaymentStatus.kt)
— owns the only mapping to label, color, and notification title. Renderers
never re-derive a word. `PaymentState`'s three 12-arm accessor tables
collapsed onto one `SessionData` interface (`transactionId`, `phoneNumber`,
`amount` — the facts every non-idle state carries).

**Why this way.** The words are data (`TransactionStatus` in the DB), and the
mapping to presentation is a pure function of that data. One home means a
new status word compiles through every surface or fails at compile time —
not a silent grey row in history.

**Pros.**
- An unrecognized status now reads as UNVERIFIED everywhere. Before, a junk
  status could render as "Payment Successful" in a notification — the one
  string a payments app must never get wrong.
- The phantom arms are gone; the type says what the app actually produces.
- `PaymentStatus` is pure and unit-tested (9 tests), the first tests the
  status mapping ever had.

**Cons and tradeoffs.**
- One more module to learn: a renderer must import the mapping instead of
  switching on a word inline. In exchange, there is exactly one place to
  look.
- Unverified-by-default can mask a parser gap (a real status the parser
  doesn't emit yet reads as UNVERIFIED, not as an error). The mitigation is
  deliberate: the parser's test corpus is the guardrail, not a runtime
  crash.
- The `SessionData` interface adds a supertype to eight data classes. The
  parameter order and types are unchanged, so callers were untouched — but
  it is one more concept in the state file.

## 2. Claim: one atomic step decides what an SMS means

**What changed.** `TransactionDetector`'s four-method choreography
(`shouldProcessSMS` → `tryClaimSms` → `getSessionTxnId` → `processSMS`) is
now one `@Synchronized` call: [`claim(sender, body)`](../app/src/main/java/com/flowpay/app/helpers/TransactionDetector.kt)
returns a `ClaimedSms` — the parsed transaction plus the session txn id,
captured **before** a consuming match clears the window that holds it — or
null. Both entry points (the SMS receiver and the debug injection tool)
shrink to `claim → ingest`.

**Why this way.** The old protocol spanned callers, and its ordering
constraint (read-before-clear, for orphan reattach after process death)
lived in a comment. A future caller could break it in silence — and the
failure mode is a duplicated or lost payment record. One lock around all
five steps makes the constraint structural: there is no second call to
order wrongly.

**Pros.**
- The orphan-reattach invariant (see ARCHITECTURE.md) is now physically
  impossible to violate from outside.
- The stray-CREDIT exception — a credit never consumes the window the real
  debit confirmation still needs — lives inside `claim()` too, so no caller
  can forget it.
- `TransactionDetectorTest` was rewritten against the seam: 12 tests
  covering every scenario the old choreography had, plus the txn-id round
  trip and the credit rule.

**Cons and tradeoffs.**
- The function does more per call than its predecessors (window + dedup +
  parse + consume). That is the point — but it means the seam is coarser:
  a caller cannot parse without claiming. Nothing needed that.
- `ClaimedSms` carries a `sessionTxnId` that is only meaningful in the
  reattach path. Callers outside the pipeline ignore it — a small price for
  capturing it atomically.

## 3. Deletion: ~360 lines the deletion test cleared

**What changed.** `OverlayLogger` (one real caller, inlined as plain `Log`
calls), `CallManager`'s dead dial-string/overlay pass-throughs and its
Compose snapshot state, the repository → viewmodel → DAO read chain no
screen ever called, `SettingsRepository`'s unconsumed mirror flags, and
`PaymentState`'s two zero-caller presentation extensions.

**Why.** Dead surface is not free: every reader pays to skip it, every grep
returns it, and every refactor must carry it. The deletion test (zero
callers, verified across all three source sets) cleared all of it.

**Pros.**
- −358 lines net, and the surviving read surface is exactly the surface the
  screens use — the repository is now honest about its shape.
- The tested `PaymentTransactionStore` half of the repository is untouched;
  the DAO lost only `@Query` methods with no callers (schema unchanged).

**Cons and tradeoffs.**
- If a future screen needs a deleted read method, it must be re-added
  deliberately. That friction is the feature: re-adding means someone
  decided the surface should grow.
- Stale entries for deleted code remain in `detekt-baseline.xml` and
  `lint-baseline.xml`. They are harmless — unmatched baseline entries don't
  fail builds — but they are tidiness debt.

## 4. Dialog band: one dialog over a `PaymentRail`

**What changed.** The USSD and UPI 123 in-call progress dialogs — ~90%
copy-paste of each other — are one
[`PaymentProgressDialog`](../app/src/main/java/com/flowpay/app/ui/dialogs/PaymentProgressDialog.kt)
parameterised by a sealed
[`PaymentRail`](../app/src/main/java/com/flowpay/app/ui/dialogs/PaymentRail.kt)
whose members are exactly the two per-rail things that differ: the badge
icon and the accent theme. The dialog manager's four builder methods are one
`show(outcome: TerminalDialog)` with the acknowledge-on-dismiss rule explicit
per outcome.

**Why this way.** The twins had already drifted in small ways (button
sizing, one extra shortcut) — pixel-level deltas preserved exactly through
the merge. Green branding for UPI 123 is deliberate rail identity, so it
travels with the rail through the theme seam (`GreenAccentTheme`) instead of
living hardcoded in the shared composable.

**Pros.**
- A third rail (RBI keeps adding them) is a new `PaymentRail` member, not a
  forked dialog.
- `TerminalDialog.AwaitingConfirmation.ackOnDismiss = false` encodes the
  session rule (the dialog can't acknowledge the session away while the
  bank's SMS is still pending) in the type, not in a comment.

**Cons and tradeoffs.**
- The merged composable has more parameters than either twin (the union of
  both surfaces). `LongParameterList` is suppressed with precedent; the
  alternative — a params object — was more indirection for one call site.
- `PaymentRail` is a new concept to learn. The glossary defines it.

## 5. Overlay presenter: the service decides nothing

**What changed.** Every PaymentState → what-to-show decision moved out of
[`CallOverlayService`](../app/src/main/java/com/flowpay/app/services/CallOverlayService.kt)
into [`OverlayPresenter`](../app/src/main/java/com/flowpay/app/services/OverlayPresenter.kt)
— a pure mapper over an exhaustive `when` producing an immutable `OverlayUi`
(which dialog, what progress, how to wind down, whether to hang up). The
service keeps only effects: window management, the watchdog, the documented
start choreography, audio, haptics, and the actual view/dialog calls.
Overlay-permission checks collapsed from five names in three files to
`PermissionManager`.

**Why this way.** The service was the single most untested module on the
money path — the watchdog → cancel route had zero coverage. Effects and
decisions in one body is why: decisions can't be tested without a device
when they're tangled into pacing animators. Splitting them made the routing
the first thing that *can* be tested.

**Pros.**
- `OverlayPresenterTest` (10 tests) locks the mapping, including the
  watchdog route and the cancelled-by-user vs cancelled distinction.
- The `when` is exhaustive with no `else`: a future `PaymentState` variant
  will not compile until the mapping decides what it means — the safety
  property you want on a live-payment surface.
- The finish delay is an enum (`Now` / `AfterBarHold`), not milliseconds —
  so timing constants stay service-side and the mapper stays pure.

**Cons and tradeoffs.**
- One more indirection: reading the overlay behavior now spans two files.
  In exchange, each file is one thing.
- Decisions that were hopelessly entangled with pacing coroutines would
  have stayed in the service — none were; the split is clean.

## 6. Banks and gates: one directory, one fail-closed gate

**What changed.** Bank display names live in
[`data/BankDirectory`](../app/src/main/java/com/flowpay/app/data/BankDirectory.kt)
(`bankId → @StringRes name`), consumed by setup, the main screen, and
settings — the `strings.xml` section that had sat empty is its backing. And
[`MainActivityHelper.initiateTransfer`](../app/src/main/java/com/flowpay/app/helpers/MainActivityHelper.kt)
— the last stop before dialing — now fails closed when `RECEIVE_SMS` is
missing, reusing the existing grant-SMS copy.

**Why this way.** The parser keeps its own SMS-matching keywords (ADR-0002):
the directory is display vocabulary only. And the UI's pre-flight permission
checks are affordances — a user can get past them, or grant-and-revoke
between screens. A payment without `RECEIVE_SMS` cannot ever see the bank's
confirmation, so the app would silently produce an unverified outcome. The
gate refuses to dial instead.

**Pros.**
- A new bank is one directory entry and one string — not three disagreeing
  copies of the same list (settings had duplicated all ten).
- The fail-closed gate makes the money-path precondition structural.

**Cons and tradeoffs.**
- A user who deliberately denies `RECEIVE_SMS` now hits a hard stop at
  transfer time, with the toast telling them why. That is correct — the app
  cannot keep its core promise without it — but it is a stricter behavior
  than the UI prompts alone.
- `BankDirectory.name()` falls back to HDFC on an unknown id rather than
  crashing. A wrong id renders a wrong name instead of failing loudly —
  acceptable for display-only data, and tested.

## 7. Copy: enforcement at the parameter seam

**What changed.** User-visible composable text moved to `@StringRes`
parameters — `PermissionExplanationDialog`, the settings rows, the QR black
screen — with ~40 hardcoded leaks fixed across five screens and the overlay.
The CI greps stay as a backstop, with a comment in `build.yml` naming the
parameter seam as the primary enforcement.

**Why this way.** The greps pattern-match call-site formatting, so they miss
exactly the forms this codebase leaked through (multi-line calls, parameter
forms). The compiler doesn't miss anything: an `@StringRes` parameter cannot
receive a literal.

**Pros.**
- All three CI gate patterns verified green; every on-screen string is
  resolved from `strings.xml`.
- The seam scales: a new screen inherits enforcement from its composable
  signatures, not from a lint rule someone must remember to run.

**Cons and tradeoffs.**
- `@StringRes` parameters are stricter than `String` for callers with
  dynamic text — `SettingsRow`'s value stays `String` deliberately, since it
  legitimately carries dynamic data (bank name, version, SIM).
- A known gap remains: copy returned by `SetupHelper`'s validation results
  is still hardcoded English. It is deferred to its own pass (below).

## 8. Echo interface: one `StateFlow<TestState>`

**What changed.** `TestConfigurationHelper`'s 15-method callback interface
and 13 mirror variables are one immutable
[`TestState`](../app/src/main/java/com/flowpay/app/helpers/TestConfigurationHelper.kt)
data class exposed through a single `StateFlow`; the activity renders by
collecting it. Commands the activity owns (toasts, navigation, permission
requests, completion persistence) are constructor lambdas.

**Why this way.** The 13 double-write patterns (local var + callback) and
the 4-touch confirmation flows were untestable by construction — a test had
to reimplement the callback to observe anything. One state flow is the same
pattern the ViewModel screens already use, minus the ViewModel class.

**Pros.**
- The 13-state machine has its first tests: 16 Robolectric tests covering
  every transition, including the 25-second timeout driven via shadows.
- The screen's behavior is readable in one data class instead of inferred
  from 15 callback methods.

**Cons and tradeoffs.**
- `TestState` is one 13-field object instead of 15 focused methods. The
  fields are the echo surface — each is written exactly once per
  transition — so the object is honest about the screen's real state size.
- No ViewModel was introduced (deliberate — see below); the seam is the
  pattern without the class.

---

## What was deliberately NOT done

Honest about what isn't consolidated, and why:

- **No ViewModel for the first-run screens.** `SetupActivity` and
  `TestConfigurationActivity` hold no state worth surviving process death.
  The test-configuration screen got the StateFlow *pattern* without the
  ViewModel *class*; `SetupActivity` keeps its older `UICallback`.
- **Helper-returned validation copy is deferred.** `SetupHelper`'s
  validation results still return hardcoded English strings. Moving them is
  a mechanical but wide pass of its own; it is the last known copy leak.
- **SIM carrier names are deferred with it.** `"Jio"`/`"Airtel"` literals
  are duplicated in off-limits `SetupHelper`; converting only the settings
  copy would split the vocabulary. The empty "SIM Carriers" section in
  `strings.xml` is their future home.
- **`CallManager`'s per-caller instantiation and deprecated listener stay.**
  Reachable only from the narrow test-configuration surface; rewiring to the
  app-scoped coordinator buys nothing for its own sake (ARCHITECTURE.md
  documents this).
- **Previously-saved test results still don't restore completion flags.**
  Pre-existing product behavior (the old poll clobbered them too); honoring
  them is a product decision, not a refactor.

## The commit shape, honestly

The plan called for nine independent per-candidate commits. The landed work
turned out to be interlocked — `CallOverlayService` carries four candidates'
edits, `MainActivity` three, `TestConfigurationActivity` couples the dialog
and echo work — and with no local Android SDK, intermediate states could not
be compile-verified. So the branch carries four commits instead: the glossary
and ADRs, the claim protocol (a clean island on the money path), the other
seven candidates as one coherent commit, and the docs sync. Each commit is
described in its message; this document is the map across them.

## How this was verified

No Android SDK was available where the pass was written, so nothing was
compiled locally. Instead: exhaustive greps across all three source sets for
every deleted and renamed symbol; the CI gate patterns re-run by hand; and
47 new unit tests written at the new seams (`PaymentStatusTest` 9,
`TransactionDetectorTest` 12, `OverlayPresenterTest` 10,
`TestConfigurationHelperTest` 16) plus `BankDirectoryTest`. The compiler and
the full suite run in CI on every push — that is the layer this pass leans
on for what greps cannot prove.
