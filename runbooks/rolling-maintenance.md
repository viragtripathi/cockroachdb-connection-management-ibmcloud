# Rolling Node Maintenance (IBM Cloud NLB + CockroachDB)

## Preconditions
- NLB health checks point to `http://<node>:8080/health?ready=1`
- Cluster settings set per `sql/cluster-settings.sql`

## Steps (per node)
1. **Quiesce**:
    - `cockroach node drain --self --insecure=false`
    - Verify `/health?ready=1` returns non-200; NLB should mark member unhealthy within ~10–15s.
2. **Watch connections**:
    - DB Console: connections decline to ~0 for that node.
3. **Upgrade / OS work**:
    - Apply changes and restart the node.
4. **Return to service**:
    - Verify `/health?ready=1` → 200 and NLB member healthy.
    - Move to next node.

## Post-checks
- No sustained spike in `Connection Attempts/sec`.
- Latency within SLOs.