(ns vcfund.facts-test
  (:require [clojure.test :refer [deftest is]]
            [vcfund.facts :as facts]))

(deftest usa-has-a-spec-basis
  (is (some? (facts/spec-basis "USA")))
  (is (string? (:provenance (facts/spec-basis "USA")))))

(deftest unknown-jurisdiction-has-no-fabricated-spec-basis
  (is (nil? (facts/spec-basis "ATL"))))

(deftest coverage-never-reports-a-missing-jurisdiction-as-covered
  (let [report (facts/coverage ["USA" "ATL" "GBR"])]
    (is (= 2 (:covered report)))
    (is (= ["ATL"] (:missing-jurisdictions report)))
    (is (= ["GBR" "USA"] (:covered-jurisdictions report)))))

(deftest required-docs-satisfied-needs-every-doc
  (let [all (facts/doc-checklist "USA")]
    (is (facts/required-docs-satisfied? "USA" all))
    (is (not (facts/required-docs-satisfied? "USA" (rest all))))
    (is (not (facts/required-docs-satisfied? "ATL" all)) "no spec-basis -> never satisfied")))
