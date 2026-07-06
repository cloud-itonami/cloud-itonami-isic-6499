# Business Model: Venture capital fund (ISIC 6499)

## Classification

- Repository: `cloud-itonami-isic-6499`
- ISIC Rev.4/5: `6499` -- Other financial service activities, except
  insurance and pension funding, n.e.c. The UN ISIC Rev.4 explanatory note
  for this class names *"own-account investment activities, such as by
  venture capital companies, investment clubs etc."* explicitly
  (https://unstats.un.org/unsd/classifications/Econ/Detail/EN/27/6499) --
  this repository implements that member of the class.
- Activity: raising committed capital from limited partners, sourcing and
  diligencing deals, deploying capital into portfolio companies, and
  distributing exit proceeds back to LPs.
- Social impact: financial inclusion (open-source fund-ops tooling lowers
  the cost of running an emerging-manager fund), data sovereignty,
  transparent audit.

## Customer

- emerging-manager / first-time GPs who want to self-host fund operations
  instead of buying a closed fund-admin SaaS
- angel syndicates transitioning to a committed-capital fund structure
- accelerator-affiliated seed funds
- independent fund administrators serving multiple small GPs

## Offer

- deal pipeline tracking (sourcing → screening → pitched → term-sheet →
  dd → ic-review), illegal transitions blocked
- LP subscription intake (commitment amount, capital account, accredited-
  investor/QP affirmation)
- deal due-diligence checklist proposal against a named framework's
  spec-basis
- AML/sanctions screening for LPs and portfolio-company parties
- versioned term-sheet negotiation rounds with redline diffs, plus
  two-sided e-signature execution tracking (blocked once the deal is
  already committed)
- capital-call notice proposal, pro-rata by commitment share, overcall-blocked
- Investment Committee capital-deployment proposal (requires the deal to
  have actually reached Investment Committee review in the pipeline, and
  the latest term-sheet round to be fully executed, not merely proposed)
- dedicated follow-on investment-deployment proposal for an already-
  committed deal, referencing the original commitment number
- whole-fund GP-clawback reconciliation and a governed repayment proposal,
  HARD-gated on the requested amount never exceeding the independently
  recomputed whole-fund entitlement
- portfolio-company KPI/board-report logging for committed deals
- board-seat/governance-rights administration (grant and revocation) for
  committed deals, with a current-roster projection over the append-only
  event log
- SAFE/SAFT-conversion (both pre-money and post-money multi-SAFE
  simultaneous-conversion conventions), priced-round ownership/dilution
  (percentage and absolute share-count terms), option-pool-shuffle,
  vesting (with single/double-trigger change-of-control acceleration) and
  option-exercise estimates
- crypto-native deal support (SAFT token deals, on-chain LP wallet settlement)
- whole-fund NAV, unfunded-commitment (optional multi-currency conversion
  for LPs denominated in different currencies) and management-fee
  reporting (optional step-down after the investment period)
- exit-proceeds waterfall distribution proposal
- immutable audit ledger

## Revenue

- self-host setup: one-time implementation fee
- managed hosting: monthly subscription per fund vehicle
- support: monthly retainer with SLA
- migration: import from an incumbent fund-admin system or spreadsheets
- capital-call / distribution processing fee

## Trust Controls

- no capital is called from LPs, committed to a portfolio company (initial
  or follow-on), paid out as an exit distribution, or repaid by the GP as a
  clawback, without Investment Committee (human) sign-off
- a fabricated DD checklist, a sanctions hit, a missing accredited-investor
  affirmation, a call that would overcall an LP past their commitment, a
  commit attempted with no term sheet ever proposed OR with a proposed but
  unexecuted (not fully signed) term sheet, a follow-on or a board-seat
  event proposed on a deal never committed, or a clawback repayment
  exceeding the independently recomputed whole-fund entitlement forces a
  hold, not an override
- LP personal/financial data and portfolio-company confidential data stay
  outside Git
- every call/commitment/distribution path is auditable
- emergency manual override paths remain outside LLM control
