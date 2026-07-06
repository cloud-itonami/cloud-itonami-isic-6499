(ns vcfund.nav
  "Whole-fund NAV (net asset value) and unfunded-commitment reporting --
  closes the first of the three remaining honest coverage gaps (whole-fund
  NAV, term-sheet negotiation workflow, absolute cap-table/option-pool
  modeling) tracked in README's \"Business-process coverage\" table.

  Read-only reporting, not a governed op: computing NAV moves no capital
  and makes no investment decision, so it does not need
  `vcfund.governor` gating the way the six governed ops do -- the same
  posture `vcfund.captable` takes on valuation math.

  Two layers:
    - `unfunded-commitments`/`fund-nav` -- pure functions over plain data,
      independently testable.
    - `fund-nav-report` -- a thin adapter that reads the live `vcfund.store`
      state and calls the pure functions. Kept separate so the math itself
      has zero I/O dependency.

  Honest limitations (see docstrings): no fund expenses/management-fee
  accrual netted into cash, no multi-currency FX conversion (assumes one
  fund base currency), a deal's fair value defaults to its cost basis
  (the commitment amount) until an operator records a `:fair-value-mark`
  KPI via `:portfolio/report` -- never silently mark up an unvalued
  investment."
  (:require [vcfund.store :as store]))

(defn unfunded-commitments
  "Per-LP and fund-wide unfunded commitment: commitment - called. The
  fund-wide total is what the GP can still legally call under the LPA
  (subject to any investment-period/re-up terms, which this does not
  model)."
  [lps]
  (when (empty? lps) (throw (ex-info "unfunded-commitments: at least one LP required" {})))
  (let [rows (mapv (fn [{:keys [id commitment-amount called-amount]}]
                     (let [commitment-amount (double commitment-amount)
                           called-amount (double (or called-amount 0))]
                       {:lp-id id
                        :commitment-amount commitment-amount
                        :called-amount called-amount
                        :unfunded (- commitment-amount called-amount)}))
                   lps)]
    {:by-lp rows
     :total-commitments (reduce + (map :commitment-amount rows))
     :total-called (reduce + (map :called-amount rows))
     :total-unfunded (reduce + (map :unfunded rows))}))

(defn fund-nav
  "Whole-fund NAV = net cash + fair value of still-held (un-exited)
  investments.

  `total-called` -- cumulative capital called in from LPs to date.
  `total-invested-at-cost` -- cumulative capital committed/deployed to
  portfolio companies to date (cost basis, not marks).
  `total-exit-proceeds-received` -- cumulative gross proceeds received
  from exits, BEFORE the LP/GP waterfall split pays it back out.
  `total-distributed-to-lps` -- cumulative amount actually distributed to
  LPs (the `:total-to-lp` side of each `vcfund.registry/distribute-
  waterfall` result).
  `investments` -- coll of `{:deal-id :cost-basis :fair-value (nil ->
  defaults to cost-basis, never inflate an unmarked investment) :exited?}`.

  Returns `{:net-cash :held-fair-value :nav}`. Does NOT net out fund
  expenses/management fees -- an honest limitation, not silently assumed
  away; a real fund's cash balance also reflects those."
  [{:keys [total-called total-invested-at-cost total-exit-proceeds-received
           total-distributed-to-lps investments]}]
  (doseq [[k v] {:total-called total-called :total-invested-at-cost total-invested-at-cost
                :total-exit-proceeds-received total-exit-proceeds-received
                :total-distributed-to-lps total-distributed-to-lps}]
    (when (neg? v) (throw (ex-info (str "fund-nav: " k " must be >= 0") {}))))
  (let [net-cash (- (+ (double total-called) (double total-exit-proceeds-received))
                    (+ (double total-invested-at-cost) (double total-distributed-to-lps)))
        held (remove :exited? investments)
        held-fair-value (reduce + (map (fn [{:keys [cost-basis fair-value]}]
                                         (double (or fair-value cost-basis)))
                                       held))]
    {:net-cash net-cash
     :held-fair-value held-fair-value
     :nav (+ net-cash held-fair-value)}))

(defn- latest-fair-value-mark
  "The most recent `:fair-value-mark` KPI an operator recorded via
  `:portfolio/report` for this deal, or nil if never marked (in which case
  `fund-nav` falls back to cost basis)."
  [st deal-id]
  (->> (store/portfolio-reports-of st deal-id)
       reverse
       (keep #(get-in % ["kpis" :fair-value-mark]))
       first))

(defn fund-nav-report
  "Adapter: reads the live `vcfund.store` state and computes whole-fund
  NAV + unfunded commitments. This is the function an operator console
  actually calls; `fund-nav`/`unfunded-commitments` stay pure and
  independently testable."
  [st]
  (let [lps (store/all-lps st)
        committed-deals (filter #(contains? #{:committed :exited} (:status %)) (store/all-deals st))
        commitment-recs (store/commitment-history st)
        distribution-recs (store/distribution-history st)
        total-called (reduce + (map #(double (or (:called-amount %) 0)) lps))
        total-invested-at-cost (reduce + (map #(double (get % "amount" 0)) commitment-recs))
        total-distributed-to-lps (reduce + (map #(double (:total-to-lp (get % "waterfall"))) distribution-recs))
        total-exit-proceeds-received (reduce + (map #(+ (double (:total-to-lp (get % "waterfall")))
                                                         (double (:total-to-gp (get % "waterfall"))))
                                                     distribution-recs))
        investments (mapv (fn [d]
                            {:deal-id (:id d)
                             :cost-basis (double (:ask-amount d))
                             :fair-value (latest-fair-value-mark st (:id d))
                             :exited? (= :exited (:status d))})
                          committed-deals)]
    (assoc (fund-nav {:total-called total-called
                      :total-invested-at-cost total-invested-at-cost
                      :total-exit-proceeds-received total-exit-proceeds-received
                      :total-distributed-to-lps total-distributed-to-lps
                      :investments investments})
           :unfunded (when (seq lps) (unfunded-commitments lps)))))
