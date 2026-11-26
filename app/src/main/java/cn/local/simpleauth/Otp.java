package cn.local.simpleauth;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** RFC 6238 TOTP, with strict Base32 and otpauth input validation. */
public final class Otp {
    private Otp() {}
    public static final class Account {
        public final String issuer, name, secret, algorithm;
        public final int digits, period;
        public final java.util.List<RecoveryCodes.Entry> recoveryCodes;
        public final LoginDetails login;
        public final boolean passwordOnly;
        public final String groupId;
        public Account(String issuer, String name, String secret, String algorithm, int digits, int period) {
            this(issuer, name, secret, algorithm, digits, period, java.util.Collections.emptyList());
        }
        public Account(String issuer, String name, String secret, String algorithm, int digits, int period,
                java.util.List<RecoveryCodes.Entry> recoveryCodes) {
            this(issuer, name, secret, algorithm, digits, period, recoveryCodes, LoginDetails.EMPTY, false);
        }
        public Account(String issuer, String name, String secret, String algorithm, int digits, int period,
                java.util.List<RecoveryCodes.Entry> recoveryCodes, LoginDetails login, boolean passwordOnly) {
            this(issuer, name, secret, algorithm, digits, period, recoveryCodes, login, passwordOnly, "");
        }
        public Account(String issuer, String name, String secret, String algorithm, int digits, int period,
                java.util.List<RecoveryCodes.Entry> recoveryCodes, LoginDetails login, boolean passwordOnly, String groupId) {
            if (groupId == null || (!groupId.isEmpty() && !groupId.matches("[a-zA-Z0-9-]{1,80}"))) throw new IllegalArgumentException("分组编号无效");
            this.groupId = groupId;
            this.issuer = issuer.trim(); this.name = name.trim();
            if (this.name.isEmpty() || this.name.length() > 200 || this.issuer.length() > 200)
                throw new IllegalArgumentException("请填写账号名称（最多 200 字）");
            if (login == null) throw new IllegalArgumentException("登录信息不可为空");
            this.login = login; this.passwordOnly = passwordOnly;
            if (passwordOnly) {
                login.requireCredentials();
                if (!secret.isEmpty()) throw new IllegalArgumentException("密码条目不应包含验证码密钥");
                this.secret = "";
            } else {
                this.secret = normalize(secret);
                byte[] key = decode(this.secret);
                if (key.length < 10 || key.length > 128) throw new IllegalArgumentException("密钥长度不正确，需 10～128 字节");
            }
            this.algorithm = algorithm.toUpperCase(Locale.ROOT);
            if (!this.algorithm.matches("SHA1|SHA256|SHA512")) throw new IllegalArgumentException("不支持此验证码算法");
            if (digits != 6 && digits != 8) throw new IllegalArgumentException("仅支持 6 或 8 位验证码");
            if (period < 1 || period > 300) throw new IllegalArgumentException("刷新周期需在 1～300 秒之间");
            this.digits = digits; this.period = period;
            this.recoveryCodes = RecoveryCodes.immutable(recoveryCodes);
        }
        public Account withRecoveryCodes(java.util.List<RecoveryCodes.Entry> codes) {
            return new Account(issuer, name, secret, algorithm, digits, period, codes, login, passwordOnly, groupId);
        }
        public Account withLogin(LoginDetails details) {
            return new Account(issuer, passwordOnly ? details.username : name, secret, algorithm, digits, period, recoveryCodes, details, passwordOnly, groupId);
        }
        public Account withGroup(String id) {
            return new Account(issuer, name, secret, algorithm, digits, period, recoveryCodes, login, passwordOnly, id);
        }
        public Account withTitle(String title) {
            if (title.trim().isEmpty()) throw new IllegalArgumentException("请填写网站名称");
            return new Account(title, name, secret, algorithm, digits, period, recoveryCodes, login, passwordOnly, groupId);
        }
        public static Account passwordEntry(String title, LoginDetails details) {
            if (title.trim().isEmpty()) throw new IllegalArgumentException("请填写网站或条目名称");
            return new Account(title, details.username, "", "SHA1", 6, 30, java.util.Collections.emptyList(), details, true);
        }
    }
    public static String normalize(String s) {
        return s.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
    }
    public static byte[] decode(String raw) {
        String text = normalize(raw);
        if (!text.matches("[A-Z2-7]+={0,6}")) throw new IllegalArgumentException("密钥必须是 Base32：字母 A–Z 和数字 2–7");
        int end = text.indexOf('=');
        String s = end < 0 ? text : text.substring(0, end);
        int rem = s.length() % 8;
        if (rem == 1 || rem == 3 || rem == 6 || (end >= 0 && text.length() % 8 != 0))
            throw new IllegalArgumentException("Base32 密钥长度或填充错误");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buffer = 0, bits = 0;
        for (char c : s.toCharArray()) {
            int value = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(c);
            buffer = (buffer << 5) | value; bits += 5;
            if (bits >= 8) { bits -= 8; out.write((buffer >> bits) & 255); }
        }
        if (bits > 0 && (buffer & ((1 << bits) - 1)) != 0) throw new IllegalArgumentException("Base32 密钥末尾不正确");
        return out.toByteArray();
    }
    public static String code(Account a, long seconds) {
        if (a.passwordOnly) throw new IllegalArgumentException("此条目尚未添加验证码");
        if (seconds < 0) throw new IllegalArgumentException("设备时间不正确");
        try {
            Mac mac = Mac.getInstance("Hmac" + a.algorithm);
            mac.init(new SecretKeySpec(decode(a.secret), "Hmac" + a.algorithm));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(seconds / a.period).array());
            int offset = hash[hash.length - 1] & 15;
            int n = ((hash[offset] & 127) << 24) | ((hash[offset + 1] & 255) << 16)
                | ((hash[offset + 2] & 255) << 8) | (hash[offset + 3] & 255);
            return String.format(Locale.ROOT, "%0" + a.digits + "d", n % (a.digits == 6 ? 1000000 : 100000000));
        } catch (java.security.GeneralSecurityException e) { throw new IllegalStateException("设备不支持此加密算法", e); }
    }
    public static Account parse(String input) {
        try {
            if (input.length() > 4096) throw new IllegalArgumentException("二维码内容过长");
            URI uri = new URI(input.trim());
            if (!"otpauth".equalsIgnoreCase(uri.getScheme()) || !"totp".equalsIgnoreCase(uri.getHost()))
                throw new IllegalArgumentException("请扫描 TOTP 验证器二维码，不支持 HOTP 或普通网址");
            Map<String, String> p = new HashMap<>();
            if (uri.getRawQuery() == null) throw new IllegalArgumentException("二维码缺少密钥");
            for (String pair : uri.getRawQuery().split("&")) {
                String[] kv = pair.split("=", 2);
                String k = decodeUrl(kv[0]);
                if (p.containsKey(k)) throw new IllegalArgumentException("二维码包含重复参数");
                p.put(k, kv.length == 2 ? decodeUrl(kv[1]) : "");
            }
            String rawPath = uri.getRawPath();
            String label = rawPath == null ? "" : decodeUrl(rawPath.startsWith("/") ? rawPath.substring(1) : rawPath);
            int colon = label.indexOf(':');
            String prefix = colon < 0 ? "" : label.substring(0, colon).trim();
            String issuer = p.getOrDefault("issuer", prefix).trim();
            if (!prefix.isEmpty() && !issuer.equals(prefix)) throw new IllegalArgumentException("二维码中的服务名称不一致");
            if (!p.containsKey("secret")) throw new IllegalArgumentException("二维码缺少密钥");
            return new Account(issuer, colon < 0 ? label : label.substring(colon + 1), p.get("secret"),
                p.getOrDefault("algorithm", "SHA1"), Integer.parseInt(p.getOrDefault("digits", "6")),
                Integer.parseInt(p.getOrDefault("period", "30")));
        } catch (NumberFormatException e) { throw new IllegalArgumentException("二维码的位数或周期无效"); }
          catch (java.net.URISyntaxException e) { throw new IllegalArgumentException("二维码格式无效"); }
    }
    private static String decodeUrl(String s) {
        try { return URLDecoder.decode(s.replace("+", "%2B"), "UTF-8"); }
        catch (java.io.UnsupportedEncodingException e) { throw new AssertionError(e); }
    }
}
