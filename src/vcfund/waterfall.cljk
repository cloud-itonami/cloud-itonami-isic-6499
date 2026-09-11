(ns vcfund.waterfall
  "Whole-fund (European-style) waterfall reconciliation and GP clawback --
  closes the gap flagged since R0 in `vcfund.registry/distribute-
  waterfall`'s own docstring: 'A real whole-fund European waterfall needs
  fund-level state spanning every investment, which is out of scope for a
  single investment record.' This is that fund-level state.

  Every actual distribution in this actor is deal-by-deal (American-style:
  `vcfund.registry/distribute-waterfall` computes return-of-capital ->
  preferred -> carry for ONE investment, and GP carry is paid out
  immediately on that deal's exit). The risk an American-style regime
  carries that a European (whole-fund) one doesn't: an early home-run exit
  pays real GP carry right away, but if LATER deals in the same fund lose
  money, the GP may have already been paid MORE carry than the fund's
  overall (whole-fund) performance ever entitled them to. The difference
  is a GP CLAWBACK -- money the GP owes back to the fund.

  `whole-fund-waterfall` answers exactly that: 'if we had applied ONE
  preferred return and ONE carry split to the fund's aggregate performance
  instead of deal-by-deal, what would the GP be entitled to -- and does
  that fall short of what deal-by-deal already paid them?'

  Read-only reconciliation, not a governed op -- like `vcfund.nav`,
  computing this moves no capital. A real clawback repayment (the GP
  actually wiring money back to the fund) would be its own governed,
  human-approved capital-movement act; out of scope for this R0, same as
  every other capital-movement edge this actor doesn't implement.

  Same simple (non-compounded) preferred-return discipline as
  `vcfund.registry/distribute-waterfall` -- ONE fund-wide simple-interest
  clock over `fund-life-years`, not a real IRR-based hurdle. Honestly
  simplified, not silently."
  (:require [vcfund.store :as store]))

(defn whole-fund-waterfall
  "`total-contributed-capital` -- cumulative capital committed/deployed to
  portfolio companies fund-wide (cost basis, across every deal, exited or
  not).
  `total-exit-proceeds` -- cumulative gross proceeds from every REALIZED
  exit to date (before any waterfall split).
  `fund-life-years` -- years since the fund's first close, a REAL external
  fact the caller supplies, never inferred.
  `preferred-return-rate`/`carry-rate` -- the fund's LPA terms.
  `total-gp-carry-already-paid` -- sum of `:total-to-gp` across every
  deal-by-deal `vcfund.registry/distribute-waterfall` result to date.

  Returns `{:model :return-of-capital :preferred-return-paid
  :whole-fund-gp-entitlement :total-gp-carry-already-paid :gp-clawback}`.
  `:gp-clawback` is the excess (never negative -- if deal-by-deal actually
  UNDER-paid relative to whole-fund entitlement, that is a separate,
  ordinary catch-up payment, not modeled here as a distinct concept, since
  no op currently pays it out)."
  [{:keys [total-contributed-capital total-exit-proceeds fund-life-years
           preferred-return-rate carry-rate total-gp-carry-already-paid]}]
  (doseq [[k v] {:total-contributed-capital total-contributed-capital
                :total-exit-proceeds total-exit-proceeds
                :fund-life-years fund-life-years
                :total-gp-carry-already-paid total-gp-carry-already-paid}]
    (when (neg? v) (throw (ex-info (str "whole-fund-waterfall: " k " must be >= 0") {}))))
  (when (neg? preferred-return-rate)
    (throw (ex-info "whole-fund-waterfall: preferred-return-rate must be >= 0" {})))
  (when-not (<= 0 carry-rate 1)
    (throw (ex-info "whole-fund-waterfall: carry-rate must be in [0,1]" {})))
  (let [total-contributed-capital (double total-contributed-capital)
        total-exit-proceeds (double total-exit-proceeds)
        return-of-capital (min total-exit-proceeds total-contributed-capital)
        after-roc (max 0.0 (- total-exit-proceeds return-of-capital))
        preferred-due (* total-contributed-capital (double preferred-return-rate) (double fund-life-years))
        preferred-paid (min after-roc preferred-due)
        after-preferred (max 0.0 (- after-roc preferred-paid))
        whole-fund-gp-entitlement (* after-preferred (double carry-rate))
        already-paid (double total-gp-carry-already-paid)]
    {:model :whole-fund-european-simple-preferred
     :return-of-capital return-of-capital
     :preferred-return-paid preferred-paid
     :whole-fund-gp-entitlement whole-fund-gp-entitlement
     :total-gp-carry-already-paid already-paid
     :gp-clawback (max 0.0 (- already-paid whole-fund-gp-entitlement))}))

(defn whole-fund-waterfall-report
  "Adapter: reads the live `vcfund.store` state (every commitment +
  distribution to date) and computes the whole-fund waterfall
  reconciliation. `fund-life-years` and the LPA rate terms are REAL
  external facts the caller supplies (see `whole-fund-waterfall`); the
  rate defaults mirror `vcfund.store`'s deal-by-deal defaults so a
  whole-fund reconciliation and the deal-by-deal distributions it
  reconciles against use the same LPA terms unless the caller overrides."
  [st fund-life-years
   & [{:keys [preferred-return-rate carry-rate]
       :or {preferred-return-rate store/default-preferred-return-rate
            carry-rate store/default-carry-rate}}]]
  (let [commitment-recs (store/commitment-history st)
        distribution-recs (store/distribution-history st)
        total-contributed (reduce + (map #(double (get % "amount" 0)) commitment-recs))
        total-exit-proceeds (reduce + (map #(+ (double (:total-to-lp (get % "waterfall")))
                                               (double (:total-to-gp (get % "waterfall"))))
                                           distribution-recs))
        total-gp-already-paid (reduce + (map #(double (:total-to-gp (get % "waterfall"))) distribution-recs))]
    (whole-fund-waterfall {:total-contributed-capital total-contributed
                           :total-exit-proceeds total-exit-proceeds
                           :fund-life-years fund-life-years
                           :preferred-return-rate preferred-return-rate
                           :carry-rate carry-rate
                           :total-gp-carry-already-paid total-gp-already-paid})))
