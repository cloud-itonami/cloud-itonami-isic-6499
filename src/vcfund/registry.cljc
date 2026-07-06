(ns vcfund.registry
  "Pure-function capital-call, investment-commitment (initial AND
  follow-on), exit-distribution, GP-clawback-repayment and portfolio-report
  record construction -- an append-only venture-fund
  capital-movement/monitoring draft.

  Like `cloud-itonami-isic-6511`'s `underwriting.registry`, there is no
  single international identifier standard for a fund's investment-
  commitment record -- every fund/administrator assigns its own reference
  format. This namespace does NOT invent one; it builds a fund-scoped
  sequence number and validates the record's required fields, the same
  honest, non-fabricating discipline `vcfund.facts` uses.

  This namespace is pure data + pure functions -- no I/O, no network call
  to any fund-accounting or cap-table system. It builds the RECORD an
  operator would keep, not the act of committing capital or distributing
  proceeds itself (that is `vcfund.operation`'s `:investment/commit` and
  `:exit/distribute`, which are always human-gated -- see README
  `Actuation`)."
  (:require [clojure.string :as str]))

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is the
  Investment Committee's act, not this actor's. See README `Actuation`."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn register-commitment
  "Validate + construct an investment-commitment registration DRAFT --
  committing fund capital into a portfolio company. Pure function -- does
  not touch any real fund-accounting/cap-table system or wire any real
  capital."
  [portfolio-company security-type amount jurisdiction sequence]
  (when-not (and portfolio-company (not= portfolio-company ""))
    (throw (ex-info "commitment: portfolio-company required" {})))
  (when-not (contains? #{:safe :convertible-note :priced-equity :saft} security-type)
    (throw (ex-info "commitment: security-type must be :safe, :convertible-note, :priced-equity or :saft" {})))
  (when (< amount 0)
    (throw (ex-info "commitment: amount must be >= 0" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "commitment: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "commitment: sequence must be >= 0" {})))
  (let [commitment-number (str (str/upper-case jurisdiction) "-" (zero-pad sequence 8))
        record {"record_id" commitment-number
                "kind" "commitment-draft"
                "portfolio_company" portfolio-company
                "security_type" (name security-type)
                "amount" amount
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "commitment_number" commitment-number
     "certificate" (unsigned-certificate "InvestmentCommitmentCertificate" commitment-number commitment-number)}))

(defn register-follow-on-commitment
  "Validate + construct a FOLLOW-ON investment-commitment registration
  DRAFT -- deploying additional fund capital into a portfolio company the
  fund ALREADY holds an initial commitment in (a later round, exercising
  pro-rata or otherwise). Distinct from `register-commitment`: it
  references `original-commitment-number` so the audit trail links every
  tranche back to the deal's first investment, rather than looking like
  an unrelated new commitment. Pure function -- does not touch any real
  fund-accounting/cap-table system or wire any real capital."
  [portfolio-company original-commitment-number security-type amount jurisdiction sequence]
  (when-not (and portfolio-company (not= portfolio-company ""))
    (throw (ex-info "follow-on: portfolio-company required" {})))
  (when-not (and original-commitment-number (not= original-commitment-number ""))
    (throw (ex-info "follow-on: original-commitment-number required" {})))
  (when-not (contains? #{:safe :convertible-note :priced-equity :saft} security-type)
    (throw (ex-info "follow-on: security-type must be :safe, :convertible-note, :priced-equity or :saft" {})))
  (when (< amount 0)
    (throw (ex-info "follow-on: amount must be >= 0" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "follow-on: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "follow-on: sequence must be >= 0" {})))
  (let [follow-on-number (str (str/upper-case jurisdiction) "-FOLLOWON-" (zero-pad sequence 8))
        record {"record_id" follow-on-number
                "kind" "follow-on-commitment-draft"
                "portfolio_company" portfolio-company
                "original_commitment_number" original-commitment-number
                "security_type" (name security-type)
                "amount" amount
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "follow_on_number" follow-on-number
     "certificate" (unsigned-certificate "FollowOnCommitmentCertificate" follow-on-number follow-on-number)}))

(def default-notice-period-days
  "Standard capital-call notice period -- an LP must fund a call within
  this many days of the notice date. 10 business days is a common market
  default; a real fund cites its own LPA's actual notice-period clause."
  10)

(defn capital-call-allocations
  "Pure pro-rata capital-call allocation across LPs, by commitment share.
  Never mutates any LP record -- the store applies each
  `:new-called-amount` when the call is actually issued (governor-gated,
  see `vcfund.governor/overcall-violations`).

  `lps` -- coll of `{:id .. :commitment-amount .. :called-amount ..}`
  (`:called-amount` defaults to 0 if absent -- an LP not yet called from).
  `call-amount` -- the total amount the fund needs to draw down for this
  call, before pro-rata split.

  Returns one map per LP: `{:lp-id :commitment-amount :called-amount
  :allocation :new-called-amount :overcall?}`. `:overcall?` true means
  this LP's cumulative called amount would exceed their commitment -- a
  HARD violation, never silently capped or dropped."
  [lps call-amount]
  (when (neg? call-amount) (throw (ex-info "capital-call: call-amount must be >= 0" {})))
  (when (empty? lps) (throw (ex-info "capital-call: no LP commitments on file" {})))
  (let [total-committed (reduce + (map :commitment-amount lps))]
    (when (zero? total-committed)
      (throw (ex-info "capital-call: total LP commitment is zero" {})))
    (mapv (fn [{:keys [id commitment-amount called-amount]}]
            (let [commitment-amount (double commitment-amount)
                  called-amount (double (or called-amount 0))
                  share (/ commitment-amount (double total-committed))
                  allocation (* share (double call-amount))
                  new-called (+ called-amount allocation)]
              {:lp-id id
               :commitment-amount commitment-amount
               :called-amount called-amount
               :allocation allocation
               :new-called-amount new-called
               :overcall? (> new-called commitment-amount)}))
          lps)))

(defn register-capital-call
  "Validate + construct a capital-call notice DRAFT -- drawing committed
  capital in from LPs, pro-rata by commitment share. Pure function -- does
  not touch any real banking/wire system or move any real capital; it
  builds the RECORD an operator would keep and send to LPs."
  [allocations call-amount jurisdiction sequence notice-date]
  (when (empty? allocations)
    (throw (ex-info "capital-call: allocations required" {})))
  (when (neg? call-amount)
    (throw (ex-info "capital-call: call-amount must be >= 0" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "capital-call: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "capital-call: sequence must be >= 0" {})))
  (when-not (and notice-date (not= notice-date ""))
    (throw (ex-info "capital-call: notice-date required" {})))
  (let [call-number (str (str/upper-case jurisdiction) "-CALL-" (zero-pad sequence 6))
        record {"record_id" call-number
                "kind" "capital-call-draft"
                "call_amount" (double call-amount)
                "allocations" (mapv (fn [{:keys [lp-id allocation new-called-amount]}]
                                      {"lp_id" lp-id "allocation" allocation
                                       "new_called_amount" new-called-amount})
                                    allocations)
                "notice_date" notice-date
                "funding_due_days" default-notice-period-days
                "immutable" true}]
    {"record" record "call_number" call-number
     "certificate" (unsigned-certificate "CapitalCallCertificate" call-number call-number)}))

(defn distribute-waterfall
  "Compute a DEAL-BY-DEAL exit waterfall for ONE investment record --
  return-of-capital -> simple (non-compounded) preferred return -> GP carry
  split on the remainder. This is NOT a whole-fund European waterfall: it
  does not net across other investments in the fund and has no GP
  clawback. A real whole-fund waterfall needs fund-level state spanning
  every investment, which is out of scope for a single investment record
  -- see docs/adr/0001-architecture.md. Never silently claim this is a
  whole-fund calculation.

  `contributed-capital`/`exit-proceeds` -- amounts in the fund's base
  currency.
  `preferred-return-rate` -- annual simple rate, e.g. 0.08 for 8%.
  `holding-period-years` -- years capital was outstanding for this deal.
  `carry-rate` -- GP carried-interest share of profit above
  return-of-capital + preferred return, e.g. 0.20 for a standard 20% carry."
  [{:keys [contributed-capital exit-proceeds preferred-return-rate
           holding-period-years carry-rate]}]
  (when (neg? contributed-capital) (throw (ex-info "waterfall: contributed-capital must be >= 0" {})))
  (when (neg? exit-proceeds) (throw (ex-info "waterfall: exit-proceeds must be >= 0" {})))
  (when (neg? preferred-return-rate) (throw (ex-info "waterfall: preferred-return-rate must be >= 0" {})))
  (when (neg? holding-period-years) (throw (ex-info "waterfall: holding-period-years must be >= 0" {})))
  (when-not (<= 0 carry-rate 1) (throw (ex-info "waterfall: carry-rate must be in [0,1]" {})))
  (let [contributed-capital (double contributed-capital)
        exit-proceeds       (double exit-proceeds)
        return-of-capital   (min exit-proceeds contributed-capital)
        after-roc           (max 0.0 (- exit-proceeds return-of-capital))
        preferred-due       (* contributed-capital (double preferred-return-rate) (double holding-period-years))
        preferred-paid      (min after-roc preferred-due)
        after-preferred     (max 0.0 (- after-roc preferred-paid))
        gp-carry            (* after-preferred (double carry-rate))
        lp-residual-profit  (- after-preferred gp-carry)]
    {:model                :deal-by-deal-simple-preferred
     :return-of-capital    return-of-capital
     :preferred-return-due preferred-due
     :preferred-return-paid preferred-paid
     :gp-carry             gp-carry
     :lp-residual-profit   lp-residual-profit
     :total-to-lp          (+ return-of-capital preferred-paid lp-residual-profit)
     :total-to-gp          gp-carry}))

(defn register-distribution
  "Validate + construct an exit-distribution registration DRAFT from a
  `distribute-waterfall` result. Pure function -- does not move any real
  capital."
  [commitment-number waterfall effective-date]
  (when-not (and commitment-number (not= commitment-number ""))
    (throw (ex-info "distribution: commitment_number required" {})))
  (when-not (and waterfall (:model waterfall))
    (throw (ex-info "distribution: waterfall result required" {})))
  (let [record-id (str commitment-number "#exit@" effective-date)]
    {"record" {"record_id" record-id
              "kind" "distribution-draft"
              "commitment_number" commitment-number
              "waterfall" waterfall
              "effective_date" effective-date
              "immutable" true}
     "certificate" (unsigned-certificate "ExitDistributionCertificate" commitment-number record-id)}))

(defn register-portfolio-report
  "Validate + construct a portfolio-monitoring KPI report DRAFT for a
  committed deal -- board-reporting-style facts (revenue, burn, runway,
  headcount, whatever `kpis` the operator's board-report template asks
  for) as of a given `period`. Pure function -- `kpis` are real facts the
  operator supplies from the actual board deck/data room, never invented
  here. No certificate: this is an internal monitoring record, not a
  legal instrument."
  [deal-id period kpis]
  (when-not (and deal-id (not= deal-id ""))
    (throw (ex-info "portfolio-report: deal-id required" {})))
  (when-not (and period (not= period ""))
    (throw (ex-info "portfolio-report: period required" {})))
  (when-not (map? kpis)
    (throw (ex-info "portfolio-report: kpis must be a map" {})))
  {"record" {"record_id" (str deal-id "#report@" period)
            "kind" "portfolio-report"
            "deal_id" deal-id
            "period" period
            "kpis" kpis
            "immutable" true}})

(defn register-term-sheet
  "Validate + construct a versioned term-sheet DRAFT record -- one round of
  a deal's pre-commitment negotiation. `terms` is whatever fields the
  operator's term-sheet template asks for (valuation, security type,
  pro-rata rights, board seat, liquidation-preference multiple, ...) --
  never invented here, always the real terms on the table. `version`
  advances one per round (0-indexed, one call per proposal/counter);
  negotiation history is append-only, never overwriting a prior version.
  No certificate: a term sheet is non-binding by convention, not a legal
  instrument this actor issues."
  [deal-id proposed-by terms version]
  (when-not (and deal-id (not= deal-id ""))
    (throw (ex-info "term-sheet: deal-id required" {})))
  (when-not (contains? #{:fund :founder} proposed-by)
    (throw (ex-info "term-sheet: proposed-by must be :fund or :founder" {})))
  (when-not (map? terms)
    (throw (ex-info "term-sheet: terms must be a map" {})))
  (when (< version 0)
    (throw (ex-info "term-sheet: version must be >= 0" {})))
  {"record" {"record_id" (str deal-id "#term-sheet-v" version)
            "kind" "term-sheet-draft"
            "deal_id" deal-id
            "version" version
            "proposed_by" (name proposed-by)
            "terms" terms
            "immutable" true}})

(defn term-sheet-diff
  "Field-level redline between two term-sheet rounds' `terms` maps --
  what's new, what's gone, what changed. Pure, read-only; never mutates
  either version. A key present in both with the same value is silently
  omitted (unchanged terms aren't a redline)."
  [prev-terms next-terms]
  (when-not (map? prev-terms) (throw (ex-info "term-sheet-diff: prev-terms must be a map" {})))
  (when-not (map? next-terms) (throw (ex-info "term-sheet-diff: next-terms must be a map" {})))
  (let [all-keys (into (set (keys prev-terms)) (keys next-terms))]
    (reduce (fn [acc k]
              (let [absent ::absent
                    pv (get prev-terms k absent)
                    nv (get next-terms k absent)]
                (cond
                  (= pv nv)      acc
                  (= pv absent)  (assoc-in acc [:added k] nv)
                  (= nv absent)  (assoc-in acc [:removed k] pv)
                  :else          (assoc-in acc [:changed k] {:from pv :to nv}))))
            {:added {} :removed {} :changed {}}
            all-keys)))

(defn register-term-sheet-signature
  "Validate + construct a term-sheet e-signature DRAFT record -- one
  party's signature against a SPECIFIC term-sheet version (never the
  latest-at-signing-time implicitly; a stale-version signature is a
  distinct failure mode `vcfund.governor` checks for). Append-only,
  parallel to the term-sheet history itself: signing never mutates or
  replaces the term-sheet record it signs. No certificate: like the term
  sheet itself, this is process state this actor tracks, not a legal
  instrument it issues -- a real e-signature (DocuSign, on-chain
  signature, wet-ink scan) is the actual legal act; this is the audit
  record of it having happened."
  [deal-id version signed-by]
  (when-not (and deal-id (not= deal-id ""))
    (throw (ex-info "term-sheet-signature: deal-id required" {})))
  (when (< version 0)
    (throw (ex-info "term-sheet-signature: version must be >= 0" {})))
  (when-not (contains? #{:fund :founder} signed-by)
    (throw (ex-info "term-sheet-signature: signed-by must be :fund or :founder" {})))
  {"record" {"record_id" (str deal-id "#term-sheet-v" version "#signed-by-" (name signed-by))
            "kind" "term-sheet-signature"
            "deal_id" deal-id
            "version" version
            "signed_by" (name signed-by)
            "immutable" true}})

(defn fully-executed?
  "Is term-sheet `version` fully executed -- BOTH :fund and :founder have
  signed THAT SPECIFIC version (not some earlier or later one)?
  `signatures` -- the deal's append-only signature history (`vcfund.store/
  signature-history-of`)."
  [signatures version]
  (let [signers (into #{}
                      (comp (filter #(= version (get % "version")))
                            (map #(get % "signed_by")))
                      signatures)]
    (and (contains? signers "fund") (contains? signers "founder"))))

(defn register-clawback-repayment
  "Validate + construct a GP-clawback repayment DRAFT -- the GP actually
  returning capital to the fund that `vcfund.waterfall/whole-fund-
  waterfall` determined they were paid, deal-by-deal, in excess of the
  fund's aggregate (whole-fund) entitlement. Fund-level, not deal-scoped
  (a clawback reconciles the WHOLE fund's carry history, not one deal).
  Pure function -- does not move any real capital; it builds the RECORD
  an operator would keep, matching the discipline every other `register-*`
  fn in this namespace follows."
  [amount sequence effective-date]
  (when (neg? amount)
    (throw (ex-info "clawback-repayment: amount must be >= 0" {})))
  (when (< sequence 0)
    (throw (ex-info "clawback-repayment: sequence must be >= 0" {})))
  (when-not (and effective-date (not= effective-date ""))
    (throw (ex-info "clawback-repayment: effective-date required" {})))
  (let [record-id (str "CLAWBACK-" (zero-pad sequence 6))]
    {"record" {"record_id" record-id
              "kind" "clawback-repayment-draft"
              "amount" (double amount)
              "effective_date" effective-date
              "immutable" true}
     "certificate" (unsigned-certificate "ClawbackRepaymentCertificate" record-id record-id)}))

(defn append
  "Append a commitment/distribution record, returning a NEW list (never
  mutate history in place)."
  [history result]
  (conj (vec history) (get result "record")))
