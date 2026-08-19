# HMCL CE Online Plugin Certification Implementation Plan

**Goal:** Implement repository-weekly and per-NPL certification with online signed revocation state,
a GitHub Actions OIDC API, and a developer/admin web console.

**Projects:** `HMCL-CE`, `HMCL-CE-Plugin-Approval`, `HMCL-CE-Plugin-SDK`, and
`HMCL-CE-Plugin-Store`.

## Workstreams

1. Extend the trust root with `repository-attestor`, `artifact-attestor`, `trust-status`, and fixed
   `statusUrl` configuration.
2. Implement and test repository, NPL, and status-snapshot envelope verification.
3. Add persistent ETag-aware status refresh with atomic cache writes and rollback protection.
4. Move marketplace trust to the selected plugin version and enforce revocation during install/load.
5. Build the Node.js API, PostgreSQL job worker, GitHub OIDC validation, GitHub/NPL inspection, and
   role-separated signing services.
6. Build the developer/admin React console and publish an OpenAPI 3.1 contract.
7. Replace SDK developer-held certification keys with an OIDC draft-release approval workflow.
8. Update official-store schema validation and workflows for per-version NPL attestations.
9. Run focused security tests, full project tests, builds, syntax checks, and end-to-end fixture flow.

## Completion Gates

- A repository approval alone never yields `CERTIFIED`.
- A valid NPL attestation with stale, suspended, or revoked repository state never yields
  `CERTIFIED`.
- A valid repository plus a different version's attestation is rejected.
- A lower signed status version cannot roll back cached revocations.
- GitHub Actions completes certification using only `id-token: write`, with no official private key.
- The approval service can revoke one repository or one exact artifact and HMCL CE enforces it after
  the next successful status refresh.
