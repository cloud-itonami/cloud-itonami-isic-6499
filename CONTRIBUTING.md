# Contributing

`cloud-itonami-isic-6499` accepts contributions to the OSS blueprint, governed
actor, policy tests, documentation and operator model.

## Development

```bash
clojure -M:dev:run     # walk the demo lifecycle through one OperationActor
clojure -M:dev:test    # governor contract · phase invariants · store parity
clojure -M:lint         # clj-kondo (errors fail; CI mirrors this)
```

Keep changes small and include tests for any governor/phase change.

## Rules

- Do not commit real LP records, real portfolio-company data, credentials,
  or personal/financial data.
- Keep committing fund capital or distributing exit proceeds behind the
  InvestmentCommitteeGovernor.
- Treat this vertical as high-risk: add tests for spec-basis, sanctions,
  accredited-investor status, and audit logging.
- Never fabricate a jurisdiction's fund-formation/exemption requirements to
  make `vcfund.facts/coverage` look bigger -- extend the catalog with a real
  citation instead.
- Document any new business-model or operator assumption in `docs/`.

## Pull Requests

PRs should describe:

- what behavior changed
- which policy invariant is affected
- how it was tested
- whether operator or certification docs need updates
