package cn.local.simpleauth;

import android.content.Context;
import android.content.res.Configuration;
import java.util.Locale;

/** Only non-sensitive UI preferences are stored outside the encrypted vault. */
final class AppLanguage {
    private AppLanguage() {}
    static String selected(Context context) {
        String saved = context.getSharedPreferences("ui_preferences", Context.MODE_PRIVATE).getString("language", "system");
        return LanguageCatalog.supports(saved) ? saved : "system";
    }
    static boolean hasSelection(Context context) {
        return context.getSharedPreferences("ui_preferences", Context.MODE_PRIVATE).contains("language");
    }
    static void save(Context context, String language) {
        if (!LanguageCatalog.supports(language)) throw new IllegalArgumentException("Unsupported UI language");
        context.getSharedPreferences("ui_preferences", Context.MODE_PRIVATE).edit().putString("language", language).apply();
    }
    static Context wrap(Context base) {
        String language = selected(base);
        if (language.equals("system")) return base;
        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        Locale locale = Locale.forLanguageTag(language.equals("zh") ? "zh-Hans" : language);
        configuration.setLocale(locale); configuration.setLayoutDirection(locale);
        return base.createConfigurationContext(configuration);
    }
}
