(ns vcfund.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  This namespace drives the REAL actor stack (`vcfund.operation` ->
  `vcfund.governor` -> `vcfund.phase` -> `vcfund.store`) through a
  scenario extended from this repo's own `vcfund.sim` demo driver
  (`clojure -M:dev:run`, run BEFORE this file was written to confirm the
  seeded ids and the ledger it actually produces), and renders the result
  deterministically.

  Everything on the page is real output:

    - the LP / deal / party rows are `vcfund.store/demo-data` read back
      through the `Store` protocol AFTER the run, so the capital call's
      pro-rata `:called-amount` advance and deal-1's `:sourced` ->
      `:ic-review` -> `:committed` -> `:exited` walk are the store's own
      state, not a hand-typed copy;
    - every disposition, confidence and violation rule comes from the
      graph state `langgraph.graph/run*` returned;
    - the registry records are the drafts `vcfund.registry` actually
      built;
    - the phase-3 autonomy table is read out of `vcfund.phase/phases`;
    - the approver-attribution section is a RENDER-TIME MEASUREMENT (see
      `approver-hits`), not a hard-coded claim.

  No timestamps in the page content, so reruns are byte-identical
  against the same seed.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [langgraph.graph :as g]
            [vcfund.nav :as nav]
            [vcfund.operation :as op]
            [vcfund.phase :as phase]
            [vcfund.registry :as registry]
            [vcfund.store :as store]
            [vcfund.waterfall :as waterfall]))

(def ^:private operator
  {:actor-id "op-1" :actor-role :investment-committee :phase 3})

(def ^:private approver "op-1")

;; The demo's whole-fund reconciliation horizon. A REAL external fact the
;; caller supplies (`vcfund.waterfall/whole-fund-waterfall-report`); the
;; sim driver uses the same stretched 100y fund life so the whole-fund GP
;; entitlement collapses toward zero and a clawback is actually owed.
(def ^:private fund-life-years 100)

;; ----------------------------- run capture -----------------------------

(defn- rules-of [state]
  (mapv :rule (get-in state [:verdict :violations])))

(defn- approver-key?
  "Structural test only: does this key NAME an approver identity? It does
  not know whether the store is supposed to keep one."
  [k]
  (contains? #{"approved-by" "approved_by" "approver" "by"}
             (if (keyword? k) (name k) (str k))))

(defn- approver-hits
  "Every [key value] pair anywhere inside `x` whose key names an approver
  identity. Purely structural, so any disclosure derived from it
  self-corrects the moment the store starts (or stops) keeping one."
  [x]
  (cond
    (map? x)
    (into (vec (keep (fn [[k v]] (when (approver-key? k) [k v])) x))
          (mapcat approver-hits (vals x)))

    (sequential? x)
    (vec (mapcat approver-hits x))

    :else []))

(defn- run-op!
  "Runs ONE operation through the real actor. When the phase gate /
  governor escalates it and `approve?` is set, resumes the interrupted
  graph with a human Investment Committee approval. Appends one entry to
  `log` describing what actually happened and returns the final state."
  [actor log tid request approve?]
  (let [r1 (g/run* actor {:request request :context operator} {:thread-id tid})
        s1 (:state r1)
        escalated? (= :escalate (:disposition s1))
        r2 (when (and escalated? approve?)
             (g/run* actor {:approval {:status :approved :by approver}}
                     {:thread-id tid :resume? true}))
        s2 (or (:state r2) s1)]
    (swap! log conj
           {:thread       tid
            :op           (:op request)
            :subject      (:subject request)
            :initial      (:disposition s1)
            :final        (:disposition s2)
            :approved?    (boolean r2)
            :confidence   (get-in s2 [:verdict :confidence])
            :high-stakes? (get-in s2 [:verdict :high-stakes?])
            :hard?        (get-in s2 [:verdict :hard?])
            :summary      (get-in s2 [:proposal :summary])
            :effect       (get-in s2 [:record :effect])
            :rules        (rules-of s2)
            ;; what the GRAPH knew about the approver ...
            :graph-approver (some #(when (= :approval-granted (:t %)) (:by %))
                                  (:audit s2))
            ;; ... and what the graph HANDED the store on the record.
            :record-approver (first (map second (approver-hits (get-in s2 [:record :payload]))))})
    s2))

(defn run-demo!
  "Runs a fresh seeded store (`vcfund.store/seed-db`) through a scenario
  that reaches every disposition this actor can produce.

  Happy path -- deal-1 (Acme AI, Inc., USA, clean founders party-1 /
  party-2) walks a complete fund lifecycle: LP directory upsert
  (auto-commits, no capital risk) -> DD checklist assessment (escalates,
  approved) -> KYC screening of both founders (escalate, approved) ->
  pipeline advance `:sourced` -> `:ic-review` (auto) -> term sheet v0
  proposed (auto) -> signed by the fund and by the founder, so v0 is
  FULLY EXECUTED (auto) -> a $2,000,000 capital call pro-rata across
  lp-1/lp-2 (ALWAYS escalates, `:actuation/call`, approved) ->
  investment commitment (ALWAYS escalates, `:actuation/deploy`,
  approved) -> a $750,000 priced-equity follow-on (ALWAYS escalates,
  same stake, approved) -> a board seat granted to party-1 (auto) -> a
  2026-Q3 portfolio KPI report (auto) -> a $12,000,000 exit distribution
  after 3 years (ALWAYS escalates, `:actuation/distribute`, approved) ->
  a whole-fund GP-clawback repayment (ALWAYS escalates,
  `:actuation/clawback`, approved).

  HARD holds -- thirteen proposals the governor refuses outright, none of
  which ever reaches a human:

    - `:investment/commit` on deal-2 with no term sheet ever proposed
      (`:term-sheet-missing`, plus `:no-spec-basis`, `:dd-incomplete`
      and `:stage-insufficient`);
    - `:kyc/screen` on party-3, a seeded sanctions hit
      (`:sanctions-hit`);
    - `:dd/assess` on deal-2, whose seeded jurisdiction \"ATL\" has NO
      entry in `vcfund.facts/catalog` (`:no-spec-basis`);
    - a $20,000,000 capital call, which overcalls both LPs past their
      commitments (`:capital-call-overcall`);
    - `:sourced` -> `:committed` as a pipeline move, which is never a
      legal transition (`:invalid-stage-transition`);
    - a term sheet proposed on deal-1 AFTER it exited
      (`:term-sheet-after-commitment`);
    - `:investment/commit` on deal-3 whose (freshly proposed) term sheet
      nobody signed (`:term-sheet-not-executed`, plus the same three
      outstanding gates);
    - a follow-on, a board seat and a KPI report on deal-2, which the
      fund never committed to (`:follow-on-requires-prior-commitment`,
      `:board-seat-without-commitment`,
      `:portfolio-report-without-commitment`);
    - an exit distribution on deal-3, which has no commitment record
      (`:commitment-missing`);
    - a 999,999,999 clawback demand against an independently recomputed
      entitlement (`:clawback-exceeds-entitlement`);
    - and finally, lp-2's accredited-investor affirmation is WITHDRAWN
      through a real `:lp/intake` op (which auto-commits -- the actor is
      allowed to record that fact), after which any further capital call
      is refused fund-wide (`:accredited-investor-violation`). This is
      deliberately last: it leaves the store in the state the run
      actually ended in, visible in the LP register.

  Returns `{:db .. :log ..}`; `:log` is one entry per operation, built
  from the graph state `run*` returned."
  []
  (let [db (store/seed-db)
        actor (op/build db)
        log (atom [])
        run! (fn [tid request approve?] (run-op! actor log tid request approve?))]

    ;; ---- deal-1: full lifecycle ----
    (run! "t1" {:op :lp/intake :subject "lp-1"
                :patch {:id "lp-1" :status :active}} false)
    (run! "t2" {:op :dd/assess :subject "deal-1"} true)
    (run! "t3" {:op :kyc/screen :subject "party-1"} true)
    (run! "t4" {:op :kyc/screen :subject "party-2"} true)
    (run! "t5" {:op :deal/advance-stage :subject "deal-1" :to-stage :ic-review} false)
    (run! "t6" {:op :term-sheet/propose :subject "deal-1" :proposed-by :fund
                :terms {:valuation 8000000 :security-type :safe
                        :pro-rata-rights true :board-seat false}} false)
    (run! "t7" {:op :term-sheet/sign :subject "deal-1" :signed-by :fund} false)
    (run! "t8" {:op :term-sheet/sign :subject "deal-1" :signed-by :founder} false)
    (run! "t9" {:op :capital-call/issue :subject "deal-1"
                :call-amount 2000000 :notice-date "2026-07-06"} true)
    (run! "t10" {:op :investment/commit :subject "deal-1"} true)
    (run! "t11" {:op :investment/follow-on :subject "deal-1"
                 :security-type :priced-equity :amount 750000} true)
    (run! "t12" {:op :governance/board-seat :subject "deal-1"
                 :seat-holder "party-1" :seat-type :board-member
                 :event :granted :effective-date "2026-07-06"} false)
    (run! "t13" {:op :portfolio/report :subject "deal-1" :period "2026-Q3"
                 :kpis {:revenue 450000 :burn-rate 80000
                        :runway-months 14 :headcount 12}} false)
    (run! "t14" {:op :exit/distribute :subject "deal-1"
                 :exit-proceeds 12000000 :holding-period-years 3} true)
    (let [{:keys [gp-clawback]} (waterfall/whole-fund-waterfall-report db fund-life-years)]
      (run! "t15" {:op :waterfall/clawback-repay :subject "fund"
                   :amount gp-clawback :effective-date "2026-07-06"
                   :fund-life-years fund-life-years} true))

    ;; ---- HARD holds (none of these ever reaches a human) ----
    ;; deal-2 has no term sheet at all yet -- run this BEFORE any term
    ;; sheet exists for it, so `:term-sheet-missing` is what fires.
    (run! "h1" {:op :investment/commit :subject "deal-2"} false)
    (run! "h2" {:op :kyc/screen :subject "party-3"} false)
    (run! "h3" {:op :dd/assess :subject "deal-2"} false)
    (run! "h4" {:op :capital-call/issue :subject "deal-3"
                :call-amount 20000000 :notice-date "2026-07-06"} false)
    (run! "h5" {:op :deal/advance-stage :subject "deal-3" :to-stage :committed} false)
    (run! "h6" {:op :term-sheet/propose :subject "deal-1" :proposed-by :founder
                :terms {:valuation 9000000}} false)
    ;; deal-3 gets a term sheet (auto-commits, no capital risk) that
    ;; NOBODY signs -- so the commit attempt right after it holds on
    ;; `:term-sheet-not-executed` rather than `:term-sheet-missing`.
    (run! "h7" {:op :term-sheet/propose :subject "deal-3" :proposed-by :fund
                :terms {:valuation 1500000}} false)
    (run! "h8" {:op :investment/commit :subject "deal-3"} false)
    (run! "h9" {:op :investment/follow-on :subject "deal-2"
                :security-type :priced-equity :amount 100000} false)
    (run! "h10" {:op :governance/board-seat :subject "deal-2"
                 :seat-holder "party-1" :seat-type :board-observer
                 :event :granted :effective-date "2026-07-06"} false)
    (run! "h11" {:op :portfolio/report :subject "deal-2" :period "2026-Q3"
                 :kpis {:revenue 0}} false)
    (run! "h12" {:op :exit/distribute :subject "deal-3"
                 :exit-proceeds 1000000 :holding-period-years 2} false)
    (run! "h13" {:op :waterfall/clawback-repay :subject "fund"
                 :amount 999999999 :effective-date "2026-07-06"
                 :fund-life-years 3} false)
    ;; LAST: an LP's accredited-investor affirmation is withdrawn. The
    ;; actor is allowed to RECORD that fact (auto-commits, no capital
    ;; risk); the very next capital call is then refused fund-wide.
    (run! "h14" {:op :lp/intake :subject "lp-2"
                 :patch {:id "lp-2" :accredited? false}} false)
    (run! "h15" {:op :capital-call/issue :subject "deal-2"
                 :call-amount 500000 :notice-date "2026-07-06"} false)

    {:db db :log @log}))

;; ----------------------------- html helpers -----------------------------

(defn- esc
  "Escapes ONE raw value for HTML text. Never call this on a string that
  already contains markup -- that is how a page ends up reading
  `intake &rarr; advise` literally."
  [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- kw-str [k] (if (keyword? k) (str k) (str k)))

(defn- code [v] (str "<code>" (esc v) "</code>"))

(defn- pill [class label] (str "<span class=\"" class "\">" (esc label) "</span>"))

(defn- muted [v] (pill "muted" v))

(defn- row [& cells]
  (str "        <tr>" (str/join (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- rows [xs] (str/join "\n" xs))

(defn- table [headers body-rows]
  (str "    <table>\n"
       "      <thead><tr>"
       (str/join (map #(str "<th>" (esc %) "</th>") headers))
       "</tr></thead>\n"
       "      <tbody>\n" (rows body-rows) "\n      </tbody>\n"
       "    </table>\n"))

(defn- section [title lead body]
  (str "  <section class=\"card\">\n"
       "    <h2>" (esc title) "</h2>\n"
       "    <p class=\"lead\">" (esc lead) "</p>\n"
       body
       "  </section>\n"))

(defn- disposition-pill [d]
  (case d
    :commit   (pill "ok" "commit")
    :hold     (pill "critical" "HARD hold")
    :escalate (pill "warn" "escalate (human)")
    (muted (str d))))

(defn- yes-no [b] (if b (pill "ok" "yes") (pill "critical" "no")))

;; ----------------------------- sections -----------------------------

(defn- lp-rows [db]
  (for [{:keys [id name commitment-amount called-amount currency jurisdiction
                accredited? status]} (store/all-lps db)]
    (row (code id) (esc name) (code commitment-amount) (code called-amount)
         (esc currency) (esc jurisdiction)
         (if accredited? (pill "ok" "affirmed") (pill "critical" "NOT affirmed"))
         (esc (kw-str status)))))

(defn- deal-rows [db]
  (for [{:keys [id portfolio-company jurisdiction ask-amount currency security-type
                status sector investment-stage commitment-number]} (store/all-deals db)]
    (row (code id) (esc portfolio-company) (esc jurisdiction)
         (code ask-amount) (esc currency)
         (code (kw-str security-type))
         (code (kw-str status))
         (esc sector) (esc investment-stage)
         (if commitment-number (code commitment-number) (muted "none")))))

(defn- operation-rows [log]
  (for [{:keys [thread op subject initial final approved? confidence high-stakes? rules]} log]
    (row (code thread) (code (kw-str op)) (code subject)
         (disposition-pill initial)
         (if approved? (pill "ok" (str "approved by " approver)) (muted "n/a"))
         (disposition-pill final)
         (code confidence)
         (if high-stakes? (pill "warn" "actuation") (muted "no"))
         (if (seq rules)
           (str/join " " (map #(pill "critical" (kw-str %)) rules))
           (muted "clean")))))

(defn- hold-rows [ledger]
  (for [{:keys [op subject violations confidence]} (filter #(= :governor-hold (:t %)) ledger)]
    (row (code (kw-str op)) (code subject) (code confidence)
         (str/join " " (map #(pill "critical" (kw-str (:rule %))) violations))
         (str/join "<br>" (map #(esc (:detail %)) violations)))))

(defn- rule-coverage-rows
  "Distinct HARD rules this run actually provoked -- derived from the
  ledger, never a hand-maintained list, so it tracks the governor."
  [ledger]
  (let [holds (filter #(= :governor-hold (:t %)) ledger)
        by-rule (reduce (fn [m {:keys [op subject violations]}]
                          (reduce (fn [m {:keys [rule detail]}]
                                    (update m rule
                                            (fnil (fn [e]
                                                    (-> e
                                                        (update :ops conj [op subject])
                                                        (assoc :detail detail)))
                                                  {:ops [] :detail detail})))
                                  m violations))
                        {} holds)]
    (for [[rule {:keys [ops detail]}] (sort-by (comp str key) by-rule)]
      (row (pill "critical" (kw-str rule))
           (code (count ops))
           (str/join ", " (map (fn [[o s]] (code (str (kw-str o) " " s))) ops))
           (esc detail)))))

(defn- phase-rows
  "Phase-3 autonomy, read out of `vcfund.phase/phases` and joined against
  what each op actually did in this run."
  [log]
  (let [p3 (get phase/phases 3)
        auto (:auto p3)
        observed (reduce (fn [m {:keys [op initial final]}]
                           (update m op (fnil conj #{}) [initial final]))
                         {} log)]
    (for [op (sort-by str (:writes p3))]
      (row (code (kw-str op))
           (if (contains? auto op)
             (pill "ok" "auto-commit when governor-clean")
             (pill "warn" "human approval required"))
           (if-let [obs (get observed op)]
             (str/join " " (for [[i f] (sort-by str obs)]
                             (str (disposition-pill i)
                                  (when (not= i f) (str " &rarr; " (disposition-pill f))))))
             (muted "not exercised in this run"))))))

(defn- ledger-rows [ledger]
  (for [{:keys [t op subject disposition basis summary]} ledger]
    (row (case t
           :committed (pill "ok" "committed")
           :governor-hold (pill "critical" "governor-hold")
           (muted (kw-str t)))
         (code (kw-str op)) (code subject)
         (esc (kw-str disposition))
         (esc (str/join " | " (map str basis)))
         (esc (or summary "")))))

;; ---- registry records ----

(defn- record-table
  "Renders a list of registry draft records as a key/value table. Column
  order comes from the record itself (they are ordered maps built by
  `vcfund.registry`), so nothing here is retyped."
  [recs]
  (if (empty? recs)
    "    <p class=\"muted\">no records</p>\n"
    (let [ks (vec (distinct (mapcat keys recs)))]
      (table (map str ks)
             (for [r recs]
               (apply row (for [k ks]
                            (let [v (get r k)]
                              (cond (nil? v) (muted "-")
                                    (map? v) (code (pr-str v))
                                    :else (code v))))))))))

(defn- unwrap
  "The registry returns {\"record\" .. \"certificate\" ..}; the store keeps
  the record. Normalize both shapes."
  [r]
  (or (get r "record") r))

(defn- number-rows [m]
  (for [[k v] (sort-by (comp str key) (filter #(number? (val %)) m))]
    (row (code (kw-str k)) (code v))))

;; ---- approver attribution (MEASURED, not assumed) ----

(def ^:private effect->artifacts
  "effect -> (db, subject) -> the store artifacts that effect writes.
  Structural knowledge about where the store keeps things; it says
  nothing about whether an approver survives there."
  {:assessment/set                 (fn [db s] [(store/assessment-of db s)])
   :kyc/set                        (fn [db s] [(store/kyc-of db s)])
   :capital-call/mark-issued       (fn [db _] (store/capital-call-history db))
   :investment/mark-committed      (fn [db s] [(store/commitment-of db s)])
   :investment/follow-on-committed (fn [db s] (store/follow-on-history-of db s))
   :distribution/mark-paid         (fn [db _] (store/distribution-history db))
   :waterfall/clawback-repaid      (fn [db _] (store/clawback-repayment-history db))
   :lp/upsert                      (fn [db s] [(store/lp db s)])
   :deal/advance-stage             (fn [db s] [(store/deal db s)])
   :term-sheet/proposed            (fn [db s] (store/term-sheet-history-of db s))
   :term-sheet/signed              (fn [db s] (store/signature-history-of db s))
   :portfolio/report-logged        (fn [db s] (store/portfolio-reports-of db s))
   :governance/board-seat-recorded (fn [db s] (store/board-seat-history-of db s))})

(defn- measure-approvals
  "For every operation a human actually approved in this run, MEASURE
  three things and return them:

    :graph-approver  -- who the graph said approved it
                        (`:approval-granted` in the audit channel)
    :record-approver -- who the graph HANDED the store on the commit
                        record's `:payload`
    :store-approver  -- who can still be read back OUT of the store
                        afterwards, by scanning the artifacts that
                        effect writes for any approver-named key

  Nothing here assumes an answer; the disclosure below is derived from
  whatever these come back as."
  [db log]
  (for [{:keys [op subject effect graph-approver record-approver] :as e}
        (filter :approved? log)]
    (let [artifacts (when-let [f (get effect->artifacts effect)]
                      (remove nil? (f db subject)))
          hits (approver-hits (vec artifacts))]
      (assoc e
             :artifact-count (count artifacts)
             :store-approver (first (map second hits))
             :store-approver-key (first (map first hits))))))

(defn- ledger-approver-facts
  "Does the append-only ledger itself carry the approval? Measured."
  [ledger]
  (filter #(seq (approver-hits %)) ledger))

(defn- approval-rows [measured]
  (for [{:keys [op subject effect graph-approver record-approver
                store-approver store-approver-key artifact-count]} measured]
    (row (code (kw-str op)) (code subject) (code (kw-str effect))
         (if graph-approver (code graph-approver) (muted "-"))
         (if record-approver (code record-approver) (muted "-"))
         (code artifact-count)
         (if store-approver
           (str (pill "ok" "recoverable") " " (code (str (kw-str store-approver-key)
                                                         " = " store-approver)))
           (pill "critical" "not recoverable")))))

(defn- approver-disclosure
  "The disclosure text is DERIVED from the measurement above, at render
  time. If the store ever starts keeping the approver, this paragraph
  changes on its own -- nothing here hard-codes a verdict."
  [measured ledger]
  (let [total (count measured)
        kept (filter :store-approver measured)
        lost (remove :store-approver measured)
        handed (filter :record-approver measured)
        in-ledger (ledger-approver-facts ledger)]
    (str
     "    <p class=\"lead\">"
     (esc (str total " operation(s) in this run were approved by a human Investment "
               "Committee member. The graph knew the approver for "
               (count (filter :graph-approver measured)) " of them, and handed an "
               "approver on the commit record's :payload for " (count handed) "."))
     "</p>\n"
     "    <p class=\"" (if (seq lost) "callout-bad" "callout-ok") "\">"
     (esc
      (cond
        (and (empty? lost) (seq kept))
        (str "Every approved operation's approver is still readable back out of the "
             "store afterwards. \"Who approved this?\" is answerable from the SSoT "
             "alone.")

        (and (seq lost) (seq kept))
        (str "PARTIAL ATTRIBUTION. " (count kept) " of " total " approved operations keep "
             "the approver in the store (" (str/join ", " (map (comp str :op) kept))
             "); " (count lost) " lose it (" (str/join ", " (map (comp str :op) lost))
             "). Those are exactly the effects whose store handler rebuilds the record "
             "from :path and the deal/LP directory instead of persisting the :payload "
             "it was handed, so the approver travels as far as the store's front door "
             "and no further. \"Who approved this?\" cannot be answered from the SSoT "
             "for those operations.")

        (seq lost)
        (str "APPROVER ATTRIBUTION LOST. None of the " total " approved operations keep "
             "the approver in the store.")

        :else
        "No operation in this run was approved by a human."))
     "</p>\n"
     "    <p class=\"" (if (seq in-ledger) "callout-ok" "callout-bad") "\">"
     (esc
      (if (seq in-ledger)
        (str "The append-only audit ledger carries an approver on " (count in-ledger)
             " fact(s), so the approval is recoverable there even where the record "
             "itself dropped it.")
        (str "The append-only audit ledger does NOT carry the approval either: the "
             ":approval-granted fact is produced by the graph's :request-approval node "
             "into the run's :audit channel, but the :commit node only appends its own "
             ":committed fact, so the approval never reaches store/append-ledger!. "
             "Reading this console, you cannot distinguish \"nobody approved it\" from "
             "\"the store did not keep who did\" for any operation marked NOT "
             "RECOVERABLE above -- except by trusting this page's own run log, which "
             "is in-memory graph state, not the SSoT.")))
     "</p>\n"
     "    <p class=\"muted\">"
     (esc (str "This section is a measurement, not an assertion: "
               "vcfund.render-html/approver-hits scans each artifact for any key named "
               "approved-by / approved_by / approver / by. If the defect is ever fixed, "
               "this page reports the fix without being edited."))
     "</p>\n")))

;; ----------------------------- style -----------------------------

(def ^:private page-css
  "Self-contained. No design-system dependency is wired in, deliberately:
  this generator must build offline and byte-reproducibly from `deps.edn`
  as it already stands, and the fleet's jp-go-dds bridge exports a
  different token vocabulary than these consoles use."
  (str/join
   "\n"
   ["*{box-sizing:border-box}"
    "body{margin:0;background:#f4f6f9;color:#16202c;"
    "font-family:-apple-system,'Hiragino Kaku Gothic ProN','Noto Sans JP',sans-serif;"
    "font-size:14px;line-height:1.6}"
    "header.bar{background:#0d1b2a;color:#fff;padding:20px 28px}"
    "header.bar h1{margin:0 0 6px;font-size:19px;font-weight:650}"
    "header.bar .badge{display:inline-block;background:#1b3550;border-radius:999px;"
    "padding:3px 12px;font-size:12px;color:#cfe0f2}"
    "main{padding:24px 28px 56px;max-width:1400px}"
    "section.card{background:#fff;border:1px solid #dde3ea;border-radius:10px;"
    "padding:20px 22px;margin-bottom:20px}"
    "section.card h2{margin:0 0 6px;font-size:16px;font-weight:650}"
    "p.lead{margin:0 0 14px;color:#4a5a6b;font-size:13px}"
    "p.muted,span.muted{color:#8794a3}"
    "p.callout-ok{margin:0 0 12px;padding:10px 14px;border-radius:8px;"
    "background:#e8f6ed;border-left:4px solid #1e7a45;color:#14532d}"
    "p.callout-bad{margin:0 0 12px;padding:10px 14px;border-radius:8px;"
    "background:#fdecec;border-left:4px solid #b3261e;color:#7a1a15}"
    "table{border-collapse:collapse;width:100%;font-size:12.5px}"
    "th{text-align:left;background:#eef2f6;color:#42505f;font-weight:600;"
    "padding:7px 10px;border-bottom:1px solid #dde3ea;white-space:nowrap}"
    "td{padding:7px 10px;border-bottom:1px solid #eef1f5;vertical-align:top}"
    "tr:last-child td{border-bottom:none}"
    "code{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:12px;"
    "background:#f1f4f8;border-radius:4px;padding:1px 5px}"
    "span.ok{color:#1e7a45;font-weight:600}"
    "span.warn{color:#8a5a00;font-weight:600}"
    "span.critical{display:inline-block;color:#b3261e;font-weight:600;"
    "background:#fdecec;border-radius:4px;padding:1px 6px;margin:1px 2px 1px 0}"]))

;; ----------------------------- document -----------------------------

(defn render
  "Renders the whole operator console from a store `db` and the run `log`
  that `run-demo!` produced."
  [{:keys [db log]}]
  (let [ledger (vec (store/ledger db))
        holds (filter #(= :governor-hold (:t %)) ledger)
        measured (vec (measure-approvals db log))
        nav-report (nav/fund-nav-report db)
        wf (waterfall/whole-fund-waterfall-report db fund-life-years)
        seats (registry/current-board-seats (store/board-seat-history-of db "deal-1"))]
    (str
     "<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
     "<title>cloud-itonami-isic-6499 · venture-capital fund operations</title>"
     "<style>" page-css "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Trusts, funds and similar financial entities (ISIC 6499) — Venture-Capital Fund Operator Console</h1>\n"
     "  <span class=\"badge\">read-only build-time sample · governor-gated · all four directions of capital movement always human-approved</span>\n"
     "</header>\n"
     "<main>\n"

     (section
      "Limited partners (fund register)"
      (str "Read back out of vcfund.store AFTER the run. The called amounts are the "
           "pro-rata split vcfund.registry/capital-call-allocations computed for the "
           "$2,000,000 deal-1 call, applied by the store — not typed here.")
      (table ["LP" "Name" "Commitment" "Called to date" "Ccy" "Jurisdiction"
              "Accredited-investor affirmation" "Status"]
             (lp-rows db)))

     (section
      "Deal pipeline"
      (str "Seeded deals with their live pipeline status. deal-1 walked "
           ":sourced -> :ic-review -> :committed -> :exited through real ops; "
           "deal-2 and deal-3 are still :sourced because every attempt to move them "
           "was refused.")
      (table ["Deal" "Portfolio company" "Jurisdiction" "Ask" "Ccy" "Security"
              "Status" "Sector" "Stage" "Commitment no."]
             (deal-rows db)))

     (section
      "Operations in this run"
      (str "One row per actor run. 'Governor + phase' is the disposition before any "
           "human saw it; 'Final' is after the human resumed the interrupted graph. "
           "Confidence and violation rules come from the InvestmentCommitteeGovernor "
           "verdict.")
      (table ["Thread" "Op" "Subject" "Governor + phase" "Human" "Final"
              "Confidence" "Stake" "HARD violations"]
             (operation-rows log)))

     (section
      "Human approvals — can the store say who approved?"
      (str "Measured at render time by scanning the store artifacts each committed "
           "effect writes for any approver-named key.")
      (str (approver-disclosure measured ledger)
           (table ["Op" "Subject" "Effect" "Approver (graph state)"
                   "Approver handed to store (:payload)" "Artifacts scanned"
                   "Approver readable back from store"]
                  (approval-rows measured))))

     (section
      "HARD governor holds"
      (str (count holds) " proposals were refused outright. A HARD violation cannot be "
           "overridden by a human approver — these never reach the approval node at all.")
      (table ["Op" "Subject" "Confidence" "Rules" "Governor detail"]
             (hold-rows ledger)))

     (section
      "HARD rule coverage exercised by this run"
      (str "Distinct HARD rules this scenario actually provoked, derived from the "
           "ledger rather than from a hand-maintained list.")
      (table ["Rule" "Times fired" "Provoked by" "Detail"]
             (rule-coverage-rows ledger)))

     (section
      "Phase-3 autonomy (vcfund.phase/phases)"
      (str "Read directly out of the phase table for phase 3 (supervised-auto) and "
           "joined against what each op actually did above. The five capital-moving ops "
           "are absent from every phase's :auto set as a structural fact, not a rollout "
           "milestone.")
      (table ["Op" "Phase-3 posture" "Observed in this run"] (phase-rows log)))

     (section
      "Audit ledger (this run)"
      "The append-only decision-fact log vcfund.store/append-ledger! actually wrote."
      (table ["Fact" "Op" "Subject" "Disposition" "Basis" "Summary"]
             (ledger-rows ledger)))

     (section
      "Capital-call notices issued"
      "Drafts built by vcfund.registry/register-capital-call."
      (record-table (map unwrap (store/capital-call-history db))))

     (section
      "Investment commitments"
      "Drafts built by vcfund.registry/register-commitment."
      (record-table (map unwrap (store/commitment-history db))))

     (section
      "Follow-on commitments (deal-1)"
      "Drafts built by vcfund.registry/register-follow-on-commitment, referencing the original commitment number."
      (record-table (map unwrap (store/follow-on-history-of db "deal-1"))))

     (section
      "Term sheets and e-signatures (deal-1, deal-3)"
      (str "deal-1 v0 is fully executed (fund + founder). deal-3 v0 was proposed and "
           "never signed, which is why the deal-3 commit attempt HARD-held.")
      (str (record-table (map unwrap (concat (store/term-sheet-history-of db "deal-1")
                                             (store/term-sheet-history-of db "deal-3"))))
           (record-table (map unwrap (store/signature-history-of db "deal-1")))))

     (section
      "Board seats (deal-1)"
      (str "Append-only event history, plus the CURRENT roster projected from it by "
           "vcfund.registry/current-board-seats (" (count seats) " seated).")
      (record-table (map unwrap (store/board-seat-history-of db "deal-1"))))

     (section
      "Portfolio KPI reports (deal-1)"
      "Drafts built by vcfund.registry/register-portfolio-report."
      (record-table (map unwrap (store/portfolio-reports-of db "deal-1"))))

     (section
      "Exit distributions"
      "Deal-by-deal waterfall computed by vcfund.registry/distribute-waterfall from the caller-supplied closing facts."
      (record-table (map unwrap (store/distribution-history db))))

     (section
      "GP-clawback repayments (fund-level)"
      "Drafts built by vcfund.registry/register-clawback-repayment, gated on an independently recomputed entitlement."
      (record-table (map unwrap (store/clawback-repayment-history db))))

     (section
      "Fund position (vcfund.nav/fund-nav-report)"
      "Computed from live store state with the report's own defaults; numeric fields only."
      (table ["Field" "Value"] (number-rows nav-report)))

     (section
      (str "Whole-fund waterfall reconciliation (fund life " fund-life-years "y)")
      (str "vcfund.waterfall/whole-fund-waterfall-report recomputed from every "
           "commitment and distribution on file. This is the figure the governor "
           "checks a clawback demand against — the proposal's own number is never "
           "trusted.")
      (table ["Field" "Value"] (number-rows wf)))

     "</main>\n</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db log] :as result} (run-demo!)
        ledger (store/ledger db)
        holds (filter #(= :governor-hold (:t %)) ledger)]
    ;; Build-time invariant, not a convention: a console that shows no
    ;; HARD hold has not demonstrated that the governor can refuse
    ;; anything, so refuse to write it at all.
    (when (empty? holds)
      (throw (ex-info (str "refusing to write " out
                           ": the run produced ZERO :governor-hold facts, so this page "
                           "would not demonstrate that the InvestmentCommitteeGovernor "
                           "can refuse a proposal. Fix the scenario, not this check.")
                      {:ledger-facts (count ledger)
                       :operations (count log)})))
    (spit out (render result))
    (println "wrote" out
             (str "(" (count log) " operations, "
                  (count ledger) " ledger facts, "
                  (count holds) " HARD holds, "
                  (count (distinct (mapcat #(map :rule (:violations %)) holds)))
                  " distinct HARD rules, "
                  (count (filter :approved? log)) " human approvals)"))))
