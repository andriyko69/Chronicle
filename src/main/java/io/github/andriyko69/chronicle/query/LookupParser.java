/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.query;

import io.github.andriyko69.chronicle.event.ChronicleClock;
import io.github.andriyko69.chronicle.event.EventEnvelope;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

public final class LookupParser {
    private static final Pattern DURATION = Pattern.compile("([0-9]+)([smhdw])");
    private static final Pattern RESOURCE = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");
    private static final Pattern UUID_TEXT = Pattern.compile("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");
    public record Context(String dimension, LookupSpec.Center position) {
        public Context {
            if ((dimension == null) != (position == null)) throw new IllegalArgumentException("Incomplete player context");
        }
        public static Context console() { return new Context(null, null); }
    }
    public record Rollback(LookupSpec spec, boolean preview) {}
    /** Everything strictly before {@code cutoff}'s second is purged. */
    public record Purge(Duration olderThan, java.time.Instant cutoff, boolean preview, boolean optimize) {}
    /**
     * A container's spill rows settle at the end of the tick that removed it, so a younger cutoff could separate a break
     * from spills still being recorded. A minute also keeps a typo such as t:1s from wiping the whole log.
     */
    public static final Duration MIN_PURGE_AGE = Duration.ofMinutes(1);
    private final ChronicleClock clock;
    public LookupParser(ChronicleClock clock) { this.clock = Objects.requireNonNull(clock); }

    /** Only an age: purges are global and never filtered by place or actor, which would leave partial histories. */
    public Purge parsePurge(String query) {
        if (query == null || query.length() > 256) throw syntax("Purge arguments must be at most 256 characters");
        Duration age = null;
        boolean preview = false, optimize = false;
        for (String token : query.isBlank() ? new String[0] : query.strip().split("\\s+")) {
            switch (token) {
                case "--preview", "preview" -> { if (preview) throw syntax("Duplicate preview flag"); preview = true; }
                case "--optimize", "optimize" -> { if (optimize) throw syntax("Duplicate optimize flag"); optimize = true; }
                default -> {
                    if (!token.startsWith("t:") && !token.startsWith("time:"))
                        throw syntax("Unsupported purge argument '" + token + "'; use t:<age> [--preview] [--optimize]");
                    if (age != null) throw syntax("Duplicate time filter");
                    age = duration(token.substring(token.indexOf(':') + 1));
                }
            }
        }
        if (age == null) throw syntax("Purge requires an explicit age, e.g. /ch purge t:30d");
        if (age.compareTo(MIN_PURGE_AGE) < 0) throw syntax("Purge age must be at least " + MIN_PURGE_AGE.toSeconds() + "s");
        try { return new Purge(age, clock.now().minus(age), preview, optimize); }
        catch (java.time.DateTimeException | ArithmeticException failure) { throw syntax("Time window outside supported range"); }
    }

    public LookupSpec parse(String query, Context context) { return parse(query, context, new HashSet<>()); }

    /** Lookup filters plus a {@code --preview} flag; a world-wide rollback must name its time window or users. */
    public Rollback parseRollback(String query, Context context) {
        if (query == null || query.length() > 2048) throw syntax("Query must be at most 2048 characters");
        var filters = new ArrayList<String>();
        boolean preview = false;
        for (String token : query.isBlank() ? new String[0] : query.strip().split("\\s+")) {
            if (!token.equals("--preview") && !token.equals("preview")) filters.add(token);
            else if (preview) throw syntax("Duplicate preview flag");
            else preview = true;
        }
        var seen = new HashSet<String>();
        var spec = parse(String.join(" ", filters), context, seen);
        if (spec.bounds() == null && !seen.contains("time") && !seen.contains("user"))
            throw syntax("Global rollback requires explicit t: or u:");
        return new Rollback(spec, preview);
    }

    private LookupSpec parse(String query, Context context, Set<String> seen) {
        if (query == null || query.length() > 2048) throw syntax("Query must be at most 2048 characters");
        String[] tokens = query.isBlank() ? new String[0] : query.strip().split("\\s+");
        if (tokens.length > 16) throw syntax("Too many filters");
        Duration duration = Duration.ofHours(24);
        String dimension = context.dimension();
        LookupSpec.Center center = context.position();
        int radius = 5;
        boolean global = false;
        List<LookupSpec.Actor> actors = List.of();
        List<String> targets = List.of();
        List<String> actions = LookupSpec.ALL_ACTIONS;
        for (String token : tokens) {
            int split = token.indexOf(':');
            if (split < 1 || split == token.length() - 1) throw syntax("Expected filter:value, got '" + token + "'");
            String key = switch (token.substring(0, split)) {
                case "t", "time" -> "time";
                case "r", "radius" -> "radius";
                case "u", "user" -> "user";
                case "b", "block" -> "block";
                case "a", "action" -> "action";
                case "dim", "world" -> "dimension";
                case "at" -> "at";
                default -> throw syntax("Unknown filter '" + token.substring(0, split) + "'");
            };
            if (!seen.add(key)) throw syntax("Duplicate " + key + " filter");
            String value = token.substring(split + 1);
            switch (key) {
                case "time" -> duration = duration(value);
                case "radius" -> {
                    global = value.equals("global");
                    if (!global) {
                        radius = integer(value, "Radius");
                        if (radius < 0 || radius > 100) throw syntax("Radius must be 0..100; use r:global with t:, u: or b:");
                    }
                }
                case "user" -> actors = Arrays.stream(values(value)).map(LookupParser::actor).toList();
                case "block" -> targets = Arrays.stream(values(value)).map(LookupParser::resource).distinct().toList();
                case "action" -> actions = switch (value) {
                    case "block" -> List.of("BLOCK_BREAK", "BLOCK_PLACE");
                    case "-block", "break" -> List.of("BLOCK_BREAK");
                    case "+block", "place" -> List.of("BLOCK_PLACE");
                    case "container", "item" -> List.of("ITEM_TAKE", "ITEM_PUT");
                    case "-item", "take", "-container" -> List.of("ITEM_TAKE");
                    case "+item", "put", "+container" -> List.of("ITEM_PUT");
                    case "kill", "killed", "entity_kill" -> List.of("ENTITY_KILL");
                    default -> throw syntax("Action must be block/break/place, container/item/take/put or kill (also +/-block, +/-item, +/-container)");
                };
                case "dimension" -> dimension = resource(value);
                case "at" -> {
                    String[] xyz = value.split(",", -1);
                    if (xyz.length != 3) throw syntax("Coordinates must be at:x,y,z");
                    center = new LookupSpec.Center(integer(xyz[0], "Coordinate"), integer(xyz[1], "Coordinate"), integer(xyz[2], "Coordinate"));
                }
                default -> throw new AssertionError(key);
            }
        }
        if (global && seen.contains("at")) throw syntax("at: cannot be combined with r:global");
        if (global && !seen.contains("time") && actors.isEmpty() && targets.isEmpty())
            throw syntax("Global lookup requires explicit t:, u: or b:");
        if (!global && (center == null || dimension == null))
            throw syntax("Console spatial lookup requires both at:x,y,z and dim:dimension, or r:global with t:, u: or b:");
        try {
            return new LookupSpec(clock.now().minus(duration), dimension,
                    global ? null : LookupSpec.Bounds.around(center, radius), actors, targets, actions);
        } catch (java.time.DateTimeException | ArithmeticException failure) { throw syntax("Time window outside supported range"); }
    }
    private static Duration duration(String value) {
        var matcher = DURATION.matcher(value);
        int end = 0;
        long seconds = 0;
        try {
            while (matcher.find()) {
                if (matcher.start() != end) throw syntax("Invalid duration '" + value + "'; try 1h30m");
                long multiplier = switch (matcher.group(2)) {
                    case "s" -> 1; case "m" -> 60; case "h" -> 3600; case "d" -> 86400; case "w" -> 604800;
                    default -> throw new AssertionError();
                };
                seconds = Math.addExact(seconds, Math.multiplyExact(Long.parseLong(matcher.group(1)), multiplier));
                end = matcher.end();
            }
            if (end != value.length() || seconds <= 0) throw syntax("Duration must be positive, e.g. 10m or 1h30m");
            return Duration.ofSeconds(seconds);
        } catch (ArithmeticException | NumberFormatException failure) { throw syntax("Duration too large"); }
    }
    private static String[] values(String value) {
        String[] values = value.split(",", -1);
        if (values.length > 16 || Arrays.stream(values).anyMatch(String::isEmpty)) throw syntax("Use 1..16 nonempty comma-separated values");
        return values;
    }
    private static LookupSpec.Actor actor(String value) {
        if (value.equals("#fakeplayer")) return new LookupSpec.Actor(null, null, true);
        if (UUID_TEXT.matcher(value).matches()) return new LookupSpec.Actor(null, UUID.fromString(value), false);
        if (value.startsWith("#") || value.matches(".*\\p{Cntrl}.*"))
            throw syntax("Invalid actor name or UUID '" + value + "'");
        EventEnvelope.text(value, 128);
        return new LookupSpec.Actor(value, null, false);
    }
    private static String resource(String value) {
        String id = value.startsWith(":") ? "minecraft" + value : value.contains(":") ? value : "minecraft:" + value;
        // Keep pure parsing independent of Minecraft bootstrap; these are ResourceLocation's 1.21.1 character sets.
        if (id.length() > 128 || !RESOURCE.matcher(id).matches()) throw syntax("Invalid resource location '" + value + "'");
        return id;
    }
    private static int integer(String value, String label) {
        try {
            if (!value.matches("-?[0-9]+")) throw new NumberFormatException();
            return Integer.parseInt(value);
        } catch (NumberFormatException failure) { throw syntax(label + " must be a 32-bit integer"); }
    }
    private static IllegalArgumentException syntax(String message) { return new IllegalArgumentException(message); }
}
