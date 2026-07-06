# cloud-itonami-isic-6499

Open Business Blueprint for **ISIC Rev.4/5 6499**: Other financial service
activities, except insurance and pension funding, n.e.c. -- specifically
the class's own-account-investing member, a **venture capital fund**: LP
subscription intake, deal due diligence, capital calls, Investment
Committee capital deployment, and exit distribution back to LPs.

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

This actor drafts and governs a venture-fund lifecycle: LP subscription
intake, AML/sanctions screening, per-deal due-diligence checklisting, a
capital-call proposal, an Investment-Committee capital-deployment
proposal, and an exit/distribution proposal. It does **not**, by itself,
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
| `src/vcfund/store.cljc` | **Store** protocol -- `MemStore` ‖ `DatomicStore` (`langchain.db`) + append-only audit ledger + capital-call/investment/distribution history |
| `src/vcfund/registry.cljc` | Capital-call pro-rata allocation + investment-commitment draft records + exit-distribution waterfall calc (deal-by-deal, documented limitation -- see docstring) |
| `src/vcfund/facts.cljc` | Per-jurisdiction fund-formation/exemption-regime catalog with an official spec-basis citation per entry, honest coverage reporting |
| `src/vcfund/ddllm.cljc` | **DD-LLM Advisor** -- `mock-advisor` ‖ `llm-advisor`; LP-intake/DD/KYC/capital-call/commitment/distribution proposals |
| `src/vcfund/governor.cljc` | **InvestmentCommitteeGovernor** -- spec-basis · sanctions hold · DD-complete · accredited-investor · capital-call overcall · confidence floor · triple actuation gate |
| `src/vcfund/phase.cljc` | **Phase 0→3** -- read-only → assisted intake → assisted DD/screen → supervised (call/commit/distribute always human) |
| `src/vcfund/operation.cljc` | **OperationActor** -- langgraph-clj StateGraph |
| `src/vcfund/sim.cljc` | demo driver |
| `test/vcfund/*_test.clj` | governor contract · phase invariants · store parity · registry conformance · facts coverage |

## Business-process coverage (honest)

This actor covers **four governed decision gates** in a VC fund's
lifecycle, plus the audit ledger. It does **not** cover the surrounding
day-to-day fund operations. Stated plainly so nobody mistakes gate
coverage for full fund-administration coverage:

| Covered | Not covered (out of scope for this R0) |
|---|---|
| LP subscription intake + fund-wide accredited-investor gate | Fund formation, LPA drafting, fund closes, side letters |
| Capital calls, pro-rata by commitment share, overcall-blocked | Whole-fund NAV / unfunded-commitment reporting beyond per-LP `:called-amount` |
| Deal DD checklist vs. named jurisdiction spec-basis | Deal *pipeline* / sourcing-funnel management across many deals at once (each deal is tracked, but there is no funnel/stage-transition model) |
| AML/sanctions screening (LPs, founders) | Term-sheet negotiation, cap-table dilution/valuation math |
| Investment-Committee capital deployment | Portfolio-company monitoring between commit and exit -- board seats, KPI/board reporting, follow-on decisions (nothing happens in this window today) |
| Exit-proceeds waterfall (deal-by-deal: return of capital → preferred return → GP carry) | Whole-fund European waterfall with cross-deal netting and GP clawback (explicitly out of scope, see `docs/adr/0001-architecture.md`) |
| Immutable audit ledger for every call/commit/distribute decision | Tax reporting (K-1s etc.), regulatory filings (Form D/ADV), real fund-accounting-system integration |

Extending coverage is additive, the same discipline as jurisdiction
coverage: add the next gate (capital calls were the first addition beyond
the initial four-gate R0) as its own governed op with its own HARD checks
and tests, never silently expand scope without a corresponding governor
rule and test.

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
