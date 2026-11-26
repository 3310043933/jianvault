package cn.local.simpleauth;

import java.util.Collections;

public final class InternationalizationTest {
    private static int count;
    private static void check(boolean ok, String message) { count++; if (!ok) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        LoginDetails details = new LoginDetails("https://example.invalid", "中文User", "  复制Password!  ", "备注\nDo not translate");
        Otp.Account account = Otp.Account.passwordEntry("用户名称", details);
        VaultState state = new VaultState(Collections.singletonList(account), Collections.emptyList());
        String before = VaultCodec.encode(state), english = PasswordPresentation.copyAll(account, true), chinese = PasswordPresentation.copyAll(account, false);
        check(english.startsWith("Name: 用户名称\nURL: https://example.invalid"), "English field labels");
        check(english.contains("\nUsername: 中文User\nPassword:   复制Password!  \nNotes: 备注\nDo not translate"), "Saved Unicode, spaces and notes unchanged");
        check(chinese.startsWith("名称：用户名称"), "Chinese field labels");
        check(PasswordPresentation.copyAll(account).equals(chinese), "Old copy API stays compatible");
        check(before.equals(VaultCodec.encode(state)), "Language selection cannot mutate vault data");
        check(VaultMerge.merge(state, state).skipped == 1, "Language presentation preserves record equality");
        check(ErrorMessages.translate("请填写登录账号和密码", true).equals("Enter a username and password."), "Known error in English");
        check(ErrorMessages.translate("请填写登录账号和密码", false).equals("请填写登录账号和密码"), "Known error in Chinese");
        check(ErrorMessages.translate("用户自己的文字", true).equals("用户自己的文字"), "Unknown text is not machine-translated");
        check(ErrorMessages.translate("Already English", true).equals("Already English"), "No double translation");
        check(ErrorMessages.translate(null, true) == null, "Null error remains null");
        System.out.println("PASS: " + count + " internationalization checks.");
    }
}
