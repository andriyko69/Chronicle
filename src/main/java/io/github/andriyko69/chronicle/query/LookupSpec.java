/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.query;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record LookupSpec(Instant since, String dimension, Bounds bounds,
        List<Actor> actors, List<String> targets, List<String> actions) {
    public static final int PAGE_SIZE = 8;
    public static final int MAX_PAGE = 10_000;
    public record Center(int x, int y, int z) {}
    public record Bounds(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        public Bounds {
            if (minX > maxX || minY > maxY || minZ > maxZ) throw new IllegalArgumentException("Invalid bounds");
        }
        public static Bounds around(Center center, int radius) {
            if (radius < 0 || radius > 100) throw new IllegalArgumentException("Radius must be 0..100");
            return new Bounds(lower(center.x(), radius), upper(center.x(), radius),
                    lower(center.y(), radius), upper(center.y(), radius), lower(center.z(), radius), upper(center.z(), radius));
        }
        private static int lower(int value, int radius) { return (int)Math.max(Integer.MIN_VALUE, (long)value - radius); }
        private static int upper(int value, int radius) { return (int)Math.min(Integer.MAX_VALUE, (long)value + radius); }
    }
    public record Actor(String name, UUID uuid, boolean fakePlayer) {
        public Actor {
            if ((name != null ? 1 : 0) + (uuid != null ? 1 : 0) + (fakePlayer ? 1 : 0) != 1)
                throw new IllegalArgumentException("Actor must select one name, UUID or fake-player type");
        }
    }
    public LookupSpec {
        Objects.requireNonNull(since);
        actors = List.copyOf(actors); targets = List.copyOf(targets); actions = List.copyOf(actions);
        if (bounds != null && dimension == null) throw new IllegalArgumentException("Spatial lookup needs dimension");
        if (actors.size() > 16 || targets.size() > 16 || actions.size() > 4) throw new IllegalArgumentException("Too many filters");
    }
    public static int offset(int page) {
        if (page < 1 || page > MAX_PAGE) throw new IllegalArgumentException("Page must be 1.." + MAX_PAGE);
        return (page - 1) * PAGE_SIZE;
    }
}
