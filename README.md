# kotobase-storage

Provider-neutral storage contract for Kotobase.

- `IBlockStore` stores immutable bytes under CID keys.
- `IRefStore` conditionally publishes mutable database roots.
- `IBackendCapabilities` makes consistency guarantees explicit.

IPLD decoding and CID verification belong to `kotobase-engine`; providers
return untrusted bytes. Blocks are globally content-addressed, while
`scoped-ref` isolates mutable heads by tenant and database.

Provider implementations must run `kotobase.storage.contract/verify` (JVM) or
`kotobase.storage.async-contract/verify` (Worker/JavaScript).

This contract replaces the legacy application document/event `IStore` for
database persistence.

## Every backend declares one ref profile

`:conditional-ref` says the operation exists. It does not say whether two
writers can rely on it — and that is the difference between a store that
rejects a stale publish and one that accepts it, reports success, and loses
an update. So `-capabilities` must contain exactly **one** of:

| profile | meaning | backed by |
|---|---|---|
| `:linearizable-ref` | the store evaluates the precondition; concurrent writers in unrelated processes are safe | R2 `onlyIf`, `If-Match` on an endpoint that honours it, a SQL transaction, `git update-ref` |
| `:single-writer-ref` | the CAS holds only within one writer | IPNS (publishes unconditionally), Backblaze B2 (no conditional write on either API) |

Declaring neither, or both, is refused by `validate-backend!`. The choice is
mandatory rather than defaulted because the failure mode of guessing is
silent: an ignored precondition returns success.

`storage/linearizable?` is the predicate to branch on. Testing for
`:conditional-ref` distinguishes nothing — every backend has it.

## The suite races writers, and can prove it

The conformance suite has two halves.

The **sequential** half is the original eight checks. The **concurrent**
half races four writers at the same expected head and requires exactly one
to win, plus every loser to observe the winner. It runs only for a backend
declaring `:linearizable-ref`; the result reports `:concurrency :verified`
or `:not-claimed`, so a passing run cannot be read as a guarantee the
backend never made.

The concurrent half exists because the sequential half cannot fail on the
realistic hazard. A provider that reads the head, compares, and writes a
turn later — read-then-PUT, which is all an endpoint without a conditional
write can offer — passes all eight sequential checks. One writer never
observes the difference.

`test/run.cljs` therefore runs the suite against three stores it must
**reject**, and requires the rejection to come from the right check:

| oracle | caught by |
|---|---|
| decides on a stale read, writes a turn later (`:toctou`) | the race, and only the race |
| no precondition at all (`:ignored`) | the sequential half |
| declares no ref profile | `validate-backend!` |

The first is load-bearing. The async race runs promises on one event loop,
where interleaving is not guaranteed; if every read resolved before any
write, four writers would serialize and the race would pass against a store
enforcing nothing. That oracle is what makes the check meaningful rather
than decorative.

## Large objects are a third plane, and declare a transfer profile

`kotobase.storage.object` is the port for bytes too big to move as blocks —
git-LFS objects and git-annex/DataLad content (superproject ADR-2608012600).
It is separate from `IBlockStore` for the same reason blocks and refs are
separate: what a caller may rely on differs, and guessing fails silently.

A block travels through the process that asked for it; a GB object must not,
and proxying it is what put a 4 MiB ceiling on `PUT /ipfs/:cid`. So every
large-object provider declares exactly one **transfer profile**:

| profile | meaning |
|---|---|
| `:presigned-transfer` | it can hand the client a URL; bytes go straight to storage |
| `:proxied-transfer` | bytes must pass through this process |

and separately declares `:object-delete`. A store that cannot delete must
return `{:deleted? false :reason :not-supported}` — the contract suite fails
a store that reports a successful delete while the bytes stay readable,
because `git annex drop --from` and an LFS quota both act on that answer.

Two properties the suite refuses to let a provider skip:

- **a PUT grant binds `content-length` in its signature.** Listing the header
  in the request while signing only `host` binds nothing, and an unbound
  presigned PUT is a blank cheque for arbitrary bytes under a CID whose
  digest the holder never had to know.
- **`present?` means THIS store holds it**, not that the bytes exist
  somewhere. git-annex drops its last local copy on the strength of that
  answer.

`kotobase.storage.object-id` is the identity seam: git-LFS `oid sha256:<hex>`,
git-annex `SHA256E-s<size>--<hex>` and CIDv1(raw, sha2-256) all carry the same
digest, so the conversions are pure functions and no registry is needed. The
two configurations that break the derivation — `chunk=` (every chunk carries
the whole file's digest) and client-side encryption (`GPGHMACSHA1--…` is an
HMAC) — return nil and throw rather than resolve to some other object. It is
dependency-free and cross-checked against `io-multiformats` in the tests.

## Verification is a property of the store, not of each caller

## A signed head has to be addressed, not just signed

`signed-head` exists so a ref can be served by a mirror, CDN or storage node
nobody trusts. Until 2026-08-04 the read path did not deliver that, and both
gaps were the same mistake in different clothes — checking a **signature**
where **authority** was needed. Every head involved is genuinely signed;
nothing had to be forged.

| gap | what an untrusted host could do |
|---|---|
| `"ref"` was signed but never compared to the ref being read | answer a read for ref B with ref A's real head — a reader gets A's CID under B |
| `verify-fn` was called with the issuer **the record names** | substitute a head signed by its own key; "somebody signed this" is satisfied by any keypair |

Both are now checked *before* the signature, so a head addressed elsewhere
never reaches a verify that would say yes. `open`/`async-open` take an
optional `:accept-issuer?`; **it defaults to accepting only the store's own
`:issuer`, which is a behaviour change.** The documented deployment model is
one writer per ref, so that is the right default; a deployment that rotates
signing identities or hands a ref over on purpose passes its own predicate
(`#(contains? known-issuers %)`) — a stated decision rather than the absence
of one.

The oracles run on both runtimes deliberately. `verify-chain-async` is a
second copy of the check, which is the exact shape that drifts, and this
library's own principle is that passing on the JVM is not evidence for the
Worker path. Superproject decision record: **ADR-2608047000**.

## Untrusted blocks

Blocks may live on hosts nobody trusts, which is only sound if somebody
re-hashes what comes back. `kotobase.storage.verify` makes that the store's
job instead of every caller's:

```clojure
(verify/verifying-block-store inner cid-of)        ; JVM / sync
(verify/async-verifying-block-store inner cid-of)  ; Worker / Promise
```

`cid-of` is `(fn [bytes] cid-string)` and is **injected**, not depended on:
this library sits under every provider including Worker builds, so a hashing
dependency here would land in all of them. `io-multiformats` stays test-only
for the same reason `object-id` is hand-rolled.

A block whose bytes do not hash to the CID it was returned under **throws**
`:kotobase.storage/cid-mismatch`. That is deliberately the opposite of
`signed-head`, which reads an unverifiable head as absent — and the
difference is what absence means to the caller. A missing ref means "nothing
published", and the caller's next move is safe. A missing block is
indistinguishable from a subtree that does not exist, so omitting a tampered
one turns a corrupt store into a *shorter answer*: rows quietly gone from a
query, looking exactly like a cache miss. A CID is only ever asked for
because something pointed at it, so a mismatch is not "the host has nothing"
— it is proof the host returned bytes that are not the bytes.

`classify` gives a scrub pass the survey it needs (`{:verified … :mismatched
…}`) so wanting to enumerate damage never requires making the read path
lenient. Missing CIDs stay omitted, `IRefStore` is delegated when and only
when the wrapped store has it (so `backend?` keeps telling the truth), and
`-capabilities` gains `:verified-blocks` so the guarantee is discoverable
rather than an accident of assembly.

Puts are not verified: the bytes on a put came from this process, and a
caller that computes its own CIDs wrongly builds a store that fails its own
reads, loudly, at the same seam.

## Test

```sh
clojure -M:test                          # JVM, real threads
nbb --classpath "src:test" test/run.cljs # async, with oracles
```

Both, as CI runs them. `nbb` rather than `cljs.main -re node`: that runner
does not propagate the script's exit code, so a failing suite exited 0 and
the gate would have been green forever.
