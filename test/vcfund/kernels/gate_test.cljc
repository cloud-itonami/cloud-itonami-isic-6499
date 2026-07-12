(ns vcfund.kernels.gate-test
  "The safety kernel's executable spec, four ways:

  1. battery lock — the kernel's own in-subset battery must pass
     case-for-case (`battery-case-count` == `(battery-pass-count)`),
     so a silently dropped case can't survive review.
  2. parity matrix — the kernel's phase core is compared against an
     independent reference copy of the ORIGINAL set-based cond logic
     over the FULL input space (all phases incl. out-of-range, all op
     codes incl. unknown, all governor dispositions). The façade
     delegates, so this is the guard that delegation didn't change
     semantics.
  3. governor boundary — the confidence floor boundary and the
     fail-closed treatment of out-of-range confidence, exercised
     through the real `vcfund.governor/check` façade.
  4. clawback threshold — the scaled-integer (micro-unit) comparison
     that replaced the old inline 1e-6 float epsilon, exercised both
     directly and through the real façade against a real store."
  (:require [clojure.test :refer [deftest is testing]]
            [vcfund.governor :as governor]
            [vcfund.kernels.gate :as gate]
            [vcfund.store :as store]))

(deftest battery-lock
  (is (= gate/battery-case-count (gate/battery-pass-count))
      "every battery case must pass; update battery-case-count only when adding cases"))

(deftest confidence-floor-pinned-to-facade-constant
  (is (= gate/confidence-floor-x100
         (Math/round (* 100.0 governor/confidence-floor)))
      "the façade's documented 0.6 and the kernel's deciding 60 must not drift"))

;; ---------------------------------------------------------------
;; Independent oracle for the parity matrix: the pre-kernel phase
;; logic (sets + cond) restated over wire codes, PLUS the kernel's
;; fail-closed contract for out-of-range phases (no writes at all).
;; The original façade normalized an unknown phase to default-phase 3
;; BEFORE this logic and still does — so out-of-range rows here pin
;; the kernel's own contract, not a façade behavior change.

(def ^:private ref-read-ops #{0})
(def ^:private ref-phases
  {0 {:writes #{}                             :auto #{}}
   1 {:writes #{1}                            :auto #{}}
   2 {:writes #{1 2 3}                        :auto #{}}
   3 {:writes #{1 2 3 4 5 6 7 8 9 10 11 12 13} :auto #{1 4 5 6 10 13}}})

(defn- ref-gate [phase op gov]
  (let [{:keys [writes auto]} (get ref-phases phase {:writes #{} :auto #{}})]
    (cond
      (= gov 2)                        {:d 2 :r 0}
      (contains? ref-read-ops op)      {:d gov :r 0}
      (not (contains? writes op))      {:d 2 :r 1}
      (and (= gov 0)
           (not (contains? auto op)))  {:d 1 :r 2}
      :else                            {:d gov :r 0})))

(deftest phase-parity-matrix
  (testing "kernel == reference over the full input space (432 combos)"
    (doseq [phase [-1 0 1 2 3 4 7 100 -99]
            op    [0 1 2 3 4 5 6 7 8 9 10 11 12 13 14 99]
            gov   [0 1 2]]
      (let [expected (ref-gate phase op gov)]
        (is (= (:d expected) (gate/phase-disposition phase op gov))
            (str "disposition mismatch at phase=" phase " op=" op " gov=" gov))
        (is (= (:r expected) (gate/phase-reason phase op gov))
            (str "reason mismatch at phase=" phase " op=" op " gov=" gov))))))

(deftest capital-movement-ops-auto-enabled-nowhere
  (testing "ops 7/8/9/11/12 (:capital-call/issue, :investment/commit,
            :investment/follow-on, :exit/distribute,
            :waterfall/clawback-repay) are auto-enabled at NO phase —
            kernel restates the phase table's permanent structural
            invariant for all FOUR directions of capital movement"
    (doseq [phase [-1 0 1 2 3 4 7]
            op    [7 8 9 11 12]]
      (is (= 0 (gate/op-auto-enabled phase op))
          (str "op " op " must never be auto at phase " phase)))))

;; ---------------------------------------------------------------
;; Governor boundary through the real façade. op :lp/intake touches
;; neither the store nor any of the fifteen hard checks, so the
;; verdict is decided purely by confidence/actuation — nil store is
;; safe.

(defn- verdict [proposal]
  (governor/check {:op :lp/intake :subject "lp-x"} {} proposal nil))

(deftest confidence-floor-boundary
  (testing "0.59 escalates, 0.60 clears (kernel decides at integer x100)"
    (is (true?  (:escalate? (verdict {:confidence 0.59}))))
    (is (false? (:ok? (verdict {:confidence 0.59}))))
    (is (true?  (:ok? (verdict {:confidence 0.6}))))
    (is (false? (:escalate? (verdict {:confidence 0.6}))))))

(deftest out-of-range-confidence-fails-closed
  (testing "an advisor reporting impossible confidence gets MORE scrutiny,
            not auto-commit (kernel is deliberately stricter than the old
            inline `(< conf floor)` here)"
    (is (true? (:escalate? (verdict {:confidence 1.5}))))
    (is (false? (:ok? (verdict {:confidence 1.5}))))
    (is (true? (:escalate? (verdict {:confidence -0.2}))))))

(deftest actuation-still-escalates-and-hard-still-wins
  (is (true? (:escalate? (verdict {:confidence 0.99 :stake :actuation/deploy}))))
  (testing "a hard violation dominates actuation escalation"
    (let [v (governor/check {:op :dd/assess :subject "deal-x"} {}
                            {:confidence 0.99 :stake :actuation/deploy :cites []} nil)]
      (is (true? (:hard? v)))
      (is (false? (:escalate? v)))
      (is (some #{:no-spec-basis} (mapv :rule (:violations v)))))))

;; ---------------------------------------------------------------
;; Clawback threshold: the kernel's strict micro-unit comparison,
;; direct boundary first, then through the real façade against a real
;; (fresh) store — a fund that has paid zero GP carry owes zero
;; clawback, so repaying exactly 0 is legitimate and any positive
;; repayment is a HARD hold.

(deftest clawback-exceeds-micro-unit-boundary
  (is (= 0 (gate/clawback-exceeds 1000000 1000000)) "exactly the entitlement is legitimate")
  (is (= 1 (gate/clawback-exceeds 1000001 1000000)) "one micro-unit over is a violation")
  (is (= 0 (gate/clawback-exceeds 999999 1000000)))
  (is (= 0 (gate/clawback-exceeds 0 0))))

(deftest clawback-threshold-decided-in-kernel-through-facade
  (let [st (store/seed-db)
        v (fn [amount]
            (governor/check {:op :waterfall/clawback-repay :subject "fund"
                             :amount amount :fund-life-years 3}
                            {} {:confidence 0.9 :stake :actuation/clawback} st))]
    (testing "repaying exactly the (zero) entitlement is not a violation, but actuation still escalates"
      (is (false? (:hard? (v 0))))
      (is (true? (:escalate? (v 0)))))
    (testing "any positive repayment exceeds a zero entitlement -> HARD hold"
      (is (true? (:hard? (v 1))))
      (is (some #{:clawback-exceeds-entitlement} (mapv :rule (:violations (v 1))))))))
