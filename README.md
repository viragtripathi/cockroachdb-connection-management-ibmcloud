# CockroachDB Connection Management on IBM Cloud

This package provides a practical, IBM-Cloud–focused setup for:
- L4 load balancing with IBM Cloud VPC **Network Load Balancer (NLB)**
- Health checks against CockroachDB’s **/health?ready=1** endpoint on port **8080**
- Graceful failover/failback using CockroachDB **node drain** settings
- Connection pooling recommendations (HikariCP / PgBouncer)
- Terraform example + IBM Cloud CLI helper

> Works with CockroachDB secure clusters using TLS end-to-end (LB is L4 pass-through).

## Contents

- `connection-management-ibmcloud.md` — Detailed guide/runbook.
- `terraform/` — Deploys an IBM Cloud VPC NLB in front of CockroachDB nodes.
- `cli/ibmcloud-nlb.sh` — Minimal IBM Cloud CLI helper to create an NLB + pool.
- `examples/hikari.properties` — Sensible pool defaults with maxLifetime + jitter.
- `examples/pgbouncer.ini` — Example PgBouncer config for CockroachDB.
- `sql/cluster-settings.sql` — Recommended node shutdown/drain cluster settings.
- `runbooks/rolling-maintenance.md` — Step-by-step for planned maintenance.

## Quick start (Terraform)

```bash
cd terraform
# Export IBM Cloud creds as needed before running terraform init/plan/apply.
terraform init
terraform plan -var 'region=us-south' \
               -var 'vpc_id=vpc-xxxxxxxx' \
               -var 'subnet_ids=["subnet-aaa","subnet-bbb"]' \
               -var 'crdb_member_ips=["10.0.1.10","10.0.2.10","10.0.3.10"]' \
               -var 'is_public=true'
terraform apply
````

The module creates:

* An **NLB** with listener **TCP/26257**
* A **pool** with **round\_robin** and **HTTP** health monitor on **8080** `/health?ready=1`
* Pool members pointing at your CockroachDB nodes (**26257**)

## Quick start (IBM Cloud CLI)

```bash
cd cli
./ibmcloud-nlb.sh \
  --name crdb-nlb \
  --subnet-id <subnet-id> \
  --is-public true \
  --members 10.0.1.10,10.0.2.10,10.0.3.10
```

## Connection string (apps)

Point apps at the NLB **hostname/IP** on **26257**:

```
postgresql://<user>@<nlb-host-or-ip>:26257/<db>?sslmode=verify-full&sslrootcert=...&sslcert=...&sslkey=...
```

---
