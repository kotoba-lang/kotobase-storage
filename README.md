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
