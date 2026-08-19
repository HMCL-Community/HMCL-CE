# Official Reference Plugin Certification Rollback

## Goal

Replace per-release online certification with community review expressed through the signed official
plugin registry. A community plugin repository is certified when the official registry references the
same plugin ID and normalized repository identity.

## Trust Rules

- Entries loaded through the signed official source remain `OFFICIAL`.
- An item loaded through a non-official source is `CERTIFIED` when the signed official registry
  contains an entry with both the same plugin ID and the same normalized GitHub repository identity.
- Every version published by a matched community repository receives the same `CERTIFIED` result.
- Matching only a plugin ID is insufficient. A repository mismatch remains `COMMUNITY` and cannot
  inherit another repository's certification.
- If the official source cannot be loaded or verified, no community item receives certification.
- Legacy artifact attestations, repository attestations, online status snapshots, and installation
  receipts do not grant, deny, refresh, or revoke store certification.

## Data Flow

All configured sources continue loading independently and concurrently. After the loads complete,
the aggregate snapshot derives an immutable certification index from the successfully verified
official source. The index is keyed by plugin ID and normalized repository identity.

Before winner selection, the snapshot projects each matching non-official item to certified trust
for all of its resolved versions. Official items retain official trust. Unmatched community items
retain community trust, and transport or manifest validation failures retain rejected trust.

Repository identity comes from source-controlled registry metadata or GitHub Topic discovery, not
from an untrusted manifest field alone. Both sides use the existing repository normalizer.

## Installation And Runtime

Certified installation no longer requires a proof-backed `PluginCertificationReceipt`. Installation
continues to enforce package URL policy, SHA-256, declared size, compatibility, dependency, and
permission checks.

The launcher no longer starts or refreshes the online certification-status service for store trust.
Previously persisted certification receipts remain readable for compatibility but are not required
to install or load a plugin under the new policy. A later cleanup may remove the dormant proof model
after compatibility requirements are known.

## Compatibility

Existing manifest certification fields are tolerated but ignored for trust assignment. Missing,
partial, expired, or mismatched legacy proof declarations do not turn an otherwise valid community
manifest into `REJECTED`.

The official registry remains signed and continues pinning exact remote manifest bytes. This rollback
changes how the official registry delegates certification; it does not weaken validation of the
official registry itself.

## Failure Handling

- Invalid official signatures or manifest pins fail the official source and produce no delegated
  certifications.
- Invalid or absent repository identities cannot match the certification index.
- A plugin ID collision with a different repository remains untrusted community content.
- Unavailable community manifests remain rejected partial items and cannot be certified.

## Testing

Tests cover certification when plugin ID and repository both match, non-certification for repository
mismatches and ID-only collisions, non-certification when the official source fails, all-version
certification, tolerance of legacy proof declarations, installation without a receipt, and the
absence of online status refreshes from the store path.
