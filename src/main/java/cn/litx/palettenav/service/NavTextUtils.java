package cn.litx.palettenav.service;

import org.springframework.util.StringUtils;

/**
 * Small shared text helpers for the public page and the public JSON view.
 */
public final class NavTextUtils {

    private NavTextUtils() {
    }

    /** Returns the first non-blank value, trimmed; an empty string when both are blank. */
    public static String firstText(String first, String second) {
        if (StringUtils.hasText(first)) {
            return first.trim();
        }
        return StringUtils.hasText(second) ? second.trim() : "";
    }
}