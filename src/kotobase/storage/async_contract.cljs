(ns kotobase.storage.async-contract
  "Reusable Promise-based conformance suite for Worker storage providers."
  (:require [kotobase.storage.core :as storage]))

(defn verify [backend]
  (storage/validate-backend! backend)
  (let [checks (atom 0)
        check (fn [ok? message]
                (swap! checks inc)
                (when-not ok? (throw (js/Error. message))))
        a (js/Uint8Array. #js [1 2 3])
        b (js/Uint8Array. #js [4 5])]
    (-> (storage/-put-blocks! backend
                              [{:cid "cid-a" :bytes a}
                               {:cid "cid-b" :bytes b}])
        (.then (fn [_]
                 (storage/-put-blocks! backend [{:cid "cid-a" :bytes a}])))
        (.then (fn [_]
                 (storage/-get-blocks backend ["cid-a" "cid-b" "missing"])))
        (.then
         (fn [found]
           (check (= #{"cid-a" "cid-b"} (set (keys found)))
                  "batch get mismatch")
           (check (= [1 2 3] (vec (get found "cid-a")))
                  "bytes mismatch")
           (storage/-read-ref backend "main")))
        (.then
         (fn [missing]
           (check (nil? missing) "missing ref must be nil")
           (storage/-compare-and-set-ref! backend "main" nil "cid-a")))
        (.then
         (fn [created]
           (check (:published? created) "genesis CAS must win")
           (storage/-compare-and-set-ref! backend "main" nil "cid-b")))
        (.then
         (fn [lost]
           (check (false? (:published? lost)) "stale CAS must lose")
           (check (= "cid-a" (:current lost)) "loser must observe winner")
           (storage/-compare-and-set-ref! backend "main" "cid-a" "cid-b")))
        (.then
         (fn [updated]
           (check (:published? updated) "current CAS must win")
           (storage/-read-ref backend "main")))
        (.then
         (fn [current]
           (check (= "cid-b" (:cid current)) "ref did not update")
           {:checks @checks})))))
