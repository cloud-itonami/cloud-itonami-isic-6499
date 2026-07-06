(ns vcfund.governor-contract-test
  "The governor contract as executable tests -- the venture-fund analog of
  `cloud-itonami-isic-6511`'s `underwriting.governor-contract-test`. The
  single invariant under test:

    DD-LLM never calls, commits or distributes capital the
    InvestmentCommitteeGovernor would reject, `:capital-call/issue`,
    `:investment/commit` and `:exit/distribute` NEVER auto-commit at any
    phase, `:deal/advance-stage`/`:portfolio/report` (no capital risk) MAY
    auto-commit when clean, and every decision (commit OR hold) leaves
    exactly one ledger fact."
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

(deftest deal-advance-stage-auto-commits-clean-transition
  (testing "no capital risk -- a legal forward pipeline move auto-commits, no approval needed"
    (let [[db actor] (fresh)
          res (exec-op actor "t20" {:op :deal/advance-stage :subject "deal-1" :to-stage :screening} operator)]
      (is (= :commit (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (= :screening (:status (store/deal db "deal-1")))))))

(deftest deal-advance-stage-illegal-transition-is-held
  (testing "skipping straight to :committed via advance-stage (not the real commit op) -> HARD hold"
    (let [[db actor] (fresh)
          res (exec-op actor "t21" {:op :deal/advance-stage :subject "deal-1" :to-stage :committed} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:invalid-stage-transition} (-> (store/ledger db) first :basis)))
      (is (= :sourced (:status (store/deal db "deal-1"))) "stage unchanged on hold"))))

(deftest commit-without-ic-review-stage-is-held
  (testing "DD-cleared and KYC-cleared, but the deal never reached :ic-review -> HARD hold (stage-insufficient)"
    (let [[db actor] (fresh)]
      (exec-op actor "t22a" {:op :dd/assess :subject "deal-1"} operator)
      (approve! actor "t22a")
      (exec-op actor "t22b" {:op :kyc/screen :subject "party-1"} operator)
      (approve! actor "t22b")
      ;; deliberately no :deal/advance-stage -- deal-1 is still :sourced
      (let [res (exec-op actor "t22" {:op :investment/commit :subject "deal-1"} operator)]
        (is (= :hold (get-in res [:state :disposition])))
        (is (some #{:stage-insufficient} (-> (store/ledger db) last :basis)))
        (is (nil? (store/commitment-of db "deal-1")))))))

(deftest term-sheet-propose-auto-commits
  (testing "no capital risk -- proposing a term-sheet round auto-commits, no approval needed"
    (let [[db actor] (fresh)
          res (exec-op actor "t25" {:op :term-sheet/propose :subject "deal-1" :proposed-by :fund
                                    :terms {:valuation 8000000 :security-type :safe}} operator)]
      (is (= :commit (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (= 1 (count (store/term-sheet-history-of db "deal-1")))))))

(deftest term-sheet-versions-accumulate-across-rounds
  (testing "each propose call is a new version, append-only, not overwriting the prior round"
    (let [[db actor] (fresh)]
      (exec-op actor "t26a" {:op :term-sheet/propose :subject "deal-1" :proposed-by :fund
                             :terms {:valuation 6000000}} operator)
      (exec-op actor "t26b" {:op :term-sheet/propose :subject "deal-1" :proposed-by :founder
                             :terms {:valuation 8000000}} operator)
      (let [hist (store/term-sheet-history-of db "deal-1")]
        (is (= 2 (count hist)))
        (is (= 0 (get (first hist) "version")))
        (is (= 1 (get (second hist) "version")))
        (is (= "fund" (get (first hist) "proposed_by")))
        (is (= "founder" (get (second hist) "proposed_by")))))))

(deftest term-sheet-after-commitment-is-held
  (testing "proposing new terms on an already-committed deal -> HARD hold"
    (let [[db actor] (fresh)
          _ (exec-op actor "t27a" {:op :dd/assess :subject "deal-1"} operator)
          _ (approve! actor "t27a")
          _ (exec-op actor "t27b" {:op :deal/advance-stage :subject "deal-1" :to-stage :ic-review} operator)
          _ (exec-op actor "t27c" {:op :term-sheet/propose :subject "deal-1" :proposed-by :fund
                                   :terms {:valuation 8000000}} operator)
          _ (exec-op actor "t27d" {:op :investment/commit :subject "deal-1"} operator)
          _ (approve! actor "t27d")
          res (exec-op actor "t27" {:op :term-sheet/propose :subject "deal-1" :proposed-by :fund
                                    :terms {:valuation 9000000}} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:term-sheet-after-commitment} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/term-sheet-history-of db "deal-1"))) "second proposal not recorded"))))

(deftest commit-without-term-sheet-is-held
  (testing "DD-cleared, KYC-cleared, IC-reviewed, but no term sheet was ever proposed -> HARD hold"
    (let [[db actor] (fresh)]
      (exec-op actor "t28a" {:op :dd/assess :subject "deal-1"} operator)
      (approve! actor "t28a")
      (exec-op actor "t28b" {:op :kyc/screen :subject "party-1"} operator)
      (approve! actor "t28b")
      (exec-op actor "t28c" {:op :deal/advance-stage :subject "deal-1" :to-stage :ic-review} operator)
      ;; deliberately no :term-sheet/propose
      (let [res (exec-op actor "t28" {:op :investment/commit :subject "deal-1"} operator)]
        (is (= :hold (get-in res [:state :disposition])))
        (is (some #{:term-sheet-missing} (-> (store/ledger db) last :basis)))
        (is (nil? (store/commitment-of db "deal-1")))))))

(deftest portfolio-report-without-commitment-is-held
  (testing "a KPI report on a deal that was never committed -> HARD hold"
    (let [[db actor] (fresh)
          res (exec-op actor "t23" {:op :portfolio/report :subject "deal-1" :period "2026-Q1"
                                    :kpis {:revenue 0}} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:portfolio-report-without-commitment} (-> (store/ledger db) first :basis)))
      (is (empty? (store/portfolio-reports-of db "deal-1"))))))

(deftest portfolio-report-auto-commits-once-deal-is-committed
  (testing "no capital risk -- a KPI report on an already-committed deal auto-commits, no approval needed"
    (let [[db actor] (fresh)
          _ (exec-op actor "t24a" {:op :dd/assess :subject "deal-1"} operator)
          _ (approve! actor "t24a")
          _ (exec-op actor "t24b" {:op :kyc/screen :subject "party-1"} operator)
          _ (approve! actor "t24b")
          _ (exec-op actor "t24c" {:op :deal/advance-stage :subject "deal-1" :to-stage :ic-review} operator)
          _ (exec-op actor "t24e" {:op :term-sheet/propose :subject "deal-1" :proposed-by :fund
                                   :terms {:valuation 8000000 :security-type :safe}} operator)
          _ (exec-op actor "t24d" {:op :investment/commit :subject "deal-1"} operator)
          _ (approve! actor "t24d")
          res (exec-op actor "t24" {:op :portfolio/report :subject "deal-1" :period "2026-Q3"
                                    :kpis {:revenue 450000 :headcount 12}} operator)]
      (is (= :commit (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (= 1 (count (store/portfolio-reports-of db "deal-1")))))))

(deftest capital-call-overcall-is-held-and-unoverridable
  (testing "a call far exceeding total fund commitments -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t11" {:op :capital-call/issue :subject "deal-1"
                                    :call-amount 20000000 :notice-date "2026-07-06"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:capital-call-overcall} (-> (store/ledger db) first :basis)))
      (is (empty? (store/capital-call-history db)) "no call record written")
      (is (zero? (:called-amount (store/lp db "lp-1"))) "no LP called-amount advanced"))))

(deftest capital-call-blocked-by-an-unaccredited-lp-fund-wide
  (testing "a capital call cannot be issued while ANY LP in the fund lacks accreditation"
    (let [[db actor] (fresh)]
      (store/with-lps db {"lp-3" {:id "lp-3" :name "Unaffirmed LP" :commitment-amount 250000
                                  :called-amount 0 :currency "USD" :jurisdiction "USA"
                                  :accredited? false :status :pending}})
      (let [res (exec-op actor "t12" {:op :capital-call/issue :subject "deal-1"
                                      :call-amount 100000 :notice-date "2026-07-06"} operator)]
        (is (= :hold (get-in res [:state :disposition])))
        (is (some #{:accredited-investor-violation} (-> (store/ledger db) first :basis)))
        (is (empty? (store/capital-call-history db)))))))

(deftest capital-call-issue-always-escalates-then-human-decides
  (testing "a clean, pro-rata call still ALWAYS interrupts for human approval -- actuation/call is never auto"
    (let [[db actor] (fresh)
          r1 (exec-op actor "t13" {:op :capital-call/issue :subject "deal-1"
                                   :call-amount 2000000 :notice-date "2026-07-06"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, capital-call record drafted and LP called-amounts advance"
        (let [r2 (approve! actor "t13")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (= 1 (count (store/capital-call-history db))) "one draft capital-call record")
          (is (pos? (:called-amount (store/lp db "lp-1"))))
          (is (pos? (:called-amount (store/lp db "lp-2"))))))))
  (testing "reject -> hold, nothing called"
    (let [[db actor] (fresh)
          _ (exec-op actor "t14" {:op :capital-call/issue :subject "deal-1"
                                  :call-amount 2000000 :notice-date "2026-07-06"} operator)
          r2 (g/run* actor {:approval {:status :rejected :by "op-1"}}
                     {:thread-id "t14" :resume? true})]
      (is (= :hold (get-in r2 [:state :disposition])))
      (is (empty? (store/capital-call-history db)) "nothing called on reject")
      (is (zero? (:called-amount (store/lp db "lp-1")))))))

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
          _ (exec-op actor "t8c" {:op :deal/advance-stage :subject "deal-1" :to-stage :ic-review} operator)
          _ (exec-op actor "t8e" {:op :term-sheet/propose :subject "deal-1" :proposed-by :fund
                                  :terms {:valuation 8000000 :security-type :safe}} operator)
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
          _ (exec-op actor "t9c" {:op :deal/advance-stage :subject "deal-1" :to-stage :ic-review} operator)
          _ (exec-op actor "t9e" {:op :term-sheet/propose :subject "deal-1" :proposed-by :fund
                                  :terms {:valuation 8000000 :security-type :safe}} operator)
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
          _ (exec-op actor "t10z" {:op :deal/advance-stage :subject "deal-1" :to-stage :ic-review} operator)
          _ (exec-op actor "t10e" {:op :term-sheet/propose :subject "deal-1" :proposed-by :fund
                                   :terms {:valuation 8000000 :security-type :safe}} operator)
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
