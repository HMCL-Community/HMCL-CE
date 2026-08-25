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
package org.jackhuang.hmcl.plugin.runtime;

import org.jackhuang.hmcl.plugin.PluginArtifactIdentity;
import org.jackhuang.hmcl.plugin.PluginDataObject;
import org.jackhuang.hmcl.plugin.PluginHookEvent;
import org.jackhuang.hmcl.plugin.PluginHookPoint;
import org.jackhuang.hmcl.plugin.PluginHookResult;
import org.jackhuang.hmcl.plugin.PluginPatchDeclaration;
import org.jackhuang.hmcl.plugin.PluginPermission;
import org.jackhuang.hmcl.plugin.PluginSecretAccess;
import org.jackhuang.hmcl.plugin.bridge.PluginCapabilitySession;
import org.jackhuang.hmcl.plugin.bridge.PluginCapabilityToken;
import org.jackhuang.hmcl.plugin.bridge.PluginPermissionAuthority;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies external Hook and reserved Patch endpoints at the plugin-scoped authority boundary.
@NotNullByDefault
public final class RuntimeHookEndpointTest {
    /// Exact test payload identity used by valid token grants.
    private static final PluginArtifactIdentity PAYLOAD_IDENTITY = new PluginArtifactIdentity(
            "dev.test.external",
            "1.0.0-next",
            "a".repeat(64)
    );

    /// Exact callback domain owned by runtime payload capability sessions.
    private static final String CALLBACK_DOMAIN = "runtime.payload";

    /// Passes the exact immutable event, timeout, and plugin-scoped token to the selected Provider.
    @Test
    public void invokeSelectedProviderWithExactEventDeadlineAndAuthority() throws Exception {
        PluginPermissionAuthority authority = new PluginPermissionAuthority();
        PluginCapabilitySession session = authority.openSession(
                PAYLOAD_IDENTITY,
                PluginExecutionMode.EMBEDDED,
                () -> Set.of(PluginPermission.LAUNCHER_HOOK),
                CALLBACK_DOMAIN,
                Duration.ofSeconds(30)
        );
        PluginHookEvent event = event();
        Duration timeout = Duration.ofMillis(275);
        AtomicReference<PluginCapabilityToken> receivedToken = new AtomicReference<>();
        RuntimeHookEndpoint endpoint = new RuntimeHookEndpoint(
                PAYLOAD_IDENTITY,
                PluginExecutionMode.EMBEDDED,
                authority,
                session::issue,
                (ownerPluginId, token, receivedEvent, receivedTimeout) -> {
                    assertEquals(PAYLOAD_IDENTITY.getPluginId(), ownerPluginId);
                    assertSame(event, receivedEvent);
                    assertEquals(timeout, receivedTimeout);
                    authority.requirePermission(
                            token,
                            ownerPluginId,
                            PAYLOAD_IDENTITY,
                            PluginExecutionMode.EMBEDDED,
                            PluginPermission.LAUNCHER_HOOK,
                            CALLBACK_DOMAIN
                    );
                    receivedToken.set(token);
                    return PluginHookResult.cancel("provider-policy", "Provider cancelled launch");
                }
        );

        PluginHookResult result = endpoint.invoke(event, timeout);

        assertEquals(PluginHookResult.Action.CANCEL, result.action());
        assertTrue(receivedToken.get() != null);
        assertThrows(SecurityException.class, () -> authority.requirePermission(
                receivedToken.get(),
                PAYLOAD_IDENTITY.getPluginId(),
                PAYLOAD_IDENTITY,
                PluginExecutionMode.EMBEDDED,
                PluginPermission.LAUNCHER_HOOK,
                CALLBACK_DOMAIN
        ));
    }

    /// Rejects a shared Host that presents another dependent plugin's token before invoking Provider code.
    @Test
    public void rejectCrossPluginTokenReuseBySharedProvider() {
        PluginPermissionAuthority authority = new PluginPermissionAuthority();
        PluginArtifactIdentity otherIdentity = new PluginArtifactIdentity(
                "dev.test.other",
                "1.0.0-next",
                "b".repeat(64)
        );
        PluginCapabilityToken stolen = authority.issue(
                otherIdentity,
                PluginExecutionMode.EMBEDDED,
                Set.of(PluginPermission.LAUNCHER_HOOK),
                CALLBACK_DOMAIN,
                Duration.ofSeconds(30)
        );
        AtomicBoolean providerInvoked = new AtomicBoolean();
        RuntimeHookEndpoint endpoint = new RuntimeHookEndpoint(
                PAYLOAD_IDENTITY,
                PluginExecutionMode.EMBEDDED,
                authority,
                () -> stolen,
                (ownerPluginId, token, event, timeout) -> {
                    providerInvoked.set(true);
                    return PluginHookResult.unchanged();
                }
        );

        assertThrows(SecurityException.class,
                () -> endpoint.invoke(event(), Duration.ofSeconds(1)));
        assertEquals(false, providerInvoked.get());
    }

    /// Stops callback authority immediately when the owning payload session generation is suspended.
    @Test
    public void rejectHookAfterCapabilitySessionSuspension() throws Exception {
        PluginPermissionAuthority authority = new PluginPermissionAuthority();
        PluginCapabilitySession session = authority.openSession(
                PAYLOAD_IDENTITY,
                PluginExecutionMode.EMBEDDED,
                () -> Set.of(PluginPermission.LAUNCHER_HOOK),
                CALLBACK_DOMAIN,
                Duration.ofSeconds(30)
        );
        RuntimeHookEndpoint endpoint = new RuntimeHookEndpoint(
                PAYLOAD_IDENTITY,
                PluginExecutionMode.EMBEDDED,
                authority,
                session::issue,
                (ownerPluginId, token, event, timeout) -> PluginHookResult.unchanged()
        );
        assertEquals(PluginHookResult.Action.UNCHANGED,
                endpoint.invoke(event(), Duration.ofSeconds(1)).action());

        session.suspend();

        assertThrows(IllegalStateException.class,
                () -> endpoint.invoke(event(), Duration.ofSeconds(1)));
    }

    /// Validates declared Patch ownership and permission before returning the Stage-1 fail-closed status.
    @Test
    public void retainDeclaredPatchRegistrationAsEngineUnavailable() {
        PluginPermissionAuthority authority = new PluginPermissionAuthority();
        PluginPatchDeclaration declaration = patch("launch");
        PluginCapabilitySession session = authority.openSession(
                PAYLOAD_IDENTITY,
                PluginExecutionMode.EMBEDDED,
                () -> Set.of(PluginPermission.LAUNCHER_PATCH),
                CALLBACK_DOMAIN,
                Duration.ofSeconds(30)
        );
        AtomicReference<PluginCapabilityToken> issuedToken = new AtomicReference<>();
        RuntimePatchEndpoint endpoint = new RuntimePatchEndpoint(
                PAYLOAD_IDENTITY,
                PluginExecutionMode.EMBEDDED,
                authority,
                () -> {
                    PluginCapabilityToken token = session.issue();
                    issuedToken.set(token);
                    return token;
                },
                List.of(declaration)
        );

        assertEquals(
                RuntimePatchEndpoint.RegistrationStatus.PATCH_ENGINE_UNAVAILABLE,
                endpoint.register(declaration)
        );
        assertEquals(List.of(declaration), endpoint.declarations());
        assertThrows(IllegalArgumentException.class, () -> endpoint.register(patch("undeclared")));
        assertThrows(SecurityException.class, () -> authority.requirePermission(
                issuedToken.get(),
                PAYLOAD_IDENTITY.getPluginId(),
                PAYLOAD_IDENTITY,
                PluginExecutionMode.EMBEDDED,
                PluginPermission.LAUNCHER_PATCH,
                CALLBACK_DOMAIN
        ));
    }

    /// Rejects a Patch registration when its session token belongs to another external payload.
    @Test
    public void rejectCrossPluginPatchTokenReuse() {
        PluginPermissionAuthority authority = new PluginPermissionAuthority();
        PluginPatchDeclaration declaration = patch("launch");
        PluginArtifactIdentity otherIdentity = new PluginArtifactIdentity(
                "dev.test.other",
                "1.0.0-next",
                "b".repeat(64)
        );
        PluginCapabilityToken stolen = authority.issue(
                otherIdentity,
                PluginExecutionMode.EMBEDDED,
                Set.of(PluginPermission.LAUNCHER_PATCH),
                CALLBACK_DOMAIN,
                Duration.ofSeconds(30)
        );
        RuntimePatchEndpoint endpoint = new RuntimePatchEndpoint(
                PAYLOAD_IDENTITY,
                PluginExecutionMode.EMBEDDED,
                authority,
                () -> stolen,
                List.of(declaration)
        );

        assertThrows(SecurityException.class, () -> endpoint.register(declaration));
    }

    /// Creates one immutable Hook event fixture.
    ///
    /// @return immutable test event
    private static PluginHookEvent event() {
        return new PluginHookEvent(
                PluginHookEvent.CURRENT_CONTRACT_VERSION,
                "runtime-endpoint-test",
                PluginHookPoint.BEFORE_GAME_LAUNCH,
                Instant.EPOCH,
                PluginDataObject.empty(),
                PluginSecretAccess.denied(PAYLOAD_IDENTITY.getPluginId())
        );
    }

    /// Creates one valid overload-specific Patch declaration.
    ///
    /// @param method target method name
    /// @return validated declaration
    private static PluginPatchDeclaration patch(String method) {
        return new PluginPatchDeclaration(
                "org.jackhuang.hmcl.test.PatchTarget",
                method,
                PluginPatchDeclaration.PatchType.BEFORE,
                List.of("java.lang.String")
        );
    }
}
