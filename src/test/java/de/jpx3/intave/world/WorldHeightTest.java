/*
 * Copyright 2026 Intave
 *
 * This software is licensed under the PolyForm Perimeter License 1.0.0.
 * You may use this software for any purpose, except for providing to
 * others any product that competes with the software.
 *
 * A copy of the license is available at:
 *   https://polyformproject.org/licenses/perimeter/1.0.0/
 */

package de.jpx3.intave.world;

import de.jpx3.intave.adapter.MinecraftVersion;
import de.jpx3.intave.adapter.MinecraftVersions;
import org.bukkit.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldHeightTest {

  @BeforeEach
  void setupVersion() {
    MinecraftVersion.setCurrent(MinecraftVersions.VER26_3);
  }

  @Test
  void readsBoundsFromEachWorld() {
    World vanillaWorld = worldWithBounds(-64, 320);
    World extendedWorld = worldWithBounds(-3000, 3001);

    assertEquals(-64, WorldHeight.minimum(vanillaWorld));
    assertEquals(320, WorldHeight.maximumExclusive(vanillaWorld));
    assertEquals(-3000, WorldHeight.minimum(extendedWorld));
    assertEquals(3001, WorldHeight.maximumExclusive(extendedWorld));
  }

  @Test
  void containsUsesInclusiveMinimumAndExclusiveMaximum() {
    World world = worldWithBounds(-3000, 3001);

    assertFalse(WorldHeight.contains(world, -3001));
    assertTrue(WorldHeight.contains(world, -3000));
    assertTrue(WorldHeight.contains(world, 3000));
    assertFalse(WorldHeight.contains(world, 3001));
  }

  @Test
  void cachesBoundsPerWorld() {
    AtomicInteger minimumReads = new AtomicInteger();
    AtomicInteger maximumReads = new AtomicInteger();
    World world = worldWithBounds(-3000, 3001, minimumReads, maximumReads);

    assertEquals(-3000, WorldHeight.minimum(world));
    assertEquals(3001, WorldHeight.maximumExclusive(world));
    assertTrue(WorldHeight.contains(world, 0));
    assertEquals(-3000, WorldHeight.minimum(world));
    assertEquals(3001, WorldHeight.maximumExclusive(world));

    assertEquals(1, minimumReads.get());
    assertEquals(1, maximumReads.get());
  }

  @Test
  void cleanupInvalidatesCachedBounds() {
    AtomicInteger minimumReads = new AtomicInteger();
    AtomicInteger maximumReads = new AtomicInteger();
    World world = worldWithBounds(-3000, 3001, minimumReads, maximumReads);

    assertTrue(WorldHeight.contains(world, 0));
    WorldHeight.invalidate(world);
    assertTrue(WorldHeight.contains(world, 0));

    assertEquals(2, minimumReads.get());
    assertEquals(2, maximumReads.get());
  }

  @Test
  void concurrentHammerResolvesBoundsOnlyOnce() throws Exception {
    AtomicInteger minimumReads = new AtomicInteger();
    AtomicInteger maximumReads = new AtomicInteger();
    World world = worldWithBounds(-3000, 3001, minimumReads, maximumReads);
    int threadCount = 16;
    int lookupsPerThread = 250_000;
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    List<Future<Integer>> results = new ArrayList<>(threadCount);
    try {
      for (int thread = 0; thread < threadCount; thread++) {
        results.add(executor.submit(() -> {
          assertTrue(start.await(5, TimeUnit.SECONDS));
          int hits = 0;
          for (int index = 0; index < lookupsPerThread; index++) {
            int y = index % 6003 - 3001;
            if (WorldHeight.contains(world, y)) {
              hits++;
            }
            if (WorldHeight.minimum(world) != -3000
              || WorldHeight.maximumExclusive(world) != 3001) {
              throw new AssertionError("Observed incorrect cached bounds");
            }
          }
          return hits;
        }));
      }
      start.countDown();
      for (Future<Integer> result : results) {
        assertTrue(result.get(20, TimeUnit.SECONDS) > 0);
      }
    } finally {
      start.countDown();
      executor.shutdownNow();
    }

    assertEquals(1, minimumReads.get());
    assertEquals(1, maximumReads.get());
  }

  @Test
  void manyWorldsKeepIndependentBounds() {
    World[] worlds = new World[256];
    for (int index = 0; index < worlds.length; index++) {
      int minimum = -3000 + index;
      worlds[index] = worldWithBounds(minimum, minimum + 6001);
    }

    for (int pass = 0; pass < 10_000; pass++) {
      int index = pass & 255;
      int minimum = -3000 + index;
      World world = worlds[index];
      assertEquals(minimum, WorldHeight.minimum(world));
      assertEquals(minimum + 6001, WorldHeight.maximumExclusive(world));
      assertTrue(WorldHeight.contains(world, minimum));
      assertFalse(WorldHeight.contains(world, minimum + 6001));
    }
  }

  @Test
  void readersRemainCorrectWhileOtherWorldsChurn() throws Exception {
    AtomicInteger minimumReads = new AtomicInteger();
    AtomicInteger maximumReads = new AtomicInteger();
    World stableWorld = worldWithBounds(-3000, 3001, minimumReads, maximumReads);
    assertTrue(WorldHeight.contains(stableWorld, 0));

    int readerCount = 8;
    int readsPerReader = 250_000;
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(readerCount + 1);
    List<Future<?>> tasks = new ArrayList<>(readerCount + 1);
    try {
      for (int reader = 0; reader < readerCount; reader++) {
        tasks.add(executor.submit(() -> {
          assertTrue(start.await(5, TimeUnit.SECONDS));
          for (int index = 0; index < readsPerReader; index++) {
            if (!WorldHeight.contains(stableWorld, index % 6001 - 3000)) {
              throw new AssertionError("Stable world bounds disappeared during cache churn");
            }
          }
          return null;
        }));
      }
      tasks.add(executor.submit(() -> {
        assertTrue(start.await(5, TimeUnit.SECONDS));
        for (int index = 0; index < 1_000; index++) {
          World transientWorld = worldWithBounds(-index, index + 1);
          if (!WorldHeight.contains(transientWorld, 0)) {
            throw new AssertionError("Transient world resolved incorrect bounds");
          }
          WorldHeight.invalidate(transientWorld);
        }
        return null;
      }));

      start.countDown();
      for (Future<?> task : tasks) {
        task.get(20, TimeUnit.SECONDS);
      }
    } finally {
      start.countDown();
      executor.shutdownNow();
    }

    assertEquals(1, minimumReads.get());
    assertEquals(1, maximumReads.get());
  }

  private static World worldWithBounds(int minimum, int maximumExclusive) {
    return worldWithBounds(
      minimum, maximumExclusive, new AtomicInteger(), new AtomicInteger()
    );
  }

  private static World worldWithBounds(
    int minimum,
    int maximumExclusive,
    AtomicInteger minimumReads,
    AtomicInteger maximumReads
  ) {
    return (World) Proxy.newProxyInstance(
      World.class.getClassLoader(),
      new Class<?>[]{World.class},
      (proxy, method, arguments) -> {
        switch (method.getName()) {
          case "getMinHeight":
            minimumReads.incrementAndGet();
            return minimum;
          case "getMaxHeight":
            maximumReads.incrementAndGet();
            return maximumExclusive;
          case "toString":
            return "World[" + minimum + ", " + maximumExclusive + ")";
          case "hashCode":
            return System.identityHashCode(proxy);
          case "equals":
            return proxy == arguments[0];
          default:
            throw new UnsupportedOperationException(method.toString());
        }
      }
    );
  }
}
