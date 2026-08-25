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
package org.jackhuang.hmcl.plugin.bridge;

import org.jackhuang.hmcl.plugin.PluginManifest;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// Owns generation-safe opaque JVM references and scopes every lookup to the authority's plugin owner.
///
/// @param <T> opaque authority type whose owner is resolved by the injected verifier
@NotNullByDefault
public final class BridgeHandleRegistry<T> {
    /// Release action used for references that need no explicit cleanup.
    private static final Runnable NO_RELEASE = () -> {
    };

    /// Derives an already authenticated plugin owner from an opaque authority.
    private final OwnerVerifier<T> ownerVerifier;

    /// Live entries keyed only by registry-local numeric slot.
    private final Map<Long, Entry> entries = new HashMap<>();

    /// Current generation for every allocated or reusable numeric slot.
    private final Map<Long, Long> generations = new HashMap<>();

    /// Revoked numeric slots eligible for generation-safe reuse.
    private final ArrayDeque<Long> reusableIds = new ArrayDeque<>();

    /// Next never-before-used positive numeric slot.
    private long nextId = 1L;

    /// Creates an empty registry backed by an authority-to-owner verifier.
    ///
    /// Task 7 supplies the capability-token authority through this narrow boundary.
    ///
    /// @param ownerVerifier verifier that rejects invalid authority and returns its canonical plugin owner
    public BridgeHandleRegistry(OwnerVerifier<T> ownerVerifier) {
        this.ownerVerifier = Objects.requireNonNull(ownerVerifier, "ownerVerifier");
    }

    /// Registers one opaque JVM reference without an explicit release action.
    ///
    /// @param ownerPluginId canonical owner plugin ID
    /// @param type language-neutral type descriptor
    /// @param reference referenced JVM object
    /// @return opaque owner-free handle
    public BridgeHandle register(String ownerPluginId, String type, Object reference) {
        return register(ownerPluginId, type, reference, NO_RELEASE);
    }

    /// Registers one opaque JVM reference and its idempotent release action.
    ///
    /// @param ownerPluginId canonical owner plugin ID
    /// @param type language-neutral type descriptor
    /// @param reference referenced JVM object
    /// @param release action invoked once after the handle is invalidated
    /// @return opaque owner-free handle
    public synchronized BridgeHandle register(
            String ownerPluginId,
            String type,
            Object reference,
            Runnable release
    ) {
        requireOwnerId(ownerPluginId);
        String validatedType = BridgeHandle.requireValidType(type);
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(release, "release");

        long id = allocateId();
        long generation = generations.computeIfAbsent(id, ignored -> 1L);
        BridgeHandle handle = new BridgeHandle(id, generation, validatedType);
        entries.put(id, new Entry(ownerPluginId, handle, reference, release));
        return handle;
    }

    /// Resolves one opaque handle after authority owner, generation, and type validation.
    ///
    /// @param authority opaque authority verified by the injected owner resolver
    /// @param handle presented handle
    /// @param expectedType operation-required type descriptor
    /// @return referenced JVM object
    /// @throws BridgeError when authority, generation, owner, or type validation fails
    public Object resolve(T authority, BridgeHandle handle, String expectedType) throws BridgeError {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(expectedType, "expectedType");
        synchronized (this) {
            Entry entry = entries.get(handle.id());
            if (entry == null || entry.handle().generation() != handle.generation()) {
                throw BridgeError.of(BridgeError.Category.STALE_HANDLE);
            }
            String authorityOwner = requireVerifiedOwner(authority, entry.ownerPluginId());
            if (!entry.ownerPluginId().equals(authorityOwner)) {
                throw BridgeError.of(BridgeError.Category.PERMISSION_DENIED);
            }
            if (!entry.handle().type().equals(handle.type()) || !entry.handle().type().equals(expectedType)) {
                throw BridgeError.of(BridgeError.Category.TYPE_MISMATCH);
            }
            return entry.reference();
        }
    }

    /// Reports whether one exact slot generation remains live without resolving its JVM reference.
    ///
    /// @param handle opaque handle
    /// @return whether the exact generation is live
    public synchronized boolean isLive(BridgeHandle handle) {
        Objects.requireNonNull(handle, "handle");
        Entry entry = entries.get(handle.id());
        return entry != null && entry.handle().equals(handle);
    }

    /// Counts live references owned by one plugin for lifecycle diagnostics.
    ///
    /// @param ownerPluginId canonical owner plugin ID
    /// @return live reference count
    public synchronized int liveCount(String ownerPluginId) {
        requireOwnerId(ownerPluginId);
        return (int) entries.values().stream()
                .filter(entry -> entry.ownerPluginId().equals(ownerPluginId))
                .count();
    }

    /// Invalidates every owner handle before releasing any corresponding JVM reference.
    ///
    /// All release actions are attempted even if one fails. Any cleanup failure is reported only as a redacted
    /// internal Bridge error after the remaining references have been released.
    ///
    /// @param ownerPluginId canonical owner plugin ID
    /// @throws BridgeError after complete cleanup when one or more release actions fail
    public void revokeOwner(String ownerPluginId) throws BridgeError {
        requireOwnerId(ownerPluginId);
        List<Entry> revoked;
        synchronized (this) {
            revoked = entries.values().stream()
                    .filter(entry -> entry.ownerPluginId().equals(ownerPluginId))
                    .toList();

            // Advance every generation first so release callbacks can never observe a partially live owner.
            for (Entry entry : revoked) {
                invalidate(entry);
            }
            for (Entry entry : revoked) {
                entries.remove(entry.handle().id());
                if (generations.get(entry.handle().id()) > 0L) {
                    reusableIds.addLast(entry.handle().id());
                }
            }
        }

        boolean releaseFailed = false;
        for (Entry entry : revoked) {
            try {
                entry.release().run();
            } catch (RuntimeException | Error exception) {
                releaseFailed = true;
            }
        }
        if (releaseFailed) {
            throw BridgeError.of(BridgeError.Category.INTERNAL);
        }
    }

    /// Allocates one new or generation-advanced numeric slot.
    ///
    /// @return positive numeric slot
    private long allocateId() {
        if (!reusableIds.isEmpty()) {
            return reusableIds.removeFirst();
        }
        if (nextId <= 0L) {
            throw BridgeError.of(BridgeError.Category.UNAVAILABLE);
        }
        return nextId++;
    }

    /// Advances one handle generation before its entry is removed.
    ///
    /// A slot whose generation is exhausted is marked negative and never reused.
    ///
    /// @param entry live owner entry
    private void invalidate(Entry entry) {
        long generation = entry.handle().generation();
        generations.put(entry.handle().id(), generation == Long.MAX_VALUE ? -1L : generation + 1L);
    }

    /// Verifies the opaque authority and normalizes verifier failures to the permission-denied category.
    ///
    /// @param authority opaque authority
    /// @return canonical authority owner
    private String requireVerifiedOwner(T authority, String expectedOwnerPluginId) {
        try {
            String owner = ownerVerifier.requireOwner(
                    Objects.requireNonNull(authority, "authority"),
                    expectedOwnerPluginId
            );
            requireOwnerId(owner);
            return owner;
        } catch (BridgeError error) {
            throw error;
        } catch (RuntimeException | Error exception) {
            throw BridgeError.of(BridgeError.Category.PERMISSION_DENIED);
        }
    }

    /// Validates one canonical executable plugin ID.
    ///
    /// @param ownerPluginId candidate owner ID
    private static void requireOwnerId(String ownerPluginId) {
        if (!PluginManifest.isCanonicalExecutableId(ownerPluginId)) {
            throw new IllegalArgumentException("Bridge handle owner must be a canonical plugin ID");
        }
    }

    /// Resolves an opaque authority to its authenticated plugin owner.
    ///
    /// Task 7 implements this interface with the plugin capability authority rather than trusting Host input.
    ///
    /// @param <T> opaque authority type
    @FunctionalInterface
    @NotNullByDefault
    public interface OwnerVerifier<T> {
        /// Verifies one authority and returns its canonical plugin owner.
        ///
        /// @param authority opaque authority
        /// @return canonical authenticated owner plugin ID
        String requireOwner(T authority, String expectedOwnerPluginId);
    }

    /// Stores one JVM reference and its owner privately behind an owner-free handle.
    ///
    /// @param ownerPluginId canonical owner plugin ID
    /// @param handle exact live handle generation
    /// @param reference referenced JVM object
    /// @param release post-invalidation cleanup action
    @NotNullByDefault
    private record Entry(String ownerPluginId, BridgeHandle handle, Object reference, Runnable release) {
    }
}
