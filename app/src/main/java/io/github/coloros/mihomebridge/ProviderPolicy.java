package io.github.coloros.mihomebridge;

/** Shared decision for "may the system bind MiHome's controls provider right now". */
final class ProviderPolicy {
    // PackageManager.COMPONENT_ENABLED_STATE_* values, kept literal to stay unit-testable.
    private static final int DEFAULT = 0, ENABLED = 1;

    static boolean available(boolean applicationEnabled, int componentState, boolean serviceEnabled) {
        if (!applicationEnabled) return false;
        return switch (componentState) {
            case ENABLED -> true;
            case DEFAULT -> serviceEnabled;
            default -> false;
        };
    }
    private ProviderPolicy() {}
}
