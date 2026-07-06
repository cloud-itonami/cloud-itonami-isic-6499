# ADR-0001: cloud-itonami-isic-6499 -- DD-LLM as a contained intelligence node

- Status: Accepted (2026-07-06)
- Related: `cloud-itonami-isic-6511` ADR-0001 (Underwriter-LLM ⊣
  UnderwritingGovernor, the pattern this ADR ports), `cloud-itonami-M6910`
  ADR-0001 (Registrar-LLM ⊣ RegistrarGovernor), `cloud-itonami-L6810`
  ADR-0001 (Realtor-LLM ⊣ RealtorGovernor), langgraph-clj ADR-0001 (Pregel
  superstep + interrupt + Datomic checkpoint), superproject
  ADR-2607061700 (original classification decision + later addendum
  documenting the consolidation into this repo)

## Context and history

No `cloud-itonami-*` blueprint covered the full venture-capital fund
lifecycle (LP fundraising → DD → capital deployment → exit distribution).
UN ISIC Rev.4's own explanatory note for `6499` ("Other financial service
activities, except insurance and pension funding activities, n.e.c.") names
this activity explicitly: *"own-account investment activities, such as by
venture capital companies, investment clubs etc."*
(https://unstats.un.org/unsd/classifications/Econ/Detail/EN/27/6499).

This repository was originally published (2026-07-04) as a generic
`:blueprint`-tier n.e.c. scaffold -- README/blueprint.edn/docs only, no
running code, illustrated with a different member of the same n.e.c.
bucket (factoring / check-cashing / money-order issuance). When this VC
actor was first built (2026-07-06), the initial instinct was to avoid
touching this live repo and instead publish standalone as
`cloud-itonami-vc-fund`, reasoning that `6420`/`6430`/`6499`/`6630` were
all "occupied." On reconsideration (same day, at the owner's request to
keep the ISIC-numbered naming convention): this repo was a two-commit,
zero-fork, zero-star, code-free scaffold -- not meaningfully "occupied" --
and `6499`'s own citation names venture capital first among its examples.
So the standalone repo's full implementation was migrated in here (this
repo's content replaced, not merely appended), `cloud-itonami-vc-fund` was
deleted, and `kotoba-lang/industry`'s registry entry for `6499` was
promoted `:blueprint` → `:implemented` (superproject ADR-2607061700
addendum records this pivot).

## Problem

A venture fund's lifecycle needs several different kinds of judgment:

1. **LP eligibility** -- is the LP actually an accredited investor /
   qualified purchaser, on record, before capital is committed on their
   behalf?
2. **KYC/sanctions screening** -- does an LP or a portfolio-company party
   (founder, existing cap-table holder) match a sanctions/PEP list?
3. **Due-diligence correctness** -- is the DD checklist for a deal grounded
   in a named framework/jurisdiction's official fund-formation or
   exemption regime, or invented?
4. **Real actuation, two directions** -- actually committing fund capital
   into a portfolio company (irreversible: a term sheet is signed, wires
   go out), and actually distributing exit proceeds back to LPs
   (irreversible: money leaves the fund's account, the waterfall split is
   final).

An LLM has no authority or grounding for any of these. The design problem
is therefore not "run a VC fund with an LLM" but "seal the LLM inside a
trust boundary and layer LP-eligibility, KYC/sanctions, DD-authenticity,
audit and human-approval on top of it, while structurally fixing both
capital-in and capital-out as human-only."

## Decision

### 1. DD-LLM is sealed into the bottom node; it never commits or distributes directly

`vcfund.ddllm` returns exactly five kinds of proposal: LP-intake
normalization, KYC/sanctions screening, deal DD-checklist assessment,
investment-commitment proposal, and exit-distribution proposal. No
proposal writes the SSoT or moves real capital directly.

### 2. OperationActor = langgraph-clj StateGraph, 1 run = 1 fund operation

`vcfund.operation/build` is the same StateGraph shape as
`cloud-itonami-isic-6511` / `cloud-itonami-M6910` / `cloud-itonami-L6810`
(intake → advise → govern → decide → commit | hold | request-approval).
One graph run corresponds to one fund operation, with no unbounded inner
loop.

### 3. InvestmentCommitteeGovernor is a separate system from DD-LLM

`vcfund.governor` has six checks: spec-basis · sanctions-hit ·
DD-incomplete · accredited-investor-violation (HARD, un-overridable) +
confidence-floor · actuation-gate (SOFT, human decides).
`accredited-investor-violation` has no analog in the life-insurance
template -- it is this domain's genuine addition, reflecting that
committing LP capital without a recorded accreditation affirmation is a
real securities-law violation (US Reg D 506(b)/(c), Investment Company Act
§3(c)(1)/§3(c)(7)), the same class of hard legal fact spec-basis/sanctions
already are.

### 4. Real actuation is structurally always human-only, in BOTH capital directions

`vcfund.governor`'s actuation gate and `vcfund.phase`'s phase table both
prevent `:investment/commit` and `:exit/distribute` from ever
auto-committing. Unlike the insurance template (exactly one actuation
member -- "actuation is not a spectrum"), this fund actor's `high-stakes`
set has **two** members on purpose: a VC fund moves real money in two
independent directions (capital deployed to a portfolio company; proceeds
returned to LPs), and each is its own absolute, non-gradated actuation
event. Neither depends on the other being implemented correctly.

### 5. No fabricated global fund-identifier or IRR-based whole-fund waterfall standard

Same discipline as `cloud-itonami-L6810`'s `realty.registry` and
`cloud-itonami-isic-6511`'s `underwriting.registry`: there is no single
international identifier standard for a fund's investment-commitment
record, so `vcfund.registry` does not invent one -- it validates required
fields and assigns a fund-scoped sequence number only. Similarly,
`vcfund.registry/distribute-waterfall` computes a **deal-by-deal** (not
whole-fund) waterfall: return-of-capital → preferred return (simple, not
compounded IRR) → GP carry split on the remaining profit for **one
investment record**. A real whole-fund European waterfall needs
cross-deal netting and GP clawback state that spans every investment in
the fund, which is out of scope for a single investment record -- this is
documented as a scoping limitation in the function's docstring, not
silently narrowed.

### 6. Relationship to the adjacent ISIC blueprints

`cloud-itonami-isic-6430` (trust/fund vehicle administration) and
`cloud-itonami-isic-6630` (fee-based fund management) already model
adjacent pieces of a real fund's legal structure -- the passive fund
vehicle and the fee-earning management service, respectively. This repo
does not require or wrap either; it is a self-contained governed
implementation of the third piece (own-account, carry-generating
investment decision-making that `6499` itself names), the same
"self-contained sibling, not a shared-code dependency" relationship
`underwriting.*` has to `kotoba-lang/insurance`.

## Consequences

- (+) Venture-fund capital deployment gets the same governed, auditable-
  actor treatment as life insurance (`cloud-itonami-isic-6511`), company
  incorporation (`cloud-itonami-M6910`) and real estate
  (`cloud-itonami-L6810`), without centralizing liability in one vendor --
  any emerging-manager GP can fork and run their own instance.
- (+) The dual-actuation invariant (governor + phase, two layers, two
  members) is regression-tested by `test/vcfund/phase_test.clj`.
- (+) `MemStore` ‖ `DatomicStore` parity is proven by
  `test/vcfund/store_contract_test.clj`, the same `:db-api`-driven swap
  pattern the rest of the fleet uses.
- (+) Consolidating into this existing ISIC-numbered repo (rather than a
  standalone bespoke name) keeps every `cloud-itonami-*` entry on the same
  ISIC-first naming convention, and lets `kotoba-lang/industry`'s registry
  correctly report `6499` at `:implemented` maturity via its normal
  maturity-roadmap (`:spec` → `:blueprint` → `:implemented`) instead of
  living outside the registry entirely.
- (-) This R0 seeds only 4 jurisdictions (JPN, USA, GBR, DEU) with an
  official fund-formation/exemption spec-basis, out of ~194 worldwide;
  `vcfund.facts/coverage` reports this honestly.
- (-) Real accredited-investor verification, real cap-table/fund-
  accounting-system integration, and real KYC/sanctions-screening provider
  integration are out of scope for this OSS actor -- each operator's
  responsibility.
- (-) The waterfall is deal-by-deal, not a whole-fund European waterfall
  with GP clawback -- a real fund still needs a proper fund-accounting
  system alongside this actor.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Keep the actor standalone as `cloud-itonami-vc-fund` | ❌ | Breaks the ISIC-numbered naming convention every other entry follows, and 6499 already cites this exact business -- no reason to sit outside the registry it belongs in |
| Publish at `:blueprint` maturity only (markdown, no running code) | ❌ | Owner explicitly asked for the full `:implemented` governed-actor tier |
| One `high-stakes` member, matching the insurance template exactly | ❌ | A VC fund genuinely has two independent real-money-movement directions (capital in, proceeds out); collapsing them to one would hide that a clean exit distribution needs its own independent human sign-off, not a re-use of the commitment approval |
| Require a whole-fund European waterfall with cross-deal netting/clawback for R0 | ❌ | Needs fund-level state beyond a single investment record; scoped out honestly rather than half-implemented |
| Wrap/require `kotoba-lang/insurance`-style shared capability lib | ❌ | Neither `formation.*`, `realty.*` nor `underwriting.*` require a sibling capability lib; keeping the actor self-contained matches the established pattern |
