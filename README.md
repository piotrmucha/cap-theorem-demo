# CAP Theorem Demo — Flash Sale Stock Counter

Two Postgres instances in real streaming replication. Flip one config
setting and the same system goes from **AP** (always accepts writes, serves
stale reads) to **CP** (refuses writes it can't replicate) — demonstrated
with a real network partition, not a simulation.

## Architecture

```
                      replication-net   ← cut THIS to partition
   ┌───────────────┐  ─────────────────  ┌───────────────┐
   │     db-us     │◄── WAL streaming ──►│     db-eu     │
   │   (primary)   │                     │ (hot standby) │
   └───────┬───────┘                     └───────┬───────┘
           │ us-net                              │ eu-net
   ┌───────┴───────────┐                 ┌───────┴───────┐
   │      api-us       │                 │    api-eu     │
   │ POST /admin/sell  │                 │  GET /stock   │
   │ POST /admin/reset │                 │  (read-only)  │
   │ GET  /stock       │                 └───────────────┘
   └───────────────────┘                        :8082
          :8081
```

- **db-us** — Postgres primary, the only writable copy.
- **db-eu** — real hot standby. On first boot it clones the primary with
  `pg_basebackup`, then streams WAL continuously.
- Three separate docker networks so `replication-net` carries *only* the
  primary↔standby link — cutting it partitions the databases without
  touching either app's connection to its own database.

## Run it

```bash
docker compose up -d --build
```

- US (takes writes): http://localhost:8081
- EU (read replica): http://localhost:8082

Check replication is live:

```bash
docker compose exec db-us psql -U shop -d shop \
  -c "SELECT application_name, state, sync_state FROM pg_stat_replication;"
```

Expect one row: `db_eu | streaming | sync`.

Note that `sync_state` reads `sync` in **both** AP and CP mode — it only
reports that `db_eu` is *named* in `synchronous_standby_names`, which it
always is. It does **not** track `synchronous_commit`. To see which mode is
actually live, ask the server directly:

```bash
docker compose exec db-us psql -U shop -d shop -c "SHOW synchronous_commit;"
```

`local` is AP, `remote_apply` is CP.

## Switching CP / AP

The standby is always *named* as synchronous. Whether the primary actually
waits for it is one word — swap which line is commented in the `command:`
block of `db-us`:

| Mode | `synchronous_commit` | Primary replies… |
|------|----------------------|------------------|
| AP   | `local`              | as soon as its own WAL is flushed |
| CP   | `remote_apply`       | only once the standby has replayed the write |

Then apply:

```bash
docker compose up -d --force-recreate db-us
```

**Why CP blocks**: with a synchronous standby named, Postgres won't report
`COMMIT` as successful until that standby confirms. The backend parks in a
wait loop until the confirmation arrives or is canceled. Partition the
standby and it never arrives — the write hangs.

### Why `remote_apply` and not `on`

`synchronous_commit` is not a boolean — it's a ladder, and only the top
rung gives you strong consistency:

| Value | Primary waits until the standby has… | Guarantee |
|-------|--------------------------------------|-----------|
| `local` | *(doesn't wait at all)* | eventual consistency |
| `remote_write` | received it into OS buffers | survives a standby *process* crash |
| `on` | **flushed it to disk** | no data loss on failover |
| `remote_apply` | **replayed it into the visible table** | reads on the standby see it |

`on` guarantees *durability*, not *visibility*. The standby can have the
WAL safely on disk while still not having replayed it — so a read on EU
right after the primary confirms the write can still return the old value.

That is not a theoretical gap. Measured on this exact stack, with a 5s
replay delay on the standby to widen the window:

```
synchronous_commit = on            → sell returned 200 in 106ms
                                     US = 49, EU = 50   ← stale read
synchronous_commit = remote_apply  → sell blocked for the full replay delay
                                     US and EU always agree
```

Since the whole point of the demo is "in CP mode, EU never shows a number
that isn't real", `remote_apply` is the honest setting. With `on` the demo
would *look* fine most of the time on a local network — which is exactly
what makes it a trap.

The cost is real: `remote_apply` puts
the standby's replay in your write path, so every write pays a full network
round trip plus replay time.

**Why we still get a clean 503**: `api-us` sets a JDBC query
timeout (`SELL_TIMEOUT_SECONDS`, default 3s), so pgjdbc sends a real
Postgres cancel request instead of hanging forever.

Two non-obvious things make this work, both found by testing against a live
partition:

1. Postgres's `statement_timeout` does **not** interrupt a synchronous
   replication wait. Only an actual query cancel does — hence the JDBC
   query timeout rather than the GUC.
2. Canceling that wait does **not** fail the statement. Postgres commits
   locally, emits a `WARNING` ("canceling wait for synchronous
   replication"), and returns success. So the app sets
   `JdbcTemplate.setIgnoreWarnings(false)` to surface that warning and map
   it to a 503.

**So in CP mode the write does apply locally on the primary even when the
API reports 503** — the primary's own counter decrements. That is not a
bug; it is exactly the "in doubt" state synchronous replication leaves you
in during a partition. The write is durable locally but unconfirmed, which
is why the API refuses to call it a success.

## Partition / heal

```bash
docker network disconnect replication-net db-eu   # break
docker network connect    replication-net db-eu   # heal
```

After healing, the standby reconnects and catches up within a few seconds
(`wal_retrieve_retry_interval`, default 5s). A replication slot on the
primary keeps the needed WAL, so reconnecting always resumes rather than
forcing a fresh base backup.

## What to expect

**AP mode, healthy** — EU trails US by a beat (eventual consistency).

**AP mode, partitioned** — "Sell" keeps returning 200 instantly. EU
freezes on a stale count and `last synced: Xs ago` climbs. Available, not
consistent.

**CP mode, healthy** — every confirmed sell is already visible on EU. Read
US and EU back to back and they always agree (strong consistency).

**CP mode, partitioned** — "Sell" hangs ~3s then returns 503. Nothing is
confirmed while the standby is unreachable. Consistent, not available.
(The US counter still ticks down — see the "in doubt" note above.)

Both recover within seconds of `docker network connect`.

## Resetting

Click "Reset stock", or:

```bash
curl -X POST http://localhost:8081/admin/reset        # back to STARTING_STOCK (8)
curl -X POST "http://localhost:8081/admin/reset?qty=50"  # or pick a number
docker compose down -v                           # full wipe
```

## Layout

```
docker-compose.yml
postgres/primary/initdb/   schema + replication role/slot
postgres/standby/          entrypoint that clones the primary
api-us/                    Spring Boot, read/write against the primary
api-eu/                    Spring Boot, read-only against the standby
```
