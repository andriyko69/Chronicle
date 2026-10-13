/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.journal.openhft;

/** Loaded as bytes into an unnamed module: relays only explicit operator JVM grants. */
public final class OpensRelay {
    public static void open(Module target) {
        for (String name : new String[]{"java.lang", "java.lang.reflect", "java.io", "java.nio",
                "sun.nio.ch", "java.nio.channels.spi", "jdk.internal.ref"}) {
            Object.class.getModule().addOpens(name, target);
        }
    }
}
