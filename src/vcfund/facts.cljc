(ns vcfund.facts
  "Per-jurisdiction venture-fund formation/exemption-regime catalog -- the
  G2-style spec-basis table the InvestmentCommitteeGovernor checks every
  `:dd/assess` proposal against ('did the advisor cite an OFFICIAL public
  source for this jurisdiction's fund-formation/exemption requirements, or
  did it invent one?').

  Coverage is reported HONESTLY (see `coverage`), the same discipline
  `cloud-itonami-isic-6511`'s `underwriting.facts` uses: a jurisdiction not
  in this table has NO spec-basis, full stop -- the advisor must not
  fabricate one, and the governor holds if it tries.

  Seed values are drawn from each jurisdiction's official securities/fund
  regulator (see `:provenance`); they are a STARTING catalog, not a
  from-scratch survey of all ~194 jurisdictions. Extending coverage is
  additive: add one map to `catalog`, cite a real source, done -- never
  invent a jurisdiction's requirements to make coverage look bigger.

  The four seed jurisdictions (JPN, USA, GBR, DEU) intentionally match
  `underwriting.facts`'s seed set, for fleet-wide consistency.")

(def catalog
  "iso3 -> requirement map. `:required-docs` mirrors the generic LP-
  subscription + DD checklist a venture fund asks for in some form;
  `:legal-basis` / `:owner-authority` / `:provenance` are the G2 citation
  the governor requires before any `:dd/assess` proposal can commit."
  {"JPN" {:name "Japan"
          :owner-authority "金融庁 (Financial Services Agency)"
          :legal-basis "金融商品取引法 第63条 (適格機関投資家等特例業務, FIEA Article 63 QII special business)"
          :national-spec "投資事業有限責任組合契約に関する法律 (Limited Partnership Act for Investment)"
          :provenance "https://www.fsa.go.jp/"
          :required-docs ["特例業務届出書 (Article 63 notification filing)"
                          "適格機関投資家該当性確認書 (qualified institutional investor eligibility confirmation)"
                          "投資事業有限責任組合契約書 (LP subscription/limited partnership agreement)"
                          "本人確認書類 (KYC identification)"]}
   "USA" {:name "United States"
          :owner-authority "U.S. Securities and Exchange Commission (SEC)"
          :legal-basis "Regulation D Rule 506(b)/506(c) (17 CFR §230.506); Investment Company Act of 1940 §3(c)(1)/§3(c)(7)"
          :national-spec "ILPA Due Diligence Questionnaire (DDQ) + Reporting Template; NVCA model financing documents"
          :provenance "https://www.sec.gov/ ; https://ilpa.org/ ; https://nvca.org/"
          :required-docs ["Subscription agreement"
                          "Accredited investor / qualified purchaser questionnaire (Reg D 506(b)/(c) or §3(c)(7))"
                          "ILPA Due Diligence Questionnaire (DDQ) response"
                          "Limited Partnership Agreement (NVCA model or equivalent)"]}
   "GBR" {:name "United Kingdom"
          :owner-authority "Financial Conduct Authority (FCA)"
          :legal-basis "Alternative Investment Fund Managers Regulations 2013 (sub-threshold AIFM regime)"
          :national-spec "National Private Placement Regime (NPPR) marketing notification"
          :provenance "https://www.fca.org.uk/"
          :required-docs ["Subscription agreement"
                          "FCA sub-threshold AIFM registration confirmation"
                          "National Private Placement Regime (NPPR) marketing notification"
                          "Investor categorisation (professional/institutional) record"]}
   "DEU" {:name "Germany"
          :owner-authority "Bundesanstalt für Finanzdienstleistungsaufsicht (BaFin)"
          :legal-basis "Kapitalanlagegesetzbuch (KAGB) §2 Abs. 4a/5 (sub-threshold AIFM registration); EuVECA Regulation (EU) 2017/1991 (opt-in)"
          :national-spec "KAGB-Schwellenwert-Registrierung / EuVECA-Bescheinigung"
          :provenance "https://www.bafin.de/"
          :required-docs ["Zeichnungsschein (subscription form)"
                          "KAGB-Schwellenwert-Registrierung oder EuVECA-Bescheinigung (sub-threshold registration or EuVECA certificate)"
                          "Anlegerklassifizierung (investor classification record)"
                          "Kommanditgesellschaftsvertrag (limited partnership agreement, German KG structure)"]}})

(defn spec-basis
  "The jurisdiction's requirement map, or nil -- nil means NO spec-basis,
  and the governor must hold any proposal that tries to commit capital on it."
  [iso3]
  (get catalog iso3))

(defn coverage
  "Honest coverage report: how many of the requested jurisdictions actually
  have a spec-basis entry. Never report a missing jurisdiction as covered."
  ([] (coverage (keys catalog)))
  ([iso3s]
   (let [have (filter catalog iso3s)
         missing (remove catalog iso3s)]
     {:requested (count iso3s)
      :covered (count have)
      :covered-jurisdictions (vec (sort have))
      :missing-jurisdictions (vec (sort missing))
      :note (str "cloud-itonami-vc-fund R0: " (count catalog)
                 " jurisdictions seeded with an official spec-basis. "
                 "This is a starting catalog, not a survey of all ~194 "
                 "jurisdictions -- extend `vcfund.facts/catalog`, "
                 "never fabricate a jurisdiction's requirements.")})))

(defn required-docs-satisfied?
  "Does `submitted` (a set/coll of doc keywords or strings) satisfy every
  required doc listed for `iso3`? Missing spec-basis -> never satisfied."
  [iso3 submitted]
  (when-let [{:keys [required-docs]} (spec-basis iso3)]
    (let [need (count required-docs)
          have (count (filter (set submitted) required-docs))]
      (= need have))))

(defn doc-checklist [iso3]
  (:required-docs (spec-basis iso3) []))
