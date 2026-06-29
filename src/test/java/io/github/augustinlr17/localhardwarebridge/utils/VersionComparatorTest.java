package io.github.augustinlr17.localhardwarebridge.utils;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link VersionComparator}.
 * Covers semver parsing, comparison, pre-release precedence, and edge cases.
 * Fully hermetic.
 */
public class VersionComparatorTest {

    // --- Basic semver comparison ---

    @Test
    public void equalVersionsReturnZero() {
        assertEquals(0, VersionComparator.compare("2.0.0", "2.0.0"));
    }

    @Test
    public void higherMajorIsNewer() {
        assertTrue(VersionComparator.isNewer("1.0.0", "2.0.0"));
        assertFalse(VersionComparator.isNewer("2.0.0", "1.0.0"));
    }

    @Test
    public void higherMinorIsNewer() {
        assertTrue(VersionComparator.isNewer("2.0.0", "2.1.0"));
        assertFalse(VersionComparator.isNewer("2.1.0", "2.0.0"));
    }

    @Test
    public void higherPatchIsNewer() {
        assertTrue(VersionComparator.isNewer("2.0.0", "2.0.1"));
        assertFalse(VersionComparator.isNewer("2.0.1", "2.0.0"));
    }

    // --- Numeric (not lexicographic) comparison ---

    @Test
    public void numericComparisonNotLexicographic() {
        // 2.10.0 should be newer than 2.9.0 (not older as lexicographic would give)
        assertTrue(VersionComparator.isNewer("2.9.0", "2.10.0"));
        assertFalse(VersionComparator.isNewer("2.10.0", "2.9.0"));
    }

    @Test
    public void doubleDigitPatch() {
        assertTrue(VersionComparator.isNewer("1.0.9", "1.0.10"));
    }

    // --- v-prefix handling ---

    @Test
    public void vPrefixIsStripped() {
        assertEquals(0, VersionComparator.compare("v2.0.0", "2.0.0"));
        assertEquals(0, VersionComparator.compare("V2.0.0", "2.0.0"));
        assertEquals(0, VersionComparator.compare("2.0.0", "v2.0.0"));
    }

    @Test
    public void vPrefixComparison() {
        assertTrue(VersionComparator.isNewer("v1.0.0", "v2.0.0"));
    }

    // --- Pre-release handling ---

    @Test
    public void releaseIsNewerThanPreRelease() {
        // 2.0.0 (release) > 2.0.0-rc.1 (pre-release)
        assertTrue(VersionComparator.isNewer("2.0.0-rc.1", "2.0.0"));
        assertFalse(VersionComparator.isNewer("2.0.0", "2.0.0-rc.1"));
    }

    @Test
    public void preReleaseRcComparison() {
        // rc.2 > rc.1
        assertTrue(VersionComparator.isNewer("2.0.0-rc.1", "2.0.0-rc.2"));
        assertFalse(VersionComparator.isNewer("2.0.0-rc.2", "2.0.0-rc.1"));
    }

    @Test
    public void preReleaseAlphaBeforeBeta() {
        // beta > alpha (lexicographic for alphanumeric)
        assertTrue(VersionComparator.isNewer("2.0.0-alpha.1", "2.0.0-beta.1"));
        assertFalse(VersionComparator.isNewer("2.0.0-beta.1", "2.0.0-alpha.1"));
    }

    @Test
    public void preReleaseBetaBeforeRc() {
        assertTrue(VersionComparator.isNewer("2.0.0-beta.1", "2.0.0-rc.1"));
    }

    @Test
    public void preReleaseNumericSegmentComparison() {
        // rc.10 > rc.2 (numeric, not lexicographic)
        assertTrue(VersionComparator.isNewer("2.0.0-rc.2", "2.0.0-rc.10"));
    }

    @Test
    public void morePreReleaseSegmentsIsNewer() {
        // 2.0.0-rc.1.2 > 2.0.0-rc.1
        assertTrue(VersionComparator.isNewer("2.0.0-rc.1", "2.0.0-rc.1.2"));
    }

    @Test
    public void preReleaseWithSameCoreDifferentPre() {
        assertTrue(VersionComparator.isNewer("2.1.0-alpha", "2.1.0-beta"));
    }

    @Test
    public void differentCoreWithPreRelease() {
        // 2.0.0-rc.1 vs 2.1.0-rc.1 → core wins
        assertTrue(VersionComparator.isNewer("2.0.0-rc.1", "2.1.0-rc.1"));
    }

    @Test
    public void buildMetadataIsIgnored() {
        // 2.0.0+build123 == 2.0.0
        assertEquals(0, VersionComparator.compare("2.0.0+build123", "2.0.0"));
    }

    @Test
    public void preReleaseWithBuildMetadata() {
        // 2.0.0-rc.1+build5 == 2.0.0-rc.1
        assertEquals(0, VersionComparator.compare("2.0.0-rc.1+build5", "2.0.0-rc.1"));
    }

    // --- Edge cases ---

    @Test
    public void nullVersionsAreTreatedAsZero() {
        assertEquals(0, VersionComparator.compare(null, null));
        assertEquals(0, VersionComparator.compare(null, "0.0.0"));
        assertTrue(VersionComparator.isNewer(null, "1.0.0"));
    }

    @Test
    public void blankVersionsAreTreatedAsZero() {
        assertEquals(0, VersionComparator.compare("", ""));
        assertEquals(0, VersionComparator.compare("  ", "0.0.0"));
        assertTrue(VersionComparator.isNewer("", "1.0.0"));
    }

    @Test
    public void missingPatchDefaultsToZero() {
        assertEquals(0, VersionComparator.compare("2.0", "2.0.0"));
        assertEquals(0, VersionComparator.compare("2", "2.0.0"));
    }

    @Test
    public void nonNumericSegmentTreatedAsZero() {
        // "2.x.0" → core = [2, 0, 0]
        assertEquals(0, VersionComparator.compare("2.x.0", "2.0.0"));
    }

    @Test
    public void currentVersionIsNotEmptyAndComparable() {
        // Make sure Constants.VERSION can be compared without exceptions
        int result = VersionComparator.compare("0.0.1", io.github.augustinlr17.localhardwarebridge.Constants.VERSION);
        // 0.0.1 should be older than the current version (which is 2.x.x)
        assertTrue("0.0.1 should be older than current version", result < 0);
    }

    @Test
    public void compareReturnsNegativeForOlder() {
        assertTrue(VersionComparator.compare("1.0.0", "2.0.0") < 0);
    }

    @Test
    public void compareReturnsPositiveForNewer() {
        assertTrue(VersionComparator.compare("2.0.0", "1.0.0") > 0);
    }

    @Test
    public void compareReturnsZeroForEqual() {
        assertEquals(0, VersionComparator.compare("2.0.0", "2.0.0"));
    }

    @Test
    public void isNewerFalseForEqualVersions() {
        assertFalse(VersionComparator.isNewer("2.0.0", "2.0.0"));
    }

    @Test
    public void isNewerFalseForOlderThanLatest() {
        assertFalse(VersionComparator.isNewer("3.0.0", "2.0.0"));
    }

    @Test
    public void trailingPreReleaseDashOnly() {
        // "2.0.0-" → no pre-release content → treated as release
        assertEquals(0, VersionComparator.compare("2.0.0-", "2.0.0"));
    }

    // --- Semver spec examples (from semver.org §11) ---

    @Test
    public void semverSpecExample1_1_0_alpha_1_0() {
        // 1.0.0-alpha < 1.0.0
        assertTrue(VersionComparator.isNewer("1.0.0-alpha", "1.0.0"));
    }

    @Test
    public void semverSpecExample1_0_0_alpha_1_0_0_beta() {
        // 1.0.0-alpha < 1.0.0-beta
        assertTrue(VersionComparator.isNewer("1.0.0-alpha", "1.0.0-beta"));
    }

    @Test
    public void semverSpecExample1_0_0_beta_1_0_0_rc() {
        // 1.0.0-beta < 1.0.0-rc.1
        assertTrue(VersionComparator.isNewer("1.0.0-beta", "1.0.0-rc.1"));
    }

    @Test
    public void semverSpecExample1_0_0_rc_1_1_0_0() {
        // 1.0.0-rc.1 < 1.0.0
        assertTrue(VersionComparator.isNewer("1.0.0-rc.1", "1.0.0"));
    }

    @Test
    public void semverSpecExampleNumericPreReleaseIncrement() {
        // 1.0.0-rc.1 < 1.0.0-rc.2
        assertTrue(VersionComparator.isNewer("1.0.0-rc.1", "1.0.0-rc.2"));
    }

    @Test
    public void semverSpecExample1_0_0_alpha_1_1_0_0_alpha_beta() {
        // 1.0.0-alpha.1 < 1.0.0-alpha.beta
        // (numeric 1 < alphanumeric "beta")
        assertTrue(VersionComparator.isNewer("1.0.0-alpha.1", "1.0.0-alpha.beta"));
    }

    // --- Multi-segment pre-release ---

    @Test
    public void preReleaseWithThreeSegments() {
        // 1.0.0-alpha.1.beta < 1.0.0-alpha.1.beta.2
        assertTrue(VersionComparator.isNewer("1.0.0-alpha.1.beta", "1.0.0-alpha.1.beta.2"));
    }

    @Test
    public void preReleaseNumericVsAlphanumericAtDifferentPosition() {
        // 1.0.0-1.alpha < 1.0.0-alpha.1
        // First segment: numeric 1 < alphanumeric "alpha"
        assertTrue(VersionComparator.isNewer("1.0.0-1.alpha", "1.0.0-alpha.1"));
    }

    @Test
    public void preReleaseSameSegmentsEqual() {
        assertEquals(0, VersionComparator.compare("1.0.0-rc.1", "1.0.0-rc.1"));
    }

    // --- 4-part versions (not strict semver but should not crash) ---

    @Test
    public void fourPartVersionDoesNotCrash() {
        // 4th segment is ignored (only first 3 are parsed)
        assertEquals(0, VersionComparator.compare("2.0.0.1", "2.0.0.2"));
    }

    @Test
    public void fourPartVersionCoreComparison() {
        assertTrue(VersionComparator.isNewer("1.9.9.9", "2.0.0.0"));
    }

    // --- Whitespace and trim ---

    @Test
    public void leadingTrailingWhitespaceIsTrimmed() {
        assertEquals(0, VersionComparator.compare("  2.0.0  ", "2.0.0"));
    }

    @Test
    public void whitespaceOnlyTreatedAsZero() {
        assertEquals(0, VersionComparator.compare("   ", "0.0.0"));
    }

    // --- Complex real-world version strings ---

    @Test
    public void githubTagWithVAndPreRelease() {
        assertTrue(VersionComparator.isNewer("v2.0.0", "v2.1.0-rc.1"));
    }

    @Test
    public void versionWithBuildMetadataAndPreRelease() {
        // 2.0.0-rc.1+build123 == 2.0.0-rc.1 (build metadata ignored)
        assertEquals(0, VersionComparator.compare("2.0.0-rc.1+build123", "2.0.0-rc.1"));
    }

    @Test
    public void largeVersionNumbers() {
        assertTrue(VersionComparator.isNewer("99.99.99", "100.0.0"));
    }

    @Test
    public void zeroVersions() {
        assertEquals(0, VersionComparator.compare("0.0.0", "0.0.0"));
        assertTrue(VersionComparator.isNewer("0.0.0", "0.0.1"));
        assertTrue(VersionComparator.isNewer("0.0.0", "0.1.0"));
        assertTrue(VersionComparator.isNewer("0.0.0", "1.0.0"));
    }

    @Test
    public void preReleaseOnZeroVersion() {
        // 0.0.1-alpha < 0.0.1
        assertTrue(VersionComparator.isNewer("0.0.1-alpha", "0.0.1"));
    }

    // --- Real release sequence simulation ---

    @Test
    public void realReleaseSequence() {
        // Simulate the full release history of a typical project
        String[] versions = {
            "1.0.0", "1.0.1", "1.0.2",
            "1.1.0", "1.1.1",
            "1.2.0",
            "2.0.0-alpha", "2.0.0-beta", "2.0.0-rc.1", "2.0.0-rc.2",
            "2.0.0",
            "2.0.1",
            "2.1.0",
        };
        // Each version should be newer than the previous
        for (int i = 0; i < versions.length - 1; i++) {
            assertTrue(
                versions[i + 1] + " should be newer than " + versions[i],
                VersionComparator.isNewer(versions[i], versions[i + 1])
            );
        }
    }

    // --- Symmetry: compare(a,b) == -compare(b,a) ---

    @Test
    public void symmetryForDifferentVersions() {
        String[] pairs = {
            "1.0.0", "2.0.0",
            "2.0.0", "2.0.0-rc.1",
            "2.0.0-rc.1", "2.0.0-rc.2",
            "1.0.0-alpha", "1.0.0-beta",
            "1.0.0", "1.0.1",
        };
        for (int i = 0; i < pairs.length; i += 2) {
            int forward = VersionComparator.compare(pairs[i], pairs[i + 1]);
            int backward = VersionComparator.compare(pairs[i + 1], pairs[i]);
            assertTrue("Symmetry for " + pairs[i] + " vs " + pairs[i + 1],
                forward == -backward);
        }
    }

    // --- Transitivity: if a < b and b < c then a < c ---

    @Test
    public void transitivityABC() {
        String a = "1.0.0-alpha";
        String b = "1.0.0-rc.1";
        String c = "1.0.0";
        assertTrue("a < b", VersionComparator.isNewer(a, b));
        assertTrue("b < c", VersionComparator.isNewer(b, c));
        assertTrue("a < c (transitive)", VersionComparator.isNewer(a, c));
    }

    @Test
    public void transitivityNumericSegments() {
        String a = "1.9.0";
        String b = "1.10.0";
        String c = "1.11.0";
        assertTrue(VersionComparator.isNewer(a, b));
        assertTrue(VersionComparator.isNewer(b, c));
        assertTrue(VersionComparator.isNewer(a, c));
    }

    // --- Pre-release identifier edge cases ---

    @Test
    public void emptyPreReleaseSegment() {
        // "2.0.0-rc..1" → segments are ["rc", "", "1"]
        // Empty string is non-numeric (alphanumeric), so it compares as string
        // Should not crash
        VersionComparator.compare("2.0.0-rc..1", "2.0.0-rc.1");
    }

    @Test
    public void preReleaseWithSpecialChars() {
        // "2.0.0-alpha-1" — the dash inside is part of the segment "alpha-1"
        // This is technically not valid semver but should not crash
        VersionComparator.compare("2.0.0-alpha-1", "2.0.0-beta");
    }

    // --- Mixed v-prefix and no prefix ---

    @Test
    public void mixedVAndNoVPrefixComparison() {
        assertTrue(VersionComparator.isNewer("v1.0.0", "2.0.0"));
        assertTrue(VersionComparator.isNewer("1.0.0", "v2.0.0"));
    }

    // --- Version strings with spaces inside ---

    @Test
    public void versionWithInternalSpaces() {
        // "2. 0.0" — space after dot causes non-numeric parse → 0
        // So core = [2, 0, 0] same as "2.0.0"
        assertEquals(0, VersionComparator.compare("2. 0.0", "2.0.0"));
    }

    // --- Double-digit pre-release numeric identifiers ---

    @Test
    public void preReleaseDoubleDigitNumericVsSingle() {
        // rc.10 > rc.9 (numeric, not lexicographic where "9" > "10")
        assertTrue(VersionComparator.isNewer("2.0.0-rc.9", "2.0.0-rc.10"));
    }

    @Test
    public void preReleaseTripleDigitNumeric() {
        assertTrue(VersionComparator.isNewer("2.0.0-rc.99", "2.0.0-rc.100"));
    }

    // --- Pre-release with different number of segments ---

    @Test
    public void morePreReleaseSegmentsNewer() {
        // 1.0.0-alpha.1.2 > 1.0.0-alpha.1
        assertTrue(VersionComparator.isNewer("1.0.0-alpha.1", "1.0.0-alpha.1.2"));
    }

    @Test
    public void fewerPreReleaseSegmentsOlder() {
        // 1.0.0-alpha < 1.0.0-alpha.1
        assertTrue(VersionComparator.isNewer("1.0.0-alpha", "1.0.0-alpha.1"));
    }

    // --- Non-numeric major version ---

    @Test
    public void nonNumericMajorTreatedAsZero() {
        // "x.0.0" → [0, 0, 0]
        assertEquals(0, VersionComparator.compare("x.0.0", "0.0.0"));
    }

    @Test
    public void nonNumericMinorTreatedAsZero() {
        // "2.x.0" → [2, 0, 0]
        assertEquals(0, VersionComparator.compare("2.x.0", "2.0.0"));
    }

    // --- Only major version ---

    @Test
    public void onlyMajorVersion() {
        assertTrue(VersionComparator.isNewer("1", "2"));
        assertTrue(VersionComparator.isNewer("1", "2.0.0"));
    }

    @Test
    public void onlyMajorAndMinor() {
        assertTrue(VersionComparator.isNewer("1.0", "1.1"));
        assertTrue(VersionComparator.isNewer("1.0", "1.0.1"));
    }

    // --- Constants.VERSION integration ---

    @Test
    public void currentVersionIsNewerThanZero() {
        assertTrue(VersionComparator.isNewer("0.0.0",
            io.github.augustinlr17.localhardwarebridge.Constants.VERSION));
    }

    @Test
    public void currentVersionIsNotNewerThanItself() {
        assertFalse(VersionComparator.isNewer(
            io.github.augustinlr17.localhardwarebridge.Constants.VERSION,
            io.github.augustinlr17.localhardwarebridge.Constants.VERSION));
    }
}
