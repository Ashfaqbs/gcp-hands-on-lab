package com.ashfaq.gcplab._05_redis;

import redis.clients.jedis.Jedis;

import java.util.List;

/**
 * CRUD against Memorystore for Redis, reached through the SSH tunnel to
 * redis-bastion (localhost:6379 -> tunnel -> 10.23.23.35:6379). Uses Jedis,
 * a plain synchronous Redis client - Memorystore speaks the real Redis
 * wire protocol, nothing GCP-specific about the client side.
 *
 * No AUTH required - despite intending to enable it at creation, the
 * instance came up with authEnabled unset/false (confirmed via
 * `gcloud redis instances describe ... --format="yaml(authEnabled)"`
 * returning nothing). Fine for this throwaway learning instance; a real
 * deployment should enable AUTH (and ideally in-transit encryption too).
 *
 * Run with (tunnel must be active first - see package-info):
 *   mvn exec:java -Dexec.mainClass=com.ashfaq.gcplab._05_redis.CacheCrudDemo -Dexec.args=create
 *   ... -Dexec.args=read
 *   ... -Dexec.args=update
 *   ... -Dexec.args=delete
 */
public final class CacheCrudDemo {

    private static final String HOST = "localhost"; // tunnel endpoint, not Redis's real private IP
    private static final int PORT = 6379;
    private static final String KEY = "employee:1:name";

    private CacheCrudDemo() {
    }

    public static void main(String[] args) {
        if (args.length != 1 || !List.of("create", "read", "update", "delete").contains(args[0])) {
            System.out.println("Usage: CacheCrudDemo <create|read|update|delete>");
            return;
        }

        try (Jedis jedis = new Jedis(HOST, PORT)) {
            switch (args[0]) {
                case "create" -> create(jedis);
                case "read" -> read(jedis);
                case "update" -> update(jedis);
                case "delete" -> delete(jedis);
                default -> throw new IllegalStateException("unreachable");
            }
        }
    }

    private static void create(Jedis jedis) {
        // SET with an expiry (TTL) - unlike a DB row, cache entries should almost always have one.
        jedis.setex(KEY, 300, "Ashfaq"); // expires in 300s
        System.out.println("SET " + KEY + " = Ashfaq (TTL 300s)");
    }

    private static void read(Jedis jedis) {
        String value = jedis.get(KEY);
        long ttl = jedis.ttl(KEY);
        System.out.println(value == null
                ? "GET " + KEY + " -> (not found / expired)"
                : "GET " + KEY + " -> " + value + " (TTL remaining: " + ttl + "s)");
    }

    private static void update(Jedis jedis) {
        // Redis has no separate "update" verb - SET overwrites, same as create.
        jedis.setex(KEY, 300, "Ashfaq (Senior)");
        System.out.println("Overwrote " + KEY + " = Ashfaq (Senior), TTL reset to 300s");
    }

    private static void delete(Jedis jedis) {
        long removed = jedis.del(KEY);
        System.out.println(removed > 0
                ? "DEL " + KEY + " -> removed"
                : "DEL " + KEY + " -> key did not exist");
    }
}
