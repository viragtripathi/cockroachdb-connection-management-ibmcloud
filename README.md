# CockroachDB Connection Management on IBM Cloud

**Load balancing • Failover / Failback • Connection pooling**

## TL;DR (what to implement first)

* **Use an IBM Cloud VPC Network Load Balancer (NLB)** in front of CockroachDB nodes for SQL (TCP 26257). Prefer NLB’s L4 pass‑through for low latency and direct‑server‑return (DSR). Keep **round‑robin** balancing. ([IBM Cloud][1])
* **Health checks**: point the LB health check to each node’s HTTP admin port **8080** with `GET /health?ready=1` to avoid routing traffic to nodes that are up but not ready. ([Cockroach Labs][2])
* **Planned maintenance / failback**: coordinate LB health checks with CockroachDB **node drain** settings: `server.shutdown.initial_wait`, `server.shutdown.connections.timeout`, and `server.shutdown.transactions.timeout`. ([Cockroach Labs][3])
* **Connection pooling**: set a **max connection lifetime of \~5–30 minutes** with **\~10% jitter**, and size **active connections ≈ 4 × vCPUs per region**; CockroachDB comfortably supports many idle connections. ([Cockroach Labs][4])
* **Multi‑zone / multi‑region**: for cross‑zone and cross‑region traffic steering, pair regional NLBs with **IBM Cloud Internet Services (CIS) Global Load Balancer** (GLB) pools for HA and traffic steering. ([IBM Cloud][1])

---

## 1) Reference architecture (IBM Cloud)

### 1.1 Single region, multi‑AZ (recommended starting point)

* **Per region**: 3 CockroachDB nodes (one per zone) + **1 public or private NLB** listening on **TCP/26257**, back‑end pool members are the three nodes on **26257**.
* **NLB health checks**: **HTTP** to each node on **port 8080**, **path** `/health?ready=1`. (Default NLB check interval 5s, timeout 2s, unhealthy after 2 failures; you can adjust.) ([IBM Cloud][5])
* **Why NLB**: L4 pass‑through and **Direct Server Return (DSR)** keep latency low; **round‑robin** distributes new connections evenly. ([IBM Cloud][1])
* **TLS**: terminate TLS at the database (recommended) using CockroachDB’s certs. The NLB passes TCP through untouched. ([Cockroach Labs][6])

### 1.2 When would I use IBM Cloud **Application Load Balancer (ALB) for VPC**?

* ALB supports TCP listeners and advanced knobs (server/client **idle timeouts** default 50s, configurable to **2 hours**; **TCP keep‑alive** support; **Proxy Protocol** support). For typical SQL, **NLB is still preferred**; however, ALB can be useful when you need L7 features or specific timeout controls. If you do use ALB for SQL/TCP, increase idle timeouts to exceed your longest expected quiet period on a connection. ([IBM Cloud][7])

### 1.3 Multi‑region

* Deploy one NLB per region; steer clients with **CIS GLB** (geo / latency steering, active‑passive failover). This pattern also protects against an LB/AZ failure. ([IBM Cloud][1])

---

## 2) Load balancer selection & settings

| Aspect                   | **NLB (VPC)**                                                          | **ALB (VPC)**                                                           |
| ------------------------ | ---------------------------------------------------------------------- | ----------------------------------------------------------------------- |
| Layer                    | L4 (TCP/UDP) pass‑through                                              | L7 proxy (also supports TCP listeners)                                  |
| Health checks            | TCP or HTTP to any port (e.g., CockroachDB **8080** `/health?ready=1`) | TCP/HTTP/HTTPS checks, per‑listener controls                            |
| Balancing                | Round‑robin (default), weighted, least‑connections                     | Weighted rules, L7 features                                             |
| Idle timeout             | Not explicitly documented as with ALB; rely on app/OS keep‑alive       | **50s default**, configurable up to **2h**; supports **TCP keep‑alive** |
| DSR                      | **Yes** (public and private NLB)                                       | No DSR                                                                  |
| Best for CockroachDB SQL | **Yes (recommended)**                                                  | Sometimes (when you specifically need ALB knobs)                        |

Sources: NLB features/methods/DSR and GLB usage; ALB timeouts/keep‑alive/Proxy Protocol. ([IBM Cloud][1])

**Health check target for CockroachDB**
Use the node HTTP endpoint: **`GET http://<node>:8080/health?ready=1`**. CockroachDB documents this in its HAProxy example; IBM NLB supports HTTP path/port health checks, so you can keep the same semantics on IBM Cloud. ([Cockroach Labs][2])

> Why `/health?ready=1`? It returns **200 only when the node is ready to serve SQL**, so the LB won’t send new connections to nodes that are up but draining or not yet ready. ([Cockroach Labs][2])

---

## 3) Failover & failback

### 3.1 Unplanned failover (node/VM down)

1. The LB marks the node unhealthy via health checks (by default in about \~10s with NLB defaults; you can tune interval/thresholds). ([IBM Cloud][5])
2. New SQL connections flow to remaining healthy nodes.
3. CockroachDB automatically rebalances range leases/leadership as needed—no app change required.

**Tip (detection speed):** NLB defaults are 5s interval, 2s timeout, and 2 failures (≈10s to mark unhealthy). If you need faster detection, reduce interval to **5s** and increase failures judiciously; coordinate with CockroachDB’s drain timing (below) to avoid premature cuts during planned work. ([IBM Cloud][5])

### 3.2 Planned maintenance (graceful drain) + clean failback

Coordinate CockroachDB’s **node drain** with LB settings so clients are moved off a node *before* it shuts down:

* Set (cluster‑wide or per maintenance window) **`server.shutdown.initial_wait`** long enough for the LB to observe `/health?ready=1` returning **503** and stop routing to that node. (CockroachDB suggests a value aligned with your LB probe cadence; e.g., if your LB needs \~10–15s to mark down, use ≥ that value.) ([Cockroach Labs][3])
* Optionally set **`server.shutdown.connections.timeout`** to let existing client sessions close gracefully instead of being dropped; and ensure **`server.shutdown.transactions.timeout`** > your longest expected successful transaction. ([Cockroach Labs][3])
* After maintenance, the node becomes **ready** again (`/health?ready=1` returns 200) and the LB resumes sending new connections (**failback**).

> CockroachDB’s “Node Shutdown” doc details the **unready → SQL wait → SQL drain → DistSQL drain** phases and the related cluster settings. Use this to script repeatable maintenance. ([Cockroach Labs][3])

---

## 4) Connection pooling (driver pools or PgBouncer)

**Core rules (from CockroachDB docs):**

* **Connection max lifetime:** start with **\~5 minutes**, then raise toward **30 minutes** if you see tail‑latency impacts (CockroachDB Cloud caps at 30m). **Always add \~10% jitter** to avoid connection storms. ([Cockroach Labs][4])
* **Sizing:** size *active* connections (those concurrently running queries) at roughly **`4 × vCPUs per region`**. Idle connections are cheap; CockroachDB can handle tens of thousands of connections. ([Cockroach Labs][4])
* **Validate connections** in the pool (e.g., HikariCP keepalive/validation) so broken connections are re‑opened before use. ([Cockroach Labs][4])

**In‑process pools** (HikariCP, pgx, etc.) are simpler and lowest‑latency for stateful apps. For microservices swarms or serverless, **external pooling** (e.g., PgBouncer) can help **govern concurrency** across many instances—at the cost of one extra hop. (The CockroachDB page links common examples.) ([Cockroach Labs][4])

> Guardrail: If you use PgBouncer, keep the **aggregate active connections across all PgBouncer instances** within the `4 × vCPUs` rule, and run PgBouncer redundantly but not excessively (you lose cluster‑wide concurrency control if you have too many separate pools).

---

## 5) Timeouts & keep‑alives (end‑to‑end)

* **ALB idle timeouts**: default **50s** (client and server sides), configurable up to **2 hours**. For long‑running SQL statements, raise ALB idle timeouts and/or enable **TCP keep‑alive** so the LB doesn’t cut “quiet” connections mid‑query. ([IBM Cloud][7])
* **NLB**: no specific idle timeout values are documented like ALB’s; rely on **TCP keep‑alive** at the OS/driver level for long‑lived connections. (NLB is L4 pass‑through; avoid app‑layer idleness assumptions.) ([IBM Cloud][1])
* **App/driver**: enable TCP keep‑alive where supported and set **statement/transaction timeouts** appropriate to your SLOs (you can set `sql.defaults.statement_timeout` or per‑session via connection `options`). ([Cockroach Labs][6])

---

## 6) IBM Cloud implementation examples

### 6.1 NLB for CockroachDB (conceptual steps)

1. **Create NLB** (public or private) and a **TCP listener** on **26257**.
2. **Create back‑end pool** with members pointing to CockroachDB node IPs on port **26257**; set **method = round‑robin**.
3. **Health check**: protocol **HTTP**, **port 8080**, **path** `/health?ready=1`; defaults: interval **5s**, timeout **2s**, unhealthy after **2** failures (tune as needed). ([IBM Cloud][1])

> IBM Cloud docs also show how to pair NLBs with **CIS GLB** for multi‑zone HA and traffic steering. ([IBM Cloud][1])

### 6.2 Terraform (VPC NLB sketch)

```hcl
# Provider pinned to your region
provider "ibm" {
  region = "us-south"
}

# NLB (simplified; resource names illustrative)
resource "ibm_is_lb" "crdb_nlb" {
  name    = "crdb-nlb"
  is_public = true
  subnets   = [ibm_is_subnet.app_subnet.id]
}

# Listener on 26257/TCP
resource "ibm_is_lb_listener" "sql_listener" {
  load_balancer = ibm_is_lb.crdb_nlb.id
  port          = 26257
  protocol      = "tcp"
}

# Back-end pool (round-robin) with HTTP health check on 8080 /health?ready=1
resource "ibm_is_lb_pool" "crdb_pool" {
  load_balancer = ibm_is_lb.crdb_nlb.id
  protocol      = "tcp"
  algorithm     = "round_robin"

  health_monitor {
    type     = "http"
    port     = 8080
    url_path = "/health?ready=1"
    interval = 5
    timeout  = 2
    retries  = 2
  }
}

# Pool members: CockroachDB nodes (private IPs), SQL port 26257
resource "ibm_is_lb_pool_member" "node1" {
  pool          = ibm_is_lb_pool.crdb_pool.id
  port          = 26257
  target_address = var.crdb_node1_ip
}
# ...add node2, node3 similarly, one per zone
```

Terraform references: `ibm_is_lb_listener`, `ibm_is_lb_pool` resources. ([Terraform Registry][8])

### 6.3 IBM Cloud CLI (useful for ops)

* List VPC load balancers: `ibmcloud is load-balancers`
* Inspect a specific LB (public IPs, etc.): `ibmcloud is lb <LB_ID>`
  (Shown within IBM’s Kubernetes troubleshooting doc; install the `infrastructure-service`/VPC plugin to use `ibmcloud is ...`.) ([IBM Cloud][9])

---

## 7) Application connection strings

Point applications at the **NLB’s hostname or IP** on **26257** using standard PostgreSQL‑wire URLs, e.g.:

```
postgresql://<user>@<nlb-hostname-or-ip>:26257/<db>?sslmode=verify-full&sslrootcert=...&sslcert=...&sslkey=...
```

See CockroachDB’s “Client Connection Parameters” for driver‑specific URL/DSN formats and TLS options. ([Cockroach Labs][6])

---

## 8) Operational runbooks

### 8.1 Rolling node maintenance (drain & upgrade)

1. **Pre‑check**: NLB health checks are HTTP 8080 `/health?ready=1`.
2. **Set drain timings** (example; tune to your probes):

   ```sql
   SET CLUSTER SETTING server.shutdown.initial_wait = '12s';  -- LB needs ≤ ~10–15s
   SET CLUSTER SETTING server.shutdown.transactions.timeout = '2m'; -- > longest expected txn
   -- Optional if you can't adjust pool lifetimes:
   SET CLUSTER SETTING server.shutdown.connections.timeout = '5m';
   ```

   ([Cockroach Labs][3])
3. **Drain the node** (`cockroach node drain --self`) and monitor until connections drop to zero.
4. Perform node work; restart.
5. **Verify** `/health?ready=1` = 200 and that the LB resumes routing new connections.

### 8.2 Unplanned failures

* Confirm the NLB marked the node unhealthy (LB UI/API).
* Watch CockroachDB range lease movement and app **reconnect** rates; if you see **connection storms**, reduce pool lifetimes and add **\~10% jitter**. ([Cockroach Labs][4])

---

## 9) Observability (what to watch)

* **DB Console → SQL metrics**:

    * **Open SQL Sessions** vs **SQL Connection Attempts/sec**. A healthy pool config typically keeps `ConnectionAttempts/sec < OpenSessions / 100`. ([Cockroach Labs][4])
* **LB metrics**: per‑listener connections and health status (IBM LB dashboards expose active connections per listener/protocol). ([IBM Cloud][10])

---

## 10) IBM Cloud “tips & tricks” applied to CockroachDB

* **Prefer NLB for SQL**: It’s L4, pass‑through, and supports **DSR**, which reduces per‑request latency. Use **round‑robin** to fairly spread *new* connections. ([IBM Cloud][1])
* **If you must use ALB for SQL/TCP**: increase **idle timeouts** (beyond 50s) and **enable TCP keep‑alive** to protect long‑running queries; optionally enable **Proxy Protocol** if you need client IP at the backend (ensure your server stack supports it end‑to‑end). ([IBM Cloud][7])
* **Health checks**: Always target **`/health?ready=1` on 8080**—this is “ready” semantics, not just “alive.” ([Cockroach Labs][2])
* **Coordinate drain with LB**: Use CockroachDB’s **node shutdown** cluster settings so the LB has time to observe “unready” and stop routing traffic *before* connections are forcibly closed. ([Cockroach Labs][3])
* **Throttle reconnections**: Pool **max lifetime** + **10% jitter** prevents thundering herds after failback. ([Cockroach Labs][4])
* **Multi‑zone / Multi‑LB HA**: Combine per‑zone/per‑region NLBs with **CIS GLB** pools for resilient steering when an LB or zone is impaired. ([IBM Cloud][1])

---

## 11) Advanced: client IP visibility through proxies (optional)

If you require **client IP** at the database while using a proxy/LB that rewrites source IPs (e.g., ALB with Proxy Protocol enabled), you may use Proxy Protocol end‑to‑end—but ensure **both sides** are configured correctly (LB sends it, server accepts it). Because this is a low‑level TCP feature, validate support and behavior in your exact CockroachDB version and test thoroughly in lower environments. (CockroachDB’s HAProxy examples and industry docs explain the mechanism and caveats.) ([Cockroach Labs][2])

---

## Appendix A — Pinned sources

* CockroachDB **HAProxy** example (health check `/health?ready=1`): shows the canonical health probe you should mirror with IBM LB. ([Cockroach Labs][2])
* CockroachDB **Node Shutdown** (drain/failover coordination): phases and cluster settings. ([Cockroach Labs][3])
* CockroachDB **Connection Pooling** (max lifetime, jitter, 4×vCPU rule, idle conn guidance). ([Cockroach Labs][4])
* IBM Cloud **NLB** (L4, DSR, load‑balancing methods, GLB integration). ([IBM Cloud][1])
* IBM Cloud **ALB** advanced management (timeouts, HTTP/TCP keep‑alive, Proxy Protocol). ([IBM Cloud][7])

---

[1]: https://cloud.ibm.com/docs/vpc?topic=vpc-network-load-balancers "IBM Cloud Docs"
[2]: https://www.cockroachlabs.com/docs/stable/deploy-cockroachdb-on-premises "Deploy CockroachDB On-Premises"
[3]: https://www.cockroachlabs.com/docs/stable/node-shutdown "Node Shutdown"
[4]: https://www.cockroachlabs.com/docs/stable/connection-pooling "Connection Pooling"
[5]: https://cloud.ibm.com/docs/vpc?topic=vpc-nlb-health-checks "IBM Cloud Docs"
[6]: https://www.cockroachlabs.com/docs/stable/connection-parameters "Client Connection Parameters"
[7]: https://cloud.ibm.com/docs/vpc?amp%3Blocale=en&topic=vpc-advanced-traffic-management "IBM Cloud Docs"
[8]: https://registry.terraform.io/providers/IBM-Cloud/ibm/1.82.1/docs/resources/is_lb_listener "ibm_is_lb_listener | Resources | IBM-Cloud/ibm - Terraform Registry"
[9]: https://cloud.ibm.com/docs/containers?topic=containers-vpc_ts_lb "IBM Cloud Docs"
[10]: https://cloud.ibm.com/media/docs/pdf/loadbalancer-service/loadbalancer-service.pdf "IBM Cloud Load Balancer"
