package io.github.coloros.mihomebridge;

final class EnumerationPolicy {
    static boolean useAllDevices(boolean providerRequest, int devices, int scenes) {
        return providerRequest && devices == Integer.MAX_VALUE && scenes == 0;
    }
    private EnumerationPolicy() {}
}
