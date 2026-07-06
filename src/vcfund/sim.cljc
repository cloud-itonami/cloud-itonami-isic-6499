(ns vcfund.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean LP + deal through
  LP intake -> deal DD assessment -> KYC screening -> investment-commitment
  proposal (always escalates) -> human approval -> commit -> exit-
  distribution proposal (always escalates) -> human approval -> commit,
  then shows two HARD holds (a sanctions hit and a fabricated jurisdiction)
  that never reach a human at all, and prints the audit ledger + the draft
  commitment/distribution records."
  (:require [langgraph.graph :as g]
            [vcfund.store :as store]
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

    (println "== investment/commit deal-1 (always escalates -- actuation/deploy) ==")
    (let [r (exec! actor "t4" {:op :investment/commit :subject "deal-1"} operator)]
      (println r)
      (println "-- human Investment Committee approves --")
      (println (approve! actor "t4")))

    (println "== exit/distribute deal-1 (exit for 12,000,000 after 3y; always escalates -- actuation/distribute) ==")
    (let [r (exec! actor "t5" {:op :exit/distribute :subject "deal-1"
                              :exit-proceeds 12000000 :holding-period-years 3} operator)]
      (println r)
      (println "-- human Investment Committee approves --")
      (println (approve! actor "t5")))

    (println "== kyc/screen party-3 (sanctions hit -> HARD hold, never reaches a human) ==")
    (println (exec! actor "t6" {:op :kyc/screen :subject "party-3"} operator))

    (println "== dd/assess deal-2 (ATL -- no spec-basis -> HARD hold) ==")
    (println (exec! actor "t7" {:op :dd/assess :subject "deal-2"} operator))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== draft investment-commitment records ==")
    (doseq [r (store/commitment-history db)] (println r))

    (println "== draft exit-distribution records ==")
    (doseq [r (store/distribution-history db)] (println r))))
