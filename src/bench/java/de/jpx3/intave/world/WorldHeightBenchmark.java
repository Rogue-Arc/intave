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

import java.lang.reflect.Proxy;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;

public final class WorldHeightBenchmark {
  private static final int WARMUP_OPERATIONS = 10_000_000;
  private static final int MEASURED_OPERATIONS = 50_000_000;
  private static volatile long sink;

  public static void main(String[] args) throws Exception {
    int measuredOperations = args.length == 0
      ? MEASURED_OPERATIONS
      : Integer.parseInt(args[0]);
    MinecraftVersion.setCurrent(MinecraftVersions.VER26_3);
    World singleWorld = worldWithBounds(-3000, 3001);
    World[] manyWorlds = new World[64];
    for (int index = 0; index < manyWorlds.length; index++) {
      manyWorlds[index] = worldWithBounds(-3000 + index, 3001 + index);
    }

    runSingleWorld(singleWorld, WARMUP_OPERATIONS);
    print("single-world", measuredOperations, runSingleWorld(singleWorld, measuredOperations));
    runManyWorlds(manyWorlds, WARMUP_OPERATIONS);
    print(
      "64-world round-robin", measuredOperations,
      runManyWorlds(manyWorlds, measuredOperations)
    );
    runConcurrent(singleWorld, measuredOperations);
  }

  private static long runSingleWorld(World world, int operations) {
    long hits = 0;
    long started = System.nanoTime();
    for (int index = 0; index < operations; index++) {
      hits += WorldHeight.contains(world, (index & 8191) - 4096) ? 1 : 0;
    }
    sink = hits;
    return System.nanoTime() - started;
  }

  private static long runManyWorlds(World[] worlds, int operations) {
    long hits = 0;
    long started = System.nanoTime();
    for (int index = 0; index < operations; index++) {
      World world = worlds[index & (worlds.length - 1)];
      hits += WorldHeight.contains(world, (index & 8191) - 4096) ? 1 : 0;
    }
    sink = hits;
    return System.nanoTime() - started;
  }

  private static void runConcurrent(World world, int operations) throws InterruptedException {
    int threads = Math.min(16, Math.max(2, Runtime.getRuntime().availableProcessors()));
    int operationsPerThread = operations / threads;
    CountDownLatch ready = new CountDownLatch(threads);
    CountDownLatch start = new CountDownLatch(1);
    Thread[] workers = new Thread[threads];
    long[] hits = new long[threads];
    for (int thread = 0; thread < threads; thread++) {
      int workerIndex = thread;
      workers[thread] = new Thread(() -> {
        ready.countDown();
        try {
          start.await();
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
          return;
        }
        long count = 0;
        for (int index = 0; index < operationsPerThread; index++) {
          count += WorldHeight.contains(world, (index & 8191) - 4096) ? 1 : 0;
        }
        hits[workerIndex] = count;
      }, "world-height-benchmark-" + thread);
      workers[thread].start();
    }
    ready.await();
    long started = System.nanoTime();
    start.countDown();
    for (Thread worker : workers) {
      worker.join();
    }
    long elapsed = System.nanoTime() - started;
    long totalHits = 0;
    for (long count : hits) {
      totalHits += count;
    }
    sink = totalHits;
    print("single-world concurrent x" + threads, (long) operationsPerThread * threads, elapsed);
  }

  private static void print(String scenario, long operations, long elapsedNanos) {
    double nanosPerOperation = elapsedNanos / (double) operations;
    double operationsPerSecond = operations * 1_000_000_000.0 / elapsedNanos;
    System.out.printf(
      Locale.ROOT, "%s: %.2f ns/op, %.0f ops/s%n",
      scenario, nanosPerOperation, operationsPerSecond
    );
  }

  private static World worldWithBounds(int minimum, int maximumExclusive) {
    return (World) Proxy.newProxyInstance(
      World.class.getClassLoader(),
      new Class<?>[]{World.class},
      (proxy, method, arguments) -> {
        switch (method.getName()) {
          case "getMinHeight":
            return minimum;
          case "getMaxHeight":
            return maximumExclusive;
          case "hashCode":
            return System.identityHashCode(proxy);
          case "equals":
            return proxy == arguments[0];
          case "toString":
            return "World[" + minimum + ", " + maximumExclusive + ")";
          default:
            throw new UnsupportedOperationException(method.toString());
        }
      }
    );
  }
}
