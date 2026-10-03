# ADR-0002: SmsTransactionParser keeps its own SMS-matching keywords

- Status: Accepted
- Date: 2026-10-03

## Context

The bank vocabulary existed in three disagreeing shapes: the setup screen's
bank list (10 banks), the main screen's bank-name `when` (with a hardcoded
default), and the parser's SMS keywords (14 banks). The obvious-looking fix —
one shared bank vocabulary — would route the parser's *matching* vocabulary
through a module designed for *display* names.

## Decision

The bank directory owns display names only: bank id to user-facing name,
plus the ordered list. `SmsTransactionParser` keeps its own SMS-matching
keywords, deliberately not shared with the directory.

## Consequences

- Adding a bank is two deliberate acts: a directory entry (display) and
  parser keywords (matching) + corpus tests. That is acknowledged, not
  accidental: the two vocabularies answer different questions.
- The parser's 30-test corpus and its permissive-by-design matching stay
  untouched by display changes.
- Future reviews should not re-propose unifying matching and display
  vocabulary.
