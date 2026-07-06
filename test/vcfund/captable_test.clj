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
