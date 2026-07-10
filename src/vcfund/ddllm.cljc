(ns vcfund.ddllm
  "DD-LLM client -- the *contained intelligence node*.

  It normalizes LP subscription intake, drafts a per-jurisdiction deal
  due-diligence checklist, screens LPs/founders against a KYC/sanctions
  signal, and drafts the capital-call, investment-commitment and
  exit-distribution actions. CRITICAL: it is a smart-but-untrusted advisor.
  It returns a *proposal* (with a rationale + the fields it cited), never a
  committed record or a real capital movement. Every output is censored
  downstream by `vcfund.governor` before anything touches the SSoT, and
  `:capital-call/issue`/`:investment/commit`/`:exit/distribute` proposals
  NEVER auto-commit at any phase -- see README `Actuation`.

  Like `underwriting.underwriterllm`, this is a deterministic mock so the
  actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the spec-basis gate
     :cites      [kw|str ..]    ; facts/sources the LLM used -- SCANNED too
     :effect     kw             ; how a commit would mutate the SSoT
     :stake      kw|nil         ; :actuation/call | :actuation/deploy | :actuation/distribute | nil
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [vcfund.facts :as facts]
            [vcfund.pipeline :as pipeline]
            [vcfund.registry :as registry]
            [vcfund.store :as store]
            [vcfund.waterfall :as waterfall]
            [langchain.model :as model]))

(defn- normalize-lp-intake
  "Directory upsert -- the LLM only normalizes/validates the patch; it does
  not invent an LP's commitment amount or accreditation status. High
  confidence, low stakes."
  [_db {:keys [patch]}]
  {:summary    (str "LPレコード更新: " (pr-str (keys patch)))
   :rationale  "入力 patch の正規化のみ。新規事実の生成なし。"
   :cites      (vec (keys patch))
   :effect     :lp/upsert
   :value      patch
   :stake      nil
   :confidence 0.97})

(defn- assess-dd
  "Per-jurisdiction deal due-diligence checklist draft. A deal whose
  jurisdiction has NO official spec-basis in `vcfund.facts` (the demo fixture
  is `deal-2`, seeded with jurisdiction \"ATL\") must be rejected by the
  InvestmentCommitteeGovernor -- never invent a jurisdiction's
  fund-formation/exemption requirements."
  [db {:keys [subject]}]
  (let [d (store/deal db subject)
        iso3 (:jurisdiction d)
        sb (facts/spec-basis iso3)]
    (if (nil? sb)
      {:summary    (str iso3 " の公式spec-basisが見つかりません")
       :rationale  "vcfund.facts に未登録の法域。要件を推測で作らない。"
       :cites      []
       :effect     :assessment/set
       :value      {:jurisdiction iso3 :checklist [] :spec-basis nil}
       :stake      nil
       :confidence 0.9}
      {:summary    (str iso3 " (" (:owner-authority sb) ") 向けDDチェックリスト "
                        (count (:required-docs sb)) " 件を提案")
       :rationale  (str "公式ソース: " (:provenance sb) " / 法的根拠: " (:legal-basis sb))
       :cites      [(:legal-basis sb) (:provenance sb)]
       :effect     :assessment/set
       :value      {:jurisdiction iso3
                    :checklist (:required-docs sb)
                    :spec-basis (:provenance sb)
                    :legal-basis (:legal-basis sb)}
       :stake      nil
       :confidence 0.9})))

(def default-corporate-intel-screen
  "No-op corporate-intelligence cross-reference: always 'nothing on file'.
  This is the default so every existing caller of `screen-kyc`/`infer`/
  `mock-advisor` keeps its exact prior behavior unless it explicitly wires
  in `vcfund.corporate-intel/screen` (or an equivalent). Not required from
  this namespace directly -- keeping the dependency optional at the ddllm
  level, injected only by whoever builds the advisor."
  (constantly {:found? false :hit? false}))

(defn- screen-kyc
  "KYC / sanctions screening draft. `:sanctions-hit?` on the party record
  injects the failure mode: the InvestmentCommitteeGovernor must HOLD,
  un-overridably, on any sanctions/PEP hit. Missing identification yields
  low confidence -> escalate rather than auto-clear.

  `screen-fn` (party name -> corporate-intel result, see
  `vcfund.corporate-intel/screen`) is consulted ONLY once the local checks
  are otherwise clean -- it can turn a would-be :clear into :hit or
  :incomplete, but a local sanctions-hit or missing id-doc is decided
  first, cheaply, without depending on an external actor at all."
  [db {:keys [subject]} screen-fn]
  (let [p (store/party db subject)]
    (cond
      (nil? p)
      {:summary "対象partyが見つかりません" :rationale "no party record"
       :cites [] :effect :kyc/set :value {:party-id subject :verdict :unknown}
       :stake nil :confidence 0.0}

      (:sanctions-hit? p)
      {:summary    (str (:name p) ": 制裁/PEPリストと一致")
       :rationale  "スクリーニングが一致を検出。人手確認とホールドが必須。"
       :cites      [:sanctions-list]
       :effect     :kyc/set
       :value      {:party-id subject :verdict :hit}
       :stake      nil
       :confidence 0.95}

      (nil? (:id-doc p))
      {:summary    (str (:name p) ": 本人確認書類が未提出")
       :rationale  "本人確認書類が無いため確信度を上げられない。"
       :cites      [:id-doc]
       :effect     :kyc/set
       :value      {:party-id subject :verdict :incomplete}
       :stake      nil
       :confidence 0.4}

      :else
      (let [ci (screen-fn (:name p))]
        (cond
          (:hit? ci)
          {:summary    (str (:name p) ": corporate-intelligence 照会で制裁/PEPフラグを検出")
           :rationale  "cloud-itonami-isic-8291 の名前スクリーニングが一致を検出。人手確認とホールドが必須。"
           :cites      [:corporate-intelligence]
           :effect     :kyc/set
           :value      {:party-id subject :verdict :hit}
           :stake      nil
           :confidence 0.9}

          (:pending-human-review? ci)
          {:summary    (str (:name p) ": corporate-intelligence 照会が人手レビュー待ち")
           :rationale  "cloud-itonami-isic-8291 側の DisclosureGovernor が high-stakes escalate 中。確定するまでクリアにできない。"
           :cites      [:corporate-intelligence]
           :effect     :kyc/set
           :value      {:party-id subject :verdict :incomplete}
           :stake      nil
           :confidence 0.5}

          (:held? ci)
          {:summary    (str (:name p) ": corporate-intelligence 照会が拒否された(契約/設定の問題)")
           :rationale  (str "cloud-itonami-isic-8291 の DisclosureGovernor が本テナントの照会を拒否: " (pr-str (:reason ci)))
           :cites      [:corporate-intelligence]
           :effect     :kyc/set
           :value      {:party-id subject :verdict :incomplete}
           :stake      nil
           :confidence 0.4}

          :else
          {:summary    (str (:name p) ": 制裁リスト一致なし、本人確認書類あり")
           :rationale  "本人確認書類確認 + 制裁リスト非一致 + corporate-intelligence 照会クリア(または未収載)。"
           :cites      [:id-doc :sanctions-list :corporate-intelligence]
           :effect     :kyc/set
           :value      {:party-id subject :verdict :clear}
           :stake      nil
           :confidence 0.9})))))

(defn- propose-advance-stage
  "Draft a pipeline-stage advance -- moving a deal through the sourcing
  funnel (`vcfund.pipeline`) BEFORE any capital moves. The LLM only
  proposes the move; `vcfund.governor`'s `stage-transition-violations`
  independently re-validates it against `vcfund.pipeline/valid-
  transition?` (never trust the advisor's self-check alone). No capital
  moves here, so `:stake` is nil -- not actuation."
  [db {:keys [subject to-stage]}]
  (let [d (store/deal db subject)
        legal? (pipeline/valid-transition? (:status d) to-stage)]
    {:summary    (str subject ": " (:status d) " -> " to-stage
                      (when-not legal? " (不正な遷移)"))
     :rationale  (if legal?
                   "パイプライン上の前進移動"
                   "現在stageから許可されていない遷移")
     :cites      [(:status d)]
     :effect     :deal/advance-stage
     :value      {:deal-id subject :to-stage to-stage}
     :stake      nil
     :confidence (if legal? 0.9 0.2)}))

(defn- propose-term-sheet
  "Draft one round of term-sheet negotiation -- `:terms` (valuation,
  security type, pro-rata rights, board seat, liquidation-preference
  multiple, whatever the operator's template asks for) and `:proposed-by`
  (`:fund` or `:founder`) supplied by the caller from the actual
  negotiation, never invented here. When there's a prior round, the
  rationale includes the field-level redline
  (`vcfund.registry/term-sheet-diff`) against it, so a human reviewing the
  proposal sees exactly what changed, not just the new terms in isolation.
  `vcfund.governor`'s `term-sheet-after-commitment-violations`
  independently blocks proposing new terms once capital is already
  committed. No capital moves here, so `:stake` is nil -- not actuation."
  [db {:keys [subject proposed-by terms]}]
  (let [d (store/deal db subject)
        committed? (contains? #{:committed :exited} (:status d))
        history (store/term-sheet-history-of db subject)
        version (count history)
        prior-terms (when (seq history) (get (last history) "terms"))
        redline (when prior-terms (registry/term-sheet-diff prior-terms terms))]
    {:summary    (str subject " term sheet v" version " (" (name proposed-by) " 提案)"
                      (when committed? " (commit済み案件への提案は不可)"))
     :rationale  (cond
                   committed? "投資実行済みの案件への新規term sheet提案"
                   redline    (str "redline vs v" (dec version) ": " (pr-str redline))
                   :else      "pre-commitment交渉ラウンド (初回提案)")
     :cites      (vec (keys terms))
     :effect     :term-sheet/proposed
     :value      {:deal-id subject :proposed-by proposed-by :terms terms}
     :stake      nil
     :confidence (if committed? 0.2 0.9)}))

(defn- propose-term-sheet-signature
  "Draft one party's e-signature against a SPECIFIC term-sheet version --
  always the CURRENT latest version at signing time (the actor does not
  let a party sign a stale round; `vcfund.governor`'s `term-sheet-not-
  executed-violations` independently re-checks the LATEST version's
  signatures at commit time, never trusting this proposal's snapshot). No
  capital moves here, so `:stake` is nil -- not actuation."
  [db {:keys [subject signed-by]}]
  (let [history (store/term-sheet-history-of db subject)
        latest-version (when (seq history) (get (last history) "version"))]
    {:summary    (str subject " term sheet v" latest-version " へ " (name signed-by) " が署名"
                      (when (nil? latest-version) " (term sheetが存在しない)"))
     :rationale  (if latest-version
                   "現行バージョンへの署名"
                   "対象term sheetが無い")
     :cites      [latest-version]
     :effect     :term-sheet/signed
     :value      {:deal-id subject :version latest-version :signed-by signed-by}
     :stake      nil
     :confidence (if latest-version 0.9 0.0)}))

(defn- propose-portfolio-report
  "Draft a portfolio-monitoring KPI report for an already-committed deal --
  board-reporting-style facts (`:kpis`) supplied by the caller from the
  actual board deck/data room, never invented here. No capital moves here,
  so `:stake` is nil -- not actuation."
  [db {:keys [subject period kpis]}]
  (let [d (store/deal db subject)
        committed? (contains? #{:committed :exited} (:status d))]
    {:summary    (str subject " (" period ") のポートフォリオレポート提案"
                      (when-not committed? " (commit未実行)"))
     :rationale  (if committed?
                   "committed dealへの正規のKPIレポート"
                   "投資実行されていない案件へのレポートは不可")
     :cites      (vec (keys kpis))
     :effect     :portfolio/report-logged
     :value      {:deal-id subject :period period :kpis kpis}
     :stake      nil
     :confidence (if committed? 0.9 0.2)}))

(defn- propose-capital-call
  "Draft the capital-call notice action -- drawing committed capital IN
  from LPs, pro-rata by commitment share, to fund a deal. `:call-amount` is
  a REAL external fact supplied by the caller (the deal's actual funding
  need), never invented here; the pro-rata split itself is recomputed
  independently by the governor from store data, never trusted from this
  proposal. ALWAYS `:stake :actuation/call` -- a capital call is a
  REAL-WORLD act (LPs become contractually obligated to wire funds), never
  a draft the actor may auto-run. See README `Actuation`: no phase ever
  adds this op to a phase's `:auto` set (`vcfund.phase`); the governor also
  always escalates on `:actuation/call`. Two independent layers agree,
  deliberately."
  [db {:keys [subject call-amount notice-date]}]
  (let [lps (store/all-lps db)
        allocations (try (registry/capital-call-allocations lps call-amount)
                         (catch #?(:clj Exception :cljs :default) _ []))
        overcalled (filter :overcall? allocations)]
    {:summary    (str subject " 向け資金化のためLP " (count lps) "者へキャピタルコール "
                      call-amount " を発行提案"
                      (when (seq overcalled) (str " (" (count overcalled) "件がコミットメント超過)")))
     :rationale  (if (seq overcalled)
                   "一部LPの累計コール額がコミットメント額を超過"
                   "各LPのコミットメント比率に応じた按分配分")
     :cites      (mapv :id lps)
     :effect     :capital-call/mark-issued
     :value      {:jurisdiction (:jurisdiction (store/deal db subject))
                  :call-amount call-amount
                  :notice-date notice-date}
     :stake      :actuation/call
     :confidence (if (seq overcalled) 0.3 0.9)}))

(defn- propose-commit
  "Draft the actual investment-commitment action -- deploying real fund
  capital into a portfolio company. ALWAYS `:stake :actuation/deploy` --
  this is a REAL-WORLD act (a term sheet is signed, wires go out), never a
  draft the actor may auto-run. See README `Actuation`: no phase ever adds
  this op to a phase's `:auto` set (`vcfund.phase`); the governor also
  always escalates on `:actuation/deploy`. Two independent layers agree,
  deliberately."
  [db {:keys [subject]}]
  (let [d (store/deal db subject)
        assessment (store/assessment-of db subject)
        docs-ok? (and assessment (facts/required-docs-satisfied?
                                  (:jurisdiction d)
                                  (:checklist assessment)))]
    {:summary    (str (:portfolio-company d) " (" (:jurisdiction d)
                      ") への投資実行準備ができました" (when-not docs-ok? " (DD書類未充足)"))
     :rationale  (if assessment
                   (str "spec-basis: " (:spec-basis assessment))
                   "DD assessment未実施")
     :cites      (if assessment [(:spec-basis assessment)] [])
     :effect     :investment/mark-committed
     :value      {:deal-id subject}
     :stake      :actuation/deploy
     :confidence (if docs-ok? 0.9 0.3)}))

(defn- propose-board-seat
  "Draft a board-seat/governance-rights administration event -- granting
  or revoking a board seat/observer right for an already-committed deal.
  `:seat-holder`/`:seat-type`/`:event`/`:effective-date` are REAL facts
  about the negotiated governance right supplied by the caller (the term
  sheet/investment agreement is the actual legal grant -- see
  `propose-term-sheet`), never invented here. No capital moves here, so
  `:stake` is nil -- not actuation."
  [db {:keys [subject seat-holder seat-type event effective-date]}]
  (let [d (store/deal db subject)
        committed? (contains? #{:committed :exited} (:status d))]
    {:summary    (str subject ": " seat-holder " へのboard seat " (name event) " (" (name seat-type) ")"
                      (when-not committed? " (commit未実行)"))
     :rationale  (if committed?
                   "committed dealへの正規のガバナンス権利管理"
                   "投資実行されていない案件へのboard seat操作は不可")
     :cites      [(name event) (name seat-type)]
     :effect     :governance/board-seat-recorded
     :value      {:deal-id subject :seat-holder seat-holder :seat-type seat-type
                  :event event :effective-date effective-date}
     :stake      nil
     :confidence (if committed? 0.9 0.2)}))

(defn- propose-follow-on
  "Draft a FOLLOW-ON investment-commitment action -- deploying ADDITIONAL
  real fund capital into a portfolio company the fund ALREADY holds an
  initial commitment in (a later round, exercising pro-rata or otherwise).
  `:security-type`/`:amount` are REAL external facts about the new round
  supplied by the caller, never invented here. ALWAYS `:stake
  :actuation/deploy` -- same direction of capital travel as an initial
  commitment (deploying INTO a portfolio company), so it reuses that
  stake rather than adding a new one; a REAL-WORLD act, never a draft the
  actor may auto-run. See README `Actuation`."
  [db {:keys [subject security-type amount]}]
  (let [d (store/deal db subject)
        original (store/commitment-of db subject)
        committed? (= :committed (:status d))]
    {:summary    (str (:portfolio-company d) " へのfollow-on投資 " amount " を提案"
                      (when-not committed? " (既存commitmentが無い、または既にexit済み)"))
     :rationale  (if committed?
                   (str "既存commitment record_id: " (get original "record_id"))
                   "既存commitmentが無い、または既にexit済みの案件へのfollow-on提案")
     :cites      (if committed? [(get original "record_id")] [])
     :effect     :investment/follow-on-committed
     :value      {:deal-id subject :security-type security-type :amount amount}
     :stake      :actuation/deploy
     :confidence (if committed? 0.9 0.2)}))

(defn- propose-clawback-repayment
  "Draft a GP-clawback repayment action -- the GP actually returning
  capital to the fund that `vcfund.waterfall/whole-fund-waterfall-report`
  determines they were paid, deal-by-deal, in excess of the fund's
  aggregate (whole-fund) entitlement. `:amount`/`:effective-date`/
  `:fund-life-years` are REAL external facts supplied by the caller (an
  actual GP repayment instruction and how long the fund has been alive),
  never invented here; `vcfund.governor`'s `clawback-exceeds-entitlement-
  violations` independently recomputes the whole-fund waterfall and
  rejects any requested amount above the computed `:gp-clawback`, never
  trusting this proposal's figure alone. ALWAYS `:stake
  :actuation/clawback` -- the one direction of capital travel that flows
  FROM the GP INTO the fund, the mirror image of every other actuation
  here."
  [db {:keys [amount effective-date fund-life-years]}]
  (let [{:keys [gp-clawback] :as wf} (waterfall/whole-fund-waterfall-report db fund-life-years)
        exceeds? (> (double amount) (+ (double gp-clawback) 1e-6))]
    {:summary    (str "GP clawback返金 " amount " を提案 (算定額 " gp-clawback ")"
                      (when exceeds? " (算定clawback額を超過)"))
     :rationale  (if exceeds?
                   "要求額が独立算定したwhole-fund clawback額を超過"
                   (str "whole-fund waterfall再計算: " (pr-str wf)))
     :cites      [:whole-fund-waterfall]
     :effect     :waterfall/clawback-repaid
     :value      {:amount amount :effective-date effective-date}
     :stake      :actuation/clawback
     :confidence (if exceeds? 0.2 0.9)}))

(defn- propose-distribute
  "Draft the exit-distribution action -- returning real proceeds to LPs.
  `:exit-proceeds`/`:holding-period-years` are REAL external facts supplied
  by the caller (an actual signed closing statement), never invented here.
  ALWAYS `:stake :actuation/distribute`."
  [db {:keys [subject exit-proceeds holding-period-years]}]
  (let [c (store/commitment-of db subject)]
    {:summary    (str subject " のexit分配提案 (proceeds=" exit-proceeds ")"
                      (when-not c " (commitmentレコード無し)"))
     :rationale  (if c
                   (str "commitment record_id: " (get c "record_id"))
                   "対応するinvestment commitmentが無い")
     :cites      (if c [(get c "record_id")] [])
     :effect     :distribution/mark-paid
     :value      {:deal-id subject
                  :exit-proceeds exit-proceeds
                  :holding-period-years holding-period-years}
     :stake      :actuation/distribute
     :confidence (if c 0.9 0.2)}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}
  `screen-fn` (default: `default-corporate-intel-screen`, a no-op) is only
  consulted by `:kyc/screen`, once local checks are otherwise clean."
  ([db request] (infer db request default-corporate-intel-screen))
  ([db {:keys [op] :as request} screen-fn]
   (case op
    :lp/intake          (normalize-lp-intake db request)
    :dd/assess          (assess-dd db request)
    :kyc/screen         (screen-kyc db request screen-fn)
    :deal/advance-stage (propose-advance-stage db request)
    :term-sheet/propose (propose-term-sheet db request)
    :term-sheet/sign    (propose-term-sheet-signature db request)
    :capital-call/issue (propose-capital-call db request)
    :investment/commit  (propose-commit db request)
    :investment/follow-on (propose-follow-on db request)
    :portfolio/report   (propose-portfolio-report db request)
    :governance/board-seat (propose-board-seat db request)
    :exit/distribute    (propose-distribute db request)
    :waterfall/clawback-repay (propose-clawback-repayment db request)
    {:summary "未対応の操作" :rationale (str op) :cites []
     :effect :noop :stake nil :confidence 0.0})))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere.
  opts:
    :corporate-intel-screen -- party name -> corporate-intel result (see
      `vcfund.corporate-intel/screen`). Default: no-op (never changes a
      screen-kyc verdict), so `(mock-advisor)` with no args keeps every
      existing caller's exact prior behavior."
  ([] (mock-advisor {}))
  ([{:keys [corporate-intel-screen]
     :or   {corporate-intel-screen default-corporate-intel-screen}}]
   (reify Advisor (-advise [_ st req] (infer st req corporate-intel-screen)))))

(def ^:private system-prompt
  (str "あなたはベンチャーキャピタルファンドのDDエージェントの助言者です。与えられた事実のみに"
       "基づき、提案を1つだけEDNマップで返します。説明や前置きは一切書かず、"
       "EDNだけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) "
       ":effect(:lp/upsert|:assessment/set|:kyc/set|:deal/advance-stage|"
       ":term-sheet/proposed|:term-sheet/signed|:capital-call/mark-issued|"
       ":investment/mark-committed|:investment/follow-on-committed|"
       ":portfolio/report-logged|:governance/board-seat-recorded|"
       ":distribution/mark-paid|:waterfall/clawback-repaid) "
       ":stake(:actuation/call|:actuation/deploy|:actuation/distribute|"
       ":actuation/clawback|nil) :confidence(0..1)。\n"
       "重要: 登録されていない法域のfund-formation/exemption要件を絶対に創作してはいけません。"
       "spec-basisが無い場合は :cites を空にし confidence を上げないこと。"))

(defn- facts-for [st {:keys [op subject]}]
  (case op
    :dd/assess          {:deal (store/deal st subject)}
    :kyc/screen         {:party (store/party st subject)}
    :deal/advance-stage {:deal (store/deal st subject)}
    :term-sheet/propose {:deal (store/deal st subject)
                        :term-sheet-history (store/term-sheet-history-of st subject)}
    :term-sheet/sign    {:term-sheet-history (store/term-sheet-history-of st subject)
                        :signature-history (store/signature-history-of st subject)}
    :capital-call/issue {:deal (store/deal st subject) :lps (store/all-lps st)}
    :investment/commit  {:deal (store/deal st subject)
                         :assessment (store/assessment-of st subject)}
    :investment/follow-on {:deal (store/deal st subject)
                           :commitment (store/commitment-of st subject)}
    :portfolio/report   {:deal (store/deal st subject)}
    :governance/board-seat {:deal (store/deal st subject)
                            :board-seat-history (store/board-seat-history-of st subject)}
    :exit/distribute    {:deal (store/deal st subject)
                         :commitment (store/commitment-of st subject)}
    :waterfall/clawback-repay {:commitment-history (store/commitment-history st)
                               :distribution-history (store/distribution-history st)}
    {:deal (store/deal st subject)}))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure yields
  a safe low-confidence noop so the InvestmentCommitteeGovernor
  escalates/holds -- an LLM hiccup can never auto-commit or auto-distribute
  real capital."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :noop :stake nil :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :ddllm-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
