# HMCL CE Online Plugin Certification Design

## Goals

- Certify a third-party plugin only when both its GitHub repository and the exact NPL release are approved.
- Re-verify approved repositories every seven days.
- Inspect and sign every newly published NPL independently.
- Let HMCL CE periodically fetch a signed online trust-status snapshot and react to repository or artifact revocation.
- Authenticate GitHub Actions with short-lived GitHub OIDC tokens instead of developer API keys.
- Keep all official signing private keys on the approval service or in its KMS/HSM boundary.

## Trust Roles

The offline root authorizes four separate online roles:

- `official-repository` signs the official plugin registry.
- `repository-attestor` signs repository verification attestations.
- `artifact-attestor` signs immutable NPL attestations.
- `trust-status` signs the current repository and artifact status snapshot.

Role keys are not interchangeable. HMCL CE rejects an otherwise valid Ed25519 signature when it was
made by a key from the wrong role. The root document contains the fixed HTTPS `statusUrl`; plugin
manifests cannot select a status server.

Each online role uses an exact threshold of one. Multiple distinct key IDs may coexist within one
role only to support rotation; one valid role signature is sufficient. A duplicate ID within a role,
or any key ID shared by two online roles, invalidates the root.

## Signed Documents

All signed documents use the existing envelope and canonical JSON v1 implementation:

```json
{
  "signed": {},
  "signatures": [
    { "keyId": "ed25519:...", "signature": "Base64..." }
  ]
}
```

The signed byte sequence is `<domain> + "\n" + HMCLCE-CANONICAL-JSON-V1(signed)`. Canonical JSON v1
accepts only exact integers in `[-9007199254740991, 9007199254740991]`; repository IDs, status
versions, and NPL byte sizes must also be positive.

### Repository Attestation

Domain: `HMCLCE-REPOSITORY-ATTESTATION-V1`.

The payload binds `_type`, schema version, normalized `owner/repository`, GitHub numeric repository
ID, default branch, exact plugin IDs, topics, verification status, check time, next check time,
policy version, source commit, and a unique verification ID. An approval remains current only while
the online status snapshot reports the same repository identity as approved.

HMCL CE obtains the current repository `verificationId` from the signed status snapshot and derives
`/api/v1/repositories/attestations/{verificationId}` against the root `statusUrl` origin. A manifest
field that attempts to provide a repository attestation URL is rejected.

### NPL Attestation

Domain: `HMCLCE-NPL-ATTESTATION-V1`.

The payload binds repository name and numeric ID, plugin ID, plugin version, Git tag, release asset
name and URL, exact SHA-256 and byte size, source commit, repository verification ID, approval time,
policy version, and approval job ID. A one-byte package change therefore requires a new approval.

### Trust-Status Snapshot

Domain: `HMCLCE-TRUST-STATUS-V1`.

The payload contains:

- `_type: trust-status-snapshot` and `schemaVersion: 1`;
- a strictly increasing integer `version`;
- `generatedAt`, `expires`, and `policyVersion`;
- the current status of every known repository, including its numeric ID, normalized name,
  verification ID, status, weekly verification times, and plugin IDs;
- explicitly revoked artifact SHA-256 values with plugin ID, version, revocation time, and reason code;
- revoked online attestation key IDs.

Each lowercase SHA-256 may appear only once in `revokedArtifacts`. Repeating a digest, even with
different plugin ID or version metadata, invalidates the whole snapshot.

The server emits a new atomic snapshot whenever a weekly check changes state, an administrator
suspends or restores a repository, or an artifact is revoked. Snapshot validity is at most 48 hours.

## GitHub Actions API

GitHub Actions requests an OIDC token with audience `hmclce-plugin-approval`. The approval service
validates the token against GitHub's JWKS and requires the expected issuer, audience, repository,
numeric repository ID, tag ref, workflow identity, source SHA, and unexpired time claims. The service
does not accept a repository name supplied only in JSON and does not issue long-lived developer API
keys.

The release workflow is:

1. Build and locally validate exactly one NPL.
2. Create a draft GitHub Release and upload the NPL.
3. Obtain a GitHub OIDC token.
4. Register or refresh the repository through the approval API.
5. Submit the draft release asset for NPL approval.
6. Poll the idempotent approval job.
7. Add the returned NPL attestation to the matching manifest version.
8. Upload the manifest and attestation, then publish the release.

The service downloads the NPL itself from the bound GitHub release asset. It never trusts a digest,
size, plugin ID, or version merely because the workflow submitted it.

## Repository Verification

Registration and weekly checks query GitHub directly and verify at least:

- the OIDC numeric repository ID and normalized name;
- repository availability and non-archived, non-disabled, non-fork state;
- the lowercase `hmclce` Topic;
- the default branch and immutable source commit;
- a bounded schema-v2 `manifest.json`;
- canonical plugin IDs and repository ownership;
- policy-required metadata and release provenance.

Automatic checks issue `approved`, `suspended`, or `revoked` state. A failed weekly check does not
silently extend the previous approval.

## NPL Verification

The worker downloads with strict HTTPS and GitHub host allowlists, bounded redirects, a 512 MiB
compressed limit, timeouts, and private-address rejection. It checks archive path safety, duplicate
entries, expansion limits, exactly one root `plugin.json`, schema version 4, type-specific entrypoint,
permissions, dependencies, launcher constraints, plugin ID, version, repository, tag, and source
commit. Signing occurs only after the server computes the package digest and size.

Approval jobs are idempotent for one repository ID, tag, asset identity, and digest. Publishing the
same plugin ID and version with different bytes is rejected.

## HMCL CE Verification

Trust is calculated for each manifest version, not once for the repository or plugin entry.

A community version has no certification declaration. A partially signed, mismatched, expired,
revoked, or role-confused declaration is `REJECTED`. A third-party version is `CERTIFIED` only when:

1. its NPL attestation signature and every bound manifest field are valid, including `v<version>` tag;
2. its referenced historical repository proof is valid and artifact approval occurred inside that proof's interval;
3. source commit and policy version match between the artifact and historical repository proof;
4. the fresh status snapshot identifies the same repository ID/name as approved, includes the plugin ID,
   and has a future `nextVerificationAt`;
5. neither proof's signer key ID nor the artifact digest is revoked, and the downloaded NPL digest and
   size match both manifest and attestation.

The status snapshot's current verification ID does not need to equal the historical ID referenced by
an already approved artifact. This allows weekly repository re-verification without re-signing every
unchanged historical NPL.

On certified installation HMCL CE persists both complete signed envelopes as an artifact-bound
receipt. The NPL, permission decision, launcher state, and receipt are published or rolled back as one
transaction; unsigned replacement and uninstall remove the prior receipt. Before execution, the
receipt is re-verified against the installed ID, version, SHA-256, and byte size. Ordinary plugins are
gated before class-loader creation. Mixin plugins are gated before classpath attachment and again
before transformer registration.

An official signed-registry entry may retain `OFFICIAL`, but the official registry cannot grant a
third-party artifact certification by setting a Boolean flag.

## Online Refresh and Rollback Protection

Launcher plugin-system initialization starts asynchronous refresh even if the user never opens the
plugin store. HMCL CE then refreshes at store entry or when due, no more often than every six hours
after an authenticated `200` or a `304` whose authenticated cache remains within its signed validity
interval. A `304` cannot revive an expired snapshot. It uses ETag/`If-None-Match`, bounded HTTPS
responses, and atomic cache replacement. Failures retry after approximately five minutes, thirty
minutes, and then one hour at the cap, with stable per-instance `+/-10%` jitter. A one-minute
scheduler checks whether the current delay is due, while synchronized due checks coalesce concurrent
startup and store-entry triggers. It persists the highest accepted snapshot version and generation
time. A lower version, earlier generation time, invalid signature, excessive validity window, or
expired snapshot is rejected and cannot replace the last valid cache.

A fresh cached snapshot supports offline use until its signed expiry. An expired or unavailable
snapshot cannot grant a certification badge or authorize a new certified install/update. It is
reported as stale rather than falsely reported as revoked. Explicit repository, artifact, and signer
key revocations in the latest authenticated cache remain effective even after expiry. Runtime gates
re-read that cache, so a refresh completed during the current process immediately blocks any affected
certified plugin that has not crossed its loading boundary.

## Revocation

Repository revocation invalidates every third-party artifact from that repository. Artifact
revocation targets one unique lowercase SHA-256 and leaves unrelated releases unaffected. Online
key-ID revocation blocks every repository or artifact proof produced by a compromised attestation
key, whose ID is already present in each signature record. Every change is audited with actor,
action, target, reason code, previous state, new state, and timestamp.

## Failure Rules

- Unknown algorithms, domains, roles, schema versions, and critical fields fail closed.
- Manifest-provided status origins are ignored and rejected.
- Network failures preserve the last valid cache and never fabricate approval.
- Snapshot rollback and freeze attempts do not replace stored state.
- Repository renames require numeric-ID continuity and a newly issued attestation.
- Signature-service or database failures leave a draft release unpublished.
- Secrets, OIDC tokens, private keys, and bearer-bearing URLs never enter logs.

## Testing

Tests cover role separation, canonicalization, repository/NPL field binding, one-byte mutation,
cross-version substitution, weekly expiry, repository and artifact revocation, snapshot rollback,
freeze/expiry, fresh and stale ETag 304 behavior, bounded failure backoff, concurrent refresh
coalescing, atomic cache recovery, fixed status URL, offline fresh/stale behavior, receipt lifecycle
and rollback, both runtime loading gates, OIDC claim confusion, SSRF redirects, ZIP bombs and
traversal, idempotent jobs, concurrent claims, and draft-release failure handling.
