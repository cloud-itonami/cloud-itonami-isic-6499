(ns vcfund.governor
  "InvestmentCommitteeGovernor -- the independent compliance layer that
  earns the DD-LLM the right to commit. The LLM has no notion of which
  securities exemption applies, no fiduciary authority over LP capital, and
  no business being the one that decides real capital moves, so this MUST
  be a separate system able to *reject* a proposal and fall back to HOLD --
  the venture-fund analog of `cloud-itonami-isic-6511`'s
  UnderwritingGovernor, `cloud-itonami-M6910`'s RegistrarGovernor and
  `cloud-itonami-L6810`'s RealtorGovernor.

  Sixteen checks, in priority order. The first fifteen are HARD
  violations: a human approver CANNOT override them (you don't get to
  approve your way past a sanctions hit, a fabricated fund-formation
  spec-basis, incomplete DD, an LP missing an accredited-investor
  affirmation, a capital call that would overcall an LP past their
  commitment, an illegal pipeline-stage transition, a commit attempted
  before Investment Committee review, before any term sheet was ever
  proposed, or before the latest term sheet is FULLY EXECUTED (both sides
  signed), a term sheet proposed after capital is already committed, a
  portfolio report or a board-seat event on a deal that was never
  actually committed, a follow-on proposed on a deal with no prior
  commitment, an exit distribution with no commitment record, or a
  GP-clawback repayment that exceeds the independently-recomputed
  whole-fund entitlement). The last is SOFT: it asks a human to look (low
  confidence / actuation), and the human may approve -- but see
  `vcfund.phase`: for `:capital-call/issue`, `:investment/commit`,
  `:investment/follow-on`, `:exit/distribute` and `:waterfall/clawback-
  repay` (real capital movement, in any of the FOUR directions) NO phase
  ever allows auto-commit either. Two independent layers agree that all
  four directions of actuation are always a human call. `:deal/advance-
  stage`, `:term-sheet/propose`, `:term-sheet/sign`, `:portfolio/report`
  and `:governance/board-seat` are NOT actuation (no capital moves) and so
  are NOT high-stakes -- they may auto-commit when clean, a deliberately
  lighter-touch posture matching their actual (low) risk.

    1.  Spec-basis        -- did the DD proposal cite an OFFICIAL
                             fund-formation/exemption source
                             (`vcfund.facts`), or invent one?
    2.  Sanctions hold     -- does a deal's founder/cap-table party carry a
                             sanctions/PEP hit (screened or on file)?
    3.  DD incomplete      -- for an investment commitment, is the deal's
                             required DD checklist actually satisfied?
    4.  Stage insufficient -- for an investment commitment, has the deal
                             actually reached `:ic-review` in the pipeline
                             funnel (`vcfund.pipeline`)? DD data being on
                             file isn't enough if the deal was never
                             formally taken to Investment Committee review.
    5.  Stage transition   -- for `:deal/advance-stage`, is `from`->`to` a
                             legal forward move (`vcfund.pipeline/valid-
                             transition?`), never skipping into
                             `:committed`/`:exited` directly?
    6.  Term sheet missing -- for an investment commitment, has AT LEAST
                             ONE term-sheet round actually been proposed
                             (`vcfund.registry/register-term-sheet`)? No
                             capital moves on a handshake with no term
                             sheet on file, ever.
    7.  Term sheet not executed -- for an investment commitment, is the
                             LATEST term-sheet version FULLY EXECUTED --
                             both `:fund` and `:founder` signed THAT
                             version (`vcfund.registry/fully-executed?`)?
                             A proposed-but-unsigned term sheet is not
                             enough to wire real capital.
    8.  Term sheet after commitment -- for `:term-sheet/propose`, is the
                             deal already `:committed`/`:exited`?
                             Negotiation happens pre-commitment only.
    9.  Accredited investor -- for a capital call, investment commitment or
                             follow-on investment, does every LP in the
                             fund have a recorded accredited-investor/QP
                             affirmation? (fund-wide: the whole vehicle's
                             exemption depends on every investor
                             qualifying, not just the one deal.)
    10. Capital-call overcall -- does the requested call amount, allocated
                             pro-rata by commitment share
                             (`vcfund.registry/capital-call-allocations`),
                             push any LP's cumulative called amount past
                             their commitment? Recomputed independently
                             from store data -- never trust the advisor's
                             self-reported allocation.
    11. Portfolio report requires commitment -- for `:portfolio/report`, is
                             the deal actually `:committed`/`:exited`? A
                             board-report/KPI record on a deal the fund
                             never invested in is fabricated monitoring.
    12. Follow-on requires prior commitment -- for `:investment/follow-on`,
                             is the deal actually `:committed` (an initial
                             tranche already on file, and not already
                             `:exited`)? A follow-on deploys ADDITIONAL
                             capital into a deal the fund already holds --
                             it is never a substitute for the first
                             `:investment/commit`.
    13. Commitment missing -- for `:exit/distribute`, does a commitment
                             record already exist for the referenced deal?
                             You cannot distribute exit proceeds for
                             capital that was never committed.
    14. Clawback exceeds entitlement -- for `:waterfall/clawback-repay`,
                             does the requested repayment amount exceed the
                             INDEPENDENTLY recomputed whole-fund waterfall
                             entitlement (`vcfund.waterfall/whole-fund-
                             waterfall-report`)? Never trust the proposal's
                             self-reported repayment figure.
    15. Board seat requires commitment -- for `:governance/board-seat`, is
                             the deal actually `:committed`/`:exited`? A
                             board-seat grant/revocation on a deal the fund
                             never invested in is fabricated governance
                             administration.
    16. Confidence floor / actuation gate -- LLM confidence below threshold,
                             OR the op is `:capital-call/issue`,
                             `:investment/commit`, `:investment/follow-on`,
                             `:exit/distribute` or `:waterfall/clawback-
                             repay` -> escalate."
  (:require [vcfund.facts :as facts]
            [vcfund.kernels.gate :as gate]
            [vcfund.pipeline :as pipeline]
            [vcfund.registry :as registry]
            [vcfund.store :as store]
            [vcfund.waterfall :as waterfall]))

(def confidence-floor
  "Documented threshold. The DECIDING copy is
  `vcfund.kernels.gate/confidence-floor-x100` (integer x100 in the
  safety kernel); this def is kept for callers/docs and pinned equal by
  `vcfund.kernels.gate-test`."
  0.6)

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  A VC fund moves real capital in FOUR independent directions -- calling
  committed capital in from LPs, deploying it (initial OR follow-on) into
  a portfolio company, returning proceeds to LPs, and a GP repaying
  clawed-back carry back INTO the fund -- so, unlike the life-insurance
  template (exactly one actuation stake), this set has four members on
  purpose: none is a gradation of the others, all four are absolute.
  `:investment/follow-on` deliberately reuses `:actuation/deploy` (same
  direction of capital travel as an initial commitment, not a new stake);
  `:actuation/clawback` is its own stake because it is the one direction
  where capital flows FROM the GP INTO the fund, the mirror image of every
  other actuation here."
  #{:actuation/call :actuation/deploy :actuation/distribute :actuation/clawback})

(defn- confidence->x100
  "Host bridge (façade-side, not kernel vocabulary): scale a 0.0..1.0
  advisor confidence to the kernel's integer x100 wire code."
  [c]
  (Math/round (* 100.0 (double c))))

(defn- amount->micro
  "Host bridge (façade-side, not kernel vocabulary): scale a capital
  amount (fund base currency) to the kernel's integer micro-unit
  (x1,000,000) wire code for the clawback threshold comparison.
  Micro-unit rounding replaces the old inline 1e-6 float epsilon."
  [x]
  (Math/round (* 1000000.0 (double x))))

;; ----------------------------- checks -----------------------------

(defn- spec-basis-violations
  "A `:dd/assess` (or `:investment/commit`) proposal with no spec-basis
  citation is a HARD violation -- never invent a jurisdiction's
  fund-formation/exemption requirements."
  [{:keys [op]} proposal]
  (when (contains? #{:dd/assess :investment/commit} op)
    (let [value (:value proposal)]
      (when (or (empty? (:cites proposal))
                (and (contains? value :spec-basis) (nil? (:spec-basis value))))
        [{:rule :no-spec-basis
          :detail "公式spec-basisの引用が無い提案はfund-formation要件として扱えない"}]))))

(defn- sanctions-violations
  "A sanctions/PEP hit on any deal-related party -- screened in THIS
  proposal or already on file in the store -- is a HARD, un-overridable
  hold."
  [{:keys [op subject]} proposal st]
  (let [hit-in-proposal? (= :hit (get-in proposal [:value :verdict]))
        party-ids (when (= op :investment/commit)
                    (:founders (store/deal st subject)))
        hit-on-file? (some #(= :hit (:verdict (store/kyc-of st %))) party-ids)]
    (when (or hit-in-proposal? hit-on-file?)
      [{:rule :sanctions-hit
        :detail "制裁/PEPリスト一致のある関係者を含む案件は進められない"}])))

(defn- dd-incomplete-violations
  "For `:investment/commit`, the deal jurisdiction's required DD checklist
  must actually be satisfied -- do not trust the advisor's self-reported
  confidence alone."
  [{:keys [op subject]} st]
  (when (= op :investment/commit)
    (let [d (store/deal st subject)
          assessment (store/assessment-of st subject)]
      (when-not (and assessment
                     (facts/required-docs-satisfied?
                      (:jurisdiction d) (:checklist assessment)))
        [{:rule :dd-incomplete
          :detail "案件の必要DD書類が充足していない状態での投資実行提案"}]))))

(defn- stage-insufficient-violations
  "For `:investment/commit`, the deal must have actually progressed
  through the pipeline funnel to at least `:ic-review` -- DD checklist
  data being on file (checked separately by `dd-incomplete-violations`)
  isn't enough if the deal was never formally taken to Investment
  Committee review as a pipeline stage."
  [{:keys [op subject]} st]
  (when (= op :investment/commit)
    (let [d (store/deal st subject)]
      (when-not (pipeline/at-least? (:status d) :ic-review)
        [{:rule :stage-insufficient
          :detail "案件がInvestment Committeeレビュー段階に到達していない状態での投資実行提案"}]))))

(defn- stage-transition-violations
  "For `:deal/advance-stage`, the transition must be a legal forward move
  (`vcfund.pipeline/valid-transition?`) -- never skip stages backward,
  never jump straight into `:committed`/`:exited` (those are set only by
  the real capital-moving ops)."
  [{:keys [op subject to-stage]} st]
  (when (= op :deal/advance-stage)
    (let [d (store/deal st subject)]
      (when-not (pipeline/valid-transition? (:status d) to-stage)
        [{:rule :invalid-stage-transition
          :detail (str (:status d) " から " to-stage " への遷移は許可されていない")}]))))

(defn- portfolio-report-requires-commitment-violations
  "For `:portfolio/report`, the deal must actually be `:committed` or
  `:exited` -- a board-report/KPI record on a deal the fund never invested
  in would be fabricated monitoring, not real portfolio oversight."
  [{:keys [op subject]} st]
  (when (= op :portfolio/report)
    (let [status (:status (store/deal st subject))]
      (when-not (contains? #{:committed :exited} status)
        [{:rule :portfolio-report-without-commitment
          :detail "投資実行(commit)されていない案件へのポートフォリオレポート提案"}]))))

(defn- board-seat-requires-commitment-violations
  "For `:governance/board-seat`, the deal must actually be `:committed` or
  `:exited` -- a board-seat grant/revocation on a deal the fund never
  invested in would be fabricated governance administration, the same
  posture `portfolio-report-requires-commitment-violations` takes on KPI
  reports."
  [{:keys [op subject]} st]
  (when (= op :governance/board-seat)
    (let [status (:status (store/deal st subject))]
      (when-not (contains? #{:committed :exited} status)
        [{:rule :board-seat-without-commitment
          :detail "投資実行(commit)されていない案件へのboard seat付与/剥奪提案"}]))))

(defn- term-sheet-missing-violations
  "For `:investment/commit`, at least one term-sheet round must actually
  have been proposed for the deal -- capital never moves on a handshake
  with no term sheet on file, even if DD is complete and the deal reached
  IC review."
  [{:keys [op subject]} st]
  (when (= op :investment/commit)
    (when (empty? (store/term-sheet-history-of st subject))
      [{:rule :term-sheet-missing
        :detail "term sheetが一度も提案されていない状態での投資実行提案"}])))

(defn- term-sheet-not-executed-violations
  "For `:investment/commit`, the LATEST term-sheet version must be FULLY
  EXECUTED -- both `:fund` and `:founder` have signed THAT version
  (`vcfund.registry/fully-executed?`, checked against the deal's own
  signature history, never trusting a caller-supplied claim). This check
  only fires once at least one term sheet exists -- `term-sheet-missing-
  violations` already handles the zero-proposals case, so this function
  stays silent then rather than duplicating that violation."
  [{:keys [op subject]} st]
  (when (= op :investment/commit)
    (let [history (store/term-sheet-history-of st subject)]
      (when (seq history)
        (let [latest-version (get (last history) "version")
              signatures (store/signature-history-of st subject)]
          (when-not (registry/fully-executed? signatures latest-version)
            [{:rule :term-sheet-not-executed
              :detail "最新term sheetバージョンが双方署名済みでない状態での投資実行提案"}]))))))

(defn- term-sheet-after-commitment-violations
  "For `:term-sheet/propose`, the deal must NOT already be `:committed`/
  `:exited` -- term-sheet negotiation is a pre-commitment activity; once
  capital moves, renegotiating terms is a different act (a follow-on round
  or an amendment), not a plain term-sheet proposal."
  [{:keys [op subject]} st]
  (when (= op :term-sheet/propose)
    (let [status (:status (store/deal st subject))]
      (when (contains? #{:committed :exited} status)
        [{:rule :term-sheet-after-commitment
          :detail "投資実行(commit)済みの案件へのterm sheet提案は不可"}]))))

(defn- accredited-investor-violations
  "For a capital call, an investment commitment or a follow-on investment,
  EVERY LP in the fund must have a recorded accredited-investor/qualified-
  purchaser affirmation -- not just the LPs tied to this one deal. The
  fund's securities exemption (Reg D 506(b)/(c), Investment Company Act
  §3(c)(1)/§3(c)(7)) is a fund-wide fact: a single unaccredited LP on file
  taints every subsequent capital call or deployment, not just their own
  allocation."
  [{:keys [op]} st]
  (when (contains? #{:capital-call/issue :investment/commit :investment/follow-on} op)
    (let [unaffirmed (remove :accredited? (store/all-lps st))]
      (when (seq unaffirmed)
        [{:rule :accredited-investor-violation
          :detail (str "適格投資家確認が未了のLPが" (count unaffirmed) "件存在するため実行不可")}]))))

(defn- overcall-violations
  "For `:capital-call/issue`, recompute the pro-rata allocation
  independently from store data (`vcfund.registry/capital-call-allocations`)
  -- never trust the advisor's self-reported allocation. If any LP's
  cumulative called amount would exceed their commitment, HOLD."
  [{:keys [op call-amount]} st]
  (when (= op :capital-call/issue)
    (let [allocations (registry/capital-call-allocations (store/all-lps st) call-amount)
          overcalled (filter :overcall? allocations)]
      (when (seq overcalled)
        [{:rule :capital-call-overcall
          :detail (str (count overcalled) "件のLPでコミットメント額を超過するコールとなるため発行不可")}]))))

(defn- commitment-missing-violations
  "For `:exit/distribute`, a commitment record must already exist for the
  referenced deal -- you cannot distribute exit proceeds for capital that
  was never committed."
  [{:keys [op subject]} st]
  (when (= op :exit/distribute)
    (when-not (store/commitment-of st subject)
      [{:rule :commitment-missing
        :detail "対応するinvestment commitmentレコードが無い状態でのexit分配提案"}])))

(defn- follow-on-requires-prior-commitment-violations
  "For `:investment/follow-on`, the deal must actually be `:committed` --
  a follow-on deploys ADDITIONAL capital into a deal the fund ALREADY
  holds an initial commitment in. It is never a substitute for the first
  `:investment/commit` (deal still `:sourced`/etc.), and it must never
  fire again once the deal has fully `:exited`."
  [{:keys [op subject]} st]
  (when (= op :investment/follow-on)
    (let [status (:status (store/deal st subject))]
      (when-not (= status :committed)
        [{:rule :follow-on-requires-prior-commitment
          :detail "既存commitmentが無い、または既にexit済みの案件へのfollow-on投資提案"}]))))

(defn- clawback-exceeds-entitlement-violations
  "For `:waterfall/clawback-repay`, recompute the whole-fund waterfall
  reconciliation INDEPENDENTLY from live store data
  (`vcfund.waterfall/whole-fund-waterfall-report`) -- never trust the
  proposal's self-reported repayment amount. The requested repayment must
  not exceed the actually-computed `:gp-clawback` the GP owes the fund.
  The comparison itself is decided by the safety kernel
  (`vcfund.kernels.gate/clawback-exceeds`, strict scaled-integer
  comparison at micro-unit granularity); this façade recomputes the
  entitlement and scales both sides to the kernel's wire code."
  [{:keys [op amount fund-life-years]} st]
  (when (= op :waterfall/clawback-repay)
    (let [{:keys [gp-clawback]} (waterfall/whole-fund-waterfall-report st fund-life-years)]
      (when (= 1 (gate/clawback-exceeds (amount->micro amount)
                                        (amount->micro gp-clawback)))
        [{:rule :clawback-exceeds-entitlement
          :detail (str "要求返金額" amount "が算定whole-fund clawback額" gp-clawback "を超過")}]))))

(defn check
  "Censors a DD-LLM proposal against the governor rules. Returns
   {:ok? bool :violations [..] :confidence c :escalate? bool :high-stakes? bool
    :hard? bool}.

   - :hard?       -- at least one HARD violation. Forces HOLD; a human
                    cannot override.
   - :escalate?   -- soft: low confidence OR actuation (either direction).
                    A human decides.
   - :ok?         -- clean AND not escalating: safe to auto-commit."
  [request _context proposal st]
  (let [spec-v (spec-basis-violations request proposal)
        sanc-v (sanctions-violations request proposal st)
        dd-v   (dd-incomplete-violations request st)
        stag-v (stage-insufficient-violations request st)
        tran-v (stage-transition-violations request st)
        tsm-v  (term-sheet-missing-violations request st)
        tse-v  (term-sheet-not-executed-violations request st)
        tsa-v  (term-sheet-after-commitment-violations request st)
        acc-v  (accredited-investor-violations request st)
        over-v (overcall-violations request st)
        rep-v  (portfolio-report-requires-commitment-violations request st)
        fol-v  (follow-on-requires-prior-commitment-violations request st)
        com-v  (commitment-missing-violations request st)
        claw-v (clawback-exceeds-entitlement-violations request st)
        seat-v (board-seat-requires-commitment-violations request st)
        hard (into [] (concat spec-v sanc-v dd-v stag-v tran-v tsm-v tse-v
                              tsa-v acc-v over-v rep-v fol-v com-v claw-v
                              seat-v))
        conf (:confidence proposal 0.0)
        stakes? (boolean (high-stakes (:stake proposal)))
        flag (fn [v] (if (seq v) 1 0))
        ;; The decision itself is delegated to the safety kernel
        ;; (vcfund.kernels.gate, integer-coded fail-closed core); this
        ;; façade only gathers evidence (violation lists with
        ;; human-readable details) and maps codes back to keywords.
        ;; Kernel is stricter than the old inline logic on ONE case by
        ;; design: an out-of-range confidence (< 0 or > 1.0) now
        ;; escalates instead of counting as high confidence.
        code (gate/verdict-code (flag spec-v) (flag sanc-v) (flag dd-v)
                                (flag stag-v) (flag tran-v) (flag tsm-v)
                                (flag tse-v) (flag tsa-v) (flag acc-v)
                                (flag over-v) (flag rep-v) (flag fol-v)
                                (flag com-v) (flag claw-v) (flag seat-v)
                                (confidence->x100 conf)
                                (if stakes? 1 0))]
    {:ok?          (= 0 code)
     :violations   hard
     :confidence   conf
     :hard?        (= 2 code)
     :escalate?    (= 1 code)
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
