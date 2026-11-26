package cn.local.simpleauth;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Local recovery-code records; a used flag does not contact the account provider. */
public final class RecoveryCodes {
    private RecoveryCodes() {}
    public static final int MAX_CODES = 200;
    public static final class Entry {
        public final String code;
        public final boolean used;
        public Entry(String code, boolean used) {
            if (code == null || !code.matches("[A-Za-z0-9][A-Za-z0-9_-]{3,127}"))
                throw new IllegalArgumentException("每条恢复码需为 4～128 位字母、数字、连字符或下划线，请只粘贴恢复码");
            this.code = code;
            this.used = used;
        }
    }
    public static List<Entry> immutable(List<Entry> input) {
        if (input == null || input.size() > MAX_CODES) throw new IllegalArgumentException("最多保存 200 条恢复码");
        List<Entry> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Entry entry : input) {
            if (entry == null || !seen.add(entry.code)) throw new IllegalArgumentException("恢复码列表含空项或重复项");
            result.add(entry);
        }
        return Collections.unmodifiableList(result);
    }
    public static List<Entry> parse(String raw) {
        if (raw == null || raw.length() > 32768) throw new IllegalArgumentException("粘贴内容过长，最多 200 条恢复码");
        List<Entry> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String token : raw.trim().split("[\\s\\u00A0,;，；]+")) {
            String code = token.replaceFirst("^[•●·]+", "");
            if (code.isEmpty() || code.equals("-") || code.equals("*")) continue;
            Entry entry = new Entry(code, false);
            if (seen.add(entry.code)) result.add(entry);
            if (result.size() > MAX_CODES) throw new IllegalArgumentException("最多保存 200 条恢复码");
        }
        if (result.isEmpty()) throw new IllegalArgumentException("请至少粘贴一条恢复码");
        return immutable(result);
    }
    public static List<Entry> setUsed(List<Entry> input, int index, boolean used) {
        if (index < 0 || index >= input.size()) throw new IllegalArgumentException("恢复码不存在");
        List<Entry> result = new ArrayList<>(input);
        result.set(index, new Entry(input.get(index).code, used));
        return immutable(result);
    }
    public static int remaining(List<Entry> input) {
        int result = 0;
        for (Entry entry : input) if (!entry.used) result++;
        return result;
    }
}
