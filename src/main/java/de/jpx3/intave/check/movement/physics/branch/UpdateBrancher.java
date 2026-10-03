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

import de.jpx3.intave.check.movement.physics.environment.SimulationEnvironment;
import de.jpx3.intave.check.movement.physics.update.CausalConstraint;
import de.jpx3.intave.check.movement.physics.update.TickAmbiguousUpdate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.UnaryOperator;

public final class UpdateBrancher extends MovementSearchBrancher {
	@Override
	public void branch(MovementSearchInput input, MovementSearchBranch inputBranch, Collection<MovementSearchBranch> outputBranches) {
		SimulationEnvironment environment = input.environment();

		List<TickAmbiguousUpdate> updates = input.sortedPossibleTickAmbiguousUpdates();
		if (updates.isEmpty()) {
			outputBranches.add(inputBranch);
			return;
		}
		// Some updates MUST happen in this tick, so we enforce them and all before to happen now
		long lastVerifiedCompleteUpdate = Long.MIN_VALUE;
		long lastExplicitlyRequiredUpdate = Long.MIN_VALUE;
		long firstUpdateThatCannotBePostponed = Long.MAX_VALUE;
		for (TickAmbiguousUpdate update : updates) {
			CausalConstraint updateConstraint = update.constraint();
			if (updateConstraint.mustHappenThisTick(environment) && !updateConstraint.isOpenBounded()) {
				lastVerifiedCompleteUpdate = Math.max(lastVerifiedCompleteUpdate, updateConstraint.sequenceNumber());
			}
			if (update.mustRunBeforeExplicitTick()) {
				lastExplicitlyRequiredUpdate = Math.max(
					lastExplicitlyRequiredUpdate,
					updateConstraint.sequenceNumber()
				);
			}
			if (!update.canBePostponed(environment)) {
				firstUpdateThatCannotBePostponed = Math.min(
					firstUpdateThatCannotBePostponed,
					updateConstraint.sequenceNumber()
				);
			}
		}

		UnaryOperator<SimulationEnvironment> environmentUpdater = UnaryOperator.identity();
		List<UnaryOperator<SimulationEnvironment>> options = new ArrayList<>();
		List<Boolean> canFinishTick = new ArrayList<>();
		long requiredForExplicitTick = Math.max(
			lastVerifiedCompleteUpdate, lastExplicitlyRequiredUpdate
		);

		collectOptions(
			updates, 0, environmentUpdater, environment.activeSequence(),
			new ArrayList<>(), true, firstUpdateThatCannotBePostponed,
			requiredForExplicitTick, options, canFinishTick
		);

		for (int i = options.size() - 1; i >= 0; i--) {
			UnaryOperator<SimulationEnvironment> envUpdate = options.get(i);
			boolean thisCanFinishTick = canFinishTick.get(i);
			MovementSearchBranch cfg = inputBranch;
			cfg = cfg.withAmbiguousUpdates(envUpdate, i, thisCanFinishTick);
			cfg = cfg.withExplicitTickFinishAllow(thisCanFinishTick);
			outputBranches.add(cfg);
		}
	}

	private static void collectOptions(
		List<TickAmbiguousUpdate> updates,
		int updateIndex,
		UnaryOperator<SimulationEnvironment> environmentUpdater,
		long appliedSequence,
		List<TickAmbiguousUpdate> updatesAppliedThisTick,
		boolean currentStateChanged,
		long firstUpdateThatCannotBePostponed,
		long requiredForExplicitTick,
		List<UnaryOperator<SimulationEnvironment>> options,
		List<Boolean> canFinishTick
	) {
		if (currentStateChanged && (firstUpdateThatCannotBePostponed == Long.MAX_VALUE
			|| appliedSequence >= firstUpdateThatCannotBePostponed)) {
			options.add(environmentUpdater);
			canFinishTick.add(appliedSequence >= requiredForExplicitTick);
		}
		if (updateIndex == updates.size()) {
			return;
		}

		TickAmbiguousUpdate update = updates.get(updateIndex);
		if (canRunInSameTick(update, updatesAppliedThisTick)) {
			CausalConstraint constraint = update.constraint();
			UnaryOperator<SimulationEnvironment> updatedEnvironment = andThen(
				environmentUpdater,
				env -> {
					update.applyTo(env);
					env.setActiveSequence(constraint.sequenceNumber());
					return env;
				}
			);
			updatesAppliedThisTick.add(update);
			collectOptions(
				updates, updateIndex + 1, updatedEnvironment,
				constraint.sequenceNumber(), updatesAppliedThisTick, true,
				firstUpdateThatCannotBePostponed, requiredForExplicitTick,
				options, canFinishTick
			);
			updatesAppliedThisTick.remove(updatesAppliedThisTick.size() - 1);
		}

		if (update.canBeSkipped()) {
			collectOptions(
				updates, updateIndex + 1, environmentUpdater, appliedSequence,
				updatesAppliedThisTick, false, firstUpdateThatCannotBePostponed,
				requiredForExplicitTick, options, canFinishTick
			);
		}
	}

	private static boolean canRunInSameTick(
		TickAmbiguousUpdate update, List<TickAmbiguousUpdate> appliedUpdates
	) {
		for (TickAmbiguousUpdate appliedUpdate : appliedUpdates) {
			if (!update.canRunInSameTickWith(appliedUpdate)
				|| !appliedUpdate.canRunInSameTickWith(update)) {
				return false;
			}
		}
		return true;
	}

	private static <T> UnaryOperator<T> andThen(UnaryOperator<T> first, UnaryOperator<T> second) {
		return t -> second.apply(first.apply(t));
	}
}
