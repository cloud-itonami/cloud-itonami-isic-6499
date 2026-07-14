# wasm/ — kotoba-wasm deployment of the clawback-exceeds-entitlement check

`clawback_entitlement.kotoba` is a port of `vcfund.governor/clawback-
exceeds-entitlement-violations`'s pure ground-truth comparison -- does a
requested GP-clawback repayment exceed the independently recomputed
whole-fund waterfall entitlement? (see `src/vcfund/governor.cljc` lines
~335-351, check 14 of 15 HARD violations) -- into the minimal `.kotoba`
language subset, compiled to a real WASM module via `kotoba wasm emit`,
and hosted via `kototama.tender` (`test/wasm/clawback_entitlement_test.clj`).

This follows the same `kotoba wasm emit` → `kototama.tender` pattern
already proven by `cloud-itonami-isic-6492`'s `wasm/affordability.kotoba`,
`cloud-itonami-isic-6511`'s `wasm/underwriting_decision.kotoba` and
`cloud-itonami-isic-6512`'s `wasm/claim_coverage.kotoba` (ADR-2607062330
addendum 5) -- another sibling actor's hot-path decision function ported
to real WASM.

The decision itself already lives in `vcfund.kernels.gate/clawback-
exceeds` -- this actor went through the safe-kotoba safety-kernel
extraction (ADR-2607121200) before this wasm PoC, so the pure integer
comparison core was already isolated from the store lookups and
`gate/verdict-code` composition. That kernel's own docstring notes
`.kotoba`/wasm emission is deliberately not wired into the kernel's own
build yet (owner decision 2026-07-12: ClojureScript + kotoba-datomic
first) -- this module does not change that; it is a standalone PoC port
of the pure comparison, verified in isolation the same way
`cloud-itonami-isic-6611`/`-6630` proved their own post-kernel-extraction
checks (`listing_standard.kotoba`, `fee_accrual.kotoba`) as real WASM
without wiring wasm into either actor's main runtime.

## Why the source differs from `vcfund.governor` / `vcfund.kernels.gate`

The `.kotoba` compiler's actual WASM code-generator supports only a small,
empirically-verified subset: the special forms `do`/`let`/`if` plus
`+ - * quot / rem mod = < > <= >= zero? not inc dec` (confirmed by reading
`compile-wasm-expr` in `kotoba-lang/kotoba/src/kotoba/runtime.clj` -- no
`pos?`/`neg?`/`and`/`or`/`when`, unlike the broader tree-walking
interpreter). The port therefore:

- Ports ONLY the pure ground-truth arithmetic core -- the direct
  comparison of the requested repayment amount against the recomputed
  entitlement -- never the store lookup (`vcfund.waterfall/whole-fund-
  waterfall-report`) or the `:waterfall/clawback-repay` op-dispatch, both
  of which stay in Clojure and never get ported (no maps, no protocols,
  no op-keyword dispatch in the wasm-compilable subset).
- Uses plain positional integer args instead of `{:keys [...]}` map
  destructuring (no maps in the wasm-compilable subset).
- Inverts the polarity relative to `gate/clawback-exceeds`, which returns
  `1` when the repayment EXCEEDS the entitlement (a violation flag feeding
  `hard-violation`'s `norm-flag`). This module's `clawback-within-
  entitlement?` (and `main`) returns `1` when the repayment is WITHIN
  entitlement (i.e. NOT a violation) and `0` when it exceeds -- the more
  natural "is this OK" polarity for a boolean-shaped WASM export, the same
  polarity convention `claim_coverage.kotoba` (`cloud-itonami-isic-6512`)
  and `fee_accrual.kotoba`/`listing_standard.kotoba`
  (`cloud-itonami-isic-6630`/`-6611`) already use.
- Re-scales from **micro-units (x1,000,000)** -- the kernel's own wire
  convention, chosen there because the façade (`vcfund.governor`) starts
  from double-precision dollar amounts and the kernel is otherwise
  unconstrained integer width -- down to **cents (x100, smallest currency
  unit)**, the convention every other sibling wasm port already uses
  (`affordability.kotoba`, `claim_coverage.kotoba`, `fee_accrual.kotoba`).
  This is not cosmetic: `mem-i32-at` reads a 32-bit signed integer, so a
  micro-unit-scaled value tops out around **$2,147** before it overflows
  i32 (2,147,483,647 / 1,000,000), far too small for a real fund-scale
  GP-clawback repayment. Cents give ~$21.4M of representable range
  instead -- still a PoC-scale limit (see the known scope limit below),
  but a far more useful one for this domain. The comparison itself
  (`<=`) is scale-invariant, so this is purely a representable-range
  choice, not a semantic change from the kernel's own strict-exceeds
  contract.

This is a simpler port than `fee_accrual.kotoba`: one direct comparison,
no multi-term formula, no zero-guard branch -- the closest analog is
`claim_coverage.kotoba`.

**Known scope limit (i32 range):** because both inputs are cents-scaled
32-bit integers, a repayment or entitlement above ~$21.4M (2,147,483,647
cents) would overflow `i32` when the host writes it into linear memory --
this is a PoC-scale limitation of the `mem-i32-at` ABI itself (same
caveat `fee_accrual.kotoba`'s README documents for its own i32 product),
not a design claim that this port holds for arbitrarily large whole-fund
clawback entitlements. Promoting to i64 memory reads would lift this, and
is out of scope for this pass.

## ABI — parameterized invocation

`kotoba wasm emit` rejects any `main` with parameters (`:main-arity` --
the compiler only ever exports a 0-arity `main`, see `compile-wasm-expr`
in `kotoba-lang/kotoba/src/kotoba/runtime.clj`), so real inputs are passed
through the guest's exported linear memory instead -- the same convention
`cloud-itonami-isic-6492`'s `affordability.kotoba` and
`cloud-itonami-isic-6512`'s `claim_coverage.kotoba` use. A host writes two
little-endian i32 values (cents) before calling `main()`:

| offset | field                |
|--------|----------------------|
| 0      | `amount-cents`       |
| 4      | `entitlement-cents`  |

`main()` returns `1` (repayment within entitlement -- not a violation) or
`0` (repayment exceeds entitlement -- a HARD `:clawback-exceeds-
entitlement` violation per `vcfund.governor`). Both offsets are well below
`heap-base` (2048), so they never collide with anything the compiler
itself places in memory.

## Rebuilding

```sh
cd ../../kotoba-lang/kotoba   # sibling checkout, west-managed
bin/kotoba-clj wasm emit ../../cloud-itonami/cloud-itonami-isic-6499/wasm/clawback_entitlement.kotoba \
  --package-lock kotoba.lock.edn \
  --output ../../cloud-itonami/cloud-itonami-isic-6499/wasm/clawback_entitlement.wasm --json
```

Fleet deployment: not attempted in this pass — see cloud-itonami-isic-6492/6511 for the established pattern.
