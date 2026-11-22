package com.lms.course.model;

import com.lms.common.error.ValidationException;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Builds the URL-safe identifier a course is linked by. */
public final class Slug {

    public static final int MAX_LENGTH = 120;

    private static final Pattern VALID = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9]+");
    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}");

    private Slug() {
    }

    /** Derives a slug from free text. Titles with no usable characters are rejected, not emptied. */
    public static String from(String text) {
        if (text == null || text.isBlank()) {
            throw new ValidationException("Cannot build a link from empty text");
        }
        String normalised = Normalizer.normalize(text, Normalizer.Form.NFKD).toLowerCase(Locale.ROOT);
        String ascii = DIACRITICS.matcher(normalised).replaceAll("");
        String slug = NON_ALPHANUMERIC.matcher(ascii).replaceAll("-").replaceAll("^-+|-+$", "");
        if (slug.isEmpty()) {
            throw new ValidationException("Title contains no characters usable in a URL: " + text);
        }
        return slug.length() > MAX_LENGTH ? trimToWordBoundary(slug) : slug;
    }

    public static String requireValid(String slug) {
        if (slug == null || !VALID.matcher(slug).matches() || slug.length() > MAX_LENGTH) {
            throw new ValidationException("Not a usable course link: " + slug);
        }
        return slug;
    }

    private static String trimToWordBoundary(String slug) {
        String cut = slug.substring(0, MAX_LENGTH);
        int lastDash = cut.lastIndexOf('-');
        return lastDash > 0 ? cut.substring(0, lastDash) : cut;
    }
}
