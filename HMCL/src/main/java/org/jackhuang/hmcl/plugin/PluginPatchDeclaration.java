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

import com.google.gson.annotations.SerializedName;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/// One declarative method patch contributed by a schema-v5 plugin.
///
/// These declarations define the schema-v5 patch contract. HMCL currently parses and validates them when the
/// manifest loads, but the JVM-side engine that would apply their bytecode transformations is not implemented.
@NotNullByDefault
public final class PluginPatchDeclaration {
    /// Pattern accepted for fully-qualified binary class names such as org.hmcl.core.GameLaunchService.
    private static final Pattern TARGET_PATTERN = Pattern.compile(
            "[a-zA-Z_$][a-zA-Z0-9_$]*(\\.[a-zA-Z_$][a-zA-Z0-9_$]*)+");

    /// Pattern accepted for Java method names.
    private static final Pattern METHOD_PATTERN = Pattern.compile("[a-zA-Z_$][a-zA-Z0-9_$]*");

    /// Fully-qualified launcher class whose method is patched.
    @SerializedName("target")
    private @Nullable String target;

    /// Name of the patched method.
    @SerializedName("method")
    private @Nullable String method;

    /// Where the plugin callback runs relative to the original method body.
    @SerializedName("type")
    private @Nullable PatchType type;

    /// Ordered method parameter descriptors used to distinguish overloads.
    @SerializedName("parameters")
    private @Nullable List<@Nullable String> parameters;

    /// Creates an empty declaration for Gson deserialization.
    public PluginPatchDeclaration() {
    }

    /// Creates a validated declaration programmatically.
    ///
    /// @param target fully-qualified launcher class
    /// @param method patched method name
    /// @param type callback position relative to the original body
    /// @param parameters ordered method parameter descriptors, or an empty list for a no-argument method
    public PluginPatchDeclaration(String target, String method, PatchType type, List<String> parameters) {
        this.target = target;
        this.method = method;
        this.type = type;
        this.parameters = List.copyOf(parameters);
        validate();
    }

    /// Validates the declaration, throwing on malformed entries.
    ///
    /// @throws IllegalArgumentException when target, method, type, or parameters are missing or invalid
    public void validate() {
        if (target == null || !TARGET_PATTERN.matcher(target).matches()) {
            throw new IllegalArgumentException("Invalid patch target class: " + target);
        }
        if (method == null || !METHOD_PATTERN.matcher(method).matches()) {
            throw new IllegalArgumentException("Invalid patch method name: " + method);
        }
        if (type == null) {
            throw new IllegalArgumentException("Missing patch type for " + target + "." + method);
        }
        if (parameters == null) {
            throw new IllegalArgumentException("Missing patch parameters for " + target + "." + method);
        }
        for (@Nullable String parameter : parameters) {
            if (parameter == null || parameter.isBlank()) {
                throw new IllegalArgumentException("Patch parameter cannot be null or blank for "
                        + target + "." + method);
            }
        }
    }

    /// Returns the fully-qualified launcher class whose method is patched.
    public String getTarget() {
        return java.util.Objects.requireNonNull(target);
    }

    /// Returns the name of the patched method.
    public String getMethod() {
        return java.util.Objects.requireNonNull(method);
    }

    /// Returns where the plugin callback runs relative to the original method body.
    public PatchType getType() {
        return Objects.requireNonNull(type);
    }

    /// Returns the ordered parameter descriptors that identify the patched overload.
    ///
    /// @return immutable ordered parameter descriptors
    /// @throws IllegalStateException if the parameter declaration is missing from an unvalidated declaration
    public @Unmodifiable List<String> getParameters() {
        @Nullable List<@Nullable String> values = parameters;
        if (values == null) {
            throw new IllegalStateException("Patch declaration has no parameters");
        }
        if (values.isEmpty()) {
            return List.of();
        }
        return values.stream().map(Objects::requireNonNull).toList();
    }

    /// Compares the complete patched method identity and callback position.
    ///
    /// @param other comparison target
    /// @return whether both declarations describe the same ordered patch
    @Override
    public boolean equals(@Nullable Object other) {
        return this == other
                || other instanceof PluginPatchDeclaration declaration
                && getTarget().equals(declaration.getTarget())
                && getMethod().equals(declaration.getMethod())
                && getType() == declaration.getType()
                && getParameters().equals(declaration.getParameters());
    }

    /// Returns a hash derived from the complete ordered patch identity.
    ///
    /// @return patch declaration hash
    @Override
    public int hashCode() {
        return Objects.hash(getTarget(), getMethod(), getType(), getParameters());
    }

    /// Callback position of a patch relative to the original method body.
    @NotNullByDefault
    public enum PatchType {
        /// Requests a callback before the original body with access to the invocation context.
        @SerializedName("before")
        BEFORE,

        /// Requests a callback after the original body with access to the result.
        @SerializedName("after")
        AFTER,

        /// Requests replacement of the original body; the strongest form of patch declaration.
        @SerializedName("replace")
        REPLACE
    }
}
