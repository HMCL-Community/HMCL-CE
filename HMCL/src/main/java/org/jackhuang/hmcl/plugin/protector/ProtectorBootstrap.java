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
package org.jackhuang.hmcl.plugin.protector;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import org.jackhuang.hmcl.Metadata;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/// Establishes the isolated Protector parent or joins it as the authenticated protected launcher child.
@NotNullByDefault
public final class ProtectorBootstrap {
    /// Internal endpoint argument consumed before ordinary launcher argument processing.
    static final String ENDPOINT_ARGUMENT = "--hmcl-protector-endpoint";

    /// Internal authentication argument consumed before ordinary launcher argument processing.
    static final String NONCE_ARGUMENT = "--hmcl-protector-nonce";

    /// URL-safe random nonce source used only for local session authentication.
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /// Windows named-pipe namespace prefix.
    private static final String WINDOWS_PIPE_PREFIX = "\\\\.\\pipe\\hmcl-protector-";

    /// Windows named-pipe access flags requiring this process to create the first pipe instance.
    private static final int WINDOWS_PIPE_OPEN_MODE =
            Kernel32.PIPE_ACCESS_DUPLEX | Kernel32.FILE_FLAG_FIRST_PIPE_INSTANCE;

    /// Maximum wait for the OS to publish child termination after its control transport closes.
    private static final Duration TRANSPORT_CLOSE_EXIT_WAIT = Duration.ofSeconds(1);

    /// Prevents construction of the process bootstrap utility.
    private ProtectorBootstrap() {
    }

    /// Enters Protector supervision as a new parent or connects an internally launched protected child.
    ///
    /// @param launcherArgs restart-barrier-stripped launcher arguments
    /// @return stripped launcher arguments in the child, or `null` after the parent finishes supervision
    /// @throws IOException if local IPC, child launch, authentication, or supervision fails closed
    public static String @Nullable [] enter(String @Unmodifiable [] launcherArgs) throws IOException {
        @Nullable ChildInvocation child = ChildInvocation.parse(launcherArgs);
        if (child != null) {
            StartupReporter.connect(child.endpoint(), child.nonce());
            return child.launcherArgs();
        }
        supervise(Metadata.HMCL_LOCAL_HOME, productionCommand(), launcherArgs, SupervisionPolicy.production());
        return null;
    }

    /// Launches and supervises one child command through an authenticated platform-local endpoint.
    ///
    /// @param launcherHome launcher-local recovery directory
    /// @param commandPrefix Java command ending in the child main class
    /// @param launcherArgs ordinary child arguments
    /// @param policy supervision deadlines
    /// @throws IOException if transport, process, protocol, or recovery handling fails
    static void supervise(
            Path launcherHome,
            @Unmodifiable List<String> commandPrefix,
            String @Unmodifiable [] launcherArgs,
            SupervisionPolicy policy
    ) throws IOException {
        String nonce = newNonce();
        try (LocalServer server = LocalServer.open()) {
            List<String> command = new ArrayList<>(commandPrefix);
            command.add(ENDPOINT_ARGUMENT);
            command.add(server.descriptor());
            command.add(NONCE_ARGUMENT);
            command.add(nonce);
            command.addAll(List.of(launcherArgs));
            Process child = new ProcessBuilder(command)
                    .directory(Path.of(System.getProperty("user.dir")).toFile())
                    .inheritIO()
                    .start();
            superviseStartedChild(launcherHome, child, server, nonce, policy);
        }
    }

    /// Runs the authenticated state machine for one already started child.
    ///
    /// @param launcherHome launcher-local recovery directory
    /// @param child started child process
    /// @param server bound local server
    /// @param nonce session authentication nonce
    /// @param policy supervision deadlines
    /// @throws IOException if supervision or recovery persistence fails
    private static void superviseStartedChild(
            Path launcherHome,
            Process child,
            LocalServer server,
            String nonce,
            SupervisionPolicy policy
    ) throws IOException {
        long startupNanos = System.nanoTime();
        PluginRecoveryStore recoveryStore = new PluginRecoveryStore(launcherHome);
        StartupState state = new StartupState(startupNanos);
        @Nullable LocalConnection connection;
        try {
            connection = server.accept(child, policy.connectTimeout());
        } catch (IOException acceptFailure) {
            try {
                persistRecovery(recoveryStore, PluginRecoveryRecord.FailureReason.CORE_DEADLINE_EXCEEDED, state);
            } catch (IOException recoveryFailure) {
                acceptFailure.addSuppressed(recoveryFailure);
            }
            try {
                terminateUnauthenticatedChild(child, policy.terminationGrace());
            } catch (IOException terminationFailure) {
                acceptFailure.addSuppressed(terminationFailure);
            }
            throw acceptFailure;
        }
        if (connection == null) {
            persistRecovery(recoveryStore, failureForExit(child), state);
            return;
        }
        state.connected(System.nanoTime());

        boolean terminalHandoff = false;
        try (connection) {
            ProtectorProtocol inboundProtocol = new ProtectorProtocol(nonce);
            ProtectorProtocol outboundProtocol = new ProtectorProtocol(nonce);
            LinkedBlockingQueue<ReadEvent> events = new LinkedBlockingQueue<>();
            Thread reader = new Thread(
                    () -> readMessages(connection.input(), inboundProtocol, events),
                    "HMCL Protector Control Reader"
            );
            reader.setDaemon(true);
            reader.start();

            while (true) {
                long now = System.nanoTime();
                @Nullable PluginRecoveryRecord.FailureReason timeout = state.timeoutAt(now, policy);
                if (timeout != null) {
                    handleTimeout(
                            recoveryStore,
                            timeout,
                            child,
                            connection.output(),
                            outboundProtocol,
                            state,
                            policy
                    );
                    return;
                }

                @Nullable ReadEvent event;
                try {
                    event = events.poll(child.isAlive() ? 20L : 200L, TimeUnit.MILLISECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Protector supervision was interrupted", exception);
                }
                if (event == null) {
                    if (!child.isAlive()) {
                        persistRecovery(recoveryStore, failureForExit(child), state);
                        return;
                    }
                    continue;
                }
                if (event.failure() != null) {
                    persistRecovery(recoveryStore, failureForExit(child), state);
                    return;
                }
                @Nullable ProtectorMessage message = event.message();
                if (message == null) {
                    persistRecovery(recoveryStore, failureForExit(child), state);
                    return;
                }
                Terminal terminal;
                try {
                    terminal = state.accept(message, now);
                } catch (IOException protocolFailure) {
                    try {
                        persistRecovery(
                                recoveryStore,
                                PluginRecoveryRecord.FailureReason.UNEXPECTED_PROCESS_EXIT,
                                state
                        );
                    } catch (IOException recoveryFailure) {
                        protocolFailure.addSuppressed(recoveryFailure);
                    }
                    throw protocolFailure;
                }
                if (terminal == Terminal.READY || terminal == Terminal.CANCELLED || terminal == Terminal.NORMAL) {
                    recoveryStore.clear();
                    terminalHandoff = true;
                    return;
                }
            }
        } finally {
            if (!terminalHandoff && child.isAlive()) {
                forceTerminateChild(child, null);
            }
        }
    }

    /// Terminates one child that did not establish an authenticated control channel.
    ///
    /// @param child unauthenticated child process
    /// @param grace maximum wait after ordinary process termination
    /// @throws IOException if termination waiting is interrupted
    private static void terminateUnauthenticatedChild(Process child, Duration grace) throws IOException {
        if (!child.isAlive()) {
            return;
        }
        child.destroy();
        try {
            if (!child.waitFor(grace.toMillis(), TimeUnit.MILLISECONDS)) {
                child.destroyForcibly();
                child.waitFor();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            child.destroyForcibly();
            throw new IOException("Protector unauthenticated-child termination was interrupted", exception);
        }
    }

    /// Persists one timeout and guarantees child termination if graceful control or waiting fails.
    ///
    /// @param recoveryStore launcher-local recovery store
    /// @param reason controlled timeout classification
    /// @param child timed-out child process
    /// @param output parent-to-child control stream
    /// @param protocol authenticated parent encoder
    /// @param state last authenticated startup state
    /// @param policy supervision deadlines
    /// @throws IOException if persistence, control writing, or graceful waiting fails
    static void handleTimeout(
            PluginRecoveryStore recoveryStore,
            PluginRecoveryRecord.FailureReason reason,
            Process child,
            OutputStream output,
            ProtectorProtocol protocol,
            StartupState state,
            SupervisionPolicy policy
    ) throws IOException {
        try {
            persistRecovery(recoveryStore, reason, state);
            terminateTimedOutChild(child, output, protocol, state, policy);
        } catch (IOException failure) {
            forceTerminateChild(child, failure);
            throw failure;
        }
    }

    /// Force-terminates and reaps one child without replacing an existing primary failure.
    ///
    /// @param child child process
    /// @param primaryFailure primary failure that receives an interrupted-cleanup suppression, or `null`
    private static void forceTerminateChild(Process child, @Nullable IOException primaryFailure) {
        if (!child.isAlive()) {
            return;
        }
        child.destroyForcibly();
        try {
            child.waitFor();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (primaryFailure != null) {
                primaryFailure.addSuppressed(new IOException("Protector forced termination was interrupted", exception));
            }
        }
    }

    /// Reads authenticated child messages until EOF or the first transport/protocol failure.
    ///
    /// @param input child-to-parent stream
    /// @param protocol authenticated decoder
    /// @param events supervisor event queue
    private static void readMessages(
            InputStream input,
            ProtectorProtocol protocol,
            LinkedBlockingQueue<ReadEvent> events
    ) {
        try {
            while (true) {
                @Nullable String line = ProtectorProtocol.readLine(input);
                if (line == null) {
                    events.offer(ReadEvent.eof());
                    return;
                }
                events.offer(ReadEvent.message(protocol.decode(line)));
            }
        } catch (IOException failure) {
            events.offer(ReadEvent.failure(failure));
        }
    }

    /// Requests diagnostics and graceful termination, then force-terminates an unresponsive child after the grace.
    ///
    /// @param child timed-out child
    /// @param output parent-to-child stream
    /// @param protocol authenticated encoder
    /// @param state last authenticated startup state
    /// @param policy supervision deadlines
    /// @throws IOException if a control request cannot be sent
    private static void terminateTimedOutChild(
            Process child,
            OutputStream output,
            ProtectorProtocol protocol,
            StartupState state,
            SupervisionPolicy policy
    ) throws IOException {
        writeControl(output, protocol, ProtectorMessage.Kind.DIAGNOSTICS_REQUEST, state);
        writeControl(output, protocol, ProtectorMessage.Kind.TERMINATION_REQUEST, state);
        try {
            if (!child.waitFor(policy.terminationGrace().toMillis(), TimeUnit.MILLISECONDS)) {
                child.destroyForcibly();
                child.waitFor();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            child.destroyForcibly();
            throw new IOException("Protector termination wait was interrupted", exception);
        }
    }

    /// Writes one authenticated parent control message without retaining authentication material.
    ///
    /// @param output parent-to-child stream
    /// @param protocol authenticated encoder
    /// @param kind requested operation
    /// @param state last authenticated startup state
    /// @throws IOException if encoding or transport writing fails
    private static void writeControl(
            OutputStream output,
            ProtectorProtocol protocol,
            ProtectorMessage.Kind kind,
            StartupState state
    ) throws IOException {
        ProtectorMessage message = new ProtectorMessage(
                kind,
                state.nextControlTimestamp(Math.max(0L, System.nanoTime())),
                state.stage(),
                null,
                null
        );
        output.write(protocol.encode(message).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        output.flush();
    }

    /// Persists one bounded recovery record and retries only a definitely unpublished failure.
    ///
    /// @param store recovery store
    /// @param reason controlled failure reason
    /// @param state last authenticated state
    /// @throws IOException if publication is not known to have succeeded
    private static void persistRecovery(
            PluginRecoveryStore store,
            PluginRecoveryRecord.FailureReason reason,
            StartupState state
    ) throws IOException {
        PluginRecoveryRecord record = new PluginRecoveryRecord(
                Math.max(1L, System.currentTimeMillis()),
                reason.category(),
                reason,
                state.stage(),
                state.lastHeartbeatTimestamp(),
                state.activeProviderId(),
                state.activePluginId(),
                null,
                null
        );
        try {
            store.save(record);
        } catch (PluginRecoveryStore.PublicationException first) {
            if (first.result().outcome() != PluginRecoveryStore.PublicationOutcome.NOT_PUBLISHED) {
                return;
            }
            store.save(record);
        }
    }

    /// Classifies one child exit without retaining raw process output.
    ///
    /// @param child terminated or terminating child
    /// @return controlled process failure reason
    private static PluginRecoveryRecord.FailureReason failureForExit(Process child) throws IOException {
        if (child.isAlive()) {
            try {
                child.waitFor(TRANSPORT_CLOSE_EXIT_WAIT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Protector child-exit classification was interrupted", exception);
            }
        }
        if (child.isAlive()) {
            return PluginRecoveryRecord.FailureReason.UNEXPECTED_PROCESS_EXIT;
        }
        return child.exitValue() == 0
                ? PluginRecoveryRecord.FailureReason.UNEXPECTED_PROCESS_EXIT
                : PluginRecoveryRecord.FailureReason.CHILD_CRASH;
    }

    /// Builds the current JVM command while retaining runtime arguments and the complete classpath.
    ///
    /// @return immutable child command prefix ending in the launcher entry point
    private static @Unmodifiable List<String> productionCommand() {
        String executable = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows")
                ? "java.exe"
                : "java";
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", executable).toString());
        command.addAll(ManagementFactory.getRuntimeMXBean().getInputArguments());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add("org.jackhuang.hmcl.EntryPoint");
        return List.copyOf(command);
    }

    /// Generates one 256-bit URL-safe unpadded session nonce.
    ///
    /// @return nonce accepted by [ProtectorProtocol]
    private static String newNonce() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /// Terminal supervision classifications produced by authenticated child messages.
    @NotNullByDefault
    private enum Terminal {
        /// Startup remains under supervision.
        ACTIVE,

        /// UI readiness completed startup supervision.
        READY,

        /// User cancellation completed startup without failure.
        CANCELLED,

        /// Normal pre-ready shutdown completed without failure.
        NORMAL
    }

    /// Immutable production or test supervision deadlines.
    ///
    /// @param connectTimeout child connection deadline
    /// @param heartbeatLossTimeout heartbeat-loss deadline
    /// @param coreReadyTimeout Core readiness deadline
    /// @param providerReadyTimeout active Provider deadline
    /// @param pluginReadyTimeout active ordinary-plugin deadline
    /// @param hardStartupTimeout non-renewable startup deadline
    /// @param terminationGrace graceful termination wait
    @NotNullByDefault
    record SupervisionPolicy(
            Duration connectTimeout,
            Duration heartbeatLossTimeout,
            Duration coreReadyTimeout,
            Duration providerReadyTimeout,
            Duration pluginReadyTimeout,
            Duration hardStartupTimeout,
            Duration terminationGrace
    ) {
        /// Validates strictly positive supervision deadlines.
        SupervisionPolicy {
            for (Duration duration : List.of(
                    connectTimeout,
                    heartbeatLossTimeout,
                    coreReadyTimeout,
                    providerReadyTimeout,
                    pluginReadyTimeout,
                    hardStartupTimeout,
                    terminationGrace
            )) {
                if (duration.isZero() || duration.isNegative()) {
                    throw new IllegalArgumentException("Protector deadlines must be positive");
                }
            }
        }

        /// Returns the protocol-defined production deadlines.
        ///
        /// @return production supervision policy
        static SupervisionPolicy production() {
            return new SupervisionPolicy(
                    ProtectorProtocol.CONNECT_TIMEOUT,
                    ProtectorProtocol.HEARTBEAT_LOSS_TIMEOUT,
                    ProtectorProtocol.CORE_READY_TIMEOUT,
                    ProtectorProtocol.PROVIDER_READY_TIMEOUT,
                    ProtectorProtocol.PLUGIN_READY_TIMEOUT,
                    ProtectorProtocol.HARD_STARTUP_TIMEOUT,
                    ProtectorProtocol.TERMINATION_GRACE_TIMEOUT
            );
        }
    }

    /// Mutable parent-side authenticated startup state and deadline tracker.
    @NotNullByDefault
    static final class StartupState {
        /// Parent monotonic time when the protected process was started.
        private final long startupNanos;

        /// Last authenticated stage.
        private ProtectorStage stage = ProtectorStage.JVM_STARTED;

        /// Parent monotonic time of the last stage transition or renewal.
        private long stageLeaseNanos;

        /// Parent monotonic time of the last authenticated heartbeat.
        private long lastHeartbeatArrivalNanos;

        /// Last authenticated child monotonic heartbeat timestamp.
        private long lastHeartbeatTimestamp;

        /// Last parent monotonic timestamp used for an outbound control message.
        private long lastControlTimestampNanos = -1L;

        /// Active Runtime Provider ID, or `null`.
        private @Nullable String activeProviderId;

        /// Active ordinary plugin ID, or `null`.
        private @Nullable String activePluginId;

        /// Creates the initial JVM-started state.
        ///
        /// @param startupNanos parent monotonic process-start time
        StartupState(long startupNanos) {
            this.startupNanos = startupNanos;
            this.stageLeaseNanos = startupNanos;
            this.lastHeartbeatArrivalNanos = startupNanos;
        }

        /// Starts heartbeat-loss measurement after the authenticated local connection is established.
        ///
        /// @param connectionNanos parent monotonic connection time
        void connected(long connectionNanos) {
            lastHeartbeatArrivalNanos = connectionNanos;
        }

        /// Accepts one authenticated message and updates state only after transition validation.
        ///
        /// @param message decoded child message
        /// @param arrivalNanos parent monotonic receive time
        /// @return terminal or active classification
        /// @throws IOException if the message contradicts current startup state
        Terminal accept(ProtectorMessage message, long arrivalNanos) throws IOException {
            return switch (message.kind()) {
                case HEARTBEAT -> {
                    requireCurrentState(message);
                    lastHeartbeatArrivalNanos = arrivalNanos;
                    lastHeartbeatTimestamp = message.monotonicTimestampNanos();
                    yield Terminal.ACTIVE;
                }
                case STAGE -> {
                    if (message.stage().ordinal() < stage.ordinal()
                            || message.stage() == ProtectorStage.UI_READY) {
                        throw new IOException("Invalid Protector stage transition");
                    }
                    stage = message.stage();
                    activeProviderId = message.activeProviderId();
                    activePluginId = message.activePluginId();
                    stageLeaseNanos = arrivalNanos;
                    yield Terminal.ACTIVE;
                }
                case LEASE_RENEWAL -> {
                    requireCurrentState(message);
                    stageLeaseNanos = arrivalNanos;
                    yield Terminal.ACTIVE;
                }
                case READY -> {
                    if (stage == ProtectorStage.JVM_STARTED) {
                        throw new IOException("Protector cannot report UI readiness before Core readiness");
                    }
                    yield Terminal.READY;
                }
                case CANCEL -> {
                    requireCurrentStage(message);
                    yield Terminal.CANCELLED;
                }
                case NORMAL_SHUTDOWN -> {
                    requireCurrentStage(message);
                    yield Terminal.NORMAL;
                }
                case DIAGNOSTICS_RESPONSE, TERMINATION_ACKNOWLEDGED -> Terminal.ACTIVE;
                case DIAGNOSTICS_REQUEST, TERMINATION_REQUEST ->
                        throw new IOException("Child sent a parent-only Protector operation");
            };
        }

        /// Returns the first deadline exceeded at one parent monotonic time.
        ///
        /// @param nowNanos parent monotonic time
        /// @param policy supervision deadlines
        /// @return controlled timeout reason, or `null` while startup may continue
        @Nullable PluginRecoveryRecord.FailureReason timeoutAt(long nowNanos, SupervisionPolicy policy) {
            if (elapsed(startupNanos, nowNanos) >= policy.hardStartupTimeout().toNanos()) {
                return PluginRecoveryRecord.FailureReason.HARD_STARTUP_DEADLINE_EXCEEDED;
            }
            if (elapsed(lastHeartbeatArrivalNanos, nowNanos) >= policy.heartbeatLossTimeout().toNanos()) {
                return PluginRecoveryRecord.FailureReason.HEARTBEAT_LOST;
            }
            long leaseElapsed = elapsed(stageLeaseNanos, nowNanos);
            if (stage == ProtectorStage.JVM_STARTED && leaseElapsed >= policy.coreReadyTimeout().toNanos()) {
                return PluginRecoveryRecord.FailureReason.CORE_DEADLINE_EXCEEDED;
            }
            if (stage == ProtectorStage.RUNTIME_PROVIDERS_LOADING
                    && activeProviderId != null
                    && leaseElapsed >= policy.providerReadyTimeout().toNanos()) {
                return PluginRecoveryRecord.FailureReason.PROVIDER_DEADLINE_EXCEEDED;
            }
            if (stage == ProtectorStage.ORDINARY_PLUGINS_LOADING
                    && activePluginId != null
                    && leaseElapsed >= policy.pluginReadyTimeout().toNanos()) {
                return PluginRecoveryRecord.FailureReason.PLUGIN_DEADLINE_EXCEEDED;
            }
            return null;
        }

        /// Returns and records a strictly increasing parent control timestamp.
        ///
        /// @param candidate current non-negative monotonic clock value
        /// @return candidate or the next representable value after the prior control timestamp
        long nextControlTimestamp(long candidate) {
            long timestamp = Math.max(candidate, lastControlTimestampNanos + 1L);
            lastControlTimestampNanos = timestamp;
            return timestamp;
        }

        /// Validates that a state-bearing message describes the exact current stage and identities.
        ///
        /// @param message authenticated state-bearing message
        /// @throws IOException if it does not match current state
        private void requireCurrentState(ProtectorMessage message) throws IOException {
            if (message.stage() != stage
                    || !java.util.Objects.equals(message.activeProviderId(), activeProviderId)
                    || !java.util.Objects.equals(message.activePluginId(), activePluginId)) {
                throw new IOException("Protector message does not match current startup state");
            }
        }

        /// Validates that a terminal message names the exact current startup stage.
        ///
        /// @param message authenticated terminal message
        /// @throws IOException if it names a stale or future stage
        private void requireCurrentStage(ProtectorMessage message) throws IOException {
            if (message.stage() != stage) {
                throw new IOException("Protector terminal message does not match current startup stage");
            }
        }

        /// Computes non-negative elapsed monotonic time without overflow.
        ///
        /// @param start earlier monotonic time
        /// @param end later monotonic time
        /// @return saturated elapsed nanoseconds
        private static long elapsed(long start, long end) {
            return end >= start ? end - start : Long.MAX_VALUE;
        }

        /// Returns the current stage.
        ///
        /// @return last authenticated stage
        ProtectorStage stage() {
            return stage;
        }

        /// Returns the last child heartbeat timestamp.
        ///
        /// @return authenticated child monotonic heartbeat time
        long lastHeartbeatTimestamp() {
            return lastHeartbeatTimestamp;
        }

        /// Returns the active Provider ID.
        ///
        /// @return active Provider ID, or `null`
        @Nullable String activeProviderId() {
            return activeProviderId;
        }

        /// Returns the active ordinary plugin ID.
        ///
        /// @return active plugin ID, or `null`
        @Nullable String activePluginId() {
            return activePluginId;
        }
    }

    /// One decoded message, clean EOF, or reader failure delivered to the supervisor loop.
    ///
    /// @param message decoded message, or `null`
    /// @param failure transport/protocol failure, or `null`
    @NotNullByDefault
    private record ReadEvent(@Nullable ProtectorMessage message, @Nullable IOException failure) {
        /// Creates one decoded-message event.
        ///
        /// @param message decoded child message
        /// @return message event
        private static ReadEvent message(ProtectorMessage message) {
            return new ReadEvent(message, null);
        }

        /// Creates one clean-EOF event.
        ///
        /// @return EOF event
        private static ReadEvent eof() {
            return new ReadEvent(null, null);
        }

        /// Creates one reader-failure event.
        ///
        /// @param failure transport or protocol failure
        /// @return failure event
        private static ReadEvent failure(IOException failure) {
            return new ReadEvent(null, failure);
        }
    }

    /// Parsed authenticated child invocation with internal arguments removed.
    ///
    /// @param endpoint platform-local endpoint descriptor
    /// @param nonce session nonce
    /// @param launcherArgs remaining ordinary launcher arguments
    @NotNullByDefault
    private record ChildInvocation(String endpoint, String nonce, String @Unmodifiable [] launcherArgs) {
        /// Parses exact internal argument pairs and rejects partial or duplicate configuration.
        ///
        /// @param args candidate launcher arguments
        /// @return child invocation, or `null` when no internal marker exists
        /// @throws IOException if internal configuration is malformed
        private static @Nullable ChildInvocation parse(String @Unmodifiable [] args) throws IOException {
            @Nullable String endpoint = null;
            @Nullable String nonce = null;
            List<String> ordinary = new ArrayList<>();
            boolean sawInternal = false;
            for (int index = 0; index < args.length; index++) {
                String argument = args[index];
                if (argument.equals(ENDPOINT_ARGUMENT) || argument.equals(NONCE_ARGUMENT)) {
                    sawInternal = true;
                    if (++index >= args.length) {
                        throw new IOException("Invalid internal Protector child arguments");
                    }
                    if (argument.equals(ENDPOINT_ARGUMENT)) {
                        if (endpoint != null) {
                            throw new IOException("Invalid internal Protector child arguments");
                        }
                        endpoint = args[index];
                    } else {
                        if (nonce != null) {
                            throw new IOException("Invalid internal Protector child arguments");
                        }
                        nonce = args[index];
                    }
                } else {
                    ordinary.add(argument);
                }
            }
            if (!sawInternal) {
                return null;
            }
            if (endpoint == null || nonce == null) {
                throw new IOException("Invalid internal Protector child arguments");
            }
            return new ChildInvocation(endpoint, nonce, ordinary.toArray(String[]::new));
        }
    }

    /// Platform-local bidirectional connection used by parent and protected child.
    @NotNullByDefault
    static final class LocalConnection implements AutoCloseable {
        /// Child-to-parent or parent-to-child input stream.
        private final InputStream input;

        /// Parent-to-child or child-to-parent output stream.
        private final OutputStream output;

        /// Resource closing the underlying socket or pipe.
        private final AutoCloseable resource;

        /// Creates one connection around an owned transport resource.
        ///
        /// @param input transport input
        /// @param output transport output
        /// @param resource underlying owned resource
        private LocalConnection(InputStream input, OutputStream output, AutoCloseable resource) {
            this.input = input;
            this.output = output;
            this.resource = resource;
        }

        /// Returns the connection input.
        ///
        /// @return transport input
        InputStream input() {
            return input;
        }

        /// Returns the connection output.
        ///
        /// @return transport output
        OutputStream output() {
            return output;
        }

        /// Closes the underlying transport resource.
        ///
        /// @throws IOException if transport cleanup fails
        @Override
        public void close() throws IOException {
            try {
                resource.close();
            } catch (IOException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new IOException("Protector transport cleanup failed", exception);
            }
        }
    }

    /// Bound platform-local server endpoint owned by one Protector parent.
    @NotNullByDefault
    private interface LocalServer extends AutoCloseable {
        /// Creates a Windows named pipe or Unix-domain socket for the current platform.
        ///
        /// @return bound local server
        /// @throws IOException if endpoint creation fails
        static LocalServer open() throws IOException {
            return isWindows() ? WindowsPipeServer.open() : UnixSocketServer.open();
        }

        /// Returns the opaque descriptor passed only to the child process.
        ///
        /// @return endpoint descriptor
        String descriptor();

        /// Accepts the child before its deadline or returns `null` after a pre-connect exit.
        ///
        /// @param child started child process
        /// @param timeout connection deadline
        /// @return connected transport, or `null` after child exit
        /// @throws IOException if connection times out or transport acceptance fails
        @Nullable LocalConnection accept(Process child, Duration timeout) throws IOException;

        /// Closes and removes the exact owned endpoint.
        ///
        /// @throws IOException if cleanup fails
        @Override
        void close() throws IOException;
    }

    /// Connects a protected child to one opaque platform-local descriptor.
    ///
    /// @param descriptor parent-created endpoint descriptor
    /// @param timeout connection deadline
    /// @return connected local transport
    /// @throws IOException if connection fails or times out
    static LocalConnection connectLocal(String descriptor, Duration timeout) throws IOException {
        return descriptor.startsWith(WINDOWS_PIPE_PREFIX)
                ? WindowsPipeConnection.connect(descriptor, timeout)
                : UnixSocketServer.connect(descriptor, timeout);
    }

    /// Returns whether the current runtime is Windows.
    ///
    /// @return whether Windows named-pipe transport is required
    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    /// Unix-domain socket server with owner-only endpoint directory permissions.
    @NotNullByDefault
    private static final class UnixSocketServer implements LocalServer {
        /// Owned temporary endpoint directory.
        private final Path directory;

        /// Exact Unix socket path.
        private final Path socketPath;

        /// Bound non-blocking server channel.
        private final ServerSocketChannel channel;

        /// Creates one bound server.
        ///
        /// @param directory owned endpoint directory
        /// @param socketPath exact socket path
        /// @param channel bound server channel
        private UnixSocketServer(Path directory, Path socketPath, ServerSocketChannel channel) {
            this.directory = directory;
            this.socketPath = socketPath;
            this.channel = channel;
        }

        /// Creates and binds one owner-only Unix-domain endpoint.
        ///
        /// @return bound server
        /// @throws IOException if creation or binding fails
        private static UnixSocketServer open() throws IOException {
            Path directory = Files.createTempDirectory("hmcl-protector-");
            try {
                Files.setPosixFilePermissions(directory, EnumSet.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE
                ));
                Path socketPath = directory.resolve("control.sock");
                ServerSocketChannel channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
                channel.configureBlocking(false);
                channel.bind(UnixDomainSocketAddress.of(socketPath));
                return new UnixSocketServer(directory, socketPath, channel);
            } catch (IOException failure) {
                Files.deleteIfExists(directory);
                throw failure;
            }
        }

        /// Connects one child Unix-domain socket with bounded retry.
        ///
        /// @param descriptor socket path
        /// @param timeout connection deadline
        /// @return connected transport
        /// @throws IOException if connection fails or times out
        private static LocalConnection connect(String descriptor, Duration timeout) throws IOException {
            long deadline = System.nanoTime() + timeout.toNanos();
            @Nullable IOException lastFailure = null;
            while (System.nanoTime() < deadline) {
                SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
                try {
                    channel.connect(UnixDomainSocketAddress.of(descriptor));
                    return socketConnection(channel);
                } catch (IOException failure) {
                    lastFailure = failure;
                    channel.close();
                    sleepRetry();
                }
            }
            throw new IOException("Protector child connection timed out", lastFailure);
        }

        /// Returns the exact socket path descriptor.
        ///
        /// @return socket path
        @Override
        public String descriptor() {
            return socketPath.toString();
        }

        /// Polls non-blocking accept until connection, process exit, or timeout.
        ///
        /// @param child started child process
        /// @param timeout connection deadline
        /// @return connected transport, or `null` after child exit
        /// @throws IOException if connection times out or acceptance fails
        @Override
        public @Nullable LocalConnection accept(Process child, Duration timeout) throws IOException {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                @Nullable SocketChannel accepted = channel.accept();
                if (accepted != null) {
                    accepted.configureBlocking(true);
                    return socketConnection(accepted);
                }
                if (!child.isAlive()) {
                    return null;
                }
                sleepRetry();
            }
            throw new IOException("Protector child connection timed out");
        }

        /// Closes the server and removes only its exact socket and directory.
        ///
        /// @throws IOException if cleanup fails
        @Override
        public void close() throws IOException {
            @Nullable IOException failure = null;
            try {
                channel.close();
            } catch (IOException exception) {
                failure = exception;
            }
            try {
                Files.deleteIfExists(socketPath);
                Files.deleteIfExists(directory);
            } catch (IOException exception) {
                if (failure != null) {
                    failure.addSuppressed(exception);
                } else {
                    failure = exception;
                }
            }
            if (failure != null) {
                throw failure;
            }
        }

        /// Wraps one connected Unix socket channel.
        ///
        /// @param channel connected socket
        /// @return bidirectional connection
        private static LocalConnection socketConnection(SocketChannel channel) {
            return new LocalConnection(
                    Channels.newInputStream(channel),
                    Channels.newOutputStream(channel),
                    channel
            );
        }
    }

    /// Windows named-pipe server rejecting remote clients at the kernel boundary.
    @NotNullByDefault
    private static final class WindowsPipeServer implements LocalServer {
        /// Owned server pipe handle.
        private final Pointer handle;

        /// Full opaque named-pipe path.
        private final String pipeName;

        /// Whether the handle was transferred to an accepted connection.
        private boolean accepted;

        /// Creates one bound named-pipe server.
        ///
        /// @param handle owned pipe handle
        /// @param pipeName full pipe path
        private WindowsPipeServer(Pointer handle, String pipeName) {
            this.handle = handle;
            this.pipeName = pipeName;
        }

        /// Creates one byte-mode duplex named pipe with remote clients rejected.
        ///
        /// @return bound server
        /// @throws IOException if pipe creation fails
        private static WindowsPipeServer open() throws IOException {
            String name = WINDOWS_PIPE_PREFIX + java.util.UUID.randomUUID();
            Pointer handle;
            try (WindowsPipeSecurity security = WindowsPipeSecurity.create()) {
                handle = Kernel32.INSTANCE.CreateNamedPipeW(
                        new WString(name),
                        WINDOWS_PIPE_OPEN_MODE,
                        Kernel32.PIPE_TYPE_BYTE | Kernel32.PIPE_READMODE_BYTE
                                | Kernel32.PIPE_NOWAIT | Kernel32.PIPE_REJECT_REMOTE_CLIENTS,
                        1,
                        ProtectorProtocol.MAX_MESSAGE_BYTES,
                        ProtectorProtocol.MAX_MESSAGE_BYTES,
                        0,
                        security.pointer()
                );
            }
            if (Kernel32.isInvalid(handle)) {
                throw Kernel32.failure("Protector named pipe could not be created");
            }
            return new WindowsPipeServer(handle, name);
        }

        /// Returns the full named-pipe descriptor.
        ///
        /// @return named-pipe path
        @Override
        public String descriptor() {
            return pipeName;
        }

        /// Polls non-blocking pipe connection until connection, process exit, or timeout.
        ///
        /// @param child started child process
        /// @param timeout connection deadline
        /// @return connected pipe, or `null` after child exit
        /// @throws IOException if connection times out or fails
        @Override
        public @Nullable LocalConnection accept(Process child, Duration timeout) throws IOException {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                if (Kernel32.INSTANCE.ConnectNamedPipe(handle, null)) {
                    return transferConnection();
                }
                int error = Native.getLastError();
                if (error == Kernel32.ERROR_PIPE_CONNECTED) {
                    return transferConnection();
                }
                if (error != Kernel32.ERROR_PIPE_LISTENING && error != Kernel32.ERROR_NO_DATA) {
                    throw Kernel32.failure("Protector named-pipe connection failed", error);
                }
                if (!child.isAlive()) {
                    return null;
                }
                sleepRetry();
            }
            throw new IOException("Protector child connection timed out");
        }

        /// Switches the accepted pipe to blocking byte mode and transfers handle ownership.
        ///
        /// @return connected named pipe
        /// @throws IOException if pipe mode cannot be changed
        private LocalConnection transferConnection() throws IOException {
            accepted = true;
            return WindowsPipeConnection.wrap(handle, true);
        }

        /// Closes an unaccepted server handle.
        ///
        /// @throws IOException if handle closure fails
        @Override
        public void close() throws IOException {
            if (!accepted && !Kernel32.INSTANCE.CloseHandle(handle)) {
                throw Kernel32.failure("Protector named-pipe server could not be closed");
            }
        }
    }

    /// Owner-and-SYSTEM-only Windows pipe security descriptor and native attribute structure.
    @NotNullByDefault
    private static final class WindowsPipeSecurity implements AutoCloseable {
        /// SDDL granting full control only to LocalSystem and the object owner.
        private static final String OWNER_ONLY_SDDL = "D:P(A;;GA;;;SY)(A;;GA;;;OW)";

        /// Native self-relative security descriptor allocated by Advapi32.
        private final Pointer descriptor;

        /// Native security attributes passed only while creating the named pipe.
        private final SecurityAttributes attributes;

        /// Creates one owned security wrapper around a converted descriptor.
        ///
        /// @param descriptor native self-relative security descriptor
        private WindowsPipeSecurity(Pointer descriptor) {
            this.descriptor = descriptor;
            this.attributes = new SecurityAttributes(descriptor);
        }

        /// Converts the fixed owner-only SDDL into native pipe security attributes.
        ///
        /// @return owned native security wrapper
        /// @throws IOException if Windows cannot convert the security descriptor
        private static WindowsPipeSecurity create() throws IOException {
            PointerByReference descriptor = new PointerByReference();
            if (!Advapi32.INSTANCE.ConvertStringSecurityDescriptorToSecurityDescriptorW(
                    new WString(OWNER_ONLY_SDDL),
                    Advapi32.SDDL_REVISION_1,
                    descriptor,
                    null
            )) {
                throw Kernel32.failure("Protector named-pipe security could not be created");
            }
            return new WindowsPipeSecurity(descriptor.getValue());
        }

        /// Returns the native SECURITY_ATTRIBUTES pointer used for pipe creation.
        ///
        /// @return native structure pointer
        private Pointer pointer() {
            return attributes.getPointer();
        }

        /// Returns the numeric native attribute pointer for structure verification.
        ///
        /// @return nonzero native pointer value
        private long attributesPointer() {
            return Pointer.nativeValue(attributes.getPointer());
        }

        /// Returns whether the native attributes retain the converted security descriptor.
        ///
        /// @return whether the descriptor pointer is non-null
        private boolean hasSecurityDescriptor() {
            return attributes.securityDescriptor != null;
        }

        /// Frees the converted native security descriptor after pipe creation copied it.
        @Override
        public void close() {
            Kernel32.INSTANCE.LocalFree(descriptor);
        }
    }

    /// JNA mapping of the Windows SECURITY_ATTRIBUTES structure.
    @NotNullByDefault
    @Structure.FieldOrder({"length", "securityDescriptor", "inheritHandle"})
    public static final class SecurityAttributes extends Structure {
        /// Native structure size in bytes.
        public int length;

        /// Explicit owner-only security descriptor.
        public Pointer securityDescriptor;

        /// Whether child processes inherit the created pipe handle.
        public int inheritHandle;

        /// Creates and writes one non-inheritable native security-attributes structure.
        ///
        /// @param descriptor native self-relative security descriptor
        private SecurityAttributes(Pointer descriptor) {
            this.securityDescriptor = descriptor;
            this.inheritHandle = 0;
            this.length = size();
            write();
        }
    }

    /// Windows named-pipe connection and stream adapter.
    @NotNullByDefault
    private static final class WindowsPipeConnection implements AutoCloseable {
        /// Owned connected pipe handle.
        private final Pointer handle;

        /// Whether closing should disconnect the server side first.
        private final boolean serverSide;

        /// Creates one connected pipe wrapper.
        ///
        /// @param handle owned pipe handle
        /// @param serverSide whether this is the server end
        private WindowsPipeConnection(Pointer handle, boolean serverSide) {
            this.handle = handle;
            this.serverSide = serverSide;
        }

        /// Connects a child to an existing local named pipe with bounded retry.
        ///
        /// @param pipeName full named-pipe path
        /// @param timeout connection deadline
        /// @return connected transport
        /// @throws IOException if connection fails or times out
        private static LocalConnection connect(String pipeName, Duration timeout) throws IOException {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                Pointer handle = Kernel32.INSTANCE.CreateFileW(
                        new WString(pipeName),
                        Kernel32.GENERIC_READ | Kernel32.GENERIC_WRITE,
                        0,
                        null,
                        Kernel32.OPEN_EXISTING,
                        0,
                        null
                );
                if (!Kernel32.isInvalid(handle)) {
                    IntByReference mode = new IntByReference(Kernel32.PIPE_READMODE_BYTE | Kernel32.PIPE_NOWAIT);
                    if (!Kernel32.INSTANCE.SetNamedPipeHandleState(handle, mode, null, null)) {
                        Kernel32.INSTANCE.CloseHandle(handle);
                        throw Kernel32.failure("Protector named-pipe mode could not be set");
                    }
                    return wrap(handle, false);
                }
                int error = Native.getLastError();
                if (error != Kernel32.ERROR_FILE_NOT_FOUND && error != Kernel32.ERROR_PIPE_BUSY) {
                    throw Kernel32.failure("Protector named-pipe client connection failed", error);
                }
                sleepRetry();
            }
            throw new IOException("Protector child connection timed out");
        }

        /// Wraps one connected pipe handle as Java streams.
        ///
        /// @param handle connected pipe handle
        /// @param serverSide whether this is the server end
        /// @return bidirectional transport
        private static LocalConnection wrap(Pointer handle, boolean serverSide) {
            WindowsPipeConnection connection = new WindowsPipeConnection(handle, serverSide);
            return new LocalConnection(
                    new PipeInputStream(handle),
                    new PipeOutputStream(handle),
                    connection
            );
        }

        /// Flushes, disconnects when necessary, and closes the owned handle.
        ///
        /// @throws IOException if handle closure fails
        @Override
        public void close() throws IOException {
            if (serverSide) {
                Kernel32.INSTANCE.DisconnectNamedPipe(handle);
            }
            if (!Kernel32.INSTANCE.CloseHandle(handle)) {
                throw Kernel32.failure("Protector named-pipe connection could not be closed");
            }
        }
    }

    /// Blocking InputStream over one connected Windows named pipe.
    @NotNullByDefault
    private static final class PipeInputStream extends InputStream {
        /// Borrowed pipe handle owned by its connection.
        private final Pointer handle;

        /// Native-read buffer amortizing per-byte protocol framing reads.
        private final byte[] bufferedBytes = new byte[4096];

        /// Next unread buffered byte index.
        private int bufferedPosition;

        /// Number of valid buffered bytes.
        private int bufferedLimit;

        /// Creates one stream over a connected handle.
        ///
        /// @param handle borrowed connected pipe handle
        private PipeInputStream(Pointer handle) {
            this.handle = handle;
        }

        /// Reads one byte from the pipe.
        ///
        /// @return unsigned byte or `-1` at clean pipe closure
        /// @throws IOException if reading fails
        @Override
        public int read() throws IOException {
            if (bufferedPosition >= bufferedLimit) {
                bufferedLimit = read(bufferedBytes, 0, bufferedBytes.length);
                bufferedPosition = 0;
                if (bufferedLimit < 0) {
                    return -1;
                }
            }
            return Byte.toUnsignedInt(bufferedBytes[bufferedPosition++]);
        }

        /// Reads one bounded byte range from the pipe.
        ///
        /// @param bytes destination array
        /// @param offset destination offset
        /// @param length maximum byte count
        /// @return bytes read or `-1` at clean pipe closure
        /// @throws IOException if reading fails
        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            java.util.Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) {
                return 0;
            }
            byte[] target = offset == 0 && length == bytes.length ? bytes : new byte[length];
            while (true) {
                IntByReference read = new IntByReference();
                if (Kernel32.INSTANCE.ReadFile(handle, target, length, read, null)) {
                    if (target != bytes) {
                        System.arraycopy(target, 0, bytes, offset, read.getValue());
                    }
                    return read.getValue();
                }
                int error = Native.getLastError();
                if (error == Kernel32.ERROR_BROKEN_PIPE) {
                    return -1;
                }
                if (error == Kernel32.ERROR_NO_DATA) {
                    sleepRetry();
                    continue;
                }
                throw Kernel32.failure("Protector named-pipe read failed", error);
            }
        }
    }

    /// Blocking OutputStream over one connected Windows named pipe.
    @NotNullByDefault
    private static final class PipeOutputStream extends OutputStream {
        /// Borrowed pipe handle owned by its connection.
        private final Pointer handle;

        /// Creates one stream over a connected handle.
        ///
        /// @param handle borrowed connected pipe handle
        private PipeOutputStream(Pointer handle) {
            this.handle = handle;
        }

        /// Writes one byte to the pipe.
        ///
        /// @param value low eight bits to write
        /// @throws IOException if writing fails
        @Override
        public void write(int value) throws IOException {
            write(new byte[]{(byte) value});
        }

        /// Writes one complete byte range to the pipe.
        ///
        /// @param bytes source array
        /// @param offset source offset
        /// @param length byte count
        /// @throws IOException if writing fails
        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            java.util.Objects.checkFromIndexSize(offset, length, bytes.length);
            int position = 0;
            while (position < length) {
                int remaining = length - position;
                byte[] source = new byte[remaining];
                System.arraycopy(bytes, offset + position, source, 0, remaining);
                IntByReference written = new IntByReference();
                if (!Kernel32.INSTANCE.WriteFile(handle, source, remaining, written, null)) {
                    int error = Native.getLastError();
                    if (isRetryablePipeWriteError(error)) {
                        sleepRetry();
                        continue;
                    }
                    throw Kernel32.failure("Protector named-pipe write failed", error);
                }
                if (written.getValue() <= 0) {
                    throw new IOException("Protector named-pipe write made no progress");
                }
                position += written.getValue();
            }
        }

        /// Flushes pipe buffers to the peer.
        ///
        /// @throws IOException if flushing fails
        @Override
        public void flush() throws IOException {
            // Non-blocking WriteFile completes the handoff; FlushFileBuffers would wait for peer consumption.
        }
    }

    /// Returns whether a Windows named-pipe write failure represents transient backpressure.
    ///
    /// `ERROR_NO_DATA` means the pipe is closing and therefore must never enter the retry loop.
    ///
    /// @param errorCode native Windows error code
    /// @return whether the write may be retried
    static boolean isRetryablePipeWriteError(int errorCode) {
        return errorCode == Kernel32.ERROR_PIPE_BUSY;
    }

    /// Minimal JNA-core Kernel32 named-pipe binding used without adding jna-platform to the launcher.
    @NotNullByDefault
    private interface Kernel32 extends Library {
        /// Loaded Windows kernel library.
        Kernel32 INSTANCE = Native.load("kernel32", Kernel32.class);

        /// Duplex server pipe access.
        int PIPE_ACCESS_DUPLEX = 0x00000003;

        /// Requires creation of the first named-pipe instance for this name.
        int FILE_FLAG_FIRST_PIPE_INSTANCE = 0x00080000;

        /// Byte-oriented pipe type.
        int PIPE_TYPE_BYTE = 0x00000000;

        /// Byte-oriented read mode.
        int PIPE_READMODE_BYTE = 0x00000000;

        /// Blocking pipe mode.
        int PIPE_WAIT = 0x00000000;

        /// Non-blocking server connect mode.
        int PIPE_NOWAIT = 0x00000001;

        /// Kernel rejection of remote named-pipe clients.
        int PIPE_REJECT_REMOTE_CLIENTS = 0x00000008;

        /// Generic read access.
        int GENERIC_READ = 0x80000000;

        /// Generic write access.
        int GENERIC_WRITE = 0x40000000;

        /// Opens an existing pipe instance.
        int OPEN_EXISTING = 3;

        /// The named-pipe client connected between create and connect.
        int ERROR_PIPE_CONNECTED = 535;

        /// A non-blocking server is listening for a client.
        int ERROR_PIPE_LISTENING = 536;

        /// No pipe data or previous client remains connected.
        int ERROR_NO_DATA = 232;

        /// The named pipe does not exist yet.
        int ERROR_FILE_NOT_FOUND = 2;

        /// All pipe instances are busy.
        int ERROR_PIPE_BUSY = 231;

        /// The peer closed its pipe end.
        int ERROR_BROKEN_PIPE = 109;

        /// Creates one named-pipe server instance.
        Pointer CreateNamedPipeW(
                WString name,
                int openMode,
                int pipeMode,
                int maxInstances,
                int outputBufferSize,
                int inputBufferSize,
                int defaultTimeoutMillis,
                @Nullable Pointer securityAttributes
        );

        /// Connects one client to a named-pipe server.
        boolean ConnectNamedPipe(Pointer pipe, @Nullable Pointer overlapped);

        /// Opens one existing named-pipe client handle.
        Pointer CreateFileW(
                WString name,
                int desiredAccess,
                int shareMode,
                @Nullable Pointer securityAttributes,
                int creationDisposition,
                int flagsAndAttributes,
                @Nullable Pointer templateFile
        );

        /// Sets byte and blocking mode for one connected pipe.
        boolean SetNamedPipeHandleState(
                Pointer pipe,
                IntByReference mode,
                @Nullable Pointer maximumCollectionCount,
                @Nullable Pointer collectionTimeout
        );

        /// Reads bytes from one connected pipe.
        boolean ReadFile(
                Pointer file,
                byte[] buffer,
                int bytesToRead,
                IntByReference bytesRead,
                @Nullable Pointer overlapped
        );

        /// Writes bytes to one connected pipe.
        boolean WriteFile(
                Pointer file,
                byte[] buffer,
                int bytesToWrite,
                IntByReference bytesWritten,
                @Nullable Pointer overlapped
        );

        /// Flushes all pending pipe writes.
        boolean FlushFileBuffers(Pointer file);

        /// Disconnects the server end of a pipe.
        boolean DisconnectNamedPipe(Pointer pipe);

        /// Closes one kernel handle.
        boolean CloseHandle(Pointer handle);

        /// Releases one local-allocation buffer.
        @Nullable Pointer LocalFree(Pointer memory);

        /// Returns whether a native handle equals INVALID_HANDLE_VALUE.
        ///
        /// @param handle candidate native handle
        /// @return whether the handle is invalid
        static boolean isInvalid(@Nullable Pointer handle) {
            return handle == null || Pointer.nativeValue(handle) == -1L;
        }

        /// Creates one fixed diagnostic using the current native error code.
        ///
        /// @param message safe fixed diagnostic
        /// @return I/O failure
        static IOException failure(String message) {
            return failure(message, Native.getLastError());
        }

        /// Creates one fixed diagnostic with a numeric native error code.
        ///
        /// @param message safe fixed diagnostic
        /// @param error native error code
        /// @return I/O failure
        static IOException failure(String message, int error) {
            return new IOException(message + " (Windows error " + error + ")");
        }
    }

    /// Minimal JNA-core Advapi32 security-descriptor binding used for Windows pipe ACLs.
    @NotNullByDefault
    private interface Advapi32 extends StdCallLibrary {
        /// Loaded Windows security library.
        Advapi32 INSTANCE = Native.load("advapi32", Advapi32.class);

        /// SDDL parser revision supported by Windows.
        int SDDL_REVISION_1 = 1;

        /// Converts one SDDL string into an allocated self-relative security descriptor.
        boolean ConvertStringSecurityDescriptorToSecurityDescriptorW(
                WString stringSecurityDescriptor,
                int stringSecurityDescriptorRevision,
                PointerByReference securityDescriptor,
                @Nullable IntByReference securityDescriptorSize
        );
    }

    /// Sleeps briefly between non-blocking local transport attempts.
    ///
    /// @throws IOException if interrupted
    private static void sleepRetry() throws IOException {
        try {
            Thread.sleep(10L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Protector local transport wait was interrupted", exception);
        }
    }
}
