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
    private enum Screen { OTP, PASSWORDS, BACKUP, LANGUAGE, HELP }
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

    @Override protected void attachBaseContext(android.content.Context base) { super.attachBaseContext(AppLanguage.wrap(base)); }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().getDecorView().setLayoutDirection(getResources().getConfiguration().getLayoutDirection());
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
    @Override protected void onResume() { super.onResume(); if (!isFinishing() && AppLanguage.hasSelection(this) && !unlocked && !authenticating) unlock(); }
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
        if (!AppLanguage.hasSelection(this)) { renderLanguagePage(true); return; }
        root.addView(text(getString(R.string.app_name), 32, INK, true)); space(root, 16);
        root.addView(text(getString(R.string.ui_unlock_with_your_device_pin_001), 16, MUTED, false));
        space(root, 24); button(root, getString(R.string.ui_unlock_vault_002), this::unlock);
    }
    @SuppressWarnings("deprecation") private void unlock() {
        if (!AppLanguage.hasSelection(this)) { root.removeAllViews(); renderLanguagePage(true); return; }
        KeyguardManager keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (!keyguard.isDeviceSecure()) {
            root.removeAllViews(); root.addView(text(getString(R.string.ui_secure_your_device_first_003), 28, INK, true)); space(root, 16);
            root.addView(text(getString(R.string.ui_set_a_screen_lock_pin_004), 16, MUTED, false));
            space(root, 20); button(root, getString(R.string.ui_open_security_settings_005), () -> startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS)));
            return;
        }
        Intent intent = keyguard.createConfirmDeviceCredentialIntent(getString(R.string.ui_unlock_jianvault_006), getString(R.string.ui_verify_your_identity_to_open_007));
        if (intent == null) { toast(getString(R.string.ui_system_authentication_is_unavailable_please_008)); return; }
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
            if (text != null) writeBackupFile(uri, text); else toast(getString(R.string.ui_the_backup_text_is_no_009));
        }
    }
    private void render() {
        updates.clear(); root.removeAllViews(); root.requestFocus();
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        TextView menu = text("☰", 27, GREEN, true); menu.setGravity(Gravity.CENTER); menu.setContentDescription(getString(R.string.ui_open_navigation_menu_010));
        menu.setOnClickListener(v -> drawer.open()); header.addView(menu, new LinearLayout.LayoutParams(dp(48), dp(48)));
        String pageTag = getString(screen == Screen.PASSWORDS ? R.string.tag_passwords : screen == Screen.BACKUP ? R.string.tag_backup : screen == Screen.LANGUAGE ? R.string.language_button : screen == Screen.HELP ? R.string.tag_help : R.string.tag_authenticator);
        TextView tag = text(pageTag, 11, GREEN, true); tag.setLetterSpacing(.13f); header.addView(tag); root.addView(header);
        if (screen == Screen.LANGUAGE) { renderLanguagePage(false); return; }
        if (screen == Screen.HELP) { renderHelp(); return; }
        if (screen == Screen.BACKUP) { renderBackupPage(); return; }
        if (screen == Screen.PASSWORDS && !fatal) { renderPasswords(); return; }
        space(root, 4); root.addView(text(getString(R.string.app_name), 34, INK, true));
        root.addView(text(getString(R.string.ui_totp_codes_encrypted_on_this_011), 14, MUTED, false)); space(root, 20);
        if (fatal) {
            root.addView(text(getString(R.string.ui_cannot_read_the_vault_012), 23, INK, true)); space(root, 12);
            root.addView(text(getString(R.string.ui_the_data_or_system_key_013), 16, MUTED, false));
            return;
        }
        LinearLayout actions = new LinearLayout(this); actions.setOrientation(LinearLayout.HORIZONTAL);
        Button scan = makeButton(getString(R.string.ui_scan_qr_code_014), this::scan); actions.addView(scan, new LinearLayout.LayoutParams(0, dp(52), 1));
        Space gap = new Space(this); actions.addView(gap, new LinearLayout.LayoutParams(dp(10), 1));
        Button manual = makeButton(getString(R.string.ui_enter_setup_key_015), this::manual); actions.addView(manual, new LinearLayout.LayoutParams(0, dp(52), 1)); root.addView(actions);
        space(root, 18);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); scroll.addView(content);
        boolean hasOtp = false;
        for (Otp.Account a : accounts) if (!a.passwordOnly) hasOtp = true;
        if (!hasOtp) {
            LinearLayout card = card(); space(card, 12); card.addView(text(getString(R.string.ui_add_a_totp_account_016), 23, INK, true)); space(card, 12);
            card.addView(text(getString(R.string.ui_scan_a_website_s_authenticator_017), 16, MUTED, false));
            space(card, 20); card.addView(text(getString(R.string.ui_no_sign_in_offline_no_018), 12, GREEN, true)); content.addView(card);
        } else {
            for (Otp.Account a : accounts) if (!a.passwordOnly) accountCard(a);
        }
        space(root, 12);
        TextView help = text(getString(R.string.ui_help_and_recovery_information_019), 13, MUTED, false); help.setPadding(0, dp(8), 0, dp(8)); help.setOnClickListener(v -> help()); root.addView(help);
    }
    @Override public void onBackPressed() { goBack(); }
    private void goBack() {
        if (!AppLanguage.hasSelection(this)) { finish(); return; }
        if (drawer.isOpen()) { drawer.close(); return; }
        if (screen != Screen.OTP) { screen = Screen.OTP; passwordQuery = ""; render(); return; }
        finish();
    }
    private boolean hasGroup(String id) { for (PasswordGroup g : groups) if (g.id.equals(id)) return true; return false; }
    private String groupName(String id) { for (PasswordGroup g : groups) if (g.id.equals(id)) return g.name; return getString(R.string.ui_ungrouped_020); }
    private void openScreen(Screen next) { drawer.close(); screen = next; passwordQuery = ""; render(); }
    private void openPasswords(String groupId) { selectedGroupId = groupId; openScreen(Screen.PASSWORDS); }
    private View drawerMenu() {
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(18), dp(24), dp(18), dp(24));
        body.addView(text(getString(R.string.app_name), 29, INK, true)); body.addView(text(getString(R.string.ui_an_encrypted_vault_on_your_021), 13, MUTED, false)); space(body, 24);
        navItem(body, getString(R.string.ui_authenticator_022), screen == Screen.OTP, () -> openScreen(Screen.OTP));
        navItem(body, getString(R.string.ui_website_credentials_023), screen == Screen.PASSWORDS, () -> openPasswords(null));
        navItem(body, getString(R.string.ui_encrypted_import_export_024), screen == Screen.BACKUP, () -> openScreen(Screen.BACKUP));
        navItem(body, "🌐  " + getString(R.string.language_button), screen == Screen.LANGUAGE, () -> openScreen(Screen.LANGUAGE));
        navItem(body, getString(R.string.ui_help_025), screen == Screen.HELP, () -> openScreen(Screen.HELP));
        return body;
    }
    private TextView navItem(LinearLayout parent, String label, boolean selected, Runnable action) {
        TextView item = text(label, 15, selected ? GREEN : INK, selected); item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(dp(14), dp(12), dp(14), dp(12)); item.setMinHeight(dp(52));
        if (selected) { GradientDrawable bg = new GradientDrawable(); bg.setColor(0xFFE1EDE5); bg.setCornerRadius(dp(14)); item.setBackground(bg); }
        item.setOnClickListener(v -> action.run()); parent.addView(item, new LinearLayout.LayoutParams(-1, -2)); return item;
    }
    private void renderPasswords() {
        space(root, 4); root.addView(text(getString(R.string.ui_website_credentials_023), 29, INK, true));
        root.addView(text(getString(R.string.ui_fixed_credentials_sorted_by_name_026), 12, MUTED, false)); space(root, 14);
        button(root, getString(R.string.ui_save_website_credentials_027), () -> editLogin(null)); space(root, 10);
        LinearLayout groupBar = new LinearLayout(this); groupBar.setGravity(Gravity.CENTER_VERTICAL);
        Spinner selector = new Spinner(this); List<String> groupIds = new ArrayList<>(), labels = new ArrayList<>();
        groupIds.add(null); labels.add(getString(R.string.ui_all_websites_028)); groupIds.add(""); labels.add(getString(R.string.ui_ungrouped_020));
        for (PasswordGroup g : groups) { groupIds.add(g.id); labels.add(g.name); }
        ArrayAdapter<String> groupAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        groupAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); selector.setAdapter(groupAdapter);
        selector.setContentDescription(getString(R.string.ui_filter_websites_by_group_029)); selector.setSelection(Math.max(0, groupIds.indexOf(selectedGroupId)));
        groupBar.addView(selector, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button manage = new Button(this); manage.setText(getString(R.string.ui_manage_groups_030)); manage.setAllCaps(false); manage.setOnClickListener(v -> manageGroups());
        groupBar.addView(manage, new LinearLayout.LayoutParams(-2, dp(48))); root.addView(groupBar);
        selector.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                String chosen = groupIds.get(position);
                if (!java.util.Objects.equals(chosen, selectedGroupId)) openPasswords(chosen);
            }
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        EditText search = new EditText(this); search.setHint(getString(R.string.ui_search_names_urls_or_usernames_031)); search.setTextSize(15); search.setSingleLine(true);
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
            count.setText(getString(R.string.websites_count, adapter.items.size()));
            empty.setText(passwordQuery.trim().isEmpty() ? getString(R.string.ui_no_website_credentials_here_yet_033) : getString(R.string.ui_no_matching_websites_try_another_034));
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
        space(root, 4); root.addView(text(getString(R.string.ui_encrypted_import_export_024), 27, INK, true));
        root.addView(text(getString(R.string.ui_manual_backup_and_restore_you_035), 13, MUTED, false)); space(root, 18);
        ScrollView scroll = new ScrollView(this); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); scroll.addView(body);
        LinearLayout export = card(); export.addView(text(getString(R.string.ui_export_all_data_036), 21, INK, true)); space(export, 10);
        export.addView(text(fatal ? getString(R.string.ui_this_vault_cannot_be_read_037) : getString(R.string.ui_includes_totp_accounts_website_credentials_038), 14, MUTED, false));
        if (!fatal) {
            space(export, 12); export.addView(text(getString(R.string.vault_summary, accounts.size(), groups.size()), 12, GREEN, true));
            space(export, 16); button(export, getString(R.string.ui_export_encrypted_backup_042), this::exportBackup);
        }
        space(export, 6); body.addView(export); space(body, 14);
        LinearLayout restore = card(); restore.addView(text(getString(R.string.ui_import_all_data_043), 21, INK, true)); space(restore, 10);
        restore.addView(text(getString(R.string.ui_select_an_encrypted_txt_file_044), 14, MUTED, false));
        space(restore, 16); button(restore, getString(R.string.ui_select_file_paste_backup_045), () -> importBackup(null)); space(restore, 6); body.addView(restore);
        space(body, 18); body.addView(text(getString(R.string.ui_your_key_is_not_stored_046), 12, MUTED, false));
    }
    private void renderHelp() {
        space(root, 4); root.addView(text(getString(R.string.ui_help_025), 29, INK, true)); space(root, 18);
        ScrollView scroll = new ScrollView(this); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); scroll.addView(body);
        body.addView(text(getString(R.string.language_help), 13, MUTED, false)); space(body, 20);
        helpSection(body, getString(R.string.ui_navigation_047), getString(R.string.ui_tap_at_the_top_left_048));
        helpSection(body, getString(R.string.ui_authenticator_022), getString(R.string.ui_scan_a_qr_code_or_049));
        helpSection(body, getString(R.string.ui_website_credentials_023), getString(R.string.ui_add_search_and_open_website_050));
        helpSection(body, getString(R.string.ui_passwords_and_notes_051), getString(R.string.ui_fixed_passwords_change_only_when_052));
        helpSection(body, getString(R.string.ui_copy_053), getString(R.string.ui_copy_all_includes_the_entry_054));
        helpSection(body, getString(R.string.ui_encrypted_export_055), getString(R.string.ui_open_encrypted_import_export_and_056));
        helpSection(body, getString(R.string.ui_merge_import_057), getString(R.string.ui_select_a_file_or_paste_058));
        helpSection(body, getString(R.string.ui_replace_vault_059), getString(R.string.ui_to_replace_the_entire_vault_060));
        helpSection(body, getString(R.string.ui_data_protection_061), getString(R.string.ui_the_app_works_offline_and_062));
        helpSection(body, getString(R.string.ui_test_build_status_063), getString(R.string.ui_algorithm_data_and_encrypted_backup_064));
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
            Otp.Account a = items.get(position); labels[0].setText(a.issuer.isEmpty() ? getString(R.string.ui_website_account_065) : a.issuer);
            labels[1].setText(a.login.username + "  ·  " + groupName(a.groupId)); labels[2].setText(a.login.website.isEmpty() ? getString(R.string.ui_no_url_provided_066) : a.login.website + "  ›");
            return row;
        }
    }
    private void createGroup(java.util.function.Consumer<PasswordGroup> callback) {
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(20), 0, dp(20), dp(12));
        EditText name = field(form, getString(R.string.ui_for_example_work_personal_shopping_067), false);
        AlertDialog dialog = showSecure(new AlertDialog.Builder(this).setTitle(getString(R.string.ui_create_group_068)).setView(form).setNegativeButton(getString(R.string.ui_cancel_069), null).setPositiveButton(getString(R.string.ui_create_070), null).create());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                PasswordGroup group = PasswordGroup.create(name.getText().toString()); VaultState next = new VaultState(accounts, groups).addGroup(group);
                if (persist(next.accounts, next.groups)) { dialog.dismiss(); if (callback != null) callback.accept(group); }
            } catch (IllegalArgumentException e) { name.setError(localError(e.getMessage())); }
        });
    }
    private void manageGroups() {
        String[] labels = new String[groups.size() + 1]; labels[0] = getString(R.string.ui_create_group_071);
        List<PasswordGroup> snapshot = new ArrayList<>(groups);
        for (int i = 0; i < snapshot.size(); i++) labels[i + 1] = snapshot.get(i).name;
        showSecure(new AlertDialog.Builder(this).setTitle(getString(R.string.ui_manage_groups_030)).setItems(labels, (d, which) -> {
            if (which == 0) { createGroup(g -> manageGroups()); return; }
            PasswordGroup group = snapshot.get(which - 1);
            showSecure(new AlertDialog.Builder(this).setTitle(group.name).setItems(new String[] {getString(R.string.ui_view_websites_in_this_group_072), getString(R.string.ui_rename_073), getString(R.string.ui_delete_group_move_websites_to_074)}, (dialog, action) -> {
                if (action == 0) openPasswords(group.id);
                else if (action == 1) renameGroup(group);
                else showSecure(new AlertDialog.Builder(this).setTitle(getString(R.string.delete_group_title, group.name)).setMessage(getString(R.string.ui_websites_in_this_group_will_076))
                    .setNegativeButton(getString(R.string.ui_cancel_069), null).setPositiveButton(getString(R.string.ui_delete_group_077), (confirm, button) -> {
                        VaultState next = new VaultState(accounts, groups).deleteGroup(group.id);
                        if (persist(next.accounts, next.groups)) toast(getString(R.string.ui_group_deleted_its_websites_were_078));
                    }).create());
            }).setNegativeButton(getString(R.string.ui_cancel_069), null).create());
        }).setNegativeButton(getString(R.string.ui_close_079), null).create());
    }
    private void renameGroup(PasswordGroup group) {
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(20), 0, dp(20), dp(12));
        EditText name = field(form, getString(R.string.ui_group_name_080), false); name.setText(group.name);
        AlertDialog dialog = showSecure(new AlertDialog.Builder(this).setTitle(getString(R.string.ui_rename_group_081)).setView(form).setNegativeButton(getString(R.string.ui_cancel_069), null).setPositiveButton(getString(R.string.ui_save_082), null).create());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try { VaultState next = new VaultState(accounts, groups).renameGroup(group.id, name.getText().toString()); if (persist(next.accounts, next.groups)) dialog.dismiss(); }
            catch (IllegalArgumentException e) { name.setError(localError(e.getMessage())); }
        });
    }
    private void accountCard(Otp.Account a) {
        LinearLayout card = card();
        card.addView(text(a.passwordOnly ? getString(R.string.ui_website_password_083) : getString(R.string.ui_authenticator_totp_084), 11, GREEN, true)); space(card, 6);
        card.addView(text(a.issuer.isEmpty() ? getString(R.string.ui_authenticator_account_085) : a.issuer, 19, INK, true)); card.addView(text(a.name, 14, MUTED, false));
        if (!a.passwordOnly) {
        TextView code = text("", 37, GREEN, true); code.setTextDirection(View.TEXT_DIRECTION_LTR); code.setTypeface(Typeface.MONOSPACE, Typeface.BOLD); code.setLetterSpacing(.05f); code.setPadding(0, dp(14), 0, dp(8));
        code.setContentDescription(getString(R.string.ui_verification_code_tap_to_copy_086)); card.addView(code);
        TextView remaining = text("", 12, MUTED, false); card.addView(remaining);
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); bar.setMax(a.period * 1000);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(GREEN)); card.addView(bar, new LinearLayout.LayoutParams(-1, dp(12)));
        Runnable update = () -> {
            long now = System.currentTimeMillis(); String value = Otp.code(a, now / 1000);
            code.setText(value.substring(0, value.length() / 2) + " " + value.substring(value.length() / 2));
            int left = a.period * 1000 - (int) (now % (a.period * 1000L));
            remaining.setText(getString(R.string.otp_remaining, (left + 999) / 1000)); bar.setProgress(left);
        };
        updates.add(update); update.run();
        code.setOnClickListener(v -> copy(a));
        } else {
            space(card, 12); card.addView(text(getString(R.string.ui_website_credentials_stored_encrypted_089), 13, MUTED, false));
        }
        TextView recovery = text(a.recoveryCodes.isEmpty() ? getString(R.string.ui_save_recovery_codes_090) : getString(R.string.recovery_badge, RecoveryCodes.remaining(a.recoveryCodes), a.recoveryCodes.size()), 14, GREEN, true);
        recovery.setPadding(0, dp(16), 0, dp(16));
        recovery.setOnClickListener(v -> recovery(a)); card.addView(recovery);
        TextView remove = text(getString(R.string.ui_remove_account_093), 12, MUTED, false); remove.setGravity(Gravity.END); remove.setPadding(0, dp(12), 0, dp(8));
        remove.setOnClickListener(v -> {
            activeDialog = new AlertDialog.Builder(this).setTitle(getString(R.string.remove_account_title, a.name))
                .setMessage(getString(R.string.ui_this_removes_the_entry_s_095))
                .setNegativeButton(getString(R.string.ui_keep_096), null).setPositiveButton(getString(R.string.ui_remove_097), (d, w) -> {
                    List<Otp.Account> next = new ArrayList<>(accounts); next.remove(a); persist(next);
                }).show();
        }); card.addView(remove); content.addView(card); space(content, 12);
    }
    private void chooseOtp() {
        showSecure(new AlertDialog.Builder(this).setTitle(getString(R.string.ui_add_a_totp_account_016))
            .setItems(new String[] {getString(R.string.ui_scan_an_authenticator_qr_code_098), getString(R.string.ui_enter_authenticator_setup_key_099)}, (dialog, which) -> {
                if (which == 0) scan(); else manual();
            }).setNegativeButton(getString(R.string.ui_cancel_069), null).create());
    }
    private void scan() {
        Intent intent = new Intent(this, SecureCaptureActivity.class);
        intent.setAction("com.google.zxing.client.android.SCAN");
        intent.putExtra("SCAN_FORMATS", "QR_CODE");
        intent.putExtra("PROMPT_MESSAGE", getString(R.string.ui_scan_the_authenticator_qr_code_100));
        intent.putExtra("BEEP_ENABLED", false);
        intent.putExtra("BARCODE_IMAGE_ENABLED", false);
        intent.putExtra("SCAN_ORIENTATION_LOCKED", false);
        startActivityForResult(intent, 72);
    }
    private void importUri(String raw) {
        try {
            Otp.Account a = Otp.parse(raw);
            activeDialog = new AlertDialog.Builder(this).setTitle(getString(R.string.ui_add_authenticator_account_101))
                .setMessage(getString(R.string.otp_setup_summary, a.issuer.isEmpty() ? getString(R.string.ui_authenticator_account_085) : a.issuer, a.name, a.digits, a.period))
                .setNegativeButton(getString(R.string.ui_cancel_069), null).setPositiveButton(getString(R.string.ui_add_104), (d, w) -> add(a)).show();
        } catch (IllegalArgumentException e) { new AlertDialog.Builder(this).setTitle(getString(R.string.ui_cannot_import_105)).setMessage(localError(e.getMessage())).setPositiveButton(getString(R.string.ui_ok_106), null).show(); }
    }
    private void manual() {
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(24), dp(8), dp(24), dp(12));
        form.addView(text(getString(R.string.ui_enter_the_setup_key_from_107), 14, MUTED, false));
        EditText issuer = field(form, getString(R.string.ui_service_name_for_example_github_108), false);
        EditText name = field(form, getString(R.string.ui_account_name_or_username_109), false);
        EditText secret = field(form, getString(R.string.ui_authenticator_setup_key_base32_110), true);
        form.addView(text(getString(R.string.ui_manual_setup_defaults_to_sha1_111), 12, MUTED, false));
        ScrollView scroll = new ScrollView(this); scroll.addView(form);
        AlertDialog dialog = showSecure(new AlertDialog.Builder(this).setTitle(getString(R.string.ui_enter_authenticator_setup_key_099)).setView(scroll).setNegativeButton(getString(R.string.ui_cancel_069), null).setPositiveButton(getString(R.string.ui_save_authenticator_112), null).create());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                String s = secret.getText().toString().trim();
                Otp.Account a = s.regionMatches(true, 0, "otpauth:", 0, 8) ? Otp.parse(s)
                    : new Otp.Account(issuer.getText().toString(), name.getText().toString(), s, "SHA1", 6, 30);
                if (add(a)) dialog.dismiss();
            } catch (IllegalArgumentException e) { secret.setError(localError(e.getMessage())); }
        });
    }
    private boolean add(Otp.Account a) {
        for (Otp.Account old : accounts) if (!a.passwordOnly && !old.passwordOnly && java.util.Arrays.equals(Otp.decode(old.secret), Otp.decode(a.secret)) && old.algorithm.equals(a.algorithm) && old.digits == a.digits && old.period == a.period) {
            toast(getString(R.string.ui_this_authenticator_key_has_already_113)); return false;
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
        catch (Exception e) { toast(getString(R.string.ui_could_not_save_existing_accounts_114)); return false; }
    }
    private void copy(Otp.Account a) {
        copySensitive(Otp.code(a, System.currentTimeMillis() / 1000), getString(R.string.ui_one_time_code_115), getString(R.string.ui_code_copied_use_it_before_116));
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
        AlertDialog progress = showSecure(new AlertDialog.Builder(this).setTitle(title).setMessage(getString(R.string.ui_processing_please_wait_117))
            .setCancelable(false).setNegativeButton(getString(R.string.ui_cancel_069), (d, w) -> backupGeneration++).create());
        backupWorker.execute(() -> {
            try {
                T result = work.call();
                handler.post(() -> {
                    if (generation != backupGeneration || !unlocked || isDestroyed() || isFinishing()) return;
                    progress.dismiss(); success.accept(result);
                });
            } catch (Exception error) {
                String message = error instanceof java.security.GeneralSecurityException ? getString(R.string.ui_the_key_is_incorrect_or_118)
                    : error instanceof java.io.IOException || error instanceof SecurityException ? getString(R.string.ui_cannot_read_or_write_this_119)
                    : error instanceof IllegalArgumentException ? localError(error.getMessage()) : getString(R.string.ui_the_backup_cannot_be_parsed_120);
                handler.post(() -> {
                    if (generation != backupGeneration || !unlocked || isDestroyed() || isFinishing()) return;
                    progress.dismiss();
                    showSecure(new AlertDialog.Builder(this).setTitle(getString(R.string.ui_operation_not_completed_121)).setMessage(message).setPositiveButton(getString(R.string.ui_ok_106), null).create());
                });
            }
        });
    }
    private void exportBackup() {
        LinearLayout form = backupForm();
        form.addView(text(getString(R.string.ui_export_totp_accounts_website_credentials_122), 14, MUTED, false));
        EditText key = field(form, getString(R.string.ui_encryption_key_at_least_12_123), true), confirm = field(form, getString(R.string.ui_enter_the_same_key_again_124), true);
        ScrollView scroll = new ScrollView(this); scroll.addView(form);
        AlertDialog dialog = showSecure(new AlertDialog.Builder(this).setTitle(getString(R.string.ui_export_encrypted_backup_042)).setView(scroll)
            .setNegativeButton(getString(R.string.ui_cancel_069), null).setPositiveButton(getString(R.string.ui_encrypt_and_copy_125), null).create());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            char[] first = readKey(key), second = readKey(confirm);
            try {
                BackupCodec.validateKey(first);
                if (!java.util.Arrays.equals(first, second)) throw new IllegalArgumentException(getString(R.string.ui_the_two_keys_do_not_126));
            } catch (IllegalArgumentException error) {
                java.util.Arrays.fill(first, '\0'); java.util.Arrays.fill(second, '\0'); confirm.setError(localError(error.getMessage())); return;
            }
            java.util.Arrays.fill(second, '\0'); key.setText(""); confirm.setText(""); dialog.dismiss();
            VaultState snapshot = new VaultState(accounts, groups);
            backupTask(getString(R.string.ui_encrypting_backup_127), () -> { try { return BackupCodec.encrypt(snapshot, first); } finally { java.util.Arrays.fill(first, '\0'); } }, this::showExportedBackup);
        });
    }
    private boolean copyEncryptedBackup(String text) {
        if (text.length() > BackupCodec.CLIPBOARD_LIMIT) { toast(getString(R.string.ui_the_backup_is_too_large_128)); return false; }
        try {
            ClipData clip = ClipData.newPlainText(getString(R.string.ui_jianvault_encrypted_backup_129), text); PersistableBundle extras = new PersistableBundle();
            extras.putBoolean("android.content.extra.IS_SENSITIVE", true); clip.getDescription().setExtras(extras);
            ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(clip); toast(getString(R.string.ui_encrypted_text_containing_all_data_130)); return true;
        } catch (RuntimeException error) { toast(getString(R.string.ui_the_clipboard_could_not_accept_131)); return false; }
    }
    private void showExportedBackup(String text) {
        boolean copied = copyEncryptedBackup(text);
        showSecure(new AlertDialog.Builder(this).setTitle(getString(R.string.ui_encrypted_backup_ready_132))
            .setMessage((copied ? getString(R.string.ui_copied_to_the_clipboard_paste_133) : getString(R.string.ui_save_a_txt_file_to_134))
                + getString(R.string.backup_length, text.length()))
            .setNegativeButton(getString(R.string.ui_close_079), null).setPositiveButton(getString(R.string.ui_copy_again_137), (d, w) -> copyEncryptedBackup(text))
            .setNeutralButton(getString(R.string.ui_save_txt_138), (d, w) -> {
                pendingBackupFileText = text;
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain");
                intent.putExtra(Intent.EXTRA_TITLE, getString(R.string.ui_jianvault_backup_139) + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(new java.util.Date()) + ".txt");
                try { startActivityForResult(intent, 74); } catch (android.content.ActivityNotFoundException error) { pendingBackupFileText = null; toast(getString(R.string.ui_no_document_saving_app_is_140)); }
            }).create());
    }
    private void importBackup(String fileText) {
        LinearLayout form = backupForm();
        form.addView(text(getString(R.string.ui_select_an_encrypted_txt_backup_141), 14, MUTED, false));
        EditText pasted = null;
        if (fileText == null) {
            pasted = new EditText(this); pasted.setHint(getString(R.string.ui_paste_encrypted_text_starting_with_142)); pasted.setTextSize(13); pasted.setGravity(Gravity.TOP);
            pasted.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            pasted.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING); pasted.setSaveEnabled(false); pasted.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
            form.addView(pasted, new LinearLayout.LayoutParams(-1, dp(130)));
        } else { space(form, 12); form.addView(text(getString(R.string.loaded_txt, fileText.length()), 14, GREEN, true)); }
        EditText input = pasted;
        Button pick = new Button(this); pick.setText(getString(R.string.ui_choose_encrypted_txt_file_145)); form.addView(pick);
        EditText key = field(form, getString(R.string.ui_decryption_key_exactly_as_used_146), true);
        ScrollView scroll = new ScrollView(this); scroll.addView(form);
        AlertDialog dialog = showSecure(new AlertDialog.Builder(this).setTitle(getString(R.string.ui_import_all_data_043)).setView(scroll)
            .setNegativeButton(getString(R.string.ui_cancel_069), null).setPositiveButton(getString(R.string.ui_decrypt_and_preview_147), null).create());
        pick.setOnClickListener(v -> {
            key.setText(""); dialog.dismiss();
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"text/plain", "application/octet-stream"});
            try { startActivityForResult(intent, 73); } catch (android.content.ActivityNotFoundException error) { toast(getString(R.string.ui_no_document_picker_is_available_148)); }
        });
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            char[] secret = readKey(key);
            try { BackupCodec.validateKey(secret); } catch (IllegalArgumentException error) { java.util.Arrays.fill(secret, '\0'); key.setError(localError(error.getMessage())); return; }
            String encrypted = fileText != null ? fileText : input.getText().toString();
            if (encrypted.trim().isEmpty() || encrypted.length() > BackupCodec.MAX_TEXT_BYTES) { java.util.Arrays.fill(secret, '\0'); toast(getString(R.string.ui_provide_a_complete_backup_no_149)); return; }
            key.setText(""); if (input != null) input.setText(""); dialog.dismiss();
            backupTask(getString(R.string.ui_decrypting_backup_150), () -> { try { return BackupCodec.decrypt(encrypted, secret); } finally { java.util.Arrays.fill(secret, '\0'); } }, this::confirmBackupRestore);
        });
    }
    private void confirmBackupRestore(VaultState restored) {
        int otp = 0, passwords = 0, recovery = 0;
        for (Otp.Account a : restored.accounts) { if (!a.passwordOnly) otp++; if (!a.login.isEmpty()) passwords++; recovery += a.recoveryCodes.size(); }
        VaultMerge.Result plan = null; String mergeMessage;
        if (fatal) mergeMessage = getString(R.string.ui_the_local_vault_cannot_be_151);
        else {
            try {
                plan = VaultMerge.merge(new VaultState(accounts, groups), restored);
                mergeMessage = getString(R.string.merge_preview, plan.added, plan.skipped, plan.groupsAdded)
                    + (plan.groupsRenamed > 0 ? getString(R.string.merge_renamed, plan.groupsRenamed) : "")
                    + getString(R.string.ui_only_complete_exact_matches_are_158);
            } catch (IllegalArgumentException error) { mergeMessage = localError(error.getMessage()); }
        }
        VaultMerge.Result merged = plan;
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle(getString(R.string.ui_backup_decrypted_choose_import_mode_159))
            .setMessage(getString(R.string.backup_counts, otp, passwords, restored.groups.size(), recovery, mergeMessage))
            .setNegativeButton(getString(R.string.ui_cancel_069), null).setNeutralButton(getString(R.string.ui_replace_vault_165), (d, w) -> confirmOverwrite(restored));
        if (merged != null) builder.setPositiveButton(getString(R.string.ui_merge_import_057), (d, w) -> {
            if (merged.added == 0 && merged.groupsAdded == 0) { toast(getString(R.string.merge_no_changes, merged.skipped)); return; }
            applyImportedState(merged.state, true, getString(R.string.merge_success, merged.added, merged.skipped));
        });
        showSecure(builder.create());
    }
    private void confirmOverwrite(VaultState restored) {
        showSecure(new AlertDialog.Builder(this).setTitle(getString(R.string.ui_replace_all_local_data_170))
            .setMessage(getString(R.string.overwrite_body, restored.accounts.size(), restored.groups.size()))
            .setNegativeButton(getString(R.string.ui_cancel_069), null).setPositiveButton(getString(R.string.ui_confirm_replacement_174), (d, w) -> applyImportedState(restored, false, getString(R.string.ui_all_data_was_replaced_from_175))).create());
    }
    private void applyImportedState(VaultState state, boolean merged, String message) {
        try {
            vault.save(state); accounts.clear(); accounts.addAll(state.accounts); groups.clear(); groups.addAll(state.groups); fatal = false;
            screen = merged ? Screen.BACKUP : Screen.OTP; selectedGroupId = null; passwordQuery = ""; render(); toast(message);
        } catch (Exception error) { toast(getString(R.string.ui_import_failed_the_original_file_176)); }
    }
    private void readBackupFile(android.net.Uri uri) {
        backupTask(getString(R.string.ui_reading_encrypted_txt_177), () -> {
            try (java.io.InputStream stream = getContentResolver().openInputStream(uri); java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
                if (stream == null) throw new java.io.IOException("Unavailable input stream");
                byte[] buffer = new byte[8192]; int read, total = 0;
                while ((read = stream.read(buffer)) != -1) {
                    total += read; if (total > BackupCodec.MAX_TEXT_BYTES) throw new IllegalArgumentException(getString(R.string.ui_the_txt_file_exceeds_the_178));
                    output.write(buffer, 0, read);
                }
                return new String(output.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
            }
        }, this::importBackup);
    }
    private void writeBackupFile(android.net.Uri uri, String text) {
        backupTask(getString(R.string.ui_saving_encrypted_txt_179), () -> {
            try (java.io.OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                if (output == null) throw new java.io.IOException("Unavailable output stream");
                output.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)); output.flush(); return true;
            }
        }, saved -> toast(getString(R.string.ui_encrypted_txt_backup_saved_180)));
    }
    private Otp.Account updateLogin(Otp.Account old, LoginDetails details) {
        int index = accounts.indexOf(old);
        if (index < 0 || !unlocked) { toast(getString(R.string.ui_please_reopen_this_entry_181)); return null; }
        Otp.Account updated = old.withLogin(details);
        List<Otp.Account> next = new ArrayList<>(accounts); next.set(index, updated);
        return persist(next) ? updated : null;
    }
    private void login(Otp.Account account) {
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(20), dp(8), dp(20), dp(12));
        if (Build.VERSION.SDK_INT >= 29) form.setImportantForContentCapture(View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS);
        form.addView(text(account.issuer, 23, INK, true));
        form.addView(text(getString(R.string.group_field, groupName(account.groupId)), 13, MUTED, false)); space(form, 14);
        button(form, getString(R.string.ui_copy_all_183), () -> copySensitive(PasswordPresentation.copyAll(account, new String[]{getString(R.string.copy_name), getString(R.string.copy_url), getString(R.string.copy_username), getString(R.string.copy_password), getString(R.string.copy_notes)}), getString(R.string.ui_complete_website_credentials_184), getString(account.login.notes.isEmpty() ? R.string.credentials_copied : R.string.credentials_notes_copied))); space(form, 16);
        form.addView(text(getString(R.string.ui_website_url_188), 12, MUTED, false)); form.addView(text(account.login.website.isEmpty() ? getString(R.string.ui_not_provided_189) : account.login.website, 15, INK, false)); space(form, 12);
        form.addView(text(getString(R.string.ui_username_190), 12, MUTED, false)); form.addView(text(account.login.username, 16, INK, true));
        Button copyUser = new Button(this); copyUser.setText(getString(R.string.ui_copy_username_191)); copyUser.setOnClickListener(v -> copySensitive(account.login.username, getString(R.string.ui_username_190), getString(R.string.ui_username_copied_192))); form.addView(copyUser);
        form.addView(text(getString(R.string.ui_password_193), 12, MUTED, false)); TextView password = text("••••••••", 20, INK, true); password.setTypeface(Typeface.MONOSPACE); form.addView(password);
        CheckBox reveal = new CheckBox(this); reveal.setText(getString(R.string.ui_show_password_194));
        reveal.setOnCheckedChangeListener((v, checked) -> password.setText(checked ? account.login.password : "••••••••")); form.addView(reveal);
        Button copyPassword = new Button(this); copyPassword.setText(getString(R.string.ui_copy_password_195)); copyPassword.setOnClickListener(v -> copySensitive(account.login.password, getString(R.string.ui_website_password_083), getString(R.string.ui_password_copied_paste_it_promptly_196))); form.addView(copyPassword);
        space(form, 14); form.addView(text(getString(R.string.ui_notes_197), 12, MUTED, false));
        form.addView(text(account.login.notes.isEmpty() ? getString(R.string.ui_not_provided_189) : account.login.notes, 15, account.login.notes.isEmpty() ? MUTED : INK, false)); space(form, 12);
        Button move = new Button(this); move.setText(getString(R.string.ui_move_to_another_group_ungrouped_198)); form.addView(move);
        Button delete = new Button(this); delete.setText(getString(R.string.ui_delete_website_credentials_199)); delete.setTextColor(0xFF9C3D36); form.addView(delete);
        ScrollView scroll = new ScrollView(this); scroll.addView(form);
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle(getString(R.string.credentials_title, account.issuer.isEmpty() ? getString(R.string.ui_authenticator_account_085) : account.issuer)).setView(scroll)
            .setNegativeButton(getString(R.string.ui_close_079), null).setPositiveButton(getString(R.string.ui_edit_201), null);
        if (!account.passwordOnly) builder.setNeutralButton(getString(R.string.ui_remove_login_details_202), null);
        AlertDialog dialog = showSecure(builder.create());
        move.setOnClickListener(v -> { dialog.dismiss(); movePassword(account); });
        delete.setOnClickListener(v -> showSecure(new AlertDialog.Builder(this).setTitle(getString(R.string.delete_credentials_title, account.issuer))
            .setMessage(account.passwordOnly ? getString(account.recoveryCodes.isEmpty() ? R.string.delete_credentials_body : R.string.delete_credentials_legacy_body) : getString(R.string.ui_only_login_details_and_notes_207))
            .setNegativeButton(getString(R.string.ui_keep_096), null).setPositiveButton(getString(R.string.ui_delete_208), (d, w) -> {
                boolean saved;
                if (account.passwordOnly) { List<Otp.Account> next = new ArrayList<>(accounts); next.remove(account); saved = persist(next); }
                else saved = updateLogin(account, LoginDetails.EMPTY) != null;
                if (saved) { password.setText("••••••••"); dialog.dismiss(); }
            }).create()));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> { dialog.dismiss(); editLogin(account); });
        if (!account.passwordOnly) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v ->
            showSecure(new AlertDialog.Builder(this).setTitle(getString(R.string.ui_remove_login_details_209)).setMessage(getString(R.string.ui_only_the_website_url_username_210))
                .setNegativeButton(getString(R.string.ui_keep_096), null).setPositiveButton(getString(R.string.ui_remove_097), (d, w) -> {
                    if (updateLogin(account, LoginDetails.EMPTY) != null) { password.setText("••••••••"); dialog.dismiss(); }
                }).create()));
    }
    private void editLogin(Otp.Account account) {
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(20), dp(8), dp(20), dp(12));
        if (Build.VERSION.SDK_INT >= 29) form.setImportantForContentCapture(View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS);
        form.addView(text(getString(R.string.ui_manually_save_website_credentials_all_211), 13, MUTED, false));
        EditText title = field(form, getString(R.string.ui_website_or_entry_name_for_212), false);
        EditText website = field(form, getString(R.string.ui_website_url_optional_213), false); website.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText username = field(form, getString(R.string.ui_username_email_214), false); username.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        EditText password = field(form, getString(R.string.ui_login_password_215), true);
        CheckBox reveal = new CheckBox(this); reveal.setText(getString(R.string.ui_show_password_194));
        reveal.setOnCheckedChangeListener((v, checked) -> {
            int cursor = password.getSelectionStart();
            password.setTransformationMethod(checked ? null : android.text.method.PasswordTransformationMethod.getInstance());
            if (cursor >= 0) password.setSelection(Math.min(cursor, password.length()));
        }); form.addView(reveal);
        EditText notes = new EditText(this); notes.setHint(getString(R.string.ui_notes_optional_multiline_216)); notes.setTextSize(15); notes.setGravity(Gravity.TOP);
        notes.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        notes.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        notes.setMinLines(3); notes.setMaxLines(6); notes.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO); notes.setSaveEnabled(false);
        if (Build.VERSION.SDK_INT >= 29) notes.setImportantForContentCapture(View.IMPORTANT_FOR_CONTENT_CAPTURE_NO);
        form.addView(notes, new LinearLayout.LayoutParams(-1, -2));
        if (account != null) { title.setText(account.issuer); website.setText(account.login.website); username.setText(account.login.username); password.setText(account.login.password); notes.setText(account.login.notes); }
        form.addView(text(getString(R.string.ui_group_optional_217), 12, MUTED, false));
        Spinner group = new Spinner(this); form.addView(group, new LinearLayout.LayoutParams(-1, dp(48)));
        List<String> groupIds = new ArrayList<>();
        String initialGroup = account != null ? account.groupId : selectedGroupId != null && hasGroup(selectedGroupId) ? selectedGroupId : "";
        fillGroupChoices(group, groupIds, initialGroup);
        TextView newGroup = text(getString(R.string.ui_create_group_071), 14, GREEN, true); newGroup.setPadding(0, dp(10), 0, dp(12));
        newGroup.setOnClickListener(v -> createGroup(created -> fillGroupChoices(group, groupIds, created.id))); form.addView(newGroup);
        form.addView(text(getString(R.string.ui_passwords_are_preserved_exactly_including_218), 12, MUTED, false));
        ScrollView scroll = new ScrollView(this); scroll.addView(form);
        AlertDialog dialog = showSecure(new AlertDialog.Builder(this).setTitle(account == null ? getString(R.string.ui_add_website_credentials_219) : getString(R.string.ui_edit_login_details_220))
            .setView(scroll).setNegativeButton(getString(R.string.ui_cancel_069), null).setPositiveButton(getString(R.string.ui_save_encrypted_221), null).create());
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
                    if (index < 0) { toast(getString(R.string.ui_please_reopen_this_entry_181)); return; }
                    Otp.Account updated = account.withLogin(details).withTitle(title.getText().toString()).withGroup(groupId);
                    List<Otp.Account> next = new ArrayList<>(accounts); next.set(index, updated); saved = persist(next) ? updated : null;
                }
                if (saved != null) { password.setText(""); notes.setText(""); dialog.dismiss(); toast(getString(R.string.ui_credentials_and_notes_saved_encrypted_222)); }
            } catch (IllegalArgumentException e) { password.setError(localError(e.getMessage())); }
        });
    }
    private void fillGroupChoices(Spinner spinner, List<String> ids, String selected) {
        ids.clear(); ids.add(""); List<String> names = new ArrayList<>(); names.add(getString(R.string.ui_no_group_223));
        for (PasswordGroup g : groups) { ids.add(g.id); names.add(g.name); }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); spinner.setAdapter(adapter);
        spinner.setSelection(Math.max(0, ids.indexOf(selected)));
    }
    private void movePassword(Otp.Account account) {
        List<String> ids = new ArrayList<>(); ids.add(""); List<String> names = new ArrayList<>(); names.add(getString(R.string.ui_no_group_223));
        for (PasswordGroup g : groups) { ids.add(g.id); names.add(g.name); }
        names.add(getString(R.string.ui_create_group_071));
        java.util.function.Consumer<String> move = id -> {
            int index = accounts.indexOf(account); if (index < 0) { toast(getString(R.string.ui_please_reopen_this_entry_181)); return; }
            Otp.Account updated = account.withGroup(id); List<Otp.Account> next = new ArrayList<>(accounts); next.set(index, updated);
            if (persist(next)) login(updated);
        };
        showSecure(new AlertDialog.Builder(this).setTitle(getString(R.string.ui_move_to_group_224)).setSingleChoiceItems(names.toArray(new String[0]), ids.indexOf(account.groupId), (dialog, which) -> {
            dialog.dismiss();
            if (which == ids.size()) createGroup(created -> move.accept(created.id)); else move.accept(ids.get(which));
        }).setNegativeButton(getString(R.string.ui_cancel_069), null).create());
    }
    private Otp.Account updateRecovery(Otp.Account old, List<RecoveryCodes.Entry> codes) {
        int index = accounts.indexOf(old);
        if (index < 0 || !unlocked) { toast(getString(R.string.ui_please_reopen_this_account_225)); return null; }
        Otp.Account updated = old.withRecoveryCodes(codes);
        List<Otp.Account> next = new ArrayList<>(accounts); next.set(index, updated);
        return persist(next) ? updated : null;
    }
    private void recovery(Otp.Account account) {
        if (account.recoveryCodes.isEmpty()) { editRecovery(account); return; }
        Otp.Account[] current = {account};
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(20), dp(8), dp(20), dp(8));
        body.addView(text(account.name + getString(R.string.ui_stored_only_on_this_device_226), 13, MUTED, false));
        TextView count = text("", 14, GREEN, true); count.setPadding(0, dp(12), 0, dp(8)); body.addView(count);
        CheckBox reveal = new CheckBox(this); reveal.setText(getString(R.string.ui_show_complete_recovery_codes_227)); reveal.setTextColor(INK); body.addView(reveal);
        ScrollView scroll = new ScrollView(this);
        LinearLayout rows = new LinearLayout(this); rows.setOrientation(LinearLayout.VERTICAL); scroll.addView(rows);
        int height = Math.min(340, Math.max(100, (int)(getResources().getDisplayMetrics().heightPixels / getResources().getDisplayMetrics().density) - 330));
        body.addView(scroll, new LinearLayout.LayoutParams(-1, dp(height)));
        Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            rows.removeAllViews();
            count.setText(getString(R.string.recovery_remaining_count, RecoveryCodes.remaining(current[0].recoveryCodes), current[0].recoveryCodes.size()));
            for (int i = 0; i < current[0].recoveryCodes.size(); i++) {
                final int index = i;
                RecoveryCodes.Entry entry = current[0].recoveryCodes.get(i);
                TextView value = text((i + 1) + ".  " + (reveal.isChecked() ? entry.code : "••••  ••••") + (entry.used ? getString(R.string.ui_used_228) : ""), 16, entry.used ? MUTED : INK, true);
                value.setTypeface(Typeface.MONOSPACE); value.setPadding(0, dp(16), 0, dp(6)); value.setSaveEnabled(false); rows.addView(value);
                LinearLayout actions = new LinearLayout(this); actions.setOrientation(LinearLayout.HORIZONTAL);
                Button copy = new Button(this); copy.setText(getString(R.string.ui_copy_053)); copy.setAllCaps(false); copy.setEnabled(!entry.used);
                copy.setOnClickListener(v -> copySensitive(entry.code, getString(R.string.ui_recovery_code_229), getString(R.string.ui_recovery_code_copied_mark_it_230)));
                actions.addView(copy, new LinearLayout.LayoutParams(0, dp(48), 1));
                Button used = new Button(this); used.setText(entry.used ? getString(R.string.ui_mark_unused_231) : getString(R.string.ui_mark_used_232)); used.setAllCaps(false);
                used.setOnClickListener(v -> {
                    Otp.Account updated = updateRecovery(current[0], RecoveryCodes.setUsed(current[0].recoveryCodes, index, !entry.used));
                    if (updated != null) { current[0] = updated; refresh[0].run(); }
                });
                actions.addView(used, new LinearLayout.LayoutParams(0, dp(48), 1)); rows.addView(actions);
            }
        };
        reveal.setOnCheckedChangeListener((b, checked) -> refresh[0].run()); refresh[0].run();
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(getString(R.string.ui_recovery_codes_233) + (account.issuer.isEmpty() ? getString(R.string.ui_authenticator_account_085) : account.issuer))
            .setView(body).setNegativeButton(getString(R.string.ui_close_079), null).setPositiveButton(getString(R.string.ui_replace_clear_234), null).create();
        showSecure(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> { dialog.dismiss(); editRecovery(current[0]); });
    }
    private void editRecovery(Otp.Account account) {
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(20), dp(8), dp(20), dp(8));
        form.addView(text(account.name + getString(R.string.ui_paste_the_complete_recovery_code_235), 14, MUTED, false));
        EditText input = new EditText(this); input.setHint(getString(R.string.ui_paste_recovery_codes_here_236)); input.setTextSize(15); input.setGravity(Gravity.TOP);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO); input.setSaveEnabled(false);
        form.addView(input, new LinearLayout.LayoutParams(-1, dp(150)));
        form.addView(text(getString(R.string.ui_encrypted_on_this_device_if_237), 12, MUTED, false));
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle(account.recoveryCodes.isEmpty() ? getString(R.string.ui_save_recovery_codes_238) : getString(R.string.ui_replace_all_recovery_codes_239))
            .setView(form).setNegativeButton(getString(R.string.ui_cancel_069), null).setPositiveButton(getString(R.string.ui_save_082), null);
        if (!account.recoveryCodes.isEmpty()) builder.setNeutralButton(getString(R.string.ui_clear_240), null);
        AlertDialog dialog = showSecure(builder.create());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                List<RecoveryCodes.Entry> parsed = RecoveryCodes.parse(input.getText().toString());
                Runnable save = () -> {
                    Otp.Account updated = updateRecovery(account, parsed);
                    if (updated != null) { input.setText(""); dialog.dismiss(); toast(getString(R.string.recovery_saved, parsed.size())); recovery(updated); }
                };
                if (account.recoveryCodes.isEmpty()) save.run();
                else showSecure(new AlertDialog.Builder(this).setTitle(getString(R.string.recovery_replace_title, parsed.size()))
                    .setMessage(getString(R.string.ui_this_deletes_the_account_s_245))
                    .setNegativeButton(getString(R.string.ui_cancel_069), null).setPositiveButton(getString(R.string.ui_confirm_replacement_246), (d, w) -> save.run()).create());
            } catch (IllegalArgumentException e) { input.setError(localError(e.getMessage())); }
        });
        if (!account.recoveryCodes.isEmpty()) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v ->
            showSecure(new AlertDialog.Builder(this).setTitle(getString(R.string.ui_clear_this_account_s_recovery_247))
                .setMessage(getString(R.string.ui_only_the_locally_saved_recovery_248))
                .setNegativeButton(getString(R.string.ui_keep_096), null).setPositiveButton(getString(R.string.ui_clear_codes_249), (d, w) -> {
                    if (updateRecovery(account, java.util.Collections.emptyList()) != null) { input.setText(""); dialog.dismiss(); toast(getString(R.string.ui_local_recovery_codes_cleared_250)); }
                }).create()));
    }
    private void help() { openScreen(Screen.HELP); }
    private String localError(String message) {
        String key = ErrorMessages.resourceKey(message);
        int id = key == null ? 0 : getResources().getIdentifier(key, "string", getPackageName());
        return id == 0 ? message : getString(id);
    }
    private void renderLanguagePage(boolean firstRun) {
        space(root, 8);
        root.addView(text(getString(firstRun ? R.string.language_welcome : R.string.language_setting), 28, INK, true));
        space(root, 12);
        root.addView(text(getString(firstRun ? R.string.language_first_run : R.string.language_help), 14, MUTED, false));
        space(root, 16);
        ScrollView scroll = new ScrollView(this);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); scroll.addView(body);
        languageOption(body, "system", "🌐  " + getString(R.string.language_system));
        String[] labels = getResources().getStringArray(R.array.language_native_labels);
        for (int i = 0; i < LanguageCatalog.CODES.length; i++) languageOption(body, LanguageCatalog.CODES[i], labels[i]);
        space(body, 12);
        body.addView(text(getString(R.string.language_flags_help), 12, MUTED, false));
    }
    private void languageOption(LinearLayout parent, String code, String label) {
        boolean selected = AppLanguage.hasSelection(this) && code.equals(AppLanguage.selected(this));
        TextView option = navItem(parent, (selected ? "✓  " : "") + label, selected, () -> {
            if (!AppLanguage.hasSelection(this) || !code.equals(AppLanguage.selected(this))) {
                AppLanguage.save(this, code); recreate();
            }
        });
        if (!code.equals("system")) option.setTextLocale(java.util.Locale.forLanguageTag(code));
    }
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
