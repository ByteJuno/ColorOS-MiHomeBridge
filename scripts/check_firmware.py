"""Inspect supplied APKs without executing them or reading app/account data."""
import argparse
import hashlib
import json
import re
import struct
import zipfile
from datetime import date
from pathlib import Path


def dex_classes(data):
    u32 = lambda off: struct.unpack_from("<I", data, off)[0]

    def uleb(off):
        result, shift = 0, 0
        while True:
            byte = data[off]
            off += 1
            result |= (byte & 127) << shift
            if not byte & 128:
                return result, off
            shift += 7

    def string(index):
        _, off = uleb(u32(u32(60) + index * 4))
        return data[off:data.index(b"\0", off)].decode("utf8", errors="replace")

    def typename(index):
        return string(u32(u32(68) + index * 4))

    def method(index):
        off = u32(92) + 8 * index
        _, proto, name = struct.unpack_from("<HHI", data, off)
        po = u32(76) + 12 * proto
        ret = typename(u32(po + 4))
        args = u32(po + 8)
        params = [] if args == 0 else [typename(struct.unpack_from("<H", data, args + 4 + 2 * i)[0]) for i in range(u32(args))]
        return string(name) + "(" + "".join(params) + ")" + ret

    for i in range(u32(96)):
        off = u32(100) + 32 * i
        name = typename(u32(off))
        pos = u32(off + 24)
        if not pos:
            yield name, []
            continue
        counts = []
        for _ in range(4):
            count, pos = uleb(pos)
            counts.append(count)
        for _ in range(counts[0] + counts[1]):
            _, pos = uleb(pos)
            _, pos = uleb(pos)
        methods = []
        for count in counts[2:]:
            index = 0
            for _ in range(count):
                diff, pos = uleb(pos)
                index += diff
                _, pos = uleb(pos)
                _, pos = uleb(pos)
                methods.append(method(index))
        yield name, methods


TARGETS = {
    "mydevices": {
        "Lcom/oplus/mydevices/deviceui/devicecard/DeviceCardHomeActivity;": [
            r"s1\(Ljava/util/List;ZZ\)V",
            r"j1\(Lcom/oplus/mydevices/domain/entities/cards/QuickCardDeviceData;Ljava/util/List;\)V",
        ],
        "Lcom/oplus/mydevices/domain/entities/cards/QuickCardDeviceData;": [
            r"<init>\(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Lcom/oplus/mydevices/domain/entities/plugin/DeviceStatus;Lcom/oplus/mydevices/domain/entities/plugin/DeviceStatus;Lcom/oplus/mydevices/domain/entities/plugin/DeviceStatus;Ljava/lang/String;Ljava/util/List;Ljava/lang/String;ZZZLcom/oplus/mydevices/domain/entities/device/BatteryInfo;Lcom/oplus/mydevices/domain/entities/device/BatteryInfo;Lcom/oplus/mydevices/domain/entities/device/BatteryInfo;Lcom/oplus/mydevices/domain/entities/device/BatteryInfo;Ljava/lang/Boolean;ZLjava/lang/String;Lcom/oplus/mydevices/domain/entities/cards/DeviceAbnormalState;Ljava/lang/String;ZLjava/lang/String;Ljava/lang/String;Lcom/oplus/mydevices/domain/entities/cards/StatusRing;\)V",
            r"getId\(\)Ljava/lang/String;", r"getName\(\)Ljava/lang/String;",
            r"setDeviceType\(Lcom/oplus/mydevices/domain/entities/device/DeviceType;\)V",
            r"setDisplayState\(Lcom/oplus/mydevices/domain/entities/cards/DeviceCardDisplayState;\)V",
        ],
        "Lcom/oplus/mydevices/domain/entities/plugin/DeviceStatus;": [r"<init>\(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Integer;Ljava/lang/Integer;\)V"],
        "Lcom/oplus/mydevices/domain/entities/cards/DeviceCardDisplayState$Companion;": [
            r"fromDeviceData\(Lcom/oplus/mydevices/domain/entities/device/DeviceType;ZLcom/oplus/mydevices/domain/entities/device/ConnectState;Z\)Lcom/oplus/mydevices/domain/entities/cards/DeviceCardDisplayState;",
        ],
        "Laa/un;": [r"h\(Lcom/oplus/mydevices/domain/entities/cards/QuickCardDeviceData;\)V"],
        "Laa/Ln;": [r"d\(Ljava/lang/String;Ljava/lang/String;Lcom/oplus/mydevices/domain/entities/device/DeviceType;Ljava/lang/Integer;\)V", r"b\(\)V"],
        "Lcom/oplus/mydevices/deviceui/devicecard/view/homecard/HomeCardView;": [
            r"u\(Lcom/oplus/mydevices/domain/entities/cards/QuickCardDeviceData;\)V",
            r"dispatchTouchEvent\(Landroid/view/MotionEvent;\)Z",
        ],
    },
    "mihome": {
        "Lcom/xiaomi/smarthome/controls/ControlUtils;": [
            r"initService\(Landroid/content/Context;Z\)V",
            r"readAllDeviceList\(Landroid/content/Context;Ljava/lang/String;L[^;]+;\)V",
            r"readSuggestDeviceList\(Landroid/content/Context;Ljava/lang/String;IIL[^;]+;\)V",
        ],
        "Lcom/xiaomi/smarthome/controls/MiControlsProviderService;": [r"createPublisherForAllAvailable\(\)Ljava/util/concurrent/Flow\$Publisher;"],
        "Lcom/xiaomi/smarthome/controls/MiControlsProviderService$2;": [r"onReadyRequest\(J\)V"],
    },
    "systemui": {
        "Lcom/android/systemui/controls/dagger/ControlsComponent;": [
            r"<init>\(Z.+\)V", r"getControlsController\(\)Ljava/util/Optional;", r"getControlsUiController\(\)Ljava/util/Optional;",
        ],
        "Lcom/android/systemui/controls/ui/ControlsUiControllerImpl;": [r"resolveActivity\(\)Ljava/lang/Class;"],
        "Lcom/oplus/systemui/qs/tiles/OplusDeviceControlsTile;": [r"isAvailable\(\)Z"],
        "Lcom/oplus/systemui/qs/tileimpl/OplusQSFactoryImpl;": [r"createTile\(Ljava/lang/String;\).+"],
        "Lcom/android/systemui/qs/pipeline/domain/interactor/CurrentTilesInteractorImpl;": [
            r"addTile\(Lcom/android/systemui/qs/pipeline/shared/TileSpec;I\)V",
            r"removeTiles\(Ljava/util/Collection;\)V",
            r"getCurrentTilesSpecs\(\)Ljava/util/List;",
        ],
        "Lcom/android/systemui/qs/pipeline/shared/TileSpec$Companion;": [
            r"create\(Ljava/lang/String;\)Lcom/android/systemui/qs/pipeline/shared/TileSpec;",
        ],
        "Lcom/android/systemui/controls/controller/ControlsBindingControllerImpl;": [
            r"<init>\(Landroid/content/Context;Lcom/android/systemui/util/concurrency/DelayableExecutor;Ldagger/Lazy;Lcom/android/systemui/controls/controller/PackageUpdateMonitor\$Factory;Lcom/android/systemui/settings/UserTracker;\)V",
            r"subscribe\(Lcom/android/systemui/controls/controller/StructureInfo;\)V",
            r"action\(Landroid/content/ComponentName;Lcom/android/systemui/controls/controller/ControlInfo;Landroid/service/controls/actions/ControlAction;\)V",
            r"unbind\(\)V",
        ],
        "Lcom/android/systemui/controls/controller/ControlsProviderLifecycleManager;": [
            r"<init>\(Landroid/content/Context;Lcom/android/systemui/util/concurrency/DelayableExecutor;Landroid/service/controls/IControlsActionCallback\$Stub;Landroid/os/UserHandle;Landroid/content/ComponentName;Lcom/android/systemui/controls/controller/PackageUpdateMonitor\$Factory;\)V",
            r"getToken\(\)Landroid/os/IBinder;",
        ],
        "Lcom/android/systemui/controls/controller/ControlsControllerImpl;": [
            r"changeUser\(Landroid/os/UserHandle;\)V",
            r"getFavoritesForComponent\(Landroid/content/ComponentName;\)Ljava/util/List;",
            r"getCurrentUserId\(\)I",
        ],
        "Lcom/android/systemui/controls/controller/StructureInfo;": [
            r"<init>\(Landroid/content/ComponentName;Ljava/lang/CharSequence;Ljava/util/List;\)V",
        ],
        "Lcom/oplus/deviceplugin/sdk/entity/PanelInfo;": [
            r"<init>\(Lcom/oplus/deviceplugin/sdk/entity/PanelState;Ljava/util/List;\)V",
            r"getPanelDevices\(\)Ljava/util/List;",
        ],
        "Lcom/oplus/deviceplugin/sdk/entity/PanelDevice;": [
            r"<init>\(Ljava/lang/String;Lcom/oplus/deviceplugin/sdk/entity/DeviceType;Ljava/lang/Integer;Ljava/util/List;Ljava/util/Map;Ljava/lang/String;Lcom/oplus/deviceplugin/sdk/entity/DeviceStatus;Ljava/lang/String;ILjava/util/List;Lcom/oplus/deviceplugin/sdk/entity/DeviceItemType;Lcom/oplus/deviceplugin/sdk/entity/ConnectState;Lcom/oplus/deviceplugin/sdk/entity/SynergyCoordinationState;IIIIIIILjava/lang/Boolean;\)V",
        ],
        "Lcom/oplus/deviceplugin/sdk/entity/DeviceStatus;": [
            r"<init>\(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Integer;\)V",
        ],
        "Lcom/oplus/deviceplugin/sdk/presenter/l;": [r"b\(\)Lcom/oplus/deviceplugin/sdk/entity/PanelInfo;"],
        "Lcom/oplus/deviceplugin/sdk/ui/panel/d;": [r"a\(Lcom/oplus/deviceplugin/sdk/entity/PanelInfo;\)V"],
        "Lcom/oplus/deviceplugin/sdk/ui/panel/j;": [r"a\(Lcom/oplus/deviceplugin/sdk/entity/PanelInfo;\)V"],
        "Lcom/oplus/deviceplugin/sdk/ui/panel/h;": [r"onResume\(\)V", r"onPause\(\)V", r"onDestroy\(\)V"],
        "Lcom/oplus/deviceplugin/sdk/ui/panel/m;": [r"onResume\(\)V", r"onPause\(\)V", r"onDestroy\(\)V",
            r"a\(Lcom/oplus/deviceplugin/sdk/ui/panel/m;Ljava/util/List;Lcom/android/systemui/plugins/qs/IHostAbility;Lcom/android/systemui/plugins/qs/SpanSizeInPlugin;\)V"],
        "Lcom/oplus/deviceplugin/sdk/ui/view/separatecardview/SquareDeviceCardView;": [r"d\(Lcom/oplus/deviceplugin/sdk/entity/PanelDevice;\)V"],
        "Lcom/oplus/deviceplugin/sdk/ui/view/separatecardview/RectangleDeviceCardView;": [r"e\(Lcom/oplus/deviceplugin/sdk/entity/PanelDevice;\)V"],
        "Lcom/oplus/deviceplugin/sdk/ui/view/d;": [r"f\(Lcom/oplus/deviceplugin/sdk/entity/PanelDevice;\)V"],
        "Lcom/oplus/deviceplugin/sdk/utils/PlatformUtils;": [r"getIsEditMode\(\)Z"],
        "Lcom/oplus/deviceplugin/sdk/utils/ViewExtKt;": [
            r"bindDeviceIcon\(Lcom/oplus/deviceplugin/sdk/ui/view/progressbar/BatteryCircularProgressLayout;Lcom/oplus/deviceplugin/sdk/entity/PanelDevice;Z\)V",
            r"bindDeviceIconBlurDisable\(Lcom/oplus/deviceplugin/sdk/ui/view/progressbar/BatteryCircularProgressLayout;Lcom/oplus/deviceplugin/sdk/entity/PanelDevice;Z\)V",
            r"bindDeviceIconGlobalTheme\(Lcom/oplus/deviceplugin/sdk/ui/view/progressbar/BatteryCircularProgressLayout;Lcom/oplus/deviceplugin/sdk/entity/PanelDevice;Z\)V",
        ],
    },
}


def inspect(path, kind):
    found = {}
    with zipfile.ZipFile(path) as apk:
        for entry in apk.namelist():
            if not re.fullmatch(r"classes\d*\.dex", entry):
                continue
            for name, methods in dex_classes(apk.read(entry)):
                if name in TARGETS[kind]:
                    found[name] = {"dex": entry, "methods": methods}
    checks = []
    for name, signatures in TARGETS[kind].items():
        for expected in signatures:
            matches = [m for m in found.get(name, {}).get("methods", []) if re.fullmatch(expected, m)]
            checks.append({"class": name, "expected": expected, "matched": matches, "ok": bool(matches)})
    with path.open("rb") as file:
        digest = hashlib.file_digest(file, "sha256").hexdigest()
    return {"file": path.name, "bytes": path.stat().st_size,
            "sha256": digest,
            "checks": checks, "ok": all(c["ok"] for c in checks)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mihome", type=Path, required=True)
    parser.add_argument("--systemui", type=Path, required=True)
    parser.add_argument("--mydevices", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--firmware")
    parser.add_argument("--mihome-version")
    parser.add_argument("--systemui-version")
    parser.add_argument("--mydevices-version")
    args = parser.parse_args()
    result = {"schema": 1, "date": date.today().isoformat(), "firmware": args.firmware,
              "mihome_version": args.mihome_version, "systemui_version": args.systemui_version,
              "artifacts": [inspect(args.mihome, "mihome"), inspect(args.systemui, "systemui")]}
    if args.mydevices:
        result["mydevices_version"] = args.mydevices_version
        result["artifacts"].append(inspect(args.mydevices, "mydevices"))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf8")
    failed = [c for a in result["artifacts"] for c in a["checks"] if not c["ok"]]
    print(json.dumps({"ok": not failed, "checks": sum(len(a["checks"]) for a in result["artifacts"]), "failed": failed}, ensure_ascii=False))
    raise SystemExit(bool(failed))


if __name__ == "__main__":
    main()
