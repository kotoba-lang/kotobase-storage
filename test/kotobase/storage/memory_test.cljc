(ns kotobase.storage.memory-test
  (:require [clojure.test :refer [deftest is]]
            [kotobase.storage.contract :as contract]
            [kotobase.storage.memory :as memory]))

(deftest reference-backend-satisfies-contract
  (contract/verify (memory/memory-store)
                   (fn [ok? label] (is ok? label))))

