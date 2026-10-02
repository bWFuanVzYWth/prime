package dev.primept;

/** Startup capabilities shared by packaged clients and development runs. */
public final class StartupOptions {
    private StartupOptions() {}

    public static boolean enabled() {
        return Boolean.parseBoolean(System.getProperty("primept.enabled", "true"));
    }
}
