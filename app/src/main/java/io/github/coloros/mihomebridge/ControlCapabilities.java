package io.github.coloros.mihomebridge;

import android.os.Bundle;
import android.service.controls.Control;
import android.service.controls.DeviceTypes;
import android.service.controls.actions.BooleanAction;
import android.service.controls.actions.CommandAction;
import android.service.controls.actions.ControlAction;
import android.service.controls.actions.FloatAction;
import android.service.controls.templates.ControlTemplate;
import android.service.controls.templates.RangeTemplate;
import android.service.controls.templates.StatelessTemplate;
import android.service.controls.templates.TemperatureControlTemplate;
import android.service.controls.templates.ToggleRangeTemplate;
import android.service.controls.templates.ToggleTemplate;

/** Only expose capabilities actually supplied by the provider. */
final class ControlCapabilities {
    final ToggleTemplate toggle;
    final ToggleRangeTemplate combined;
    final RangeTemplate range;
    final ControlTemplate template;

    ControlCapabilities(Control control) {
        template = unwrap(control.getControlTemplate());
        toggle = template instanceof ToggleTemplate value ? value : null;
        combined = template instanceof ToggleRangeTemplate value ? value : null;
        range = template instanceof RangeTemplate value ? value : combined == null ? null : combined.getRange();
    }
    boolean hasToggle() { return toggle != null || combined != null; }
    boolean checked() { return toggle != null ? toggle.isChecked() : combined != null && combined.isChecked(); }
    boolean stateless() { return template instanceof StatelessTemplate; }
    boolean controllable() { return hasToggle() || range != null || stateless(); }
    ControlAction action(String operation, boolean checked, float value) {
        if (operation == null) throw new IllegalArgumentException("缺少设备操作类型");
        return switch (operation) {
            case "toggle" -> {
                if (!hasToggle()) throw new IllegalArgumentException("设备没有开关能力");
                yield new BooleanAction(toggle == null ? combined.getTemplateId() : toggle.getTemplateId(), checked);
            }
            case "range" -> {
                if (range == null) throw new IllegalArgumentException("设备没有数值调节能力");
                float normalized = ControlValuePolicy.normalize(value, range.getMinValue(), range.getMaxValue(), range.getStepValue());
                yield new FloatAction(range.getTemplateId(), normalized);
            }
            case "command" -> {
                if (!stateless()) throw new IllegalArgumentException("设备没有场景执行能力");
                yield new CommandAction(template.getTemplateId());
            }
            default -> throw new IllegalArgumentException("不支持的设备操作");
        };
    }
    Bundle bundle(int type) {
        Bundle result = new Bundle();
        result.putBoolean("toggle", hasToggle()); result.putBoolean("checked", checked());
        result.putBoolean("command", stateless()); result.putBoolean("range", range != null);
        if (range != null) {
            result.putFloat("min", range.getMinValue()); result.putFloat("max", range.getMaxValue());
            result.putFloat("step", range.getStepValue()); result.putFloat("value", range.getCurrentValue());
            result.putString("format", range.getFormatString().toString());
            result.putString("range_title", type == DeviceTypes.TYPE_LIGHT ? "亮度"
                    : type == DeviceTypes.TYPE_THERMOSTAT || type == DeviceTypes.TYPE_AC_UNIT ? "温度" : "调节");
        }
        return result;
    }
    static ControlTemplate unwrap(ControlTemplate source) {
        for (int i = 0; i < 4 && source instanceof TemperatureControlTemplate temperature; i++) source = temperature.getTemplate();
        return source;
    }
    static String fingerprint(Bundle values) {
        return values.getBoolean("toggle") + ":" + values.getBoolean("checked") + ":" + values.getBoolean("command")
                + ":" + values.getBoolean("range") + ":" + values.getFloat("min") + ":" + values.getFloat("max")
                + ":" + values.getFloat("step") + ":" + values.getFloat("value") + ":" + values.getString("format", "")
                + ":" + values.getString("range_title", "");
    }
}
