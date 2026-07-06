(ns vcfund.captable-test
  (:require [clojure.test :refer [deftest is testing]]
            [vcfund.captable :as captable]))

(defn- close? [a b] (< (Math/abs (- (double a) (double b))) 1e-6))

(deftest safe-conversion-uses-the-more-favorable-of-cap-or-discount
  (testing "cap is lower (more favorable to the SAFE holder) -> converts at cap"
    (let [r (captable/safe-conversion {:investment-amount 500000 :valuation-cap 8000000
                                       :discount-rate 0.20 :next-round-pre-money-valuation 12000000})]
      ;; discounted valuation = 12M * 0.8 = 9.6M > 8M cap -> cap wins
      (is (= :cap (:basis r)))
      (is (close? 8000000.0 (:conversion-valuation r)))
      (is (close? (/ 500000.0 8500000.0) (:ownership-pct r)))))
  (testing "discount is lower (more favorable) -> converts at discount"
    (let [r (captable/safe-conversion {:investment-amount 500000 :valuation-cap 20000000
                                       :discount-rate 0.20 :next-round-pre-money-valuation 12000000})]
      ;; discounted valuation = 12M * 0.8 = 9.6M < 20M cap -> discount wins
      (is (= :discount (:basis r)))
      (is (close? 9600000.0 (:conversion-valuation r))))))

(deftest safe-conversion-handles-cap-only-and-discount-only
  (let [cap-only (captable/safe-conversion {:investment-amount 500000 :valuation-cap 8000000
                                            :discount-rate nil :next-round-pre-money-valuation 12000000})
        discount-only (captable/safe-conversion {:investment-amount 500000 :valuation-cap nil
                                                  :discount-rate 0.20 :next-round-pre-money-valuation 12000000})]
    (is (= :cap (:basis cap-only)))
    (is (close? 8000000.0 (:conversion-valuation cap-only)))
    (is (= :discount (:basis discount-only)))
    (is (close? 9600000.0 (:conversion-valuation discount-only)))))

(deftest safe-conversion-validation-rules
  (is (thrown? Exception (captable/safe-conversion {:investment-amount -1 :valuation-cap 1
                                                    :discount-rate nil :next-round-pre-money-valuation 1})))
  (is (thrown? Exception (captable/safe-conversion {:investment-amount 1 :valuation-cap nil
                                                    :discount-rate nil :next-round-pre-money-valuation 1}))
      "at least one of cap/discount required")
  (is (thrown? Exception (captable/safe-conversion {:investment-amount 1 :valuation-cap -1
                                                    :discount-rate nil :next-round-pre-money-valuation 1})))
  (is (thrown? Exception (captable/safe-conversion {:investment-amount 1 :valuation-cap 1
                                                    :discount-rate 1.5 :next-round-pre-money-valuation 1}))))

;; ----------------------------- saft-conversion (crypto) -----------------------------

(deftest saft-conversion-mirrors-safe-conversion-in-token-terms
  (testing "same cap-vs-discount mechanic as safe-conversion, denominated in token supply"
    (let [r (captable/saft-conversion {:investment-amount 500000 :token-valuation-cap 8000000
                                       :discount-rate 0.20 :tge-fully-diluted-valuation 12000000})]
      (is (= :cap (:basis r)))
      (is (close? 8000000.0 (:conversion-valuation r)))
      (is (close? (/ 500000.0 8500000.0) (:token-allocation-pct r))))))

(deftest priced-round-ownership-computes-post-money-and-dilution
  (let [r (captable/priced-round-ownership {:investment-amount 2000000 :pre-money-valuation 8000000})]
    (is (close? 10000000.0 (:post-money-valuation r)))
    (is (close? 0.2 (:new-investor-ownership-pct r)))
    (is (close? 0.8 (:dilution-factor r)))))

(deftest priced-round-ownership-validation-rules
  (is (thrown? Exception (captable/priced-round-ownership {:investment-amount -1 :pre-money-valuation 1})))
  (is (thrown? Exception (captable/priced-round-ownership {:investment-amount 1 :pre-money-valuation -1}))))

;; ----------------------------- option-pool-shuffle -----------------------------

(deftest option-pool-shuffle-sizes-a-pre-money-pool
  (let [r (captable/option-pool-shuffle {:pre-round-shares 9000000 :target-pool-pct 0.10})]
    (is (close? 1000000.0 (:new-pool-shares r)))
    (is (close? 10000000.0 (:pre-money-share-count r)))
    (is (close? 0.10 (/ (:new-pool-shares r) (:pre-money-share-count r)))
        "the new pool really is 10% of the resulting pre-money share count")))

(deftest option-pool-shuffle-validation-rules
  (is (thrown? Exception (captable/option-pool-shuffle {:pre-round-shares -1 :target-pool-pct 0.1})))
  (is (thrown? Exception (captable/option-pool-shuffle {:pre-round-shares 1 :target-pool-pct 1.1}))))

;; ----------------------------- priced-round-shares -----------------------------

(deftest priced-round-shares-composes-with-option-pool-shuffle
  (testing "term-sheet-realistic composition: shuffle a pool in first, then price the round against it"
    (let [pool (captable/option-pool-shuffle {:pre-round-shares 9000000 :target-pool-pct 0.10})
          r (captable/priced-round-shares {:investment-amount 2000000 :pre-money-valuation 10000000
                                           :pre-round-shares (:pre-money-share-count pool)})]
      (is (close? 1.0 (:price-per-share r)))
      (is (close? 2000000.0 (:new-shares r)))
      (is (close? 12000000.0 (:post-round-shares r)))
      (is (close? (/ 2000000.0 12000000.0) (:new-investor-ownership-pct r))))))

(deftest priced-round-shares-validation-rules
  (is (thrown? Exception (captable/priced-round-shares {:investment-amount -1 :pre-money-valuation 1 :pre-round-shares 1})))
  (is (thrown? Exception (captable/priced-round-shares {:investment-amount 1 :pre-money-valuation -1 :pre-round-shares 1})))
  (is (thrown? Exception (captable/priced-round-shares {:investment-amount 1 :pre-money-valuation 1 :pre-round-shares 0}))))

;; ----------------------------- cap-table-ownership -----------------------------

(deftest cap-table-ownership-computes-each-holders-percentage
  (let [r (captable/cap-table-ownership [{:holder "founders" :shares 7000000}
                                         {:holder "pool" :shares 1000000}
                                         {:holder "investor" :shares 2000000}])]
    (is (close? 10000000.0 (:total-shares r)))
    (is (close? 0.7 (:ownership-pct (first (filter #(= "founders" (:holder %)) (:by-holder r))))))
    (is (close? 0.2 (:ownership-pct (first (filter #(= "investor" (:holder %)) (:by-holder r))))))))

(deftest cap-table-ownership-validation-rules
  (is (thrown? Exception (captable/cap-table-ownership []))))

;; ----------------------------- multi-safe-conversion-shares -----------------------------

(deftest multi-safe-conversion-shares-each-converts-at-its-own-price
  (testing "two SAFEs with different cap/discount terms, each converting independently"
    ;; SAFE A: cap 8M vs discount 12M*0.8=9.6M -> cap wins -> $1.00/share, 500,000 new shares.
    ;; SAFE B: cap 5M vs discount 9.6M -> cap wins -> $0.625/share, 480,000 new shares.
    (let [r (captable/multi-safe-conversion-shares
             {:safes [{:id "safe-a" :investment-amount 500000 :valuation-cap 8000000 :discount-rate 0.20}
                      {:id "safe-b" :investment-amount 300000 :valuation-cap 5000000 :discount-rate 0.20}]
              :next-round-pre-money-valuation 12000000
              :pre-conversion-shares 8000000})
          by-id (into {} (map (juxt :id identity)) (:per-safe r))]
      (is (close? 1.0 (:price-per-share (get by-id "safe-a"))))
      (is (close? 500000.0 (:new-shares (get by-id "safe-a"))))
      (is (close? 0.625 (:price-per-share (get by-id "safe-b"))))
      (is (close? 480000.0 (:new-shares (get by-id "safe-b"))))
      (is (close? 980000.0 (:total-safe-shares r)))
      (is (close? 8980000.0 (:post-safe-conversion-shares r))))))

(deftest multi-safe-conversion-shares-validation-rules
  (is (thrown? Exception (captable/multi-safe-conversion-shares
                          {:safes [] :next-round-pre-money-valuation 1 :pre-conversion-shares 1})))
  (is (thrown? Exception (captable/multi-safe-conversion-shares
                          {:safes [{:id "a" :investment-amount 1 :valuation-cap 1 :discount-rate nil}]
                           :next-round-pre-money-valuation 1 :pre-conversion-shares 0}))))

;; ----------------------------- post-money-multi-safe-conversion-shares -----------------------------

(deftest post-money-multi-safe-conversion-shares-solves-the-circular-system
  (testing "two post-money SAFEs, ownership-pcts 0.1 and 0.2 -- cross-checked by hand"
    ;; k = 0.1 + 0.2 = 0.3; post-total = 7,000,000 / (1 - 0.3) = 10,000,000
    ;; safe-a: 0.1 * 10,000,000 = 1,000,000 new shares
    ;; safe-b: 0.2 * 10,000,000 = 2,000,000 new shares
    (let [r (captable/post-money-multi-safe-conversion-shares
             {:safes [{:id "safe-a" :investment-amount 800000 :valuation-cap 8000000}
                      {:id "safe-b" :investment-amount 800000 :valuation-cap 4000000}]
              :pre-conversion-shares 7000000})
          by-id (into {} (map (juxt :id identity)) (:per-safe r))]
      (is (close? 0.1 (:ownership-pct (get by-id "safe-a"))))
      (is (close? 0.2 (:ownership-pct (get by-id "safe-b"))))
      (is (close? 1000000.0 (:new-shares (get by-id "safe-a"))))
      (is (close? 2000000.0 (:new-shares (get by-id "safe-b"))))
      (is (close? 3000000.0 (:total-safe-shares r)))
      (is (close? 10000000.0 (:post-safe-conversion-shares r)))
      (is (close? (:post-safe-conversion-shares r)
                  (+ 7000000.0 (:total-safe-shares r)))
          "post-conversion total = pre-conversion baseline + all new SAFE shares"))))

(deftest post-money-multi-safe-conversion-shares-single-safe-matches-direct-math
  (testing "a single post-money SAFE degenerates to the non-circular case: shares = investment/cap * pre/(1-investment/cap)"
    (let [r (captable/post-money-multi-safe-conversion-shares
             {:safes [{:id "safe-a" :investment-amount 1000000 :valuation-cap 10000000}]
              :pre-conversion-shares 9000000})]
      (is (close? 0.1 (:ownership-pct (first (:per-safe r)))))
      (is (close? 1000000.0 (:total-safe-shares r)) "9,000,000/(1-0.1) - 9,000,000 = 10,000,000 - 9,000,000")
      (is (close? 10000000.0 (:post-safe-conversion-shares r))))))

(deftest post-money-multi-safe-conversion-shares-validation-rules
  (is (thrown? Exception (captable/post-money-multi-safe-conversion-shares
                          {:safes [] :pre-conversion-shares 1000000})))
  (is (thrown? Exception (captable/post-money-multi-safe-conversion-shares
                          {:safes [{:id "a" :investment-amount 100 :valuation-cap 1000}]
                           :pre-conversion-shares 0})))
  (is (thrown? Exception (captable/post-money-multi-safe-conversion-shares
                          {:safes [{:id "a" :investment-amount -1 :valuation-cap 1000}]
                           :pre-conversion-shares 1000000}))
      "negative investment-amount")
  (is (thrown? Exception (captable/post-money-multi-safe-conversion-shares
                          {:safes [{:id "a" :investment-amount 100 :valuation-cap nil}]
                           :pre-conversion-shares 1000000}))
      "cap-only fn -- valuation-cap required"))

(deftest post-money-multi-safe-conversion-shares-rejects-an-oversubscribed-round
  (testing "combined ownership caps reaching 100% of the company is not a solvable cap table"
    (is (thrown? Exception (captable/post-money-multi-safe-conversion-shares
                            {:safes [{:id "a" :investment-amount 6000000 :valuation-cap 10000000}
                                     {:id "b" :investment-amount 5000000 :valuation-cap 10000000}]
                             :pre-conversion-shares 1000000})))))

;; ----------------------------- vesting-schedule -----------------------------

(deftest vesting-schedule-before-cliff-is-zero
  (let [r (captable/vesting-schedule {:total-shares 48000 :vesting-months 48 :cliff-months 12 :months-elapsed 6})]
    (is (false? (:cliff-reached? r)))
    (is (close? 0.0 (:vested-shares r)))))

(deftest vesting-schedule-lump-sums-at-the-cliff
  (let [r (captable/vesting-schedule {:total-shares 48000 :vesting-months 48 :cliff-months 12 :months-elapsed 12})]
    (is (true? (:cliff-reached? r)))
    (is (close? 12000.0 (:vested-shares r)) "12/48 of the grant vests at the 1-year cliff")
    (is (close? 0.25 (:vested-pct r)))))

(deftest vesting-schedule-linear-after-the-cliff
  (let [r (captable/vesting-schedule {:total-shares 48000 :vesting-months 48 :cliff-months 12 :months-elapsed 36})]
    (is (close? 36000.0 (:vested-shares r)))
    (is (close? 0.75 (:vested-pct r)))))

(deftest vesting-schedule-caps-at-fully-vested
  (let [r (captable/vesting-schedule {:total-shares 48000 :vesting-months 48 :cliff-months 12 :months-elapsed 100})]
    (is (close? 48000.0 (:vested-shares r)))
    (is (close? 1.0 (:vested-pct r)))))

(deftest vesting-schedule-validation-rules
  (is (thrown? Exception (captable/vesting-schedule {:total-shares -1 :vesting-months 48 :cliff-months 12 :months-elapsed 1})))
  (is (thrown? Exception (captable/vesting-schedule {:total-shares 1 :vesting-months 0 :cliff-months 0 :months-elapsed 1})))
  (is (thrown? Exception (captable/vesting-schedule {:total-shares 1 :vesting-months 48 :cliff-months 60 :months-elapsed 1})))
  (is (thrown? Exception (captable/vesting-schedule {:total-shares 1 :vesting-months 48 :cliff-months 12 :months-elapsed -1}))))

;; ----------------------------- accelerated-vesting -----------------------------

(deftest accelerated-vesting-single-trigger-fires-on-change-of-control-alone
  (testing "before the cliff, still 100% vests immediately under a single-trigger change-of-control"
    (let [r (captable/accelerated-vesting {:total-shares 48000 :vesting-months 48 :cliff-months 12
                                           :months-elapsed 6 :trigger :single :change-of-control? true})]
      (is (true? (:accelerated? r)))
      (is (true? (:cliff-reached? r)))
      (is (close? 48000.0 (:vested-shares r)))
      (is (close? 1.0 (:vested-pct r))))))

(deftest accelerated-vesting-single-trigger-does-not-fire-without-a-change-of-control
  (let [r (captable/accelerated-vesting {:total-shares 48000 :vesting-months 48 :cliff-months 12
                                         :months-elapsed 36 :trigger :single :change-of-control? false})]
    (is (false? (:accelerated? r)))
    (is (close? 36000.0 (:vested-shares r)) "falls back to the ordinary vesting-schedule result")))

(deftest accelerated-vesting-double-trigger-requires-both-change-of-control-and-termination
  (let [both (captable/accelerated-vesting {:total-shares 48000 :vesting-months 48 :cliff-months 12
                                            :months-elapsed 6 :trigger :double
                                            :change-of-control? true :terminated? true})
        coc-only (captable/accelerated-vesting {:total-shares 48000 :vesting-months 48 :cliff-months 12
                                                :months-elapsed 6 :trigger :double
                                                :change-of-control? true :terminated? false})
        term-only (captable/accelerated-vesting {:total-shares 48000 :vesting-months 48 :cliff-months 12
                                                 :months-elapsed 6 :trigger :double
                                                 :change-of-control? false :terminated? true})]
    (is (true? (:accelerated? both)))
    (is (close? 48000.0 (:vested-shares both)))
    (is (false? (:accelerated? coc-only)) "change-of-control alone does not fire a double-trigger provision")
    (is (false? (:accelerated? term-only)) "termination alone (no change-of-control) does not fire it either")))

(deftest accelerated-vesting-validation-rules
  (is (thrown? Exception (captable/accelerated-vesting {:total-shares 1 :vesting-months 48 :cliff-months 12
                                                        :months-elapsed 1 :trigger :triple})))
  (is (thrown? Exception (captable/accelerated-vesting {:total-shares -1 :vesting-months 48 :cliff-months 12
                                                        :months-elapsed 1 :trigger :single :change-of-control? true}))
      "still reuses vesting-schedule's own validation"))

;; ----------------------------- option-exercise-economics -----------------------------

(deftest option-exercise-economics-computes-intrinsic-value
  (let [r (captable/option-exercise-economics {:shares 10000 :strike-price 0.50 :fmv-per-share 3.00})]
    (is (close? 5000.0 (:exercise-cost r)))
    (is (close? 30000.0 (:market-value r)))
    (is (close? 25000.0 (:intrinsic-value r)))))

(deftest option-exercise-economics-underwater-options-floor-at-zero
  (let [r (captable/option-exercise-economics {:shares 10000 :strike-price 3.00 :fmv-per-share 0.50})]
    (is (close? 0.0 (:intrinsic-value r)) "underwater -- nobody exercises for a loss")))

(deftest option-exercise-economics-validation-rules
  (is (thrown? Exception (captable/option-exercise-economics {:shares -1 :strike-price 1 :fmv-per-share 1})))
  (is (thrown? Exception (captable/option-exercise-economics {:shares 1 :strike-price -1 :fmv-per-share 1})))
  (is (thrown? Exception (captable/option-exercise-economics {:shares 1 :strike-price 1 :fmv-per-share -1}))))

;; ----------------------------- option-exercise-tax-treatment -----------------------------

(deftest option-exercise-tax-treatment-nso-is-ordinary-income-at-exercise
  (let [r (captable/option-exercise-tax-treatment
           {:shares 10000 :strike-price 0.50 :fmv-per-share 3.00
            :option-type :nso :ordinary-income-tax-rate 0.35})]
    (is (= :nso (:option-type r)))
    (is (close? 25000.0 (:intrinsic-value r)) "same spread option-exercise-economics computes")
    (is (close? 8750.0 (:ordinary-income-tax r)) "25,000 spread * 35% ordinary rate")
    (is (nil? (:amt-preference-item r)) "NSO has no AMT preference item")))

(deftest option-exercise-tax-treatment-iso-has-no-regular-tax-but-an-amt-preference-item
  (let [r (captable/option-exercise-tax-treatment
           {:shares 10000 :strike-price 0.50 :fmv-per-share 3.00 :option-type :iso})]
    (is (= :iso (:option-type r)))
    (is (close? 25000.0 (:amt-preference-item r)) "the spread, an AMT preference item, NOT computed AMT liability")
    (is (nil? (:ordinary-income-tax r)) "ISO owes no regular tax at exercise by statute")))

(deftest option-exercise-tax-treatment-underwater-options-owe-no-tax-either-way
  (let [nso (captable/option-exercise-tax-treatment
             {:shares 10000 :strike-price 3.00 :fmv-per-share 0.50
              :option-type :nso :ordinary-income-tax-rate 0.35})
        iso (captable/option-exercise-tax-treatment
             {:shares 10000 :strike-price 3.00 :fmv-per-share 0.50 :option-type :iso})]
    (is (close? 0.0 (:ordinary-income-tax nso)))
    (is (close? 0.0 (:amt-preference-item iso)))))

(deftest option-exercise-tax-treatment-validation-rules
  (is (thrown? Exception (captable/option-exercise-tax-treatment
                          {:shares 1 :strike-price 1 :fmv-per-share 1 :option-type :rsu})))
  (is (thrown? Exception (captable/option-exercise-tax-treatment
                          {:shares 1 :strike-price 1 :fmv-per-share 1 :option-type :nso}))
      "ordinary-income-tax-rate required for :nso")
  (is (thrown? Exception (captable/option-exercise-tax-treatment
                          {:shares 1 :strike-price 1 :fmv-per-share 1 :option-type :nso
                           :ordinary-income-tax-rate 1.5}))
      "ordinary-income-tax-rate must be in [0,1]")
  (is (thrown? Exception (captable/option-exercise-tax-treatment
                          {:shares -1 :strike-price 1 :fmv-per-share 1 :option-type :iso}))
      "still reuses option-exercise-economics's own validation"))
