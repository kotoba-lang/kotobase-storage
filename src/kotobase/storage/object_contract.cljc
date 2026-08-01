(ns kotobase.storage.object-contract
  "Reusable conformance suite for large-object providers.

  Same shape as `kotobase.storage.contract`: `check` is `(fn [truthy label])`
  so a provider repo can run it under whatever test runner it has.

  The suite is deliberately asymmetric between the two transfer profiles. A
  proxied store can be driven end to end here; a presigned store cannot,
  because completing an upload means talking to the provider's network. What
  IS checked for a presigned store is the property that has no second chance:
  that a PUT grant binds its size. Everything else about a presigned store
  belongs in that provider's own live test."
  (:require [kotobase.storage.object :as object]))

;; The suite never hashes, so the CIDs are literals — but they are the REAL
;; CIDs of the bytes it stores (sha2-256 of [1 2 3] and of [4 5]), so a
;; provider that does verify content can run this suite unchanged.
(def ^:private cid-a
  "bafkreiadsbmmn4waznesyuz3bjgrj33xzqhxrk6mz3ksq7meugrachh3qe")
(def ^:private cid-b
  "bafkreibpugzxpp3hgcpwlzphxsozeq2fzjsi33comandtcu4wsl5zorxmu")
(def ^:private not-an-object-cid "SHA256E-s3--abc.wav")

(defn- bytes-of [ints]
  #?(:clj (byte-array ints) :cljs (js/Uint8Array. (clj->js ints))))

(defn thrown-by?
  "True when `f` throws. A function rather than a macro so the suite stays
  callable from a provider repo on either platform."
  [f]
  (try (f) false
       (catch #?(:clj Exception :cljs :default) _ true)))

(defn- verify-proxied [store check]
  (check (nil? (object/-stat-object store cid-a))
         "a missing object stats as nil")
  (check (false? (object/present? store cid-a))
         "and is not present")
  (object/-put-object! store cid-a (bytes-of [1 2 3]))
  (check (= 3 (:size-bytes (object/-stat-object store cid-a)))
         "a stored object reports its size")
  (check (true? (object/present? store cid-a))
         "and is present")
  (check (= [1 2 3] (vec (object/-get-object store cid-a)))
         "object bytes round-trip")
  (check (nil? (object/-get-object store cid-b))
         "an object nobody stored is not invented")
  (let [{:keys [deleted? reason]} (object/-delete-object! store cid-a)]
    (if (object/deletes? store)
      (do (check (true? deleted?) "a store claiming :object-delete deletes")
          (check (nil? (object/-stat-object store cid-a))
                 "and the object is gone afterwards"))
      (do (check (false? deleted?)
                 "a store without :object-delete reports failure rather than
                  a tombstone that says success")
          (check (= :not-supported reason) "and says why")
          (check (some? (object/-stat-object store cid-a))
                 "and does not claim the bytes went away")))))

(defn- verify-presigned [store check]
  (let [put (object/-presign-put store cid-a {:size-bytes 3})
        get (object/-presign-get store cid-a {})]
    (check (object/valid-grant? put) "a put grant has href, method and expiry")
    (check (object/valid-grant? get) "a get grant has href, method and expiry")
    (check (object/bound-put-grant? put)
           "a put grant binds content-length in its SIGNATURE — an unbound
            presigned PUT is a blank cheque for arbitrary bytes")
    (check (not= (:href put) (:href get))
           "the two grants are not the same URL by accident")))

(defn verify
  "Run the shared large-object contract."
  [store check]
  (object/validate-object-store! store)
  (check (thrown-by? #(object/-stat-object store not-an-object-cid))
         "a store rejects an id that is not a CIDv1(raw, sha2-256) — an LFS
          oid or an annex key passed straight through creates an object
          nobody can resolve")
  (if (object/presigned? store)
    (verify-presigned store check)
    (verify-proxied store check))
  {:profile (object/transfer-profile store)
   :deletes (object/deletes? store)})
