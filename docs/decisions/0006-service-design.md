# 0006: recon-service design

**Status:** accepted

## The break register
`recon_break` is a register of live breaks, not a per-run log. A row is created the first time
a run finds a break and is then updated in place by later runs. `run_id` is the latest run that
saw it, while `opened_at`, `owner`, `note` and `status` belong to the people working it.
- A partial unique index allows **one live break per key** (type, trade, confirm, field). It
  uses `NULLS NOT DISTINCT` (PostgreSQL 15+), because MISSING_CONFIRM has a null confirm and
  plain unique indexes treat nulls as distinct.
- A re-run therefore finds the same breaks, refreshes them (`breaks.carriedOver`) and inserts
  nothing (`breaks.newlyOpened = 0`). That's the idempotency guarantee, and `RunIT` tests it,
  including that ids and `opened_at` don't change.
- `match_link` is per run (`run_id, internal_id` primary key). Re-runs produce identical link
  sets, which keeps a history of what each run decided and which rule decided it.
- A manually resolved break that a later run finds again comes back as a new OPEN row. The data
  is still wrong, so hiding it would be worse.

## Runs
- `POST /api/runs` inserts a RUNNING row, commits, and queues the work on a small thread pool,
  returning 202 with a `Location` to poll. Even a 10M-row date never holds an HTTP request open.
- A partial unique index (`business_date WHERE status = 'RUNNING'`) makes a second concurrent
  run for the same date a 409. The database enforces it, so it also holds across several
  service instances.
- A run is **one transaction**: links, new breaks, carried breaks, auto-resolutions and the run's
  counts commit together or not at all. On failure the run is marked FAILED in a separate
  transaction and its error is stored.
- Bulk reads and writes go through `JdbcTemplate` batches (`reWriteBatchedInserts`). JPA
  (Hibernate) handles what people do: listing, viewing and editing breaks.

## Concurrent edits
`recon_break.version` is a JPA `@Version`, and runs bump it in their batch updates. A PATCH that
races a run, or another user, fails with 409 instead of silently overwriting. Clients can also
send `expectedVersion` to say "only if nobody changed it since I loaded it".

## Import
`POST /api/imports` reads from `recon.import-directory`, which is configuration and never a path
from the request. Each CSV is streamed with `COPY` into a temp staging table, then
`INSERT ... ON CONFLICT DO NOTHING` into the real table, all in one transaction. Re-importing is
a no-op, and a bad file leaves nothing behind. Values are stored raw; normalization stays in
recon-core.

## Errors
Every error is an RFC 7807 problem detail, with a `detail` the caller can act on and an `errors`
list naming each bad field. Spring's default type-mismatch text is replaced: `status=CLOSED`
gives "status must be one of OPEN, ASSIGNED, RESOLVED, got 'CLOSED'".

## Testing
Integration tests run against real PostgreSQL 16 in Testcontainers (no H2: partial indexes,
`NULLS NOT DISTINCT` and `COPY` are PostgreSQL features), on data from the real generator.
Testcontainers is pinned to 1.21.4, above Spring Boot's 1.21.3, because Docker Engine 29+ no
longer accepts the Docker API version that 1.21.3 uses.
