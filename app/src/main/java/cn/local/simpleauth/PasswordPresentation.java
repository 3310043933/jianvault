package cn.local.simpleauth;

import java.text.Collator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class PasswordPresentation {
    private PasswordPresentation() {}
    public static List<Otp.Account> select(List<Otp.Account> accounts, String groupId, String query) {
        String q = query.trim().toLowerCase(Locale.ROOT);
        List<Otp.Account> result = new ArrayList<>();
        for (Otp.Account a : accounts) {
            if (a.login.isEmpty() || (groupId != null && !a.groupId.equals(groupId))) continue;
            if (!q.isEmpty() && !a.issuer.toLowerCase(Locale.ROOT).contains(q)
                && !a.login.website.toLowerCase(Locale.ROOT).contains(q)
                && !a.login.username.toLowerCase(Locale.ROOT).contains(q)) continue;
            result.add(a);
        }
        Collator collator = Collator.getInstance(Locale.CHINA);
        result.sort((a, b) -> { int order = collator.compare(a.issuer, b.issuer); return order != 0 ? order : collator.compare(a.login.username, b.login.username); });
        return result;
    }
    public static String copyAll(Otp.Account a) {
        return "名称：" + a.issuer + "\n地址：" + a.login.website + "\n账号：" + a.login.username + "\n密码：" + a.login.password
            + (a.login.notes.isEmpty() ? "" : "\n备注：" + a.login.notes);
    }
}
