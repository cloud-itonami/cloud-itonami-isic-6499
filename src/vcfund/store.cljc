(ns vcfund.store
  "SSoT for the venture-fund actor, behind a `Store` protocol so the backend
  is a swap, not a rewrite -- the same seam `cloud-itonami-isic-6511`
  (`underwriting.store`) uses:

    - `MemStore`     -- atom of EDN. The deterministic default for
                        dev/tests/demo (no deps).
    - `DatomicStore` -- backed by `langchain.db`, a Datomic-API-compatible
                        EAV store (datalog q / pull / upsert). Pure `.cljc`,
                        so it runs offline AND can be pointed at a real
                        Datomic Local or a kotoba-server pod by swapping
                        `langchain.db`'s `:db-api` (see langchain.kotoba-db).

  Both implement the same protocol and pass the same contract
  (test/vcfund/store_contract_test.clj), which is the whole point: the
  actor, the InvestmentCommitteeGovernor and the audit ledger never know
  which SSoT they run on.

  The ledger stays append-only on every backend: 'who called what capital
  from which LPs, who committed what capital to which deal, on what DD
  basis, approved by whom' (and symmetrically for exit distributions) is
  always a query over an immutable log -- the audit trail an LP trusting a
  GP with their capital needs, and the evidence an operator needs if a
  call, commitment or distribution is later disputed."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [vcfund.registry :as registry]
            [langchain.db :as d]))

(defprotocol Store
  (deal [s id])
  (all-deals [s])
  (lp [s id])
  (all-lps [s])
  (party [s id])
  (kyc-of [s party-id] "committed KYC screening verdict for a party, or nil")
  (assessment-of [s deal-id] "committed DD-checklist assessment for a deal, or nil")
  (commitment-of [s deal-id] "committed investment-commitment record for a deal, or nil")
  (ledger [s])
  (capital-call-history [s] "the append-only capital-call history (vcfund.registry drafts)")
  (commitment-history [s] "the append-only investment-commitment history (vcfund.registry drafts)")
  (distribution-history [s] "the append-only exit-distribution history (vcfund.registry drafts)")
  (portfolio-reports-of [s deal-id] "the append-only KPI-report history for one deal, oldest first")
  (term-sheet-history-of [s deal-id] "the append-only versioned term-sheet negotiation history for one deal, oldest first")
  (signature-history-of [s deal-id] "the append-only term-sheet e-signature history for one deal, oldest first")
  (follow-on-history-of [s deal-id] "the append-only follow-on investment-commitment history for one deal, oldest first")
  (clawback-repayment-history [s] "the append-only, fund-level (not deal-scoped) GP-clawback-repayment history")
  (board-seat-history-of [s deal-id] "the append-only board-seat/governance-rights event history for one deal, oldest first")
  (next-sequence [s jurisdiction] "next commitment-number sequence for a jurisdiction")
  (call-sequence [s jurisdiction] "next capital-call-number sequence for a jurisdiction")
  (follow-on-sequence [s jurisdiction] "next follow-on-number sequence for a jurisdiction")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-lps [s lps] "replace/seed the LP directory (map id->lp)")
  (with-parties [s parties] "replace/seed the party directory (map id->party)")
  (with-deals [s deals] "replace/seed the deal directory (map id->deal)"))

;; ----------------------------- demo data -----------------------------

(def default-preferred-return-rate 0.08)
(def default-carry-rate 0.20)

(defn demo-data
  "A small, self-contained LP/deal/party set so the actor + tests run offline."
  []
  {:lps
   {"lp-1" {:id "lp-1" :name "Sequoia Fund of Funds" :commitment-amount 5000000
            :called-amount 0 :currency "USD" :jurisdiction "USA" :accredited? true :status :active
            :wallet-address "0x71C7656EC7ab88b098defB751B7401B5f6d8976"}
    "lp-2" {:id "lp-2" :name "個人LP 山田太郎" :commitment-amount 1000000
            :called-amount 0 :currency "JPY" :jurisdiction "JPN" :accredited? true :status :active}}
   :deals
   {"deal-1" {:id "deal-1" :portfolio-company "Acme AI, Inc." :founders ["party-1" "party-2"]
              :jurisdiction "USA" :ask-amount 2000000 :currency "USD"
              :security-type :safe :status :sourced
              :sector "ai" :investment-stage "seed"}
    "deal-2" {:id "deal-2" :portfolio-company "Orbit Robotics KK" :founders ["party-1"]
              :jurisdiction "ATL" :ask-amount 500000 :currency "USD"
              :security-type :priced-equity :status :sourced
              :sector "robotics" :investment-stage "series-a"}
    "deal-3" {:id "deal-3" :portfolio-company "Shady Corp" :founders ["party-3"]
              :jurisdiction "USA" :ask-amount 300000 :currency "USD"
              :security-type :convertible-note :status :sourced
              :sector "fintech" :investment-stage "seed"}}
   :parties
   {"party-1" {:id "party-1" :name "Jane Founder" :role :founder :sanctions-hit? false :id-doc "passport-us-****1234"}
    "party-2" {:id "party-2" :name "John Cofounder" :role :founder :sanctions-hit? false :id-doc "passport-us-****5678"}
    "party-3" {:id "party-3" :name "Sanctioned Founder" :role :founder :sanctions-hit? true :id-doc nil}}})

;; ----------------------------- shared commit/distribute logic -----------------------------

(defn- issue-capital-call!
  "Backend-agnostic `:capital-call/mark-issued` -- recomputes the pro-rata
  allocation from the current LP directory (never trusts a caller-supplied
  allocation), drafts the capital-call notice record, and returns
  {:result .. :allocations ..} for the caller to persist (each LP's
  `:called-amount` advances to its `:new-called-amount`)."
  [s jurisdiction call-amount notice-date]
  (let [allocations (registry/capital-call-allocations (all-lps s) call-amount)
        seq-n (call-sequence s jurisdiction)
        result (registry/register-capital-call allocations call-amount jurisdiction seq-n notice-date)]
    {:result result :allocations allocations}))

(defn- commit-investment!
  "Backend-agnostic `:investment/mark-committed` -- looks up the deal via
  the protocol, drafts the investment-commitment record, and returns
  {:result .. :deal-patch ..} for the caller to persist. Pure w.r.t. any
  particular backend's transaction mechanics."
  [s deal-id]
  (let [d (deal s deal-id)
        seq-n (next-sequence s (:jurisdiction d))
        result (registry/register-commitment
                (:portfolio-company d) (:security-type d) (:ask-amount d)
                (:jurisdiction d) seq-n)]
    {:result result
     :deal-patch {:status :committed
                  :commitment-number (get result "commitment_number")}}))

(defn- commit-follow-on!
  "Backend-agnostic follow-on investment commit -- looks up the deal's
  EXISTING commitment record (required -- a follow-on references the
  deal's original tranche) and the caller-supplied round facts
  (`:security-type`/`:amount`, real facts about the new round, never
  invented here), drafts the follow-on commitment record referencing the
  original commitment number, and returns {:result ..}. The deal's
  `:status` stays `:committed` -- a follow-on doesn't change the deal's
  lifecycle status, only its (append-only) commitment history."
  [s deal-id {:keys [security-type amount]}]
  (let [d (deal s deal-id)
        original (commitment-of s deal-id)
        seq-n (follow-on-sequence s (:jurisdiction d))
        result (registry/register-follow-on-commitment
                (:portfolio-company d) (get original "record_id") security-type amount
                (:jurisdiction d) seq-n)]
    {:result result}))

(defn- repay-clawback!
  "Backend-agnostic GP-clawback repayment -- drafts the repayment record.
  Pure w.r.t. any particular backend's transaction mechanics; the amount
  itself is independently re-validated against `vcfund.waterfall` by the
  governor (`clawback-exceeds-entitlement-violations`) before this ever
  runs, never trusted from the proposal alone."
  [s {:keys [amount effective-date]}]
  (let [seq-n (count (clawback-repayment-history s))
        result (registry/register-clawback-repayment amount seq-n effective-date)]
    {:result result}))

(defn- record-board-seat!
  "Backend-agnostic `:governance/board-seat` -- drafts one board-seat
  grant/revocation event and returns {:result ..} for the caller to
  persist (append-only per-deal history; `vcfund.registry/current-board-
  seats` projects the CURRENT roster from it, never mutating the log)."
  [s deal-id {:keys [seat-holder seat-type event effective-date]}]
  (let [seq-n (count (board-seat-history-of s deal-id))
        result (registry/register-board-seat-event
                deal-id seat-holder seat-type event effective-date seq-n)]
    {:result result}))

(defn- distribute-exit!
  "Backend-agnostic `:distribution/mark-paid` -- looks up the deal's
  committed investment, computes the deal-by-deal waterfall from the
  caller-supplied real-world exit facts (`:exit-proceeds`/
  `:holding-period-years` -- the actor never invents these, they come from
  an actual signed closing statement), and returns
  {:result .. :deal-patch ..}."
  [s deal-id {:keys [exit-proceeds holding-period-years]}]
  (let [c (commitment-of s deal-id)
        waterfall (registry/distribute-waterfall
                   {:contributed-capital   (get-in c ["amount"])
                    :exit-proceeds         exit-proceeds
                    :preferred-return-rate default-preferred-return-rate
                    :holding-period-years  holding-period-years
                    :carry-rate            default-carry-rate})
        result (registry/register-distribution
                (get c "record_id") waterfall (str holding-period-years "y"))]
    {:result result
     :deal-patch {:status :exited}
     :waterfall waterfall}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (deal [_ id] (get-in @a [:deals id]))
  (all-deals [_] (sort-by :id (vals (:deals @a))))
  (lp [_ id] (get-in @a [:lps id]))
  (all-lps [_] (sort-by :id (vals (:lps @a))))
  (party [_ id] (get-in @a [:parties id]))
  (kyc-of [_ id] (get-in @a [:kyc id]))
  (assessment-of [_ deal-id] (get-in @a [:assessments deal-id]))
  (commitment-of [_ deal-id] (get-in @a [:commitments deal-id]))
  (ledger [_] (:ledger @a))
  (capital-call-history [_] (:capital-call-history @a))
  (commitment-history [_] (:commitment-history @a))
  (distribution-history [_] (:distribution-history @a))
  (portfolio-reports-of [_ deal-id] (get-in @a [:portfolio-reports deal-id] []))
  (term-sheet-history-of [_ deal-id] (get-in @a [:term-sheets deal-id] []))
  (signature-history-of [_ deal-id] (get-in @a [:term-sheet-signatures deal-id] []))
  (follow-on-history-of [_ deal-id] (get-in @a [:follow-on-history deal-id] []))
  (clawback-repayment-history [_] (:clawback-repayment-history @a))
  (board-seat-history-of [_ deal-id] (get-in @a [:board-seats deal-id] []))
  (next-sequence [_ jurisdiction]
    (get-in @a [:sequences jurisdiction] 0))
  (call-sequence [_ jurisdiction]
    (get-in @a [:call-sequences jurisdiction] 0))
  (follow-on-sequence [_ jurisdiction]
    (get-in @a [:follow-on-sequences jurisdiction] 0))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :lp/upsert
      (swap! a update-in [:lps (:id value)] merge value)

      :assessment/set
      (swap! a assoc-in [:assessments (first path)] payload)

      :kyc/set
      (swap! a assoc-in [:kyc (first path)] payload)

      :deal/advance-stage
      (swap! a assoc-in [:deals (first path) :status] (:to-stage payload))

      :term-sheet/proposed
      (let [deal-id (first path)
            {:keys [proposed-by terms]} payload
            version (count (get-in @a [:term-sheets deal-id] []))
            result (registry/register-term-sheet deal-id proposed-by terms version)]
        (swap! a update-in [:term-sheets deal-id] (fnil registry/append []) result)
        result)

      :term-sheet/signed
      (let [deal-id (first path)
            {:keys [version signed-by]} payload
            result (registry/register-term-sheet-signature deal-id version signed-by)]
        (swap! a update-in [:term-sheet-signatures deal-id] (fnil registry/append []) result)
        result)

      :portfolio/report-logged
      (let [deal-id (first path)
            {:keys [period kpis]} payload
            result (registry/register-portfolio-report deal-id period kpis)]
        (swap! a update-in [:portfolio-reports deal-id] (fnil registry/append []) result)
        result)

      :capital-call/mark-issued
      (let [{:keys [jurisdiction call-amount notice-date]} payload
            {:keys [result allocations]} (issue-capital-call! s jurisdiction call-amount notice-date)]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:call-sequences jurisdiction] (fnil inc 0))
                       (update :lps (fn [lps]
                                      (reduce (fn [m {:keys [lp-id new-called-amount]}]
                                                (update m lp-id assoc :called-amount new-called-amount))
                                              lps allocations)))
                       (update :capital-call-history registry/append result))))
        result)

      :investment/mark-committed
      (let [deal-id (first path)
            {:keys [result deal-patch]} (commit-investment! s deal-id)]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:sequences (:jurisdiction (get-in state [:deals deal-id]))] (fnil inc 0))
                       (update-in [:deals deal-id] merge deal-patch)
                       (assoc-in [:commitments deal-id] (get result "record"))
                       (update :commitment-history registry/append result))))
        result)

      :investment/follow-on-committed
      (let [deal-id (first path)
            {:keys [result]} (commit-follow-on! s deal-id payload)]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:follow-on-sequences (:jurisdiction (get-in state [:deals deal-id]))] (fnil inc 0))
                       (update-in [:follow-on-history deal-id] (fnil registry/append []) result))))
        result)

      :governance/board-seat-recorded
      (let [deal-id (first path)
            {:keys [result]} (record-board-seat! s deal-id payload)]
        (swap! a update-in [:board-seats deal-id] (fnil registry/append []) result)
        result)

      :distribution/mark-paid
      (let [deal-id (first path)
            {:keys [result deal-patch]} (distribute-exit! s deal-id payload)]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:deals deal-id] merge deal-patch)
                       (update :distribution-history registry/append result))))
        result)

      :waterfall/clawback-repaid
      (let [{:keys [result]} (repay-clawback! s payload)]
        (swap! a update :clawback-repayment-history registry/append result)
        result)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-lps [s lps] (when (seq lps) (swap! a update :lps merge lps)) s)
  (with-parties [s parties] (when (seq parties) (swap! a update :parties merge parties)) s)
  (with-deals [s deals] (when (seq deals) (swap! a update :deals merge deals)) s))

(defn seed-db
  "A MemStore seeded with the demo LP/deal/party set. The deterministic default."
  []
  (->MemStore (atom (assoc (demo-data)
                           :assessments {} :kyc {} :ledger [] :sequences {} :call-sequences {}
                           :follow-on-sequences {}
                           :commitments {} :commitment-history []
                           :capital-call-history [] :distribution-history []
                           :portfolio-reports {} :term-sheets {} :term-sheet-signatures {}
                           :follow-on-history {} :clawback-repayment-history []
                           :board-seats {}))))

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  Map/compound values (founder id lists, KYC/assessment/commitment
  payloads, ledger facts) are stored as EDN strings so `langchain.db`
  doesn't expand them into sub-entities -- the same convention
  `underwriting.store` uses."
  {:lp/id             {:db/unique :db.unique/identity}
   :deal/id           {:db/unique :db.unique/identity}
   :party/id          {:db/unique :db.unique/identity}
   :kyc/party-id      {:db/unique :db.unique/identity}
   :assessment/deal-id {:db/unique :db.unique/identity}
   :commitment/deal-id {:db/unique :db.unique/identity}
   :ledger/seq        {:db/unique :db.unique/identity}
   :capital-call-history/seq {:db/unique :db.unique/identity}
   :commitment-history/seq {:db/unique :db.unique/identity}
   :distribution-history/seq {:db/unique :db.unique/identity}
   :sequence/jurisdiction {:db/unique :db.unique/identity}
   :call-sequence/jurisdiction {:db/unique :db.unique/identity}
   :portfolio-report/seq {:db/unique :db.unique/identity}
   :term-sheet/seq {:db/unique :db.unique/identity}
   :term-sheet-signature/seq {:db/unique :db.unique/identity}
   :follow-on/seq {:db/unique :db.unique/identity}
   :follow-on-sequence/jurisdiction {:db/unique :db.unique/identity}
   :clawback-repayment/seq {:db/unique :db.unique/identity}
   :board-seat/seq {:db/unique :db.unique/identity}})

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

(defn- portfolio-report-count
  "Global count of portfolio-report entities across ALL deals -- used as
  the next `:portfolio-report/seq` (a single global counter, the same
  convention `:ledger/seq` uses, since `:portfolio-report/seq` must be
  globally unique, not merely unique per deal)."
  [conn]
  (count (d/q '[:find [?s ...] :where [?e :portfolio-report/seq ?s]] (d/db conn))))

(defn- term-sheet-count
  "Global count of term-sheet entities across ALL deals -- the identity
  key (`:term-sheet/seq`, needs global uniqueness); the per-deal `version`
  stored inside the record itself is a separate, per-deal count."
  [conn]
  (count (d/q '[:find [?s ...] :where [?e :term-sheet/seq ?s]] (d/db conn))))

(defn- term-sheet-signature-count
  "Global count of term-sheet-signature entities across ALL deals -- same
  `:ledger/seq`-style global-identity convention as `term-sheet-count`."
  [conn]
  (count (d/q '[:find [?s ...] :where [?e :term-sheet-signature/seq ?s]] (d/db conn))))

(defn- follow-on-count
  "Global count of follow-on-commitment entities across ALL deals -- same
  `:ledger/seq`-style global-identity convention as `term-sheet-count`."
  [conn]
  (count (d/q '[:find [?s ...] :where [?e :follow-on/seq ?s]] (d/db conn))))

(defn- clawback-repayment-count
  "Global count of GP-clawback-repayment entities -- fund-level, not
  deal-scoped, so this is already the only count the identity needs."
  [conn]
  (count (d/q '[:find [?s ...] :where [?e :clawback-repayment/seq ?s]] (d/db conn))))

(defn- board-seat-count
  "Global count of board-seat-event entities across ALL deals -- same
  `:ledger/seq`-style global-identity convention as `term-sheet-count`."
  [conn]
  (count (d/q '[:find [?s ...] :where [?e :board-seat/seq ?s]] (d/db conn))))

(defn- lp->tx [{:keys [id name commitment-amount called-amount currency jurisdiction
                       accredited? status wallet-address]}]
  (cond-> {:lp/id id}
    name              (assoc :lp/name name)
    commitment-amount (assoc :lp/commitment-amount commitment-amount)
    (some? called-amount) (assoc :lp/called-amount called-amount)
    currency          (assoc :lp/currency currency)
    jurisdiction       (assoc :lp/jurisdiction jurisdiction)
    (some? accredited?) (assoc :lp/accredited? accredited?)
    status            (assoc :lp/status status)
    wallet-address    (assoc :lp/wallet-address wallet-address)))

(def ^:private lp-pull
  [:lp/id :lp/name :lp/commitment-amount :lp/called-amount :lp/currency :lp/jurisdiction
   :lp/accredited? :lp/status :lp/wallet-address])

(defn- pull->lp [m]
  (when (:lp/id m)
    {:id (:lp/id m) :name (:lp/name m) :commitment-amount (:lp/commitment-amount m)
     :called-amount (or (:lp/called-amount m) 0)
     :currency (:lp/currency m) :jurisdiction (:lp/jurisdiction m)
     :accredited? (boolean (:lp/accredited? m)) :status (:lp/status m)
     :wallet-address (:lp/wallet-address m)}))

(defn- deal->tx [{:keys [id portfolio-company founders jurisdiction ask-amount
                        currency security-type status commitment-number
                        sector investment-stage]}]
  (cond-> {:deal/id id}
    portfolio-company (assoc :deal/portfolio-company portfolio-company)
    founders          (assoc :deal/founders (enc founders))
    jurisdiction       (assoc :deal/jurisdiction jurisdiction)
    ask-amount        (assoc :deal/ask-amount ask-amount)
    currency          (assoc :deal/currency currency)
    security-type     (assoc :deal/security-type (enc security-type))
    status            (assoc :deal/status (enc status))
    commitment-number (assoc :deal/commitment-number commitment-number)
    sector            (assoc :deal/sector sector)
    investment-stage  (assoc :deal/investment-stage investment-stage)))

(def ^:private deal-pull
  [:deal/id :deal/portfolio-company :deal/founders :deal/jurisdiction :deal/ask-amount
   :deal/currency :deal/security-type :deal/status :deal/commitment-number
   :deal/sector :deal/investment-stage])

(defn- pull->deal [m]
  (when (:deal/id m)
    {:id (:deal/id m) :portfolio-company (:deal/portfolio-company m)
     :founders (or (dec* (:deal/founders m)) [])
     :jurisdiction (:deal/jurisdiction m) :ask-amount (:deal/ask-amount m)
     :currency (:deal/currency m) :security-type (dec* (:deal/security-type m))
     :status (dec* (:deal/status m)) :commitment-number (:deal/commitment-number m)
     :sector (:deal/sector m) :investment-stage (:deal/investment-stage m)}))

(defn- party->tx [{:keys [id name role sanctions-hit? id-doc]}]
  (cond-> {:party/id id}
    name (assoc :party/name name)
    role (assoc :party/role (enc role))
    (some? sanctions-hit?) (assoc :party/sanctions-hit? sanctions-hit?)
    id-doc (assoc :party/id-doc id-doc)))

(defn- pull->party [m]
  (when (:party/id m)
    {:id (:party/id m) :name (:party/name m) :role (dec* (:party/role m))
     :sanctions-hit? (boolean (:party/sanctions-hit? m)) :id-doc (:party/id-doc m)}))

(defrecord DatomicStore [conn]
  Store
  (deal [_ id]
    (pull->deal (d/pull (d/db conn) deal-pull [:deal/id id])))
  (all-deals [_]
    (->> (d/q '[:find [?id ...] :where [?e :deal/id ?id]] (d/db conn))
         (map #(pull->deal (d/pull (d/db conn) deal-pull [:deal/id %])))
         (sort-by :id)))
  (lp [_ id]
    (pull->lp (d/pull (d/db conn) lp-pull [:lp/id id])))
  (all-lps [_]
    (->> (d/q '[:find [?id ...] :where [?e :lp/id ?id]] (d/db conn))
         (map #(pull->lp (d/pull (d/db conn) lp-pull [:lp/id %])))
         (sort-by :id)))
  (party [_ id]
    (pull->party (d/pull (d/db conn)
                         [:party/id :party/name :party/role :party/sanctions-hit? :party/id-doc]
                         [:party/id id])))
  (kyc-of [_ id]
    (dec* (d/q '[:find ?p . :in $ ?pid
                :where [?k :kyc/party-id ?pid] [?k :kyc/payload ?p]]
              (d/db conn) id)))
  (assessment-of [_ deal-id]
    (dec* (d/q '[:find ?p . :in $ ?did
                :where [?a :assessment/deal-id ?did] [?a :assessment/payload ?p]]
              (d/db conn) deal-id)))
  (commitment-of [_ deal-id]
    (dec* (d/q '[:find ?p . :in $ ?did
                :where [?c :commitment/deal-id ?did] [?c :commitment/payload ?p]]
              (d/db conn) deal-id)))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (capital-call-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :capital-call-history/seq ?s] [?e :capital-call-history/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (commitment-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :commitment-history/seq ?s] [?e :commitment-history/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (distribution-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :distribution-history/seq ?s] [?e :distribution-history/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (portfolio-reports-of [_ deal-id]
    (->> (d/q '[:find ?s ?r :in $ ?did
               :where [?e :portfolio-report/deal-id ?did]
                      [?e :portfolio-report/seq ?s]
                      [?e :portfolio-report/record ?r]]
              (d/db conn) deal-id)
         (sort-by first)
         (mapv (comp dec* second))))
  (term-sheet-history-of [_ deal-id]
    (->> (d/q '[:find ?s ?r :in $ ?did
               :where [?e :term-sheet/deal-id ?did]
                      [?e :term-sheet/seq ?s]
                      [?e :term-sheet/record ?r]]
              (d/db conn) deal-id)
         (sort-by first)
         (mapv (comp dec* second))))
  (signature-history-of [_ deal-id]
    (->> (d/q '[:find ?s ?r :in $ ?did
               :where [?e :term-sheet-signature/deal-id ?did]
                      [?e :term-sheet-signature/seq ?s]
                      [?e :term-sheet-signature/record ?r]]
              (d/db conn) deal-id)
         (sort-by first)
         (mapv (comp dec* second))))
  (follow-on-history-of [_ deal-id]
    (->> (d/q '[:find ?s ?r :in $ ?did
               :where [?e :follow-on/deal-id ?did]
                      [?e :follow-on/seq ?s]
                      [?e :follow-on/record ?r]]
              (d/db conn) deal-id)
         (sort-by first)
         (mapv (comp dec* second))))
  (clawback-repayment-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :clawback-repayment/seq ?s] [?e :clawback-repayment/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (board-seat-history-of [_ deal-id]
    (->> (d/q '[:find ?s ?r :in $ ?did
               :where [?e :board-seat/deal-id ?did]
                      [?e :board-seat/seq ?s]
                      [?e :board-seat/record ?r]]
              (d/db conn) deal-id)
         (sort-by first)
         (mapv (comp dec* second))))
  (next-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :sequence/jurisdiction ?j] [?e :sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (call-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :call-sequence/jurisdiction ?j] [?e :call-sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (follow-on-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :follow-on-sequence/jurisdiction ?j] [?e :follow-on-sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :lp/upsert
      (d/transact! conn [(lp->tx value)])

      :assessment/set
      (d/transact! conn [{:assessment/deal-id (first path) :assessment/payload (enc payload)}])

      :kyc/set
      (d/transact! conn [{:kyc/party-id (first path) :kyc/payload (enc payload)}])

      :deal/advance-stage
      (d/transact! conn [{:deal/id (first path) :deal/status (enc (:to-stage payload))}])

      :term-sheet/proposed
      (let [deal-id (first path)
            {:keys [proposed-by terms]} payload
            version (count (term-sheet-history-of s deal-id))
            result (registry/register-term-sheet deal-id proposed-by terms version)]
        (d/transact! conn [{:term-sheet/seq (term-sheet-count conn)
                           :term-sheet/deal-id deal-id
                           :term-sheet/record (enc (get result "record"))}])
        result)

      :term-sheet/signed
      (let [deal-id (first path)
            {:keys [version signed-by]} payload
            result (registry/register-term-sheet-signature deal-id version signed-by)]
        (d/transact! conn [{:term-sheet-signature/seq (term-sheet-signature-count conn)
                           :term-sheet-signature/deal-id deal-id
                           :term-sheet-signature/record (enc (get result "record"))}])
        result)

      :portfolio/report-logged
      (let [deal-id (first path)
            {:keys [period kpis]} payload
            result (registry/register-portfolio-report deal-id period kpis)]
        (d/transact! conn [{:portfolio-report/seq (portfolio-report-count conn)
                           :portfolio-report/deal-id deal-id
                           :portfolio-report/record (enc (get result "record"))}])
        result)

      :capital-call/mark-issued
      (let [{:keys [jurisdiction call-amount notice-date]} payload
            {:keys [result allocations]} (issue-capital-call! s jurisdiction call-amount notice-date)
            next-n (inc (call-sequence s jurisdiction))]
        (d/transact! conn
                     (into [{:call-sequence/jurisdiction jurisdiction :call-sequence/next next-n}
                            {:capital-call-history/seq (count (capital-call-history s))
                             :capital-call-history/record (enc (get result "record"))}]
                           (map (fn [{:keys [lp-id new-called-amount]}]
                                  {:lp/id lp-id :lp/called-amount new-called-amount}))
                           allocations))
        result)

      :investment/mark-committed
      (let [deal-id (first path)
            {:keys [result deal-patch]} (commit-investment! s deal-id)
            jurisdiction (:jurisdiction (deal s deal-id))
            next-n (inc (next-sequence s jurisdiction))]
        (d/transact! conn
                     [(deal->tx (assoc deal-patch :id deal-id))
                      {:sequence/jurisdiction jurisdiction :sequence/next next-n}
                      {:commitment/deal-id deal-id :commitment/payload (enc (get result "record"))}
                      {:commitment-history/seq (count (commitment-history s))
                       :commitment-history/record (enc (get result "record"))}])
        result)

      :investment/follow-on-committed
      (let [deal-id (first path)
            {:keys [result]} (commit-follow-on! s deal-id payload)
            jurisdiction (:jurisdiction (deal s deal-id))
            next-n (inc (follow-on-sequence s jurisdiction))]
        (d/transact! conn
                     [{:follow-on-sequence/jurisdiction jurisdiction :follow-on-sequence/next next-n}
                      {:follow-on/seq (follow-on-count conn)
                       :follow-on/deal-id deal-id
                       :follow-on/record (enc (get result "record"))}])
        result)

      :governance/board-seat-recorded
      (let [deal-id (first path)
            {:keys [result]} (record-board-seat! s deal-id payload)]
        (d/transact! conn [{:board-seat/seq (board-seat-count conn)
                           :board-seat/deal-id deal-id
                           :board-seat/record (enc (get result "record"))}])
        result)

      :distribution/mark-paid
      (let [deal-id (first path)
            {:keys [result deal-patch]} (distribute-exit! s deal-id payload)]
        (d/transact! conn
                     [(deal->tx (assoc deal-patch :id deal-id))
                      {:distribution-history/seq (count (distribution-history s))
                       :distribution-history/record (enc (get result "record"))}])
        result)

      :waterfall/clawback-repaid
      (let [{:keys [result]} (repay-clawback! s payload)]
        (d/transact! conn [{:clawback-repayment/seq (clawback-repayment-count conn)
                           :clawback-repayment/record (enc (get result "record"))}])
        result)
      nil)
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (enc fact)}])
    fact)
  (with-lps [s lps]
    (when (seq lps) (d/transact! conn (mapv lp->tx (vals lps)))) s)
  (with-parties [s parties]
    (when (seq parties) (d/transact! conn (mapv party->tx (vals parties)))) s)
  (with-deals [s deals]
    (when (seq deals) (d/transact! conn (mapv deal->tx (vals deals)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data`
  ({:lps .. :deals .. :parties ..}); empty when omitted."
  ([] (datomic-store {}))
  ([{:keys [lps deals parties]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (-> s (with-lps lps) (with-deals deals) (with-parties parties)))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo LP/deal/party set -- the
  Datomic-backed analog of `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))
