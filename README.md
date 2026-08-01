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

## Test

```sh
clojure -M:test                                          # JVM, real threads
clojure -M:cljs-test -m cljs.main -re node -m run        # async, with oracles
```
