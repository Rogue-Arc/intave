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

package de.jpx3.intave.check.movement.physics.branch;

import de.jpx3.intave.check.movement.physics.environment.MockSimulationEnvironment;
import de.jpx3.intave.check.movement.physics.environment.SimulationEnvironment;
import de.jpx3.intave.check.movement.physics.update.CausalConstraint;
import de.jpx3.intave.check.movement.physics.update.TickAmbiguousUpdate;
import de.jpx3.intave.search.Searcher;
import de.jpx3.intave.share.Motion;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class UpdateBrancherBruteForceTest {
  private static final long OPEN = Long.MAX_VALUE;
  private static final long CURRENT_TICK = 10;

  @Test
  void exhaustivelyMatchesSubsetOracleForFourUpdates() {
    UpdateMode[] modes = UpdateMode.values();
    int modeConfigurations = power(modes.length, 4);
    int compatibilityGraphs = 1 << 6;
    int scenarios = 0;

    for (int encodedModes = 0; encodedModes < modeConfigurations; encodedModes++) {
      UpdateMode[] selectedModes = decodeModes(encodedModes, modes, 4);
      for (int graph = 0; graph < compatibilityGraphs; graph++) {
        List<TestUpdate> updates = createUpdates(selectedModes, graph, CURRENT_TICK);
        Collections.rotate(updates, scenarios & 3);
        assertMatchesOracle(updates, CURRENT_TICK, 0);
        scenarios++;
      }
    }

    assertEquals(82_944, scenarios);
  }

  @Test
  void seededRandomizedConfigurationsMatchSubsetOracle() {
    Random random = new Random(0x6A09E667F3BCC909L);
    int scenarios = 10_000;

    for (int scenario = 0; scenario < scenarios; scenario++) {
      int updateCount = random.nextInt(8);
      long currentTick = random.nextInt(7);
      long activeSequence = random.nextInt(updateCount + 1);
      long[] incompatibleMasks = new long[updateCount];

      for (int first = 0; first < updateCount; first++) {
        for (int second = 0; second < updateCount; second++) {
          if (first != second && random.nextInt(5) == 0) {
            incompatibleMasks[first] |= 1L << second;
          }
        }
      }

      List<TestUpdate> updates = new ArrayList<>();
      for (int index = 0; index < updateCount; index++) {
        boolean skippable = random.nextBoolean();
        boolean mustRunBeforeExplicitTick = !skippable && random.nextBoolean();
        boolean canBePostponed = random.nextBoolean();
        long notBefore = currentTick + random.nextInt(3) - 1;
        long notAfter;
        switch (random.nextInt(4)) {
          case 0:
            notAfter = OPEN;
            break;
          case 1:
            notAfter = currentTick - 1;
            break;
          case 2:
            notAfter = currentTick;
            break;
          default:
            notAfter = currentTick + 1;
            break;
        }
        updates.add(new TestUpdate(
          index, index + 1L, notBefore, notAfter,
          canBePostponed, mustRunBeforeExplicitTick, skippable,
          incompatibleMasks[index]
        ));
      }
      Collections.shuffle(updates, random);

      assertMatchesOracle(updates, currentTick, activeSequence);
    }
  }

  @Test
  void everyInputPermutationProducesTheSameBranches() {
    List<TestUpdate> updates = new ArrayList<>();
    updates.add(update(0, 1, UpdateMode.SKIPPABLE, 1L << 3));
    updates.add(update(1, 2, UpdateMode.PLAIN, 0));
    updates.add(update(2, 3, UpdateMode.SKIPPABLE_FORCED, 1L << 4));
    updates.add(update(3, 4, UpdateMode.EXPLICIT, 1L << 0));
    updates.add(update(4, 5, UpdateMode.DEADLINE, 0));

    Set<Signature> expected = expectedSignatures(updates, environment(updates, CURRENT_TICK, 0));
    List<List<TestUpdate>> permutations = new ArrayList<>();
    permute(new ArrayList<>(updates), 0, permutations);

    assertEquals(120, permutations.size());
    for (List<TestUpdate> permutation : permutations) {
      assertMatchesOracle(permutation, CURRENT_TICK, 0);
      assertEquals(expected, actualSignatures(permutation, CURRENT_TICK, 0));
    }
  }

  @Test
  void exploresEverySubsetOfCompatibleSkippableUpdates() {
    List<TestUpdate> updates = new ArrayList<>();
    for (int index = 0; index < 12; index++) {
      updates.add(update(index, index + 1L, UpdateMode.SKIPPABLE, 0));
    }

    Set<Signature> actual = actualSignatures(updates, CURRENT_TICK, 0);

    assertEquals(1 << 12, actual.size());
    for (long effectMask = 0; effectMask < 1L << 12; effectMask++) {
      long sequence = effectMask == 0
        ? 0
        : 64 - Long.numberOfLeadingZeros(effectMask);
      assertTrue(actual.contains(new Signature(effectMask, sequence, true)));
    }
  }

  @Test
  void mutuallyIncompatibleSkippableUpdatesProduceOnlyEmptyAndSingletonBranches() {
    int updateCount = 64;
    long allUpdates = -1L;
    List<TestUpdate> updates = new ArrayList<>();
    for (int index = 0; index < updateCount; index++) {
      long incompatible = allUpdates & ~(1L << index);
      updates.add(update(index, index + 1L, UpdateMode.SKIPPABLE, incompatible));
    }

    Set<Signature> actual = actualSignatures(updates, CURRENT_TICK, 0);

    assertEquals(updateCount + 1, actual.size());
    assertTrue(actual.contains(new Signature(0, 0, true)));
    for (int index = 0; index < updateCount; index++) {
      assertTrue(actual.contains(new Signature(1L << index, index + 1L, true)));
    }
  }

  @Test
  void handlesADeepIncompatibleSkippableChain() {
    int updateCount = 2_048;
    List<TestUpdate> updates = new ArrayList<>();
    for (int index = 0; index < updateCount; index++) {
      updates.add(update(index & 63, index + 1L, UpdateMode.SKIPPABLE, -1L));
    }

    MockSimulationEnvironment environment = environment(updates, CURRENT_TICK, 0);
    MovementSearchInput input = MovementSearchInput.forTick(null, null, environment, false);
    Collection<MovementSearchBranch> branches = new ArrayList<>();

    new UpdateBrancher().branch(input, MovementSearchBranch.blank(input), branches);

    assertFalse(branches.isEmpty());
    assertEquals(updateCount + 1, branches.size());
    assertEquals(0.0D, environment.baseMotionX());
    assertEquals(0, environment.activeSequence());
  }

  @Test
  void nonSkippableUpdateCannotBeBypassed() {
    List<TestUpdate> updates = new ArrayList<>();
    updates.add(update(0, 1, UpdateMode.PLAIN, 0));
    updates.add(update(1, 2, UpdateMode.SKIPPABLE, 0));
    updates.add(update(2, 3, UpdateMode.EXPLICIT, 0));

    Set<Signature> actual = actualSignatures(updates, CURRENT_TICK, 0);

    assertFalse(actual.contains(new Signature(1L << 2, 3, true)));
    assertFalse(actual.contains(new Signature((1L << 1) | (1L << 2), 3, true)));
    assertTrue(actual.contains(new Signature((1L << 0) | (1L << 2), 3, true)));
    assertTrue(actual.contains(new Signature(
      (1L << 0) | (1L << 1) | (1L << 2), 3, true
    )));
  }

  @Test
  void asymmetricCompatibilityIsRejectedInBothDirections() {
    List<TestUpdate> updates = new ArrayList<>();
    updates.add(update(0, 1, UpdateMode.SKIPPABLE, 1L << 1));
    updates.add(update(1, 2, UpdateMode.SKIPPABLE, 0));

    Set<Signature> actual = actualSignatures(updates, CURRENT_TICK, 0);

    assertEquals(3, actual.size());
    assertFalse(actual.contains(new Signature(3, 2, true)));
  }

  @Test
  void futureExpiredAndAlreadyActiveUpdatesAreIgnored() {
    List<TestUpdate> updates = new ArrayList<>();
    updates.add(new TestUpdate(0, 1, 0, OPEN, true, false, false, 0));
    updates.add(new TestUpdate(1, 2, CURRENT_TICK + 1, OPEN, true, false, false, 0));
    updates.add(new TestUpdate(2, 3, 0, CURRENT_TICK - 1, true, false, false, 0));
    updates.add(new TestUpdate(3, 4, 0, OPEN, true, false, false, 0));

    Set<Signature> actual = actualSignatures(updates, CURRENT_TICK, 1);

    assertEquals(Set.of(
      new Signature(0, 1, true),
      new Signature(1L << 3, 4, true)
    ), actual);
  }

  @Test
  void explicitFinishRequiresTheLatestDeadlineOrExplicitRequirement() {
    List<TestUpdate> updates = new ArrayList<>();
    updates.add(update(0, 1, UpdateMode.DEADLINE, 0));
    updates.add(update(1, 2, UpdateMode.PLAIN, 0));
    updates.add(update(2, 3, UpdateMode.EXPLICIT, 0));
    updates.add(update(3, 4, UpdateMode.PLAIN, 0));

    Set<Signature> actual = actualSignatures(updates, CURRENT_TICK, 0);

    assertTrue(actual.stream().anyMatch(signature ->
      signature.sequence == 1 && !signature.canFinishExplicitTick
    ));
    assertTrue(actual.stream().anyMatch(signature ->
      signature.sequence == 2 && !signature.canFinishExplicitTick
    ));
    assertTrue(actual.stream().anyMatch(signature ->
      signature.sequence == 3 && signature.canFinishExplicitTick
    ));
  }

  @Test
  void inputBranchExplicitRestrictionIsPreserved() {
    List<TestUpdate> updates = new ArrayList<>();
    updates.add(update(0, 1, UpdateMode.PLAIN, 0));
    MockSimulationEnvironment environment = environment(updates, CURRENT_TICK, 0);
    MovementSearchInput input = MovementSearchInput.forTick(null, null, environment, false);
    MovementSearchBranch inputBranch = MovementSearchBranch.blank(input)
      .setToOnlyUsableForImplicitFlyingPackets();
    Collection<MovementSearchBranch> branches = new ArrayList<>();

    new UpdateBrancher().branch(input, inputBranch, branches);

    assertFalse(branches.isEmpty());
    assertTrue(branches.stream().noneMatch(MovementSearchBranch::canFinishExplicitTick));
  }

  @Test
  void updateModifiersRunAfterExistingBranchModifiers() {
    List<TestUpdate> updates = Collections.singletonList(
      update(0, 1, UpdateMode.PLAIN, 0)
    );
    MockSimulationEnvironment environment = environment(updates, CURRENT_TICK, 0);
    MovementSearchInput input = MovementSearchInput.forTick(null, null, environment, false);
    MovementSearchBranch inputBranch = MovementSearchBranch.blank(input)
      .withPreviousPostTickCandidate(Motion.of(100.0D, 0.0D, 0.0D), false);
    Collection<MovementSearchBranch> branches = new ArrayList<>();

    new UpdateBrancher().branch(input, inputBranch, branches);

    Set<Double> resultingMotion = new HashSet<>();
    for (MovementSearchBranch branch : branches) {
      resultingMotion.add(branch.modifiedMutableView(environment).baseMotionX());
    }
    assertEquals(Set.of(100.0D, 101.0D), resultingMotion);
  }

  @Test
  void applyingBranchesNeverMutatesTheSourceEnvironment() {
    List<TestUpdate> updates = new ArrayList<>();
    for (int index = 0; index < 8; index++) {
      updates.add(update(index, index + 1L, UpdateMode.SKIPPABLE, 0));
    }
    MockSimulationEnvironment environment = environment(updates, CURRENT_TICK, 0);
    MovementSearchInput input = MovementSearchInput.forTick(null, null, environment, false);
    Collection<MovementSearchBranch> branches = new ArrayList<>();
    new UpdateBrancher().branch(input, MovementSearchBranch.blank(input), branches);

    for (MovementSearchBranch branch : branches) {
      SimulationEnvironment modified = branch.modifiedMutableView(environment);
      assertNotEquals(environment, modified);
    }

    assertEquals(0.0D, environment.baseMotionX());
    assertEquals(0, environment.activeSequence());
    assertEquals(CURRENT_TICK, environment.currentTick());
    assertEquals(updates, environment.allTickAmbiguousUpdates());
  }

  @Test
  void repeatedSearchesHaveIdenticalResultsAndNoCachedStateLeakage() {
    List<TestUpdate> updates = new ArrayList<>();
    updates.add(update(0, 1, UpdateMode.SKIPPABLE, 1L << 2));
    updates.add(update(1, 2, UpdateMode.PLAIN, 0));
    updates.add(update(2, 3, UpdateMode.SKIPPABLE, 0));
    MockSimulationEnvironment environment = environment(updates, CURRENT_TICK, 0);
    Searcher<MovementSearchInput, MovementSearchBranch> searcher = new Searcher<>(
      Collections.singletonList(new UpdateBrancher()), MovementSearchBranch::blank
    );

    Set<Signature> first = signatures(
      searcher.searchConfigurationsFor(
        MovementSearchInput.forTick(null, null, environment, false)
      ),
      environment
    );
    Set<Signature> second = signatures(
      searcher.searchConfigurationsFor(
        MovementSearchInput.forTick(null, null, environment, false)
      ),
      environment
    );

    assertEquals(first, second);
    assertEquals(expectedSignatures(updates, environment), first);
  }

  private static void assertMatchesOracle(
    List<TestUpdate> updates, long currentTick, long activeSequence
  ) {
    MockSimulationEnvironment environment = environment(updates, currentTick, activeSequence);
    Set<Signature> expected = expectedSignatures(updates, environment);
    MovementSearchInput input = MovementSearchInput.forTick(null, null, environment, false);
    Collection<MovementSearchBranch> branches = new ArrayList<>();

    new UpdateBrancher().branch(input, MovementSearchBranch.blank(input), branches);

    Set<Signature> actual = signatures(branches, environment);
    assertEquals(actual.size(), branches.size(), () ->
      "Duplicate observable branches for " + describe(updates, currentTick, activeSequence)
    );
    assertEquals(expected, actual, () ->
      "Branch mismatch for " + describe(updates, currentTick, activeSequence)
    );
    assertEquals(0.0D, environment.baseMotionX());
    assertEquals(activeSequence, environment.activeSequence());
  }

  private static Set<Signature> actualSignatures(
    List<TestUpdate> updates, long currentTick, long activeSequence
  ) {
    MockSimulationEnvironment environment = environment(updates, currentTick, activeSequence);
    MovementSearchInput input = MovementSearchInput.forTick(null, null, environment, false);
    Collection<MovementSearchBranch> branches = new ArrayList<>();
    new UpdateBrancher().branch(input, MovementSearchBranch.blank(input), branches);
    return signatures(branches, environment);
  }

  private static Set<Signature> signatures(
    Collection<MovementSearchBranch> branches, SimulationEnvironment environment
  ) {
    Set<Signature> signatures = new HashSet<>();
    for (MovementSearchBranch branch : branches) {
      SimulationEnvironment modified = branch.modifiedMutableView(environment);
      signatures.add(new Signature(
        Math.round(modified.baseMotionX()),
        modified.activeSequence(),
        branch.canFinishExplicitTick()
      ));
    }
    return signatures;
  }

  private static Set<Signature> expectedSignatures(
    List<TestUpdate> allUpdates, SimulationEnvironment environment
  ) {
    List<TestUpdate> updates = new ArrayList<>();
    for (TestUpdate update : allUpdates) {
      if (update.possible(environment)) {
        updates.add(update);
      }
    }
    updates.sort(Comparator.comparingLong(update -> update.constraint().sequenceNumber()));

    long firstNonPostponable = Long.MAX_VALUE;
    long requiredForExplicitTick = Long.MIN_VALUE;
    for (TestUpdate update : updates) {
      CausalConstraint constraint = update.constraint();
      if (!update.canBePostponed(environment)) {
        firstNonPostponable = Math.min(
          firstNonPostponable, constraint.sequenceNumber()
        );
      }
      if (update.mustRunBeforeExplicitTick()
        || constraint.mustHappenThisTick(environment) && !constraint.isOpenBounded()) {
        requiredForExplicitTick = Math.max(
          requiredForExplicitTick, constraint.sequenceNumber()
        );
      }
    }

    Set<Signature> expected = new HashSet<>();
    int subsetCount = 1 << updates.size();
    for (int subset = 0; subset < subsetCount; subset++) {
      if (!isReachableSubset(updates, subset) || !isCompatibleSubset(updates, subset)) {
        continue;
      }

      long sequence = environment.activeSequence();
      long effectMask = 0;
      for (int index = 0; index < updates.size(); index++) {
        if ((subset & 1 << index) == 0) {
          continue;
        }
        TestUpdate update = updates.get(index);
        sequence = update.constraint().sequenceNumber();
        effectMask |= update.effectBit();
      }
      if (firstNonPostponable != Long.MAX_VALUE && sequence < firstNonPostponable) {
        continue;
      }
      expected.add(new Signature(
        effectMask, sequence, sequence >= requiredForExplicitTick
      ));
    }
    return expected;
  }

  private static boolean isReachableSubset(List<TestUpdate> updates, int subset) {
    for (int selected = 0; selected < updates.size(); selected++) {
      if ((subset & 1 << selected) == 0) {
        continue;
      }
      for (int earlier = 0; earlier < selected; earlier++) {
        if ((subset & 1 << earlier) == 0 && !updates.get(earlier).canBeSkipped()) {
          return false;
        }
      }
    }
    return true;
  }

  private static boolean isCompatibleSubset(List<TestUpdate> updates, int subset) {
    for (int first = 0; first < updates.size(); first++) {
      if ((subset & 1 << first) == 0) {
        continue;
      }
      for (int second = first + 1; second < updates.size(); second++) {
        if ((subset & 1 << second) == 0) {
          continue;
        }
        TestUpdate a = updates.get(first);
        TestUpdate b = updates.get(second);
        if (!a.canRunInSameTickWith(b) || !b.canRunInSameTickWith(a)) {
          return false;
        }
      }
    }
    return true;
  }

  private static MockSimulationEnvironment environment(
    List<TestUpdate> updates, long currentTick, long activeSequence
  ) {
    MockSimulationEnvironment environment = new MockSimulationEnvironment();
    environment.setCurrentTick(currentTick);
    environment.setActiveSequence(activeSequence);
    environment.setBaseMotion(0.0D, 0.0D, 0.0D);
    environment.allTickAmbiguousUpdates().addAll(updates);
    return environment;
  }

  private static List<TestUpdate> createUpdates(
    UpdateMode[] modes, int compatibilityGraph, long currentTick
  ) {
    long[] incompatibilities = new long[modes.length];
    int edge = 0;
    for (int first = 0; first < modes.length; first++) {
      for (int second = first + 1; second < modes.length; second++) {
        if ((compatibilityGraph & 1 << edge) != 0) {
          incompatibilities[first] |= 1L << second;
          incompatibilities[second] |= 1L << first;
        }
        edge++;
      }
    }

    List<TestUpdate> updates = new ArrayList<>();
    for (int index = 0; index < modes.length; index++) {
      UpdateMode mode = modes[index];
      updates.add(new TestUpdate(
        index, index + 1L, currentTick - 1,
        mode.closedDeadline ? currentTick : OPEN,
        mode.canBePostponed, mode.mustRunBeforeExplicitTick,
        mode.skippable, incompatibilities[index]
      ));
    }
    return updates;
  }

  private static TestUpdate update(
    int id, long sequence, UpdateMode mode, long incompatibleMask
  ) {
    return new TestUpdate(
      id, sequence, CURRENT_TICK - 1,
      mode.closedDeadline ? CURRENT_TICK : OPEN,
      mode.canBePostponed, mode.mustRunBeforeExplicitTick,
      mode.skippable, incompatibleMask
    );
  }

  private static UpdateMode[] decodeModes(
    int encoded, UpdateMode[] modes, int count
  ) {
    UpdateMode[] decoded = new UpdateMode[count];
    for (int index = 0; index < count; index++) {
      decoded[index] = modes[encoded % modes.length];
      encoded /= modes.length;
    }
    return decoded;
  }

  private static int power(int base, int exponent) {
    int value = 1;
    for (int index = 0; index < exponent; index++) {
      value *= base;
    }
    return value;
  }

  private static void permute(
    List<TestUpdate> updates, int index, List<List<TestUpdate>> output
  ) {
    if (index == updates.size()) {
      output.add(new ArrayList<>(updates));
      return;
    }
    for (int swap = index; swap < updates.size(); swap++) {
      Collections.swap(updates, index, swap);
      permute(updates, index + 1, output);
      Collections.swap(updates, index, swap);
    }
  }

  private static String describe(
    List<TestUpdate> updates, long currentTick, long activeSequence
  ) {
    return "tick=" + currentTick + ", active=" + activeSequence + ", updates=" + updates;
  }

  private enum UpdateMode {
    PLAIN(true, false, false, false),
    FORCED(false, false, false, false),
    EXPLICIT(true, true, false, false),
    DEADLINE(false, false, false, true),
    SKIPPABLE(true, false, true, false),
    SKIPPABLE_FORCED(false, false, true, false);

    private final boolean canBePostponed;
    private final boolean mustRunBeforeExplicitTick;
    private final boolean skippable;
    private final boolean closedDeadline;

    UpdateMode(
      boolean canBePostponed,
      boolean mustRunBeforeExplicitTick,
      boolean skippable,
      boolean closedDeadline
    ) {
      this.canBePostponed = canBePostponed;
      this.mustRunBeforeExplicitTick = mustRunBeforeExplicitTick;
      this.skippable = skippable;
      this.closedDeadline = closedDeadline;
    }
  }

  private static final class TestUpdate extends TickAmbiguousUpdate {
    private final int id;
    private final long effectBit;
    private final CausalConstraint constraint;
    private final boolean canBePostponed;
    private final boolean mustRunBeforeExplicitTick;
    private final boolean skippable;
    private final long incompatibleMask;

    private TestUpdate(
      int id,
      long sequence,
      long notBefore,
      long notAfter,
      boolean canBePostponed,
      boolean mustRunBeforeExplicitTick,
      boolean skippable,
      long incompatibleMask
    ) {
      this.id = id;
      this.effectBit = 1L << id;
      this.constraint = new CausalConstraint(notBefore, notAfter, sequence);
      this.canBePostponed = canBePostponed;
      this.mustRunBeforeExplicitTick = mustRunBeforeExplicitTick;
      this.skippable = skippable;
      this.incompatibleMask = incompatibleMask;
    }

    @Override
    public void applyTo(SimulationEnvironment environment) {
      Motion motion = environment.mutableBaseMotionCopy();
      motion.motionX += effectBit;
      environment.setBaseMotion(motion);
    }

    @Override
    public CausalConstraint constraint() {
      return constraint;
    }

    @Override
    public boolean canBePostponed(SimulationEnvironment environment) {
      return canBePostponed;
    }

    @Override
    public boolean mustRunBeforeExplicitTick() {
      return mustRunBeforeExplicitTick;
    }

    @Override
    public boolean canBeSkipped() {
      return skippable;
    }

    @Override
    public boolean canRunInSameTickWith(TickAmbiguousUpdate other) {
      if (!(other instanceof TestUpdate)) {
        return true;
      }
      TestUpdate testUpdate = (TestUpdate) other;
      return (incompatibleMask & 1L << testUpdate.id) == 0;
    }

    private long effectBit() {
      return effectBit;
    }

    @Override
    public String toString() {
      return "U" + id + "{" + constraint
        + ", postpone=" + canBePostponed
        + ", explicit=" + mustRunBeforeExplicitTick
        + ", skip=" + skippable
        + ", incompatible=0x" + Long.toHexString(incompatibleMask) + '}';
    }
  }

  private static final class Signature {
    private final long effectMask;
    private final long sequence;
    private final boolean canFinishExplicitTick;

    private Signature(long effectMask, long sequence, boolean canFinishExplicitTick) {
      this.effectMask = effectMask;
      this.sequence = sequence;
      this.canFinishExplicitTick = canFinishExplicitTick;
    }

    @Override
    public boolean equals(Object object) {
      if (this == object) {
        return true;
      }
      if (!(object instanceof Signature)) {
        return false;
      }
      Signature other = (Signature) object;
      return effectMask == other.effectMask
        && sequence == other.sequence
        && canFinishExplicitTick == other.canFinishExplicitTick;
    }

    @Override
    public int hashCode() {
      int result = Long.hashCode(effectMask);
      result = 31 * result + Long.hashCode(sequence);
      result = 31 * result + Boolean.hashCode(canFinishExplicitTick);
      return result;
    }

    @Override
    public String toString() {
      return "Signature{effects=0x" + Long.toHexString(effectMask)
        + ", sequence=" + sequence
        + ", explicit=" + canFinishExplicitTick + '}';
    }
  }
}
