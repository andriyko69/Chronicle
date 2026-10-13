/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;

public record InfrastructureConfig(Path database, Path journal, int ingressCapacity, int batchSize,
        Duration batchMaxAge, Duration syncInterval, Duration retryMin, Duration retryMax,
        Duration shutdownGrace, int busyTimeoutMillis, int retainedCycles) {
    public InfrastructureConfig {
        if (database == null || journal == null || ingressCapacity < 1 || batchSize < 1 || batchSize > 1000
                || busyTimeoutMillis < 1 || retainedCycles < 0) throw new IllegalArgumentException("Invalid infrastructure configuration");
        for (Duration duration : new Duration[]{batchMaxAge, syncInterval, retryMin, retryMax, shutdownGrace})
            if (duration == null || duration.isNegative() || duration.isZero() || duration.compareTo(Duration.ofMinutes(5)) > 0)
                throw new IllegalArgumentException("Durations must be positive and at most five minutes");
        if (retryMin.compareTo(retryMax) > 0) throw new IllegalArgumentException("Retry minimum exceeds cap");
        if (database.toAbsolutePath().normalize().startsWith(journal.toAbsolutePath().normalize()))
            throw new IllegalArgumentException("Database must be outside the journal directory");
    }
    public static InfrastructureConfig defaults(Path root) {
        return new InfrastructureConfig(root.resolve("audit.sqlite"), root.resolve("journal"), 8192, 100,
                Duration.ofMillis(25), Duration.ofMillis(50), Duration.ofMillis(50), Duration.ofSeconds(5),
                Duration.ofSeconds(10), 100, 2);
    }
    public static InfrastructureConfig load(Path root) throws IOException {
        var properties = new Properties();
        Path file = root.resolve("chronicle.properties");
        if (Files.exists(file)) try (var input = Files.newInputStream(file)) { properties.load(input); }
        if (!properties.getProperty("storage.backend", "sqlite").equals("sqlite"))
            throw new IllegalArgumentException("Only SQLite is implemented");
        return new InfrastructureConfig(root.resolve(properties.getProperty("sqlite.path", "audit.sqlite")),
                root.resolve(properties.getProperty("journal.path", "journal")),
                integer(properties, "ingress.capacity", 8192), integer(properties, "batch.size", 100),
                millis(properties, "batch.maxAgeMillis", 25), millis(properties, "journal.syncMillis", 50),
                millis(properties, "retry.minMillis", 50), millis(properties, "retry.maxMillis", 5000),
                millis(properties, "shutdown.graceMillis", 10000), integer(properties, "sqlite.busyMillis", 100),
                integer(properties, "journal.retainedCycles", 2));
    }
    private static int integer(Properties properties, String key, int fallback) {
        return Integer.parseInt(properties.getProperty(key, Integer.toString(fallback)));
    }
    private static Duration millis(Properties properties, String key, int fallback) {
        return Duration.ofMillis(integer(properties, key, fallback));
    }
}
