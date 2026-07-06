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

## Addendum (2026-07-06, same day): capital calls -- the biggest R0 coverage gap, closed

An honest coverage review of the R0 actor (four gates: LP intake, DD,
investment commit, exit distribute) found its most consequential gap: LPs'
committed capital was modeled as accepted in full at subscription, with no
mechanism for the fund to draw it down incrementally as deals actually
need funding -- the real-world capital-call mechanism every committed-
capital fund vehicle runs on. Closed by adding a fifth governed op:

- **`:capital-call/issue`** -- `vcfund.registry/capital-call-allocations`
  computes a pure pro-rata split of a requested call amount across all LPs
  by commitment share (never mutating any LP record); each LP carries a
  new `:called-amount` field advanced only on a governor-cleared,
  human-approved call. `vcfund.registry/register-capital-call` drafts the
  notice record (fund-scoped `{JURISDICTION}-CALL-{seq}` number,
  `default-notice-period-days` = 10, cited as a market-default assumption,
  not a specific LPA's actual clause).
- **New HARD check, `overcall-violations`** (`vcfund.governor`) --
  recomputes the allocation independently from store data (same discipline
  as `dd-incomplete-violations`: never trust the advisor's self-reported
  numbers) and HOLDS, un-overridably, if any LP's cumulative called amount
  would exceed their commitment.
- **`accredited-investor-violations` extended** to also gate
  `:capital-call/issue`, not just `:investment/commit` -- the fund's
  securities exemption is fund-wide and applies the moment LP capital is
  drawn in, not only when it is deployed out.
- **Third `high-stakes` member, `:actuation/call`** -- a VC fund moves real
  capital in three directions, not two; calling capital in is its own
  absolute actuation event, gated identically (governor + phase, both
  layers) to deployment and distribution.

Consequences: `test/vcfund/*` grew from 33 tests/153 assertions to 45
tests/219 assertions, still lint-clean. Remaining honest gaps (unchanged by
this addendum, tracked in README's "Business-process coverage" table):
deal pipeline/sourcing-funnel management, term-sheet/cap-table/valuation
math, portfolio-company monitoring between commit and exit, whole-fund
NAV/European waterfall, tax/regulatory reporting.

## Addendum 2 (2026-07-06, same day): pipeline, portfolio monitoring, cap-table math

A second honest coverage pass closed three more of the gaps the first
addendum left open:

- **`vcfund.pipeline`** (new, pure) -- a deal sourcing funnel (`:sourced
  :screening :pitched :term-sheet :dd :ic-review`), forward-only
  transitions (`valid-transition?`), with `:passed` reachable from any
  pre-commitment stage and `:committed`/`:exited` reachable ONLY via the
  real capital-moving ops, never via the new `:deal/advance-stage` op
  directly -- this keeps pipeline stage and capital status from ever
  diverging.
- **New HARD check, `stage-transition-violations`** -- for
  `:deal/advance-stage`, re-validates the proposed move independently
  against `vcfund.pipeline/valid-transition?`.
- **New HARD check, `stage-insufficient-violations`** -- for
  `:investment/commit`, requires the deal to have actually reached
  `:ic-review` in the funnel, not merely that DD checklist data happens to
  be on file (`dd-incomplete-violations` and this check are independent:
  DD-complete-but-never-reviewed and reviewed-but-DD-incomplete are both
  correctly caught).
- **`:portfolio/report`** (new op) -- board-report-style KPI logging for an
  already-committed deal (`vcfund.registry/register-portfolio-report`,
  append-only per-deal history via `store/portfolio-reports-of`). New HARD
  check `portfolio-report-requires-commitment-violations` blocks reporting
  on a deal the fund never actually invested in.
- **`:deal/advance-stage` and `:portfolio/report` are deliberately NOT
  `high-stakes`** -- neither moves capital, so both are phase-3
  auto-eligible (added to `vcfund.phase`'s `:auto` set alongside
  `:lp/intake`), a genuinely lighter governance posture matching their
  actual (low) risk rather than applying maximum friction uniformly
  everywhere. `:capital-call/issue`/`:investment/commit`/`:exit/distribute`
  remain the only ops that always escalate.
- **`vcfund.captable`** (new, pure) -- SAFE-conversion math (converts at
  whichever of valuation-cap/discount is more favorable to the SAFE
  holder) and priced-round ownership/dilution math, in PERCENTAGE-OF-
  COMPANY terms only. Deliberately a standalone calculator, not a governed
  op or a field wired into deal records: setting/negotiating a valuation
  is advisory, not a capital-movement event, so it doesn't need
  `vcfund.governor` gating the way the six governed ops do. Honestly does
  NOT model absolute share counts, option-pool sizing, or multi-SAFE
  proration -- see its docstring.

Consequences: `test/vcfund/*` grew from 45 tests/219 assertions to 60
tests/278 assertions (new `pipeline_test.clj`/`captable_test.clj` plus
governor/store/phase additions), still lint-clean. Remaining honest gaps
(tracked in README's "Business-process coverage" table): whole-fund NAV,
term-sheet negotiation workflow, absolute cap-table/option-pool modeling,
a dedicated follow-on-investment op (currently: source a new deal record
for the follow-on round and run it through the same lifecycle), board-seat/
governance-rights administration, whole-fund European waterfall, tax/
regulatory reporting.

## Addendum 3 (2026-07-06, same day): whole-fund NAV, absolute cap-table shares, term-sheet negotiation

A third honest coverage pass closed the three gaps the owner picked next:

- **`vcfund.nav`** (new, pure + a store-aware adapter) -- `unfunded-
  commitments` (per-LP and fund-wide: commitment - called) and `fund-nav`
  (net cash + fair value of still-held investments; net cash = total
  called + total exit-proceeds-received - total-invested-at-cost -
  total-distributed-to-lps). A held investment defaults to cost basis
  until an operator records a `:fair-value-mark` KPI via `:portfolio/
  report` -- never silently mark up an unvalued investment. `fund-nav-
  report` reads the live store (LPs, commitment/distribution history,
  latest fair-value mark per deal) and calls the pure functions -- kept
  separate so the NAV math itself has zero I/O dependency. Read-only
  reporting, not a governed op: computing NAV moves no capital. Honestly
  does NOT net fund expenses/management fees into cash, and assumes one
  fund base currency (no FX) -- see its docstring. Verified against a full
  store-driven lifecycle: NAV = cost basis while held and unmarked, tracks
  a `:fair-value-mark` once recorded, and after exit correctly reduces to
  exactly the GP carry still resident in fund cash pending a GP-carry
  sweep (no dedicated "pay GP carry" op exists, so that cash is real, not
  a rounding artifact).
- **`vcfund.captable` extended** -- `option-pool-shuffle` (the standard
  pre-money-pool term-sheet mechanic: a new/refreshed pool is carved out
  of EXISTING holders' ownership, not the new investor's -- the single
  most common source of dilution founders under-account for),
  `priced-round-shares` (absolute share-count math: price-per-share =
  pre-money-valuation / pre-round-shares, composes with
  `option-pool-shuffle`'s output), and `cap-table-ownership`
  (fully-diluted ownership % from a plain `{:holder :shares}` cap table).
  Still no vesting, no option strike-price/exercise modeling, no
  multi-SAFE simultaneous-conversion proration.
- **`:term-sheet/propose`** (new op) -- versioned per-deal negotiation
  history (`vcfund.registry/register-term-sheet`, append-only,
  `:proposed-by` `:fund`/`:founder`, arbitrary `:terms` map). New HARD
  checks: `term-sheet-after-commitment-violations` (can't propose new
  terms once the deal is `:committed`/`:exited` -- negotiation is
  pre-commitment only) and **`term-sheet-missing-violations`** (NEW
  precondition on `:investment/commit`: capital never moves on a
  handshake with no term sheet ever proposed for the deal, independent of
  and in addition to `dd-incomplete-violations`/`stage-insufficient-
  violations`). `:term-sheet/propose` is NOT `high-stakes` (no capital
  moves) -- auto-eligible at phase 3, same posture as `:deal/advance-
  stage`/`:portfolio/report`.
- **Every existing test/demo path that reaches `:investment/commit`
  needed a `:term-sheet/propose` step added** -- the same "close a real
  gap, then go fix every call site" discipline the `:ic-review` stage
  requirement already established in Addendum 2. `commit-without-term-
  sheet-is-held` is the new dedicated regression test proving the
  precondition holds in isolation.

Consequences: `test/vcfund/*` grew from 60 tests/278 assertions to 81
tests/347 assertions (new `nav_test.clj` plus governor/registry/store/
phase additions), still lint-clean; demo (`clojure -M:dev:run`) walks the
full lifecycle including the new term-sheet round and its own HARD-hold
case (proposing terms after commitment). Remaining honest gaps (tracked in
README's "Business-process coverage" table): term-sheet
redlining/e-signature workflow (versions are recorded, no diff/counter-
offer state machine), vesting/option-strike/multi-SAFE proration, a
dedicated follow-on-investment op, board-seat/governance-rights
administration, fund expense/management-fee accrual in NAV, multi-currency
FX, whole-fund European waterfall, tax/regulatory reporting.

## Addendum 4 (2026-07-06, same day, autonomous /loop iteration): management fees, whole-fund waterfall/GP-clawback

A recurring `/loop` (`coverage, 成熟度を向上` every 30 min) picked the next
two gaps autonomously, since the owner's last explicit direction ("1, 2,
3") had already been fully delivered in Addendum 3:

- **`vcfund.nav/management-fee-accrued`** (new, pure) -- a flat,
  non-compounded annual rate on a caller-supplied fee basis (typically
  total LP commitments), netted into `fund-nav`'s cash balance via a new
  OPTIONAL `:management-fees-accrued` key (default 0, fully backward
  compatible with every existing call site and test). `fund-nav-report`
  gained an optional second arg, `{:fund-life-years .. :annual-fee-rate
  ..}` (both real external facts, never inferred; `fund-life-years`
  defaults to 0 -> 0 fees when omitted), computing the fee basis from
  live LP commitments. Honestly still a flat rate with no step-down after
  the investment period (a real LPA's fee often steps down, e.g. 2% ->
  1.5%, around year 5) -- see the ns docstring.
- **`vcfund.waterfall`** (new, pure + a store-aware adapter) -- closes the
  gap flagged in `vcfund.registry/distribute-waterfall`'s own docstring
  since R0: a real whole-fund EUROPEAN waterfall needs fund-level state
  spanning every investment. `whole-fund-waterfall` aggregates ALL
  contributed capital and ALL realized exit proceeds fund-wide, applies
  ONE preferred return and ONE carry split (same simple, non-compounded
  discipline as the deal-by-deal calc), and compares the resulting
  whole-fund GP entitlement against `total-gp-carry-already-paid` (the sum
  of every deal-by-deal `:total-to-gp`). The shortfall, if any, is a **GP
  clawback** -- money the GP was already paid (from an early home-run
  exit's deal-by-deal carry) that the fund's AGGREGATE performance (once
  later, losing deals are counted) never actually entitled them to. Read-
  only reconciliation, not a governed op -- like `vcfund.nav`, this moves
  no capital; an actual clawback repayment would be its own governed act,
  out of scope here. Verified against a two-deal store-integration
  scenario (one big win, one loss) producing a real, non-trivial clawback
  number (not a contrived round figure), matching a hand-computed
  cross-check.

Consequences: `test/vcfund/*` grew from 81 tests/347 assertions to 92
tests/373 assertions (new `waterfall_test.clj` plus `nav_test.clj`
additions), still lint-clean. Remaining honest gaps (tracked in README's
"Business-process coverage" table): term-sheet redlining/e-signature
workflow, vesting/option-strike/multi-SAFE proration, a dedicated
follow-on-investment op, board-seat/governance-rights administration,
management-fee step-downs, multi-currency FX, an actual GP-clawback
repayment op, tax/regulatory reporting.
