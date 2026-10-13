/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.event;

import java.time.Instant;
import java.util.Objects;

@FunctionalInterface
public interface ChronicleClock {
    Instant now();

    static ChronicleClock system() { return Instant::now; }
    static ChronicleClock fixed(Instant value) {
        Objects.requireNonNull(value);
        return () -> value;
    }
}
