(ns vcfund.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and the
  Datomic-backed (langchain.db) store satisfy the same contract is what
  makes 'swap the SSoT for Datomic / kotoba-server' a configuration
  change, not a rewrite -- see `underwriting.store-contract-test` for the
  same pattern on the sibling actor this repo ports."
  (:require [clojure.test :refer [deftest is testing]]
            [vcfund.registry :as registry]
            [vcfund.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= ["party-1" "party-2"] (:founders (store/deal s "deal-1"))))
      (is (= "USA" (:jurisdiction (store/deal s "deal-1"))))
      (is (= :safe (:security-type (store/deal s "deal-1"))))
      (is (true? (:accredited? (store/lp s "lp-1"))))
      (is (zero? (:called-amount (store/lp s "lp-1"))))
      (is (= "0x71C7656EC7ab88b098defB751B7401B5f6d8976" (:wallet-address (store/lp s "lp-1")))
          "crypto-native settlement rail: an LP may fund capital calls via an on-chain wallet")
      (is (= "Jane Founder" (:name (store/party s "party-1"))))
      (is (false? (:sanctions-hit? (store/party s "party-1"))))
      (is (true? (:sanctions-hit? (store/party s "party-3"))))
      (is (= ["deal-1" "deal-2" "deal-3"] (mapv :id (store/all-deals s))))
      (is (= ["lp-1" "lp-2"] (mapv :id (store/all-lps s))))
      (is (nil? (store/kyc-of s "party-1")))
      (is (nil? (store/assessment-of s "deal-1")))
      (is (nil? (store/commitment-of s "deal-1")))
      (is (= [] (store/ledger s)))
      (is (= [] (store/capital-call-history s)))
      (is (= [] (store/commitment-history s)))
      (is (= [] (store/distribution-history s)))
      (is (= [] (store/portfolio-reports-of s "deal-1")))
      (is (= [] (store/term-sheet-history-of s "deal-1")))
      (is (= [] (store/signature-history-of s "deal-1")))
      (is (= [] (store/follow-on-history-of s "deal-1")))
      (is (= [] (store/clawback-repayment-history s)))
      (is (zero? (store/next-sequence s "USA")))
      (is (zero? (store/call-sequence s "USA")))
      (is (zero? (store/follow-on-sequence s "USA"))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "partial upsert merges, preserving untouched fields"
        (store/commit-record! s {:effect :lp/upsert
                                 :value {:id "lp-1" :status :active}})
        (is (= :active (:status (store/lp s "lp-1"))))
        (is (= "Sequoia Fund of Funds" (:name (store/lp s "lp-1"))) "name preserved"))
      (testing "assessment / kyc payloads commit and read back"
        (store/commit-record! s {:effect :assessment/set :path ["deal-1"]
                                 :payload {:jurisdiction "USA" :checklist ["a" "b"]}})
        (is (= {:jurisdiction "USA" :checklist ["a" "b"]} (store/assessment-of s "deal-1")))
        (store/commit-record! s {:effect :kyc/set :path ["party-1"]
                                 :payload {:party-id "party-1" :verdict :clear}})
        (is (= {:party-id "party-1" :verdict :clear} (store/kyc-of s "party-1"))))
      (testing "advance-stage moves a deal through the pipeline funnel"
        (store/commit-record! s {:effect :deal/advance-stage :path ["deal-1"]
                                 :payload {:to-stage :ic-review}})
        (is (= :ic-review (:status (store/deal s "deal-1")))))
      (testing "term-sheet proposals accumulate as a versioned per-deal history"
        (store/commit-record! s {:effect :term-sheet/proposed :path ["deal-1"]
                                 :payload {:proposed-by :fund :terms {:valuation 8000000}}})
        (is (= 1 (count (store/term-sheet-history-of s "deal-1"))))
        (is (= 0 (get (first (store/term-sheet-history-of s "deal-1")) "version"))))
      (testing "term-sheet signatures accumulate as a per-deal history"
        (store/commit-record! s {:effect :term-sheet/signed :path ["deal-1"]
                                 :payload {:version 0 :signed-by :fund}})
        (store/commit-record! s {:effect :term-sheet/signed :path ["deal-1"]
                                 :payload {:version 0 :signed-by :founder}})
        (is (= 2 (count (store/signature-history-of s "deal-1"))))
        (is (true? (registry/fully-executed? (store/signature-history-of s "deal-1") 0))))
      (testing "capital call drafts a call record, advances LP called-amounts and the call sequence"
        (store/commit-record! s {:effect :capital-call/mark-issued :path ["deal-1"]
                                 :payload {:jurisdiction "USA" :call-amount 2000000 :notice-date "2026-07-06"}})
        (is (= "USA-CALL-000000" (get (first (store/capital-call-history s)) "record_id")))
        (is (= "capital-call-draft" (get (first (store/capital-call-history s)) "kind")))
        (is (= 1 (count (store/capital-call-history s))))
        (is (= 1 (store/call-sequence s "USA")))
        (is (pos? (:called-amount (store/lp s "lp-1"))))
        (is (pos? (:called-amount (store/lp s "lp-2")))))
      (testing "commit drafts an investment-commitment record and advances the sequence"
        (store/commit-record! s {:effect :investment/mark-committed :path ["deal-1"]})
        ;; commitment-history holds the inner "record" sub-map (registry/append's
        ;; convention), whose commitment-number key is "record_id".
        (is (= "USA-00000000" (get (first (store/commitment-history s)) "record_id")))
        (is (= "commitment-draft" (get (first (store/commitment-history s)) "kind")))
        (is (= :committed (:status (store/deal s "deal-1"))))
        (is (= 1 (count (store/commitment-history s))))
        (is (= 1 (store/next-sequence s "USA"))))
      (testing "follow-on commit drafts a follow-on record referencing the original commitment"
        (store/commit-record! s {:effect :investment/follow-on-committed :path ["deal-1"]
                                 :payload {:security-type :priced-equity :amount 500000}})
        (is (= "USA-FOLLOWON-00000000" (get (first (store/follow-on-history-of s "deal-1")) "record_id")))
        (is (= "USA-00000000" (get (first (store/follow-on-history-of s "deal-1")) "original_commitment_number")))
        (is (= "follow-on-commitment-draft" (get (first (store/follow-on-history-of s "deal-1")) "kind")))
        (is (= :committed (:status (store/deal s "deal-1")))
            "a follow-on does not change the deal's lifecycle status")
        (is (= 1 (count (store/follow-on-history-of s "deal-1"))))
        (is (= 1 (store/follow-on-sequence s "USA"))))
      (testing "portfolio report logs a KPI record for the now-committed deal"
        (store/commit-record! s {:effect :portfolio/report-logged :path ["deal-1"]
                                 :payload {:period "2026-Q3" :kpis {:revenue 450000}}})
        (is (= 1 (count (store/portfolio-reports-of s "deal-1"))))
        (is (= "portfolio-report" (get (first (store/portfolio-reports-of s "deal-1")) "kind"))))
      (testing "distribute drafts an exit-distribution record from the committed investment"
        (store/commit-record! s {:effect :distribution/mark-paid :path ["deal-1"]
                                 :payload {:exit-proceeds 12000000 :holding-period-years 3}})
        (is (= "distribution-draft" (get (first (store/distribution-history s)) "kind")))
        (is (= :exited (:status (store/deal s "deal-1"))))
        (is (= 1 (count (store/distribution-history s)))))
      (testing "clawback repayment drafts a fund-level (not deal-scoped) repayment record"
        (store/commit-record! s {:effect :waterfall/clawback-repaid
                                 :payload {:amount 25000 :effective-date "2026-07-06"}})
        (is (= "CLAWBACK-000000" (get (first (store/clawback-repayment-history s)) "record_id")))
        (is (= "clawback-repayment-draft" (get (first (store/clawback-repayment-history s)) "kind")))
        (is (= 1 (count (store/clawback-repayment-history s)))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/deal s "nope")))
    (is (= [] (store/all-deals s)))
    (is (= [] (store/ledger s)))
    (is (= [] (store/capital-call-history s)))
    (is (= [] (store/commitment-history s)))
    (is (zero? (store/next-sequence s "USA")))
    (is (zero? (store/call-sequence s "USA")))
    (store/with-deals s {"x" {:id "x" :portfolio-company "X Inc" :jurisdiction "USA"
                              :founders [] :ask-amount 0 :currency "USD"
                              :security-type :safe :status :sourced}})
    (is (= "X Inc" (:portfolio-company (store/deal s "x"))))))
