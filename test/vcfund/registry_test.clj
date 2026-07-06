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

(deftest commitment-accepts-saft-for-crypto-native-funds
  (testing "a fund investing in a token deal via a SAFT (Simple Agreement for Future Tokens)"
    (let [result (r/register-commitment "Acme Protocol Labs" :saft 500000 "USA" 1)]
      (is (= (get-in result ["record" "security_type"]) "saft")))))

;; ----------------------------- term sheets -----------------------------

(deftest term-sheet-is-a-draft-with-no-certificate
  (let [result (r/register-term-sheet "deal-1" :fund {:valuation 8000000} 0)]
    (is (nil? (get result "certificate")) "a term sheet is non-binding, not a legal instrument this actor issues")
    (is (= (get-in result ["record" "immutable"]) true))
    (is (= (get-in result ["record" "kind"]) "term-sheet-draft"))))

(deftest term-sheet-versions-and-proposer
  (let [result (r/register-term-sheet "deal-1" :founder {:valuation 9000000} 2)]
    (is (= (get-in result ["record" "record_id"]) "deal-1#term-sheet-v2"))
    (is (= (get-in result ["record" "version"]) 2))
    (is (= (get-in result ["record" "proposed_by"]) "founder"))
    (is (= (get-in result ["record" "terms"]) {:valuation 9000000}))))

(deftest term-sheet-validation-rules
  (is (thrown? Exception (r/register-term-sheet "" :fund {} 0)))
  (is (thrown? Exception (r/register-term-sheet "deal-1" :investor {} 0)) "proposed-by must be :fund or :founder")
  (is (thrown? Exception (r/register-term-sheet "deal-1" :fund "not-a-map" 0)))
  (is (thrown? Exception (r/register-term-sheet "deal-1" :fund {} -1))))

(deftest term-sheet-history-is-append-only
  (let [v0 (r/register-term-sheet "deal-1" :fund {:valuation 6000000} 0)
        hist (r/append [] v0)
        v1 (r/register-term-sheet "deal-1" :founder {:valuation 8000000} 1)
        hist2 (r/append hist v1)]
    (is (= 2 (count hist2)))
    (is (= 0 (get-in hist2 [0 "version"])))
    (is (= 1 (get-in hist2 [1 "version"])))))

(deftest term-sheet-diff-classifies-added-removed-changed
  (let [d (r/term-sheet-diff {:valuation 6000000 :board-seat false :pro-rata-rights true}
                             {:valuation 8000000 :board-seat false :liquidation-preference 1.0})]
    (is (= {:from 6000000 :to 8000000} (get-in d [:changed :valuation])) "changed value")
    (is (= 1.0 (get-in d [:added :liquidation-preference])) "new key")
    (is (= true (get-in d [:removed :pro-rata-rights])) "dropped key")
    (is (not (contains? (:changed d) :board-seat)) "unchanged key omitted entirely")))

(deftest term-sheet-diff-validation-rules
  (is (thrown? Exception (r/term-sheet-diff "not-a-map" {})))
  (is (thrown? Exception (r/term-sheet-diff {} "not-a-map"))))

(deftest term-sheet-signature-is-a-draft-with-no-certificate
  (let [result (r/register-term-sheet-signature "deal-1" 0 :fund)]
    (is (nil? (get result "certificate")))
    (is (= (get-in result ["record" "record_id"]) "deal-1#term-sheet-v0#signed-by-fund"))
    (is (= (get-in result ["record" "kind"]) "term-sheet-signature"))
    (is (= (get-in result ["record" "version"]) 0))
    (is (= (get-in result ["record" "signed_by"]) "fund"))))

(deftest term-sheet-signature-validation-rules
  (is (thrown? Exception (r/register-term-sheet-signature "" 0 :fund)))
  (is (thrown? Exception (r/register-term-sheet-signature "deal-1" -1 :fund)))
  (is (thrown? Exception (r/register-term-sheet-signature "deal-1" 0 :investor))))

(deftest fully-executed-requires-both-signers-on-the-same-version
  (let [fund-v0 (get (r/register-term-sheet-signature "deal-1" 0 :fund) "record")
        founder-v0 (get (r/register-term-sheet-signature "deal-1" 0 :founder) "record")
        fund-v1 (get (r/register-term-sheet-signature "deal-1" 1 :fund) "record")]
    (is (not (r/fully-executed? [] 0)) "no signatures at all")
    (is (not (r/fully-executed? [fund-v0] 0)) "only one side signed")
    (is (r/fully-executed? [fund-v0 founder-v0] 0) "both sides signed the same version")
    (is (not (r/fully-executed? [fund-v1 founder-v0] 0))
        "signatures on DIFFERENT versions never count as executing either one")))

;; ----------------------------- capital calls -----------------------------

(def lps-fixture
  [{:id "lp-1" :commitment-amount 5000000 :called-amount 0}
   {:id "lp-2" :commitment-amount 1000000 :called-amount 0}])

(deftest capital-call-allocations-split-pro-rata-by-commitment-share
  (let [allocs (r/capital-call-allocations lps-fixture 2000000)
        by-id (into {} (map (juxt :lp-id identity)) allocs)]
    (is (close? (/ 10000000.0 6) (:allocation (get by-id "lp-1"))))
    (is (close? (/ 2000000.0 6) (:allocation (get by-id "lp-2"))))
    (is (not (:overcall? (get by-id "lp-1"))))
    (is (not (:overcall? (get by-id "lp-2"))))))

(deftest capital-call-allocations-flag-overcall
  (testing "a call far exceeding total commitments overcalls every LP"
    (let [allocs (r/capital-call-allocations lps-fixture 20000000)]
      (is (every? :overcall? allocs)))))

(deftest capital-call-allocations-account-for-prior-called-amount
  (testing "an LP already near their commitment can be pushed over by a small additional call"
    (let [lps [{:id "lp-1" :commitment-amount 5000000 :called-amount 4900000}
               {:id "lp-2" :commitment-amount 1000000 :called-amount 0}]
          allocs (r/capital-call-allocations lps 200000)
          by-id (into {} (map (juxt :lp-id identity)) allocs)]
      (is (:overcall? (get by-id "lp-1")) "lp-1: 4.9M + pro-rata share pushes past 5M commitment")
      (is (not (:overcall? (get by-id "lp-2")))))))

(deftest capital-call-allocations-validation-rules
  (is (thrown? Exception (r/capital-call-allocations lps-fixture -1)))
  (is (thrown? Exception (r/capital-call-allocations [] 1000)))
  (is (thrown? Exception (r/capital-call-allocations [{:id "lp-1" :commitment-amount 0}] 1000))))

(deftest capital-call-certificate-is-a-draft-not-a-real-call
  (let [allocs (r/capital-call-allocations lps-fixture 2000000)
        result (r/register-capital-call allocs 2000000 "USA" 0 "2026-07-06")]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest capital-call-assigns-call-number
  (let [allocs (r/capital-call-allocations lps-fixture 2000000)
        result (r/register-capital-call allocs 2000000 "USA" 7 "2026-07-06")]
    (is (= (get result "call_number") "USA-CALL-000007"))
    (is (= (get-in result ["record" "immutable"]) true))
    (is (= (get-in result ["record" "kind"]) "capital-call-draft"))
    (is (= (get-in result ["record" "funding_due_days"]) r/default-notice-period-days))))

(deftest capital-call-validation-rules
  (let [allocs (r/capital-call-allocations lps-fixture 2000000)]
    (is (thrown? Exception (r/register-capital-call [] 2000000 "USA" 0 "2026-07-06")))
    (is (thrown? Exception (r/register-capital-call allocs -1 "USA" 0 "2026-07-06")))
    (is (thrown? Exception (r/register-capital-call allocs 2000000 "" 0 "2026-07-06")))
    (is (thrown? Exception (r/register-capital-call allocs 2000000 "USA" -1 "2026-07-06")))
    (is (thrown? Exception (r/register-capital-call allocs 2000000 "USA" 0 nil)))))

(deftest capital-call-history-is-append-only
  (let [c1 (r/register-capital-call (r/capital-call-allocations lps-fixture 1000000) 1000000 "USA" 0 "2026-07-06")
        hist (r/append [] c1)
        c2 (r/register-capital-call (r/capital-call-allocations lps-fixture 500000) 500000 "USA" 1 "2026-08-06")
        hist2 (r/append hist c2)]
    (is (= 2 (count hist2)))
    (is (= "USA-CALL-000000" (get-in hist2 [0 "record_id"])))
    (is (= "USA-CALL-000001" (get-in hist2 [1 "record_id"])))))

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

;; ----------------------------- board seats / governance rights -----------------------------

(deftest board-seat-event-has-no-certificate
  (let [result (r/register-board-seat-event "deal-1" "party-1" :board-member :granted "2026-07-06" 0)]
    (is (nil? (get result "certificate")) "an internal administrative record, not a legal instrument this actor issues")
    (is (= (get-in result ["record" "record_id"]) "deal-1#board-seat-0000"))
    (is (= (get-in result ["record" "kind"]) "board-seat-event"))
    (is (= (get-in result ["record" "seat_holder"]) "party-1"))
    (is (= (get-in result ["record" "seat_type"]) "board-member"))
    (is (= (get-in result ["record" "event"]) "granted"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest board-seat-event-accepts-observer-seats-and-revocation
  (let [result (r/register-board-seat-event "deal-1" "party-1" :board-observer :revoked "2027-01-01" 1)]
    (is (= (get-in result ["record" "seat_type"]) "board-observer"))
    (is (= (get-in result ["record" "event"]) "revoked"))))

(deftest board-seat-event-validation-rules
  (is (thrown? Exception (r/register-board-seat-event "" "party-1" :board-member :granted "2026-07-06" 0)))
  (is (thrown? Exception (r/register-board-seat-event "deal-1" "" :board-member :granted "2026-07-06" 0)))
  (is (thrown? Exception (r/register-board-seat-event "deal-1" "party-1" :not-a-real-seat-type :granted "2026-07-06" 0)))
  (is (thrown? Exception (r/register-board-seat-event "deal-1" "party-1" :board-member :not-a-real-event "2026-07-06" 0)))
  (is (thrown? Exception (r/register-board-seat-event "deal-1" "party-1" :board-member :granted "" 0)))
  (is (thrown? Exception (r/register-board-seat-event "deal-1" "party-1" :board-member :granted "2026-07-06" -1))))

(deftest current-board-seats-projects-the-latest-event-per-holder
  (let [history [(get (r/register-board-seat-event "deal-1" "party-1" :board-member :granted "2026-01-01" 0) "record")
                (get (r/register-board-seat-event "deal-1" "party-2" :board-observer :granted "2026-02-01" 1) "record")
                (get (r/register-board-seat-event "deal-1" "party-1" :board-member :revoked "2026-06-01" 2) "record")]
        roster (r/current-board-seats history)]
    (is (= 1 (count roster)) "party-1's seat was revoked, so only party-2 remains")
    (is (= "party-2" (get (first roster) "seat_holder")))))

(deftest current-board-seats-empty-history-is-an-empty-roster
  (is (= [] (r/current-board-seats []))))

;; ----------------------------- follow-on commitments -----------------------------

(deftest follow-on-certificate-is-a-draft-not-a-real-commitment
  (let [result (r/register-follow-on-commitment "Acme AI, Inc." "USA-00000007" :safe 500000 "USA" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest follow-on-assigns-follow-on-number-and-references-original
  (let [result (r/register-follow-on-commitment "Acme AI, Inc." "USA-00000007" :priced-equity 1000000 "USA" 3)]
    (is (= (get result "follow_on_number") "USA-FOLLOWON-00000003"))
    (is (= (get-in result ["record" "record_id"]) "USA-FOLLOWON-00000003"))
    (is (= (get-in result ["record" "original_commitment_number"]) "USA-00000007"))
    (is (= (get-in result ["record" "kind"]) "follow-on-commitment-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest follow-on-accepts-saft-for-crypto-native-follow-on-rounds
  (let [result (r/register-follow-on-commitment "Acme Protocol Labs" "USA-00000001" :saft 250000 "USA" 0)]
    (is (= (get-in result ["record" "security_type"]) "saft"))))

(deftest follow-on-validation-rules
  (is (thrown? Exception (r/register-follow-on-commitment "" "USA-00000007" :safe 500000 "USA" 0)))
  (is (thrown? Exception (r/register-follow-on-commitment "Acme" "" :safe 500000 "USA" 0)))
  (is (thrown? Exception (r/register-follow-on-commitment "Acme" "USA-00000007" :not-a-real-security-type 500000 "USA" 0)))
  (is (thrown? Exception (r/register-follow-on-commitment "Acme" "USA-00000007" :safe -1 "USA" 0)))
  (is (thrown? Exception (r/register-follow-on-commitment "Acme" "USA-00000007" :safe 500000 "" 0)))
  (is (thrown? Exception (r/register-follow-on-commitment "Acme" "USA-00000007" :safe 500000 "USA" -1))))

;; ----------------------------- GP clawback repayment -----------------------------

(deftest clawback-repayment-certificate-is-a-draft-not-a-real-repayment
  (let [result (r/register-clawback-repayment 100000 0 "2026-07-06")]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest clawback-repayment-assigns-record-id
  (let [result (r/register-clawback-repayment 50000 4 "2026-07-06")]
    (is (= (get-in result ["record" "record_id"]) "CLAWBACK-000004"))
    (is (close? 50000.0 (get-in result ["record" "amount"])))
    (is (= (get-in result ["record" "effective_date"]) "2026-07-06"))
    (is (= (get-in result ["record" "kind"]) "clawback-repayment-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest clawback-repayment-validation-rules
  (is (thrown? Exception (r/register-clawback-repayment -1 0 "2026-07-06")))
  (is (thrown? Exception (r/register-clawback-repayment 1000 -1 "2026-07-06")))
  (is (thrown? Exception (r/register-clawback-repayment 1000 0 nil)))
  (is (thrown? Exception (r/register-clawback-repayment 1000 0 ""))))

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
