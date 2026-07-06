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
