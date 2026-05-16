package io.salad109.conjunctiondetector.conjunction;

import java.time.OffsetDateTime;

public record VisualizationData(
        long id,
        double missDistanceKm,
        OffsetDateTime tca,
        double relativeVelocityMS,
        double collisionProbability,
        int object1NoradId,
        String object1Name,
        String object1Type,
        String object1Tle1,
        String object1Tle2,
        int object2NoradId,
        String object2Name,
        String object2Type,
        String object2Tle1,
        String object2Tle2
) {
}
