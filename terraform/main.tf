terraform {
  required_version = ">= 1.5.0"
  required_providers {
    ibm = {
      source  = "IBM-Cloud/ibm"
      version = ">= 1.63.0"
    }
  }
}

provider "ibm" {
  region = var.region
}

resource "ibm_is_lb" "crdb_nlb" {
  name      = var.lb_name
  is_public = var.is_public
  # put the NLB in your target subnets (multi-AZ recommended)
  subnets   = var.subnet_ids
  tags      = var.tags
}

resource "ibm_is_lb_listener" "sql_listener" {
  load_balancer = ibm_is_lb.crdb_nlb.id
  protocol      = "tcp"
  port          = 26257
}

resource "ibm_is_lb_pool" "crdb_pool" {
  load_balancer = ibm_is_lb.crdb_nlb.id
  protocol      = "tcp"
  algorithm     = "round_robin"

  health_monitor {
    type     = "http"
    port     = 8080
    url_path = "/health?ready=1"
    interval = var.health_interval_seconds  # e.g., 5
    timeout  = var.health_timeout_seconds   # e.g., 2
    retries  = var.health_failures          # e.g., 2 (mark down after 2 fails)
  }

  depends_on = [ibm_is_lb_listener.sql_listener]
}

# Add each CockroachDB node as a pool member
resource "ibm_is_lb_pool_member" "members" {
  for_each      = toset(var.crdb_member_ips)
  pool          = ibm_is_lb_pool.crdb_pool.id
  target_address = each.value
  port          = 26257
}

output "nlb_id" {
  value = ibm_is_lb.crdb_nlb.id
}

output "nlb_hostname" {
  value = ibm_is_lb.crdb_nlb.hostname
}

output "nlb_public_ips" {
  value = ibm_is_lb.crdb_nlb.public_ips
}