-- https://www.cockroachlabs.com/docs/stable/node-shutdown
-- Graceful node shutdown timings (tune to your LB probe cadence & app SLOs)
-- initial_wait: time to advertise unready so LB stops routing before cutting connections
SET CLUSTER SETTING server.shutdown.initial_wait = '12s';

-- let in-flight transactions finish where possible
SET CLUSTER SETTING server.shutdown.transactions.timeout = '2m';

-- optionally allow time for clients/pools to recycle connections gracefully
SET CLUSTER SETTING server.shutdown.connections.timeout = '5m';

-- Optional: default statement timeout guardrail (adjust or set per session)
-- SET CLUSTER SETTING sql.defaults.statement_timeout = '0s'; -- 0 = no default timeout