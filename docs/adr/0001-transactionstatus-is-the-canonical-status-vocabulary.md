# ADR-0001: TransactionStatus is the canonical status vocabulary

- Status: Accepted
- Date: 2026-10-03

## Context

One payment lifecycle was named three ways (`PaymentState`,
`TransactionStatus`, `PaymentStatus`), bridged at six render/decision sites.
Five of the six bridges were untested, two carried phantom words
("SUCCESSFUL", "COMPLETED") that no producer in the codebase ever wrote, and
every new renderer had to re-derive the words from scratch. The status words
were the hottest spot in the commit history.

## Decision

The six `TransactionStatus` words are the canonical vocabulary for payment
outcomes. The `PaymentStatus` enum is deleted. One module — the
`PaymentStatus` object in `ui/status/` — owns the mapping from session state
and DB strings to the presentation facts (label, color, notification title).
Renderers consume that module; no screen re-derives status words.

The session machine's ephemeral states are not status words; `PaymentState`
remains the session machine's own vocabulary.

## Consequences

- Adding or rewording a status is one enum value + one mapping + strings.
- The mapping has one test surface; the five untested `when` tables are gone.
- Future architecture reviews should not propose renderer-local status
  mapping, a second status enum, or resurrecting `COMPLETED`-style synonyms.
