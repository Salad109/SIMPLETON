package io.salad109.conjunctiondetector;

import io.salad109.conjunctiondetector.ingestion.SyncResult;
import io.salad109.conjunctiondetector.spacetrack.GpRecord;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

class SyncTest extends AbstractPipelineTest {

    @Test
    void syncIsIdempotent() throws IOException {
        when(spaceTrackClient.fetchCatalog()).thenReturn(List.of(iridium33(), cosmos2251()));

        ingestionService.sync();
        long afterFirst = satelliteService.count();
        ingestionService.sync();
        long afterSecond = satelliteService.count();

        assertThat(afterSecond).as("second sync must not duplicate rows").isEqualTo(afterFirst);
        assertThat(afterFirst).isEqualTo(2);

        SyncResult secondLog = ingestionLogService.getRecent(1).getFirst();
        assertThat(secondLog.objectsInserted()).isZero();
        assertThat(secondLog.objectsUpdated()).isZero();
        assertThat(secondLog.objectsUnchanged()).isEqualTo(2);
        assertThat(secondLog.objectsDeleted()).isZero();
    }

    @Test
    void syncRefreshesChangedRecords() throws IOException {
        when(spaceTrackClient.fetchCatalog()).thenReturn(List.of(iridium33()));
        ingestionService.sync();

        // Newer epoch of existing object must overwrite the stored one
        GpRecord refreshed = buildGp(24946, "IRIDIUM 33",
                LocalDateTime.of(2009, 2, 9, 23, 0, 0),
                "1 24946U 97051C   09040.95833333 +.00000153 +00000-0 +47668-4 0  9991",
                "2 24946 086.3994 121.7028 0002288 085.1644 274.9812 14.34219863597336");
        when(spaceTrackClient.fetchCatalog()).thenReturn(List.of(refreshed));
        ingestionService.sync();

        SyncResult log = ingestionLogService.getRecent(1).getFirst();
        assertThat(log.objectsUpdated()).as("changed epoch refreshes the row").isEqualTo(1);
        assertThat(log.objectsInserted()).isZero();
        assertThat(log.objectsUnchanged()).isZero();
        assertThat(log.objectsSkipped()).isZero();
        assertThat(log.objectsDeleted()).isZero();
        assertThat(satelliteService.count()).isEqualTo(1);

        assertThat(satelliteService.getByCatalogIds(List.of(24946)).get(24946).getEpoch())
                .as("stored epoch reflects the newer element set")
                .isEqualTo(refreshed.getEpochUtc());
    }

    @Test
    void syncSkipsInvalidRecordsAndDeletesMissingOnes() throws IOException {
        // Stale GP with epoch >10 days before frozen clock must be filtered out
        GpRecord stale = buildGp(99999, "STALE",
                LocalDateTime.of(2008, 12, 1, 0, 0),
                "1 99999U 00000A   08336.00000000 +.00000000 +00000-0 +00000-0 0  0000",
                "2 99999 000.0000 000.0000 0000000 000.0000 000.0000 00.00000000000000");
        when(spaceTrackClient.fetchCatalog()).thenReturn(List.of(iridium33(), cosmos2251(), stale));

        ingestionService.sync();
        assertThat(satelliteService.count()).as("stale record rejected at ingest").isEqualTo(2);
        SyncResult firstLog = ingestionLogService.getRecent(1).getFirst();
        assertThat(firstLog.objectsSkipped()).isEqualTo(1);

        when(spaceTrackClient.fetchCatalog()).thenReturn(List.of(iridium33()));
        ingestionService.sync();
        assertThat(satelliteService.count()).as("cosmos pruned").isEqualTo(1);
        SyncResult secondLog = ingestionLogService.getRecent(1).getFirst();
        assertThat(secondLog.objectsDeleted()).isEqualTo(1);
    }

    @Test
    void syncSkipsUnpropagableRecords() throws IOException {
        // Both TLE lines are individually well-formed and the record passes isValid check,
        // but line1 and line2 name different satellites.
        GpRecord mismatched = buildGp(99999, "MISMATCHED",
                LocalDateTime.of(2009, 2, 9, 18, 49, 39),
                "1 24946U 97051C   09040.78448243 +.00000153 +00000-0 +47668-4 0  9994",
                "2 22675 074.0355 019.4646 0016027 098.7014 261.5952 14.31135643817415");
        when(spaceTrackClient.fetchCatalog()).thenReturn(List.of(iridium33(), mismatched));

        ingestionService.sync();

        assertThat(satelliteService.count()).as("unpropagable record rejected at ingest").isEqualTo(1);
        SyncResult log = ingestionLogService.getRecent(1).getFirst();
        assertThat(log.objectsSkipped()).as("mismatched TLE counted as skipped").isEqualTo(1);
        assertThat(log.objectsInserted()).as("valid satellite still ingested").isEqualTo(1);
    }

    @Test
    void syncFailureIsLoggedAsUnsuccessful() throws IOException {
        when(spaceTrackClient.fetchCatalog()).thenThrow(new IOException("Space-Track unreachable"));
        long before = satelliteService.count();

        assertThatThrownBy(() -> ingestionService.sync())
                .as("failed sync must propagate, not swallow")
                .isInstanceOf(IllegalStateException.class);

        SyncResult log = ingestionLogService.getRecent(1).getFirst();
        assertThat(log.successful()).as("failed sync flagged unsuccessful").isFalse();
        assertThat(log.objectsInserted()).isZero();
        assertThat(satelliteService.count()).as("failed sync must not mutate catalog").isEqualTo(before);
    }

    @Test
    void syncFailureFromRestClientExceptionIsLoggedAndPropagated() throws IOException {
        // The real-world failure class (4xx/5xx/network) is unchecked RestClientException,
        // which the old IOException-only catch missed entirely.
        when(spaceTrackClient.fetchCatalog())
                .thenThrow(new ResourceAccessException("Space-Track connection refused"));
        long before = satelliteService.count();

        assertThatThrownBy(() -> ingestionService.sync())
                .isInstanceOf(IllegalStateException.class);

        SyncResult log = ingestionLogService.getRecent(1).getFirst();
        assertThat(log.successful()).as("RestClient failure flagged unsuccessful").isFalse();
        assertThat(satelliteService.count()).as("failed sync must not mutate catalog").isEqualTo(before);
    }
}
