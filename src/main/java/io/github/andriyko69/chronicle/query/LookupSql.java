/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.query;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public record LookupSql(String sql, List<Object> parameters) {
    private static final String NEWEST_FIRST = " ORDER BY occurred_at_sec DESC, occurred_at_nanos DESC, event_id DESC";
    private static final String OLDEST_FIRST = " ORDER BY occurred_at_sec ASC, occurred_at_nanos ASC, event_id ASC";
    public LookupSql { parameters = List.copyOf(parameters); }
    public static LookupSql page(LookupSpec spec, int page) {
        var query = filtered(spec);
        query.sql.append(NEWEST_FIRST).append(" LIMIT ? OFFSET ?");
        query.parameters.add(LookupSpec.PAGE_SIZE + 1); query.parameters.add(LookupSpec.offset(page));
        return new LookupSql(query.sql.toString(), query.parameters);
    }
    /** Rollback undoes the newest change first; restore re-applies the oldest first. */
    public static LookupSql candidates(LookupSpec spec, boolean rolledBack, int limit) {
        var query = filtered(spec);
        query.sql.append(" AND rolled_back = ?");
        query.parameters.add(rolledBack ? 1 : 0);
        query.sql.append(rolledBack ? OLDEST_FIRST : NEWEST_FIRST).append(" LIMIT ?");
        query.parameters.add(limit);
        return new LookupSql(query.sql.toString(), query.parameters);
    }
    /** One page of the rows at one coordinate from {@code from} on, oldest first; reaches the position index by equality. */
    public static LookupSql position(String worldId, int x, int y, int z, Instant from, int limit, int offset) {
        return new LookupSql("SELECT * FROM audit_events WHERE world_id = ? AND x = ? AND z = ? AND y = ? AND occurred_at_sec >= ?"
                + " AND (occurred_at_sec > ? OR occurred_at_nanos >= ?)" + OLDEST_FIRST + " LIMIT ? OFFSET ?",
                List.of(worldId, x, z, y, from.getEpochSecond(), from.getEpochSecond(), from.getNano(), limit, offset));
    }
    private record Query(StringBuilder sql, List<Object> parameters) {}
    private static Query filtered(LookupSpec spec) {
        var sql = new StringBuilder("SELECT * FROM audit_events WHERE occurred_at_sec >= ? AND (occurred_at_sec > ? OR occurred_at_nanos >= ?)");
        var parameters = new ArrayList<Object>();
        parameters.add(spec.since().getEpochSecond()); parameters.add(spec.since().getEpochSecond()); parameters.add(spec.since().getNano());
        if (spec.dimension() != null) { sql.append(" AND world_id = ?"); parameters.add(spec.dimension()); }
        var bounds = spec.bounds();
        if (bounds != null) {
            axis(sql, parameters, "x", bounds.minX(), bounds.maxX());
            axis(sql, parameters, "y", bounds.minY(), bounds.maxY());
            axis(sql, parameters, "z", bounds.minZ(), bounds.maxZ());
        }
        if (!spec.actors().isEmpty()) {
            var clauses = new ArrayList<String>();
            for (var actor : spec.actors()) {
                if (actor.fakePlayer()) { clauses.add("actor_type = ?"); parameters.add("FAKE_PLAYER"); }
                // A user matches both what they did directly and environmental events they caused (root actor).
                else if (actor.uuid() != null) {
                    clauses.add("actor_uuid = ? OR root_actor_uuid = ?"); parameters.add(actor.uuid()); parameters.add(actor.uuid());
                } else {
                    clauses.add("actor_id = ? OR root_actor_uuid IN (SELECT actor_uuid FROM actor_profiles WHERE actor_id = ?)");
                    parameters.add(actor.name()); parameters.add(actor.name());
                }
            }
            sql.append(" AND (").append(String.join(" OR ", clauses)).append(")");
        }
        in(sql, parameters, "target_id", spec.targets());
        in(sql, parameters, "event_type", spec.actions());
        return new Query(sql, parameters);
    }
    // A BETWEEN, even with equal ends, is a range to SQLite and stops index seeks; equality reaches the time order.
    private static void axis(StringBuilder sql, List<Object> parameters, String column, int min, int max) {
        if (min == max) { sql.append(" AND ").append(column).append(" = ?"); parameters.add(min); }
        else { sql.append(" AND ").append(column).append(" BETWEEN ? AND ?"); parameters.add(min); parameters.add(max); }
    }
    private static void in(StringBuilder sql, List<Object> parameters, String column, List<String> values) {
        if (values.isEmpty()) return;
        sql.append(" AND ").append(column).append(" IN (").append(String.join(",", java.util.Collections.nCopies(values.size(), "?"))).append(")");
        parameters.addAll(values);
    }
}
