(ns vcfund.governor-contract-test
  "The governor contract as executable tests -- the venture-fund analog of
  `cloud-itonami-isic-6511`'s `underwriting.governor-contract-test`. The
  single invariant under test:

    DD-LLM never commits capital or distributes proceeds the
    InvestmentCommitteeGovernor would reject, `:investment/commit` and
    `:exit/distribute` NEVER auto-commit at any phase, and every decision
    (commit OR hold) leaves exactly one ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [vcfund.store :as store]
            [vcfund.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def operator {:actor-id "op-1" :actor-role :investment-committee :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(deftest clean-lp-intake-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :lp/intake :subject "lp-1"
                   :patch {:id "lp-1" :status :active}} operator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= :active (:status (store/lp db "lp-1"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))))

(deftest dd-assess-always-needs-approval
  (testing "DD assessment is never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t2" {:op :dd/assess :subject "deal-1"} operator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t2")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (some? (store/assessment-of db "deal-1")))))))

(deftest fabricated-jurisdiction-is-held
  (testing "a dd/assess proposal for a deal with no official spec-basis (ATL) -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t3" {:op :dd/assess :subject "deal-2"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:no-spec-basis} (-> (store/ledger db) first :basis)))
      (is (nil? (store/assessment-of db "deal-2")) "no assessment written"))))

(deftest sanctions-hit-is-held-and-unoverridable
  (testing "a sanctions/PEP hit on a founder -> HOLD, and never reaches request-approval"
    (let [[db actor] (fresh)
          res (exec-op actor "t4" {:op :kyc/screen :subject "party-3"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:sanctions-hit} (-> (store/ledger db) first :basis)))
      (is (nil? (store/kyc-of db "party-3")) "no KYC clearance written"))))

(deftest commit-without-dd-assessment-is-held
  (testing "investment/commit before any DD assessment -> HOLD (dd-incomplete)"
    (let [[db actor] (fresh)
          res (exec-op actor "t5" {:op :investment/commit :subject "deal-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:dd-incomplete} (-> (store/ledger db) first :basis)))
      (is (nil? (store/commitment-of db "deal-1"))))))

(deftest commit-blocked-by-an-unaccredited-lp-fund-wide
  (testing "even a fully DD-cleared deal cannot commit while ANY LP in the fund lacks accreditation"
    (let [[db actor] (fresh)]
      (store/with-lps db {"lp-3" {:id "lp-3" :name "Unaffirmed LP" :commitment-amount 250000
                                  :currency "USD" :jurisdiction "USA" :accredited? false :status :pending}})
      (exec-op actor "t6a" {:op :dd/assess :subject "deal-1"} operator)
      (approve! actor "t6a")
      (exec-op actor "t6b" {:op :kyc/screen :subject "party-1"} operator)
      (approve! actor "t6b")
      (let [res (exec-op actor "t6" {:op :investment/commit :subject "deal-1"} operator)]
        (is (= :hold (get-in res [:state :disposition])))
        (is (some #{:accredited-investor-violation} (-> (store/ledger db) last :basis)))
        (is (nil? (store/commitment-of db "deal-1")))))))

(deftest exit-distribute-without-a-commitment-is-held
  (testing "exit/distribute for a deal with no prior investment commitment -> HOLD (commitment-missing)"
    (let [[db actor] (fresh)
          res (exec-op actor "t7" {:op :exit/distribute :subject "deal-1"
                                   :exit-proceeds 1000000 :holding-period-years 1} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:commitment-missing} (-> (store/ledger db) first :basis)))
      (is (empty? (store/distribution-history db))))))

(deftest investment-commit-always-escalates-then-human-decides
  (testing "a clean, fully-assessed commitment still ALWAYS interrupts for human approval -- actuation/deploy is never auto"
    (let [[db actor] (fresh)
          _ (exec-op actor "t8a" {:op :dd/assess :subject "deal-1"} operator)
          _ (approve! actor "t8a")
          _ (exec-op actor "t8b" {:op :kyc/screen :subject "party-1"} operator)
          _ (approve! actor "t8b")
          r1 (exec-op actor "t8" {:op :investment/commit :subject "deal-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, investment-commitment record drafted"
        (let [r2 (approve! actor "t8")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (= :committed (:status (store/deal db "deal-1"))))
          (is (= 1 (count (store/commitment-history db)))
              "one draft commitment record")))))
  (testing "reject -> hold, nothing committed"
    (let [[db actor] (fresh)
          _ (exec-op actor "t9a" {:op :dd/assess :subject "deal-1"} operator)
          _ (approve! actor "t9a")
          _ (exec-op actor "t9" {:op :investment/commit :subject "deal-1"} operator)
          r2 (g/run* actor {:approval {:status :rejected :by "op-1"}}
                     {:thread-id "t9" :resume? true})]
      (is (= :hold (get-in r2 [:state :disposition])))
      (is (empty? (store/commitment-history db)) "nothing committed on reject"))))

(deftest exit-distribute-always-escalates-then-human-decides
  (testing "a clean exit distribution still ALWAYS interrupts for human approval -- actuation/distribute is never auto"
    (let [[db actor] (fresh)
          _ (exec-op actor "t10a" {:op :dd/assess :subject "deal-1"} operator)
          _ (approve! actor "t10a")
          _ (exec-op actor "t10b" {:op :investment/commit :subject "deal-1"} operator)
          _ (approve! actor "t10b")
          r1 (exec-op actor "t10" {:op :exit/distribute :subject "deal-1"
                                   :exit-proceeds 12000000 :holding-period-years 3} operator)]
      (is (= :interrupted (:status r1)))
      (let [r2 (approve! actor "t10")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= :exited (:status (store/deal db "deal-1"))))
        (is (= 1 (count (store/distribution-history db))))))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :lp/intake :subject "lp-1"
                       :patch {:id "lp-1" :status :active}} operator)
      (exec-op actor "b" {:op :dd/assess :subject "deal-2"} operator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))
