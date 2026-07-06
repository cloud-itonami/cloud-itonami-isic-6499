(ns vcfund.captable
  "Pure term-sheet / cap-table math -- SAFE conversion (single AND
  multi-SAFE simultaneous conversion), SAFT (Simple Agreement for Future
  Tokens, the crypto-native analog of a SAFE) conversion, priced-round
  ownership/dilution (percentage AND absolute share-count terms),
  option-pool-shuffle, vesting schedules and option-exercise economics.

  This closes the term-sheet/cap-table half of the third R0 coverage gap
  (whole-fund NAV was the other half, closed separately in `vcfund.nav`).
  It is deliberately a calculator, not a governed op: setting or
  negotiating a valuation is an advisory/negotiation activity, not itself a
  capital-movement event, so it does not need `vcfund.governor` gating the
  way `:investment/commit` does. An operator uses this during DD/
  term-sheet negotiation to evaluate terms before proposing
  `:investment/commit`.

  `safe-conversion`/`priced-round-ownership` work in ownership PERCENTAGES
  only. `priced-round-shares`/`option-pool-shuffle`/`cap-table-ownership`/
  `multi-safe-conversion-shares` add ABSOLUTE share-count terms, given a
  caller-supplied fully-diluted share count -- this module does not invent
  one; a real company's actual share count comes from its cap-table
  system/transfer agent.

  Still deliberately SIMPLIFIED, and honestly so: `vesting-schedule` is
  linear-with-cliff only (no accelerated vesting on a change-of-control,
  no double-trigger provisions); `option-exercise-economics` is intrinsic
  value only (no tax treatment -- ISO vs. NSO, AMT, 83(b) elections);
  `multi-safe-conversion-shares` assumes every SAFE converts against the
  SAME `pre-conversion-shares` baseline (no modeling of conversion order
  mattering, which a truly rigorous simultaneous-conversion solve would
  need for some cap/discount combinations); no liquidation-preference
  stacking. Never silently claim this produces an authoritative
  company-of-record cap table.")

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

(defn saft-conversion
  "SAFT (Simple Agreement for Future Tokens) conversion math -- the
  crypto-native analog of `safe-conversion`: an investor's capital
  converts into a TOKEN allocation (a % of total token supply) at the
  Token Generation Event (TGE), at whichever is more favorable -- the
  SAFT's cap on the token network's implied fully-diluted valuation, or a
  discount off the TGE's public token price. Same underlying mechanic as
  `safe-conversion` (this fn is a thin re-labeling wrapper around it, not
  a duplicated implementation), denominated in token supply instead of
  company equity.

  `investment-amount` -- the SAFT's principal (fiat or stablecoin; this
  fn does no currency/FX conversion, see `vcfund.nav`'s single-base-
  currency limitation).
  `token-valuation-cap` -- the SAFT's cap on implied fully-diluted token
  network valuation, or nil for a discount-only SAFT.
  `discount-rate` -- e.g. 0.20 for a 20% discount off TGE price, or nil
  for a cap-only SAFT.
  `tge-fully-diluted-valuation` -- the token network's fully-diluted
  valuation at TGE (total token supply * TGE public price), a REAL
  external fact, never inferred.

  Returns `{:conversion-valuation :token-allocation-pct :basis}` -- the
  same shape `safe-conversion` returns, with `:token-allocation-pct` in
  place of `:ownership-pct` (a % of total token supply, not equity)."
  [{:keys [investment-amount token-valuation-cap discount-rate tge-fully-diluted-valuation]}]
  (let [result (safe-conversion {:investment-amount investment-amount
                                 :valuation-cap token-valuation-cap
                                 :discount-rate discount-rate
                                 :next-round-pre-money-valuation tge-fully-diluted-valuation})]
    {:conversion-valuation (:conversion-valuation result)
     :token-allocation-pct (:ownership-pct result)
     :basis (:basis result)}))

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

(defn multi-safe-conversion-shares
  "Multiple SAFEs converting simultaneously into the SAME priced round, in
  ABSOLUTE SHARE-COUNT terms. Each SAFE converts at its OWN most-favorable
  price-per-share (cap vs. discount, via `safe-conversion`'s conversion
  valuation, divided by `pre-conversion-shares`) -- the standard mechanic:
  every instrument's terms are independent, this is NOT a shared/blended
  price or a pro-rata split of one fixed pool.

  `safes` -- coll of `{:id .. :investment-amount .. :valuation-cap ..
  :discount-rate ..}` (same term shape `safe-conversion` takes).
  `next-round-pre-money-valuation` -- the priced round they all convert into.
  `pre-conversion-shares` -- founder/pool fully-diluted shares BEFORE any
  SAFE converts (i.e. before this round entirely).

  Returns `{:per-safe [{:id :price-per-share :new-shares} ..]
  :total-safe-shares :post-safe-conversion-shares}`."
  [{:keys [safes next-round-pre-money-valuation pre-conversion-shares]}]
  (when (empty? safes)
    (throw (ex-info "multi-safe-conversion-shares: at least one SAFE required" {})))
  (when-not (pos? pre-conversion-shares)
    (throw (ex-info "multi-safe-conversion-shares: pre-conversion-shares must be > 0" {})))
  (let [pre-conversion-shares (double pre-conversion-shares)
        per-safe (mapv (fn [{:keys [id investment-amount valuation-cap discount-rate]}]
                         (let [conv (safe-conversion {:investment-amount investment-amount
                                                      :valuation-cap valuation-cap
                                                      :discount-rate discount-rate
                                                      :next-round-pre-money-valuation
                                                      next-round-pre-money-valuation})
                               price-per-share (/ (:conversion-valuation conv) pre-conversion-shares)
                               new-shares (if (zero? price-per-share)
                                           ##Inf
                                           (/ (double investment-amount) price-per-share))]
                           {:id id :price-per-share price-per-share :new-shares new-shares}))
                       safes)
        total-safe-shares (reduce + (map :new-shares per-safe))]
    {:per-safe per-safe
     :total-safe-shares total-safe-shares
     :post-safe-conversion-shares (+ pre-conversion-shares total-safe-shares)}))

(defn vesting-schedule
  "Standard time-based equity vesting: `total-shares` vest linearly over
  `vesting-months`, with a `cliff-months` cliff -- 0% vested before the
  cliff, then a lump-sum catch-up exactly AT the cliff, then linear
  monthly thereafter. The most common startup grant shape: 4-year vest
  (48 months), 1-year cliff (12 months).

  `months-elapsed` -- time since the grant date, a REAL external fact,
  never inferred.

  Returns `{:vested-shares :vested-pct :cliff-reached?}`. Deliberately
  does NOT model accelerated vesting on a change-of-control or
  double-trigger provisions -- see ns docstring."
  [{:keys [total-shares vesting-months cliff-months months-elapsed]}]
  (when (neg? total-shares)
    (throw (ex-info "vesting-schedule: total-shares must be >= 0" {})))
  (when-not (pos? vesting-months)
    (throw (ex-info "vesting-schedule: vesting-months must be > 0" {})))
  (when-not (<= 0 cliff-months vesting-months)
    (throw (ex-info "vesting-schedule: cliff-months must be in [0, vesting-months]" {})))
  (when (neg? months-elapsed)
    (throw (ex-info "vesting-schedule: months-elapsed must be >= 0" {})))
  (let [total-shares (double total-shares)
        cliff-reached? (>= months-elapsed cliff-months)
        vested-months (min months-elapsed vesting-months)
        vested-shares (if cliff-reached?
                       (* total-shares (/ (double vested-months) (double vesting-months)))
                       0.0)]
    {:vested-shares vested-shares
     :vested-pct (if (zero? total-shares) 0.0 (/ vested-shares total-shares))
     :cliff-reached? cliff-reached?}))

(defn option-exercise-economics
  "Intrinsic value of exercising `shares` stock options at `strike-price`
  when the current fair-market-value-per-share is `fmv-per-share`.
  Underwater options (fmv <= strike) have `:intrinsic-value` 0, never
  negative -- nobody voluntarily exercises for a loss. Deliberately does
  NOT model tax treatment (ISO vs. NSO, AMT, 83(b) elections) -- see ns
  docstring.

  Returns `{:exercise-cost :market-value :intrinsic-value}`."
  [{:keys [shares strike-price fmv-per-share]}]
  (when (neg? shares) (throw (ex-info "option-exercise-economics: shares must be >= 0" {})))
  (when (neg? strike-price) (throw (ex-info "option-exercise-economics: strike-price must be >= 0" {})))
  (when (neg? fmv-per-share) (throw (ex-info "option-exercise-economics: fmv-per-share must be >= 0" {})))
  (let [shares (double shares)
        exercise-cost (* shares (double strike-price))
        market-value (* shares (double fmv-per-share))]
    {:exercise-cost exercise-cost
     :market-value market-value
     :intrinsic-value (max 0.0 (- market-value exercise-cost))}))
