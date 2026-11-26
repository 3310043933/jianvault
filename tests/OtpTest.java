package cn.local.simpleauth;

import java.nio.charset.StandardCharsets;

public final class OtpTest {
    private static int count;
    private static void check(boolean pass, String name) { count++; if (!pass) throw new AssertionError(name); }
    private static void invalid(Runnable r) { boolean rejected = false; try { r.run(); } catch (IllegalArgumentException e) { rejected = true; } check(rejected, "invalid input rejected"); }
    private static String base32(String s) {
        StringBuilder b = new StringBuilder(); int buffer = 0, bits = 0;
        for (byte v : s.getBytes(StandardCharsets.US_ASCII)) { buffer = (buffer << 8) | (v & 255); bits += 8; while (bits >= 5) { bits -= 5; b.append("ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".charAt((buffer >> bits) & 31)); } }
        if (bits > 0) b.append("ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".charAt((buffer << (5 - bits)) & 31)); return b.toString();
    }
    public static void main(String[] args) {
        long[] times = {59, 1111111109L, 1111111111L, 1234567890L, 2000000000L, 20000000000L};
        String[][] expected = {{"94287082","07081804","14050471","89005924","69279037","65353130"}, {"46119246","68084774","67062674","91819424","90698825","77737706"}, {"90693936","25091201","99943326","93441116","38618901","47863826"}};
        String[] algorithms = {"SHA1", "SHA256", "SHA512"};
        String[] seeds = {"12345678901234567890", "12345678901234567890123456789012", "1234567890123456789012345678901234567890123456789012345678901234"};
        for (int i = 0; i < 3; i++) for (int j = 0; j < 6; j++) check(expected[i][j].equals(Otp.code(new Otp.Account("Test", "RFC", base32(seeds[i]), algorithms[i], 8, 30), times[j])), "RFC 6238 vector " + i + "/" + j);
        String key = base32(seeds[0]);
        Otp.Account a = Otp.parse("otpauth://totp/GitHub%3Aalice%2Btest?secret=" + key + "&issuer=GitHub");
        check(a.name.equals("alice+test") && a.issuer.equals("GitHub") && a.digits == 6 && a.period == 30, "GitHub QR defaults");
        check(Otp.code(a, 59).equals("287082"), "six digit truncation");
        check(Otp.code(a, 30).equals(Otp.code(a, 59)), "same time window");
        check(!Otp.code(a, 59).equals(Otp.code(a, 60)), "window boundary");
        check(Otp.code(a, 1111111109L).equals("081804"), "leading zero");
        Otp.Account custom = Otp.parse("otpauth://totp/Example%3Auser?secret=" + key + "&issuer=Example&algorithm=SHA256&digits=8&period=60");
        check(custom.period == 60 && custom.digits == 8 && custom.algorithm.equals("SHA256"), "custom QR parameters");
        check(new String(Otp.decode("MZXW6=== "), StandardCharsets.US_ASCII).equals("foo"), "Base32 padding");
        invalid(() -> Otp.decode("MZ======")); invalid(() -> Otp.decode("A")); invalid(() -> Otp.decode("012345"));
        invalid(() -> Otp.parse("https://example.com")); invalid(() -> Otp.parse("otpauth://hotp/test?secret=" + key));
        invalid(() -> Otp.parse("otpauth://totp/test?issuer=GitHub"));
        invalid(() -> Otp.parse("otpauth://totp/A%3Auser?issuer=B&secret=" + key));
        invalid(() -> Otp.parse("otpauth://totp/test?secret=" + key + "&secret=" + key));
        invalid(() -> Otp.parse("otpauth://totp/test?secret=" + key + "&period=0"));
        invalid(() -> Otp.parse("otpauth://totp/test?secret=" + key + "&digits=7"));
        invalid(() -> Otp.parse("otpauth://totp/test?secret=" + key + "&algorithm=MD5"));
        invalid(() -> new Otp.Account("", "", key, "SHA1", 6, 30));
        System.out.println("PASS: " + count + " checks, including all 18 RFC 6238 vectors.");
    }
}
