(ns vcfund.phase
  "Phase 0->3 staged rollout -- the venture-fund analog of
  `cloud-itonami-isic-6511`'s `underwriting.phase`: start narrow
  (read-only), widen as trust grows. Where the InvestmentCommitteeGovernor
  answers 'is this allowed?', the phase answers 'how much autonomy does the
  actor have *yet*?'. It can only ever make the actor MORE conservative
  than the governor, never the reverse.

    Phase 0  read-only        -- coverage/checklist reads only (still
                                 governor-gated). Shadow/observe.
    Phase 1  assisted-intake  -- LP subscription intake allowed, every
                                 write needs human approval.
    Phase 2  + dd/screen      -- adds deal DD assessment + KYC screening
                                 writes (still approval).
    Phase 3  supervised auto  -- governor-clean, high-confidence writes with
                                 NO capital risk (LP intake, pipeline-stage
                                 advance, portfolio KPI report) may
                                 auto-commit. DD assessment and KYC
                                 screening still escalate (a human should
                                 see a deal/party determination before it
                                 becomes the basis for a capital call or
                                 commitment).

  `:capital-call/issue` (drawing real committed capital in from LPs),
  `:investment/commit` and `:investment/follow-on` (deploying real fund
  capital, initial or additional), `:exit/distribute` (returning real
  proceeds to LPs) and `:waterfall/clawback-repay` (a GP repaying
  clawed-back carry INTO the fund) are deliberately ABSENT from every
  phase's `:auto` set, including phase 3 -- this is a permanent structural
  fact about this table, not a rollout milestone still to come. All FOUR
  directions of real capital movement are always a human Investment
  Committee call; see README `Actuation`. The InvestmentCommitteeGovernor's
  `:actuation/call`/`:actuation/deploy`/`:actuation/distribute`/
  `:actuation/clawback` high-stakes gate (`vcfund.governor`) enforces the
  same invariant independently -- two layers, not one, agree on this.
  `:deal/advance-stage`, `:term-sheet/propose`, `:term-sheet/sign`,
  `:portfolio/report` and `:governance/board-seat` move no capital
  (governed by HARD checks in `vcfund.governor`, but never `high-stakes`),
  so they ARE auto-eligible at phase 3 -- a deliberately lighter touch
  matching their actual risk.

  The decision core is delegated to the safety kernel
  `vcfund.kernels.gate` (integer-coded, fail-closed, safe-kotoba
  subset); this namespace keeps the human-readable phase table (the
  documentation and structural-invariant tests read it) and does the
  keyword<->wire-code mapping at the boundary. The kernel's own battery
  and the parity matrix in `vcfund.kernels.gate-test` pin the two
  representations together."
  (:require [vcfund.kernels.gate :as kernel]))

(def read-ops  #{:coverage/report})
(def write-ops #{:lp/intake :kyc/screen :dd/assess :deal/advance-stage :term-sheet/propose
                 :term-sheet/sign :capital-call/issue :investment/commit :investment/follow-on
                 :portfolio/report :exit/distribute :waterfall/clawback-repay
                 :governance/board-seat})

;; NOTE the invariant: `:capital-call/issue`, `:investment/commit`,
;; `:investment/follow-on`, `:exit/distribute` and `:waterfall/clawback-
;; repay` are members of `write-ops` (governor-gated like any write) but
;; are NEVER members of any phase's `:auto` set below. Do not add them
;; there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed to
  auto-commit when governor-clean>}."
  {0 {:label "read-only"       :writes #{}                                                       :auto #{}}
   1 {:label "assisted-intake" :writes #{:lp/intake}                                              :auto #{}}
   2 {:label "assisted-dd"     :writes #{:lp/intake :dd/assess :kyc/screen}                       :auto #{}}
   3 {:label "supervised-auto" :writes write-ops
      :auto #{:lp/intake :deal/advance-stage :term-sheet/propose
             :term-sheet/sign :portfolio/report :governance/board-seat}}})

(def default-phase 3)

;; ---- kernel wire-code bridges (façade-side, not kernel vocabulary) ----

(defn- op->code
  "Kernel op wire code. Unknown ops map to 14 (unknown write) — the
  kernel never write-enables it, so an unrecognized op fails closed to
  HOLD exactly as the old set-membership logic did."
  [op]
  (cond
    (contains? read-ops op)          0
    (= op :lp/intake)                1
    (= op :dd/assess)                2
    (= op :kyc/screen)               3
    (= op :deal/advance-stage)       4
    (= op :term-sheet/propose)       5
    (= op :term-sheet/sign)          6
    (= op :capital-call/issue)       7
    (= op :investment/commit)        8
    (= op :investment/follow-on)     9
    (= op :portfolio/report)         10
    (= op :exit/distribute)          11
    (= op :waterfall/clawback-repay) 12
    (= op :governance/board-seat)    13
    :else                            14))

(defn- disposition->code [d]
  (cond (= d :commit) 0 (= d :escalate) 1 (= d :hold) 2 :else 2))

(defn- code->disposition [c]
  (if (= c 0) :commit (if (= c 1) :escalate :hold)))

(defn- code->reason [c]
  (if (= c 1) :phase-disabled (if (= c 2) :phase-approval nil)))

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - reads pass through unchanged (phase restricts autonomy, not reads).
  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE (:phase-approval),
    even if the governor was clean.
  - `:capital-call/issue`/`:investment/commit`/`:investment/follow-on`/
    `:exit/distribute`/`:waterfall/clawback-repay` are never auto-eligible
    at any phase, so they always escalate once the governor clears them
    (or hold if the governor doesn't)."
  [phase {:keys [op]} governor-disposition]
  (let [p (if (contains? phases phase) phase default-phase)
        op-code (op->code op)
        gov-code (disposition->code governor-disposition)
        d (kernel/phase-disposition p op-code gov-code)
        r (kernel/phase-reason p op-code gov-code)]
    {:disposition (code->disposition d)
     :reason (code->reason r)}))

(defn verdict->disposition
  "Map an InvestmentCommitteeGovernor verdict to a base disposition before
  the phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
