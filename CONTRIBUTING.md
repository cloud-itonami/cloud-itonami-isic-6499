# Contributing

`cloud-itonami-6499` accepts contributions to the OSS blueprint, capability
bindings, policy tests, documentation and operator model.

## Development

The capability layer lives in [`kotoba-lang/banking`](https://github.com/kotoba-lang/banking). This repo holds the business blueprint and operator
contracts.

```bash
# in kotoba-lang/banking:
clojure -M:test
clojure -M:lint
```

Keep changes small and include tests for any capability-layer change.

## Rules

- Do not commit real customer records, credentials, or personal/financial data.
- Keep executing a real financial transaction (fund transfer/disbursement) behind the Financial Services Governor.
- Treat this vertical as high-risk: add tests for spec-basis, disbursement,
  disclosure and audit logging.
- Document any new business-model or operator assumption in `docs/`.

## Pull Requests

PRs should describe:

- what behavior changed
- which policy invariant is affected
- how it was tested
- whether operator or certification docs need updates
