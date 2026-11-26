package cn.local.simpleauth;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class RecoveryCodesTest {
    private static int count;
    private static void check(boolean pass, String name) { count++; if (!pass) throw new AssertionError(name); }
    private static void invalid(Runnable action) {
        boolean rejected = false;
        try { action.run(); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "bad recovery input rejected");
    }
    public static void main(String[] args) {
        // Synthetic test fixtures only. Never put real recovery codes into tests or logs.
        List<RecoveryCodes.Entry> parsed = RecoveryCodes.parse("  • Demo1-ABcd\r\n•Demo2-EFgh\tDemo3-IJkl,Demo4-MNop；Demo5-QRst\u00a0Demo6-UVwx  ");
        check(parsed.size() == 6, "pasted bullets, columns and separators");
        check(parsed.get(0).code.equals("Demo1-ABcd"), "case and hyphens preserved exactly");
        check(RecoveryCodes.remaining(parsed) == 6, "new imports unused");
        List<RecoveryCodes.Entry> unique = RecoveryCodes.parse("Demo1-ABcd\nDemo1-ABcd\nDemo2-EFgh");
        check(unique.size() == 2 && unique.get(1).code.equals("Demo2-EFgh"), "duplicate import and order");
        List<RecoveryCodes.Entry> used = RecoveryCodes.setUsed(parsed, 1, true);
        check(used.get(1).used && RecoveryCodes.remaining(used) == 5, "mark used");
        check(!parsed.get(1).used && RecoveryCodes.remaining(parsed) == 6, "original snapshot preserved for failed saves");
        check(RecoveryCodes.remaining(RecoveryCodes.setUsed(used, 1, false)) == 6, "undo mark");
        boolean immutable = false;
        try { parsed.clear(); } catch (UnsupportedOperationException expected) { immutable = true; }
        check(immutable, "returned records immutable");
        Otp.Account legacy = new Otp.Account("Service", "user", "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", "SHA1", 6, 30);
        check(legacy.recoveryCodes.isEmpty(), "old account constructor remains compatible");
        Otp.Account updated = legacy.withRecoveryCodes(used);
        check(updated.recoveryCodes.get(1).used && legacy.recoveryCodes.isEmpty(), "per-account immutable update");
        check(Otp.code(updated, 59).equals(Otp.code(legacy, 59)) && updated.secret.equals(legacy.secret), "saving recovery codes does not change TOTP");
        check(updated.withRecoveryCodes(Collections.emptyList()).recoveryCodes.isEmpty(), "clear recovery codes only");
        List<RecoveryCodes.Entry> mutable = new ArrayList<>(parsed);
        Otp.Account detached = legacy.withRecoveryCodes(mutable); mutable.clear();
        check(detached.recoveryCodes.size() == 6, "input list defensively copied");
        invalid(() -> RecoveryCodes.parse("")); invalid(() -> RecoveryCodes.parse("•\n*\n-"));
        invalid(() -> RecoveryCodes.parse("abcd\n<html>")); invalid(() -> RecoveryCodes.parse("abc"));
        invalid(() -> RecoveryCodes.parse("X".repeat(129)));
        invalid(() -> RecoveryCodes.parse("X".repeat(32769)));
        invalid(() -> RecoveryCodes.setUsed(parsed, -1, true)); invalid(() -> RecoveryCodes.setUsed(parsed, 6, true));
        invalid(() -> RecoveryCodes.immutable(Arrays.asList(parsed.get(0), parsed.get(0))));
        invalid(() -> new RecoveryCodes.Entry(null, false));
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 200; i++) many.append("Demo-code-").append(i).append('\n');
        check(RecoveryCodes.parse(many.toString()).size() == 200, "maximum code count accepted");
        many.append("Demo-code-200"); invalid(() -> RecoveryCodes.parse(many.toString()));
        System.out.println("PASS: " + count + " recovery-code checks.");
    }
}
