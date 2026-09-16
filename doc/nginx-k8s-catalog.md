# Kubernetes nginx catalog and deployment

Source and chart rendering verified on 2026-09-16 (KST). See [external chart rollout](nginx-external-rollout-20260916.md) for current AWS/IBM deployment results. The historical section below describes the earlier built-in-chart deployment.

## Catalog and source

`Nginx for Kubernetes` is a general-purpose selectable SW Catalog entry, separate from the existing VM `Nginx` entry. `DatabaseInitializer.ensureNginxHelmCatalog()` registers the catalog and its external Helm mapping idempotently.

- Repository: `https://cloudpirates-io.github.io/helm-charts` (HTTP Helm repository; alias `cloudpirates`).
- Chart: `nginx` version `0.16.8`; upstream application version `1.31.5`.
- Upstream image: `docker.io/nginx:1.31.5-alpine`, pinned by the chart's image digest.
- Package identity: `cloudpirates-nginx`; a new installation fetches the external chart through Helm.
- The AM source no longer includes `charts/nginx/` or the static `/charts/index.yaml` and `nginx-0.1.0.tgz`. Newly built AM JARs do not bundle these nginx chart assets.
- The catalog's default requests remain 100m CPU / 64Mi memory; limits remain 500m CPU / 256Mi memory. AM supplies its ingress, resource, replicas and HPA settings to the external chart. The verified default rendering creates no PVC.

Startup migrates only the exact old mapping for `Nginx for Kubernetes`: package ID `mcmp-builtin-nginx`, chart `nginx` version `0.1.0`, repository `http://localhost:18084/charts`, and repository name `mcmp-builtin`. Other chart mappings, customized repository/version selections, and other catalogs are preserved. This metadata migration does not upgrade an existing Helm release.

`CloudPiratesNginxHelmTest` downloads the pinned chart into an isolated temporary directory and renders the package by absolute path. This prevents the unrelated existing `nginx/` fixture from shadowing the external chart. Four cases passed: TLS on/off with `nginx` and `public-iks-k8s-nginx`, including service port 8088, CIDR restriction, and HPA rendering. Three H2 tests passed for registration, idempotent legacy migration, and custom mapping preservation. These tests do not establish live rollout or HTTP reachability; see [Helm ingress mapping](helm-ingress-mapping.md) for reproduction.

The detail API exposes `ingressPublicIps`. The appStatus detail modal displays these addresses under Ingress. For non-IBM clusters the resolver reads ExternalIP addresses of nodes running the AM ingress controller. IBM uses public managed ingress LoadBalancer addresses. Missing public addresses display `-`; lookup failures do not break the detail dialog.

## Historical built-in-chart installation (2026-09-16)

| Field | Value |
| --- | --- |
| Cluster | k8s-mariadb-data-init-aws-archiving-01 |
| Namespace | default |
| Catalog | 12 — Nginx for Kubernetes |
| Deployment history / application status IDs | 9 / 5 |
| Helm release | nginx-20260916003401-489dc4b9 |
| Host | nginx.example.com |
| Public worker IP | 3.36.93.224 |
| External HTTP port | 30880 |
| Allowed source | 210.219.181.78/32 |
| Application service | ClusterIP, internal port 80 |
| State | Helm deployed; application RUNNING; pod 1/1 Running |

Run from the currently allowed source IP (210.219.181.78):

```sh
curl --fail --resolve nginx.example.com:30880:3.36.93.224 \
  http://nginx.example.com:30880/
```

This returned HTTP 200 and the nginx welcome page. Port 80 is internal service traffic, not an additional public opening. The worker security group has the restricted TCP 30880 rule, and the Ingress has `nginx.ingress.kubernetes.io/whitelist-source-range: 210.219.181.78/32`. The ingress controller uses `externalTrafficPolicy: Local`. Its pre-existing HTTPS NodePort 30418 remains allocated but is not externally opened. Before the source-CIDR correction, probes from AM confirmed 30880 reachable and 80, 443, 30418 unreachable. Current allowed-source access is verified from 210.219.181.78; AM is no longer an allowed client.

Catalog, release and deployment records remain in AM's database after an AM restart, which was verified. Retain the AM database when archiving/restoring the environment; this change does not implement a separate cluster restoration mechanism.

## Historical runtime and validation

- Deployed AM image: `mc-application-manager:nginx-catalog-ingress-ip-20260916-r3`.
- Server artifacts and before/after DB backups: `/home/ubuntu/am-nginx-20260916/`.
- Compose override: `/home/ubuntu/am-nginx-20260916/override-r3.yaml`; base compose: `/home/ubuntu/mcmp/2026/0910/mc-admin-cli/conf/docker/docker-compose.yaml`.
- Only AM was recreated; other container IDs were unchanged. Existing MariaDB and ingress pods remain running.
- D-drive source retains its original HEAD `6a682de`; no commit or push was performed. Its frontend assets were rebuilt from that checkout to retain its newer Jupyter changes. The server patch was built against the server-compatible source and preserves unrelated runtime entries.
- D-drive backend suite: 622 tests, 0 failures, 0 errors, 1 skipped. New address-resolution and Helm-rendering tests passed. Frontend typecheck/build and Helm lint passed.
- AWS address display was verified through the actual detail API. IBM LB and Alibaba/NHN public-address-absent cases were subsequently verified on live clusters; see [multi-CSP verification](nginx-multi-csp-verification-20260916.md).
- Browser visual verification was blocked by the server's untrusted certificate; no certificate bypass was used.

The existing t3.small worker permits 11 pods. The AM installation newly added metrics-server and exhausted that capacity. Only that newly added metrics-server Helm release was removed after backing up its status/values; nginx then became Running. HPA is disabled for this deployment. AM CPU/memory metrics may remain unavailable until capacity is expanded and metrics-server is installed. Existing application pods were preserved.

## Historical domain correction and VM distinction

The operator confirmed `nginx.example.com` as the intended host. Helm revision 2 and deployment history 9 now both use that host. The detail API reports `nginx.example.com` and worker IP `3.36.93.224`; HTTP through port 30880 returned 200 after the correction. Original release values and deployment history were saved under `values-before-domain-correction.json` and `history-before-domain-correction.json` in the server artifact directory.

`54.180.127.114` belongs to the separate existing VM Nginx deployment (history 7, MCI `vm-mariadb-data-init-aws-archiving-01`, VM `g1-1`, service port 80). The local Windows hosts file maps `nginx.example.com` to that VM IP. It was not modified. Thus that mapping does not access the new Kubernetes deployment. K8s access uses `3.36.93.224:30880`, with the same source CIDR restriction as before. The browser's reported access to VM port 80 was not reproduced from the AM server; that does not alter the verified VM/K8s resource distinction.
## Historical source CIDR correction

At the operator's request, Helm revision 3 changes the current allowed source to `210.219.181.78/32`. The AWS TCP 30880 rule, live Ingress whitelist annotation, Helm release values and AM exposure record for deployment 9 were updated and verified. The previous `210.217.178.130/32` rule was removed. Historical checks above from the AM server describe the pre-change configuration; that server is no longer allowed by this rule. Host `nginx.example.com`, worker `3.36.93.224`, external port 30880 and the Running pod are unchanged. Before-change values and rules are saved as `cidr-before-*` in the server artifact directory.
