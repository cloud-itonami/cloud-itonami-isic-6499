# cloud-itonami-isic-6499

Open Business Blueprint for **ISIC Rev.4/5 6499**: Other financial service
activities, except insurance and pension funding, n.e.c. -- specifically
the class's own-account-investing member, a **venture capital fund**: deal
pipeline/sourcing, LP subscription intake, deal due diligence, term-sheet
negotiation, capital calls, Investment Committee capital deployment,
portfolio-company KPI monitoring and whole-fund NAV, and exit distribution
back to LPs.

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
versioned term-sheet negotiation rounds, a capital-call proposal, an
Investment-Committee capital-deployment proposal, portfolio-company KPI
reporting, and an exit/distribution proposal. It does **not**, by itself,
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
missing accredited-investor affirmation, an overcalled LP) force **hold**
and *cannot* be approved past; a clean call/commitment/distribution
proposal still always routes to a human.

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
| `src/vcfund/store.cljc` | **Store** protocol -- `MemStore` ‖ `DatomicStore` (`langchain.db`) + append-only audit ledger + capital-call/investment/distribution/portfolio-report/term-sheet history |
| `src/vcfund/registry.cljc` | Capital-call pro-rata allocation + investment-commitment draft records + exit-distribution waterfall calc (deal-by-deal, documented limitation) + portfolio-report + versioned term-sheet drafts |
| `src/vcfund/pipeline.cljc` | Pure deal-pipeline funnel model (sourcing → screening → pitched → term-sheet → dd → ic-review), forward-only transitions, `:committed`/`:exited` reachable only via the real capital ops |
| `src/vcfund/captable.cljc` | Pure SAFE-conversion, priced-round ownership/dilution (percentage AND absolute share-count terms) and option-pool-shuffle calculator (see docstring for what it deliberately does NOT model) |
| `src/vcfund/nav.cljc` | Pure whole-fund NAV + unfunded-commitment calculator, plus a store-aware `fund-nav-report` adapter |
| `src/vcfund/facts.cljc` | Per-jurisdiction fund-formation/exemption-regime catalog with an official spec-basis citation per entry, honest coverage reporting |
| `src/vcfund/ddllm.cljc` | **DD-LLM Advisor** -- `mock-advisor` ‖ `llm-advisor`; LP-intake/DD/KYC/stage-advance/term-sheet/capital-call/commitment/portfolio-report/distribution proposals |
| `src/vcfund/governor.cljc` | **InvestmentCommitteeGovernor** -- 11 checks: spec-basis · sanctions hold · DD-complete · stage-insufficient · stage-transition · term-sheet-missing · term-sheet-after-commitment · accredited-investor · capital-call overcall · portfolio-report-requires-commitment · confidence/triple-actuation gate |
| `src/vcfund/phase.cljc` | **Phase 0→3** -- read-only → assisted intake → assisted DD/screen → supervised (call/commit/distribute always human; stage-advance/term-sheet-propose/portfolio-report auto-eligible, no capital risk) |
| `src/vcfund/operation.cljc` | **OperationActor** -- langgraph-clj StateGraph |
| `src/vcfund/sim.cljc` | demo driver |
| `test/vcfund/*_test.clj` | governor contract · phase invariants · store parity · registry conformance · pipeline/captable/nav unit tests · facts coverage |

## Business-process coverage (honest)

This actor covers **eight governed decision gates** in a VC fund's
lifecycle, a pipeline/cap-table/NAV calculator layer, plus the audit
ledger. It does **not** cover the surrounding day-to-day fund operations.
Stated plainly so nobody mistakes gate coverage for full
fund-administration coverage:

| Covered | Not covered (out of scope for this R0) |
|---|---|
| Deal pipeline: sourcing → screening → pitched → term-sheet → dd → ic-review, forward-only, illegal transitions HARD-blocked (`vcfund.pipeline`) | Fund formation, LPA drafting, fund closes, side letters |
| LP subscription intake + fund-wide accredited-investor gate | Term-sheet *redlining*/e-signature workflow (versions are recorded; there's no diff/markup UI or counter-offer state machine beyond "append the next version") |
| Versioned term-sheet negotiation rounds (`:term-sheet/propose`, blocked once the deal is already committed; `:investment/commit` requires at least one round on file) | Whole-fund European waterfall with cross-deal netting and GP clawback (explicitly out of scope, see `docs/adr/0001-architecture.md`) |
| Capital calls, pro-rata by commitment share, overcall-blocked | Vesting schedules, option strike-price/exercise modeling, multi-SAFE simultaneous-conversion proration (`vcfund.captable`, see its docstring) |
| Deal DD checklist vs. named jurisdiction spec-basis, AND a HARD gate that `:investment/commit` requires the deal to have actually reached `:ic-review` in the pipeline | Follow-on investment decisions (modeled today as: source a new deal record for the follow-on round, run it through the same DD→commit lifecycle -- no dedicated "follow-on" op) |
| AML/sanctions screening (LPs, founders) | Board seats / governance rights administration |
| Investment-Committee capital deployment | Fund expense/management-fee accrual netted into `vcfund.nav`'s cash balance (see its docstring) |
| Portfolio-company KPI/board reporting for committed deals (`:portfolio/report`, HARD-gated on the deal actually being committed) | Multi-currency FX conversion (`vcfund.nav` assumes one fund base currency) |
| SAFE-conversion, priced-round ownership/dilution (percentage AND absolute share-count terms) and option-pool-shuffle math (`vcfund.captable`) | Tax reporting (K-1s etc.), regulatory filings (Form D/ADV), real fund-accounting-system integration |
| Whole-fund NAV and unfunded-commitment reporting (`vcfund.nav`, fair value defaults to cost basis until a `:fair-value-mark` KPI is recorded) | |
| Exit-proceeds waterfall (deal-by-deal: return of capital → preferred return → GP carry) | |
| Immutable audit ledger for every call/commit/report/distribute/term-sheet decision | |

Extending coverage is additive, the same discipline as jurisdiction
coverage: add the next gate as its own governed op (or calculator module,
when the concern is advisory rather than a capital-movement decision) with
its own HARD checks and tests, never silently expand scope without a
corresponding governor rule and test. History so far: capital calls, then
pipeline/portfolio-monitoring/cap-table math, then whole-fund NAV/absolute
share counts/term-sheet negotiation, were the additions beyond the initial
four-gate R0.

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
