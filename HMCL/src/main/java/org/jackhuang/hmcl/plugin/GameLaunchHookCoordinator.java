/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.plugin;

import org.jackhuang.hmcl.Launcher;
import org.jackhuang.hmcl.game.GameLaunchHookProcessListener;
import org.jackhuang.hmcl.launch.LaunchAuxiliaryProcessPlan;
import org.jackhuang.hmcl.launch.LaunchExecutionMode;
import org.jackhuang.hmcl.launch.LaunchPlanText;
import org.jackhuang.hmcl.launch.LaunchPreparation;
import org.jackhuang.hmcl.launch.LaunchProcessPlan;
import org.jackhuang.hmcl.launch.ProcessListener;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Coordinates launch-scoped Hook envelopes, transactional plan transformations, and protected secrets.
@NotNullByDefault
public final class GameLaunchHookCoordinator {
    /// Dispatcher used for ordered before and after callback execution.
    private final PluginHookDispatcher dispatcher;

    /// Dynamic eligibility probe for direct-launch after subscribers.
    private final BooleanSupplier afterSubscriberEligibility;

    /// Acquires application shutdown leases for eligible direct launch sessions.
    private final Function<String, AutoCloseable> shutdownLeaseFactory;

    /// Creates a coordinator backed by one plugin manager.
    ///
    /// @param pluginManager plugin manager supplying Hook subscribers
    public GameLaunchHookCoordinator(PluginManager pluginManager) {
        this(
                new PluginHookDispatcher(Objects.requireNonNull(pluginManager, "pluginManager")),
                () -> pluginManager.hasEligibleHookSubscriber(PluginHookPoint.AFTER_GAME_LAUNCH),
                Launcher::acquireShutdownLease
        );
    }

    /// Creates an injectable coordinator with deterministic dispatch and eligibility state.
    ///
    /// @param dispatcher Hook dispatcher
    /// @param afterSubscriberEligibility after-Hook eligibility probe
    /// @param shutdownLeaseFactory application shutdown lease factory
    GameLaunchHookCoordinator(
            PluginHookDispatcher dispatcher,
            BooleanSupplier afterSubscriberEligibility,
            Function<String, AutoCloseable> shutdownLeaseFactory
    ) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.afterSubscriberEligibility = Objects.requireNonNull(
                afterSubscriberEligibility, "afterSubscriberEligibility");
        this.shutdownLeaseFactory = Objects.requireNonNull(shutdownLeaseFactory, "shutdownLeaseFactory");
    }

    /// Returns the process-wide coordinator used by launcher instances.
    ///
    /// @return process-wide coordinator
    public static GameLaunchHookCoordinator getInstance() {
        return Holder.INSTANCE;
    }

    /// Applies every eligible before-game-launch transformation to an immutable preparation.
    ///
    /// The caller's preparation is never mutated. Protected values are staged on an isolated store and become
    /// visible to the next subscriber only after the complete ordinary-data candidate has passed validation.
    ///
    /// @param preparation caller-owned immutable launch preparation
    /// @param metadata immutable operation metadata
    /// @return launch-scoped session containing the executable transformed preparation
    /// @throws PluginHookDispatchException if a callback fails, times out, cancels, or returns invalid data
    public LaunchSession beforeLaunch(
            LaunchPreparation preparation,
            PluginDataObject metadata
    ) throws PluginHookDispatchException {
        Objects.requireNonNull(preparation, "preparation");
        PluginDataObject immutableMetadata = copyObject(metadata);
        String dispatchId = UUID.randomUUID().toString();
        GameLaunchSecretStore secrets = new GameLaunchSecretStore(preparation.secrets());
        PluginDataObject initialData = GameLaunchHookCodec.encodeBefore(preparation.plan(), immutableMetadata);
        BeforePolicy policy = new BeforePolicy(dispatchId, immutableMetadata, secrets);

        PluginDataObject finalData = dispatcher.dispatchBefore(
                PluginHookPoint.BEFORE_GAME_LAUNCH, initialData, policy);
        LaunchProcessPlan finalPlan = GameLaunchHookCodec.decodeBefore(
                finalData, immutableMetadata, secrets.slots());
        @Unmodifiable Map<String, String> finalSecrets = secrets.snapshot();
        LaunchPreparation finalPreparation = finalData == initialData
                && finalPlan.equals(preparation.plan())
                && finalSecrets.equals(preparation.secrets())
                ? preparation
                : preparation.withPlan(finalPlan).withSecrets(finalSecrets);
        boolean hasAfterSubscribers = finalPlan.executionMode() == LaunchExecutionMode.DIRECT
                && afterSubscriberEligibility.getAsBoolean();
        @Nullable AutoCloseable shutdownLease = hasAfterSubscribers
                ? Objects.requireNonNull(
                        shutdownLeaseFactory.apply("after-game-launch:" + dispatchId),
                        "shutdownLease")
                : null;
        return new LaunchSession(
                this,
                finalPreparation,
                dispatchId,
                immutableMetadata,
                finalPreparation.plan(),
                secrets,
                hasAfterSubscribers,
                shutdownLease
        );
    }

    /// Dispatches one best-effort after-game-launch notification for an owned process.
    ///
    /// @param session completed launch session
    /// @param observation immutable process exit observation
    private void afterLaunch(
            LaunchSession session,
            GameLaunchHookProcessListener.ExitObservation observation
    ) {
        PluginDataObject data = GameLaunchHookCodec.encodeAfter(
                session.finalPlan,
                session.metadata,
                observation.pid(),
                observation.exitCode(),
                observation.terminationKind(),
                observation.startedAt(),
                observation.endedAt(),
                observation.elapsedMilliseconds()
        );
        dispatcher.dispatchAfter(
                PluginHookPoint.AFTER_GAME_LAUNCH,
                data,
                new AfterPolicy(session, data)
        );
    }

    /// Copies one immutable Hook object so session identity never aliases caller identity.
    ///
    /// @param source source object
    /// @return immutable shallow copy whose nested values are already immutable
    private static PluginDataObject copyObject(PluginDataObject source) {
        Objects.requireNonNull(source, "metadata");
        return PluginDataObject.of(source.values());
    }

    /// Collects every secret slot referenced by a complete process plan.
    ///
    /// @param plan process plan
    /// @return immutable referenced slot set
    private static @Unmodifiable Set<String> referencedSecretSlots(LaunchProcessPlan plan) {
        Set<String> slots = new LinkedHashSet<>(plan.command().secretSlots());
        collectTextSlots(plan.environmentSet().values(), slots);
        collectAuxiliarySlots(plan.preLaunch(), slots);
        collectAuxiliarySlots(plan.postExit(), slots);
        return Set.copyOf(slots);
    }

    /// Adds secret slots from ordinary text values.
    ///
    /// @param values text values
    /// @param slots destination slot set
    private static void collectTextSlots(Iterable<LaunchPlanText> values, Set<String> slots) {
        for (LaunchPlanText value : values) {
            slots.addAll(value.secretSlots());
        }
    }

    /// Adds secret slots from one optional auxiliary process.
    ///
    /// @param process auxiliary process or `null`
    /// @param slots destination slot set
    private static void collectAuxiliarySlots(
            @Nullable LaunchAuxiliaryProcessPlan process,
            Set<String> slots
    ) {
        if (process != null) {
            slots.addAll(process.secretSlots());
        }
    }

    /// Implements the cancellable before-game-launch event and result policy.
    @NotNullByDefault
    private final class BeforePolicy implements PluginHookDispatcher.Policy {
        /// Opaque launch dispatch ID shared by all sequential callbacks.
        private final String dispatchId;

        /// Immutable metadata snapshot required in every replacement.
        private final PluginDataObject metadata;

        /// Committed launch-scoped secret state.
        private final GameLaunchSecretStore secrets;

        /// Creates a policy for one launch session.
        ///
        /// @param dispatchId opaque dispatch ID
        /// @param metadata immutable metadata
        /// @param secrets committed secret store
        private BeforePolicy(
                String dispatchId,
                PluginDataObject metadata,
                GameLaunchSecretStore secrets
        ) {
            this.dispatchId = dispatchId;
            this.metadata = metadata;
            this.secrets = secrets;
        }

        /// Creates a permission-scoped before event from currently committed state.
        ///
        /// @param subscriber current subscriber
        /// @param currentData currently committed data
        /// @return immutable before event
        @Override
        public PluginHookEvent eventFor(
                PluginHookSubscriber subscriber,
                PluginDataObject currentData
        ) {
            boolean accountGranted = subscriber.permissions().contains(PluginPermission.ACCOUNT);
            return new PluginHookEvent(
                    PluginHookEvent.CURRENT_CONTRACT_VERSION,
                    dispatchId,
                    PluginHookPoint.BEFORE_GAME_LAUNCH,
                    dispatcher.clock().instant(),
                    currentData,
                    secrets.accessor(accountGranted)
            );
        }

        /// Validates one complete replacement against both committed and staged protected values.
        ///
        /// @param subscriber current subscriber
        /// @param currentData currently committed data
        /// @param result non-cancel endpoint result
        /// @return validated candidate and atomic secret commit
        @Override
        public PluginHookDispatcher.Candidate validate(
                PluginHookSubscriber subscriber,
                PluginDataObject currentData,
                PluginHookResult result
        ) throws PluginHookDispatchException {
            if (result.action() == PluginHookResult.Action.UNCHANGED) {
                return new PluginHookDispatcher.Candidate(currentData, () -> {
                });
            }

            PluginDataObject candidateData = Objects.requireNonNull(result.data(), "Replacement data");
            boolean accountGranted = subscriber.permissions().contains(PluginPermission.ACCOUNT);
            secrets.validateOrdinaryData(subscriber.pluginId(), candidateData, accountGranted);
            GameLaunchSecretStore stagedSecrets = secrets.fork();
            stagedSecrets.applyProtectedUpdates(
                    subscriber.pluginId(), result.protectedSecrets(), accountGranted);
            stagedSecrets.validateOrdinaryData(subscriber.pluginId(), candidateData, accountGranted);
            LaunchProcessPlan candidatePlan = GameLaunchHookCodec.decodeBefore(
                    candidateData, metadata, stagedSecrets.slots());
            stagedSecrets.applyProtectedUpdates(
                    subscriber.pluginId(),
                    result.protectedSecrets(),
                    accountGranted,
                    referencedSecretSlots(candidatePlan)
            );
            @Unmodifiable Map<String, String> committedSnapshot = stagedSecrets.snapshot();
            return new PluginHookDispatcher.Candidate(
                    candidateData,
                    () -> secrets.commitValidated(committedSnapshot)
            );
        }

        /// Validates a deliberate cancellation before any launcher side effect begins.
        ///
        /// @param subscriber cancelling subscriber
        /// @param result cancel endpoint result
        @Override
        public void validateCancellation(
                PluginHookSubscriber subscriber,
                PluginHookResult result
        ) {
            boolean accountGranted = subscriber.permissions().contains(PluginPermission.ACCOUNT);
            secrets.validateCancellationMessage(
                    subscriber.pluginId(),
                    Objects.requireNonNull(result.message(), "Cancellation message"),
                    accountGranted
            );
        }

        /// Rejects use as an after policy because this session path is fail-fast.
        ///
        /// @param subscriber unused subscriber
        /// @param failure unused failure
        @Override
        public void reportAfterFailure(
                PluginHookSubscriber subscriber,
                PluginHookDispatchException failure
        ) {
            throw new IllegalStateException("Before-game-launch policy cannot report after failures");
        }
    }

    /// Implements the immutable notification-only after-game-launch policy.
    @NotNullByDefault
    private final class AfterPolicy implements PluginHookDispatcher.Policy {
        /// Completed launch session whose secrets remain protected and permission-scoped.
        private final LaunchSession session;

        /// Immutable expected exit data that replacement results may not alter.
        private final PluginDataObject expectedData;

        /// Creates one after policy for a completed owned process.
        ///
        /// @param session completed launch session
        /// @param expectedData immutable encoded exit data
        private AfterPolicy(LaunchSession session, PluginDataObject expectedData) {
            this.session = session;
            this.expectedData = expectedData;
        }

        /// Creates one permission-scoped after event.
        ///
        /// @param subscriber current subscriber
        /// @param currentData immutable exit data
        /// @return immutable after event
        @Override
        public PluginHookEvent eventFor(
                PluginHookSubscriber subscriber,
                PluginDataObject currentData
        ) {
            boolean accountGranted = subscriber.permissions().contains(PluginPermission.ACCOUNT);
            return new PluginHookEvent(
                    PluginHookEvent.CURRENT_CONTRACT_VERSION,
                    session.dispatchId,
                    PluginHookPoint.AFTER_GAME_LAUNCH,
                    dispatcher.clock().instant(),
                    currentData,
                    session.secrets.accessor(accountGranted)
            );
        }

        /// Accepts unchanged or byte-for-byte equivalent notification data and rejects all state edits.
        ///
        /// @param subscriber current subscriber
        /// @param currentData immutable exit data
        /// @param result non-cancel endpoint result
        /// @return validated notification candidate with a no-op commit
        @Override
        public PluginHookDispatcher.Candidate validate(
                PluginHookSubscriber subscriber,
                PluginDataObject currentData,
                PluginHookResult result
        ) throws PluginHookDispatchException {
            if (result.action() == PluginHookResult.Action.UNCHANGED) {
                return new PluginHookDispatcher.Candidate(currentData, () -> {
                });
            }
            if (!result.protectedSecrets().isEmpty()
                    || !expectedData.equals(Objects.requireNonNull(result.data(), "Replacement data"))) {
                throw new PluginHookDispatchException(
                        PluginHookPoint.AFTER_GAME_LAUNCH,
                        subscriber.pluginId(),
                        PluginHookDispatchException.Category.INVALID_RESULT
                );
            }
            return new PluginHookDispatcher.Candidate(expectedData, () -> {
            });
        }

        /// Rejects cancellation without inspecting its plugin-controlled message because an after Hook is notification-only.
        ///
        /// @param subscriber cancelling subscriber
        /// @param result unused cancel endpoint result
        /// @throws PluginHookDispatchException always, with an invalid-result category
        @Override
        public void validateCancellation(
                PluginHookSubscriber subscriber,
                PluginHookResult result
        ) throws PluginHookDispatchException {
            throw new PluginHookDispatchException(
                    PluginHookPoint.AFTER_GAME_LAUNCH,
                    subscriber.pluginId(),
                    PluginHookDispatchException.Category.INVALID_RESULT
            );
        }

        /// Logs one redacted after failure while dispatch continues to later subscribers.
        ///
        /// @param subscriber failed subscriber
        /// @param failure categorized redacted failure
        @Override
        public void reportAfterFailure(
                PluginHookSubscriber subscriber,
                PluginHookDispatchException failure
        ) {
            LOG.warning(failure.getMessage());
        }
    }

    /// Holds immutable launch-scoped state passed from before coordination into process execution and exit handling.
    @NotNullByDefault
    public static final class LaunchSession {
        /// Owning coordinator used for listener creation and after dispatch.
        private final GameLaunchHookCoordinator coordinator;

        /// Transformed executable or renderable launch preparation.
        private final LaunchPreparation preparation;

        /// Opaque dispatch ID shared by before and after events.
        private final String dispatchId;

        /// Immutable launch metadata.
        private final PluginDataObject metadata;

        /// Redacted final unresolved process plan.
        private final LaunchProcessPlan finalPlan;

        /// Protected launch-scoped secret state retained for exit coordination.
        private final GameLaunchSecretStore secrets;

        /// Whether a direct launch had an eligible after subscriber at before completion.
        private final boolean hasAfterSubscribers;

        /// Application shutdown lease held until after callbacks and post-exit handling complete.
        private final @Nullable AutoCloseable shutdownLease;

        /// Ensures exit-scoped resources are released at most once.
        private final AtomicBoolean exitFinished = new AtomicBoolean();

        /// Creates one complete immutable session.
        ///
        /// @param coordinator owning Hook coordinator
        /// @param preparation transformed launch preparation
        /// @param dispatchId opaque dispatch ID
        /// @param metadata immutable launch metadata
        /// @param finalPlan redacted final plan
        /// @param secrets protected secret store
        /// @param hasAfterSubscribers after subscriber eligibility snapshot
        /// @param shutdownLease application shutdown lease, or `null` when no after event is owed
        private LaunchSession(
                GameLaunchHookCoordinator coordinator,
                LaunchPreparation preparation,
                String dispatchId,
                PluginDataObject metadata,
                LaunchProcessPlan finalPlan,
                GameLaunchSecretStore secrets,
                boolean hasAfterSubscribers,
                @Nullable AutoCloseable shutdownLease
        ) {
            this.coordinator = coordinator;
            this.preparation = preparation;
            this.dispatchId = dispatchId;
            this.metadata = metadata;
            this.finalPlan = finalPlan;
            this.secrets = secrets;
            this.hasAfterSubscribers = hasAfterSubscribers;
            this.shutdownLease = shutdownLease;
        }

        /// Returns the transformed executable or renderable preparation.
        ///
        /// @return transformed preparation
        public LaunchPreparation preparation() {
            return preparation;
        }

        /// Returns the opaque launch dispatch ID.
        ///
        /// @return dispatch ID
        public String dispatchId() {
            return dispatchId;
        }

        /// Returns an immutable metadata copy.
        ///
        /// @return immutable launch metadata
        public PluginDataObject metadata() {
            return metadata;
        }

        /// Returns the redacted final unresolved process plan.
        ///
        /// @return final plan
        public LaunchProcessPlan finalPlan() {
            return finalPlan;
        }

        /// Returns whether a direct launch had an eligible after subscriber.
        ///
        /// @return after subscriber eligibility snapshot
        public boolean hasAfterSubscribers() {
            return hasAfterSubscribers;
        }

        /// Returns the original listener when no after event is owed, or a composing exactly-once listener.
        ///
        /// @param delegate existing process listener, or `null`
        /// @return original, composed, or `null` listener
        public @Nullable ProcessListener processListener(@Nullable ProcessListener delegate) {
            if (!hasAfterSubscribers) {
                return delegate;
            }
            return new GameLaunchHookProcessListener(
                    delegate,
                    coordinator.dispatcher.clock(),
                    this::afterLaunch
            );
        }

        /// Dispatches one exit observation through the owning coordinator.
        ///
        /// @param observation immutable process exit observation
        void afterLaunch(GameLaunchHookProcessListener.ExitObservation observation) {
            if (hasAfterSubscribers) {
                coordinator.afterLaunch(this, observation);
            }
        }

        /// Releases exit-scoped resources after listener and post-exit processing exactly once.
        public void finishExit() {
            if (shutdownLease == null || !exitFinished.compareAndSet(false, true)) {
                return;
            }
            try {
                shutdownLease.close();
            } catch (Exception exception) {
                LOG.warning("Failed to release application shutdown lease for launch " + dispatchId, exception);
            }
        }

        /// Releases exit-scoped resources when process creation failed before an exit callback was possible.
        public void closeWithoutProcess() {
            finishExit();
        }

        /// Returns the protected store for package-internal after coordination.
        ///
        /// @return launch-scoped secret store
        GameLaunchSecretStore secrets() {
            return secrets;
        }
    }

    /// Lazily owns the process-wide coordinator without affecting isolated tests.
    @NotNullByDefault
    private static final class Holder {
        /// Process-wide coordinator backed by the process-wide plugin manager.
        private static final GameLaunchHookCoordinator INSTANCE =
                new GameLaunchHookCoordinator(PluginManager.getInstance());

        /// Prevents construction of the static holder.
        private Holder() {
        }
    }
}
