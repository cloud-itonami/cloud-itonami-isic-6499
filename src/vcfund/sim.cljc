(ns vcfund.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean LP + deal through
  LP intake -> deal DD assessment -> KYC screening -> pipeline-stage
  advance to :ic-review (auto-commits, no capital risk) -> term-sheet
  proposal + both-sides e-signature (auto-commit) -> capital-call proposal
  (always escalates) -> human approval -> commit -> investment-commitment
  proposal (always escalates) -> human approval -> commit -> follow-on
  investment proposal (always escalates) -> human approval -> commit ->
  portfolio KPI report (auto-commits) -> exit-distribution proposal
  (always escalates) -> human approval -> commit -> whole-fund GP-clawback
  reconciliation + repayment proposal (always escalates) -> human approval
  -> commit, then shows eight HARD holds (a sanctions hit, a fabricated
  jurisdiction, an overcalled capital call, an illegal pipeline-stage
  transition, a term-sheet proposal on an already-committed deal, an
  investment-commit attempt with an unsigned term sheet, a follow-on
  proposed on a deal that was never committed, and a clawback repayment
  request exceeding the independently-recomputed whole-fund entitlement)
  that never reach a human at all, and prints the audit ledger + the
  draft capital-call/commitment/follow-on/distribution/clawback-repayment/
  portfolio-report/term-sheet records."
  (:require [langgraph.graph :as g]
            [vcfund.store :as store]
            [vcfund.waterfall :as waterfall]
            [vcfund.operation :as op]))

(def operator {:actor-id "op-1" :actor-role :investment-committee :phase 3})

(defn- exec! [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        actor (op/build db)]
    (println "== lp/intake lp-1 (already accredited; patch is a no-op update) ==")
    (println (exec! actor "t1" {:op :lp/intake :subject "lp-1"
                                :patch {:id "lp-1" :status :active}} operator))

    (println "== dd/assess deal-1 (USA, clean founders; escalates -- human approves) ==")
    (println (exec! actor "t2" {:op :dd/assess :subject "deal-1"} operator))
    (println (approve! actor "t2"))

    (println "== kyc/screen party-1 (clean; escalates -- human approves) ==")
    (println (exec! actor "t3" {:op :kyc/screen :subject "party-1"} operator))
    (println (approve! actor "t3"))

    (println "== deal/advance-stage deal-1 :sourced -> :ic-review (no capital risk; auto-commits) ==")
    (println (exec! actor "t3a" {:op :deal/advance-stage :subject "deal-1" :to-stage :ic-review} operator))

    (println "== term-sheet/propose deal-1 v0 (fund's opening terms; no capital risk; auto-commits) ==")
    (println (exec! actor "t3c" {:op :term-sheet/propose :subject "deal-1" :proposed-by :fund
                                :terms {:valuation 8000000 :security-type :safe
                                       :pro-rata-rights true :board-seat false}} operator))

    (println "== term-sheet/sign deal-1 v0 -- fund signs (no capital risk; auto-commits) ==")
    (println (exec! actor "t3d" {:op :term-sheet/sign :subject "deal-1" :signed-by :fund} operator))

    (println "== term-sheet/sign deal-1 v0 -- founder signs -- now fully executed (auto-commits) ==")
    (println (exec! actor "t3e" {:op :term-sheet/sign :subject "deal-1" :signed-by :founder} operator))

    (println "== capital-call/issue deal-1 (call $2,000,000 pro-rata from LPs; always escalates -- actuation/call) ==")
    (let [r (exec! actor "t3b" {:op :capital-call/issue :subject "deal-1"
                               :call-amount 2000000 :notice-date "2026-07-06"} operator)]
      (println r)
      (println "-- human Investment Committee approves --")
      (println (approve! actor "t3b")))

    (println "== investment/commit deal-1 (always escalates -- actuation/deploy) ==")
    (let [r (exec! actor "t4" {:op :investment/commit :subject "deal-1"} operator)]
      (println r)
      (println "-- human Investment Committee approves --")
      (println (approve! actor "t4")))

    (println "== investment/follow-on deal-1 (follow-on round, $750,000 priced-equity; always escalates -- actuation/deploy) ==")
    (let [r (exec! actor "t4a" {:op :investment/follow-on :subject "deal-1"
                                :security-type :priced-equity :amount 750000} operator)]
      (println r)
      (println "-- human Investment Committee approves --")
      (println (approve! actor "t4a")))

    (println "== portfolio/report deal-1 (Q3 KPIs on a committed deal; no capital risk; auto-commits) ==")
    (println (exec! actor "t4b" {:op :portfolio/report :subject "deal-1" :period "2026-Q3"
                                :kpis {:revenue 450000 :burn-rate 80000 :runway-months 14 :headcount 12}} operator))

    (println "== exit/distribute deal-1 (exit for 12,000,000 after 3y; always escalates -- actuation/distribute) ==")
    (let [r (exec! actor "t5" {:op :exit/distribute :subject "deal-1"
                              :exit-proceeds 12000000 :holding-period-years 3} operator)]
      (println r)
      (println "-- human Investment Committee approves --")
      (println (approve! actor "t5")))

    (println "== waterfall/clawback-repay whole-fund (fund-life stretched to 100y for the demo, so the whole-fund GP entitlement collapses toward zero and the GP owes back what deal-by-deal carry already paid; always escalates -- actuation/clawback) ==")
    (let [{:keys [gp-clawback] :as wf} (waterfall/whole-fund-waterfall-report db 100)]
      (println (str "whole-fund waterfall reconciliation: " wf))
      (let [r (exec! actor "t5a" {:op :waterfall/clawback-repay :subject "fund"
                                  :amount gp-clawback :effective-date "2026-07-06"
                                  :fund-life-years 100} operator)]
        (println r)
        (println "-- human Investment Committee approves --")
        (println (approve! actor "t5a"))))

    (println "== kyc/screen party-3 (sanctions hit -> HARD hold, never reaches a human) ==")
    (println (exec! actor "t6" {:op :kyc/screen :subject "party-3"} operator))

    (println "== dd/assess deal-2 (ATL -- no spec-basis -> HARD hold) ==")
    (println (exec! actor "t7" {:op :dd/assess :subject "deal-2"} operator))

    (println "== capital-call/issue deal-3 ($20,000,000 -- overcalls every LP -> HARD hold) ==")
    (println (exec! actor "t8" {:op :capital-call/issue :subject "deal-3"
                                :call-amount 20000000 :notice-date "2026-07-06"} operator))

    (println "== deal/advance-stage deal-3 :sourced -> :committed (illegal -- only the real commit op may reach :committed -> HARD hold) ==")
    (println (exec! actor "t9" {:op :deal/advance-stage :subject "deal-3" :to-stage :committed} operator))

    (println "== term-sheet/propose deal-1 v1 (already committed -> HARD hold, never reaches a human) ==")
    (println (exec! actor "t10c" {:op :term-sheet/propose :subject "deal-1" :proposed-by :founder
                                  :terms {:valuation 9000000}} operator))

    (println "== investment/commit deal-3 (term sheet proposed but never signed, plus other outstanding gates -> HARD hold) ==")
    (println (exec! actor "t11a" {:op :term-sheet/propose :subject "deal-3" :proposed-by :fund
                                  :terms {:valuation 1500000}} operator))
    (println (exec! actor "t11" {:op :investment/commit :subject "deal-3"} operator))

    (println "== investment/follow-on deal-2 (deal-2 was never committed -> HARD hold, never reaches a human) ==")
    (println (exec! actor "t12" {:op :investment/follow-on :subject "deal-2"
                                 :security-type :priced-equity :amount 100000} operator))

    (println "== waterfall/clawback-repay whole-fund (requesting far more than the independently-recomputed entitlement -> HARD hold) ==")
    (println (exec! actor "t13" {:op :waterfall/clawback-repay :subject "fund"
                                 :amount 999999999 :effective-date "2026-07-06"
                                 :fund-life-years 3} operator))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== draft capital-call records ==")
    (doseq [r (store/capital-call-history db)] (println r))

    (println "== draft investment-commitment records ==")
    (doseq [r (store/commitment-history db)] (println r))

    (println "== draft follow-on investment-commitment records (deal-1) ==")
    (doseq [r (store/follow-on-history-of db "deal-1")] (println r))

    (println "== draft portfolio-report records (deal-1) ==")
    (doseq [r (store/portfolio-reports-of db "deal-1")] (println r))

    (println "== draft term-sheet records (deal-1) ==")
    (doseq [r (store/term-sheet-history-of db "deal-1")] (println r))

    (println "== term-sheet e-signature records (deal-1) ==")
    (doseq [r (store/signature-history-of db "deal-1")] (println r))

    (println "== draft exit-distribution records ==")
    (doseq [r (store/distribution-history db)] (println r))

    (println "== draft GP-clawback-repayment records (fund-level, not deal-scoped) ==")
    (doseq [r (store/clawback-repayment-history db)] (println r))))
