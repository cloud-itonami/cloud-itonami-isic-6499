(ns vcfund.registry-test
  (:require [clojure.test :refer [deftest is testing]]
            [vcfund.registry :as r]))

(defn- close?
  "Float-safe equality for computed monetary values -- never assert exact
  `=` on a value derived from multiplying by a rate like 0.08, which is
  not exactly representable as a double."
  [a b]
  (< (Math/abs (- (double a) (double b))) 1e-6))

(deftest certificate-is-a-draft-not-a-real-commitment
  (let [result (r/register-commitment "Acme AI, Inc." :safe 0 "USA" 1)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest commitment-assigns-commitment-number
  (let [result (r/register-commitment "Acme AI, Inc." :safe 2000000 "USA" 7)]
    (is (= (get result "commitment_number") "USA-00000007"))
    (is (= (get-in result ["record" "immutable"]) true))
    (is (= (get-in result ["record" "kind"]) "commitment-draft"))
    (is (= (get-in result ["record" "security_type"]) "safe"))))

(deftest commitment-validation-rules
  (let [bad-args [["" :safe 0 "USA"]
                  ["Acme" :not-a-real-security-type 0 "USA"]
                  ["Acme" :safe -1 "USA"]
                  ["Acme" :safe 0 ""]]]
    (doseq [[company security-type amount jurisdiction] bad-args]
      (is (thrown? Exception
                   (r/register-commitment company security-type amount jurisdiction 1))))))

;; ----------------------------- waterfall -----------------------------

(deftest waterfall-returns-only-proceeds-below-contributed-capital
  (testing "a loss: LP just gets back whatever proceeds exist, no preferred, no carry"
    (let [w (r/distribute-waterfall {:contributed-capital 1000000 :exit-proceeds 500000
                                     :preferred-return-rate 0.08 :holding-period-years 3
                                     :carry-rate 0.20})]
      (is (close? 500000.0 (:return-of-capital w)))
      (is (close? 0.0 (:preferred-return-paid w)))
      (is (close? 0.0 (:gp-carry w)))
      (is (close? 500000.0 (:total-to-lp w)))
      (is (close? 0.0 (:total-to-gp w))))))

(deftest waterfall-splits-carry-only-on-residual-profit
  (testing "capital + full preferred + a residual profit split 80/20 LP/GP"
    (let [w (r/distribute-waterfall {:contributed-capital 1000000 :exit-proceeds 1100000
                                     :preferred-return-rate 0.08 :holding-period-years 1
                                     :carry-rate 0.20})]
      (is (close? 1000000.0 (:return-of-capital w)))
      (is (close? 80000.0 (:preferred-return-paid w)))
      (is (close? 4000.0 (:gp-carry w)))
      (is (close? 16000.0 (:lp-residual-profit w)))
      (is (close? 1096000.0 (:total-to-lp w)))
      (is (close? 4000.0 (:total-to-gp w))))))

(deftest waterfall-total-to-lp-plus-gp-always-equals-exit-proceeds
  (doseq [proceeds [0 500000 1100000 12000000]]
    (let [w (r/distribute-waterfall {:contributed-capital 2000000 :exit-proceeds proceeds
                                     :preferred-return-rate 0.08 :holding-period-years 3
                                     :carry-rate 0.20})]
      (is (close? (double proceeds) (+ (:total-to-lp w) (:total-to-gp w)))))))

(deftest waterfall-is-explicitly-deal-by-deal-not-whole-fund
  (is (= :deal-by-deal-simple-preferred
         (:model (r/distribute-waterfall {:contributed-capital 100 :exit-proceeds 100
                                          :preferred-return-rate 0.08 :holding-period-years 1
                                          :carry-rate 0.20})))))

(deftest waterfall-validation-rules
  (is (thrown? Exception (r/distribute-waterfall {:contributed-capital -1 :exit-proceeds 0
                                                  :preferred-return-rate 0.08 :holding-period-years 1
                                                  :carry-rate 0.20})))
  (is (thrown? Exception (r/distribute-waterfall {:contributed-capital 0 :exit-proceeds -1
                                                  :preferred-return-rate 0.08 :holding-period-years 1
                                                  :carry-rate 0.20})))
  (is (thrown? Exception (r/distribute-waterfall {:contributed-capital 0 :exit-proceeds 0
                                                  :preferred-return-rate 0.08 :holding-period-years 1
                                                  :carry-rate 1.5}))))

(deftest distribution-is-append-only
  (let [c (r/register-commitment "Acme" :safe 1000000 "USA" 1)
        hist (r/append [] c)
        w (r/distribute-waterfall {:contributed-capital 1000000 :exit-proceeds 1100000
                                   :preferred-return-rate 0.08 :holding-period-years 1
                                   :carry-rate 0.20})
        d (r/register-distribution (get c "commitment_number") w "2026-07-06")
        hist2 (r/append hist d)]
    (is (and (= (count hist) 1) (= (count hist2) 2)))
    (is (= (get-in hist2 [0 "kind"]) "commitment-draft"))
    (is (= (get-in hist2 [1 "kind"]) "distribution-draft"))))
