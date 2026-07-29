(ns kotobase.storage.core
  "Provider-neutral storage contract for Kotobase.

  Immutable CID blocks and mutable database refs deliberately have separate
  protocols because their consistency and lifecycle rules differ.")

(defprotocol IBlockStore
  (-put-blocks! [store blocks]
    "Idempotently persist [{:cid string :bytes bytes} ...].")
  (-get-blocks [store cids]
    "Return a map of present CID strings to bytes. Missing CIDs are omitted."))

(defprotocol IRefStore
  (-read-ref [store ref-name]
    "Return nil or {:cid string :version provider-token}.")
  (-compare-and-set-ref! [store ref-name expected-cid next-cid]
    "Publish NEXT only when the current CID equals EXPECTED.
     Return {:published? boolean :current cid-or-nil :version token}."))

(defprotocol IBackendCapabilities
  (-capabilities [store] "Return a set of backend capability keywords."))

(def required-capabilities
  #{:immutable-blocks :cid-addressed-read :conditional-ref})

(def ref-profiles
  "How far `-compare-and-set-ref!` actually holds.

  `:conditional-ref` only says the operation exists. It says nothing about
  whether two writers can rely on it, and that is the difference between a
  backend that rejects a stale publish and one that silently accepts it and
  loses an update. Every backend must therefore declare exactly one of:

  - `:linearizable-ref` — the CAS is enforced by the store itself, so
    concurrent writers in unrelated processes are safe. Backed by a real
    primitive: an ETag precondition the service evaluates, a SQL
    transaction, `git update-ref`.

  - `:single-writer-ref` — the CAS holds only within one writer. It may be
    serialized in-process, or ride on a transport with no conditional write
    at all (IPNS publishes unconditionally; Backblaze B2 has no conditional
    put on either API). Correct deployments put one writer in front of it,
    or a linearizable ref service.

  The set is closed and the choice is mandatory because the failure mode of
  guessing is silent: an ignored precondition returns success."
  #{:linearizable-ref :single-writer-ref})

(defn block-store? [value] (satisfies? IBlockStore value))
(defn ref-store? [value] (satisfies? IRefStore value))

(defn backend?
  [value]
  (and (block-store? value)
       (ref-store? value)
       (satisfies? IBackendCapabilities value)))

(defn ref-profile
  "Which of `ref-profiles` this backend declares, or nil if it declares
  none or more than one."
  [backend]
  (let [declared (filter (-capabilities backend) ref-profiles)]
    (when (= 1 (count declared)) (first declared))))

(defn linearizable?
  "True when the backend claims its ref CAS survives concurrent writers.

  Read this rather than testing for `:conditional-ref`, which every backend
  has and which distinguishes nothing."
  [backend]
  (= :linearizable-ref (ref-profile backend)))

(defn validate-backend!
  ([backend] (validate-backend! backend required-capabilities))
  ([backend required]
   (when-not (backend? backend)
     (throw (ex-info "Kotobase backend does not implement the storage contract"
                     {:type :kotobase.storage/invalid-backend})))
   (let [missing (remove (-capabilities backend) required)]
     (when (seq missing)
       (throw (ex-info "Kotobase backend lacks required capabilities"
                       {:type :kotobase.storage/missing-capabilities
                        :missing (set missing)}))))
   (when-not (ref-profile backend)
     (throw (ex-info "Kotobase backend must declare exactly one ref profile"
                     {:type :kotobase.storage/undeclared-ref-profile
                      :expected ref-profiles
                      :declared (set (filter (-capabilities backend)
                                             ref-profiles))})))
   backend))

(defn put-block! [store cid bytes]
  (-put-blocks! store [{:cid cid :bytes bytes}]))

(defn get-block [store cid]
  (get (-get-blocks store [cid]) cid))

(defn scoped-ref
  "Return the stable mutable-ref name for a tenant/database pair.

  Blocks remain globally CID-addressed; only mutable refs require logical
  scoping. `pr-str` is deliberately used as an unambiguous CLJ/CLJS encoding."
  [tenant database]
  (pr-str [:kotobase/ref tenant database]))

(defn ports
  "Expose synchronous kotobase-engine function ports for a JVM backend."
  [store]
  {:put! (fn [cid bytes] (put-block! store cid bytes))
   :get-fn (fn [cid] (get-block store cid))
   :head (fn [name] (some-> (-read-ref store name) :cid))
   :cas! (fn [name expected next]
           (:current (-compare-and-set-ref! store name expected next)))})

#?(:cljs
   (defn async-ports
     "Expose Promise-returning kotobase-engine ports for a Worker backend."
     [store]
     {:put! (fn [cid bytes] (put-block! store cid bytes))
      :get-fn (fn [cid]
                (-> (-get-blocks store [cid])
                    (.then #(get % cid))))
      :head (fn [name]
              (-> (-read-ref store name)
                  (.then #(some-> % :cid))))
      :cas! (fn [name expected next]
              (-> (-compare-and-set-ref! store name expected next)
                  (.then (fn [result] (:current result)))))}))
