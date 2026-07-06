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
  `:investment/commit` (deploying real fund capital) and
  `:exit/distribute` (returning real proceeds to LPs) are deliberately
  ABSENT from every phase's `:auto` set, including phase 3 -- this is a
  permanent structural fact about this table, not a rollout milestone still
  to come. All three directions of real capital movement are always a
  human Investment Committee call; see README `Actuation`. The
  InvestmentCommitteeGovernor's `:actuation/call`/`:actuation/deploy`/
  `:actuation/distribute` high-stakes gate (`vcfund.governor`) enforces the
  same invariant independently -- two layers, not one, agree on this.
  `:deal/advance-stage`, `:term-sheet/propose`, `:term-sheet/sign` and
  `:portfolio/report` move no capital (governed by HARD checks in
  `vcfund.governor`, but never `high-stakes`), so they ARE auto-eligible at
  phase 3 -- a deliberately lighter touch matching their actual risk.")

(def read-ops  #{:coverage/report})
(def write-ops #{:lp/intake :kyc/screen :dd/assess :deal/advance-stage :term-sheet/propose
                 :term-sheet/sign :capital-call/issue :investment/commit
                 :portfolio/report :exit/distribute})

;; NOTE the invariant: `:capital-call/issue`, `:investment/commit` and
;; `:exit/distribute` are members of `write-ops` (governor-gated like any
;; write) but are NEVER members of any phase's `:auto` set below. Do not
;; add them there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed to
  auto-commit when governor-clean>}."
  {0 {:label "read-only"       :writes #{}                                                       :auto #{}}
   1 {:label "assisted-intake" :writes #{:lp/intake}                                              :auto #{}}
   2 {:label "assisted-dd"     :writes #{:lp/intake :dd/assess :kyc/screen}                       :auto #{}}
   3 {:label "supervised-auto" :writes write-ops
      :auto #{:lp/intake :deal/advance-stage :term-sheet/propose
             :term-sheet/sign :portfolio/report}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - reads pass through unchanged (phase restricts autonomy, not reads).
  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE (:phase-approval),
    even if the governor was clean.
  - `:capital-call/issue`/`:investment/commit`/`:exit/distribute` are never
    auto-eligible at any phase, so they always escalate once the governor
    clears them (or hold if the governor doesn't)."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (contains? read-ops op)              {:disposition governor-disposition :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map an InvestmentCommitteeGovernor verdict to a base disposition before
  the phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
