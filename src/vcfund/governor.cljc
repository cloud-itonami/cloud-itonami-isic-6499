(ns vcfund.governor
  "InvestmentCommitteeGovernor -- the independent compliance layer that
  earns the DD-LLM the right to commit. The LLM has no notion of which
  securities exemption applies, no fiduciary authority over LP capital, and
  no business being the one that decides real capital moves, so this MUST
  be a separate system able to *reject* a proposal and fall back to HOLD --
  the venture-fund analog of `cloud-itonami-isic-6511`'s
  UnderwritingGovernor, `cloud-itonami-M6910`'s RegistrarGovernor and
  `cloud-itonami-L6810`'s RealtorGovernor.

  Five checks, in priority order. The first four are HARD violations: a
  human approver CANNOT override them (you don't get to approve your way
  past a sanctions hit, a fabricated fund-formation spec-basis, incomplete
  DD, or an LP missing an accredited-investor affirmation). The last is
  SOFT: it asks a human to look (low confidence / actuation), and the human
  may approve -- but see `vcfund.phase`: for `:investment/commit` and
  `:exit/distribute` (real capital movement, in either direction) NO phase
  ever allows auto-commit either. Two independent layers agree that both
  directions of actuation are always a human call.

    1. Spec-basis        -- did the DD proposal cite an OFFICIAL
                             fund-formation/exemption source
                             (`vcfund.facts`), or invent one?
    2. Sanctions hold     -- does a deal's founder/cap-table party carry a
                             sanctions/PEP hit (screened or on file)?
    3. DD incomplete      -- for an investment commitment, is the deal's
                             required DD checklist actually satisfied?
    4. Accredited investor -- for an investment commitment, does every LP
                             in the fund have a recorded accredited-
                             investor/QP affirmation? (fund-wide: the whole
                             vehicle's exemption depends on every investor
                             qualifying, not just the one deal.)
    5. Confidence floor / actuation gate -- LLM confidence below threshold,
                             OR the op is `:investment/commit` /
                             `:exit/distribute` -> escalate."
  (:require [vcfund.facts :as facts]
            [vcfund.store :as store]))

(def confidence-floor 0.6)

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  A VC fund moves real capital in TWO independent directions -- deploying
  it into a portfolio company, and returning proceeds to LPs -- so, unlike
  the life-insurance template (exactly one actuation stake), this set has
  two members on purpose: neither is a gradation of the other, both are
  absolute."
  #{:actuation/deploy :actuation/distribute})

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

(defn- accredited-investor-violations
  "For `:investment/commit`, EVERY LP in the fund must have a recorded
  accredited-investor/qualified-purchaser affirmation -- not just the LPs
  tied to this one deal. The fund's securities exemption (Reg D 506(b)/(c),
  Investment Company Act §3(c)(1)/§3(c)(7)) is a fund-wide fact: a single
  unaccredited LP on file taints every subsequent capital deployment, not
  just their own allocation."
  [{:keys [op]} st]
  (when (= op :investment/commit)
    (let [unaffirmed (remove :accredited? (store/all-lps st))]
      (when (seq unaffirmed)
        [{:rule :accredited-investor-violation
          :detail (str "適格投資家確認が未了のLPが" (count unaffirmed) "件存在するため投資実行不可")}]))))

(defn- commitment-missing-violations
  "For `:exit/distribute`, a commitment record must already exist for the
  referenced deal -- you cannot distribute exit proceeds for capital that
  was never committed."
  [{:keys [op subject]} st]
  (when (= op :exit/distribute)
    (when-not (store/commitment-of st subject)
      [{:rule :commitment-missing
        :detail "対応するinvestment commitmentレコードが無い状態でのexit分配提案"}])))

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
  (let [hard (into []
                   (concat (spec-basis-violations request proposal)
                           (sanctions-violations request proposal st)
                           (dd-incomplete-violations request st)
                           (accredited-investor-violations request st)
                           (commitment-missing-violations request st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
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
