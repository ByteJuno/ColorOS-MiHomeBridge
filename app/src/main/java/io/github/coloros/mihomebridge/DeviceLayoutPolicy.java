package io.github.coloros.mihomebridge;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

final class DeviceLayoutPolicy {
    record Options(Set<String> hidden, List<String> order, boolean groupRooms) {
        Options { hidden = Set.copyOf(hidden); order = List.copyOf(order); }
        static Options defaults() { return new Options(Set.of(), List.of(), false); }
    }
    static <T> List<T> arrange(List<T> input, Options options, Function<T, String> id, Function<T, String> room) {
        Map<String, T> unique = new LinkedHashMap<>();
        for (T row : input) if (!options.hidden.contains(id.apply(row))) unique.putIfAbsent(id.apply(row), row);
        List<T> ordered = new ArrayList<>();
        for (String key : options.order) { T row = unique.remove(key); if (row != null) ordered.add(row); }
        ordered.addAll(unique.values());
        if (!options.groupRooms) return ordered;
        Map<String, List<T>> groups = new LinkedHashMap<>();
        for (T row : ordered) groups.computeIfAbsent(roomName(room.apply(row)), key -> new ArrayList<>()).add(row);
        return groups.values().stream().flatMap(List::stream).toList();
    }
    static String roomName(String room) { return room == null || room.isBlank() ? "未分配房间" : room.strip(); }
    static String encodeOrder(List<String> ids) {
        return new LinkedHashSet<>(ids).stream().map(id -> Base64.getUrlEncoder().withoutPadding()
                .encodeToString(id.getBytes(StandardCharsets.UTF_8))).map(value -> "v1:" + value).reduce((a, b) -> a + "\n" + b).orElse("");
    }
    static List<String> decodeOrder(String encoded) {
        List<String> result = new ArrayList<>();
        if (encoded == null || encoded.length() > 256 * 1024) return result;
        try {
            for (String line : encoded.split("\n")) if (!line.isBlank() && result.size() < 256)
                result.add(new String(Base64.getUrlDecoder().decode(line.startsWith("v1:") ? line.substring(3) : line), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException malformed) { return List.of(); }
        return List.copyOf(new LinkedHashSet<>(result));
    }
    private DeviceLayoutPolicy() {}
}
