(ns wasm.clawback-entitlement-test
  "Hosts wasm/clawback_entitlement.wasm (compiled from
  wasm/clawback_entitlement.kotoba, see wasm/README.md) via
  kototama.tender -- proves vcfund.governor's clawback-exceeds-entitlement
  check (`clawback-exceeds-entitlement-violations` in
  src/vcfund/governor.cljc, backed by the pure kernel comparison
  `vcfund.kernels.gate/clawback-exceeds`) runs as a real WASM guest, not
  just as JVM Clojure.

  ABI: main is 0-arity (kotoba wasm emit rejects a parameterized main --
  :main-arity); the two real i32 inputs are written into the guest's
  exported linear memory at fixed offsets before calling main() -- see
  wasm/clawback_entitlement.kotoba's ns docstring for the offset layout."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [kototama.contract :as contract]
            [kototama.tender :as tender]))

(defn- wasm-bytes []
  (.readAllBytes (io/input-stream (io/file "wasm/clawback_entitlement.wasm"))))

(defn- run-clawback-within-entitlement? [amount-cents entitlement-cents]
  (let [instance (tender/instantiate (wasm-bytes) [] (contract/host-caps {}))
        memory (.memory instance)]
    (.writeI32 memory 0 amount-cents)
    (.writeI32 memory 4 entitlement-cents)
    (tender/call-main instance)))

(deftest clawback-wasm-approves-well-within-entitlement
  (testing "requested repayment well below the recomputed entitlement -> within entitlement"
    (is (= 1 (run-clawback-within-entitlement? 200000 1000000)))))

(deftest clawback-wasm-rejects-exceeding-entitlement
  (testing "requested repayment above the recomputed entitlement -> exceeds entitlement"
    (is (= 0 (run-clawback-within-entitlement? 1500000 1000000)))))

(deftest clawback-wasm-approves-exact-boundary
  (testing "requested repayment exactly equal to the entitlement -> within entitlement (<=, strict-exceeds-only per gate/clawback-exceeds)"
    (is (= 1 (run-clawback-within-entitlement? 1000000 1000000)))))

(deftest clawback-wasm-rejects-one-cent-over
  (testing "requested repayment one cent above the entitlement -> exceeds entitlement (boundary +1)"
    (is (= 0 (run-clawback-within-entitlement? 1000001 1000000)))))
