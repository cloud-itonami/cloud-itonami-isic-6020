# cloud-itonami-6020

Open Business Blueprint for **ISIC Rev.5 6020**: television programming
and broadcasting activities (a licensed television broadcast station
operator).

This repository designs a forkable OSS business for community
television broadcasting: broadcast-license scope management,
robotics-assisted transmission-equipment inspection and maintenance,
and program-schedule/advertising-billing records — run by a qualified
operator so a broadcaster keeps its own licensing and programming
history instead of renting a closed broadcast-operations platform.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here robots (transmitter-tower
inspection, studio/transmission-equipment maintenance) operate under
an actor that proposes actions and an independent **Broadcast License
Governor** that gates them. The governor never transmits content
itself; `:high`/`:safety-critical` actions (any program transmission
outside the station's own verified license scope, any content that
would violate a public-interest programming requirement) require
human sign-off.

## Core Contract

```text
intake + identity + broadcast-license scope + program schedule
        |
        v
Broadcast Operations Advisor -> Broadcast License Governor -> license record, transmission dispatch, billing record, or human approval
        |
        v
robot actions (gated) + program/maintenance record + billing record + audit ledger
```

No automated advice can dispatch a transmission the governor refuses,
approve programming outside its verified license scope, or publish a
billing record without governor approval and audit evidence.

## Capability layer

Resolves via [`kotoba-lang/industry`](https://github.com/kotoba-lang/industry)
(ISIC `6020`). Implemented by:

- [`kotoba-lang/robotics`](https://github.com/kotoba-lang/robotics) — missions, actions, safety-stops, telemetry proofs
- [`kotoba-lang/phone`](https://github.com/kotoba-lang/phone) — shared telephony/audience-contact-records capability (call-in shows, viewer contact lines)

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## Implementation

An `:implemented`-tier `tvbroadcastops.*` operations-coordination actor
(langgraph-clj StateGraph, `MemStore` SSoT, closed proposal-op
allowlist, independent Broadcast License Governor) lives in `src/` —
the same `advisor -> governor -> phase -> operation` seam every
`cloud-itonami-isic-*` actor in this fleet uses.

- **Closed proposal-op allowlist**: `:log-broadcast-record`,
  `:schedule-broadcast-operation`, `:flag-content-concern`,
  `:coordinate-equipment-maintenance` (all `:effect :propose`).
- **Two HARD governor checks** (permanent, un-overridable):
  1. **Station verified** — target station/license record must exist
     AND be registered/verified in the store.
  2. **Effect is `:propose`** — any other `:effect` value is rejected.
  3. **Scope exclusion** — this actor NEVER finalizes an on-air-content
     decision and NEVER finalizes an emergency-alert-broadcast
     decision. Any proposal whose content attempts to finalize either
     is permanently blocked. An op outside the closed four-op
     allowlist is folded into the same check.
- **Staged rollout** (Phase 0→3):
  - Phase 0: read-only
  - Phase 1: broadcast-record logging only (approval-gated)
  - Phase 2: + programming-block scheduling, equipment-maintenance
    coordination (approval-gated)
  - Phase 3: auto-commits clean, high-confidence proposals
    (`:flag-content-concern` always escalates, at every phase)
- **Append-only audit ledger** — every decision is an immutable log
  entry.
- **langgraph-clj StateGraph** — one request = one supervised run;
  human-in-the-loop via `interrupt-before`.

### CRITICAL scope exclusions

This actor coordinates the *back office* around broadcast decisions —
it never makes the decisions themselves. It structurally cannot:

- **Finalize an on-air-content decision** — what actually airs, when,
  in what form. That is always a hard permanent block.
- **Finalize an emergency-alert-broadcast decision** — whether/when to
  trigger an Emergency Alert System transmission. Same treatment, and
  a concern about either is always an always-escalate op
  (`:flag-content-concern`) requiring human sign-off, never
  auto-committed.

The governor's `scope-excluded-terms` are deliberately phrased as the
*finalization/execution action* ("finalize the on-air content
decision", "authorize the emergency alert broadcast"), never as a bare
noun ("content", "broadcast", "emergency alert", "on-air"), because
this actor's own legitimate happy-path proposals — especially
`:flag-content-concern`, whose entire purpose is to talk *about*
content/emergency-alert concerns — routinely use those bare nouns.
`governor-test` and `governor-contract-test` both assert the default
mock-advisor proposals never self-trip this check.

### Development

```bash
# Install dependencies (if inside the superproject, use :dev alias for local overrides)
clojure -M:dev -P

# Run tests
clojure -M:test

# Run linter
clojure -M:lint

# Run demo
clojure -M:run
```

### Test suite

- `test/tvbroadcastops/governor_test.clj` — unit tests of governor hard
  checks, scope exclusion, and the self-trip regression test
- `test/tvbroadcastops/advisor_test.clj` — advisor proposal shape and
  consistency
- `test/tvbroadcastops/phase_test.clj` — rollout phase logic
- `test/tvbroadcastops/governor_contract_test.clj` — full graph
  integration, audit trail
- `test/tvbroadcastops/store_contract_test.clj` — Store protocol and
  MemStore implementation

### Modules

- `tvbroadcastops.store` — SSoT (MemStore, String-keyed station
  directory, append-only ledger)
- `tvbroadcastops.advisor` — contained intelligence node (mock +
  real-LLM seam)
- `tvbroadcastops.governor` — independent compliance layer
  (BroadcastLicenseGovernor)
- `tvbroadcastops.phase` — staged rollout (0→3)
- `tvbroadcastops.operation` — langgraph-clj StateGraph
- `tvbroadcastops.sim` — demo driver

## License

AGPL-3.0-or-later.

## Governance

This actor is part of the cloud-itonami Wave 4 (human-services) fleet.
See ADR-2607121000, ADR-2607152500, and the per-actor coverage ADR in
`com-junkawasaki/root` `90-docs/adr/` for design decisions.
