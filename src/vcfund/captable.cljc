(ns vcfund.captable
  "Pure term-sheet / cap-table math -- SAFE conversion and priced-round
  ownership/dilution, in PERCENTAGE-OF-COMPANY terms.

  This closes part of the third R0 coverage gap (term sheet negotiation /
  cap-table math had no calculator at all). It is deliberately a
  calculator, not a governed op: setting or negotiating a valuation is an
  advisory/negotiation activity, not itself a capital-movement event, so it
  does not need `vcfund.governor` gating the way `:investment/commit`
  does. An operator uses this during DD/term-sheet negotiation to evaluate
  terms before proposing `:investment/commit`.

  Deliberately SIMPLIFIED, and honestly so: this works in ownership
  PERCENTAGES only, not absolute share counts. A real cap table needs
  actual fully-diluted share counts, option-pool sizing/refresh, and
  proration across multiple SAFEs converting simultaneously (which SAFE
  converts first affects everyone else's dilution) -- all out of scope
  here. Never silently claim this produces an authoritative cap table;
  it estimates ownership percentage only.")

(defn safe-conversion
  "SAFE conversion math: the SAFE holder converts at whichever is more
  favorable to them -- the valuation cap, or the discounted price off the
  next priced round's pre-money valuation. At least one of
  `valuation-cap`/`discount-rate` is required (a SAFE needs at least one
  investor-protection term; an uncapped, no-discount SAFE is not a
  standard instrument this models).

  `investment-amount` -- the SAFE's principal.
  `valuation-cap` -- the SAFE's cap, or nil for a discount-only SAFE.
  `discount-rate` -- e.g. 0.20 for a 20% discount, or nil for a cap-only SAFE.
  `next-round-pre-money-valuation` -- the priced round this SAFE converts into.

  Returns `{:conversion-valuation :ownership-pct :basis (:cap|:discount)}`."
  [{:keys [investment-amount valuation-cap discount-rate next-round-pre-money-valuation]}]
  (when (neg? investment-amount)
    (throw (ex-info "safe-conversion: investment-amount must be >= 0" {})))
  (when (and (nil? valuation-cap) (nil? discount-rate))
    (throw (ex-info "safe-conversion: at least one of valuation-cap/discount-rate required" {})))
  (when (and (some? valuation-cap) (neg? valuation-cap))
    (throw (ex-info "safe-conversion: valuation-cap must be >= 0" {})))
  (when (and (some? discount-rate) (not (<= 0 discount-rate 1)))
    (throw (ex-info "safe-conversion: discount-rate must be in [0,1]" {})))
  (when (neg? next-round-pre-money-valuation)
    (throw (ex-info "safe-conversion: next-round-pre-money-valuation must be >= 0" {})))
  (let [discounted-valuation (when discount-rate
                                (* (double next-round-pre-money-valuation) (- 1.0 (double discount-rate))))
        cap-valuation (when valuation-cap (double valuation-cap))
        basis (cond
                (and cap-valuation discounted-valuation)
                (if (<= cap-valuation discounted-valuation) :cap :discount)
                cap-valuation :cap
                :else :discount)
        conversion-valuation (min (or cap-valuation ##Inf)
                                  (or discounted-valuation ##Inf))
        investment-amount (double investment-amount)]
    {:conversion-valuation conversion-valuation
     :ownership-pct (/ investment-amount (+ conversion-valuation investment-amount))
     :basis basis}))

(defn priced-round-ownership
  "Ownership/dilution math for a priced equity round, in percentage terms.

  `investment-amount` -- new capital in this round.
  `pre-money-valuation` -- the round's agreed pre-money valuation.

  Returns `{:post-money-valuation :new-investor-ownership-pct
  :dilution-factor}` -- existing holders' post-round ownership = their
  pre-round ownership * `:dilution-factor`."
  [{:keys [investment-amount pre-money-valuation]}]
  (when (neg? investment-amount)
    (throw (ex-info "priced-round-ownership: investment-amount must be >= 0" {})))
  (when (neg? pre-money-valuation)
    (throw (ex-info "priced-round-ownership: pre-money-valuation must be >= 0" {})))
  (let [investment-amount (double investment-amount)
        post-money (+ (double pre-money-valuation) investment-amount)
        ownership-pct (if (zero? post-money) 0.0 (/ investment-amount post-money))]
    {:post-money-valuation post-money
     :new-investor-ownership-pct ownership-pct
     :dilution-factor (- 1.0 ownership-pct)}))
