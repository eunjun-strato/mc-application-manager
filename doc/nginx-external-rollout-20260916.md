# External nginx Helm rollout — 2026-09-16

## Change

The user requested uninstalling only nginx on the AWS archiving Kubernetes cluster, replacing the AM-bundled chart with an external chart like the other Helm catalog applications, recreating the earlier IBM test cluster, and deploying the replacement nginx to both.

Catalog 12 (`Nginx for Kubernetes`) now uses `cloudpirates/nginx` version `0.16.8` from `https://cloudpirates-io.github.io/helm-charts`. The upstream chart package SHA256 is `6ce283ebae619a9c0255446418703488f4848720902ec0f1026d5e7c86982b86`. It deploys the Docker Official Image `docker.io/nginx:1.31.5-alpine@sha256:72ba65eb42c10344912a84ff42408db7d34f2feb642204570ab8fc5ffd29f1d3`.

CloudPirates is the chart publisher, not the nginx project's official chart. It was selected because its public chart uses the public official nginx image and an HTTP Helm repository compatible with AM's existing Helm installation path. Bitnami's nginx image availability has moved to its Secure Images subscription policy. No legacy Bitnami image fallback was introduced.

AM's source no longer contains the newly introduced `charts/nginx` and static chart archive/index. The earlier unrelated root `nginx/` fixture was preserved. Startup registers the external chart or migrates only the exact former built-in mapping; customized mappings remain untouched. The ingress adapter maps AM's service port to the external chart's `service.ports` array. Existing ingress policy, public-address lookup and IBM automation were preserved.

Runtime: `mc-application-manager:external-nginx-20260916-r7`. The r4 patch migrated the external chart, r5 added ingress access-port details, r6 added asynchronous installation submission/tracking and rebuilt UI, and r7 made nginx listen on unprivileged container port 8080. Entries outside each patch were verified identical; prior images remain available for rollback. Compose override: `/home/ubuntu/nginx-external-20260916/override-r7.yaml`.

The live IBM test exposed a portability issue in the upstream defaults: UID 101 could not bind container port 80 (`Permission denied`). The AM adapter now sets both `containerPorts[].containerPort=8080` and `serverConfig` with `listen 8080;` for CloudPirates nginx on every provider. The Service stays on the selected port (80 in this deployment), targeting named port `http`; probes use the same name. UID 101, `runAsNonRoot=true`, and `allowPrivilegeEscalation=false` are preserved. External ports remain AWS 30880 and IBM 80.

## Verification

- H2 catalog registration / migration / custom-mapping preservation: 3 passed.
- External-chart render with TLS on/off and AWS/IBM ingress classes, service port 8088, HPA and CIDR: 4 passed, none skipped.
- Existing ingress mapping/policy regression tests: 55 passed.
- `git diff --check` passed. No commit or push.
- Render tests pull the external chart into an isolated temporary directory before rendering: `helm template nginx --repo ...` can otherwise select the existing local `nginx/` fixture.

## AWS archiving deployment — complete

- Cluster: `k8s-mariadb-data-init-aws-archiving-01`.
- Previous nginx deployment 9 / status 5 was uninstalled through AM. VM nginx (status 3) and Jupyter (status 4) remained RUNNING.
- Initial external deployment 21 was replaced through AM after the IBM portability finding. Final deployment: **24**, release `nginx-20260916062028-8b9cdf84`, revision 1, chart `nginx-0.16.8`, app `1.31.5`.
- Pod ready: 1/1, deployment readyReplicas 1; live image digest, container/listener 8080 and non-root security settings verified.
- Service: ClusterIP 80. Ingress host `nginx.example.com`, class `nginx`, source restriction `210.219.181.78/32`.
- Actual ingress worker/public endpoint: `3.36.93.224:30880`; HTTP 200 from the allowed client using `curl --resolve`.
- AM integrated detail API reports RUNNING, host `nginx.example.com`, `ingressPublicIps=["3.36.93.224"]`, `ingressAccessPorts=[30880]`.
- Metrics-server is Running; no capacity workaround was needed in this rollout. Existing Jupyter PVCs were preserved.

## Ingress access port — complete

The application detail table now shows **Host → Access Port → Public IP**. The backend reads the actual ingress controller Service: NodePort for worker IPs, public LB listener port for IBM, selecting HTTP/HTTPS according to the application TLS setting. Missing values display `-`. The application Service Port remains separate.

- Ingress address/port unit tests: 10 passed.
- Frontend TypeScript check and production build passed.
- Live deployment 11 (Jupyter) and final deployment 24 (nginx) both return `ingressAccessPorts=[30880]`, `ingressPublicIps=["3.36.93.224"]`, and their original domain names.
- Live browser visual verification was blocked by the gateway self-signed certificate; no certificate bypass was performed. API responses and the production bundle were verified.

## IBM deployment — complete

- Workflow 103 (`k8s-mariadb-backup-import-data-init`), build 14 **SUCCESS**.
- Cluster: `k8s-mariadb-data-init-ibm-01`; native ID `dal2n5co0aht41nitr20`.
- Region/zone: `jp-osa` / `jp-osa-1`, worker `bx2-2x8`, Kubernetes `1.33.13`.
- Initial deployment 23 exposed the port-80 permission issue and was uninstalled through AM. Final deployment **25**, release `nginx-20260916062053-1aa0a4fd`, uses the shared 8080 fix.
- Pod 1/1 Running. Ingress `nginx.example.com`, class `public-iks-k8s-nginx`, source restriction `210.219.181.78/32`.
- Managed LB public addresses: **163.68.88.219** and **163.68.88.220**, external HTTP **80**. Both returned HTTP 200 from the allowed client using `curl --resolve`.
- Detail API reports both public addresses and `ingressAccessPorts=[80]`.
- Both LB addresses reject the disallowed AM-server source with HTTP 403, including a spoofed `X-Forwarded-For: 210.219.181.78` header.

## 504 correction and live asynchronous installation

See [deployment-submission-timeout-20260916.md](deployment-submission-timeout-20260916.md) for the gateway evidence, design, restart behavior and test details. The user's VM Jupyter deployment 22 succeeded after approximately 119 seconds, while the gateway had returned 504 after 60 seconds. VM and K8s SW Catalog installation now use durable asynchronous receipts with separate status polling and idempotency.

- Backend submission/service/controller tests: 11 passed, including a blocked remote worker returning its receipt immediately; repeated in the runtime-compatible build.
- Frontend tracking tests, existing 38 ingress-preparation regression cases, type check and production build passed.
- Live VM/K8s input-validation requests return HTTP 400 promptly; an unknown receipt returns 404. These checks created no deployment.
- Final AWS nginx installation used the new API: receipt in **0.059 seconds**, operation `6005918e-1c39-43cf-92c0-739e7a51b301`, final `SUCCEEDED`, history 24.
- Final IBM installation used the new API: receipt in **0.015 seconds**, operation `484df400-6df8-4736-bea5-e391f9849dcf`, final `SUCCEEDED`, history 25.
- Resending each exact request with its original operation ID returned the same receipt, with one submission row per operation.
- Production-served index `index-D89BCYsX.js`, asynchronous API bundle and Access Port modal bundle were fetched and checked.
- Preserved applications verified RUNNING: VM nginx history 7/status 3, K8s Jupyter history 11/status 4, and the user's VM Jupyter history 22/status 15. No new VM installation was run solely for this verification.
- Changes applied to `D:/mcmp/mc-application-manager`; no Git commit or push.

## Evidence

Restricted server directory: `/home/ubuntu/nginx-external-20260916/`. Includes prior catalog settings, old nginx uninstall result, chart package/rendered manifests, runtime build/deploy logs, AWS request/result/detail, and live chart verification. Credentials and kubeconfigs are not included in this report.
