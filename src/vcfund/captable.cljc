(ns vcfund.captable
  "Pure term-sheet / cap-table math -- SAFE conversion, priced-round
  ownership/dilution (percentage AND absolute share-count terms), and the
  option-pool-shuffle mechanic.

  This closes the term-sheet/cap-table half of the third R0 coverage gap
  (whole-fund NAV was the other half, closed separately in `vcfund.nav`).
  It is deliberately a calculator, not a governed op: setting or
  negotiating a valuation is an advisory/negotiation activity, not itself a
  capital-movement event, so it does not need `vcfund.governor` gating the
  way `:investment/commit` does. An operator uses this during DD/
  term-sheet negotiation to evaluate terms before proposing
  `:investment/commit`.

  `safe-conversion`/`priced-round-ownership` work in ownership PERCENTAGES
  only. `priced-round-shares`/`option-pool-shuffle`/`cap-table-ownership`
  add ABSOLUTE share-count terms, given a caller-supplied fully-diluted
  share count -- this module does not invent one; a real company's actual
  share count comes from its cap-table system/transfer agent.

  Still deliberately SIMPLIFIED, and honestly so: no vesting schedules, no
  option strike-price/exercise modeling, no liquidation-preference
  stacking, and no proration across MULTIPLE SAFEs converting
  simultaneously (which SAFE converts first affects everyone else's
  dilution -- out of scope here, model one instrument at a time). Never
  silently claim this produces an authoritative company-of-record cap
  table.")

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

(defn option-pool-shuffle
  "The standard 'pre-money option pool' term-sheet mechanic: the investor
  requires a new/refreshed option pool sized at `target-pool-pct` of the
  PRE-money cap table (existing holders + the new pool, NOT including the
  new investor's shares) -- carved out of existing holders' ownership, not
  the new investor's. This is the single most common source of dilution
  founders under-account for when reading a term sheet's headline
  valuation.

  `pre-round-shares` -- existing fully-diluted shares BEFORE adding the new
  pool (i.e. before this mechanic, not before the round).
  `target-pool-pct` -- e.g. 0.10 for a 10% pool, expressed as a fraction of
  the resulting pre-money share count.

  Returns `{:new-pool-shares :pre-money-share-count}` -- feed
  `:pre-money-share-count` into `priced-round-shares` as
  `pre-round-shares` so the pre-money valuation is priced against a cap
  table that already includes the pool, exactly like a real term sheet."
  [{:keys [pre-round-shares target-pool-pct]}]
  (when (neg? pre-round-shares)
    (throw (ex-info "option-pool-shuffle: pre-round-shares must be >= 0" {})))
  (when-not (<= 0 target-pool-pct 1)
    (throw (ex-info "option-pool-shuffle: target-pool-pct must be in [0,1]" {})))
  (let [pre-round-shares (double pre-round-shares)
        target-pool-pct (double target-pool-pct)
        new-pool-shares (if (= target-pool-pct 1.0)
                          ##Inf
                          (/ (* target-pool-pct pre-round-shares) (- 1.0 target-pool-pct)))]
    {:new-pool-shares new-pool-shares
     :pre-money-share-count (+ pre-round-shares new-pool-shares)}))

(defn priced-round-shares
  "Absolute share-count math for a priced round, given the ACTUAL
  fully-diluted share count outstanding immediately before this round
  (already including any option pool carved out via `option-pool-shuffle`,
  if the term sheet calls for one -- this function does not size a pool on
  its own).

  price-per-share = pre-money-valuation / pre-round-shares
  new-shares = investment-amount / price-per-share

  Returns `{:price-per-share :new-shares :post-round-shares
  :new-investor-ownership-pct}`."
  [{:keys [investment-amount pre-money-valuation pre-round-shares]}]
  (when (neg? investment-amount)
    (throw (ex-info "priced-round-shares: investment-amount must be >= 0" {})))
  (when (neg? pre-money-valuation)
    (throw (ex-info "priced-round-shares: pre-money-valuation must be >= 0" {})))
  (when-not (pos? pre-round-shares)
    (throw (ex-info "priced-round-shares: pre-round-shares must be > 0" {})))
  (let [investment-amount (double investment-amount)
        pre-round-shares (double pre-round-shares)
        price-per-share (/ (double pre-money-valuation) pre-round-shares)
        new-shares (if (zero? price-per-share) ##Inf (/ investment-amount price-per-share))
        post-round-shares (+ pre-round-shares new-shares)]
    {:price-per-share price-per-share
     :new-shares new-shares
     :post-round-shares post-round-shares
     :new-investor-ownership-pct (if (zero? post-round-shares) 0.0 (/ new-shares post-round-shares))}))

(defn cap-table-ownership
  "Fully-diluted ownership % for each holder in a cap table, given absolute
  share counts. Pure, read-only -- does not model vesting, unexercised
  option strike prices, or liquidation-preference stacking (see ns
  docstring).

  `entries` -- coll of `{:holder .. :shares ..}`.

  Returns `{:total-shares :by-holder [{:holder :shares :ownership-pct} ..]}`."
  [entries]
  (when (empty? entries)
    (throw (ex-info "cap-table-ownership: at least one entry required" {})))
  (let [total (reduce + (map :shares entries))]
    (when (neg? total)
      (throw (ex-info "cap-table-ownership: total shares must be >= 0" {})))
    {:total-shares total
     :by-holder (mapv (fn [{:keys [holder shares]}]
                        {:holder holder :shares shares
                         :ownership-pct (if (zero? total) 0.0 (/ (double shares) total))})
                      entries)}))
