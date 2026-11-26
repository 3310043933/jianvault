package cn.local.simpleauth;

import java.util.UUID;

public final class PasswordGroup {
    public final String id, name;
    public PasswordGroup(String id, String name) {
        if (id == null || !id.matches("[a-zA-Z0-9-]{1,80}")) throw new IllegalArgumentException("分组编号无效");
        if (name == null || name.trim().isEmpty() || name.trim().length() > 40) throw new IllegalArgumentException("分组名称需为 1～40 个字符");
        if (name.trim().equals("未分组") || name.trim().equals("不分组") || name.trim().equals("全部网站")) throw new IllegalArgumentException("此名称由系统使用，请换一个分组名称");
        this.id = id; this.name = name.trim();
    }
    public static PasswordGroup create(String name) { return new PasswordGroup(UUID.randomUUID().toString(), name); }
}
