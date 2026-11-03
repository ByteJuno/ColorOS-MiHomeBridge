package io.github.coloros.mihomebridge;

final class ControlValuePolicy {
    static float normalize(float value, float min, float max, float step) {
        if (!Float.isFinite(value) || !Float.isFinite(min) || !Float.isFinite(max) || !Float.isFinite(step)
                || min >= max || step <= 0 || value < min || value > max)
            throw new IllegalArgumentException("控制值超出设备范围");
        double rounded = min + Math.rint(((double) value - min) / step) * step;
        return (float) Math.max(min, Math.min(max, rounded));
    }
    static boolean confirms(float expected, float actual, float step) {
        return Float.isFinite(expected) && Float.isFinite(actual) && Float.isFinite(step) && step > 0
                && Math.abs((double) expected - actual) <= Math.max(0.0001, step * 0.1);
    }
    private ControlValuePolicy() {}
}
