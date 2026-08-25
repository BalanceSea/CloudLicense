package cn.cloudlicense.sdk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticVersionsTest {
    @Test
    void comparesNumericSegmentsInsteadOfLexicographicText() {
        assertTrue(SemanticVersions.compare("1.10.0", "1.9.9") > 0);
    }

    @Test
    void treatsMissingSegmentsAsZero() {
        assertEquals(0, SemanticVersions.compare("v2.1", "2.1.0"));
    }

    @Test
    void ignoresBuildMetadata() {
        assertEquals(0, SemanticVersions.compare("1.2.3+build.9", "1.2.3+build.2"));
    }

    @Test
    void ordersPreReleaseIdentifiers() {
        assertTrue(SemanticVersions.compare("1.0.0-rc.2", "1.0.0-beta.10") > 0);
        assertTrue(SemanticVersions.compare("1.0.0", "1.0.0-rc.2") > 0);
    }

    @Test
    void comparesNumericIdentifiersWithoutIntegerOverflow() {
        assertTrue(SemanticVersions.compare("10000000000.0", "9999999999.0") > 0);
        assertTrue(SemanticVersions.compare(
                "1.0.0-rc.10000000000", "1.0.0-rc.9999999999") > 0);
        assertEquals(0, SemanticVersions.compare("1.0.0-rc.0002", "1.0.0-rc.2"));
    }
}
