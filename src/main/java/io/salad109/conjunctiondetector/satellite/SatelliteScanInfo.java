package io.salad109.conjunctiondetector.satellite;

import java.time.OffsetDateTime;

public record SatelliteScanInfo(int noradCatId, String tleLine1, String tleLine2, OffsetDateTime epoch,
                                String objectType) {
}
