# cloud-itonami-isic-6499

Open Business Blueprint for **ISIC Rev.5 6499**: Other financial service activities, except insurance and pension funding, n.e.c..

This repository designs a forkable OSS business for other financial service activities not elsewhere classified, except insurance and pension funding (e.g. factoring, check-cashing, money-order issuance) -- run by a qualified, licensed operator so a community or
independent professional never surrenders customer data and ledgers to a
closed SaaS.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot performs
the physical domain work**. Here a cash-handling robot manages physical currency custody for money-order/remittance operations,
under an actor that proposes actions and an independent **Financial Services Governor**
that gates them. The governor never dispatches hardware itself;
`:high`/`:safety-critical` actions require human sign-off.

## Core Contract

```text
intake + identity + case/account records
        |
        v
FinServ-LLM -> Financial Services Governor -> hold, proceed, or human approval
        |
        v
case/account ledger + evidence record + audit
```

No automated proposal, by itself, can complete the following without governor
approval and audit evidence: executing a real financial transaction (fund transfer/disbursement).

## Capability layer

This blueprint resolves its technology stack via
[`kotoba-lang/industry`](https://github.com/kotoba-lang/industry) (ISIC
`6499`). Required capabilities are implemented by:

- [`kotoba-lang/banking`](https://github.com/kotoba-lang/banking)
  -- accounts, IBAN, double-entry ledger, clearing

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## Maturity

`:blueprint` -- this repository is the published business/operator design.
The governed actor implementation (`FinServ-LLM` + `Financial Services Governor` as
running code) is a follow-up, same as any other `:blueprint`-tier
`cloud-itonami-*` entry in `kotoba-lang/industry`'s registry.

## License

Code and implementation templates are AGPL-3.0-or-later.
