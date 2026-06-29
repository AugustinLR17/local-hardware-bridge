package io.github.augustinlr17.localhardwarebridge.utils;

import java.util.ArrayList;
import java.util.List;

/**
 * Compares semantic version strings.
 *
 * <p>Supports standard semver ({@code MAJOR.MINOR.PATCH}) with optional
 * pre-release suffixes ({@code -alpha.1}, {@code -rc.2}, etc.).
 * A version <em>without</em> a pre-release suffix is newer than the same
 * numeric core <em>with</em> one (e.g. {@code 2.0.0} > {@code 2.0.0-rc.1}).
 *
 * <p>Each dot-separated numeric segment is compared numerically, not
 * lexicographically, so {@code 2.10.0} > {@code 2.9.0}.
 *
 * <p>For pre-release segments, numeric identifiers are compared numerically and
 * alphanumeric identifiers lexically. Numeric identifiers have lower
 * precedence than alphanumeric ones (per semver spec). If one version has more
 * pre-release segments, all preceding segments being equal, the one with more
 * segments wins (e.g. {@code 2.0.0-rc.1.2} > {@code 2.0.0-rc.1}).
 */
public final class VersionComparator {

    private VersionComparator() {
    }

    /**
     * Returns a negative integer if {@code v1} is older than {@code v2},
     * zero if they are equal, and a positive integer if {@code v1} is newer.
     *
     * <p>Null or blank versions are treated as {@code 0.0.0} so that any
     * valid version is considered newer.
     */
    public static int compare(String v1, String v2) {
        int[] c1 = parseCore(v1);
        int[] c2 = parseCore(v2);
        for (int i = 0; i < 3; i++) {
            if (c1[i] != c2[i]) {
                return Integer.compare(c1[i], c2[i]);
            }
        }
        // Cores are equal: a version without pre-release is newer.
        String pre1 = parsePreRelease(v1);
        String pre2 = parsePreRelease(v2);
        if (pre1 == null && pre2 == null) {
            return 0;
        }
        if (pre1 == null) {
            return 1; // v1 is a release, v2 is a pre-release → v1 newer
        }
        if (pre2 == null) {
            return -1; // v2 is a release, v1 is a pre-release → v2 newer
        }
        return comparePreRelease(pre1, pre2);
    }

    /** Returns {@code true} if {@code current} is strictly older than {@code latest}. */
    public static boolean isNewer(String current, String latest) {
        return compare(current, latest) < 0;
    }

    // --- parsing helpers ---

    private static int[] parseCore(String version) {
        int[] core = new int[]{0, 0, 0};
        if (version == null || version.isBlank()) {
            return core;
        }
        // Strip a leading 'v' (GitHub tags are often v2.0.0)
        String s = version.trim();
        if (s.startsWith("v") || s.startsWith("V")) {
            s = s.substring(1);
        }
        // Cut off any pre-release suffix
        int dash = s.indexOf('-');
        if (dash >= 0) {
            s = s.substring(0, dash);
        }
        // Cut off build metadata (+...)
        int plus = s.indexOf('+');
        if (plus >= 0) {
            s = s.substring(0, plus);
        }
        String[] parts = s.split("\\.");
        for (int i = 0; i < Math.min(parts.length, 3); i++) {
            try {
                core[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                // Non-numeric segment → treat as 0
            }
        }
        return core;
    }

    private static String parsePreRelease(String version) {
        if (version == null || version.isBlank()) {
            return null;
        }
        String s = version.trim();
        if (s.startsWith("v") || s.startsWith("V")) {
            s = s.substring(1);
        }
        // Remove build metadata first
        int plus = s.indexOf('+');
        if (plus >= 0) {
            s = s.substring(0, plus);
        }
        int dash = s.indexOf('-');
        if (dash < 0 || dash == s.length() - 1) {
            return null;
        }
        return s.substring(dash + 1);
    }

    private static int comparePreRelease(String pre1, String pre2) {
        String[] seg1 = pre1.split("\\.");
        String[] seg2 = pre2.split("\\.");
        int len = Math.max(seg1.length, seg2.length);
        for (int i = 0; i < len; i++) {
            if (i >= seg1.length) {
                return -1; // v1 has fewer segments → v2 newer
            }
            if (i >= seg2.length) {
                return 1; // v2 has fewer segments → v1 newer
            }
            int cmp = compareSegment(seg1[i], seg2[i]);
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    private static int compareSegment(String s1, String s2) {
        boolean n1 = isNumeric(s1);
        boolean n2 = isNumeric(s2);
        if (n1 && n2) {
            return Integer.compare(Integer.parseInt(s1), Integer.parseInt(s2));
        }
        if (n1) {
            return -1; // numeric has lower precedence than alphanumeric
        }
        if (n2) {
            return 1;
        }
        return s1.compareTo(s2);
    }

    private static boolean isNumeric(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        try {
            Integer.parseInt(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
