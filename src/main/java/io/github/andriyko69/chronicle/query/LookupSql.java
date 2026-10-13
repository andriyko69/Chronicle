/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.query;

import java.util.ArrayList;
import java.util.List;

public record LookupSql(String sql, List<Object> parameters) {
    public LookupSql { parameters = List.copyOf(parameters); }
    public static LookupSql page(LookupSpec spec, int page) {
        var sql = new StringBuilder("SELECT * FROM audit_events WHERE occurred_at_sec >= ? AND (occurred_at_sec > ? OR occurred_at_nanos >= ?)");
        var parameters = new ArrayList<Object>();
        parameters.add(spec.since().getEpochSecond()); parameters.add(spec.since().getEpochSecond()); parameters.add(spec.since().getNano());
        if (spec.dimension() != null) { sql.append(" AND world_id = ?"); parameters.add(spec.dimension()); }
        var bounds = spec.bounds();
        if (bounds != null) {
            sql.append(" AND x BETWEEN ? AND ? AND y BETWEEN ? AND ? AND z BETWEEN ? AND ?");
            parameters.add(bounds.minX()); parameters.add(bounds.maxX()); parameters.add(bounds.minY()); parameters.add(bounds.maxY());
            parameters.add(bounds.minZ()); parameters.add(bounds.maxZ());
        }
        if (!spec.actors().isEmpty()) {
            var clauses = new ArrayList<String>();
            for (var actor : spec.actors()) {
                if (actor.fakePlayer()) { clauses.add("actor_type = ?"); parameters.add("FAKE_PLAYER"); }
                else if (actor.uuid() != null) { clauses.add("actor_uuid = ?"); parameters.add(actor.uuid()); }
                else { clauses.add("actor_id = ?"); parameters.add(actor.name()); }
            }
            sql.append(" AND (").append(String.join(" OR ", clauses)).append(")");
        }
        in(sql, parameters, "target_id", spec.targets());
        in(sql, parameters, "event_type", spec.actions());
        sql.append(" ORDER BY occurred_at_sec DESC, occurred_at_nanos DESC, event_id DESC LIMIT ? OFFSET ?");
        parameters.add(LookupSpec.PAGE_SIZE + 1); parameters.add(LookupSpec.offset(page));
        return new LookupSql(sql.toString(), parameters);
    }
    private static void in(StringBuilder sql, List<Object> parameters, String column, List<String> values) {
        if (values.isEmpty()) return;
        sql.append(" AND ").append(column).append(" IN (").append(String.join(",", java.util.Collections.nCopies(values.size(), "?"))).append(")");
        parameters.addAll(values);
    }
}
