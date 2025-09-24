#!/usr/bin/env bash
set -euo pipefail

# Minimal helper to create an NLB + listener + pool + members for CockroachDB.
# Requires `ibmcloud` CLI (with VPC plugin: `ibmcloud is ...`) and `jq`. Login first: `ibmcloud login` (or `ibmcloud login --sso`).


usage() {
  cat <<EOF
Usage: $0 --name <lb-name> --subnet-id <subnet-id> --is-public <true|false> --members <ip1,ip2,ip3> [--region us-south]
Example:
  $0 --name crdb-nlb --subnet-id r006-... --is-public true --members 10.0.1.10,10.0.2.10,10.0.3.10 --region us-south
EOF
}

LB_NAME=""
SUBNET_ID=""
IS_PUBLIC="true"
MEMBERS=""
REGION="${REGION:-us-south}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --name) LB_NAME="$2"; shift 2;;
    --subnet-id) SUBNET_ID="$2"; shift 2;;
    --is-public) IS_PUBLIC="$2"; shift 2;;
    --members) MEMBERS="$2"; shift 2;;
    --region) REGION="$2"; shift 2;;
    -h|--help) usage; exit 0;;
    *) echo "Unknown arg: $1"; usage; exit 1;;
  esac
done

[[ -z "$LB_NAME" || -z "$SUBNET_ID" || -z "$MEMBERS" ]] && { usage; exit 1; }

echo "Target region: $REGION"
ibmcloud target -r "$REGION" >/dev/null

echo "Creating NLB..."
LB_JSON=$(ibmcloud is lb-create "$LB_NAME" "$IS_PUBLIC" --subnets "$SUBNET_ID" --output json)
LB_ID=$(echo "$LB_JSON" | jq -r .id)

echo "Creating listener TCP/26257..."
LISTENER_JSON=$(ibmcloud is lb-listener-add "$LB_ID" --protocol tcp --port 26257 --output json)
LISTENER_ID=$(echo "$LISTENER_JSON" | jq -r .id)

echo "Creating pool (round_robin, HTTP health check 8080 /health?ready=1)..."
POOL_JSON=$(ibmcloud is lb-pool-add "$LB_ID" --protocol tcp --algorithm round_robin --health-probe http \
  --health-port 8080 --health-url-path "/health?ready=1" --health-interval 5 --health-timeout 2 --health-retries 2 \
  --output json)
POOL_ID=$(echo "$POOL_JSON" | jq -r .id)

IFS=',' read -ra ADDRS <<< "$MEMBERS"
for ip in "${ADDRS[@]}"; do
  echo "Adding member $ip:26257"
  ibmcloud is lb-pool-member-add "$LB_ID" "$POOL_ID" --target-address "$ip" --port 26257 >/dev/null
done

echo "Done."
ibmcloud is lb "$LB_ID"
