(ns kotobase.storage.contract
  "Reusable synchronous conformance suite for storage providers."
  (:require [kotobase.storage.core :as storage]))

(defn verify
  "Run the shared backend contract. CHECK is `(fn [truthy label])`."
  [backend check]
  (storage/validate-backend! backend)
  (let [a #?(:clj (byte-array [1 2 3]) :cljs (js/Uint8Array. #js [1 2 3]))
        b #?(:clj (byte-array [4 5]) :cljs (js/Uint8Array. #js [4 5]))]
    (storage/-put-blocks! backend
                          [{:cid "cid-a" :bytes a}
                           {:cid "cid-b" :bytes b}])
    (storage/-put-blocks! backend [{:cid "cid-a" :bytes a}])
    (let [found (storage/-get-blocks backend
                                     ["cid-a" "cid-b" "missing"])]
      (check (= #{"cid-a" "cid-b"} (set (keys found)))
             "batch get returns present blocks and omits misses")
      (check (= [1 2 3] (vec (get found "cid-a")))
             "block bytes round-trip"))
    (check (nil? (storage/-read-ref backend "main"))
           "missing ref is nil")
    (check (:published?
            (storage/-compare-and-set-ref!
             backend "main" nil "cid-a"))
           "genesis CAS succeeds")
    (let [lost (storage/-compare-and-set-ref!
                backend "main" nil "cid-b")]
      (check (false? (:published? lost)) "stale CAS loses")
      (check (= "cid-a" (:current lost)) "CAS loser observes winner"))
    (check (:published?
            (storage/-compare-and-set-ref!
             backend "main" "cid-a" "cid-b"))
           "current-head CAS succeeds")
    (check (= "cid-b" (:cid (storage/-read-ref backend "main")))
           "published ref resolves")))
