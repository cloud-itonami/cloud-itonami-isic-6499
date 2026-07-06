(ns vcfund.nav
  "Whole-fund NAV (net asset value), unfunded-commitment and PER-LP
  capital-account reporting -- closes the first of the three remaining
  honest coverage gaps (whole-fund NAV, term-sheet negotiation workflow,
  absolute cap-table/option-pool modeling) tracked in README's
  \"Business-process coverage\" table.

  Read-only reporting, not a governed op: computing NAV moves no capital
  and makes no investment decision, so it does not need
  `vcfund.governor` gating the way the six governed ops do -- the same
  posture `vcfund.captable` takes on valuation math.

  Two layers:
    - `unfunded-commitments`/`fund-nav`/`lp-capital-account` -- pure
      functions over plain data, independently testable.
    - `fund-nav-report`/`lp-capital-account-report` -- thin adapters that
      read the live `vcfund.store` state and call the pure functions.
      Kept separate so the math itself has zero I/O dependency.
      `lp-capital-account-report` calls `fund-nav-report` internally
      rather than re-deriving the whole-fund totals independently, so an
      LP's own capital-account statement and the whole-fund NAV report
      always reconcile against the exact same numbers.

  Honest limitations (see docstrings): `management-fee-accrued` is a flat,
  non-compounded annual rate, OPTIONALLY with a single step-down after an
  investment period (a real LPA's fee schedule often steps down, e.g.
  2% -> 1.5%, around year 5 -- see `:investment-period-years`/
  `:post-investment-period-rate`; a fund with more than one step, or a
  compounding/re-basing schedule, is still not modeled); multi-currency FX
  is PARTIALLY modeled via `convert-currency`/OPTIONAL `:base-currency`/
  `:fx-rates` (REAL caller-supplied rates, never looked up or invented) on
  TWO layers -- `unfunded-commitments` (LP-level commitment/called
  amounts) and `fund-nav-report`'s held-investment valuation (a deal's
  cost-basis/`:fair-value-mark`, converted via the deal's own `:currency`)
  -- but NOT a third: `total-invested-at-cost` (summed from commitment-
  history records, which do not themselves carry a currency tag) and
  every distribution/waterfall figure stay single-currency, a still-
  unmodeled gap; a deal's fair value defaults to its cost basis (the
  commitment amount) until an operator records a `:fair-value-mark` KPI
  via `:portfolio/report` -- never silently mark up an unvalued
  investment. `lp-capital-account` pro-rates by COMMITMENT SHARE, a
  static split for the life of the fund -- an LP selling their interest
  to another investor mid-fund (a secondary transfer) is not modeled at
  all; every LP's ownership share here is exactly what it was when they
  originally committed."

  (:require [vcfund.store :as store]))

(def default-management-fee-rate
  "2% annual, on committed capital -- a common (not universal) VC fund
  management-fee rate. A real LPA's actual rate/basis/step-down schedule
  always governs; this is a default for demos/tests only."
  0.02)

(defn convert-currency
  "Converts `amount` (denominated in `from-currency`) into `to-currency`
  using a caller-supplied `rate` (units of `to-currency` per 1 unit of
  `from-currency`) -- a REAL market fact the caller supplies (a spot
  rate, a rate-lock, whatever the fund's actual FX policy is), never
  looked up or invented here. Same-currency conversion (`from-currency`
  = `to-currency`) is always a no-op regardless of `rate` -- a caller
  need not supply a trivial 1.0 rate for an amount already in the base
  currency."
  [{:keys [amount from-currency to-currency rate]}]
  (when (neg? amount) (throw (ex-info "convert-currency: amount must be >= 0" {})))
  (when-not (and from-currency (not= from-currency ""))
    (throw (ex-info "convert-currency: from-currency required" {})))
  (when-not (and to-currency (not= to-currency ""))
    (throw (ex-info "convert-currency: to-currency required" {})))
  (if (= from-currency to-currency)
    (double amount)
    (do (when-not (and rate (pos? rate))
          (throw (ex-info (str "convert-currency: a positive fx-rate is required to convert "
                              from-currency " -> " to-currency) {})))
        (* (double amount) (double rate)))))

(defn unfunded-commitments
  "Per-LP and fund-wide unfunded commitment: commitment - called. The
  fund-wide total is what the GP can still legally call under the LPA
  (subject to any investment-period/re-up terms, which this does not
  model).

  OPTIONALLY converts every LP's commitment/called amount into ONE
  `base-currency` before summing, given a caller-supplied `fx-rates` map
  (`{currency-code rate-to-base}`, via `convert-currency` -- REAL market
  facts, never looked up here). Pass `:base-currency`/`:fx-rates` for a
  multi-currency LP directory; omit both (the default) to sum face value
  with no conversion, the single-currency behavior every earlier caller
  already relies on."
  [lps & [{:keys [base-currency fx-rates]}]]
  (when (empty? lps) (throw (ex-info "unfunded-commitments: at least one LP required" {})))
  (let [conv (fn [amount currency]
               (if (and base-currency currency (not= currency base-currency))
                 (convert-currency {:amount amount :from-currency currency
                                   :to-currency base-currency :rate (get fx-rates currency)})
                 (double amount)))
        rows (mapv (fn [{:keys [id commitment-amount called-amount currency]}]
                     (let [commitment-amount (conv commitment-amount currency)
                           called-amount (conv (or called-amount 0) currency)]
                       {:lp-id id
                        :commitment-amount commitment-amount
                        :called-amount called-amount
                        :unfunded (- commitment-amount called-amount)}))
                   lps)]
    {:by-lp rows
     :total-commitments (reduce + (map :commitment-amount rows))
     :total-called (reduce + (map :called-amount rows))
     :total-unfunded (reduce + (map :unfunded rows))}))

(defn management-fee-accrued
  "Cumulative management fee accrued to date -- a flat, non-compounded
  annual rate on a fee basis (typically total committed capital during a
  fund's investment period; some LPAs switch the basis to invested-at-cost
  capital afterward -- this fn does not decide which regime applies, the
  caller supplies `fee-basis` matching the fund's actual LPA provision).

  OPTIONALLY models a single step-down after the investment period: pass
  `:investment-period-years` and `:post-investment-period-rate` to accrue
  `annual-fee-rate` for the first `investment-period-years` years and
  `post-investment-period-rate` for every year after -- the common LPA
  shape (e.g. 2% during the investment period, 1.5% after). Omit both
  (the default) for the flat single-rate behavior every earlier caller
  already relies on -- fully backward compatible. Honestly simplified:
  only ONE step is modeled (a fund with multiple step-downs, or a
  re-based fee basis after the step, is not)."
  [{:keys [fee-basis annual-fee-rate years-elapsed
           investment-period-years post-investment-period-rate]}]
  (when (neg? fee-basis) (throw (ex-info "management-fee-accrued: fee-basis must be >= 0" {})))
  (when (neg? annual-fee-rate) (throw (ex-info "management-fee-accrued: annual-fee-rate must be >= 0" {})))
  (when (neg? years-elapsed) (throw (ex-info "management-fee-accrued: years-elapsed must be >= 0" {})))
  (when (and investment-period-years (neg? investment-period-years))
    (throw (ex-info "management-fee-accrued: investment-period-years must be >= 0" {})))
  (when (and post-investment-period-rate (neg? post-investment-period-rate))
    (throw (ex-info "management-fee-accrued: post-investment-period-rate must be >= 0" {})))
  (let [fee-basis (double fee-basis)
        annual-fee-rate (double annual-fee-rate)
        years-elapsed (double years-elapsed)]
    (if (and investment-period-years post-investment-period-rate)
      (let [investment-period-years (double investment-period-years)
            post-investment-period-rate (double post-investment-period-rate)
            in-period-years (min years-elapsed investment-period-years)
            post-period-years (max 0.0 (- years-elapsed investment-period-years))]
        (+ (* fee-basis annual-fee-rate in-period-years)
           (* fee-basis post-investment-period-rate post-period-years)))
      (* fee-basis annual-fee-rate years-elapsed))))

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
  `management-fees-accrued` -- OPTIONAL, defaults to 0 (backward
  compatible); cumulative fees to date (see `management-fee-accrued`),
  netted out of cash like any other fund expense.

  Returns `{:net-cash :held-fair-value :nav}`."
  [{:keys [total-called total-invested-at-cost total-exit-proceeds-received
           total-distributed-to-lps investments management-fees-accrued]
    :or {management-fees-accrued 0}}]
  (doseq [[k v] {:total-called total-called :total-invested-at-cost total-invested-at-cost
                :total-exit-proceeds-received total-exit-proceeds-received
                :total-distributed-to-lps total-distributed-to-lps
                :management-fees-accrued management-fees-accrued}]
    (when (neg? v) (throw (ex-info (str "fund-nav: " k " must be >= 0") {}))))
  (let [net-cash (- (+ (double total-called) (double total-exit-proceeds-received))
                    (+ (double total-invested-at-cost) (double total-distributed-to-lps)
                       (double management-fees-accrued)))
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
  independently testable.

  `fund-life-years` -- OPTIONAL, defaults to 0 (no fee accrual, backward
  compatible). Years since the fund's first close -- a REAL external fact
  the caller supplies, never inferred; management fees accrue on total LP
  commitments at `annual-fee-rate` (default `default-management-fee-rate`).
  `investment-period-years`/`post-investment-period-rate` -- OPTIONAL, see
  `management-fee-accrued` for the step-down they model; omit both for the
  flat-rate default.
  `base-currency`/`fx-rates` -- OPTIONAL; converts LP-level commitment/
  called amounts (see `unfunded-commitments`) AND each held deal's
  cost-basis/fair-value-mark (via the deal's own `:currency`) into ONE
  base currency. Does NOT convert `total-invested-at-cost` (summed from
  commitment-history records, which carry no currency tag) or any
  distribution/waterfall figure -- those stay single-currency regardless
  (see ns docstring). Omit both for single-currency, no-conversion
  behavior (the default -- every earlier caller's exact behavior).

  The returned map also exposes `:fee-basis`/`:annual-fee-rate`/
  `:years-elapsed` (the FX-converted total LP commitments, the rate, and
  `fund-life-years`, respectively -- the exact inputs `management-fee-
  accrued` used to compute `:management-fees-accrued`) alongside the
  accrual itself, purely additive, so an operator/integration can hand
  the whole slice to a downstream management-fee-drawdown actor (e.g.
  `cloud-itonami-isic-6630`'s `fundmgmt.governor`, which independently
  re-verifies it -- see that repo's ADR for the documented cross-repo
  data contract) without separately re-deriving the fee basis."
  ([st] (fund-nav-report st {}))
  ([st {:keys [fund-life-years annual-fee-rate investment-period-years post-investment-period-rate
              base-currency fx-rates]
        :or {fund-life-years 0 annual-fee-rate default-management-fee-rate}}]
  (let [lps (store/all-lps st)
        committed-deals (filter #(contains? #{:committed :exited} (:status %)) (store/all-deals st))
        commitment-recs (store/commitment-history st)
        distribution-recs (store/distribution-history st)
        conv (fn [amount currency]
               (if (and base-currency currency (not= currency base-currency))
                 (convert-currency {:amount amount :from-currency currency
                                    :to-currency base-currency :rate (get fx-rates currency)})
                 (double amount)))
        total-called (reduce + (map #(conv (or (:called-amount %) 0) (:currency %)) lps))
        total-invested-at-cost (reduce + (map #(double (get % "amount" 0)) commitment-recs))
        total-distributed-to-lps (reduce + (map #(double (:total-to-lp (get % "waterfall"))) distribution-recs))
        total-exit-proceeds-received (reduce + (map #(+ (double (:total-to-lp (get % "waterfall")))
                                                         (double (:total-to-gp (get % "waterfall"))))
                                                     distribution-recs))
        fee-basis (reduce + (map #(conv (:commitment-amount %) (:currency %)) lps))
        management-fees-accrued (management-fee-accrued
                                  {:fee-basis fee-basis
                                   :annual-fee-rate annual-fee-rate
                                   :years-elapsed fund-life-years
                                   :investment-period-years investment-period-years
                                   :post-investment-period-rate post-investment-period-rate})
        investments (mapv (fn [d]
                            (let [fv (latest-fair-value-mark st (:id d))]
                              {:deal-id (:id d)
                               :cost-basis (conv (:ask-amount d) (:currency d))
                               :fair-value (when fv (conv fv (:currency d)))
                               :exited? (= :exited (:status d))}))
                          committed-deals)]
    (assoc (fund-nav {:total-called total-called
                      :total-invested-at-cost total-invested-at-cost
                      :total-exit-proceeds-received total-exit-proceeds-received
                      :total-distributed-to-lps total-distributed-to-lps
                      :management-fees-accrued management-fees-accrued
                      :investments investments})
           :unfunded (when (seq lps)
                       (unfunded-commitments lps {:base-currency base-currency :fx-rates fx-rates}))
           :management-fees-accrued management-fees-accrued
           :total-distributed-to-lps total-distributed-to-lps
           :fee-basis fee-basis
           :annual-fee-rate annual-fee-rate
           :years-elapsed fund-life-years))))

(defn lp-capital-account
  "One LP's capital-account slice -- their own commitment, called-to-date
  and unfunded remainder (`lp-row`, an `unfunded-commitments`-style
  `{:lp-id :commitment-amount :called-amount :unfunded}` -- already
  FX-converted if the caller asked for that), PLUS their pro-rata share
  of the fund's aggregate distributions-to-date and current whole-fund
  NAV.

  `total-commitments` -- fund-wide sum of every LP's (possibly
  FX-converted) commitment-amount, the SAME denominator
  `unfunded-commitments` already computed for `lp-row` -- this fn does
  NOT recompute it, so `:ownership-pct` here is guaranteed consistent
  with the share every actual capital call already pro-rates against
  (`vcfund.registry/capital-call-allocations`).
  `total-distributed-to-lps`/`fund-nav` -- the fund's whole-fund totals
  (`fund-nav-report`'s own return) -- not independently recomputed here
  either, so an LP's capital-account statement always reconciles against
  the SAME numbers the whole-fund NAV report shows, never a second,
  divergent calculation.

  Pro-rata by COMMITMENT SHARE (the standard LPA convention), NOT
  called-to-date share -- an LP who has called less capital than another
  still owns the SAME fractional slice of distributions/NAV their
  commitment entitles them to. Honestly limited: does not model an
  ownership % that changed mid-fund via a secondary transfer of an LP's
  interest (secondary transfers are not modeled at all -- see ns
  docstring), and NAV/distribution totals it shares out inherit whatever
  FX/currency limitations `fund-nav-report` itself has.

  Returns `lp-row` plus `:ownership-pct :distributed-to-date
  :nav-share`."
  [lp-row {:keys [total-commitments total-distributed-to-lps fund-nav]}]
  (when-not (pos? total-commitments)
    (throw (ex-info "lp-capital-account: total-commitments must be > 0" {})))
  (when (neg? total-distributed-to-lps)
    (throw (ex-info "lp-capital-account: total-distributed-to-lps must be >= 0" {})))
  (let [ownership-pct (/ (double (:commitment-amount lp-row)) (double total-commitments))]
    (assoc lp-row
           :ownership-pct ownership-pct
           :distributed-to-date (* ownership-pct (double total-distributed-to-lps))
           :nav-share (* ownership-pct (double fund-nav)))))

(defn lp-capital-account-report
  "Adapter: every LP's capital-account statement (`lp-capital-account`),
  computed off the SAME `fund-nav-report` an operator console already
  calls for the whole-fund view -- never a second, independently-derived
  set of totals (so the two reports always reconcile). Same `opts`
  `fund-nav-report` accepts (fee/step-down/FX options); passed straight
  through, so a multi-currency fund's per-LP statements are FX-converted
  into the same `:base-currency` the whole-fund view uses.

  Returns a vector of `lp-capital-account` results, one per LP, empty if
  the fund has no LPs."
  ([st] (lp-capital-account-report st {}))
  ([st opts]
   (let [nav-report (fund-nav-report st opts)
         by-lp (:by-lp (:unfunded nav-report))
         totals {:total-commitments (:total-commitments (:unfunded nav-report))
                 :total-distributed-to-lps (:total-distributed-to-lps nav-report)
                 :fund-nav (:nav nav-report)}]
     (mapv #(lp-capital-account % totals) by-lp))))
