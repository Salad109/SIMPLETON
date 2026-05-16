package io.salad109.conjunctiondetector.conjunction;

import java.time.OffsetDateTime;

public record ConjunctionInfo(
        long id,
        double missDistanceKm,
        OffsetDateTime tca,
        double relativeVelocityMS,
        double collisionProbability,
        int object1NoradId,
        String object1Name,
        String object1Type,
        int object2NoradId,
        String object2Name,
        String object2Type
) {
}
