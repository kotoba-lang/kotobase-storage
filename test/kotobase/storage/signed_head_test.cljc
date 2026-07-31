(ns kotobase.storage.signed-head-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotobase.storage.core :as storage]
            [kotobase.storage.signed-head :as sh]))

;; A deliberately dumb pointer: unconditional overwrite, exactly what B2 or an
;; IPNS publish gives. If the design needs more than this, it has failed.
(defn- pointer []
  (let [state (atom {})]
    {:read-head! (fn [n] (get @state n))
     :write-head! (fn [n head] (swap! state assoc n head))
     :state state}))

(defn- signer [key]
  {:sign-fn (fn [record] [key (hash record)])
   :verify-fn (fn [record sig issuer]
                (and (= sig [key (hash record)]) (= issuer key)))})

(defn- store [key]
  (let [p (pointer)
        s (signer key)]
    (assoc (merge p s)
           :store (sh/open (assoc (select-keys p [:read-head! :write-head!])
                                  :sign-fn (:sign-fn s)
                                  :verify-fn (:verify-fn s)
                                  :issuer key)))))

(deftest publishes-and-reads-back
  (let [{:keys [store]} (store "alice")]
    (is (nil? (storage/-read-ref store "main")) "absent ref is nil")
    (is (:published? (storage/-compare-and-set-ref! store "main" nil "cid-a")))
    (is (= "cid-a" (:cid (storage/-read-ref store "main"))))
    (is (:published? (storage/-compare-and-set-ref! store "main" "cid-a" "cid-b")))
    (is (= "cid-b" (:cid (storage/-read-ref store "main"))))))

(deftest a-stale-publish-loses-and-sees-the-winner
  (let [{:keys [store]} (store "alice")]
    (storage/-compare-and-set-ref! store "main" nil "cid-a")
    (let [lost (storage/-compare-and-set-ref! store "main" nil "cid-b")]
      (is (false? (:published? lost)))
      (is (= "cid-a" (:current lost))))))

(deftest the-sequence-advances-by-one
  (let [{:keys [store]} (store "alice")]
    (storage/-compare-and-set-ref! store "main" nil "cid-a")
    (is (= 0 (:version (storage/-read-ref store "main"))))
    (storage/-compare-and-set-ref! store "main" "cid-a" "cid-b")
    (is (= 1 (:version (storage/-read-ref store "main"))))))

(deftest an-unverifiable-head-reads-as-absent
  (testing "an untrusted host can serve rubbish; the reader's job is not to
            believe it, and a forged head must not read as a valid ref"
    (let [{:keys [store state]} (store "alice")]
      (storage/-compare-and-set-ref! store "main" nil "cid-a")
      (swap! state update "main" assoc "cid" "cid-forged")
      (is (nil? (storage/-read-ref store "main"))))))

(deftest a-head-from-another-issuer-reads-as-absent
  ;; `mallory` is built FIRST: destructuring :store shadows the `store` fn, so
  ;; building it after would call a record as a function.
  (let [mallory (store "mallory")
        {:keys [store state]} (store "alice")]
    (storage/-compare-and-set-ref! (:store mallory) "main" nil "cid-m")
    (reset! state {"main" (get @(:state mallory) "main")})
    (is (nil? (storage/-read-ref store "main")))))

(deftest a-fork-is-visible
  (testing "the property a plain overwritten pointer cannot provide: two heads
            at one sequence are detectable rather than a silent lost update"
    (let [a (store "alice")
          b (store "alice")]
      (storage/-compare-and-set-ref! (:store a) "main" nil "cid-a")
      (storage/-compare-and-set-ref! (:store b) "main" nil "cid-b")
      (let [ha (get @(:state a) "main")
            hb (get @(:state b) "main")]
        (is (sh/forked? ha hb))
        (is (not (sh/forked? ha ha)))))))

(deftest the-profile-is-single-writer-not-linearizable
  (testing "a signature proves authorship, not exclusivity — claiming
            linearizable here would be the silent-success failure ref-profiles
            exists to prevent"
    (let [{:keys [store]} (store "alice")]
      (is (= :single-writer-ref (storage/ref-profile store)))
      (is (not (storage/linearizable? store))))))

(deftest composes-with-a-block-only-store
  (testing "the point of the exercise: blocks on a host with no conditional
            write, refs on this"
    (let [blocks (reify
                   storage/IBlockStore
                   (-put-blocks! [_ bs] (mapv :cid bs))
                   (-get-blocks [_ _] {})
                   storage/IBackendCapabilities
                   (-capabilities [_] storage/block-capabilities))
          backend (storage/compose {:blocks blocks :refs (:store (store "alice"))})]
      (is (= backend (storage/validate-backend! backend)))
      (is (= :single-writer-ref (storage/ref-profile backend))))))
