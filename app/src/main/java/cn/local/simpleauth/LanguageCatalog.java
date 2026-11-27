package cn.local.simpleauth;

import java.util.Arrays;

/** Stable UI preference identifiers; never part of the vault or backup format. */
final class LanguageCatalog {
    private LanguageCatalog() {}
    static final String[] CODES = {"en", "zh", "zh-Hant", "es", "fr", "de", "pt", "ru", "ar", "hi", "bn", "ja", "ko", "id", "tr", "vi", "it", "th", "fa", "ur", "sw"};
    static boolean supports(String code) { return "system".equals(code) || Arrays.asList(CODES).contains(code); }
}
