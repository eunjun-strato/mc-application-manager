# Unified Nginx catalog presentation (2026-09-17)

The Software Catalog list groups the unique built-in `Nginx` Docker entry and
`Nginx for Kubernetes` CloudPirates entry into one `Nginx` row. The expanded row
loads deployment status for both original IDs, including uninstalled records.
Download counts are summed and ratings weighted by rating count. Refresh fails
visibly if either status request fails rather than silently dropping a source.

The VM and K8s deployment selectors both display Nginx, but keep the original
option values and catalog IDs. VM continues to use Docker and K8s continues to
use the external Helm chart. Editing/deleting a catalog remains explicitly
scoped to its VM or K8s member. A single edit/delete icon pair is shown; the target choice appears only after clicking an icon. Custom or ambiguous catalog pairs are not merged.

This is a presentation change. There is no database migration, catalog deletion,
workload reinstallation, Helm release rename, or reassignment of historical IDs.
The catalog API intentionally retains both entries for existing clients.

Validation:
- `node scripts/test-catalog-grouping.mjs` in applicationFE
- `node scripts/test-install-target.mjs` and `node scripts/test-ingress-preparation.mjs`
- `npm run build` (type checking and production bundle)
- `gradlew test bootJar --offline --no-daemon`: 642 tests, zero failures/errors,
  one environment-dependent test skipped.

Deployment uses the complete project JAR and preserves each server's environment,
Compose volumes and database container. Backups and rollback image references
are in `/home/ubuntu/am-nginx-unified-20260917` on each server. The runtime image is
`mc-application-manager:nginx-unified-20260917-r2`. No Git commit or push was made.

