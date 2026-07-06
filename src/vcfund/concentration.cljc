(ns vcfund.concentration
  "Portfolio CONCENTRATION reporting -- what fraction of capital actually
  deployed (each committed/exited deal's own cost basis, the ask-amount,
  the SAME basis `vcfund.nav/fund-nav-report`'s `investments` list uses)
  sits in each deal's `:sector`/`:investment-stage`. Read-only reporting,
  not a governed op, the same posture `vcfund.nav` takes: computing a
  breakdown moves no capital and makes no investment decision.

  Deliberately a SEPARATE namespace from `vcfund.nav` -- concentration is
  portfolio-COMPOSITION/compliance reporting, a different concern from
  NAV/valuation math, even though both are read-only report adapters over
  the same deal directory.

  A deal missing `:sector` or `:investment-stage` groups under the
  `:unclassified` sentinel -- never silently dropped or guessed.

  This is the upstream fact `cloud-itonami-isic-6630`'s
  `fundmgmt.governor` independently checks against its OWN mandate-
  defined sector/stage concentration caps (a fact THIS repo does not
  hold -- an LPA-authorized limit is the management company's own
  fiduciary record, not the investment actor's) before disclosing
  guideline compliance to LPs. See that repo's ADR for the documented
  cross-repo data contract."
  (:require [vcfund.store :as store]
            [vcfund.nav :as nav]))

(defn- conv [amount currency base-currency fx-rates]
  (if (and base-currency currency (not= currency base-currency))
    (nav/convert-currency {:amount amount :from-currency currency
                           :to-currency base-currency :rate (get fx-rates currency)})
    (double amount)))

(defn- by-key [deals k total conv-fn]
  (->> deals
       (group-by #(or (get % k) :unclassified))
       (map (fn [[v ds]]
              (let [amt (reduce + (map #(conv-fn (:ask-amount %) (:currency %)) ds))]
                [v {:amount amt :fraction (if (zero? total) 0.0 (/ amt total))}])))
       (into {})))

(defn concentration-report
  "Adapter: reads the live `vcfund.store` state and computes portfolio
  concentration by sector and by investment-stage, across committed
  (not-yet-exited or already-exited) deals -- the same `committed-deals`
  filter `vcfund.nav/fund-nav-report` uses.

  `base-currency`/`fx-rates` -- OPTIONAL, the SAME `vcfund.nav/convert-
  currency`-driven convention `fund-nav-report` uses; omit both for
  single-currency, no-conversion behavior.

  Returns `{:total-invested-at-cost n
            :by-sector {sector-or-:unclassified {:amount n :fraction n}}
            :by-investment-stage {stage-or-:unclassified {:amount n :fraction n}}}`.
  An empty portfolio (no committed/exited deals) reports `0.0` totals and
  empty breakdown maps -- never a divide-by-zero exception."
  ([st] (concentration-report st {}))
  ([st {:keys [base-currency fx-rates]}]
   (let [committed-deals (filter #(contains? #{:committed :exited} (:status %)) (store/all-deals st))
         conv-fn (fn [amount currency] (conv amount currency base-currency fx-rates))
         total (reduce + (map #(conv-fn (:ask-amount %) (:currency %)) committed-deals))]
     {:total-invested-at-cost total
      :by-sector (by-key committed-deals :sector total conv-fn)
      :by-investment-stage (by-key committed-deals :investment-stage total conv-fn)})))
