package cn.local.simpleauth;

import android.content.Context;
import android.content.res.Configuration;
import java.util.Locale;

/** Only this non-sensitive UI preference is stored outside the encrypted vault. */
final class AppLanguage {
    private AppLanguage() {}
    static String selected(Context context) {
        return context.getSharedPreferences("ui_preferences", Context.MODE_PRIVATE).getString("language", "system");
    }
    static void save(Context context, String language) {
        if (!language.equals("system") && !language.equals("zh") && !language.equals("en")) throw new IllegalArgumentException("Unsupported UI language");
        context.getSharedPreferences("ui_preferences", Context.MODE_PRIVATE).edit().putString("language", language).apply();
    }
    static Context wrap(Context base) {
        String language = selected(base);
        if (language.equals("system")) return base;
        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        Locale locale = language.equals("zh") ? Locale.SIMPLIFIED_CHINESE : Locale.ENGLISH;
        configuration.setLocale(locale); configuration.setLayoutDirection(locale);
        return base.createConfigurationContext(configuration);
    }
    static boolean isEnglish(Context context) {
        return !context.getResources().getConfiguration().getLocales().get(0).getLanguage().equals("zh");
    }
}
