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

import org.jackhuang.hmcl.Metadata;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/// Reports authenticated protected-child startup progress and responds to parent control requests.
@NotNullByDefault
public final class StartupReporter {
    /// Process-wide reporter installed only in an authenticated protected child.
    private static final AtomicReference<@Nullable Session> ACTIVE = new AtomicReference<>();

    /// Prevents construction of the static reporting facade.
    private StartupReporter() {
    }

    /// Connects the protected child and publishes its initial JVM-started stage before Core initialization.
    ///
    /// @param endpoint opaque platform-local endpoint descriptor
    /// @param nonce session authentication nonce
    /// @throws IOException if connection, authentication setup, or the initial report fails
    static void connect(String endpoint, String nonce) throws IOException {
        Session session = new Session(
                ProtectorBootstrap.connectLocal(endpoint, ProtectorProtocol.CONNECT_TIMEOUT),
                nonce
        );
        if (!ACTIVE.compareAndSet(null, session)) {
            session.close();
            throw new IOException("Protector child reporter is already connected");
        }
        try {
            session.start();
        } catch (IOException failure) {
            ACTIVE.compareAndSet(session, null);
            session.close();
            throw failure;
        }
    }

    /// Reports successful launcher Core setup before JavaFX launch and plugin discovery.
    public static void reportCoreReady() {
        reportStage(ProtectorStage.CORE_READY, null, null);
    }

    /// Starts or advances the deadline for one active Runtime Provider.
    ///
    /// @param providerId canonical active Provider plugin ID
    public static void reportRuntimeProvider(String providerId) {
        reportStage(ProtectorStage.RUNTIME_PROVIDERS_LOADING, providerId, null);
    }

    /// Starts or advances the deadline for one active ordinary plugin.
    ///
    /// @param pluginId canonical active ordinary plugin ID
    public static void reportOrdinaryPlugin(String pluginId) {
        reportStage(ProtectorStage.ORDINARY_PLUGINS_LOADING, null, pluginId);
    }

    /// Renews only the current stage and active identity while heartbeat reporting remains active.
    public static void renewCurrentStage() {
        @Nullable Session session = ACTIVE.get();
        if (session != null) {
            session.sendCurrent(ProtectorMessage.Kind.LEASE_RENEWAL);
        }
    }

    /// Completes startup supervision only after the primary UI stage was actually shown.
    public static void reportUiReady() {
        @Nullable Session session = ACTIVE.get();
        if (session != null) {
            session.complete(ProtectorMessage.Kind.READY, ProtectorStage.UI_READY);
        }
    }

    /// Completes startup supervision without recovery after explicit user cancellation.
    public static void reportCancel() {
        @Nullable Session session = ACTIVE.get();
        if (session != null) {
            session.complete(ProtectorMessage.Kind.CANCEL, session.stage());
        }
    }

    /// Completes startup supervision after the launcher runtime returns normally before UI readiness.
    public static void reportNormalShutdown() {
        @Nullable Session session = ACTIVE.get();
        if (session != null) {
            session.complete(ProtectorMessage.Kind.NORMAL_SHUTDOWN, session.stage());
        }
    }

    /// Reports one stage transition when an authenticated child session is active.
    ///
    /// Test and non-entry invocations without an active Protector remain unaffected.
    ///
    /// @param stage new startup stage
    /// @param activeProviderId active Provider ID, or `null`
    /// @param activePluginId active ordinary plugin ID, or `null`
    private static void reportStage(
            ProtectorStage stage,
            @Nullable String activeProviderId,
            @Nullable String activePluginId
    ) {
        @Nullable Session session = ACTIVE.get();
        if (session != null) {
            session.transition(stage, activeProviderId, activePluginId);
        }
    }

    /// One connected child session with serialized output and independent reader and heartbeat workers.
    @NotNullByDefault
    private static final class Session {
        /// Owned platform-local parent connection.
        private final ProtectorBootstrap.LocalConnection connection;

        /// Authenticated child-to-parent encoder.
        private final ProtectorProtocol outboundProtocol;

        /// Authenticated parent-to-child decoder.
        private final ProtectorProtocol inboundProtocol;

        /// Current authenticated startup stage.
        private ProtectorStage stage = ProtectorStage.JVM_STARTED;

        /// Current active Provider ID, or `null`.
        private @Nullable String activeProviderId;

        /// Current active ordinary plugin ID, or `null`.
        private @Nullable String activePluginId;

        /// Last emitted child monotonic timestamp.
        private long lastTimestampNanos = -1L;

        /// Whether a terminal success, cancellation, or normal-shutdown report was sent.
        private boolean terminal;

        /// Whether parent-directed failure termination suppresses normal-shutdown reporting.
        private boolean failureTermination;

        /// Whether background workers should continue.
        private volatile boolean running = true;

        /// Creates one connected authenticated session.
        ///
        /// @param connection owned parent connection
        /// @param nonce session authentication nonce
        private Session(ProtectorBootstrap.LocalConnection connection, String nonce) {
            this.connection = connection;
            this.outboundProtocol = new ProtectorProtocol(nonce);
            this.inboundProtocol = new ProtectorProtocol(nonce);
        }

        /// Sends the initial stage and starts control, heartbeat, and normal-shutdown handling.
        ///
        /// @throws IOException if the initial report cannot be sent
        private void start() throws IOException {
            send(ProtectorMessage.Kind.STAGE, stage, null, null);
            Thread controlReader = new Thread(this::readControl, "HMCL Protector Request Reader");
            controlReader.setDaemon(true);
            controlReader.start();
            Thread heartbeat = new Thread(this::runHeartbeat, "HMCL Protector Heartbeat");
            heartbeat.setDaemon(true);
            heartbeat.start();
        }

        /// Transitions to one exact stage and active identity.
        ///
        /// @param nextStage next stage
        /// @param providerId active Provider ID, or `null`
        /// @param pluginId active ordinary plugin ID, or `null`
        private synchronized void transition(
                ProtectorStage nextStage,
                @Nullable String providerId,
                @Nullable String pluginId
        ) {
            if (terminal || failureTermination) {
                return;
            }
            try {
                send(ProtectorMessage.Kind.STAGE, nextStage, providerId, pluginId);
                stage = nextStage;
                activeProviderId = providerId;
                activePluginId = pluginId;
            } catch (IOException failure) {
                running = false;
            }
        }

        /// Sends one current-state operation.
        ///
        /// @param kind heartbeat or renewal operation
        private synchronized void sendCurrent(ProtectorMessage.Kind kind) {
            if (terminal || failureTermination) {
                return;
            }
            try {
                send(kind, stage, activeProviderId, activePluginId);
            } catch (IOException failure) {
                running = false;
            }
        }

        /// Sends one terminal operation and stops heartbeat production.
        ///
        /// @param kind ready or cancel operation
        /// @param terminalStage terminal message stage
        private synchronized void complete(ProtectorMessage.Kind kind, ProtectorStage terminalStage) {
            if (terminal || failureTermination) {
                return;
            }
            try {
                send(kind, terminalStage, null, null);
                stage = terminalStage;
                terminal = true;
            } catch (IOException failure) {
                running = false;
            } finally {
                running = false;
            }
        }

        /// Sends periodic current-state heartbeats until startup reaches a terminal state.
        private void runHeartbeat() {
            while (running) {
                try {
                    Thread.sleep(ProtectorProtocol.HEARTBEAT_INTERVAL.toMillis());
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (running) {
                    sendCurrent(ProtectorMessage.Kind.HEARTBEAT);
                }
            }
        }

        /// Reads and handles parent-only diagnostics and termination requests.
        private void readControl() {
            try {
                while (running) {
                    @Nullable String line = ProtectorProtocol.readLine(connection.input());
                    if (line == null) {
                        running = false;
                        return;
                    }
                    ProtectorMessage message = inboundProtocol.decode(line);
                    if (message.kind() == ProtectorMessage.Kind.DIAGNOSTICS_REQUEST) {
                        try {
                            captureDiagnostics();
                            sendControlResponse(ProtectorMessage.Kind.DIAGNOSTICS_RESPONSE);
                        } catch (IOException ignored) {
                            // Keep consuming control messages so diagnostic failure cannot block graceful termination.
                        }
                    } else if (message.kind() == ProtectorMessage.Kind.TERMINATION_REQUEST) {
                        synchronized (this) {
                            failureTermination = true;
                            running = false;
                        }
                        sendControlResponse(ProtectorMessage.Kind.TERMINATION_ACKNOWLEDGED);
                        System.exit(1);
                    } else {
                        throw new IOException("Parent sent an invalid Protector control operation");
                    }
                }
            } catch (IOException failure) {
                running = false;
            }
        }

        /// Writes one fixed launcher-local thread diagnostic without arguments, environment, or authentication data.
        ///
        /// @throws IOException if the diagnostic file cannot be created
        private void captureDiagnostics() throws IOException {
            Path diagnostic = Metadata.HMCL_LOCAL_HOME.resolve("diagnostics/protector-timeout.txt");
            Files.createDirectories(diagnostic.getParent());
            StringBuilder content = new StringBuilder();
            Thread.getAllStackTraces().entrySet().stream()
                    .sorted(Comparator.comparing(entry -> entry.getKey().getName()))
                    .forEach(entry -> appendThread(content, entry));
            Files.writeString(
                    diagnostic,
                    content,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
        }

        /// Appends one thread name, state, and stack without thread-local or process-secret data.
        ///
        /// @param content destination diagnostic text
        /// @param entry thread and captured stack
        private static void appendThread(
                StringBuilder content,
                Map.Entry<Thread, StackTraceElement[]> entry
        ) {
            content.append('"').append(entry.getKey().getName()).append("\" ")
                    .append(entry.getKey().getState()).append('\n');
            for (StackTraceElement element : entry.getValue()) {
                content.append("    at ").append(element).append('\n');
            }
            content.append('\n');
        }

        /// Sends a response to one parent control request using the current stage without active identities.
        ///
        /// @param kind diagnostics or termination acknowledgement
        /// @throws IOException if response writing fails
        private synchronized void sendControlResponse(ProtectorMessage.Kind kind) throws IOException {
            send(kind, stage, null, null);
        }

        /// Encodes and flushes one child message with a strictly increasing monotonic timestamp.
        ///
        /// @param kind control operation
        /// @param messageStage reported stage
        /// @param providerId active Provider ID, or `null`
        /// @param pluginId active ordinary plugin ID, or `null`
        /// @throws IOException if encoding or writing fails
        private void send(
                ProtectorMessage.Kind kind,
                ProtectorStage messageStage,
                @Nullable String providerId,
                @Nullable String pluginId
        ) throws IOException {
            long timestamp = Math.max(System.nanoTime(), lastTimestampNanos + 1L);
            ProtectorMessage message = new ProtectorMessage(kind, timestamp, messageStage, providerId, pluginId);
            connection.output().write(outboundProtocol.encode(message).getBytes(StandardCharsets.UTF_8));
            connection.output().flush();
            lastTimestampNanos = timestamp;
        }

        /// Returns the current authenticated startup stage.
        ///
        /// @return current stage
        private synchronized ProtectorStage stage() {
            return stage;
        }

        /// Closes the owned local connection after failed startup setup.
        private void close() {
            running = false;
            try {
                connection.close();
            } catch (IOException ignored) {
                // The setup failure remains authoritative.
            }
        }
    }
}
