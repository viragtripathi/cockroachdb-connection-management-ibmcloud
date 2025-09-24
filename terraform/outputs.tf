output "nlb_connection_string_hint" {
  value = "postgresql://<user>@${ibm_is_lb.crdb_nlb.hostname}:26257/<db>?sslmode=verify-full"
}