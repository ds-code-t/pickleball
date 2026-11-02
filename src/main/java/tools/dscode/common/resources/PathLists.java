package tools.dscode.common.resources;

import java.util.ArrayList;
import java.util.List;

/**
 * One comma splitter for path settings ({@code pkb_features}, {@code pkb_glue},
 * {@code pkb_datapath}, {@code pkb_configpath}, {@code pkb_callpath},
 * {@code pkb_componentpath}). A comma always splits and is never part of a
 * folder name. Blank entries and a trailing comma are dropped. A value with
 * no comma is one path, unchanged aside from trim.
 */
public final class PathLists {
    private PathLists() {
    }

    public static List<String> split(String value) {
        if (value == null || value.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String part : value.split(",", -1)) {
            if (part == null) continue;
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return List.copyOf(out);
    }
}
