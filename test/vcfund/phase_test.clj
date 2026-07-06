(ns vcfund.phase-test
  "The phase table as executable tests. The invariant this repo cannot
  regress on: none of `:capital-call/issue`, `:investment/commit` or
  `:exit/distribute` may ever be a member of any phase's `:auto` set."
  (:require [clojure.test :refer [deftest is testing]]
            [vcfund.phase :as phase]))

(deftest capital-call-issue-never-auto-at-any-phase
  (testing "structural invariant: no phase auto-issues a capital call to LPs"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :capital-call/issue))
          (str "phase " n " must not auto-commit :capital-call/issue")))))

(deftest investment-commit-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in the future entries, auto-commits real capital deployment"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :investment/commit))
          (str "phase " n " must not auto-commit :investment/commit")))))

(deftest exit-distribute-never-auto-at-any-phase
  (testing "structural invariant: no phase auto-commits an exit distribution to LPs"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :exit/distribute))
          (str "phase " n " must not auto-commit :exit/distribute")))))

(deftest phase-0-is-fully-read-only
  (is (empty? (:writes (get phase/phases 0)))))

(deftest phase-3-auto-commits-only-lp-intake
  (is (= #{:lp/intake} (:auto (get phase/phases 3)))))

(deftest gate-hold-always-wins
  (is (= :hold (:disposition (phase/gate 3 {:op :lp/intake} :hold)))))

(deftest gate-escalates-a-clean-non-auto-write
  (is (= :escalate (:disposition (phase/gate 3 {:op :capital-call/issue} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :investment/commit} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :exit/distribute} :commit)))))

(deftest gate-holds-a-write-disabled-in-this-phase
  (is (= :hold (:disposition (phase/gate 0 {:op :lp/intake} :commit)))))
