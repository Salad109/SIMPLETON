package io.salad109.conjunctiondetector.conjunction.internal;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpatialGridTest {

    @Test
    void sameCellSatellitesFormPair() {
        float[][] x = {{100.0f}, {105.0f}};
        float[][] y = {{200.0f}, {205.0f}};
        float[][] z = {{300.0f}, {305.0f}};

        SpatialGrid grid = new SpatialGrid(10.0, x, y, z, 0);

        assertThat(collectPairs(grid)).hasSize(1);
    }

    @Test
    void adjacentCellSatellitesFormPair() {
        float[][] x = {{9.9f}, {10.1f}};
        float[][] y = {{5.0f}, {5.0f}};
        float[][] z = {{5.0f}, {5.0f}};

        SpatialGrid grid = new SpatialGrid(10.0, x, y, z, 0);

        assertThat(collectPairs(grid)).hasSize(1);
    }

    @Test
    void nanSatellitesAreSkipped() {
        float[][] x = {{Float.NaN}, {100.0f}};
        float[][] y = {{Float.NaN}, {200.0f}};
        float[][] z = {{Float.NaN}, {300.0f}};

        SpatialGrid grid = new SpatialGrid(10.0, x, y, z, 0);

        assertThat(collectPairs(grid)).isEmpty();
    }

    @Test
    void noDuplicatePairs() {
        // Triangle across cell boundaries
        float[][] x = {{5.0f}, {15.0f}, {15.0f}};
        float[][] y = {{5.0f}, {5.0f}, {15.0f}};
        float[][] z = {{5.0f}, {5.0f}, {5.0f}};

        SpatialGrid grid = new SpatialGrid(10.0, x, y, z, 0);
        List<int[]> pairs = collectPairs(grid);

        assertThat(pairs)
                .extracting(p -> List.of(Math.min(p[0], p[1]), Math.max(p[0], p[1])))
                .doesNotHaveDuplicates();
    }

    @Test
    void negativeCoordinatesHashCorrectly() {
        float[][] x = {{-5.0f}, {-4.0f}};
        float[][] y = {{-5.0f}, {-4.0f}};
        float[][] z = {{-5.0f}, {-4.0f}};

        SpatialGrid grid = new SpatialGrid(10.0, x, y, z, 0);

        assertThat(collectPairs(grid)).hasSize(1);
    }

    @Test
    void negativeCoordinatesCrossZeroBoundary() {
        // floor(-0.1/10)=-1, floor(0.1/10)=0
        float[][] x = {{-0.1f}, {0.1f}};
        float[][] y = {{5.0f}, {5.0f}};
        float[][] z = {{5.0f}, {5.0f}};

        SpatialGrid grid = new SpatialGrid(10.0, x, y, z, 0);

        assertThat(collectPairs(grid)).hasSize(1);
    }

    @Test
    void diagonalNeighborsFormPair() {
        float[][] x = {{5.0f}, {15.0f}};
        float[][] y = {{5.0f}, {15.0f}};
        float[][] z = {{5.0f}, {15.0f}};

        SpatialGrid grid = new SpatialGrid(10.0, x, y, z, 0);

        assertThat(collectPairs(grid)).hasSize(1);
    }

    @Test
    void distantSatellitesDoNotFormPair() {
        float[][] x = {{5.0f}, {25.0f}};
        float[][] y = {{5.0f}, {5.0f}};
        float[][] z = {{5.0f}, {5.0f}};

        SpatialGrid grid = new SpatialGrid(10.0, x, y, z, 0);

        assertThat(collectPairs(grid)).isEmpty();
    }

    @Test
    void cellIndexWrapsAt10Bits() {
        // Cell 1024 masks to 0 via & 0x3FF
        // false positive 10240 km apart but harmless
        float[][] x = {{5.0f}, {10245.0f}};
        float[][] y = {{5.0f}, {5.0f}};
        float[][] z = {{5.0f}, {5.0f}};

        SpatialGrid grid = new SpatialGrid(10.0, x, y, z, 0);

        assertThat(collectPairs(grid)).hasSize(1);
    }

    private List<int[]> collectPairs(SpatialGrid grid) {
        List<int[]> pairs = new ArrayList<>();
        grid.forEachCandidatePair((a, b) -> pairs.add(new int[]{a, b}));
        return pairs;
    }
}
