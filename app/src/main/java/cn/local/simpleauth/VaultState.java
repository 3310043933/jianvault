package cn.local.simpleauth;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** One atomic encrypted snapshot, including empty groups. */
public final class VaultState {
    public final List<Otp.Account> accounts;
    public final List<PasswordGroup> groups;
    public VaultState(List<Otp.Account> accounts, List<PasswordGroup> groups) {
        if (groups.size() > 1000) throw new IllegalArgumentException("最多创建 1000 个分组");
        Set<String> ids = new HashSet<>(), names = new HashSet<>();
        for (PasswordGroup g : groups) {
            if (g == null || !ids.add(g.id) || !names.add(g.name.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("分组名称或编号重复");
        }
        for (Otp.Account a : accounts) if (a == null || (!a.groupId.isEmpty() && !ids.contains(a.groupId))) throw new IllegalArgumentException("条目所属分组不存在");
        this.accounts = Collections.unmodifiableList(new ArrayList<>(accounts));
        this.groups = Collections.unmodifiableList(new ArrayList<>(groups));
    }
    public VaultState addGroup(PasswordGroup group) {
        List<PasswordGroup> next = new ArrayList<>(groups); next.add(group); return new VaultState(accounts, next);
    }
    public VaultState renameGroup(String id, String name) {
        List<PasswordGroup> next = new ArrayList<>(groups); boolean found = false;
        for (int i = 0; i < next.size(); i++) if (next.get(i).id.equals(id)) { next.set(i, new PasswordGroup(id, name)); found = true; }
        if (!found) throw new IllegalArgumentException("分组不存在");
        return new VaultState(accounts, next);
    }
    public VaultState deleteGroup(String id) {
        List<PasswordGroup> next = new ArrayList<>(groups);
        if (!next.removeIf(g -> g.id.equals(id))) throw new IllegalArgumentException("分组不存在");
        List<Otp.Account> moved = new ArrayList<>();
        for (Otp.Account a : accounts) moved.add(a.groupId.equals(id) ? a.withGroup("") : a);
        return new VaultState(moved, next);
    }
}
