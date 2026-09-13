package com.securewa.app;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small, deterministic client-side privacy scanner.
 *
 * This is intentionally a policy boundary, not a claim of perfect PII
 * detection. A server-side policy engine should repeat the check before a
 * provider call. The important property is that the share action can use the
 * redacted value rather than the original input.
 */
public final class PrivacyScanner {

    private static final Pattern EMAIL = Pattern.compile(
            "(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b");
    private static final Pattern API_KEY = Pattern.compile(
            "\\b(?:sk|pk|key)-[A-Za-z0-9_-]{10,}\\b");
    private static final Pattern CARD = Pattern.compile(
            "(?<!\\d)(?:\\d[ -]?){13,19}(?!\\d)");
    private static final Pattern PHONE = Pattern.compile(
            "(?<![A-Za-z0-9])\\+?\\d(?:[\\d ().-]{6,}\\d)(?![A-Za-z0-9])");

    private PrivacyScanner() {
    }

    public static ScanResult scan(String value) {
        String source = value == null ? "" : value;
        String redacted = source;
        List<String> findings = new ArrayList<>();

        Replacement email = replace(EMAIL, redacted, "[REDACTED EMAIL]");
        redacted = email.value;
        if (email.count > 0) findings.add("email");

        Replacement apiKey = replace(API_KEY, redacted, "[REDACTED KEY]");
        redacted = apiKey.value;
        if (apiKey.count > 0) findings.add("API key");

        Replacement card = replace(CARD, redacted, "[REDACTED CARD]");
        redacted = card.value;
        if (card.count > 0) findings.add("card number");

        Replacement phone = replace(PHONE, redacted, "[REDACTED PHONE]");
        redacted = phone.value;
        if (phone.count > 0) findings.add("phone number");

        return new ScanResult(source, redacted, findings);
    }

    private static Replacement replace(Pattern pattern, String input, String replacement) {
        Matcher matcher = pattern.matcher(input);
        int count = 0;
        while (matcher.find()) count++;
        return new Replacement(matcher.replaceAll(replacement), count);
    }

    private static final class Replacement {
        final String value;
        final int count;

        Replacement(String value, int count) {
            this.value = value;
            this.count = count;
        }
    }

    public static final class ScanResult {
        public final String originalText;
        public final String redactedText;
        private final List<String> findings;

        private ScanResult(String originalText, String redactedText, List<String> findings) {
            this.originalText = originalText;
            this.redactedText = redactedText;
            this.findings = findings;
        }

        public boolean hasFindings() {
            return !findings.isEmpty();
        }

        public int findingCount() {
            return findings.size();
        }

        public String summary() {
            if (findings.isEmpty()) return "No common PII patterns detected.";
            StringBuilder summary = new StringBuilder("Redacted: ");
            for (int i = 0; i < findings.size(); i++) {
                if (i > 0) summary.append(", ");
                summary.append(findings.get(i));
            }
            return summary.toString();
        }
    }
}
