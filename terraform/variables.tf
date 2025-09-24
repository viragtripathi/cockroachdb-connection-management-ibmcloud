variable "region" {
  type        = string
  description = "IBM Cloud region (e.g., us-south)"
}

variable "lb_name" {
  type        = string
  default     = "crdb-nlb"
  description = "Name for the NLB"
}

variable "is_public" {
  type        = bool
  default     = true
  description = "Public (true) or private (false) NLB"
}

variable "subnet_ids" {
  type        = list(string)
  description = "List of subnet IDs (multi-AZ recommended)"
}

variable "crdb_member_ips" {
  type        = list(string)
  description = "Private IPs of CockroachDB nodes"
}

variable "health_interval_seconds" {
  type        = number
  default     = 5
}

variable "health_timeout_seconds" {
  type        = number
  default     = 2
}

variable "health_failures" {
  type        = number
  default     = 2
}

variable "tags" {
  type        = list(string)
  default     = []
}