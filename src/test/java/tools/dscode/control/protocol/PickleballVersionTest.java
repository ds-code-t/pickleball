package tools.dscode.control.protocol;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PickleballVersionTest {
    @Test
    void snapshotSortsBeforeReleaseOfSameNumbers() {
        PickleballVersion release = PickleballVersion.parse("2.1.11");
        PickleballVersion snapshot = PickleballVersion.parse("2.1.11-SNAPSHOT");
        PickleballVersion older = PickleballVersion.parse("2.1.10");
        PickleballVersion newerSnapshot = PickleballVersion.parse("2.1.12-SNAPSHOT");
        assertTrue(snapshot.compareTo(release) < 0);
        assertTrue(older.compareTo(release) < 0);
        assertTrue(newerSnapshot.compareTo(release) > 0);
        List<PickleballVersion> versions = new java.util.ArrayList<>(List.of(snapshot, older, release, newerSnapshot));
        versions.sort(PickleballVersion.newestFirst());
        assertEquals("2.1.12-SNAPSHOT", versions.get(0).raw());
        assertEquals("2.1.11", versions.get(1).raw());
        assertEquals("2.1.11-SNAPSHOT", versions.get(2).raw());
        assertEquals("2.1.10", versions.get(3).raw());
    }

    @Test
    void numericMavenOrderTreatsTwoTenGreaterThanTwoNine() {
        assertTrue(PickleballVersion.parse("2.10.0").compareTo(PickleballVersion.parse("2.9.9")) > 0);
    }
}
