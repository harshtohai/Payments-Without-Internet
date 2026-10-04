# Flowpay domain glossary

Shared vocabulary for the Flowpay codebase. Use these words in code, docs,
and reviews; don't coin synonyms. Broader system context lives in
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md); decisions that close a
conversation live in [docs/adr/](docs/adr/).

## Status words

The six canonical outcome words a payment can carry — `PENDING`, `SUCCESS`,
`FAILED`, `CANCELLED`, `UNVERIFIED`, `NEEDS_REVIEW` — defined once in
`TransactionStatus`. Everything the user sees about an outcome (labels,
colors, notification titles) is derived from them in one place: the
`PaymentStatus` mapping module. A screen never invents its own word for a
payment outcome. The session machine's ephemeral states (`Initiating`,
`InProgress`, `WaitingForVerification`) are not status words; they are
session state.

## Session data

The facts every non-idle payment carries, on every `PaymentState`:
which payment (`transactionId`), to whom (`phoneNumber`), for how much
(`amount`). One home — the `PaymentState.SessionData` interface — instead of
per-state accessor tables.

## Claim

The single, atomic act by which an incoming SMS wins the right to be
processed: window check, dedup, session-txn-id capture, and parse under one
lock, returning a `ClaimedSms` — or nothing. Everything a caller needs to
know about the SMS comes back inside the claim; there is no second call
whose order matters.

## Payment gate

The single interface that answers "is this payment allowed to proceed?"
(`RECEIVE_SMS`, overlay, dial permissions). The guard at the money path —
`initiateTransfer`, the last stop before dialing — asks the gate and fails
closed. UI-level checks are affordances, not enforcement.

## Bank directory

The one module that maps a bank id to its user-facing name, plus the ordered
list of banks. Display vocabulary only: the SMS-matching keywords live in
`SmsTransactionParser` and are deliberately not shared (ADR-0002).

## Rail

Which offline UPI rail a payment runs on — `*99#` USSD or UPI 123Pay IVR.
A rail carries its own icon and accent; the in-call dialogs are shared and
parameterised by rail.

## Overlay presenter

The pure mapping from payment-session state to what the call overlay should
show: which dialog, which copy, what progress. It has no Android
dependencies; the service renders what it returns.
