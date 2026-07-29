(ns run
  "Async conformance suite, verified against its own oracles.

  The JVM half races real threads, so interleaving is guaranteed. The async
  half races promises on one event loop, where it is NOT: if every
  `-read-ref` resolved before any write ran, four writers would serialize
  and the race would pass trivially against a store enforcing nothing. So
  this file does not merely run the suite -- it runs it against stores that
  must be REJECTED, and requires the rejection to come from the check that
  is supposed to catch each one.

  The store that matters is `:toctou`. It is sequentially correct -- it
  passes every check the old suite had -- and wrong only under concurrent
  writers, because it decides on a value it read a turn earlier and writes
  without re-checking. That is not a contrived shape: it is read-then-PUT
  over HTTP, which is what a provider without a conditional write can
  offer, and it is what the old suite could never distinguish from a real
  precondition."
  (:require [kotobase.storage.async-contract :as contract]
            [kotobase.storage.core :as storage]))

(defn- resolved [value] (js/Promise.resolve value))

(defrecord AsyncMemory [state profile mode]
  storage/IBlockStore
  (-put-blocks! [_ blocks]
    (resolved (do (swap! state update :blocks into (map (juxt :cid :bytes)) blocks)
                  (mapv :cid blocks))))
  (-get-blocks [_ cids] (resolved (select-keys (:blocks @state) cids)))

  storage/IRefStore
  (-read-ref [_ name] (resolved (get-in @state [:refs name])))
  (-compare-and-set-ref! [this name expected next]
    (let [publish! (fn [] (swap! state assoc-in [:refs name]
                                 {:cid next :version next})
                     {:published? true :current next :version next})
          lose (fn [current] {:published? false :current (:cid current)
                              :version (:version current)})]
      (-> (storage/-read-ref this name)
          (.then
           (fn [read-at-call-time]
             (case mode
               ;; The store evaluates the precondition itself, at write
               ;; time. What R2's `onlyIf` and a SQL transaction give you.
               :enforced (let [current (get-in @state [:refs name])]
                           (if (= expected (:cid current)) (publish!) (lose current)))

               ;; Decide on the earlier read, write a turn later. Correct
               ;; for one writer; loses updates for two.
               :toctou (if (not= expected (:cid read-at-call-time))
                         (lose read-at-call-time)
                         (-> (resolved nil) (.then (fn [_] (publish!)))))

               ;; No precondition at all.
               :ignored (publish!)))))))

  storage/IBackendCapabilities
  (-capabilities [_] (conj storage/required-capabilities profile)))

(defn- store [profile mode]
  (->AsyncMemory (atom {:blocks {} :refs {}}) profile mode))

(def ^:private failures
  "Counted here rather than in `process.exitCode`, which `cljs.main -m run`
  resets when its own REPL turn finishes -- a failing run exited 0, so any
  CI gating on the exit code would have been green forever. Verified by
  breaking an oracle on purpose."
  (atom 0))

(defn- expect-rejection
  "Run the suite and require it to reject, for the stated reason."
  [backend label match]
  (-> (contract/verify backend)
      (.then (fn [result]
               (js/console.error
                (str "FAIL: " label " -- the suite accepted a store it must reject: "
                     (pr-str result)))
               (swap! failures inc)))
      (.catch (fn [error]
                (if (re-find match (.-message error))
                  (println (str "ok  - " label))
                  (do (js/console.error
                       (str "FAIL: " label " -- rejected for the wrong reason: "
                            (.-message error)))
                      (swap! failures inc)))))))

(defn- expect-pass [backend label expected]
  (-> (contract/verify backend)
      (.then (fn [result]
               (if (= expected result)
                 (println (str "ok  - " label ": " (pr-str result)))
                 (do (js/console.error (str "FAIL: " label " -- got " (pr-str result)))
                     (swap! failures inc)))))
      (.catch (fn [error]
                (js/console.error (str "FAIL: " label " -- " (.-message error)))
                (swap! failures inc)))))

(defn -main [& _]
  (-> (expect-pass (store :linearizable-ref :enforced)
                   "an enforcing store passes, race included"
                   {:checks 14 :profile :linearizable-ref :concurrency :verified})
      (.then (fn [_]
               ;; The load-bearing oracle: sequentially indistinguishable
               ;; from the enforcing store, caught only by the race. If
               ;; promise interleaving did not really happen, this passes
               ;; and the whole concurrent half is decoration.
               (expect-rejection (store :linearizable-ref :toctou)
                                 "read-then-write is caught, and only by the race"
                                 #"concurrent writers all published")))
      (.then (fn [_]
               (expect-rejection (store :linearizable-ref :ignored)
                                 "a store with no precondition at all is caught earlier"
                                 #"stale CAS must lose")))
      (.then (fn [_]
               (expect-rejection (->AsyncMemory (atom {:blocks {} :refs {}})
                                                :no-such-profile :enforced)
                                 "an undeclared ref profile is refused"
                                 #"exactly one ref profile")))
      (.then (fn [_]
               ;; Declaring :single-writer-ref is not a loophole: the
               ;; sequential half still applies. The same store passes it
               ;; and is simply not asked about concurrent writers.
               (expect-pass (store :single-writer-ref :toctou)
                            "a single-writer store passes without being raced"
                            {:checks 8 :profile :single-writer-ref
                             :concurrency :not-claimed})))
      (.then (fn [_]
               (if (zero? @failures)
                 (println "async contract oracles: all green")
                 (println (str "async contract oracles: " @failures " FAILURE(S) above")))
               (.exit js/process (if (zero? @failures) 0 1))))
      (.catch (fn [error]
                (js/console.error error)
                (.exit js/process 1)))))

(-main)
