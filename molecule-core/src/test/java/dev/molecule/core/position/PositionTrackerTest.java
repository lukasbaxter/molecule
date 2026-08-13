package dev.molecule.core.position;

import static org.assertj.core.api.Assertions.assertThat;

import dev.molecule.api.position.PositionSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class PositionTrackerTest {

    private static final UUID WORLD = UUID.randomUUID();

    @Test
    void returnsEmptyForAnUnknownPlayer() {
        PositionTracker tracker = new PositionTracker();

        assertThat(tracker.snapshot(UUID.randomUUID())).isEmpty();
        assertThat(tracker.isTracked(UUID.randomUUID())).isFalse();
    }

    @Test
    void publishesTheLatestPosition() {
        PositionTracker tracker = new PositionTracker();
        UUID player = UUID.randomUUID();

        tracker.update(player, WORLD, 1, 2, 3, 0f, 0f);
        tracker.update(player, WORLD, 10, 20, 30, 90f, 45f);

        PositionSnapshot snapshot = tracker.snapshot(player).orElseThrow();
        assertThat(snapshot.x()).isEqualTo(10);
        assertThat(snapshot.y()).isEqualTo(20);
        assertThat(snapshot.z()).isEqualTo(30);
        assertThat(snapshot.yaw()).isEqualTo(90f);
        assertThat(tracker.trackedCount()).isEqualTo(1);
    }

    @Test
    void forgettingAPlayerRemovesThemFromDistanceChecks() {
        PositionTracker tracker = new PositionTracker();
        UUID player = UUID.randomUUID();
        tracker.update(player, WORLD, 0, 0, 0, 0f, 0f);

        tracker.forget(player);

        // A stale entry would keep an offline player visible to visibility sweeps.
        assertThat(tracker.snapshot(player)).isEmpty();
        assertThat(tracker.trackedCount()).isZero();
    }

    /**
     * The tracker's whole purpose is to be read from threads that do not own the player,
     * while the owning thread writes. A reader must never see a partially written
     * position — snapshots are immutable and published through a concurrent map, so a
     * reader sees either the old value or the new one, never a mix.
     */
    @Test
    void readersNeverObserveATornPosition() throws InterruptedException {
        PositionTracker tracker = new PositionTracker();
        UUID player = UUID.randomUUID();
        tracker.update(player, WORLD, 0, 0, 0, 0f, 0f);

        int iterations = 20_000;
        AtomicBoolean tornRead = new AtomicBoolean(false);
        CountDownLatch startLine = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(2);
        ExecutorService threads = Executors.newFixedThreadPool(2);

        // Writer: only ever publishes coordinates where x == y == z.
        threads.execute(
                () -> {
                    try {
                        startLine.await();
                        for (int i = 1; i <= iterations; i++) {
                            tracker.update(player, WORLD, i, i, i, 0f, 0f);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        finished.countDown();
                    }
                });

        // Reader: any snapshot whose coordinates disagree was assembled from two writes.
        threads.execute(
                () -> {
                    try {
                        startLine.await();
                        for (int i = 0; i < iterations; i++) {
                            tracker.snapshot(player)
                                    .ifPresent(
                                            snapshot -> {
                                                if (snapshot.x() != snapshot.y()
                                                        || snapshot.y() != snapshot.z()) {
                                                    tornRead.set(true);
                                                }
                                            });
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        finished.countDown();
                    }
                });

        startLine.countDown();
        assertThat(finished.await(30, TimeUnit.SECONDS)).isTrue();
        threads.shutdownNow();

        assertThat(tornRead).isFalse();
    }

    @Test
    void survivesConcurrentUpdatesForManyPlayers() throws InterruptedException {
        PositionTracker tracker = new PositionTracker();
        int players = 200;
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < players; i++) {
            ids.add(UUID.randomUUID());
        }

        // Each player is written by its own thread, mirroring Folia: one owner per
        // player, many owners at once.
        ExecutorService threads = Executors.newFixedThreadPool(8);
        CountDownLatch finished = new CountDownLatch(players);
        for (UUID id : ids) {
            threads.execute(
                    () -> {
                        try {
                            for (int i = 0; i < 500; i++) {
                                tracker.update(id, WORLD, i, i, i, 0f, 0f);
                            }
                        } finally {
                            finished.countDown();
                        }
                    });
        }

        assertThat(finished.await(30, TimeUnit.SECONDS)).isTrue();
        threads.shutdownNow();

        assertThat(tracker.trackedCount()).isEqualTo(players);
        for (UUID id : ids) {
            assertThat(tracker.snapshot(id).orElseThrow().x()).isEqualTo(499);
        }
    }
}
