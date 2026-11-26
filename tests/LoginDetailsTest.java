package cn.local.simpleauth;

import java.util.Collections;

public final class LoginDetailsTest {
    private static int count;
    private static void check(boolean pass, String name) { count++; if (!pass) throw new AssertionError(name); }
    private static void invalid(Runnable action) {
        boolean rejected = false; try { action.run(); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "invalid login input rejected");
    }
    public static void main(String[] args) {
        String password = "  Synthetic-Pass!密码🔐\"\\\t  ";
        LoginDetails details = new LoginDetails(" https://example.invalid/login ", "CaseSensitive@Example.invalid", password);
        details.requireCredentials();
        check(details.password.equals(password), "password exact preservation incl spaces Unicode and punctuation");
        check(details.website.equals("https://example.invalid/login"), "website trimmed");
        check(details.username.equals("CaseSensitive@Example.invalid"), "username case preserved");
        check(LoginDetails.EMPTY.isEmpty() && !details.isEmpty(), "empty login migration default");
        Otp.Account only = Otp.Account.passwordEntry("Example", details);
        check(only.passwordOnly && only.secret.isEmpty() && only.login == details, "password without fabricated TOTP seed");
        invalid(() -> Otp.code(only, 59));
        Otp.Account legacy = new Otp.Account("Example", "old", "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", "SHA1", 6, 30);
        check(!legacy.passwordOnly && legacy.login.isEmpty(), "old account compatible");
        Otp.Account combined = legacy.withLogin(details).withRecoveryCodes(RecoveryCodes.parse("Demo-1234"));
        check(combined.login.password.equals(password) && !combined.passwordOnly, "recovery update preserves login");
        check(Otp.code(combined, 59).equals(Otp.code(legacy, 59)), "login update preserves TOTP");
        Otp.Account cleared = combined.withLogin(LoginDetails.EMPTY);
        check(cleared.login.isEmpty() && cleared.recoveryCodes.size() == 1 && cleared.secret.equals(legacy.secret), "remove login preserves recovery and TOTP");
        check(legacy.login.isEmpty() && combined.login == details, "old account snapshot unchanged");
        Otp.Account onlyWithRecovery = only.withRecoveryCodes(RecoveryCodes.parse("Demo-1234"));
        check(onlyWithRecovery.passwordOnly && onlyWithRecovery.login.password.equals(password), "password entry recovery update");
        LoginDetails changed = new LoginDetails("", "new-user", "another-synthetic-password");
        Otp.Account edited = onlyWithRecovery.withLogin(changed);
        check(edited.name.equals("new-user") && edited.recoveryCodes.size() == 1 && edited.passwordOnly, "password entry edit preserves recovery");
        invalid(() -> only.withLogin(LoginDetails.EMPTY));
        invalid(() -> Otp.Account.passwordEntry("", details));
        invalid(() -> Otp.Account.passwordEntry("Example", new LoginDetails("", "", "password")));
        invalid(() -> Otp.Account.passwordEntry("Example", new LoginDetails("", "user", "")));
        invalid(() -> new LoginDetails(null, "user", "password"));
        invalid(() -> new LoginDetails("x".repeat(2049), "user", "password"));
        invalid(() -> new LoginDetails("", "x".repeat(201), "password"));
        invalid(() -> new LoginDetails("", "user", "x".repeat(16385)));
        invalid(() -> new Otp.Account("Example", "user", "", "SHA1", 6, 30));
        invalid(() -> new Otp.Account("Example", "user", legacy.secret, "SHA1", 6, 30, Collections.emptyList(), details, true));
        System.out.println("PASS: " + count + " login-model checks.");
    }
}
