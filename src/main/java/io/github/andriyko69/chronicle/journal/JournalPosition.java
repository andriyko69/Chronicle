/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.journal;

public record JournalPosition(long value) implements Comparable<JournalPosition> {
    public static final JournalPosition NONE = new JournalPosition(-1);
    public JournalPosition { if (value < -1) throw new IllegalArgumentException("Invalid journal position"); }
    @Override public int compareTo(JournalPosition other) { return Long.compare(value, other.value); }
}
