# HMCL CE Plugin Discovery and Trust Design

## Goals

- Discover community plugin repositories through the GitHub Topic `hmclce`.
- Keep the official registry independent from GitHub Topic discovery.
- Derive official certification from cryptographic verification, never from remote Boolean fields.
- Allow unsigned community plugins while clearly separating them from certified plugins.
- Reject signed content that fails validation instead of silently downgrading it to unsigned content.
- Preserve the existing package size, SHA-256, metadata, permission, dependency, and compatibility checks.

## Trust Architecture

The trust hierarchy uses Ed25519 with role separation:

- An offline root authorizes independent online role keys and root rotation.
- `official-repository` signs only the official registry.
- `repository-attestor` signs weekly repository inspection records.
- `artifact-attestor` signs one immutable NPL release asset after server-side inspection.
- `trust-status` signs the short-lived current repository and revocation snapshot.

Production builds require all four roles to use disjoint key IDs. The root also pins one HTTPS
`statusUrl`. Repository and artifact proof URLs are derived from that root-controlled origin; a
manifest cannot redirect HMCL CE to another trust service.

Every online role has an exact threshold of one. A role may list multiple distinct key IDs during
rotation, but one valid role signature is sufficient; the list is not a multi-signature quorum.
Duplicate key IDs within a role and any overlap between online roles invalidate the root.

The initial root metadata is embedded in HMCL CE. The public root metadata may also be supplied to
build and validation workflows through repository variables. Root private keys never enter GitHub
Variables, GitHub Secrets, or an online build environment.

## Repository Sources

HMCL CE has two built-in source classes:

1. The official source uses a fixed registry URL and signed repository metadata. It does not use
   GitHub Topic discovery.
2. The community source searches the GitHub API for repositories carrying the lowercase Topic
   `hmclce`, then reads `manifest.json` from each repository's default branch.

User-configured JSON registries remain supported as community sources.

## Trust Results

Each discovered plugin receives one locally derived trust result:

- `OFFICIAL`: loaded from a valid official repository snapshot.
- `CERTIFIED`: the selected version has both a valid repository proof and exact NPL attestation,
  while the current signed status snapshot still approves that repository.
- `COMMUNITY`: the manifest contains no signing declaration.
- `REJECTED`: signing material is present but malformed, expired, revoked, mismatched, or invalid.

Certified plugins display an official certification badge and skip the repository-origin warning.
They continue through normal permission confirmation. Community plugins remain installable after
source confirmation. Rejected plugins cannot be installed.

Trust is calculated per version, not once per repository entry. When plugin IDs collide, source
selection uses `OFFICIAL`, `CERTIFIED`, then `COMMUNITY` priority.
Equal-priority conflicts are reported and never silently overwrite each other.

## Signed Envelopes

Signed documents use a common JSON envelope:

```json
{
  "signed": {},
  "signatures": [
    {
      "keyId": "ed25519:...",
      "signature": "Base64..."
    }
  ]
}
```

The signature input is the UTF-8 encoding of:

```text
<domain> + "\n" + HMCLCE-CANONICAL-JSON-V1(signed)
```

The canonical JSON v1 subset sorts object keys by Unicode code-unit order, emits minimal string
escapes, preserves array order, and only accepts exact integral JSON numbers in the inclusive
JavaScript-safe range `[-9007199254740991, 9007199254740991]`. Repository IDs, status versions, and
NPL byte sizes are additionally positive. Domain separation prevents signatures from being reused
for another document type. Implemented online domains cover weekly repository attestations,
immutable NPL attestations, status snapshots, and the official registry.

A weekly repository attestation binds the numeric GitHub repository ID, normalized repository name,
default branch, Topic, plugin ID scope, source commit, policy version, inspection interval, and
verification ID. An NPL attestation binds one plugin ID/version, `v<version>` tag, release asset URL,
SHA-256, exact size, source commit, policy version, approval job, and the repository verification ID
used during approval.

Weekly re-verification produces a new verification ID without invalidating historical NPL proofs.
HMCL CE verifies an artifact against its referenced historical repository proof and requires the
artifact approval time to fall inside that proof's interval. It separately requires the fresh status
snapshot to approve the same numeric repository/name/plugin scope with a future `nextVerificationAt`.
The status snapshot treats each lowercase artifact SHA-256 as a unique revocation key. Repeating one
digest, including with different plugin ID or version metadata, invalidates the complete snapshot.

## Official Metadata

The official repository publishes a signed `plugins.json` envelope. Every entry in its signed
payload contains `manifestSha256`, the lowercase SHA-256 of the exact remote manifest bytes. HMCL CE
checks that digest before parsing the manifest. The manifest then binds each package URL, package
SHA-256, and exact package size.

The trust root is injected into HMCL CE releases through the public Repository Variable
`HMCLCE_PLUGIN_ROOT_JSON`. It includes expiry, the fixed status URL, role thresholds, and public
keys. Private signing keys never enter repository variables or launcher binaries. Tagged builds fail
when the variable is absent, uses a non-HTTPS status URL, omits a required online role, references an
unknown key, has a threshold other than one, repeats a key ID within one role, or reuses one key
across roles.

## GitHub Discovery

Discovery queries `topic:hmclce` through the GitHub repository search API. Archived, disabled, and
forked repositories are excluded by default. Search pages and manifest responses are strictly bounded.
For every accepted repository, HMCL CE fetches `manifest.json` from its reported default branch.
The repository identity used for trust verification comes from the GitHub API result, not a
self-declared manifest value.

Anonymous API access is supported. An optional GitHub token can raise rate limits, but is sent only
to GitHub API endpoints and never to manifests, package hosts, or README hosts.

## Publishing Workflow

Certified developers do not receive or store an official signing private key. A tag workflow uses a
short-lived GitHub OIDC token whose claims bind the repository ID, tag ref, workflow, and source
commit.

On a version tag, the SDK workflow:

1. builds and locally validates the NPL package;
2. creates a draft release and uploads the immutable asset;
3. submits only the GitHub asset ID plus plugin ID/version to the approval API with an idempotency key;
4. polls the approval job;
5. lets the service download the asset and independently derive URL, SHA-256, size, metadata, and source commit;
6. inserts the returned attestation into only the matching manifest version;
7. publishes the release and updates the default-branch manifest.

The official store uses the narrowly scoped `HMCLCE_OFFICIAL_REPOSITORY_SIGNING_KEY` secret. The
matching public key and key ID are Repository Variables. This key cannot sign repository or artifact
attestations, status snapshots, or rotate root metadata.

## Installed Receipt and Runtime Gates

A certified installation persists the complete artifact and repository envelopes in a receipt bound
to the installed plugin ID, version, NPL SHA-256, byte size, and numeric repository ID. Package,
permission, launcher-state, and receipt documents share one recoverable transaction. Unsigned
replacement and uninstall remove the old receipt; any publication failure restores every document
and package together.

HMCL CE re-verifies the receipt and exact installed NPL before executable code crosses a loading
boundary. Ordinary Java/Kotlin plugins are checked before their class loader is created. Mixin
plugins are checked before any classpath attachment and again immediately before transformer
registration. Each gate reads the latest authenticated status snapshot, so a refresh in the current
process can stop a certified plugin that has not loaded yet. Stale snapshots cannot grant new
certification, while their explicit repository, artifact, and signer-key revocations remain blocking.

Launcher plugin-system initialization starts the asynchronous refresh scheduler even when the user
never opens the plugin store. After an authenticated `200`, or a `304` whose authenticated cache is
still inside its signed validity interval, refresh is suppressed for six hours. A `304` cannot revive
an expired snapshot. Failures retry on approximately five-minute, thirty-minute, and one-hour capped
stages with stable per-instance `+/-10%` jitter. A one-minute scheduler checks due time, and
synchronized due checks coalesce startup and store-entry triggers into one network request.

## Failure Handling

- Missing certification material produces `COMMUNITY`.
- Partial or invalid certification material produces `REJECTED`.
- Unknown algorithms, roles, schema versions, or critical fields fail closed.
- A stale status snapshot cannot grant certification, but explicit cached repository, artifact, and signer-key
  revocations still block loading.
- Repository or plugin ID mismatches produce `REJECTED`.
- GitHub rate limiting reports a source diagnostic without changing repository identity rules.
- Official index signature failure, manifest hash mismatch, invalid role threshold, or embedded root
  expiry prevents official trust.

Logs contain key IDs, verification IDs, and credential-safe repository identities. They never
contain private keys, tokens, signatures used as bearer data, or credential-bearing URLs.

## Testing

Unit tests cover canonicalization and safe-integer bounds, Ed25519 verification, threshold signatures,
role separation, expiry, key/artifact/repository revocation, repository binding, plugin ID binding,
downgrade resistance, source priority, equal-priority conflicts, and official manifest byte pins. HTTP
fixture tests cover Topic pagination, default branches, fork/archive filtering, bounded responses,
credential isolation, refresh backoff, and concurrent refresh coalescing. Store, runtime-gate,
transaction, and SDK tests verify that signed fixtures pass, one-byte mutations fail, receipts roll
back atomically, and revoked code never reaches a loading boundary.
