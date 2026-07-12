(ns vcfund.concentration-test
  (:require [clojure.test :refer [deftest is testing]]
            [vcfund.concentration :as concentration]
            [vcfund.store :as store]))

(defn- close? [a b] (< (Math/abs (- (double a) (double b))) 1e-6))

(deftest concentration-report-on-an-empty-portfolio-is-all-zeros
  (let [db (store/seed-db)
        r (concentration/concentration-report db)]
    (is (zero? (:total-invested-at-cost r)))
    (is (= {} (:by-sector r)))
    (is (= {} (:by-investment-stage r)))))

(deftest concentration-report-splits-by-sector-and-stage-across-committed-deals
  (let [db (store/seed-db)]
    (store/commit-record! db {:effect :investment/mark-committed :path ["deal-1"]})
    (store/commit-record! db {:effect :investment/mark-committed :path ["deal-2"]})
    (let [r (concentration/concentration-report db)]
      (is (close? 2500000.0 (:total-invested-at-cost r)) "deal-1 $2,000,000 + deal-2 $500,000")
      (is (close? 0.8 (:fraction (get (:by-sector r) "ai"))) "deal-1 (ai) is 2,000,000/2,500,000")
      (is (close? 0.2 (:fraction (get (:by-sector r) "robotics"))) "deal-2 (robotics) is 500,000/2,500,000")
      (is (close? 0.8 (:fraction (get (:by-investment-stage r) "seed"))))
      (is (close? 0.2 (:fraction (get (:by-investment-stage r) "series-a")))))))

(deftest concentration-report-groups-a-deal-missing-sector-or-stage-under-unclassified
  (let [db (store/seed-db)]
    (store/with-deals db {"deal-9" {:id "deal-9" :portfolio-company "No-Tag Co" :founders []
                                    :jurisdiction "USA" :ask-amount 1000000 :currency "USD"
                                    :security-type :safe :status :committed}})
    (let [r (concentration/concentration-report db)]
      (is (close? 1.0 (:fraction (get (:by-sector r) :unclassified))))
      (is (close? 1.0 (:fraction (get (:by-investment-stage r) :unclassified)))))))

(deftest concentration-report-excludes-un-committed-deals
  (testing "a sourced (not yet committed) deal contributes nothing to the concentration breakdown"
    (let [db (store/seed-db)
          r (concentration/concentration-report db)]
      (is (zero? (:total-invested-at-cost r)))
      (is (nil? (get (:by-sector r) "ai")) "deal-1 is only :sourced, not yet committed"))))

(deftest concentration-report-converts-currencies-when-fx-options-supplied
  (let [db (store/seed-db)]
    (store/with-deals db {"deal-10" {:id "deal-10" :portfolio-company "Yen Co" :founders []
                                     :jurisdiction "JPN" :ask-amount 100000000 :currency "JPY"
                                     :security-type :priced-equity :status :committed
                                     :sector "fintech" :investment-stage "seed"}})
    (let [r (concentration/concentration-report db {:base-currency "USD" :fx-rates {"JPY" 0.01}})]
      (is (close? 1000000.0 (:total-invested-at-cost r)) "100,000,000 JPY @ 0.01 -> 1,000,000 USD")
      (is (close? 1.0 (:fraction (get (:by-sector r) "fintech")))))))
