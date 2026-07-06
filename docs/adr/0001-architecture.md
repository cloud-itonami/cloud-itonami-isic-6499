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

## Addendum 5 (2026-07-06, same day, owner-directed): term-sheet e-signature/redlining, cap-table vesting/option/multi-SAFE math, crypto-native SAFT support

The owner picked the first two remaining items from Addendum 4's list
("1, 2") plus a cross-cutting instruction: the fund itself should be
able to operate in crypto.

- **Term-sheet redlining** -- `vcfund.registry/term-sheet-diff` (new,
  pure): field-level added/removed/changed classification between two
  `terms` maps. `vcfund.ddllm/propose-term-sheet` now includes the
  redline against the immediately prior round in its `:rationale` when
  one exists, so a human reviewing a counter-proposal sees exactly what
  changed, not just the new terms in isolation.
- **Term-sheet e-signature** -- new op `:term-sheet/sign` (subject =
  deal-id, `:signed-by` `:fund`/`:founder`), append-only per-deal
  signature history (`vcfund.store/signature-history-of`,
  `vcfund.registry/register-term-sheet-signature`, parallel to the
  term-sheet history itself -- signing never mutates the term-sheet
  record it signs). `vcfund.registry/fully-executed?` checks whether BOTH
  sides signed a SPECIFIC version (signatures on different versions never
  count as executing either one). **New HARD check,
  `term-sheet-not-executed-violations`** -- `:investment/commit` now
  requires the LATEST term-sheet version to be fully executed, not merely
  proposed (`term-sheet-missing-violations` still separately catches the
  zero-term-sheet case). `:term-sheet/sign` is NOT `high-stakes` (no
  capital moves) -- auto-eligible at phase 3. Every existing test/demo
  path reaching `:investment/commit` needed both-sides signature steps
  added -- the same "close a real gap, then fix every call site"
  discipline as the `:ic-review`/term-sheet-missing requirements in
  Addenda 2/3.
- **Cap-table math extended** (`vcfund.captable`) -- `vesting-schedule`
  (linear-with-cliff, the standard 4-year/1-year-cliff shape),
  `option-exercise-economics` (intrinsic value, floors at 0 for
  underwater options), `multi-safe-conversion-shares` (multiple SAFEs
  converting into the SAME priced round, each at its OWN most-favorable
  cap-vs-discount price against a shared `pre-conversion-shares`
  baseline -- verified against a two-SAFE scenario with a hand
  cross-check).
- **Crypto-native operation**: `:saft` (Simple Agreement for Future
  Tokens) added to `register-commitment`'s valid `:security-type` set;
  `vcfund.captable/saft-conversion` is a thin re-labeling wrapper around
  `safe-conversion` (same cap-vs-discount mechanic, denominated in token
  supply instead of equity -- `:token-allocation-pct` in place of
  `:ownership-pct`, not a duplicated implementation). LPs gained an
  optional `:wallet-address` field (`vcfund.store`, both backends) for
  on-chain capital-call/distribution settlement; the demo fixture's
  `lp-1` carries one. No new governed op or HARD check was needed for
  wallet-based sanctions screening: `:kyc/screen` and the party `:id-doc`
  field were already generic identifier fields, so a wallet address
  screens through the exact same mechanism a passport number does.
  Currency fields were already free-form strings, so a stablecoin base
  currency (e.g. `"USDC"`) needs no code change either -- only
  cross-currency FX conversion remains unmodeled (documented, unchanged
  limitation of `vcfund.nav`/`vcfund.waterfall`).

Consequences: `test/vcfund/*` grew from 92 tests/373 assertions to 112
tests/439 assertions (new signature/diff/vesting/option/multi-SAFE/SAFT
tests across `governor_contract_test.clj`, `registry_test.clj`,
`captable_test.clj` and `store_contract_test.clj`), still lint-clean;
demo (`clojure -M:dev:run`) walks the full lifecycle including both-sides
term-sheet signing and a multi-violation HARD hold (a deal with DD
incomplete, stage insufficient, AND an unsigned term sheet all at once,
printed together -- the governor reports every violation, not just the
first). Remaining honest gaps (tracked in README's "Business-process
coverage" table): a real e-signature PROVIDER integration and
redlining UI, order-dependent multi-SAFE simultaneous-conversion solving,
a dedicated follow-on-investment op, board-seat/governance-rights
administration, management-fee step-downs, vesting acceleration/option
tax treatment, multi-currency FX, an actual GP-clawback repayment op,
tax/regulatory reporting.

## Addendum 6 (2026-07-06, same day, autonomous /loop iteration): dedicated follow-on-investment op, real GP-clawback-repayment op

No new explicit owner direction this iteration -- the recurring `/loop`
prompt asked for coverage/maturity improvement, so this autonomously
picked the top two items off Addendum 5's remaining-gaps list: "a
dedicated follow-on-investment op" and "an actual GP-clawback repayment
op" (the read-only `vcfund.waterfall` reconciliation from Addendum 4
finally gets a real, governed capital-movement act on top of it).

- **Follow-on investment** -- new op `:investment/follow-on` (subject =
  deal-id, `:security-type`/`:amount` real facts about the new round,
  supplied by the caller, never invented). `vcfund.registry/register-
  follow-on-commitment` drafts a record whose `original_commitment_number`
  references the deal's FIRST commitment (`USA-FOLLOWON-00000003` style
  ids, distinct from the `USA-00000007` initial-commitment numbering), so
  the audit trail links every tranche back to the deal's original
  investment rather than reading as an unrelated new commitment.
  Append-only per-deal history (`vcfund.store/follow-on-history-of`,
  both backends) parallel to -- not merged into -- `commitment-history`;
  a follow-on does NOT change the deal's lifecycle `:status` (stays
  `:committed`), only its commitment history grows. **New HARD check,
  `follow-on-requires-prior-commitment-violations`** -- the deal must
  actually be `:committed` (an initial tranche already on file, and not
  already `:exited`); a follow-on is never a substitute for the first
  `:investment/commit`. `accredited-investor-violations`'s op set grew to
  include `:investment/follow-on` (fund-wide accreditation gates
  deployment regardless of which op deploys it). `:stake` is `:actuation/
  deploy`, deliberately REUSED rather than a new high-stakes member --
  same direction of capital travel as an initial commitment.
- **GP-clawback repayment** -- new op `:waterfall/clawback-repay`
  (fund-level, not deal-scoped -- `:subject` is the sentinel `"fund"`;
  `:amount`/`:effective-date`/`:fund-life-years` real facts supplied by
  the caller). `vcfund.registry/register-clawback-repayment` drafts a
  `CLAWBACK-000004`-style record. Append-only, fund-level history
  (`vcfund.store/clawback-repayment-history`, both backends -- NOT
  per-deal, since a clawback reconciles the WHOLE fund's carry history).
  **New HARD check, `clawback-exceeds-entitlement-violations`** --
  recomputes `vcfund.waterfall/whole-fund-waterfall-report`
  INDEPENDENTLY from live store data and rejects any requested repayment
  above the actually-computed `:gp-clawback`, never trusting the
  proposal's self-reported figure (same "never trust the advisor's
  self-check" discipline as `overcall-violations` recomputing capital-call
  allocations independently). `high-stakes` grew a FOURTH member,
  `:actuation/clawback` -- the one direction of capital travel that flows
  FROM the GP INTO the fund, the mirror image of every other actuation
  here (`:actuation/call`/`:actuation/deploy`/`:actuation/distribute` all
  move capital between the fund and LPs/portfolio companies; this one
  moves it between the fund and the GP).
- Governor docstring/check-count renumbered: 12 HARD + 1 soft -> 14 HARD +
  1 soft (fifteen total; the pre-existing `commitment-missing-violations`
  check for `:exit/distribute` had drifted out of the enumerated docstring
  list in an earlier addendum and is now correctly listed alongside the
  two new checks).
- `vcfund.phase/write-ops` grew to include both new ops; NEITHER was added
  to any phase's `:auto` set (the same "write-ops membership is not
  auto-eligibility" invariant every other capital-movement op follows).
  `vcfund.ddllm` gained `propose-follow-on`/`propose-clawback-repayment`,
  wired into `infer`/`facts-for`/the system-prompt string.

Consequences: `test/vcfund/*` grew from 112 tests/439 assertions to 126
tests/521 assertions (new `register-follow-on-commitment`/`register-
clawback-repayment` validation tests in `registry_test.clj`; follow-on/
clawback-repay parity tests across both `Store` backends in
`store_contract_test.clj`; `follow-on-requires-prior-commitment-is-held`,
`follow-on-blocked-by-an-unaccredited-lp-fund-wide`,
`investment-follow-on-always-escalates-then-human-decides`,
`clawback-repay-exceeding-entitlement-is-held` and `waterfall-clawback-
repay-always-escalates-then-human-decides` in
`governor_contract_test.clj`; never-auto-at-any-phase structural tests in
`phase_test.clj`), still lint-clean; demo (`clojure -M:dev:run`) now walks
a follow-on deployment and a whole-fund clawback reconciliation +
repayment through the full escalate-then-approve path, plus two new HARD
holds (a follow-on on a never-committed deal, a clawback request far
exceeding the recomputed entitlement) that never reach a human. Remaining
honest gaps (tracked in README's "Business-process coverage" table): a
real e-signature PROVIDER integration and redlining UI, order-dependent
multi-SAFE simultaneous-conversion solving, board-seat/governance-rights
administration, management-fee step-downs, vesting acceleration/option
tax treatment, multi-currency FX, tax/regulatory reporting.

## Addendum 7 (2026-07-06, same day, autonomous /loop iteration): governed board-seat/governance-rights op, management-fee step-down, change-of-control vesting acceleration

Again no new explicit owner direction -- the recurring `/loop` prompt
asked for coverage/maturity improvement, so this autonomously picked
three items off Addendum 6's remaining-gaps list: "board-seat/governance-
rights administration," "management-fee step-downs" and "vesting
acceleration." Real e-signature-provider integration, order-dependent
multi-SAFE solving, option tax treatment, multi-currency FX and tax/
regulatory reporting remain out of scope -- either a genuine third-party-
integration boundary (like the operator-supplied KYC/AML program) or a
larger lift than one iteration's scope.

- **Board-seat/governance-rights administration** -- new op
  `:governance/board-seat` (subject = deal-id, `:seat-holder`/
  `:seat-type`/`:event`/`:effective-date` real facts supplied by the
  caller). `vcfund.registry/register-board-seat-event` drafts a
  `deal-1#board-seat-0000`-style append-only event (`:granted` or
  `:revoked`; NO certificate, the same posture `register-portfolio-
  report` takes -- the actual governance right is negotiated and granted
  by the term sheet, this is the internal administrative record of who
  currently holds it). `vcfund.registry/current-board-seats` is a pure
  projection folding the event log into the CURRENT roster (latest event
  per `seat_holder` wins; a holder whose latest event is `:revoked` drops
  out entirely). Append-only per-deal history
  (`vcfund.store/board-seat-history-of`, both backends). **New HARD
  check, `board-seat-requires-commitment-violations`** -- the deal must
  actually be `:committed`/`:exited`, the same check
  `portfolio-report-requires-commitment-violations` runs for KPI reports.
  Unlike every op added in Addenda 1-6, this one is NOT actuation (no
  capital moves) -- `:stake` is nil, and it joins `:deal/advance-stage`/
  `:term-sheet/propose`/`:term-sheet/sign`/`:portfolio/report` as
  auto-eligible at phase 3, the deliberately lighter-touch tier for
  non-capital-risk ops.
- **Management-fee step-down** (`vcfund.nav/management-fee-accrued`) --
  OPTIONAL `:investment-period-years`/`:post-investment-period-rate`
  keys model the common LPA shape (e.g. 2% during the investment period,
  a lower rate after); omitting both keeps the exact flat-rate behavior
  every earlier caller relies on, so this is purely additive/backward
  compatible, not a breaking change to the existing signature.
  `fund-nav-report` passes the new keys through unchanged when supplied.
  Still honestly simplified to ONE step (a multi-step-down schedule, or a
  re-based fee basis after the step, is not modeled).
- **Change-of-control vesting acceleration** (`vcfund.captable/
  accelerated-vesting`, new fn) -- layers single-trigger (change-of-
  control alone) or double-trigger (change-of-control AND involuntary
  termination) full acceleration on top of `vesting-schedule`, reusing
  that fn's own validation rather than duplicating it (`dissoc`s the
  acceleration-specific keys before delegating). Returns the ordinary
  `vesting-schedule` map plus `:accelerated?`. Only ONE acceleration
  event is modeled (100% or nothing) -- a partial-acceleration provision
  (e.g. "12 months of additional vesting") is not.
- `vcfund.phase/write-ops` grew to include `:governance/board-seat`; it
  is ALSO the sixth member of phase 3's `:auto` set (unlike the four
  actuation ops, which are permanently excluded from every phase's
  `:auto` set -- see the standing invariant comment in `phase.cljc`).
  `vcfund.ddllm` gained `propose-board-seat`, wired into `infer`/
  `facts-for`/the system-prompt string. Governor docstring/check-count
  renumbered: 14 HARD + 1 soft -> 15 HARD + 1 soft (sixteen total).

Consequences: `test/vcfund/*` grew from 126 tests/521 assertions to 142
tests/571 assertions (new `register-board-seat-event`/`current-board-
seats` tests in `registry_test.clj`; `accelerated-vesting` tests in
`captable_test.clj`; management-fee step-down tests in `nav_test.clj`
(pure fn AND `fund-nav-report` integration); board-seat parity tests
across both `Store` backends in `store_contract_test.clj`;
`board-seat-without-commitment-is-held`/`board-seat-auto-commits-once-
deal-is-committed` in `governor_contract_test.clj`; auto-eligibility
structural tests in `phase_test.clj`), still lint-clean; demo (`clojure
-M:dev:run`) now walks a board-seat grant through the no-approval
auto-commit path (alongside the existing follow-on/clawback flows) plus a
new HARD hold (a board-seat grant on a never-committed deal). Remaining
honest gaps (tracked in README's "Business-process coverage" table): a
real e-signature PROVIDER integration and redlining UI, order-dependent
multi-SAFE simultaneous-conversion solving, option tax treatment (ISO/
NSO/AMT, 83(b)), multi-currency FX, tax/regulatory reporting (K-1s, Form
D/ADV).

## Addendum 8 (2026-07-06, same day, autonomous /loop iteration): post-money multi-SAFE closed-form solve, LP-level multi-currency FX

No new explicit owner direction again -- the recurring `/loop` prompt
asked for coverage/maturity improvement, so this autonomously picked two
items off Addendum 7's remaining-gaps list: "order-dependent multi-SAFE
simultaneous-conversion solving" and "multi-currency FX." Real
e-signature-provider integration, option tax treatment (ISO/NSO/AMT) and
tax/regulatory reporting remain out of scope this iteration -- the first
is a genuine third-party-integration boundary, the latter two are larger,
riskier lifts (real tax-bracket/AMT computation, real regulatory-filing
content) better left as their own dedicated pieces of work than folded
into a fast autonomous iteration.

- **Post-money multi-SAFE closed-form solve** -- new fn
  `vcfund.captable/post-money-multi-safe-conversion-shares`. The existing
  `multi-safe-conversion-shares` already correctly handles the TRADITIONAL
  ("pre-money SAFE") convention, where every SAFE's cap is priced against
  the SAME, FIXED `pre-conversion-shares` baseline -- there is no order
  dependency to solve in that convention, so the ns docstring's earlier
  wording ("no modeling of conversion order mattering... for some cap/
  discount combinations") was flagging a DIFFERENT, genuinely circular
  case: the YC 2018-style "post-money SAFE," where a cap is a direct claim
  on post-conversion ownership (`ownership-pct = investment / cap`), so N
  such SAFEs converting together each depend on the TOTAL post-conversion
  share count, which itself depends on every other SAFE's shares. Rather
  than approximate this with iteration, the fix derives an EXACT closed
  form: `k = sum(investment_i/cap_i)`; `post-safe-conversion-shares =
  pre-conversion-shares / (1 - k)`; `new-shares_i = ownership-pct_i *
  post-safe-conversion-shares` -- verified by hand cross-check in
  `captable_test.clj` and by confirming `post-safe-conversion-shares =
  pre-conversion-shares + total-safe-shares` holds algebraically. Throws
  on an oversubscribed round (`k >= 1.0`, the caps alone already claim
  100%+ of the company -- not a solvable cap table). Deliberately scoped
  to cap-only SAFEs (a post-money SAFE converting on a DISCOUNT instead
  has a different, non-circular mechanic, already covered by
  `safe-conversion`); mixing pre-money and post-money SAFEs in the same
  round, or a round with mixed cap/discount post-money SAFEs, remains out
  of scope (documented in the ns docstring, not silently ignored).
- **LP-level multi-currency FX** -- new fn `vcfund.nav/convert-currency`
  (pure: `amount`/`from-currency`/`to-currency`/`rate`, same-currency is
  always a no-op regardless of `rate`, a cross-currency conversion
  requires a caller-supplied positive `rate` -- never looked up or
  invented). `unfunded-commitments` gained OPTIONAL `:base-currency`/
  `:fx-rates` args (a currency->rate map) to convert each LP's commitment/
  called amount into one base currency before summing; omitting both
  preserves the exact single-currency behavior every earlier caller
  relies on (verified with a dedicated backward-compatibility test:
  `unfunded-commitments-without-fx-options-ignores-currency-field`).
  `fund-nav-report` threads the same options through to BOTH the
  unfunded-commitments call and the management-fee basis calculation
  (also a sum over LP commitment amounts). Deliberately bounded: this
  converts LP-level amounts only -- a deal's `:ask-amount`/`:currency` and
  a distribution's waterfall figures are NOT converted (still single-
  currency), documented explicitly as the remaining half of the FX gap
  rather than silently claimed as solved.
- Neither addition touches `vcfund.governor`/`vcfund.phase`/`vcfund.store`
  -- both `vcfund.captable` and `vcfund.nav` are pure calculator modules
  with no governor gating (setting/negotiating valuation terms or
  computing NAV moves no capital), so this iteration adds zero new
  governed decision gates; the "twelve governed decision gates" count is
  unchanged from Addendum 7.

Consequences: `test/vcfund/*` grew from 142 tests/571 assertions to 153
tests/599 assertions (new `post-money-multi-safe-conversion-shares` tests
-- happy path with a hand cross-check, a single-SAFE degenerate case,
validation rules, and an oversubscribed-round rejection -- in
`captable_test.clj`; new `convert-currency`/FX-aware `unfunded-
commitments`/`fund-nav-report` tests, including an explicit backward-
compatibility test, in `nav_test.clj`), still lint-clean; demo (`clojure
-M:dev:run`) unaffected (neither addition is a governed op, so sim.cljc
needed no changes -- consistent with how earlier `vcfund.captable`/
`vcfund.nav` additions were never wired into the demo driver either,
only `vcfund.waterfall` was, because the clawback-repay demo needed a
real number). Remaining honest gaps (tracked in README's "Business-
process coverage" table): a real e-signature PROVIDER integration and
redlining UI, mixed pre-money/post-money or mixed cap/discount post-money
multi-SAFE rounds, option tax treatment (ISO/NSO/AMT, 83(b)), deal-level/
distribution-level currency conversion, tax/regulatory reporting (K-1s,
Form D/ADV).

## Addendum 9 (2026-07-06, same day, autonomous /loop iteration): ISO/NSO option-exercise tax distinction, held-deal-level multi-currency FX

Again no new explicit owner direction -- the recurring `/loop` prompt
asked for coverage/maturity improvement. Of Addendum 8's remaining-gaps
list, this autonomously picked the two items with the best effort/risk
ratio: "option tax treatment" (scoped down to just the ISO-vs-NSO
exercise-time distinction, deliberately NOT full AMT liability -- see
below) and the held-investment slice of "deal-level currency
conversion." Skipped this iteration, and reconsidered rather than forced:
mixed pre-money/post-money (or mixed cap/discount post-money) multi-SAFE
rounds have no single clear market convention to encode -- an honest
"unmodeled, pick the fn matching your round's actual convention" is
better than a synthetic answer; `total-invested-at-cost`/distribution-
waterfall FX conversion would require adding a `currency` field to
`vcfund.registry/register-commitment`'s record shape, a signature change
that cascades across every existing call site (`store.cljc`,
`registry_test.clj`, `store_contract_test.clj`, `governor_contract_test.clj`,
`nav_test.clj`, `waterfall_test.clj`) for a fund whose demo deals are all
single-currency anyway -- deferred as a larger, separately-scoped piece
of work rather than rushed; e-signature-provider integration and tax/
regulatory reporting remain genuine boundaries (real third-party
integration / real regulatory-filing content), same as every prior
addendum's reasoning.

- **ISO/NSO option-exercise tax distinction** -- new fn
  `vcfund.captable/option-exercise-tax-treatment`, layered on top of
  `option-exercise-economics`'s `:intrinsic-value` (the spread) via
  `dissoc` + delegate, the same reuse-don't-duplicate pattern
  `accelerated-vesting` established for `vesting-schedule`. `:nso` ->
  the spread is ordinary income at exercise (IRC §83(a)):
  `:ordinary-income-tax` = spread * a REQUIRED, caller-supplied
  `ordinary-income-tax-rate` (never invented). `:iso` -> no regular tax
  at exercise (IRC §421(a)), but the spread IS an AMT preference item
  (IRC §56(b)(3)): `:amt-preference-item` = the spread. Deliberately
  bounded to be honest rather than complete: this reports the PREFERENCE
  ITEM only, never computed AMT LIABILITY (which needs the holder's
  entire tax return -- exemption phase-out, other preference items, the
  regular-tax-vs-tentative-minimum-tax comparison -- out of scope, a real
  tax advisor's job). 83(b) elections are not modeled at all.
- **Held-deal-level multi-currency FX** -- `fund-nav-report`'s
  `investments` construction (cost-basis from a deal's `:ask-amount`,
  fair-value from a `:fair-value-mark` KPI) now runs through the SAME
  `conv` closure already threading `:base-currency`/`:fx-rates` through
  the LP-level calculations added in Addendum 8 -- using the deal's own
  `:currency` field (already present on every deal record; no schema
  change needed). This closes the MORE visible, MORE important half of
  "deal-level FX" for NAV purposes: a held (un-exited) investment's
  CURRENT value, in a multi-currency portfolio. The other half --
  `total-invested-at-cost` (historical sum over commitment-history
  records) and every distribution/waterfall figure -- remains
  single-currency, honestly documented as still open (see above).
- Neither addition touches `vcfund.governor`/`vcfund.phase`/
  `vcfund.store`/`vcfund.operation` -- both are pure-calculator
  extensions with no governor gating, so this iteration adds zero new
  governed decision gates; the "twelve governed decision gates" count is
  unchanged from Addendum 8.

Consequences: `test/vcfund/*` grew from 153 tests/599 assertions to 158
tests/614 assertions (new `option-exercise-tax-treatment` tests -- NSO
ordinary-income, ISO AMT-preference-item, underwater-options-owe-nothing
for both, and validation rules including reuse of `option-exercise-
economics`'s own checks -- in `captable_test.clj`; a new held-deal FX
test covering both the unmarked cost-basis case and the marked
fair-value case in `nav_test.clj`), still lint-clean; demo (`clojure
-M:dev:run`) unaffected (same reasoning as Addendum 8 -- neither addition
is a governed op). Remaining honest gaps (tracked in README's
"Business-process coverage" table): a real e-signature PROVIDER
integration and redlining UI, mixed pre-money/post-money or mixed
cap/discount post-money multi-SAFE rounds, actual AMT LIABILITY
computation and 83(b) elections, `total-invested-at-cost`/distribution-
waterfall currency conversion, tax/regulatory reporting (K-1s, Form
D/ADV).

## Addendum 10 (2026-07-06, same day, autonomous /loop iteration): PER-LP capital-account statements

No new explicit owner direction again -- the recurring `/loop` prompt
asked for coverage/maturity improvement. This time, rather than continue
narrowing Addendum 9's already-thin remaining-gaps list (which is now
mostly genuine third-party-integration boundaries -- e-signature
provider, real tax/regulatory content -- or a previously-reasoned-against
large refactor -- `total-invested-at-cost`/distribution currency, which
would need a breaking signature change to `vcfund.registry/register-
commitment` cascading across every existing call site), this iteration
identified a genuinely NEW, previously-untracked gap: this actor has
never produced a PER-LP report. Every reporting surface built across
Addenda 1-9 (`fund-nav-report`, `whole-fund-waterfall-report`) is a
WHOLE-FUND view; a real fund's LPs expect their OWN quarterly capital-
account statement (their commitment, called-to-date, distributed-to-date
and current NAV share) -- a standard VC/PE fund-administration
deliverable that was simply absent from both the code and every prior
addendum's coverage table (`grep`-verified: "capital account" appeared
exactly once, in `docs/business-model.md`'s Offer list, referring to LP
INTAKE, never to an actual per-LP STATEMENT).

- **`vcfund.nav/lp-capital-account`** (new, pure) -- one LP's slice: an
  `unfunded-commitments`-style `{:lp-id :commitment-amount :called-amount
  :unfunded}` row (already FX-converted if the caller asked for that)
  PLUS `:ownership-pct` (commitment-amount / fund-wide total-commitments
  -- the SAME denominator every capital call already pro-rates against,
  via `vcfund.registry/capital-call-allocations`, so an LP's ownership
  share here is guaranteed internally consistent with how much of every
  actual call/distribution they bear), `:distributed-to-date`
  (ownership-pct * the fund's aggregate distributions) and `:nav-share`
  (ownership-pct * whole-fund NAV). Pro-rata by COMMITMENT SHARE (the
  standard LPA convention), not called-to-date share.
- **`vcfund.nav/lp-capital-account-report`** (new, store-aware adapter)
  -- calls `fund-nav-report` INTERNALLY (never re-derives the whole-fund
  totals independently) and maps `lp-capital-account` over every LP in
  `(:unfunded nav-report)`'s already-FX-converted `:by-lp` rows. This
  guarantees an LP's own statement and the whole-fund NAV report always
  reconcile against the exact same numbers -- verified by a dedicated
  test asserting `sum(nav-share)` = whole-fund `:nav` and
  `sum(distributed-to-date)` = `:total-distributed-to-lps` exactly.
  Inherits `fund-nav-report`'s fee/step-down/FX options unchanged (passed
  straight through), so a multi-currency fund's per-LP statements convert
  into the same base currency the whole-fund view uses.
- `fund-nav-report`'s returned map grew ONE new key,
  `:total-distributed-to-lps` (a value it was already computing
  internally for `fund-nav`'s own `total-distributed-to-lps` input, just
  never exposed) -- purely additive, does not change any existing key's
  meaning or any existing caller's behavior.
- Honestly bounded, and now explicitly documented (both in the ns
  docstring and README's coverage table): `lp-capital-account`'s
  ownership-pct is a STATIC commitment-share split for the life of the
  fund -- an LP selling their fund interest to another investor mid-fund
  (a secondary transfer) is not modeled at all. This is a genuinely NEW
  limitation surfaced by building the feature, not a pre-existing one
  this addendum happened to notice -- the honest-limitations discipline
  applies to gaps this same addendum's own new code opens up, not only
  to gaps inherited from earlier work.
- Neither new fn touches `vcfund.governor`/`vcfund.phase`/
  `vcfund.store`/`vcfund.operation` -- both are pure read-only reporting,
  the same posture every other `vcfund.nav` fn takes; zero new governed
  decision gates this iteration, the "twelve governed decision gates"
  count is unchanged from Addendum 9.

Consequences: `test/vcfund/*` grew from 158 tests/614 assertions to 164
tests/629 assertions (new tests in `nav_test.clj`: `fund-nav-report`
exposing `:total-distributed-to-lps`, `lp-capital-account`'s pure math
and validation rules, `lp-capital-account-report` reconciling exactly
against `fund-nav-report`'s totals, FX-awareness, and the empty-store
edge case), still lint-clean; demo (`clojure -M:dev:run`) unaffected --
consistent with every prior `vcfund.nav`/`vcfund.captable` addition,
neither of the two new fns is a governed op, so `sim.cljc` needed no
changes. Remaining honest gaps (tracked in README's "Business-process
coverage" table): a real e-signature PROVIDER integration and redlining
UI, mixed pre-money/post-money or mixed cap/discount post-money
multi-SAFE rounds, actual AMT LIABILITY computation and 83(b) elections,
`total-invested-at-cost`/distribution-waterfall currency conversion,
secondary transfers of an LP's fund interest, tax/regulatory reporting
(K-1s, Form D/ADV).

## Addendum 11 (2026-07-06, same day, owner-directed): `cloud-itonami-isic-6430`/`6630` promoted from documentation-only to real, tested cross-repo integration

Section 6 above ("Relationship to the adjacent ISIC blueprints")
described `6430` (trust/fund vehicle) and `6630` (fee-based fund
management) as "adjacent" -- but at the time, BOTH were `:blueprint`-tier
markdown-only stubs with zero code, so "self-contained sibling, not a
shared-code dependency" was really just "no relationship exists yet to
even be a dependency." Asked directly whether this repo was "連携/連動"
(linked/integrated) with any other ISIC classification, and then asked
to design real interoperation, the owner picked the largest of three
offered scopes: implement BOTH `6430` and `6630` as real governed actors
(promoted `:blueprint` → `:implemented`, the same tier this repo
reached earlier the same day) and wire them to genuinely interoperate
with this one.

- **The three-actor system**: this repo (`6499`) decides WHAT capital
  moves (DD, sourcing, capital-call/commitment/distribution
  PROPOSALS -- never itself a legal act). `cloud-itonami-isic-6430`
  (`trustfund.*`) is the fund vehicle -- the legal entity that actually
  holds LP subscriptions and issues the binding capital-call NOTICE off
  an upstream proposal from THIS repo. `cloud-itonami-isic-6630`
  (`fundmgmt.*`) is the management company -- the GP entity that draws
  the management fee THIS repo's `vcfund.nav/fund-nav-report` computes
  as an accrual. Three separate repos, three separate legal entities,
  NO shared code -- only a documented DATA CONTRACT, the same
  "self-contained sibling" posture this repo already has toward
  `kotoba-lang/insurance`.
- **This repo needed almost no code change** -- its EXISTING pure
  functions already produced exactly the fact shapes the two new
  downstream actors needed: `vcfund.registry/register-capital-call`'s
  return value IS the upstream fact `trustfund.governor` ingests and
  independently re-verifies (recomputing the SAME pro-rata-by-
  commitment-share math from ITS OWN subscription ledger, a deliberately
  SEPARATE re-implementation, never a shared-library call -- so a bug in
  one side's math can't silently defeat the other side's check).
- **One small, additive change WAS needed**: `vcfund.nav/fund-nav-
  report`'s return map now also exposes `:fee-basis`/`:annual-fee-rate`/
  `:years-elapsed` (the exact inputs `management-fee-accrued` used to
  compute `:management-fees-accrued`) -- these were previously only
  local `let` bindings inside the fn, computed but never returned. This
  is what `cloud-itonami-isic-6630`'s `fundmgmt.governor` needs to
  independently recompute the accrual (`fundmgmt.registry/fee-accrued`,
  again a separate re-implementation) from the SAME basis/rate/years
  this repo used, rather than trusting `:management-fees-accrued` alone
  as an opaque final number. Purely additive -- every existing caller's
  behavior is unchanged (verified: all 164 pre-existing tests still
  pass unmodified), the same "expose an already-computed local value"
  pattern used for `:total-distributed-to-lps` in Addendum 10.
- **Each downstream actor adds real, non-duplicative value, not a
  rubber stamp**: `trustfund.governor` independently re-verifies the
  FULL pro-rata allocation (protects against a wrong/tampered upstream
  number, since it has its own LP subscription directory to re-derive
  from). `fundmgmt.governor` cannot re-derive `:fee-basis` (it has no LP
  directory of its own) but DOES independently reapply the rate*basis*
  years formula and, distinctly, checks the upstream-claimed rate
  against its OWN recorded LPA fee-rate-cap mandate and refuses to
  double-draw a billing period -- checks `vcfund.governor` has no
  concept of at all, since rate-cap compliance and double-draw
  prevention are fundamentally the management company's OWN fiduciary
  responsibility, not the investment actor's.
- See `cloud-itonami-isic-6430`'s and `cloud-itonami-isic-6630`'s own
  ADR-0001s for the full design of each downstream actor (governor
  checks, phase tables, test/demo scenarios).

Consequences: `vcfund.nav`'s test suite grew from 164 tests/629
assertions to 165 tests/632 assertions (one new test confirming the
exposed fee-input keys), still lint-clean, demo unaffected (a purely
additive return-map change). `cloud-itonami-isic-6430` went from 0 lines
of code to 26 tests/116 assertions (lint-clean, demo runs end-to-end);
`cloud-itonami-isic-6630` went from 0 lines of code to 25 tests/98
assertions (lint-clean, demo runs end-to-end). Both new actors' demos
and test suites exercise BOTH the clean escalate-then-approve path AND
multiple HARD-hold cases proving the independent re-verification
actually catches a tampered/mismatched/non-compliant upstream fact, not
merely a happy-path pass-through. Remaining honest gaps in the
three-actor system: no real transport between the three actors is
implemented (a message queue / signed webhook / shared `kotoba-server`
pod -- documented as an operator's deployment decision, not code here);
`6430` does not yet implement NAV publication or distribution recording
off upstream `6499` facts (same integration pattern, next steps);
`6630` does not yet implement carry distribution off `6499`'s exit-
waterfall `:total-to-gp` figure (same pattern, next step).
