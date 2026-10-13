/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.query;

import java.util.List;

public record LookupPage(int number, List<LookupRow> rows, boolean hasMore) {
    public LookupPage {
        LookupSpec.offset(number);
        rows = List.copyOf(rows);
        if (rows.size() > LookupSpec.PAGE_SIZE) throw new IllegalArgumentException("Page exceeds hard limit");
    }
}
