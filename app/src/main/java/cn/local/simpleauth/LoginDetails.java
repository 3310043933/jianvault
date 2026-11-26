package cn.local.simpleauth;

/** Immutable login data. Passwords are never trimmed, normalized, or logged. */
public final class LoginDetails {
    public static final LoginDetails EMPTY = new LoginDetails("", "", "");
    public final String website, username, password, notes;
    public LoginDetails(String website, String username, String password) {
        this(website, username, password, "");
    }
    public LoginDetails(String website, String username, String password, String notes) {
        if (website == null || username == null || password == null || notes == null) throw new IllegalArgumentException("登录信息不能为 null");
        if (website.length() > 2048) throw new IllegalArgumentException("网站地址最多 2048 字符");
        if (username.length() > 200) throw new IllegalArgumentException("登录账号最多 200 字符");
        if (password.length() > 16384) throw new IllegalArgumentException("密码最多 16384 字符");
        if (notes.length() > 32768) throw new IllegalArgumentException("备注最多 32768 字符");
        this.website = website.trim(); this.username = username; this.password = password;
        this.notes = notes;
    }
    public boolean isEmpty() { return website.isEmpty() && username.isEmpty() && password.isEmpty() && notes.isEmpty(); }
    public void requireCredentials() {
        if (username.trim().isEmpty() || password.isEmpty()) throw new IllegalArgumentException("请填写登录账号和密码");
    }
}
