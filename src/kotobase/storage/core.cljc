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

(defn block-store? [value] (satisfies? IBlockStore value))
(defn ref-store? [value] (satisfies? IRefStore value))

(defn backend?
  [value]
  (and (block-store? value)
       (ref-store? value)
       (satisfies? IBackendCapabilities value)))

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
