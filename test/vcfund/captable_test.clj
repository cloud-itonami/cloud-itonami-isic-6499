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
