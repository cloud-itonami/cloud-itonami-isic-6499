(ns vcfund.nav-test
  (:require [clojure.test :refer [deftest is testing]]
            [vcfund.nav :as nav]
            [vcfund.store :as store]))

(defn- close? [a b] (< (Math/abs (- (double a) (double b))) 1e-6))

(def lps-fixture
  [{:id "lp-1" :commitment-amount 5000000 :called-amount 1666666.6666666667}
   {:id "lp-2" :commitment-amount 1000000 :called-amount 333333.3333333333}])

(deftest unfunded-commitments-per-lp-and-fund-wide
  (let [r (nav/unfunded-commitments lps-fixture)]
    (is (close? 6000000.0 (:total-commitments r)))
    (is (close? 2000000.0 (:total-called r)))
    (is (close? 4000000.0 (:total-unfunded r)))
    (is (close? 3333333.333333333 (:unfunded (first (filter #(= "lp-1" (:lp-id %)) (:by-lp r))))))))

(deftest unfunded-commitments-validation-rules
  (is (thrown? Exception (nav/unfunded-commitments []))))

(deftest fund-nav-values-a-held-investment-at-cost-when-unmarked
  (let [r (nav/fund-nav {:total-called 2000000 :total-invested-at-cost 2000000
                         :total-exit-proceeds-received 0 :total-distributed-to-lps 0
                         :investments [{:deal-id "deal-1" :cost-basis 2000000 :fair-value nil :exited? false}]})]
    (is (close? 0.0 (:net-cash r)))
    (is (close? 2000000.0 (:held-fair-value r)))
    (is (close? 2000000.0 (:nav r)))))

(deftest fund-nav-uses-the-fair-value-mark-when-present
  (let [r (nav/fund-nav {:total-called 2000000 :total-invested-at-cost 2000000
                         :total-exit-proceeds-received 0 :total-distributed-to-lps 0
                         :investments [{:deal-id "deal-1" :cost-basis 2000000 :fair-value 3000000 :exited? false}]})]
    (is (close? 3000000.0 (:held-fair-value r)))
    (is (close? 3000000.0 (:nav r)))))

(deftest fund-nav-excludes-exited-investments-from-held-fair-value
  (testing "an exited deal's value is realized through the exit-proceeds/distributed cash flows, not held-fair-value"
    (let [r (nav/fund-nav {:total-called 2000000 :total-invested-at-cost 2000000
                           :total-exit-proceeds-received 12000000 :total-distributed-to-lps 10096000
                           :investments [{:deal-id "deal-1" :cost-basis 2000000 :fair-value 3000000 :exited? true}]})]
      (is (close? 0.0 (:held-fair-value r)))
      ;; net-cash = (2M called + 12M exit proceeds) - (2M invested + 10.096M distributed) = 1.904M
      ;; -- exactly the GP carry, still resident as fund cash pending a GP-carry sweep.
      (is (close? 1904000.0 (:net-cash r)))
      (is (close? 1904000.0 (:nav r))))))

(deftest fund-nav-validation-rules
  (is (thrown? Exception (nav/fund-nav {:total-called -1 :total-invested-at-cost 0
                                        :total-exit-proceeds-received 0 :total-distributed-to-lps 0
                                        :investments []}))))

;; ----------------------------- store integration -----------------------------

(deftest fund-nav-report-tracks-a-full-lifecycle
  (let [db (store/seed-db)]
    (store/commit-record! db {:effect :capital-call/mark-issued :path ["deal-1"]
                              :payload {:jurisdiction "USA" :call-amount 2000000 :notice-date "2026-07-06"}})
    (store/commit-record! db {:effect :investment/mark-committed :path ["deal-1"]})
    (testing "after commit, at cost basis (unmarked)"
      (let [r (nav/fund-nav-report db)]
        (is (close? 2000000.0 (:nav r)))
        (is (close? 4000000.0 (:total-unfunded (:unfunded r))))))
    (store/commit-record! db {:effect :portfolio/report-logged :path ["deal-1"]
                              :payload {:period "2026-Q3" :kpis {:fair-value-mark 3000000}}})
    (testing "after a fair-value mark, NAV reflects the mark, not cost"
      (is (close? 3000000.0 (:nav (nav/fund-nav-report db)))))
    (store/commit-record! db {:effect :distribution/mark-paid :path ["deal-1"]
                              :payload {:exit-proceeds 12000000 :holding-period-years 3}})
    (testing "after exit, NAV = cash still resident (GP carry pending sweep), no held investments"
      (let [r (nav/fund-nav-report db)]
        (is (close? 0.0 (:held-fair-value r)))
        (is (close? 1904000.0 (:nav r)))))))
