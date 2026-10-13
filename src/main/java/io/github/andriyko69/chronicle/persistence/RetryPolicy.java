/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.persistence;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

public record RetryPolicy(Duration minimum, Duration maximum) {
    public long delayNanos(int failures) {
        long base = minimum.toNanos();
        long cap = maximum.toNanos();
        for (int i = 1; i < Math.min(failures, 63) && base < cap; i++) base = Math.min(cap, base > cap / 2 ? cap : base * 2);
        base = Math.min(cap, base);
        return ThreadLocalRandom.current().nextLong(Math.max(1, base / 2), base + 1);
    }
}
