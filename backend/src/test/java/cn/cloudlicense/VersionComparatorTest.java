package cn.cloudlicense;

import cn.cloudlicense.service.VersionComparator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionComparatorTest {
    @Test
    void comparesNumericSegments() {
        assertTrue(VersionComparator.INSTANCE.compare("1.12.0", "1.9.8") > 0);
    }

    @Test
    void ignoresBuildMetadataAndLeadingV() {
        assertEquals(0, VersionComparator.INSTANCE.compare("v2.0+release", "2.0.0+other"));
    }

    @Test
    void stableReleaseIsNewerThanPreRelease() {
        assertTrue(VersionComparator.INSTANCE.compare("2.0.0", "2.0.0-rc.2") > 0);
    }

    @Test
    void comparesNumericIdentifiersWithoutIntegerOverflow() {
        assertTrue(VersionComparator.INSTANCE.compare("10000000000.0", "9999999999.0") > 0);
        assertTrue(VersionComparator.INSTANCE.compare(
                "1.0.0-rc.10000000000", "1.0.0-rc.9999999999") > 0);
        assertEquals(0, VersionComparator.INSTANCE.compare("1.0.0-rc.0002", "1.0.0-rc.2"));
    }
}
