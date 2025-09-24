# CockroachDB Connection Management on IBM Cloud
**Load balancing • Failover / Failback • Connection pooling**

## TL;DR
- Use **IBM Cloud VPC Network Load Balancer (NLB)** on **TCP/26257**; keep **round_robin**.
- Health check each node via **HTTP 8080** `GET /health?ready=1` to route only to ready nodes.
- For planned maintenance, coordinate with CockroachDB **node drain** and **server.shutdown.\*** settings.
- Connection pooling: **maxLifetime 5–30m with ~10% jitter**; size *active* conns ~**4 × vCPUs per region**.

---

## 1) Reference architecture
- **Single region / multi-AZ**: 3+ CockroachDB nodes (one per zone) behind one **NLB** (public or private).
- **Health check**: HTTP on **8080**, path `/health?ready=1` (returns 200 only when node is ready).
- **TLS**: terminate at CockroachDB; the NLB is L4 pass-through.

**Multi-region:** one NLB per region; steer with IBM Cloud Internet Services (CIS) Global Load Balancer as needed.

---

## 2) Load balancer selection

| Aspect | NLB (VPC) | ALB (VPC) |
|--|--|--|
| Layer | L4 pass-through | L7 proxy (also TCP) |
| Health checks | TCP/HTTP to any port | TCP/HTTP/HTTPS |
| Algorithm | Round-robin (default) | Weighted/L7 rules |
| Idle timeouts | OS/app-level keep-alive | Configurable (increase for long SQL) |
| DSR | Yes | No |
| Fit for SQL | **Best** | Use only if you need ALB features |

**Recommended:** NLB + HTTP health check on port 8080 `/health?ready=1`.

---

## 3) Failover & failback

### Unplanned failure
- NLB marks node unhealthy from health-check failures; new connections go to healthy nodes.
- CockroachDB rebalances automatically; apps reconnect via their pool.

### Planned maintenance (graceful)
1. Set cluster shutdown timings (see `sql/cluster-settings.sql`).
2. Drain node (`cockroach node drain --self`).
3. The health endpoint returns non-ready; NLB stops routing.
4. After upgrade, node becomes ready; traffic resumes (**failback**).

---

## 4) Connection pooling

- **Max lifetime:** 5–30 minutes + **~10% jitter** to avoid reconnect storms.
- **Active concurrency target:** ~**4 × vCPUs per region** across all pools/instances.
- **Validation/keepalive:** enable in driver pools (HikariCP, pgx, etc).
- **PgBouncer:** optional for microservice swarms; keep aggregate active conns within limits.

---

## 5) Timeouts & keep-alives

- Prefer database/driver **TCP keep-alive**; increase ALB idle timeouts if you must use ALB.
- Consider statement/transaction timeouts aligned with SLOs.

---

## 6) Observability

- DB Console: **Open Sessions**, **Connection Attempts/sec**, **SQL latency**.
- LB: listener connections, member health status.
- Watch for reconnect spikes; if observed, shorten maxLifetime and add jitter.

---

## 7) Files in this package

- `terraform/` — NLB with listener 26257, pool w/ HTTP 8080 `/health?ready=1`, members on 26257.
- `cli/ibmcloud-nlb.sh` — CLI helper to create the same quickly.
- `sql/cluster-settings.sql` — sane defaults for graceful drains.
- `examples/hikari.properties`, `examples/pgbouncer.ini` — pool templates.
- `runbooks/rolling-maintenance.md` — repeatable ops steps.
