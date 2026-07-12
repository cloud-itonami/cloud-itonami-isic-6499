(ns vcfund.waterfall-test
  (:require [clojure.test :refer [deftest is testing]]
            [vcfund.waterfall :as waterfall]
            [vcfund.store :as store]))

(defn- close? [a b] (< (Math/abs (- (double a) (double b))) 1e-6))

(deftest whole-fund-waterfall-computes-a-clawback-when-deal-by-deal-overpaid-carry
  (testing "an early home-run's deal-by-deal carry exceeds what the fund's aggregate performance ever entitled the GP to"
    ;; Deal A: contributed 1M, exits for 5M after 2y -> deal-by-deal GP carry paid = 768,000.
    ;; Deal B: contributed 2M, exits for only 500K after 3y (a loss) -> deal-by-deal GP carry paid = 0.
    (let [r (waterfall/whole-fund-waterfall
             {:total-contributed-capital 3000000
              :total-exit-proceeds 5500000
              :fund-life-years 3
              :preferred-return-rate 0.08
              :carry-rate 0.20
              :total-gp-carry-already-paid 768000})]
      (is (= :whole-fund-european-simple-preferred (:model r)))
      (is (close? 3000000.0 (:return-of-capital r)))
      (is (close? 720000.0 (:preferred-return-paid r)))
      (is (close? 356000.0 (:whole-fund-gp-entitlement r)))
      (is (close? 768000.0 (:total-gp-carry-already-paid r)))
      (is (close? 412000.0 (:gp-clawback r))
          "GP was paid 768,000 deal-by-deal but the whole fund only entitled them to 356,000"))))

(deftest whole-fund-waterfall-is-zero-clawback-when-consistent
  (testing "a single profitable deal, deal-by-deal carry matches whole-fund entitlement exactly -- no clawback"
    (let [r (waterfall/whole-fund-waterfall
             {:total-contributed-capital 1000000
              :total-exit-proceeds 5000000
              :fund-life-years 2
              :preferred-return-rate 0.08
              :carry-rate 0.20
              :total-gp-carry-already-paid 768000})]
      (is (close? 768000.0 (:whole-fund-gp-entitlement r)))
      (is (close? 0.0 (:gp-clawback r))))))

(deftest whole-fund-waterfall-clawback-never-negative
  (testing "if deal-by-deal under-paid relative to whole-fund entitlement, clawback floors at 0 (not modeled as a catch-up payment)"
    (let [r (waterfall/whole-fund-waterfall
             {:total-contributed-capital 1000000
              :total-exit-proceeds 5000000
              :fund-life-years 2
              :preferred-return-rate 0.08
              :carry-rate 0.20
              :total-gp-carry-already-paid 100000})]
      (is (close? 0.0 (:gp-clawback r))))))

(deftest whole-fund-waterfall-validation-rules
  (is (thrown? #?(:clj Exception :cljs js/Error) (waterfall/whole-fund-waterfall
                          {:total-contributed-capital -1 :total-exit-proceeds 0 :fund-life-years 1
                           :preferred-return-rate 0.08 :carry-rate 0.2 :total-gp-carry-already-paid 0})))
  (is (thrown? #?(:clj Exception :cljs js/Error) (waterfall/whole-fund-waterfall
                          {:total-contributed-capital 0 :total-exit-proceeds 0 :fund-life-years 1
                           :preferred-return-rate -0.01 :carry-rate 0.2 :total-gp-carry-already-paid 0})))
  (is (thrown? #?(:clj Exception :cljs js/Error) (waterfall/whole-fund-waterfall
                          {:total-contributed-capital 0 :total-exit-proceeds 0 :fund-life-years 1
                           :preferred-return-rate 0.08 :carry-rate 1.5 :total-gp-carry-already-paid 0}))))

;; ----------------------------- store integration -----------------------------

(deftest whole-fund-waterfall-report-reconciles-two-real-deals
  (let [db (store/seed-db)]
    ;; deal-1: ask-amount 2,000,000, exits big.
    (store/commit-record! db {:effect :investment/mark-committed :path ["deal-1"]})
    (store/commit-record! db {:effect :distribution/mark-paid :path ["deal-1"]
                              :payload {:exit-proceeds 10000000 :holding-period-years 2}})
    ;; deal-3: ask-amount 300,000, exits at a loss.
    (store/commit-record! db {:effect :investment/mark-committed :path ["deal-3"]})
    (store/commit-record! db {:effect :distribution/mark-paid :path ["deal-3"]
                              :payload {:exit-proceeds 100000 :holding-period-years 1}})
    (let [r (waterfall/whole-fund-waterfall-report db 2)]
      (is (close? 2300000.0 (:return-of-capital r)))
      (is (close? 1536000.0 (:total-gp-carry-already-paid r))
          "deal-1 alone paid 1,536,000 GP carry deal-by-deal")
      (is (close? 1486400.0 (:whole-fund-gp-entitlement r)))
      (is (close? 49600.0 (:gp-clawback r))
          "deal-3's loss means the whole fund entitles GP to less than deal-1 alone already paid"))))
