# ADR-0001: Co-locate independently selected storage provider sources

Status: source placement accepted; merged-main qualification and retirement
remain pending. Tracking: [JUN-267](https://linear.app/kotobalabs/issue/JUN-267).

Seven non-deployment adapter/decorator projects now share the existing
`kotoba-lang/kotobase-storage` source owner. Each remains an opt-in project with
its own dependencies, tests, build declarations, LICENSE and release boundary.
The root storage contract's production source and dependency graph are unchanged
from `111c7156cd4a38b544cb3946f70b8edcca466d77`. It never depends on its providers.

| Source ID | Legacy repository |
|---|---|
| `kotoba.kotobase.storage.git` | `kotoba-lang/kotobase-storage-git` |
| `kotoba.kotobase.storage.ipfs` | `kotoba-lang/kotobase-storage-ipfs` |
| `kotoba.kotobase.storage.kura` | `kotoba-lang/kotobase-storage-kura` |
| `kotoba.kotobase.storage.pack` | `kotoba-lang/kotobase-storage-pack` |
| `kotoba.kotobase.storage.postgres` | `kotoba-lang/kotobase-storage-postgres` |
| `kotoba.kotobase.storage.s3` | `kotoba-lang/kotobase-storage-s3` |
| `kotoba.kotobase.storage.sqlite` | `kotoba-lang/kotobase-storage-sqlite` |

The projects live under `providers/<source-id>`. Exact old repository and
`io.github.*` coordinates remain source lookup aliases. Existing public Lisp
namespaces, protocols, data CIDs and provider capability profiles retain their
identities. These source IDs are separate from the language's standard library
catalog and from signed package/runtime admission. No grant is created.

Only selected storage dependency edges point to local roots. Every other EDN
runtime/test dependency value and host alias is retained. The IPFS project now
declares `@noble/hashes` 2.0.1 explicitly, matching its existing pinned
`io-multiformats` project's own declaration. Its old hoisted 2.4.0 copy is retained
under `@noble/curves` with identical integrity; all other locked entries remain
identical. This repairs an undeclared dependency without changing provider code.

Co-location does not make the legacy mutable-ref compatibility providers the
canonical Kotobase database correctness path. Signed immutable CID commits,
rollback protection, independent agreement and host-selected transport keep
their existing roles. D1's compatibility deployment/rollback boundary stays
separate. The frozen `kotobase-peer` is not made a new owner of capabilities
already extracted from it. Public runtime/deployment shells and Amu remain
outside this source monorepo operation.

## Source and history evidence

`maintenance/storage-source-imports.json` binds all seven sources to immutable
commits/trees, SHA-256 and modes. 123 imported files are active; 17 tracked
compiler outputs from pack's history are kept only in complete Git histories.
Test-only host exception-class corrections are recorded separately. Every
production provider source file remains byte-identical to its reviewed default.

The dated recovery receipt contains seven complete bundles / 114 refs, all 11
historical PRs and explicit successor bindings for the three nonancestor IPFS
PR heads. Their real merge commits are default ancestors; historical production
source is unchanged apart from extension names and two docstring path literals.
Every bundle was independently restored/fsck checked and all 140 original file
hashes were verified. Pack's existing 17 generated-file deletions are preserved
as a compressed Git patch that was applied in a fresh restore and produced the
exact original status. Original checkouts and broken worktree metadata remain
in place; private metadata backups are outside the repository.

Kura's async source is reviewed and merged through
[#2](https://github.com/kotoba-lang/kotobase-storage-kura/pull/2), main
`63b34f26af1a7d6a9f9b2099f61af76aa65c7050`, including draft #1's history.
The runner now actually executes the async suite. A stale-read CAS loser and
ambiguous catalog failures cannot remove a winner's shared pack. Using the real
older rollback implementation is a negative control: it destroys the winner's
data and the new suite exits 1. The shared compiled test bridge is selected as a
test-only, zero-dependency subproject of Kotoba main `f0ecb24b722664b825ab89992e9cc98c37ecaaf0`.

## Verification status

Current local qualification includes 138 JVM tests / 516 assertions across
the owner and five JVM providers; Kura's actual compiled Node suite has 17 tests /
70 assertions and zero lint errors/warnings. SCI conformance covers the owner,
pack, S3 mock/object/signature/benchmark guards, and IPFS's injected Kubo/Helia
clients, local archive HTTP fixture and real loopback TCP peers. Helia is not
started as a live node, and no live Kubo/S3/Kura service is used.

The real Miniflare/R2 and advanced compiled S3/engine build gates still require
qualification before retirement. Current source lookup and recovery checks do
not substitute for those gates. Native/Wasm, live interoperability, production
shard durability, signed-order admission and deployment are separate claims.
Existing Actions remain disabled; no CI success is claimed.

```sh
node scripts/check-storage-sources.mjs
node scripts/check-storage-history.mjs /absolute/github/workspaces/storage-restore
node scripts/storage-sources.mjs resolve kotoba.kotobase.storage.ipfs
node scripts/storage-sources.mjs resolve io.github.kotoba-lang/kotobase-storage-ipfs
node scripts/storage-sources.mjs classpath kotoba.kotobase.storage.s3
```

No repository is archived or deleted by this source commit. Retirement requires
merged-main verification, immediate source/ref/WIP rechecks, recovery proof and
an explicit archive readback in the receipt. The original 239 consumer-pin
holds and signed locks stay unchanged. Organization-wide review remains active.
