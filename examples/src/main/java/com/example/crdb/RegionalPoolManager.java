package com.example.crdb;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * RegionalPoolManager:
 * - One warm Hikari pool per region (point each at the region's NLB/FQDN)
 * - Choose the lowest-latency region as primary
 * - Borrow from primary, fail over to next if errors
 * - Periodically re-probe latency to allow gentle failback
 * - Jittered maxLifetime to avoid reconnect storms
 *
 * Env:
 *   CRDB_USER, CRDB_PASSWORD (optional fallbacks in code)
 */
public final class RegionalPoolManager implements AutoCloseable {

    public static final class Region {
        public final String name;
        public final String jdbcUrl; // e.g., jdbc:postgresql://us-east.example.com:26257/db?sslmode=verify-full
        public Region(String name, String jdbcUrl) { this.name = name; this.jdbcUrl = jdbcUrl; }
    }

    private final List<Region> regions;
    private final Map<String, HikariDataSource> pools = new HashMap<>();
    private volatile String primaryRegion;

    private final String user = Objects.requireNonNullElse(System.getenv("CRDB_USER"), "appuser");
    private final String pass = Objects.requireNonNullElse(System.getenv("CRDB_PASSWORD"), "");

    public RegionalPoolManager(List<Region> regions) {
        if (regions == null || regions.isEmpty()) {
            throw new IllegalArgumentException("At least one region required");
        }
        this.regions = List.copyOf(regions);

        // 1) Warm pools per region
        for (Region r : this.regions) {
            pools.put(r.name, buildPool(r.jdbcUrl));
        }

        // 2) Pick nearest by connect+ping latency
        primaryRegion = chooseLowestLatencyRegion();
        System.out.println("[RegionalPoolManager] Primary region: " + primaryRegion);

        // 3) Periodic re-probe for graceful failback
        Thread t = new Thread(this::periodicReprobe, "crdb-region-reprobe");
        t.setDaemon(true);
        t.start();
    }

    private HikariDataSource buildPool(String jdbcUrl) {
        HikariConfig cfg = new HikariConfig();

        // If you use your cockroachdb-jdbc-wrapper as a DataSource:
        //   WrappedDataSource wrapped = new WrappedDataSource(jdbcUrl, user, pass, /* wrapper options */);
        //   cfg.setDataSource(wrapped);
        // else use URL directly:
        cfg.setJdbcUrl(jdbcUrl);
        cfg.setUsername(user);
        cfg.setPassword(pass);

        // Size active concurrency relative to CPU budget (aggregate across regions).
        cfg.setMaximumPoolSize(16);
        cfg.setMinimumIdle(4);

        // Avoid reconnect storms: 20m ±10% jitter
        long base = Duration.ofMinutes(20).toMillis();
        long jitter = Math.round(base * (randBetween(0.90, 1.10) - 1.0));
        cfg.setMaxLifetime(base + jitter);

        cfg.setIdleTimeout(Duration.ofMinutes(10).toMillis());
        cfg.setConnectionTimeout(Duration.ofSeconds(30).toMillis());
        cfg.setKeepaliveTime(Duration.ofMinutes(5).toMillis()); // driver-level ping

        // Optional validation; modern drivers typically don't need this:
        // cfg.setConnectionTestQuery("SELECT 1");

        return new HikariDataSource(cfg);
    }

    private static double randBetween(double lo, double hi) {
        return lo + ThreadLocalRandom.current().nextDouble() * (hi - lo);
    }

    /** Measure connect+ping latency from each pool; pick the lowest. */
    private String chooseLowestLatencyRegion() {
        long best = Long.MAX_VALUE;
        String bestName = regions.get(0).name;
        for (Region r : regions) {
            long t0 = System.nanoTime();
            try (Connection c = pools.get(r.name).getConnection();
                 Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT 1")) {
                long dt = System.nanoTime() - t0;
                if (dt < best) { best = dt; bestName = r.name; }
            } catch (SQLException e) {
                // Treat as very slow so it won't win
            }
        }
        return bestName;
    }

    /** Prefer the primary pool; on failure, try others (and promote). */
    public Connection getConnection() throws SQLException {
        List<String> order = new ArrayList<>();
        order.add(primaryRegion);
        for (Region r : regions) if (!r.name.equals(primaryRegion)) order.add(r.name);

        SQLException first = null;
        for (String name : order) {
            try {
                return pools.get(name).getConnection();
            } catch (SQLException e) {
                if (first == null) first = e;
                System.err.println("[RegionalPoolManager] Pool unavailable for " + name + ": " + e.getMessage());
                if (name.equals(primaryRegion) && order.size() > 1) {
                    primaryRegion = order.get(1);
                    System.out.println("[RegionalPoolManager] Failover: new primary " + primaryRegion);
                }
            }
        }
        throw first != null ? first : new SQLException("No pools available");
    }

    /** Allow graceful failback by periodically re-evaluating latency. */
    private void periodicReprobe() {
        try {
            while (true) {
                Thread.sleep(Duration.ofMinutes(2).toMillis());
                String best = chooseLowestLatencyRegion();
                if (!best.equals(primaryRegion)) {
                    // Gentle: future borrows go to the new primary; in-flight sessions are unaffected.
                    System.out.println("[RegionalPoolManager] Failback candidate: " + best + " (promoting)");
                    primaryRegion = best;
                }
            }
        } catch (InterruptedException ignore) { }
    }

    // Optional: convenience wrapper with retry + per-session settings
    public <T> T withRetry(SqlOp<T> op) throws Exception {
        int maxRetries = 45;
        for (int i = 1; i <= maxRetries; i++) {
            try (Connection c = getConnection();
                 Statement s = c.createStatement()) {
                // Per-session statement timeout (10s)
                s.execute("SET statement_timeout = 10000");
                return op.run(c);
            } catch (SQLTransientException | SQLRecoverableException e) {
                Thread.sleep(1000L);
            }
        }
        throw new Exception("DB operation failed after retries");
    }

    @FunctionalInterface public interface SqlOp<T> { T run(Connection c) throws Exception; }

    @Override public void close() {
        pools.values().forEach(HikariDataSource::close);
    }
}
