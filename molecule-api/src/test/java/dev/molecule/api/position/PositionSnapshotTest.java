package dev.molecule.api.position;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class PositionSnapshotTest {

    private static final UUID OVERWORLD = UUID.randomUUID();
    private static final UUID NETHER = UUID.randomUUID();

    private static PositionSnapshot at(UUID world, double x, double y, double z) {
        return new PositionSnapshot(UUID.randomUUID(), world, x, y, z, 0f, 0f, System.nanoTime());
    }

    @Test
    void measuresDistanceWithinAWorld() {
        PositionSnapshot origin = at(OVERWORLD, 0, 0, 0);
        PositionSnapshot other = at(OVERWORLD, 3, 0, 4);

        assertThat(origin.distanceSquared(other)).isEqualTo(25.0);
    }

    @Test
    void treatsCrossWorldDistanceAsInfinite() {
        PositionSnapshot overworld = at(OVERWORLD, 0, 0, 0);
        PositionSnapshot nether = at(NETHER, 0, 0, 0);

        // Visibility checks compare against a radius, so infinity is what makes
        // "never visible across worlds" fall out of the same comparison.
        assertThat(overworld.distanceSquared(nether)).isInfinite();
        assertThat(overworld.sameWorld(nether)).isFalse();
    }

    @Test
    void reportsAgeSoCallersCanRejectStaleData() {
        PositionSnapshot stale =
                new PositionSnapshot(
                        UUID.randomUUID(),
                        OVERWORLD,
                        0,
                        0,
                        0,
                        0f,
                        0f,
                        System.nanoTime() - 250_000_000L);

        assertThat(stale.ageMillis()).isGreaterThanOrEqualTo(250L);
    }
}
