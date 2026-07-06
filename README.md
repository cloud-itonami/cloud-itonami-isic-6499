# cloud-itonami-isic-6499

Open Business Blueprint for **ISIC Rev.4/5 6499**: Other financial service
activities, except insurance and pension funding, n.e.c. -- specifically
the class's own-account-investing member, a **venture capital fund**: deal
pipeline/sourcing, LP subscription intake, deal due diligence, versioned
term-sheet negotiation with e-signature execution, capital calls,
Investment Committee capital deployment, portfolio-company KPI monitoring
and whole-fund NAV, and exit distribution back to LPs -- crypto-native
capable (SAFT token deals, on-chain LP settlement) alongside traditional
equity/SAFE/convertible-note investing.

UN ISIC Rev.4's own explanatory note for 6499 names this activity
explicitly: *"own-account investment activities, such as by venture
capital companies, investment clubs etc."*
(https://unstats.un.org/unsd/classifications/Econ/Detail/EN/27/6499). This
repository was originally published as a generic `:blueprint`-tier n.e.c.
scaffold (factoring / check-cashing / money-order framing) and has since
been promoted to `:implemented` as this specific business, the one 6499's
own citation names first -- see
[`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md) and
superproject ADR-2607061700 for the history (a short-lived standalone
`cloud-itonami-vc-fund` repo was consolidated in here rather than kept
separate, to stay on the ISIC-numbered naming convention).

Built on this workspace's
[`langgraph-clj`](https://github.com/com-junkawasaki/langgraph-clj)
StateGraph runtime (portable `.cljc`, supervised superstep loop,
interrupts, Datomic/in-mem checkpoints) -- the same actor pattern as
[`cloud-itonami-isic-6511`](https://github.com/cloud-itonami/cloud-itonami-isic-6511)
(Underwriter-LLM ⊣ UnderwritingGovernor), `cloud-itonami-M6910`
(Registrar-LLM ⊣ RegistrarGovernor) and `cloud-itonami-L6810` (Realtor-LLM
⊣ RealtorGovernor). Here it is **DD-LLM ⊣ InvestmentCommitteeGovernor**.

> **Why an actor layer at all?** An LLM is great at drafting a
> due-diligence checklist, normalizing LP subscription intake and flagging
> a thin KYC file -- but it has **no notion of which securities exemption
> applies, no fiduciary authority over LP capital, and no business being
> the one that decides real money moves into a portfolio company or back
> out to LPs today**. Letting it commit capital directly invites
> fabricated DD rationale, laundering sanctioned parties into a cap table,
> and silent fiduciary liability for whoever runs it. This project seals
> the DD-LLM into a single node and wraps it with an independent
> **InvestmentCommitteeGovernor**, a human **approval workflow**, and an
> immutable **audit ledger**.

## Scope: what this actor does and does not do

This actor drafts and governs a venture-fund lifecycle: deal pipeline
tracking (sourcing through Investment Committee review), LP subscription
intake, AML/sanctions screening, per-deal due-diligence checklisting,
versioned term-sheet negotiation rounds with redline diffs and e-signature
execution tracking, a capital-call proposal, an Investment-Committee
capital-deployment proposal, portfolio-company KPI reporting, and an
exit/distribution proposal. It does **not**, by itself,
hold a license or exemption to operate a fund in any jurisdiction, and it
does not claim to. Whoever deploys and operates a live instance (a
licensed/exempt GP, a fund administrator) supplies the jurisdiction-specific
exemption filing, the real KYC/AML program, the real cap-table/fund-
accounting integrations, and bears that jurisdiction's fiduciary liability
-- the software supplies the governed, spec-cited, audited execution
scaffold so that operator does not have to build the compliance layer from
scratch for every new fund.

### Actuation

**Calling committed capital in from LPs, committing fund capital into a
portfolio company, and distributing exit proceeds back to LPs, are never
autonomous, at any phase, by construction.** Unlike the life-insurance
template this repo ports (which has exactly one real-world actuation
event), a VC fund has **three** independent real-money-movement directions
-- capital coming in from LPs, capital going out to a portfolio company,
proceeds coming back to LPs -- so `vcfund.governor/high-stakes` has three
members: `:actuation/call`, `:actuation/deploy` and
`:actuation/distribute`. Two independent layers enforce this for each
(`vcfund.governor`'s high-stakes gate and `vcfund.phase`'s phase table,
which never puts any of the three ops in any phase's `:auto` set) -- see
`vcfund.phase`'s docstring and `test/vcfund/phase_test.clj`'s
actuation-never-auto tests. The actor may draft, check, screen and
recommend; a human Investment Committee is always the one who actually
calls capital, commits capital or authorizes a distribution.

## The core contract

```
LP/deal facts + jurisdiction fund-formation facts (vcfund.facts, spec-cited)
        |
        v
   ┌──────────────┐   proposal      ┌───────────────────────────┐
   │   DD-LLM     │ ─────────────▶ │ InvestmentCommitteeGovernor │  (independent system)
   │  (sealed)    │  + citations   │ spec-basis · sanctions ·    │
   └──────────────┘                 │ DD-complete · accredited-  │
                             commit ◀──────────┼──────────▶ hold │ investor · overcall
                                 │                  │        └─────────┬──────────┘
                           record + ledger    escalate ─▶ 人間承認 (Investment Committee)
                                                (ALWAYS for :capital-call/issue,
                                                 :investment/commit and
                                                 :exit/distribute)  un-overridable
```

**The DD-LLM never calls capital, commits capital or distributes proceeds
the InvestmentCommitteeGovernor would reject, and never does any of the
three without a human Investment Committee sign-off.** Hard violations
(fabricated fund-formation/DD spec-basis, sanctions hit, incomplete DD,
missing accredited-investor affirmation, an overcalled LP, an
investment-commit attempt with no term sheet on file or an unexecuted
one) force **hold** and *cannot* be approved past; a clean
call/commitment/distribution proposal still always routes to a human.

## Crypto-native operation

This fund is not fiat-only. `vcfund.registry/register-commitment` accepts
`:saft` (Simple Agreement for Future Tokens) alongside `:safe`/
`:convertible-note`/`:priced-equity` as a `:security-type`, and
`vcfund.captable/saft-conversion` computes token-allocation-percentage
conversion math (cap-vs-discount, the same mechanic as `safe-conversion`,
denominated in token supply instead of company equity) for a deal that
converts at TGE (Token Generation Event) rather than a priced equity
round. LPs may carry a `:wallet-address` for on-chain capital-call
funding/distribution settlement instead of (or alongside) bank-wire
details -- the demo fixture's `lp-1` has one. Sanctions/AML screening
(`:kyc/screen`) already works on wallet addresses the same way it works on
passport numbers: `vcfund.store`'s party `:id-doc` field is a free-form
identifier, so an operator's real KYC/AML provider can screen a wallet
address against an OFAC SDN-list-style sanctions check the same way it
screens a person -- no code change needed to plug that in. Currency
fields (`:currency` on LPs/deals) are free-form strings already, so a
fund denominated in a stablecoin (e.g. `"USDC"`) works today; see
`vcfund.nav`'s single-base-currency limitation for what's NOT modeled
(cross-currency/FX conversion between a fiat LP and a stablecoin-settled
capital call, for example).

## Run

```bash
clojure -M:dev:run     # walk one clean LP+deal lifecycle + one HARD-hold case through the actor
clojure -M:dev:test    # governor contract · phase invariants · store parity · registry conformance
clojure -M:lint        # clj-kondo (errors fail; CI mirrors this)
```

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here a secure document-custody robot
manages physical due-diligence data-room documents, signed term sheets and
cap-table paper records, under the actor, gated by the independent
**InvestmentCommitteeGovernor**. The governor never dispatches hardware
itself; `:high`/`:safety-critical` actions require human sign-off.

## Open business

This repository is not only source code. It is a public, forkable business
model:

| Layer | What is open |
|---|---|
| OSS core | Actor runtime, InvestmentCommitteeGovernor, investment/exit draft records, audit ledger |
| Business blueprint | Customer, offer, pricing, unit economics, sales motion |
| Operator playbook | How to fork, license, deploy and support the fund in a jurisdiction |
| Trust controls | Governance, security reporting, actuation invariant, audit requirements |

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md) to start this as an open
business on itonami.cloud, and
[`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md) for the
full architecture and decision record.

## Capability layer

This blueprint resolves its technology stack via
[`kotoba-lang/industry`](https://github.com/kotoba-lang/industry) (ISIC
`6499`). `vcfund.*` is a self-contained governed implementation -- it does
not require the sibling `kotoba-lang/securities` capability lib directly,
the same "self-contained sibling" relationship `cloud-itonami-isic-6511`'s
`underwriting.*` has to `kotoba-lang/insurance`.

## Layout

| File | Role |
|---|---|
| `src/vcfund/store.cljc` | **Store** protocol -- `MemStore` ‖ `DatomicStore` (`langchain.db`) + append-only audit ledger + capital-call/investment/distribution/portfolio-report/term-sheet/signature history; LPs carry an optional `:wallet-address` |
| `src/vcfund/registry.cljc` | Capital-call pro-rata allocation + investment-commitment draft records (`:safe`/`:convertible-note`/`:priced-equity`/`:saft`) + exit-distribution waterfall calc (deal-by-deal, documented limitation) + portfolio-report + versioned term-sheet drafts + `term-sheet-diff` redline + e-signature drafts + `fully-executed?` |
| `src/vcfund/pipeline.cljc` | Pure deal-pipeline funnel model (sourcing → screening → pitched → term-sheet → dd → ic-review), forward-only transitions, `:committed`/`:exited` reachable only via the real capital ops |
| `src/vcfund/captable.cljc` | Pure SAFE/SAFT conversion, priced-round ownership/dilution (percentage AND absolute share-count terms), option-pool-shuffle, multi-SAFE simultaneous conversion, vesting schedules and option-exercise economics (see docstring for what it deliberately does NOT model) |
| `src/vcfund/nav.cljc` | Pure whole-fund NAV + unfunded-commitment + management-fee-accrual calculator, plus a store-aware `fund-nav-report` adapter |
| `src/vcfund/waterfall.cljc` | Pure whole-fund (European-style) waterfall reconciliation + GP-clawback calculator, plus a store-aware `whole-fund-waterfall-report` adapter |
| `src/vcfund/facts.cljc` | Per-jurisdiction fund-formation/exemption-regime catalog with an official spec-basis citation per entry, honest coverage reporting |
| `src/vcfund/ddllm.cljc` | **DD-LLM Advisor** -- `mock-advisor` ‖ `llm-advisor`; LP-intake/DD/KYC/stage-advance/term-sheet-propose(+redline)/term-sheet-sign/capital-call/commitment/portfolio-report/distribution proposals |
| `src/vcfund/governor.cljc` | **InvestmentCommitteeGovernor** -- 12 checks: spec-basis · sanctions hold · DD-complete · stage-insufficient · stage-transition · term-sheet-missing · term-sheet-not-executed · term-sheet-after-commitment · accredited-investor · capital-call overcall · portfolio-report-requires-commitment · confidence/triple-actuation gate |
| `src/vcfund/phase.cljc` | **Phase 0→3** -- read-only → assisted intake → assisted DD/screen → supervised (call/commit/distribute always human; stage-advance/term-sheet-propose/term-sheet-sign/portfolio-report auto-eligible, no capital risk) |
| `src/vcfund/operation.cljc` | **OperationActor** -- langgraph-clj StateGraph |
| `src/vcfund/sim.cljc` | demo driver |
| `test/vcfund/*_test.clj` | governor contract · phase invariants · store parity · registry conformance · pipeline/captable/nav/waterfall unit tests · facts coverage |

## Business-process coverage (honest)

This actor covers **nine governed decision gates** in a VC fund's
lifecycle, a pipeline/cap-table/NAV/whole-fund-waterfall calculator layer,
plus the audit ledger. It does **not** cover the surrounding day-to-day
fund operations. Stated plainly so nobody mistakes gate coverage for full
fund-administration coverage:

| Covered | Not covered (out of scope for this R0) |
|---|---|
| Deal pipeline: sourcing → screening → pitched → term-sheet → dd → ic-review, forward-only, illegal transitions HARD-blocked (`vcfund.pipeline`) | Fund formation, LPA drafting, fund closes, side letters |
| LP subscription intake + fund-wide accredited-investor gate | Term-sheet *redlining UI*/real e-signature integration (DocuSign etc.) -- `vcfund.registry/term-sheet-diff` computes the redline and `:term-sheet/sign` tracks execution state, but there's no document-rendering/markup UI or actual e-signature provider wired in |
| Versioned term-sheet negotiation rounds with field-level redline diffs, AND two-sided e-signature execution tracking (`:term-sheet/propose`/`:term-sheet/sign`; `:investment/commit` requires the LATEST version to be signed by BOTH `:fund` and `:founder`, not merely proposed) | A real clawback REPAYMENT act -- `vcfund.waterfall` computes what the GP owes back; actually moving that money is not a governed op here |
| Capital calls, pro-rata by commitment share, overcall-blocked | Multi-SAFE conversion assumes one shared `pre-conversion-shares` baseline, not a fully order-dependent simultaneous solve (`vcfund.captable`, see its docstring) |
| Deal DD checklist vs. named jurisdiction spec-basis, AND a HARD gate that `:investment/commit` requires the deal to have actually reached `:ic-review` in the pipeline | Follow-on investment decisions (modeled today as: source a new deal record for the follow-on round, run it through the same DD→commit lifecycle -- no dedicated "follow-on" op) |
| AML/sanctions screening (LPs, founders; wallet addresses screen the same way as passport numbers, see "Crypto-native operation") | Board seats / governance rights administration |
| Investment-Committee capital deployment (`:safe`/`:convertible-note`/`:priced-equity`/`:saft`) | Management-fee schedule step-downs after the investment period, vesting acceleration on change-of-control, option tax treatment (ISO/NSO/AMT) -- all flat/simplified, see `vcfund.nav`/`vcfund.captable` docstrings |
| Portfolio-company KPI/board reporting for committed deals (`:portfolio/report`, HARD-gated on the deal actually being committed) | Multi-currency FX conversion (`vcfund.nav`/`vcfund.waterfall` assume one fund base currency; a stablecoin-denominated fund works as long as it's the ONE base currency) |
| SAFE/SAFT-conversion, priced-round ownership/dilution (percentage AND absolute share-count terms), option-pool-shuffle, multi-SAFE simultaneous conversion, vesting schedules and option-exercise economics (`vcfund.captable`) | Tax reporting (K-1s etc.), regulatory filings (Form D/ADV), real fund-accounting-system integration |
| Whole-fund NAV, unfunded-commitment and management-fee-accrual reporting (`vcfund.nav`, fair value defaults to cost basis until a `:fair-value-mark` KPI is recorded) | |
| Whole-fund (European-style) waterfall reconciliation + GP-clawback calculation against deal-by-deal carry already paid (`vcfund.waterfall`) | |
| Exit-proceeds waterfall (deal-by-deal: return of capital → preferred return → GP carry -- what actually pays out; `vcfund.waterfall` reconciles against it, doesn't replace it) | |
| Crypto-native: `:saft` security type, `vcfund.captable/saft-conversion` (token-allocation-% math), LP `:wallet-address` for on-chain settlement (see "Crypto-native operation") | |
| Immutable audit ledger for every call/commit/report/distribute/term-sheet/signature decision | |

Extending coverage is additive, the same discipline as jurisdiction
coverage: add the next gate as its own governed op (or calculator module,
when the concern is advisory/reconciliation rather than a capital-movement
decision) with its own HARD checks and tests, never silently expand scope
without a corresponding governor rule and test. History so far: capital
calls, then pipeline/portfolio-monitoring/cap-table math, then whole-fund
NAV/absolute share counts/term-sheet negotiation, then management-fee
accrual and whole-fund-waterfall/GP-clawback reconciliation, then
term-sheet e-signature execution/redlining, cap-table vesting/option/
multi-SAFE math, and crypto-native SAFT/wallet support, were the additions
beyond the initial four-gate R0.

## Jurisdiction coverage (honest)

`vcfund.facts/coverage` reports how many requested jurisdictions actually
have an official spec-basis in `vcfund.facts/catalog` -- currently 4 seeded
(JPN, USA, GBR, DEU), the same four `cloud-itonami-isic-6511`'s
`underwriting.facts` seeded, out of ~194 jurisdictions worldwide. This is a
starting catalog to prove the governor contract end-to-end, not a claim of
global coverage. Adding a jurisdiction is additive: one map entry in
`vcfund.facts/catalog`, citing a real official source -- never fabricate a
jurisdiction's fund-formation/exemption requirements to make coverage look
bigger.

## License

Code and implementation templates are AGPL-3.0-or-later.
