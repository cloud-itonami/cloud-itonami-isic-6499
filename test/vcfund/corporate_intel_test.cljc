(ns vcfund.corporate-intel-test
  "Proves the value `vcfund.corporate-intel` actually adds: a party (founder/
  investor) that is clean on every LOCAL field (no `:sanctions-hit?`, has an
  id-doc) but IS flagged in `cloud-itonami-isic-8291`'s own demo data no
  longer silently clears -- something this repo's local-only checks alone
  would have missed entirely (see `party-4` in `vcfund.store/demo-data`,
  shared name with 8291's sanctions-flagged demo official).

  Note: `:kyc/screen` NEVER auto-commits at any phase (only stage-advance/
  term-sheet-propose/term-sheet-sign/portfolio-report/board-seat do, see
  `vcfund.phase`) -- every scenario below that reaches `:commit` does so via
  an explicit approve, same as every other `:kyc/screen` test in
  `governor_contract_test.clj`. Only a HARD violation (a local
  `:sanctions-hit?`, or a stubbed definitive corporate-intel `:hit? true`)
  settles immediately with no interrupt at all -- 8291's OWN real hits
  always escalate for 8291's own human review first (no shortcut, no
  peeking behind its DisclosureGovernor), so the end-to-end proof here is
  'no longer silently clears', not 'now hard-holds' -- see
  `corporate-intel-catches-the-hit-local-checks-miss` below."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [vcfund.store :as store]
            [vcfund.operation :as op]
            [vcfund.ddllm :as ddllm]
            [vcfund.corporate-intel :as ci]))

(def operator {:actor-id "op-1" :actor-role :investment-committee :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn- wired-actor []
  (let [db (store/seed-db)]
    [db (op/build db {:advisor (ddllm/mock-advisor {:corporate-intel-screen ci/screen})})]))

(deftest local-checks-alone-would-miss-the-8291-flagged-party
  (testing "sanity: without the integration wired in, party-4 passes every local check and clears"
    (let [db (store/seed-db)
          actor (op/build db)                          ; NO corporate-intel wired in
          res (exec-op actor "sanity" {:op :kyc/screen :subject "party-4"} operator)]
      (is (= :interrupted (:status res)) "kyc/screen always escalates for approval, clean or not")
      (approve! actor "sanity")
      (is (= :clear (:verdict (store/kyc-of db "party-4")))
          "without the integration, party-4 screens :clear -- this is the gap being closed"))))

(deftest corporate-intel-catches-the-hit-local-checks-miss
  (testing "with the REAL (unmocked) 8291 actor wired in, party-4 no longer silently clears"
    (let [[db actor] (wired-actor)
          res (exec-op actor "t1" {:op :kyc/screen :subject "party-4"} operator)]
      (is (= :interrupted (:status res))
          "8291 itself escalates a real hit for ITS OWN human review first -- 6499 never
           peeks behind that gate, so this also reads as :incomplete + low confidence here")
      (is (= :low-confidence (-> res :state :audit last :reason)))
      (approve! actor "t1")
      (is (not= :clear (:verdict (store/kyc-of db "party-4")))
          "critically: it never becomes :clear, unlike the unwired sanity case above")
      (is (= :incomplete (:verdict (store/kyc-of db "party-4")))))))

(deftest corporate-intel-definitive-hit-hard-holds
  (testing "screen-kyc's :hit? branch itself is a HARD, un-overridable hold -- proven directly
            with a stub (a real 8291 hit always escalates for 8291's own human first, so this
            branch is only reachable end-to-end after that human confirms; unit-testing it here
            keeps the assertion deterministic)"
    (let [db (store/seed-db)
          definitive-hit (fn [_name] {:found? true :hit? true :capacity :director :org "co-x"})
          actor (op/build db {:advisor (ddllm/mock-advisor {:corporate-intel-screen definitive-hit})})
          res (exec-op actor "t2" {:op :kyc/screen :subject "party-4"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (some #{:sanctions-hit} (-> (store/ledger db) first :basis)))
      (is (nil? (store/kyc-of db "party-4")) "no KYC clearance written"))))

(deftest corporate-intel-clean-party-still-clears
  (testing "a party with no local signal, and no match in 8291's demo data, still clears --
            additive, not stricter-by-default (a confident not-found is not treated as a hit)"
    (let [[db actor] (wired-actor)
          res (exec-op actor "t3" {:op :kyc/screen :subject "party-1"} operator)]
      (is (= :interrupted (:status res)))
      (approve! actor "t3")
      (is (= :clear (:verdict (store/kyc-of db "party-1")))))))

(deftest corporate-intel-local-sanctions-hit-short-circuits-before-8291-is-consulted
  (testing "a local :sanctions-hit? decides the verdict first -- 8291 is never even queried"
    (let [[db actor] (wired-actor)
          res (exec-op actor "t4" {:op :kyc/screen :subject "party-3"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:sanctions-hit} (-> (store/ledger db) first :basis))))))

(deftest corporate-intel-held-screen-degrades-to-incomplete-not-clear
  (testing "if 6499's own tenant contract with 8291 is missing/misconfigured, 8291 itself holds
            the screen -- 6499 must treat that as inconclusive (escalate), never as clear"
    (let [db (store/seed-db)
          broken-screen (fn [_name] {:held? true :reason [:licensed-disclosure]})
          actor (op/build db {:advisor (ddllm/mock-advisor {:corporate-intel-screen broken-screen})})
          res (exec-op actor "t5" {:op :kyc/screen :subject "party-4"} operator)]
      (is (= :interrupted (:status res)) "low confidence (:incomplete) -> escalate, same as a missing id-doc")
      (is (nil? (store/kyc-of db "party-4"))))))
