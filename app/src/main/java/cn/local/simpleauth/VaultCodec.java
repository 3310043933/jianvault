package cn.local.simpleauth;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Converts only in-memory plaintext; Vault encrypts the entire result before any file write. */
final class VaultCodec {
    private VaultCodec() {}
    static VaultState decode(String text) throws Exception {
        JSONTokener parser = new JSONTokener(text); Object root = parser.nextValue();
        if (parser.nextClean() != 0) throw new IllegalArgumentException("保险库包含无效的附加数据");
        JSONArray array; List<PasswordGroup> groups = new ArrayList<>();
        if (root instanceof JSONArray) array = (JSONArray) root; // v1.0 through v1.2.1
        else if (root instanceof JSONObject) {
            JSONObject object = (JSONObject) root;
            if (object.getInt("format") != 2) throw new IllegalArgumentException("保险库版本不受支持");
            array = object.getJSONArray("accounts"); JSONArray saved = object.getJSONArray("groups");
            for (int i = 0; i < saved.length(); i++) { JSONObject g = saved.getJSONObject(i); groups.add(new PasswordGroup(g.getString("id"), g.getString("name"))); }
        } else throw new IllegalArgumentException("保险库格式无效");
        List<Otp.Account> accounts = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.getJSONObject(i); List<RecoveryCodes.Entry> codes = new ArrayList<>();
            if (o.has("recoveryCodes")) {
                JSONArray saved = o.getJSONArray("recoveryCodes");
                for (int j = 0; j < saved.length(); j++) { JSONObject e = saved.getJSONObject(j); codes.add(new RecoveryCodes.Entry(e.getString("code"), e.getBoolean("used"))); }
            }
            LoginDetails login = LoginDetails.EMPTY;
            if (o.has("login")) { JSONObject l = o.getJSONObject("login"); login = new LoginDetails(l.getString("website"), l.getString("username"), l.getString("password"), l.optString("notes", "")); }
            String kind = o.optString("kind", "totp");
            if (!kind.equals("totp") && !kind.equals("password")) throw new IllegalArgumentException("无法识别的账号类型");
            accounts.add(new Otp.Account(o.getString("issuer"), o.getString("name"), o.getString("secret"), o.getString("algorithm"),
                o.getInt("digits"), o.getInt("period"), codes, login, kind.equals("password"), o.optString("groupId", "")));
        }
        return new VaultState(accounts, groups);
    }
    static String encode(VaultState state) throws Exception {
        JSONArray accounts = new JSONArray(), groups = new JSONArray();
        for (PasswordGroup g : state.groups) groups.put(new JSONObject().put("id", g.id).put("name", g.name));
        for (Otp.Account a : state.accounts) {
            JSONArray codes = new JSONArray();
            for (RecoveryCodes.Entry e : a.recoveryCodes) codes.put(new JSONObject().put("code", e.code).put("used", e.used));
            accounts.put(new JSONObject().put("issuer", a.issuer).put("name", a.name).put("secret", a.secret).put("algorithm", a.algorithm)
                .put("digits", a.digits).put("period", a.period).put("recoveryCodes", codes).put("kind", a.passwordOnly ? "password" : "totp")
                .put("groupId", a.groupId).put("login", new JSONObject().put("website", a.login.website).put("username", a.login.username).put("password", a.login.password).put("notes", a.login.notes)));
        }
        return new JSONObject().put("format", 2).put("accounts", accounts).put("groups", groups).toString();
    }
}
