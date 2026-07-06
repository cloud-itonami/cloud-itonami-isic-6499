# Governance

`cloud-itonami-isic-6499` is an OSS open-business blueprint for a venture
capital fund (ISIC 6499's own-account-investment member) -- LP subscription
intake, deal due diligence, Investment Committee capital deployment, and
exit/distribution to LPs. Governance covers both the capability layer and
the operator model.

## Maintainers

Maintainers may merge changes that preserve these invariants:

- the InvestmentCommitteeGovernor remains independent of the advisor.
- hard policy violations (fabricated spec-basis, sanctions hit, incomplete
  DD, missing accredited-investor affirmation) cannot be overridden by
  human approval.
- committing fund capital into a portfolio company, or distributing exit
  proceeds to LPs, always escalates to a human Investment Committee --
  never automated.
- every hold, approval and disbursement path is auditable.
- LP personal/financial data and portfolio-company confidential data stay
  outside Git.

## Decision Records

Architecture decisions live in `docs/adr/`. Changes to the trust model,
storage contract, public business model, operator certification or license
should add or update an ADR.

## Operator Governance

Anyone may fork and operate independently. itonami.cloud certification is a
separate trust mark and should require security, audit and data-flow review.

Certified operators can lose certification for:

- bypassing the InvestmentCommitteeGovernor's policy checks
- mishandling LP or portfolio-company data
- misrepresenting certification status
- failing to respond to security incidents
- hiding material changes to customer-facing operation
