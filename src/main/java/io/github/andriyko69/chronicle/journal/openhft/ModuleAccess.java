/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.journal.openhft;

/** NeoForge defines named library modules after JVM options have been processed. */
public final class ModuleAccess {
    private static boolean opened;
    private ModuleAccess() {}
    public static synchronized void initialize() {
        if (opened) return;
        Module core = net.openhft.chronicle.core.Jvm.class.getModule();
        if (core.isNamed()) {
            try {
                byte[] bytes;
                try (var input = ModuleAccess.class.getResourceAsStream("OpensRelay.class")) {
                    if (input == null) throw new IllegalStateException("Missing module grant relay");
                    bytes = input.readAllBytes();
                }
                Class<?> relay = new ClassLoader(ClassLoader.getPlatformClassLoader()) {
                    Class<?> define() { return defineClass(null, bytes, 0, bytes.length); }
                }.define();
                // Include GAME/PLUGIN ancestors for both development and nested-library deployment.
                grant(core.getLayer(), relay);
            } catch (Exception failure) {
                throw new IllegalStateException("OpenHFT requires Chronicle's chronicle-jvm.args grants", failure);
            }
        }
        opened = true;
    }
    private static void grant(ModuleLayer layer, Class<?> relay) throws ReflectiveOperationException {
        for (Module module : layer.modules()) {
            if (module.getName().startsWith("chronicle.") || module.getName().equals("compiler"))
                relay.getMethod("open", Module.class).invoke(null, module);
        }
        for (ModuleLayer parent : layer.parents()) grant(parent, relay);
    }
}
