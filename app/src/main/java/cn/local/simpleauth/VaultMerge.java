package cn.local.simpleauth;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Append-only import: only exact whole-record duplicates are skipped. */
final class VaultMerge {
    private VaultMerge() {}
    static final class Result {
        final VaultState state;
        final int added, skipped, groupsAdded, groupsRenamed;
        Result(VaultState state, int added, int skipped, int groupsAdded, int groupsRenamed) {
            this.state = state; this.added = added; this.skipped = skipped; this.groupsAdded = groupsAdded; this.groupsRenamed = groupsRenamed;
        }
    }
    static Result merge(VaultState local, VaultState incoming) {
        List<PasswordGroup> groups = new ArrayList<>(local.groups);
        Map<String, PasswordGroup> byId = new HashMap<>(), byName = new HashMap<>();
        Set<String> foldedNames = new HashSet<>();
        for (PasswordGroup g : groups) { byId.put(g.id, g); byName.put(g.name, g); foldedNames.add(g.name.toLowerCase(Locale.ROOT)); }
        Map<String, String> mappedIds = new HashMap<>(); mappedIds.put("", "");
        int renamed = 0;
        for (PasswordGroup source : incoming.groups) {
            PasswordGroup match = byName.get(source.name);
            if (match == null) {
                // Names are user data; IDs are internal. Resolve ID/name collisions without overwriting local groups.
                for (int attempt = 0; attempt <= 1001; attempt++) {
                    String id = attempt == 0 ? source.id : UUID.nameUUIDFromBytes(("simpleauth.merge.group.v1\0" + source.id + "\0" + source.name + "\0" + attempt).getBytes(StandardCharsets.UTF_8)).toString();
                    String name = source.name;
                    if (foldedNames.contains(source.name.toLowerCase(Locale.ROOT))) {
                        String suffix = attempt == 0 ? "（导入）" : "（导入" + (attempt + 1) + "）";
                        int end = Math.min(name.length(), 40 - suffix.length());
                        if (end > 0 && Character.isHighSurrogate(name.charAt(end - 1))) end--;
                        name = name.substring(0, end) + suffix;
                    }
                    PasswordGroup existing = byId.get(id);
                    if (existing != null) {
                        if (existing.name.equals(name)) { match = existing; break; }
                        continue;
                    }
                    if (foldedNames.contains(name.toLowerCase(Locale.ROOT))) continue;
                    if (groups.size() >= 1000) throw new IllegalArgumentException("合并后分组超过 1000 个，无法合并；本机数据未修改");
                    match = new PasswordGroup(id, name); groups.add(match); byId.put(id, match); byName.put(name, match); foldedNames.add(name.toLowerCase(Locale.ROOT));
                    if (!name.equals(source.name)) renamed++;
                    break;
                }
                if (match == null) throw new IllegalArgumentException("无法安全处理分组冲突，本机数据未修改");
            }
            mappedIds.put(source.id, match.id);
        }
        List<Otp.Account> accounts = new ArrayList<>(local.accounts);
        Set<RecordKey> seen = new HashSet<>(); for (Otp.Account a : local.accounts) seen.add(new RecordKey(a));
        int added = 0, skipped = 0;
        for (Otp.Account source : incoming.accounts) {
            String id = mappedIds.get(source.groupId);
            if (id == null) throw new IllegalArgumentException("备份含未知分组");
            Otp.Account candidate = source.groupId.equals(id) ? source : source.withGroup(id);
            if (seen.add(new RecordKey(candidate))) { accounts.add(candidate); added++; }
            else skipped++;
        }
        return new Result(new VaultState(accounts, groups), added, skipped, groups.size() - local.groups.size(), renamed);
    }
    /** Hashes only index records; equality still compares every value, even on a hash collision. */
    private static final class RecordKey {
        final Otp.Account a;
        final int hash;
        RecordKey(Otp.Account a) {
            this.a = a;
            int value = Objects.hash(a.issuer, a.name, a.secret, a.algorithm, a.digits, a.period, a.passwordOnly, a.groupId,
                a.login.website, a.login.username, a.login.password, a.login.notes);
            for (RecoveryCodes.Entry e : a.recoveryCodes) value = 31 * value + Objects.hash(e.code, e.used);
            hash = value;
        }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            if (!(other instanceof RecordKey)) return false;
            Otp.Account b = ((RecordKey)other).a;
            if (a.passwordOnly != b.passwordOnly || a.digits != b.digits || a.period != b.period
                || !a.issuer.equals(b.issuer) || !a.name.equals(b.name) || !a.secret.equals(b.secret) || !a.algorithm.equals(b.algorithm)
                || !a.groupId.equals(b.groupId) || !a.login.website.equals(b.login.website) || !a.login.username.equals(b.login.username)
                || !a.login.password.equals(b.login.password) || !a.login.notes.equals(b.login.notes) || a.recoveryCodes.size() != b.recoveryCodes.size()) return false;
            for (int i = 0; i < a.recoveryCodes.size(); i++) {
                RecoveryCodes.Entry x = a.recoveryCodes.get(i), y = b.recoveryCodes.get(i);
                if (!x.code.equals(y.code) || x.used != y.used) return false;
            }
            return true;
        }
    }
}
