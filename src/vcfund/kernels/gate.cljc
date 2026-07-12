(ns vcfund.kernels.gate
  "Safety kernel for the InvestmentCommitteeGovernor + phase gate — the
  decision CORE of `vcfund.governor/check` and `vcfund.phase/gate`,
  extracted into the safe-kotoba subset (cloud-itonami kernels
  discipline, ADR-2607121200 / superproject ADR-2607101200; template:
  `cloud-itonami-isic-6511`'s `underwriting.kernels.gate`).

  Everything here is integer-coded and stays inside the emit-ready
  vocabulary: `defn`, `def` constants, nested `if`, `=`, `<`, integer
  arithmetic, recursion-free composition through named combinators. No
  keywords, strings, maps, atoms, host interop or I/O — the façades
  (`vcfund.governor`, `vcfund.phase`) reduce their inputs to flags/codes
  at the boundary and map the result codes back to keywords.
  `.kotoba`/wasm emission is deliberately NOT wired yet (owner decision
  2026-07-12: ClojureScript + kotoba-datomic first); staying inside the
  subset is what keeps that door open without a rewrite.

  Wire codes:
    flag        0 = no, anything else = yes (norm-flag, fail-closed).
                Count-like inputs (unaffirmed-LP count, overcalled-LP
                count) ride the same convention: 0 = none, any other
                value = violation.
    confidence  int x100 (0..100); out-of-range counts as LOW (fail-closed)
    amount      int micro-units (x1,000,000) for the clawback threshold
                comparison — the façade rounds `amount` and the
                independently recomputed whole-fund `gp-clawback`
                entitlement to micro-units; micro-unit rounding replaces
                the old façade-side 1e-6 float epsilon.
    op          0  read (:coverage/report)     1  :lp/intake
                2  :dd/assess                  3  :kyc/screen
                4  :deal/advance-stage         5  :term-sheet/propose
                6  :term-sheet/sign            7  :capital-call/issue
                8  :investment/commit          9  :investment/follow-on
                10 :portfolio/report           11 :exit/distribute
                12 :waterfall/clawback-repay   13 :governance/board-seat
                14+ unknown write (never enabled)
    phase       0..3 (anything else: no writes at all — the façade
                normalizes unknown phases to its own default BEFORE the
                kernel, so an out-of-range phase reaching the kernel is
                a bug and fails closed)
    verdict     0 ok/commit-eligible  1 escalate  2 hard-hold
    disposition 0 commit  1 escalate  2 hold
    reason      0 none  1 phase-disabled  2 phase-approval

  Fail-closed direction: every invalid/unknown input degrades toward
  LESS autonomy (hold/escalate), never more. Ops 7/8/9/11/12 (the FOUR
  directions of real capital movement: call, deploy initial/follow-on,
  distribute, clawback-repay) are auto-enabled at NO phase — the same
  structural invariant the phase table and the governor's actuation
  gate state independently."
  )

;; --------------------------- combinators ---------------------------

(defn not-flag [a] (if (= a 0) 1 0))
(defn norm-flag
  "Fail-closed flag normalization: only exact 0 counts as 'no'."
  [a]
  (if (= a 0) 0 1))
(defn and2 [a b] (if (= a 1) (if (= b 1) 1 0) 0))
(defn or2 [a b] (if (= a 1) 1 (if (= b 1) 1 0)))
(defn or3 [a b c] (or2 a (or2 b c)))
(defn or5 [a b c d e] (or2 (or3 a b c) (or2 d e)))

;; --------------------------- governor core -------------------------

(def confidence-floor-x100 60)

(defn confidence-low
  "1 when the advisor confidence requires a human look. Out-of-range
  values (negative, or above 100) are treated as LOW — an advisor
  reporting impossible confidence is a reason for MORE scrutiny, not
  auto-commit."
  [x100]
  (if (< x100 0)
    1
    (if (< 100 x100)
      1
      (if (< x100 confidence-floor-x100) 1 0))))

(defn clawback-exceeds
  "1 when a requested GP-clawback repayment exceeds the independently
  recomputed whole-fund entitlement, both in integer micro-units
  (x1,000,000). Strict comparison: repaying EXACTLY the computed
  entitlement is legitimate; one micro-unit more is a HARD violation."
  [amount-micro entitlement-micro]
  (if (< entitlement-micro amount-micro) 1 0))

(defn hard-violation
  "1 when any HARD (human-un-overridable) violation flag is set, in the
  governor's documented priority order: spec-basis missing / sanctions
  hit / DD incomplete / pipeline stage below IC review / illegal stage
  transition / term sheet missing / latest term sheet not fully
  executed / term sheet proposed after commitment / unaffirmed
  (non-accredited) LPs on file (count) / overcalled LPs (count) /
  portfolio report without commitment / follow-on without prior
  commitment / exit-distribution commitment record missing / clawback
  repayment exceeds entitlement / board seat without commitment."
  [spec-missing sanctions-hit dd-incomplete stage-insufficient
   stage-invalid ts-missing ts-unexecuted ts-after-commit
   unaffirmed-lps overcalled-lps report-uncommitted
   follow-on-uncommitted commitment-missing clawback-flag
   board-seat-uncommitted]
  (or3 (or5 (norm-flag spec-missing)
            (norm-flag sanctions-hit)
            (norm-flag dd-incomplete)
            (norm-flag stage-insufficient)
            (norm-flag stage-invalid))
       (or5 (norm-flag ts-missing)
            (norm-flag ts-unexecuted)
            (norm-flag ts-after-commit)
            (norm-flag unaffirmed-lps)
            (norm-flag overcalled-lps))
       (or5 (norm-flag report-uncommitted)
            (norm-flag follow-on-uncommitted)
            (norm-flag commitment-missing)
            (norm-flag clawback-flag)
            (norm-flag board-seat-uncommitted))))

(defn verdict-code
  "Governor verdict: 2 hard-hold wins over 1 escalate wins over 0 ok."
  [spec-missing sanctions-hit dd-incomplete stage-insufficient
   stage-invalid ts-missing ts-unexecuted ts-after-commit
   unaffirmed-lps overcalled-lps report-uncommitted
   follow-on-uncommitted commitment-missing clawback-flag
   board-seat-uncommitted confidence-x100 actuation]
  (if (= 1 (hard-violation spec-missing sanctions-hit dd-incomplete
                           stage-insufficient stage-invalid ts-missing
                           ts-unexecuted ts-after-commit unaffirmed-lps
                           overcalled-lps report-uncommitted
                           follow-on-uncommitted commitment-missing
                           clawback-flag board-seat-uncommitted))
    2
    (if (= 1 (or2 (confidence-low confidence-x100) (norm-flag actuation)))
      1
      0)))

;; ---------------------------- phase core ---------------------------

(defn op-write-enabled
  "1 when `op` may WRITE at `phase` (phase table row, :writes column).
  Phase 3 write-enables every KNOWN write op (codes 1..13); code 14+
  (unknown) and code 0 (read) are never write-enabled anywhere."
  [phase op]
  (if (= phase 1)
    (if (= op 1) 1 0)
    (if (= phase 2)
      (if (= op 1) 1 (if (= op 2) 1 (if (= op 3) 1 0)))
      (if (= phase 3)
        (if (< 0 op) (if (< op 14) 1 0) 0)
        0))))

(defn op-auto-enabled
  "1 when `op` may AUTO-COMMIT at `phase` (phase table row, :auto
  column). Only phase 3 has auto cells, and only for the six
  no-capital-risk ops: 1 :lp/intake, 4 :deal/advance-stage,
  5 :term-sheet/propose, 6 :term-sheet/sign, 10 :portfolio/report,
  13 :governance/board-seat. Ops 7/8/9/11/12 (all four directions of
  real capital movement) are 0 at every phase — permanent structural
  fact, not a rollout milestone."
  [phase op]
  (if (= phase 3)
    (if (= op 1)
      1
      (if (= op 4)
        1
        (if (= op 5)
          1
          (if (= op 6)
            1
            (if (= op 10)
              1
              (if (= op 13) 1 0))))))
    0))

(defn phase-disposition
  "Resolve the final disposition code from phase, op code and the
  governor's disposition code. Mirrors `vcfund.phase/gate`: governor
  hold always wins; reads pass through; a write not enabled at this
  phase holds; a governor-clean write without auto rights escalates;
  otherwise the governor's disposition stands."
  [phase op governor-disposition]
  (if (= governor-disposition 2)
    2
    (if (= op 0)
      governor-disposition
      (if (= 0 (op-write-enabled phase op))
        2
        (if (= governor-disposition 0)
          (if (= 1 (op-auto-enabled phase op)) 0 1)
          governor-disposition)))))

(defn phase-reason
  "Reason code companion of `phase-disposition` (same branch order)."
  [phase op governor-disposition]
  (if (= governor-disposition 2)
    0
    (if (= op 0)
      0
      (if (= 0 (op-write-enabled phase op))
        1
        (if (= governor-disposition 0)
          (if (= 1 (op-auto-enabled phase op)) 0 2)
          0)))))

;; ----------------------------- battery -----------------------------
;; Executable spec, kernels-style: each check returns 1 on pass, the
;; battery sums them, and the test suite locks the sum against
;; `battery-case-count` so a silently-skipped case can't pass review.

(defn check-verdict
  "Battery helper: all 15 hard slots + confidence + actuation, in
  `verdict-code`'s documented order."
  [spec sanc dd stage trans tsm tse tsa acc over rep fol com claw seat
   conf act expected]
  (if (= (verdict-code spec sanc dd stage trans tsm tse tsa acc over
                       rep fol com claw seat conf act)
         expected)
    1
    0))

(defn check-claw [amount-micro entitlement-micro expected]
  (if (= (clawback-exceeds amount-micro entitlement-micro) expected) 1 0))

(defn check-phase [phase op gov expected-disposition expected-reason]
  (and2 (if (= (phase-disposition phase op gov) expected-disposition) 1 0)
        (if (= (phase-reason phase op gov) expected-reason) 1 0)))

(defn check-never-auto [phase op]
  (if (= 0 (op-auto-enabled phase op)) 1 0))

(def battery-case-count 70)

(defn battery-pass-count []
  (+
   ;; -- verdict: clean baseline (conf 100, act 0)
   (check-verdict 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 100 0 0)
   ;; -- verdict: each of the 15 hard flags dominates alone
   (check-verdict 1 0 0 0 0 0 0 0 0 0 0 0 0 0 0 100 0 2)
   (check-verdict 0 1 0 0 0 0 0 0 0 0 0 0 0 0 0 100 0 2)
   (check-verdict 0 0 1 0 0 0 0 0 0 0 0 0 0 0 0 100 0 2)
   (check-verdict 0 0 0 1 0 0 0 0 0 0 0 0 0 0 0 100 0 2)
   (check-verdict 0 0 0 0 1 0 0 0 0 0 0 0 0 0 0 100 0 2)
   (check-verdict 0 0 0 0 0 1 0 0 0 0 0 0 0 0 0 100 0 2)
   (check-verdict 0 0 0 0 0 0 1 0 0 0 0 0 0 0 0 100 0 2)
   (check-verdict 0 0 0 0 0 0 0 1 0 0 0 0 0 0 0 100 0 2)
   (check-verdict 0 0 0 0 0 0 0 0 1 0 0 0 0 0 0 100 0 2)
   (check-verdict 0 0 0 0 0 0 0 0 0 1 0 0 0 0 0 100 0 2)
   (check-verdict 0 0 0 0 0 0 0 0 0 0 1 0 0 0 0 100 0 2)
   (check-verdict 0 0 0 0 0 0 0 0 0 0 0 1 0 0 0 100 0 2)
   (check-verdict 0 0 0 0 0 0 0 0 0 0 0 0 1 0 0 100 0 2)
   (check-verdict 0 0 0 0 0 0 0 0 0 0 0 0 0 1 0 100 0 2)
   (check-verdict 0 0 0 0 0 0 0 0 0 0 0 0 0 0 1 100 0 2)
   ;; -- verdict: hard combos still hold
   (check-verdict 1 1 0 0 0 0 0 0 0 0 0 0 0 1 0 100 0 2)
   (check-verdict 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 100 0 2)
   ;; -- verdict: confidence floor boundary + fail-closed range
   (check-verdict 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 59 0 1)
   (check-verdict 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 60 0 0)
   (check-verdict 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 61 0 0)
   (check-verdict 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 1)
   (check-verdict 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 100 0 0)
   (check-verdict 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 -5 0 1)
   (check-verdict 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 150 0 1)
   ;; -- verdict: actuation always escalates; hard still wins over it
   (check-verdict 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 100 1 1)
   (check-verdict 1 0 0 0 0 0 0 0 0 0 0 0 0 0 0 100 1 2)
   (check-verdict 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 40 1 1)
   ;; -- verdict: non-0/1 flags and raw counts normalize to violation
   (check-verdict 7 0 0 0 0 0 0 0 0 0 0 0 0 0 0 100 0 2)
   (check-verdict 0 0 0 0 0 0 0 0 3 0 0 0 0 0 0 100 0 2)
   (check-verdict 0 0 0 0 0 0 0 0 0 2 0 0 0 0 0 100 0 2)
   (check-verdict 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 100 9 1)
   ;; -- clawback threshold: micro-unit boundary, both directions
   (check-claw 1000000 1000000 0)
   (check-claw 1000001 1000000 1)
   (check-claw 999999 1000000 0)
   (check-claw 0 0 0)
   ;; -- phase: governor hold always wins
   (check-phase 3 8 2 2 0)
   ;; -- phase: reads pass through every governor disposition
   (check-phase 0 0 0 0 0)
   (check-phase 0 0 1 1 0)
   (check-phase 1 0 1 1 0)
   ;; -- phase: write disabled at this phase -> hold, phase-disabled
   (check-phase 0 1 0 2 1)
   (check-phase 1 2 0 2 1)
   (check-phase 2 4 0 2 1)
   (check-phase 3 14 0 2 1)
   ;; -- phase: enabled but not auto -> escalate, phase-approval
   (check-phase 1 1 0 1 2)
   (check-phase 2 2 0 1 2)
   (check-phase 2 3 0 1 2)
   (check-phase 3 2 0 1 2)
   (check-phase 3 3 0 1 2)
   (check-phase 3 7 0 1 2)
   (check-phase 3 8 0 1 2)
   (check-phase 3 9 0 1 2)
   (check-phase 3 11 0 1 2)
   (check-phase 3 12 0 1 2)
   ;; -- phase: the six no-capital-risk auto cells at phase 3
   (check-phase 3 1 0 0 0)
   (check-phase 3 4 0 0 0)
   (check-phase 3 5 0 0 0)
   (check-phase 3 6 0 0 0)
   (check-phase 3 10 0 0 0)
   (check-phase 3 13 0 0 0)
   ;; -- phase: governor escalate passes through an enabled write
   (check-phase 3 8 1 1 0)
   (check-phase 2 1 1 1 0)
   ;; -- phase: out-of-range phases have no writes (fail-closed)
   (check-phase -1 1 0 2 1)
   (check-phase 4 1 0 2 1)
   ;; -- actuation (all four capital directions) + unknown never auto
   (check-never-auto 3 7)
   (check-never-auto 3 8)
   (check-never-auto 3 9)
   (check-never-auto 3 11)
   (check-never-auto 3 12)
   (check-never-auto 3 14)))
