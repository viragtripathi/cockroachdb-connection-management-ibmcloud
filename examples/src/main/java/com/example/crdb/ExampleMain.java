package com.example.crdb;

import java.util.List;

public final class ExampleMain {

    public static void main(String[] args) throws Exception {
        // Replace with your regional NLB hostnames (sslmode=verify-full strongly recommended)
        var regions = List.of(
                new RegionalPoolManager.Region("us-west-2", "jdbc:postgresql://west2.example.com:26257/roachshop_demo?sslmode=verify-full"),
                new RegionalPoolManager.Region("us-central-1", "jdbc:postgresql://central1.example.com:26257/roachshop_demo?sslmode=verify-full"),
                new RegionalPoolManager.Region("us-east-1", "jdbc:postgresql://east1.example.com:26257/roachshop_demo?sslmode=verify-full")
        );

        try (var mgr = new RegionalPoolManager(regions)) {
            // Simple read
            String version = mgr.withRetry(conn -> {
                try (var ps = conn.prepareStatement("select version()")) {
                    try (var rs = ps.executeQuery()) {
                        rs.next();
                        return rs.getString(1);
                    }
                }
            });
            System.out.println("Connected. Server version: " + version);

            // Example write (adjust table/SQL to your schema)
            Integer one = mgr.withRetry(conn -> {
                try (var ps = conn.prepareStatement("select 1")) {
                    try (var rs = ps.executeQuery()) {
                        rs.next();
                        return rs.getInt(1);
                    }
                }
            });
            System.out.println("Query result: " + one);
        }
    }
}
