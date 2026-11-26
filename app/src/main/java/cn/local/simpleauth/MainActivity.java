package cn.local.simpleauth;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final int GREEN = 0xFF17664F, INK = 0xFF172C25, MUTED = 0xFF61716A, BG = 0xFFF4F7F3;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Otp.Account> accounts = new ArrayList<>();
    private final List<PasswordGroup> groups = new ArrayList<>();
    private final List<Runnable> updates = new ArrayList<>();
    private LinearLayout root, content;
    private DrawerHost drawer;
    private enum Screen { OTP, PASSWORDS, BACKUP, HELP }
    private Screen screen = Screen.OTP;
    private String selectedGroupId, passwordQuery = "";
    private Vault vault;
    private boolean unlocked, authenticating, fatal;
    private String pendingUri;
    private android.net.Uri pendingBackupRead, pendingBackupWrite;
    private String pendingBackupFileText;
    private int backupGeneration;
    private final java.util.concurrent.ExecutorService backupWorker = java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final class BackupFileHandoff {
        final String encrypted; final android.net.Uri read, write;
        BackupFileHandoff(String encrypted, android.net.Uri read, android.net.Uri write) { this.encrypted = encrypted; this.read = read; this.write = write; }
    }
    private AlertDialog activeDialog;
    private final List<AlertDialog> secureDialogs = new ArrayList<>();
    private final Runnable ticker = new Runnable() {
        @Override public void run() { if (unlocked) { for (Runnable r : updates) r.run(); handler.postDelayed(this, 250); } }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        vault = new Vault(this);
        Object retained = getLastNonConfigurationInstance();
        if (retained instanceof BackupFileHandoff) {
            BackupFileHandoff files = (BackupFileHandoff)retained; pendingBackupFileText = files.encrypted; pendingBackupRead = files.read; pendingBackupWrite = files.write;
        }
        if (state != null) authenticating = state.getBoolean("authenticating", false);
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BG);
        root.setFocusableInTouchMode(true);
        if (Build.VERSION.SDK_INT >= 29) root.setImportantForContentCapture(View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS);
        root.setPadding(dp(22), dp(16), dp(22), dp(12));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = Build.VERSION.SDK_INT >= 30
                ? insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.ime()) : null;
            int top = bars != null ? bars.top : insets.getSystemWindowInsetTop();
            int bottom = bars != null ? bars.bottom : insets.getSystemWindowInsetBottom();
            root.setPadding(dp(22) + (bars != null ? bars.left : 0), top + dp(16), dp(22) + (bars != null ? bars.right : 0), bottom + dp(12));
            if (drawer != null) drawer.setBars(top, bottom);
            return insets;
        });
        drawer = new DrawerHost(this, root, () -> unlocked, this::drawerMenu);
        setContentView(drawer);
        if (Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::goBack);
        lockedScreen();
    }
    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); out.putBoolean("authenticating", authenticating); }
    @Override public Object onRetainNonConfigurationInstance() { return new BackupFileHandoff(pendingBackupFileText, pendingBackupRead, pendingBackupWrite); }
    @Override protected void onResume() { super.onResume(); if (!isFinishing() && !unlocked && !authenticating) unlock(); }
    @Override protected void onStop() {
        super.onStop(); handler.removeCallbacks(ticker);
        if (!authenticating) {
            backupGeneration++;
            unlocked = false; accounts.clear(); groups.clear(); updates.clear(); drawer.close();
            screen = Screen.OTP; selectedGroupId = null; passwordQuery = "";
            for (AlertDialog dialog : new ArrayList<>(secureDialogs)) dialog.dismiss();
            secureDialogs.clear();
            if (activeDialog != null) { activeDialog.dismiss(); activeDialog = null; }
            lockedScreen();
        }
    }
    @Override protected void onDestroy() { backupGeneration++; backupWorker.shutdown(); super.onDestroy(); }
    private void lockedScreen() {
        root.removeAllViews();
        root.addView(text("简验证", 32, INK, true)); space(root, 16);
        root.addView(text("使用手机锁屏密码或系统支持的身份验证方式解锁。", 16, MUTED, false));
        space(root, 24); button(root, "解锁保险库", this::unlock);
    }
    @SuppressWarnings("deprecation") private void unlock() {
        KeyguardManager keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (!keyguard.isDeviceSecure()) {
            root.removeAllViews(); root.addView(text("先保护你的手机", 28, INK, true)); space(root, 16);
            root.addView(text("请先在系统设置中启用锁屏密码、图案或 PIN。之后即可安全添加账号。", 16, MUTED, false));
            space(root, 20); button(root, "打开安全设置", () -> startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS)));
            return;
        }
        Intent intent = keyguard.createConfirmDeviceCredentialIntent("解锁简验证", "验证手机身份后查看账号保险库");
        if (intent == null) { toast("系统暂时无法验证身份，请重试"); return; }
        authenticating = true; startActivityForResult(intent, 71);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 71) {
            authenticating = false;
            if (result != RESULT_OK) { finish(); return; }
            unlocked = true;
            try { VaultState state = vault.load(); accounts.clear(); accounts.addAll(state.accounts); groups.clear(); groups.addAll(state.groups); fatal = false; }
            catch (Exception e) { fatal = true; }
            render(); handler.removeCallbacks(ticker); handler.post(ticker);
            if (pendingUri != null && !fatal) { String uri = pendingUri; pendingUri = null; importUri(uri); }
            continueBackupFiles();
            return;
        }
        if (request == 72 && result == RESULT_OK && data != null) pendingUri = data.getStringExtra("SCAN_RESULT");
        if (request == 73 && result == RESULT_OK && data != null) pendingBackupRead = data.getData();
        if (request == 74) {
            if (result == RESULT_OK && data != null) pendingBackupWrite = data.getData();
            else pendingBackupFileText = null;
        }
        continueBackupFiles();
    }
    private void continueBackupFiles() {
        if (!unlocked || authenticating) return;
        if (pendingBackupRead != null) { android.net.Uri uri = pendingBackupRead; pendingBackupRead = null; readBackupFile(uri); }
        if (pendingBackupWrite != null) {
            android.net.Uri uri = pendingBackupWrite; String text = pendingBackupFileText;
            pendingBackupWrite = null; pendingBackupFileText = null;
            if (text != null) writeBackupFile(uri, text); else toast("备份文本已失效，请重新导出后保存");
        }
    }
    private void render() {
        updates.clear(); root.removeAllViews(); root.requestFocus();
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        TextView menu = text("☰", 27, GREEN, true); menu.setGravity(Gravity.CENTER); menu.setContentDescription("打开导航侧边栏");
        menu.setOnClickListener(v -> drawer.open()); header.addView(menu, new LinearLayout.LayoutParams(dp(48), dp(48)));
        String pageTag = screen == Screen.PASSWORDS ? "PASSWORD VAULT" : screen == Screen.BACKUP ? "ENCRYPTED BACKUP" : screen == Screen.HELP ? "HELP" : "AUTHENTICATOR";
        TextView tag = text(pageTag, 11, GREEN, true); tag.setLetterSpacing(.13f); header.addView(tag); root.addView(header);
        if (screen == Screen.HELP) { renderHelp(); return; }
        if (screen == Screen.BACKUP) { renderBackupPage(); return; }
        if (screen == Screen.PASSWORDS && !fatal) { renderPasswords(); return; }
        space(root, 4); root.addView(text("简验证", 34, INK, true));
        root.addView(text("动态验证码 · 本机加密保存", 14, MUTED, false)); space(root, 20);
        if (fatal) {
            root.addView(text("无法读取账号保险库", 23, INK, true)); space(root, 12);
            root.addView(text("数据或系统密钥不可用。为保护原文件，已停止写入。请勿卸载或清除数据；可尝试重新启动手机。", 16, MUTED, false));
            return;
        }
        LinearLayout actions = new LinearLayout(this); actions.setOrientation(LinearLayout.HORIZONTAL);
        Button scan = makeButton("扫码添加", this::scan); actions.addView(scan, new LinearLayout.LayoutParams(0, dp(52), 1));
        Space gap = new Space(this); actions.addView(gap, new LinearLayout.LayoutParams(dp(10), 1));
        Button manual = makeButton("手动添加", this::manual); actions.addView(manual, new LinearLayout.LayoutParams(0, dp(52), 1)); root.addView(actions);
        space(root, 18);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); scroll.addView(content);
        boolean hasOtp = false;
        for (Otp.Account a : accounts) if (!a.passwordOnly) hasOtp = true;
        if (!hasOtp) {
            LinearLayout card = card(); space(card, 12); card.addView(text("添加动态验证码", 23, INK, true)); space(card, 12);
            card.addView(text("扫描 GitHub 等网站的验证器二维码，或手动输入验证码密钥。\n\n固定的网站账号密码在左上角侧边栏中，支持按组整理。", 16, MUTED, false));
            space(card, 20); card.addView(text("无需登录 · 无需联网 · 无广告", 12, GREEN, true)); content.addView(card);
        } else {
            for (Otp.Account a : accounts) if (!a.passwordOnly) accountCard(a);
        }
        space(root, 12);
        TextView help = text("使用说明与恢复提醒  ↗", 13, MUTED, false); help.setPadding(0, dp(8), 0, dp(8)); help.setOnClickListener(v -> help()); root.addView(help);
    }
    @Override public void onBackPressed() { goBack(); }
    private void goBack() {
        if (drawer.isOpen()) { drawer.close(); return; }
        if (screen != Screen.OTP) { screen = Screen.OTP; passwordQuery = ""; render(); return; }
        finish();
    }
    private boolean hasGroup(String id) { for (PasswordGroup g : groups) if (g.id.equals(id)) return true; return false; }
    private String groupName(String id) { for (PasswordGroup g : groups) if (g.id.equals(id)) return g.name; return "未分组"; }
    private void openScreen(Screen next) { drawer.close(); screen = next; passwordQuery = ""; render(); }
    private void openPasswords(String groupId) { selectedGroupId = groupId; openScreen(Screen.PASSWORDS); }
    private View drawerMenu() {
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(18), dp(24), dp(18), dp(24));
        body.addView(text("简验证", 29, INK, true)); body.addView(text("本机加密保险库", 13, MUTED, false)); space(body, 24);
        navItem(body, "动态验证码", screen == Screen.OTP, () -> openScreen(Screen.OTP));
        navItem(body, "网站账号密码", screen == Screen.PASSWORDS, () -> openPasswords(null));
        navItem(body, "导入导出加密数据", screen == Screen.BACKUP, () -> openScreen(Screen.BACKUP));
        navItem(body, "使用说明", screen == Screen.HELP, () -> openScreen(Screen.HELP));
        return body;
    }
    private void navItem(LinearLayout parent, String label, boolean selected, Runnable action) {
        TextView item = text(label, 15, selected ? GREEN : INK, selected); item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(dp(14), dp(12), dp(14), dp(12)); item.setMinHeight(dp(52));
        if (selected) { GradientDrawable bg = new GradientDrawable(); bg.setColor(0xFFE1EDE5); bg.setCornerRadius(dp(14)); item.setBackground(bg); }
        item.setOnClickListener(v -> action.run()); parent.addView(item, new LinearLayout.LayoutParams(-1, -2));
    }
    private void renderPasswords() {
        space(root, 4); root.addView(text("网站账号密码", 29, INK, true));
        root.addView(text("固定登录信息 · 按名称排序 · 密码默认隐藏", 12, MUTED, false)); space(root, 14);
        button(root, "＋ 保存网站账号密码", () -> editLogin(null)); space(root, 10);
        LinearLayout groupBar = new LinearLayout(this); groupBar.setGravity(Gravity.CENTER_VERTICAL);
        Spinner selector = new Spinner(this); List<String> groupIds = new ArrayList<>(), labels = new ArrayList<>();
        groupIds.add(null); labels.add("全部网站"); groupIds.add(""); labels.add("未分组");
        for (PasswordGroup g : groups) { groupIds.add(g.id); labels.add(g.name); }
        ArrayAdapter<String> groupAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        groupAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); selector.setAdapter(groupAdapter);
        selector.setContentDescription("筛选网站分组"); selector.setSelection(Math.max(0, groupIds.indexOf(selectedGroupId)));
        groupBar.addView(selector, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button manage = new Button(this); manage.setText("管理分组"); manage.setAllCaps(false); manage.setOnClickListener(v -> manageGroups());
        groupBar.addView(manage, new LinearLayout.LayoutParams(-2, dp(48))); root.addView(groupBar);
        selector.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                String chosen = groupIds.get(position);
                if (!java.util.Objects.equals(chosen, selectedGroupId)) openPasswords(chosen);
            }
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        EditText search = new EditText(this); search.setHint("搜索名称、地址或账号"); search.setTextSize(15); search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS); search.setText(passwordQuery); search.setSaveEnabled(false); search.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH | android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        root.addView(search, new LinearLayout.LayoutParams(-1, dp(52)));
        TextView count = text("", 12, MUTED, false); count.setPadding(0, dp(10), 0, dp(10)); root.addView(count);
        FrameLayout listArea = new FrameLayout(this); root.addView(listArea, new LinearLayout.LayoutParams(-1, 0, 1));
        ListView list = new ListView(this); list.setDivider(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)); list.setDividerHeight(dp(8));
        list.setClipToPadding(false); list.setPadding(0, dp(2), 0, dp(12)); listArea.addView(list, new FrameLayout.LayoutParams(-1, -1));
        TextView empty = text("", 16, MUTED, false); empty.setGravity(Gravity.CENTER); empty.setPadding(dp(12), dp(20), dp(12), dp(20));
        listArea.addView(empty, new FrameLayout.LayoutParams(-1, -1)); list.setEmptyView(empty);
        PasswordAdapter adapter = new PasswordAdapter(); list.setAdapter(adapter);
        Runnable filter = () -> {
            adapter.items = PasswordPresentation.select(accounts, selectedGroupId, passwordQuery); adapter.notifyDataSetChanged();
            count.setText(adapter.items.size() + " 个网站条目");
            empty.setText(passwordQuery.trim().isEmpty() ? "这里还没有网站密码\n点击上方按钮添加，或切换分组查看" : "没有找到匹配的网站\n试试其他名称、地址或账号");
        };
        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { passwordQuery = s.toString(); filter.run(); }
            public void afterTextChanged(android.text.Editable s) {}
        });
        list.setOnItemClickListener((parent, view, position, id) -> {
            ((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(search.getWindowToken(), 0);
            login(adapter.items.get(position));
        });
        filter.run();
    }
    private void renderBackupPage() {
        space(root, 4); root.addView(text("导入导出加密数据", 27, INK, true));
        root.addView(text("手动备份与恢复 · Key 由你输入", 13, MUTED, false)); space(root, 18);
        ScrollView scroll = new ScrollView(this); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); scroll.addView(body);
        LinearLayout export = card(); export.addView(text("导出全部数据", 21, INK, true)); space(export, 10);
        export.addView(text(fatal ? "当前保险库无法读取，可以从下方导入有效备份进行恢复。" : "包含动态验证码、网站账号密码、分组、备注和恢复码。输入并确认 Key 后，复制加密文本或保存为 TXT。", 14, MUTED, false));
        if (!fatal) {
            space(export, 12); export.addView(text("本机共 " + accounts.size() + " 个条目、" + groups.size() + " 个分组", 12, GREEN, true));
            space(export, 16); button(export, "加密导出全部数据", this::exportBackup);
        }
        space(export, 6); body.addView(export); space(body, 14);
        LinearLayout restore = card(); restore.addView(text("导入全部数据", 21, INK, true)); space(restore, 10);
        restore.addView(text("选择加密 TXT 或粘贴加密文本，输入相同 Key。解析后预览新增与跳过数量：完全相同跳过，任一字段不同则新增，保留本机现有记录。", 14, MUTED, false));
        space(restore, 16); button(restore, "选择文件 / 粘贴导入", () -> importBackup(null)); space(restore, 6); body.addView(restore);
        space(body, 18); body.addView(text("Key 不保存在备份里，请单独保管。默认提供合并导入；如需整库替换，可单独选择覆盖恢复并再次确认。", 12, MUTED, false));
    }
    private void renderHelp() {
        space(root, 4); root.addView(text("使用说明", 29, INK, true)); space(root, 18);
        ScrollView scroll = new ScrollView(this); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); scroll.addView(body);
        helpSection(body, "导航", "点击左上角 ☰ 或从左侧中部向右滑动。侧边栏固定为动态验证码、网站账号密码、导入导出加密数据、使用说明四项，各自打开主页面。");
        helpSection(body, "动态验证码", "扫码添加或手动输入网站提供的验证码密钥。验证码按周期更新，请开启手机自动日期与时间。恢复码可在对应验证码条目中保存，并在网站使用成功后手动标记已用。");
        helpSection(body, "网站账号密码", "进入本页面后新增、搜索或打开网站。页面内的分组选择器可切换全部网站、未分组和自建分组；点击管理分组来创建、重命名或删除组。删除组只把条目移至未分组。");
        helpSection(body, "密码与备注", "密码只会在你编辑保存时改变。详情页默认隐藏密码，可查看、编辑、移动分组或删除。备注支持多行，与密码一起加密保存。网站密码页面不提供恢复码入口。");
        helpSection(body, "复制", "一键复制全部会复制该网站的名称、地址、账号、密码，以及非空备注。复制会包含明文密码；系统允许时会在 30 秒后尝试清除，后台可能受系统限制。");
        helpSection(body, "加密导出", "进入导入导出加密数据页面，点击加密导出。输入并确认你自己的 Key（12～1024 字符），生成加密文本后复制或保存 TXT。Key 按原样使用，不会保存到备份中，丢失后无法解密。");
        helpSection(body, "合并导入", "选择文件或粘贴加密文本，输入相同 Key。解析后先预览新增和跳过数量。只有所有字段都完全相同才跳过，任一字段不同都作为另一条记录新增；保留现有记录。同名分组先对应起来，内部编号不同不会产生重复分组。密码、备注的大小写与空格、验证码参数、恢复码顺序及已用状态都参与比较。");
        helpSection(body, "覆盖恢复", "若需要整库替换，可在预览中单独选择覆盖恢复并再次确认。此操作会替换现有数据，建议先导出当前数据。本机保险库无法读取时不能判断重复，仅提供覆盖恢复。导入后使用此手机的密钥重新加密保存。");
        helpSection(body, "数据保护", "应用完全离线，无云端自动同步。进入前台需通过系统身份验证，敏感界面禁止截图。卸载或清除数据前请备份；只有持有加密备份和对应 Key 才能恢复。");
        helpSection(body, "测试版说明", "已进行算法、数据和加密备份测试，尚未完成真机界面、文件选择器及 Android Keystore 端到端验证，也未经过独立安全审计。");
    }
    private void helpSection(LinearLayout body, String title, String description) {
        body.addView(text(title, 18, INK, true)); space(body, 8); body.addView(text(description, 14, MUTED, false)); space(body, 24);
    }
    private final class PasswordAdapter extends BaseAdapter {
        List<Otp.Account> items = new ArrayList<>();
        public int getCount() { return items.size(); }
        public Otp.Account getItem(int position) { return items.get(position); }
        public long getItemId(int position) { return position; }
        public View getView(int position, View recycled, android.view.ViewGroup parent) {
            LinearLayout row; TextView[] labels;
            if (recycled == null) {
                row = new LinearLayout(MainActivity.this); row.setOrientation(LinearLayout.VERTICAL); row.setPadding(dp(18), dp(14), dp(18), dp(14));
                GradientDrawable bg = new GradientDrawable(); bg.setColor(Color.WHITE); bg.setCornerRadius(dp(16)); bg.setStroke(dp(1), 0xFFE1E9E1); row.setBackground(bg);
                labels = new TextView[] {text("", 17, INK, true), text("", 13, MUTED, false), text("", 12, GREEN, false)};
                for (TextView label : labels) { label.setSingleLine(true); label.setEllipsize(android.text.TextUtils.TruncateAt.END); row.addView(label); }
                row.setTag(labels);
            } else { row = (LinearLayout) recycled; labels = (TextView[]) row.getTag(); }
            Otp.Account a = items.get(position); labels[0].setText(a.issuer.isEmpty() ? "网站账号" : a.issuer);
            labels[1].setText(a.login.username + "  ·  " + groupName(a.groupId)); labels[2].setText(a.login.website.isEmpty() ? "未填写地址  ›" : a.login.website + "  ›");
            return row;
        }
    }
    private void createGroup(java.util.function.Consumer<PasswordGroup> callback) {
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(20), 0, dp(20), dp(12));
        EditText name = field(form, "例如 工作、个人、购物", false);
        AlertDialog dialog = showSecure(new AlertDialog.Builder(this).setTitle("新建分组").setView(form).setNegativeButton("取消", null).setPositiveButton("创建", null).create());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                PasswordGroup group = PasswordGroup.create(name.getText().toString()); VaultState next = new VaultState(accounts, groups).addGroup(group);
                if (persist(next.accounts, next.groups)) { dialog.dismiss(); if (callback != null) callback.accept(group); }
            } catch (IllegalArgumentException e) { name.setError(e.getMessage()); }
        });
    }
    private void manageGroups() {
        String[] labels = new String[groups.size() + 1]; labels[0] = "＋ 新建分组";
        List<PasswordGroup> snapshot = new ArrayList<>(groups);
        for (int i = 0; i < snapshot.size(); i++) labels[i + 1] = snapshot.get(i).name;
        showSecure(new AlertDialog.Builder(this).setTitle("管理分组").setItems(labels, (d, which) -> {
            if (which == 0) { createGroup(g -> manageGroups()); return; }
            PasswordGroup group = snapshot.get(which - 1);
            showSecure(new AlertDialog.Builder(this).setTitle(group.name).setItems(new String[] {"查看组内网站", "重命名", "删除分组（网站移至未分组）"}, (dialog, action) -> {
                if (action == 0) openPasswords(group.id);
                else if (action == 1) renameGroup(group);
                else showSecure(new AlertDialog.Builder(this).setTitle("删除「" + group.name + "」？").setMessage("组内网站将移到「未分组」，不会删除账号密码。")
                    .setNegativeButton("取消", null).setPositiveButton("删除分组", (confirm, button) -> {
                        VaultState next = new VaultState(accounts, groups).deleteGroup(group.id);
                        if (persist(next.accounts, next.groups)) toast("分组已删除，网站已移到未分组");
                    }).create());
            }).setNegativeButton("取消", null).create());
        }).setNegativeButton("关闭", null).create());
    }
    private void renameGroup(PasswordGroup group) {
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(20), 0, dp(20), dp(12));
        EditText name = field(form, "分组名称", false); name.setText(group.name);
        AlertDialog dialog = showSecure(new AlertDialog.Builder(this).setTitle("重命名分组").setView(form).setNegativeButton("取消", null).setPositiveButton("保存", null).create());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try { VaultState next = new VaultState(accounts, groups).renameGroup(group.id, name.getText().toString()); if (persist(next.accounts, next.groups)) dialog.dismiss(); }
            catch (IllegalArgumentException e) { name.setError(e.getMessage()); }
        });
    }
    private void accountCard(Otp.Account a) {
        LinearLayout card = card();
        card.addView(text(a.passwordOnly ? "网站密码" : "动态验证码 · TOTP", 11, GREEN, true)); space(card, 6);
        card.addView(text(a.issuer.isEmpty() ? "验证账号" : a.issuer, 19, INK, true)); card.addView(text(a.name, 14, MUTED, false));
        if (!a.passwordOnly) {
        TextView code = text("", 37, GREEN, true); code.setTypeface(Typeface.MONOSPACE, Typeface.BOLD); code.setLetterSpacing(.05f); code.setPadding(0, dp(14), 0, dp(8));
        code.setContentDescription("验证码，点击复制"); card.addView(code);
        TextView remaining = text("", 12, MUTED, false); card.addView(remaining);
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); bar.setMax(a.period * 1000);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(GREEN)); card.addView(bar, new LinearLayout.LayoutParams(-1, dp(12)));
        Runnable update = () -> {
            long now = System.currentTimeMillis(); String value = Otp.code(a, now / 1000);
            code.setText(value.substring(0, value.length() / 2) + " " + value.substring(value.length() / 2));
            int left = a.period * 1000 - (int) (now % (a.period * 1000L));
            remaining.setText("剩余 " + ((left + 999) / 1000) + " 秒  ·  点击数字复制"); bar.setProgress(left);
        };
        updates.add(update); update.run();
        code.setOnClickListener(v -> copy(a));
        } else {
            space(card, 12); card.addView(text("网站登录信息 · 已加密保存", 13, MUTED, false));
        }
        TextView recovery = text(a.recoveryCodes.isEmpty() ? "＋ 保存恢复码" : "恢复码  ·  " + RecoveryCodes.remaining(a.recoveryCodes) + " / " + a.recoveryCodes.size() + " 条未使用", 14, GREEN, true);
        recovery.setPadding(0, dp(16), 0, dp(16));
        recovery.setOnClickListener(v -> recovery(a)); card.addView(recovery);
        TextView remove = text("移除账号", 12, MUTED, false); remove.setGravity(Gravity.END); remove.setPadding(0, dp(12), 0, dp(8));
        remove.setOnClickListener(v -> {
            activeDialog = new AlertDialog.Builder(this).setTitle("移除 " + a.name + "？")
                .setMessage("移除时会一并删除此条目的登录账号、密码、验证码密钥和恢复码。请先确认已在其他地方备份。移除本地条目不会关闭网站的双重验证。")
                .setNegativeButton("保留", null).setPositiveButton("确认移除", (d, w) -> {
                    List<Otp.Account> next = new ArrayList<>(accounts); next.remove(a); persist(next);
                }).show();
        }); card.addView(remove); content.addView(card); space(content, 12);
    }
    private void chooseOtp() {
        showSecure(new AlertDialog.Builder(this).setTitle("添加动态验证码")
            .setItems(new String[] {"扫描验证器二维码", "输入验证码密钥"}, (dialog, which) -> {
                if (which == 0) scan(); else manual();
            }).setNegativeButton("取消", null).create());
    }
    private void scan() {
        Intent intent = new Intent(this, SecureCaptureActivity.class);
        intent.setAction("com.google.zxing.client.android.SCAN");
        intent.putExtra("SCAN_FORMATS", "QR_CODE");
        intent.putExtra("PROMPT_MESSAGE", "扫描网站提供的验证器二维码");
        intent.putExtra("BEEP_ENABLED", false);
        intent.putExtra("BARCODE_IMAGE_ENABLED", false);
        intent.putExtra("SCAN_ORIENTATION_LOCKED", false);
        startActivityForResult(intent, 72);
    }
    private void importUri(String raw) {
        try {
            Otp.Account a = Otp.parse(raw);
            activeDialog = new AlertDialog.Builder(this).setTitle("添加验证账号")
                .setMessage((a.issuer.isEmpty() ? "验证账号" : a.issuer) + "\n" + a.name + "\n\n" + a.digits + " 位验证码，每 " + a.period + " 秒更新")
                .setNegativeButton("取消", null).setPositiveButton("添加", (d, w) -> add(a)).show();
        } catch (IllegalArgumentException e) { new AlertDialog.Builder(this).setTitle("无法导入").setMessage(e.getMessage()).setPositiveButton("知道了", null).show(); }
    }
    private void manual() {
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(24), dp(8), dp(24), dp(12));
        form.addView(text("填写网站双重验证页面提供的验证码密钥。这与网站登录密码是两种不同的信息，也可粘贴完整 otpauth:// 链接。", 14, MUTED, false));
        EditText issuer = field(form, "服务名称，例如 GitHub", false);
        EditText name = field(form, "账号，例如你的用户名", false);
        EditText secret = field(form, "验证码密钥（Base32）", true);
        form.addView(text("手动添加默认使用 SHA1、6 位、30 秒。特殊参数请扫描二维码或粘贴完整链接。", 12, MUTED, false));
        ScrollView scroll = new ScrollView(this); scroll.addView(form);
        AlertDialog dialog = showSecure(new AlertDialog.Builder(this).setTitle("输入验证码密钥").setView(scroll).setNegativeButton("取消", null).setPositiveButton("保存验证码", null).create());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                String s = secret.getText().toString().trim();
                Otp.Account a = s.regionMatches(true, 0, "otpauth:", 0, 8) ? Otp.parse(s)
                    : new Otp.Account(issuer.getText().toString(), name.getText().toString(), s, "SHA1", 6, 30);
                if (add(a)) dialog.dismiss();
            } catch (IllegalArgumentException e) { secret.setError(e.getMessage()); }
        });
    }
    private boolean add(Otp.Account a) {
        for (Otp.Account old : accounts) if (!a.passwordOnly && !old.passwordOnly && java.util.Arrays.equals(Otp.decode(old.secret), Otp.decode(a.secret)) && old.algorithm.equals(a.algorithm) && old.digits == a.digits && old.period == a.period) {
            toast("这个密钥已添加"); return false;
        }
        List<Otp.Account> next = new ArrayList<>(accounts); next.add(a); return persist(next);
    }
    private boolean persist(List<Otp.Account> next) {
        return persist(next, new ArrayList<>(groups));
    }
    private boolean persist(List<Otp.Account> next, List<PasswordGroup> nextGroups) {
        if (!unlocked || fatal) return false;
        try {
            VaultState state = new VaultState(next, nextGroups); vault.save(state);
            accounts.clear(); accounts.addAll(state.accounts); groups.clear(); groups.addAll(state.groups);
            if (selectedGroupId != null && !selectedGroupId.isEmpty() && !hasGroup(selectedGroupId)) selectedGroupId = "";
            render(); return true;
        }
        catch (Exception e) { toast("保存失败，原账号未被修改。请检查设备存储。"); return false; }
    }
    private void copy(Otp.Account a) {
        copySensitive(Otp.code(a, System.currentTimeMillis() / 1000), "一次性验证码", "验证码已复制，请在到期前使用");
    }
    private void copySensitive(String value, String label, String message) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText(label, value);
        PersistableBundle extras = new PersistableBundle(); extras.putBoolean("android.content.extra.IS_SENSITIVE", true); clip.getDescription().setExtras(extras);
        clipboard.setPrimaryClip(clip); toast(message);
        handler.postDelayed(() -> {
            try {
                ClipData current = clipboard.getPrimaryClip();
                if (current != null && current.getItemCount() > 0 && current.getItemAt(0).getText() != null
                    && value.contentEquals(current.getItemAt(0).getText())) {
                    if (Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip(); else clipboard.setPrimaryClip(ClipData.newPlainText("", ""));
                }
            } catch (SecurityException ignored) { /* Background clipboard access may be blocked. */ }
        }, 30000);
    }
    private AlertDialog showSecure(AlertDialog dialog) {
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        secureDialogs.add(dialog);
        dialog.setOnDismissListener(d -> secureDialogs.remove(dialog));
        activeDialog = dialog;
        dialog.show();
        return dialog;
    }
    private char[] readKey(EditText field) {
        android.text.Editable text = field.getText(); char[] result = new char[text.length()]; text.getChars(0, text.length(), result, 0); return result;
    }
    private LinearLayout backupForm() {
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(20), dp(8), dp(20), dp(12));
        if (Build.VERSION.SDK_INT >= 29) form.setImportantForContentCapture(View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS);
        return form;
    }
    private <T> void backupTask(String title, java.util.concurrent.Callable<T> work, java.util.function.Consumer<T> success) {
        int generation = ++backupGeneration;
        AlertDialog progress = showSecure(new AlertDialog.Builder(this).setTitle(title).setMessage("正在处理，请稍候……")
            .setCancelable(false).setNegativeButton("取消", (d, w) -> backupGeneration++).create());
        backupWorker.execute(() -> {
            try {
                T result = work.call();
                handler.post(() -> {
                    if (generation != backupGeneration || !unlocked || isDestroyed() || isFinishing()) return;
                    progress.dismiss(); success.accept(result);
                });
            } catch (Exception error) {
                String message = error instanceof java.security.GeneralSecurityException ? "Key 不正确，或备份内容已损坏；本机数据未被修改。"
                    : error instanceof java.io.IOException || error instanceof SecurityException ? "无法读写此文件，请检查文件权限、存储空间或换一个文件。"
                    : error instanceof IllegalArgumentException ? error.getMessage() : "备份内容无法解析，或此设备不支持所需功能。";
                handler.post(() -> {
                    if (generation != backupGeneration || !unlocked || isDestroyed() || isFinishing()) return;
                    progress.dismiss();
                    showSecure(new AlertDialog.Builder(this).setTitle("未完成").setMessage(message).setPositiveButton("知道了", null).create());
                });
            }
        });
    }
    private void exportBackup() {
        LinearLayout form = backupForm();
        form.addView(text("导出动态验证码、网站密码、分组、备注和恢复码。请输入 12～1024 字符的 Key，建议使用较长随机口令。Key 按原样使用，请单独保管，丢失后无法解密。", 14, MUTED, false));
        EditText key = field(form, "加密 Key（至少 12 字符）", true), confirm = field(form, "再次输入相同 Key", true);
        ScrollView scroll = new ScrollView(this); scroll.addView(form);
        AlertDialog dialog = showSecure(new AlertDialog.Builder(this).setTitle("加密导出全部数据").setView(scroll)
            .setNegativeButton("取消", null).setPositiveButton("加密并复制", null).create());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            char[] first = readKey(key), second = readKey(confirm);
            try {
                BackupCodec.validateKey(first);
                if (!java.util.Arrays.equals(first, second)) throw new IllegalArgumentException("两次输入的 Key 不一致");
            } catch (IllegalArgumentException error) {
                java.util.Arrays.fill(first, '\0'); java.util.Arrays.fill(second, '\0'); confirm.setError(error.getMessage()); return;
            }
            java.util.Arrays.fill(second, '\0'); key.setText(""); confirm.setText(""); dialog.dismiss();
            VaultState snapshot = new VaultState(accounts, groups);
            backupTask("正在加密备份", () -> { try { return BackupCodec.encrypt(snapshot, first); } finally { java.util.Arrays.fill(first, '\0'); } }, this::showExportedBackup);
        });
    }
    private boolean copyEncryptedBackup(String text) {
        if (text.length() > BackupCodec.CLIPBOARD_LIMIT) { toast("备份较大，无法完整放入剪贴板，请保存为 TXT"); return false; }
        try {
            ClipData clip = ClipData.newPlainText("简验证加密备份", text); PersistableBundle extras = new PersistableBundle();
            extras.putBoolean("android.content.extra.IS_SENSITIVE", true); clip.getDescription().setExtras(extras);
            ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(clip); toast("全部数据的加密文本已复制"); return true;
        } catch (RuntimeException error) { toast("剪贴板无法接收，请保存为 TXT"); return false; }
    }
    private void showExportedBackup(String text) {
        boolean copied = copyEncryptedBackup(text);
        showSecure(new AlertDialog.Builder(this).setTitle("加密备份已生成")
            .setMessage((copied ? "已复制到剪贴板，可粘贴到 TXT 文件或另一台设备的导入页面。" : "请点击保存 TXT，完整保存此备份。")
                + "\n\n共 " + text.length() + " 个字符，备份中不包含 Key。请将 Key 与备份分开保管。")
            .setNegativeButton("关闭", null).setPositiveButton("再次复制", (d, w) -> copyEncryptedBackup(text))
            .setNeutralButton("保存 TXT", (d, w) -> {
                pendingBackupFileText = text;
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain");
                intent.putExtra(Intent.EXTRA_TITLE, "简验证-加密备份-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(new java.util.Date()) + ".txt");
                try { startActivityForResult(intent, 74); } catch (android.content.ActivityNotFoundException error) { pendingBackupFileText = null; toast("系统没有可用的文件保存程序，请复制加密文本"); }
            }).create());
    }
    private void importBackup(String fileText) {
        LinearLayout form = backupForm();
        form.addView(text("选择加密 TXT 备份或粘贴加密文本，再输入导出时的 Key。成功解析后会先显示数量，确认前不会修改本机数据。", 14, MUTED, false));
        EditText pasted = null;
        if (fileText == null) {
            pasted = new EditText(this); pasted.setHint("粘贴 SIMPLEAUTH-BACKUP-1 开头的加密文本"); pasted.setTextSize(13); pasted.setGravity(Gravity.TOP);
            pasted.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            pasted.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING); pasted.setSaveEnabled(false); pasted.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
            form.addView(pasted, new LinearLayout.LayoutParams(-1, dp(130)));
        } else { space(form, 12); form.addView(text("已载入 TXT：" + fileText.length() + " 个字符", 14, GREEN, true)); }
        EditText input = pasted;
        Button pick = new Button(this); pick.setText("选择加密 TXT 文件"); form.addView(pick);
        EditText key = field(form, "解密 Key（与导出时完全一致）", true);
        ScrollView scroll = new ScrollView(this); scroll.addView(form);
        AlertDialog dialog = showSecure(new AlertDialog.Builder(this).setTitle("导入全部数据").setView(scroll)
            .setNegativeButton("取消", null).setPositiveButton("解析备份", null).create());
        pick.setOnClickListener(v -> {
            key.setText(""); dialog.dismiss();
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"text/plain", "application/octet-stream"});
            try { startActivityForResult(intent, 73); } catch (android.content.ActivityNotFoundException error) { toast("系统没有可用的文件选择程序，请粘贴加密文本"); }
        });
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            char[] secret = readKey(key);
            try { BackupCodec.validateKey(secret); } catch (IllegalArgumentException error) { java.util.Arrays.fill(secret, '\0'); key.setError(error.getMessage()); return; }
            String encrypted = fileText != null ? fileText : input.getText().toString();
            if (encrypted.trim().isEmpty() || encrypted.length() > BackupCodec.MAX_TEXT_BYTES) { java.util.Arrays.fill(secret, '\0'); toast("请提供完整备份，且不超过 12 MB"); return; }
            key.setText(""); if (input != null) input.setText(""); dialog.dismiss();
            backupTask("正在解密备份", () -> { try { return BackupCodec.decrypt(encrypted, secret); } finally { java.util.Arrays.fill(secret, '\0'); } }, this::confirmBackupRestore);
        });
    }
    private void confirmBackupRestore(VaultState restored) {
        int otp = 0, passwords = 0, recovery = 0;
        for (Otp.Account a : restored.accounts) { if (!a.passwordOnly) otp++; if (!a.login.isEmpty()) passwords++; recovery += a.recoveryCodes.size(); }
        VaultMerge.Result plan = null; String mergeMessage;
        if (fatal) mergeMessage = "本机保险库无法读取，不能比较重复记录，仅可选择覆盖恢复。";
        else {
            try {
                plan = VaultMerge.merge(new VaultState(accounts, groups), restored);
                mergeMessage = "合并预览：\n新增 " + plan.added + " 条\n跳过完全重复 " + plan.skipped + " 条\n新增分组 " + plan.groupsAdded + " 个"
                    + (plan.groupsRenamed > 0 ? "\n分组名称冲突 " + plan.groupsRenamed + " 个，已为导入组添加标记" : "")
                    + "\n\n全部字段完全相同才跳过，任一字段不同就新增。原有记录不会覆盖或删除。";
            } catch (IllegalArgumentException error) { mergeMessage = error.getMessage(); }
        }
        VaultMerge.Result merged = plan;
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle("备份已解析，选择导入方式")
            .setMessage("备份包含：\n动态验证码 " + otp + " 个\n网站密码 " + passwords + " 个\n分组 " + restored.groups.size() + " 个\n恢复码 " + recovery + " 条\n\n" + mergeMessage)
            .setNegativeButton("取消", null).setNeutralButton("覆盖恢复…", (d, w) -> confirmOverwrite(restored));
        if (merged != null) builder.setPositiveButton("合并导入", (d, w) -> {
            if (merged.added == 0 && merged.groupsAdded == 0) { toast("没有新增数据，已跳过 " + merged.skipped + " 条完全重复记录"); return; }
            applyImportedState(merged.state, true, "已新增 " + merged.added + " 条，跳过 " + merged.skipped + " 条完全重复记录");
        });
        showSecure(builder.create());
    }
    private void confirmOverwrite(VaultState restored) {
        showSecure(new AlertDialog.Builder(this).setTitle("确认覆盖本机全部数据？")
            .setMessage("将使用备份中的 " + restored.accounts.size() + " 个条目和 " + restored.groups.size() + " 个分组替换本机数据。备份中没有的本机记录将被删除。请先确认已有备份。")
            .setNegativeButton("取消", null).setPositiveButton("确认覆盖恢复", (d, w) -> applyImportedState(restored, false, "全部数据已覆盖恢复")).create());
    }
    private void applyImportedState(VaultState state, boolean merged, String message) {
        try {
            vault.save(state); accounts.clear(); accounts.addAll(state.accounts); groups.clear(); groups.addAll(state.groups); fatal = false;
            screen = merged ? Screen.BACKUP : Screen.OTP; selectedGroupId = null; passwordQuery = ""; render(); toast(message);
        } catch (Exception error) { toast("导入未完成，原文件未被覆盖。请检查存储空间或系统密钥是否可用。"); }
    }
    private void readBackupFile(android.net.Uri uri) {
        backupTask("读取加密 TXT", () -> {
            try (java.io.InputStream stream = getContentResolver().openInputStream(uri); java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
                if (stream == null) throw new java.io.IOException("Unavailable input stream");
                byte[] buffer = new byte[8192]; int read, total = 0;
                while ((read = stream.read(buffer)) != -1) {
                    total += read; if (total > BackupCodec.MAX_TEXT_BYTES) throw new IllegalArgumentException("TXT 文件超过 12 MB 上限");
                    output.write(buffer, 0, read);
                }
                return new String(output.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
            }
        }, this::importBackup);
    }
    private void writeBackupFile(android.net.Uri uri, String text) {
        backupTask("保存加密 TXT", () -> {
            try (java.io.OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                if (output == null) throw new java.io.IOException("Unavailable output stream");
                output.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)); output.flush(); return true;
            }
        }, saved -> toast("加密备份 TXT 已保存"));
    }
    private Otp.Account updateLogin(Otp.Account old, LoginDetails details) {
        int index = accounts.indexOf(old);
        if (index < 0 || !unlocked) { toast("请重新打开该条目"); return null; }
        Otp.Account updated = old.withLogin(details);
        List<Otp.Account> next = new ArrayList<>(accounts); next.set(index, updated);
        return persist(next) ? updated : null;
    }
    private void login(Otp.Account account) {
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(20), dp(8), dp(20), dp(12));
        if (Build.VERSION.SDK_INT >= 29) form.setImportantForContentCapture(View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS);
        form.addView(text(account.issuer, 23, INK, true));
        form.addView(text("分组：" + groupName(account.groupId), 13, MUTED, false)); space(form, 14);
        button(form, "一键复制全部", () -> copySensitive(PasswordPresentation.copyAll(account), "网站完整登录信息", "登录信息" + (account.login.notes.isEmpty() ? "" : "及备注") + "已全部复制")); space(form, 16);
        form.addView(text("网站地址", 12, MUTED, false)); form.addView(text(account.login.website.isEmpty() ? "未填写" : account.login.website, 15, INK, false)); space(form, 12);
        form.addView(text("登录账号", 12, MUTED, false)); form.addView(text(account.login.username, 16, INK, true));
        Button copyUser = new Button(this); copyUser.setText("复制账号"); copyUser.setOnClickListener(v -> copySensitive(account.login.username, "登录账号", "账号已复制")); form.addView(copyUser);
        form.addView(text("密码", 12, MUTED, false)); TextView password = text("••••••••", 20, INK, true); password.setTypeface(Typeface.MONOSPACE); form.addView(password);
        CheckBox reveal = new CheckBox(this); reveal.setText("显示密码");
        reveal.setOnCheckedChangeListener((v, checked) -> password.setText(checked ? account.login.password : "••••••••")); form.addView(reveal);
        Button copyPassword = new Button(this); copyPassword.setText("复制密码"); copyPassword.setOnClickListener(v -> copySensitive(account.login.password, "网站密码", "密码已复制，请及时粘贴")); form.addView(copyPassword);
        space(form, 14); form.addView(text("备注", 12, MUTED, false));
        form.addView(text(account.login.notes.isEmpty() ? "未填写" : account.login.notes, 15, account.login.notes.isEmpty() ? MUTED : INK, false)); space(form, 12);
        Button move = new Button(this); move.setText("移动到其他组 / 不分组"); form.addView(move);
        Button delete = new Button(this); delete.setText("删除网站密码"); delete.setTextColor(0xFF9C3D36); form.addView(delete);
        ScrollView scroll = new ScrollView(this); scroll.addView(form);
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle("账号密码 · " + (account.issuer.isEmpty() ? "验证账号" : account.issuer)).setView(scroll)
            .setNegativeButton("关闭", null).setPositiveButton("编辑", null);
        if (!account.passwordOnly) builder.setNeutralButton("移除登录信息", null);
        AlertDialog dialog = showSecure(builder.create());
        move.setOnClickListener(v -> { dialog.dismiss(); movePassword(account); });
        delete.setOnClickListener(v -> showSecure(new AlertDialog.Builder(this).setTitle("删除「" + account.issuer + "」的网站密码？")
            .setMessage(account.passwordOnly ? "将删除本条目、备注" + (account.recoveryCodes.isEmpty() ? "。" : "及旧版本保留的恢复码。") + "请确认已另行备份。" : "只删除登录信息和备注，保留原动态验证码及恢复码。")
            .setNegativeButton("保留", null).setPositiveButton("删除", (d, w) -> {
                boolean saved;
                if (account.passwordOnly) { List<Otp.Account> next = new ArrayList<>(accounts); next.remove(account); saved = persist(next); }
                else saved = updateLogin(account, LoginDetails.EMPTY) != null;
                if (saved) { password.setText("••••••••"); dialog.dismiss(); }
            }).create()));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> { dialog.dismiss(); editLogin(account); });
        if (!account.passwordOnly) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v ->
            showSecure(new AlertDialog.Builder(this).setTitle("移除登录账号密码？").setMessage("仅删除该条目的网站地址、登录账号、密码和备注，验证码与恢复码仍保留。")
                .setNegativeButton("保留", null).setPositiveButton("确认移除", (d, w) -> {
                    if (updateLogin(account, LoginDetails.EMPTY) != null) { password.setText("••••••••"); dialog.dismiss(); }
                }).create()));
    }
    private void editLogin(Otp.Account account) {
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(20), dp(8), dp(20), dp(12));
        if (Build.VERSION.SDK_INT >= 29) form.setImportantForContentCapture(View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS);
        form.addView(text("手动保存网站登录信息。所有内容在写入手机存储前加密。", 13, MUTED, false));
        EditText title = field(form, "网站或条目名称，例如 GitHub", false);
        EditText website = field(form, "网站地址（可选）", false); website.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText username = field(form, "登录账号 / 邮箱", false); username.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        EditText password = field(form, "登录密码", true);
        CheckBox reveal = new CheckBox(this); reveal.setText("显示密码");
        reveal.setOnCheckedChangeListener((v, checked) -> {
            int cursor = password.getSelectionStart();
            password.setTransformationMethod(checked ? null : android.text.method.PasswordTransformationMethod.getInstance());
            if (cursor >= 0) password.setSelection(Math.min(cursor, password.length()));
        }); form.addView(reveal);
        EditText notes = new EditText(this); notes.setHint("备注（可选，支持多行）"); notes.setTextSize(15); notes.setGravity(Gravity.TOP);
        notes.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        notes.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        notes.setMinLines(3); notes.setMaxLines(6); notes.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO); notes.setSaveEnabled(false);
        if (Build.VERSION.SDK_INT >= 29) notes.setImportantForContentCapture(View.IMPORTANT_FOR_CONTENT_CAPTURE_NO);
        form.addView(notes, new LinearLayout.LayoutParams(-1, -2));
        if (account != null) { title.setText(account.issuer); website.setText(account.login.website); username.setText(account.login.username); password.setText(account.login.password); notes.setText(account.login.notes); }
        form.addView(text("所属分组（可不分组）", 12, MUTED, false));
        Spinner group = new Spinner(this); form.addView(group, new LinearLayout.LayoutParams(-1, dp(48)));
        List<String> groupIds = new ArrayList<>();
        String initialGroup = account != null ? account.groupId : selectedGroupId != null && hasGroup(selectedGroupId) ? selectedGroupId : "";
        fillGroupChoices(group, groupIds, initialGroup);
        TextView newGroup = text("＋ 新建分组", 14, GREEN, true); newGroup.setPadding(0, dp(10), 0, dp(12));
        newGroup.setOnClickListener(v -> createGroup(created -> fillGroupChoices(group, groupIds, created.id))); form.addView(newGroup);
        form.addView(text("密码按原样保存，保留大小写和前后空格。此版本仅保存在本机。", 12, MUTED, false));
        ScrollView scroll = new ScrollView(this); scroll.addView(form);
        AlertDialog dialog = showSecure(new AlertDialog.Builder(this).setTitle(account == null ? "添加网站密码" : "编辑登录信息")
            .setView(scroll).setNegativeButton("取消", null).setPositiveButton("加密保存", null).create());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                LoginDetails details = new LoginDetails(website.getText().toString(), username.getText().toString(), password.getText().toString(), notes.getText().toString()); details.requireCredentials();
                String groupId = groupIds.get(group.getSelectedItemPosition());
                Otp.Account saved;
                if (account == null) {
                    Otp.Account created = Otp.Account.passwordEntry(title.getText().toString(), details).withGroup(groupId);
                    saved = add(created) ? created : null;
                } else {
                    int index = accounts.indexOf(account);
                    if (index < 0) { toast("请重新打开该条目"); return; }
                    Otp.Account updated = account.withLogin(details).withTitle(title.getText().toString()).withGroup(groupId);
                    List<Otp.Account> next = new ArrayList<>(accounts); next.set(index, updated); saved = persist(next) ? updated : null;
                }
                if (saved != null) { password.setText(""); notes.setText(""); dialog.dismiss(); toast("登录信息和备注已加密保存"); }
            } catch (IllegalArgumentException e) { password.setError(e.getMessage()); }
        });
    }
    private void fillGroupChoices(Spinner spinner, List<String> ids, String selected) {
        ids.clear(); ids.add(""); List<String> names = new ArrayList<>(); names.add("不分组");
        for (PasswordGroup g : groups) { ids.add(g.id); names.add(g.name); }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); spinner.setAdapter(adapter);
        spinner.setSelection(Math.max(0, ids.indexOf(selected)));
    }
    private void movePassword(Otp.Account account) {
        List<String> ids = new ArrayList<>(); ids.add(""); List<String> names = new ArrayList<>(); names.add("不分组");
        for (PasswordGroup g : groups) { ids.add(g.id); names.add(g.name); }
        names.add("＋ 新建分组");
        java.util.function.Consumer<String> move = id -> {
            int index = accounts.indexOf(account); if (index < 0) { toast("请重新打开该条目"); return; }
            Otp.Account updated = account.withGroup(id); List<Otp.Account> next = new ArrayList<>(accounts); next.set(index, updated);
            if (persist(next)) login(updated);
        };
        showSecure(new AlertDialog.Builder(this).setTitle("移动到分组").setSingleChoiceItems(names.toArray(new String[0]), ids.indexOf(account.groupId), (dialog, which) -> {
            dialog.dismiss();
            if (which == ids.size()) createGroup(created -> move.accept(created.id)); else move.accept(ids.get(which));
        }).setNegativeButton("取消", null).create());
    }
    private Otp.Account updateRecovery(Otp.Account old, List<RecoveryCodes.Entry> codes) {
        int index = accounts.indexOf(old);
        if (index < 0 || !unlocked) { toast("请重新打开该账号"); return null; }
        Otp.Account updated = old.withRecoveryCodes(codes);
        List<Otp.Account> next = new ArrayList<>(accounts); next.set(index, updated);
        return persist(next) ? updated : null;
    }
    private void recovery(Otp.Account account) {
        if (account.recoveryCodes.isEmpty()) { editRecovery(account); return; }
        Otp.Account[] current = {account};
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(20), dp(8), dp(20), dp(8));
        body.addView(text(account.name + "\n仅存本机，请另存一份。网站使用成功后，再手动标记已使用。", 13, MUTED, false));
        TextView count = text("", 14, GREEN, true); count.setPadding(0, dp(12), 0, dp(8)); body.addView(count);
        CheckBox reveal = new CheckBox(this); reveal.setText("显示完整恢复码"); reveal.setTextColor(INK); body.addView(reveal);
        ScrollView scroll = new ScrollView(this);
        LinearLayout rows = new LinearLayout(this); rows.setOrientation(LinearLayout.VERTICAL); scroll.addView(rows);
        int height = Math.min(340, Math.max(100, (int)(getResources().getDisplayMetrics().heightPixels / getResources().getDisplayMetrics().density) - 330));
        body.addView(scroll, new LinearLayout.LayoutParams(-1, dp(height)));
        Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            rows.removeAllViews();
            count.setText(RecoveryCodes.remaining(current[0].recoveryCodes) + " / " + current[0].recoveryCodes.size() + " 条未使用");
            for (int i = 0; i < current[0].recoveryCodes.size(); i++) {
                final int index = i;
                RecoveryCodes.Entry entry = current[0].recoveryCodes.get(i);
                TextView value = text((i + 1) + ".  " + (reveal.isChecked() ? entry.code : "••••  ••••") + (entry.used ? "  （已使用）" : ""), 16, entry.used ? MUTED : INK, true);
                value.setTypeface(Typeface.MONOSPACE); value.setPadding(0, dp(16), 0, dp(6)); value.setSaveEnabled(false); rows.addView(value);
                LinearLayout actions = new LinearLayout(this); actions.setOrientation(LinearLayout.HORIZONTAL);
                Button copy = new Button(this); copy.setText("复制"); copy.setAllCaps(false); copy.setEnabled(!entry.used);
                copy.setOnClickListener(v -> copySensitive(entry.code, "恢复码", "恢复码已复制；请在网站使用成功后标记已使用"));
                actions.addView(copy, new LinearLayout.LayoutParams(0, dp(48), 1));
                Button used = new Button(this); used.setText(entry.used ? "改为未使用" : "标记已使用"); used.setAllCaps(false);
                used.setOnClickListener(v -> {
                    Otp.Account updated = updateRecovery(current[0], RecoveryCodes.setUsed(current[0].recoveryCodes, index, !entry.used));
                    if (updated != null) { current[0] = updated; refresh[0].run(); }
                });
                actions.addView(used, new LinearLayout.LayoutParams(0, dp(48), 1)); rows.addView(actions);
            }
        };
        reveal.setOnCheckedChangeListener((b, checked) -> refresh[0].run()); refresh[0].run();
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("恢复码 · " + (account.issuer.isEmpty() ? "验证账号" : account.issuer))
            .setView(body).setNegativeButton("关闭", null).setPositiveButton("替换 / 清空", null).create();
        showSecure(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> { dialog.dismiss(); editRecovery(current[0]); });
    }
    private void editRecovery(Otp.Account account) {
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(20), dp(8), dp(20), dp(8));
        form.addView(text(account.name + "\n粘贴网站提供的整组恢复码，每行一条，也可用空格、逗号分隔。最多 200 条，重复项会合并。", 14, MUTED, false));
        EditText input = new EditText(this); input.setHint("在此粘贴恢复码"); input.setTextSize(15); input.setGravity(Gravity.TOP);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO); input.setSaveEnabled(false);
        form.addView(input, new LinearLayout.LayoutParams(-1, dp(150)));
        form.addView(text("加密保存在本机。若手机丢失，这里的恢复码也会一起丢失。", 12, MUTED, false));
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle(account.recoveryCodes.isEmpty() ? "保存恢复码" : "替换整组恢复码")
            .setView(form).setNegativeButton("取消", null).setPositiveButton("保存", null);
        if (!account.recoveryCodes.isEmpty()) builder.setNeutralButton("清空", null);
        AlertDialog dialog = showSecure(builder.create());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                List<RecoveryCodes.Entry> parsed = RecoveryCodes.parse(input.getText().toString());
                Runnable save = () -> {
                    Otp.Account updated = updateRecovery(account, parsed);
                    if (updated != null) { input.setText(""); dialog.dismiss(); toast("已加密保存 " + parsed.size() + " 条恢复码"); recovery(updated); }
                };
                if (account.recoveryCodes.isEmpty()) save.run();
                else showSecure(new AlertDialog.Builder(this).setTitle("替换为 " + parsed.size() + " 条恢复码？")
                    .setMessage("将删除此账号原有的恢复码及已使用记录。请确认粘贴的是当前有效的整组恢复码。")
                    .setNegativeButton("取消", null).setPositiveButton("确认替换", (d, w) -> save.run()).create());
            } catch (IllegalArgumentException e) { input.setError(e.getMessage()); }
        });
        if (!account.recoveryCodes.isEmpty()) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v ->
            showSecure(new AlertDialog.Builder(this).setTitle("清空此账号的恢复码？")
                .setMessage("只删除本机保存的恢复码，验证码账号仍保留。请先确认已在其他地方备份。")
                .setNegativeButton("保留", null).setPositiveButton("确认清空", (d, w) -> {
                    if (updateRecovery(account, java.util.Collections.emptyList()) != null) { input.setText(""); dialog.dismiss(); toast("已清空本机恢复码"); }
                }).create()));
    }
    private void help() { openScreen(Screen.HELP); }
    private EditText field(LinearLayout parent, String hint, boolean secret) {
        EditText edit = new EditText(this); edit.setHint(hint); edit.setTextSize(15); edit.setSingleLine(true);
        edit.setInputType(secret ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        edit.setImeOptions(edit.getImeOptions() | android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        if (Build.VERSION.SDK_INT >= 29) edit.setImportantForContentCapture(View.IMPORTANT_FOR_CONTENT_CAPTURE_NO);
        edit.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO); edit.setSaveEnabled(false);
        parent.addView(edit, new LinearLayout.LayoutParams(-1, dp(60))); return edit;
    }
    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(20), dp(20), dp(20), dp(12));
        GradientDrawable bg = new GradientDrawable(); bg.setColor(Color.WHITE); bg.setCornerRadius(dp(22)); bg.setStroke(dp(1), 0xFFE1E9E1); card.setBackground(bg); return card;
    }
    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(sp); view.setTextColor(color); view.setLineSpacing(dp(3), 1);
        view.setSaveEnabled(false);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return view;
    }
    private Button makeButton(String label, Runnable action) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setTextSize(14); b.setTextColor(Color.WHITE);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(GREEN); bg.setCornerRadius(dp(16)); b.setBackground(bg); b.setOnClickListener(v -> action.run()); return b;
    }
    private void button(LinearLayout parent, String label, Runnable action) { parent.addView(makeButton(label, action), new LinearLayout.LayoutParams(-1, dp(52))); }
    private void space(LinearLayout parent, int amount) { parent.addView(new Space(this), new LinearLayout.LayoutParams(1, dp(amount))); }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
}
