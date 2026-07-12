(ns vcfund.pipeline-test
  (:require [clojure.test :refer [deftest is testing]]
            [vcfund.pipeline :as pipeline]))

(deftest forward-moves-within-the-funnel-are-legal
  (testing "any later funnel stage is reachable, skips allowed"
    (is (pipeline/valid-transition? :sourced :screening))
    (is (pipeline/valid-transition? :sourced :ic-review) "skipping stages is allowed")
    (is (pipeline/valid-transition? :screening :dd))))

(deftest backward-moves-are-illegal
  (is (not (pipeline/valid-transition? :dd :screening)))
  (is (not (pipeline/valid-transition? :ic-review :sourced)))
  (is (not (pipeline/valid-transition? :sourced :sourced)) "no-op move is not strictly forward"))

(deftest passed-is-reachable-from-any-pre-commitment-stage-only
  (is (pipeline/valid-transition? :sourced :passed))
  (is (pipeline/valid-transition? :ic-review :passed))
  (is (not (pipeline/valid-transition? :committed :passed)) "can't pass on a deal already committed")
  (is (not (pipeline/valid-transition? :exited :passed)))
  (is (not (pipeline/valid-transition? :passed :passed)) "passed is terminal"))

(deftest capital-stages-are-never-reachable-via-advance-stage
  (testing "only the real capital-moving ops may set :committed/:exited"
    (is (not (pipeline/valid-transition? :ic-review :committed)))
    (is (not (pipeline/valid-transition? :sourced :committed)))
    (is (not (pipeline/valid-transition? :committed :exited)))))

(deftest at-least-checks-funnel-depth
  (is (pipeline/at-least? :ic-review :ic-review))
  (is (pipeline/at-least? :ic-review :sourced))
  (is (not (pipeline/at-least? :sourced :ic-review)))
  (is (not (pipeline/at-least? :committed :ic-review))
      "capital stages aren't funnel stages at all, so at-least? is false, not vacuously true"))
