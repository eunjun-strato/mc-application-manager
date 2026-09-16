> Historical deployment results. The non-AWS test deployments were subsequently uninstalled and their clusters removed at the user's request. See [cleanup record](nginx-multi-csp-cleanup-20260916.md) for the final state.

# Multi-CSP Kubernetes nginx verification — 2026-09-16

All seven CSP workflows and AM SW Catalog nginx installations completed. Final AM monitoring confirms every deployment is RUNNING with 1/1 pods (2026-09-16 02:32 UTC / 11:32 KST). The pre-existing AWS archiving nginx also remains RUNNING. The existing Workflow `k8s-mariadb-backup-import-data-init` (103) used `INFRA_ID` and `K8S_CLUSTER_ID` = `k8s-mariadb-data-init-{csp}-01`. Each cluster has Application Manager SW Catalog 12 (`Nginx for Kubernetes`) installed.

Common settings: namespace `default`, host `nginx.example.com`, allowed source `210.219.181.78/32`, application replicas 1, HPA disabled. Non-IBM HTTP uses worker NodePort **30880**; IBM uses its managed public LB on **80**. The application Service is ClusterIP port 80. The local Windows hosts file was not changed; external tests used `curl --resolve`.

| CSP | Region / requested worker | Kubernetes version used | Successful Workflow build | nginx deployment | Verified public address |
| --- | --- | --- | --- | --- | --- |
| Azure | koreasouth / Standard_D2s_v3 | 1.35.6 | 5 | 13, HTTP 200 | 20.214.19.2 |
| GCP | asia-northeast3 / e2-medium | 1.35.6-gke.1250000 | 6 | 14, HTTP 200 | 34.50.29.86 |
| Alibaba | ap-northeast-2 / ecs.c9i.xlarge | 1.34.10-aliyun.1 | 12 | 15, backend HTTP 200 | None (`-`) |
| Tencent | ap-seoul / BF1.MEDIUM2 | 1.34.1 | 8 | 17, HTTP 200 | 43.155.141.168 |
| IBM | jp-osa / bx2-2x8 | 1.33.13_1580 | 9 | 19, HTTP 200 | 163.68.86.201, 163.68.83.115 |
| NCP | kr / c2-g3 requested; actual c2-g2-s50 | 1.34.3-nks.1 | 13 | 20, HTTP 200 | 211.233.199.71 |
| NHN | kr1 / m2.c1m2 | v1.35.5 | 11 | 18, backend HTTP 200 | None (`-`) |

Alibaba and NHN workers have no public IP. Backend requests returned HTTP 200; ingress requests from a non-allowed internal source returned 403. Their AM ingress public-address lists are empty and the detail UI displays `-`. Public addresses in this report refer to ingress workers, except IBM, where they refer to the managed public LB.

## Findings and scoped recovery

- **Azure:** available 1.35-series patch was 1.35.6. AM initially looked up the AKS-generated NSG in the wrong resource group. The actual VMSS NIC attachment was verified, and inbound TCP 30880 from the specified /32 was added directly to the correct NSG. Deployment 13 succeeded with automatic firewall opening disabled and the Ingress CIDR restriction retained. This is an operator-managed NSG rule; AM's security-group ledger does not automatically remove it on uninstall. The rule ID and before-state are retained in server evidence.
- **GCP:** initial provisioning chose COS_CONTAINERD. The new nodepool was updated to the requested UBUNTU_CONTAINERD family and completed successfully; the managed image is Ubuntu 24.04.4 LTS. The workflow's MariaDB uses ephemeral storage, so its known initialization schema/sample seed were restored after node replacement. Validation queried the row count (1), not table contents.
- **Alibaba:** missing `ack-node-problem-detector` prevented nodegroup creation. The add-on was installed, the requested `ng1` was created, and recovery Workflow 12 passed all six stages. Two workers became Ready within the configured min1/max3 autoscaling range.
- **Tencent:** an empty `sys.description` tag and the wrong nodepool availability-zone subnet prevented worker creation. Both were corrected. Requested Ubuntu 22.04 workers became Ready; Workflow 8 and AM deployment 17 succeeded.
- **IBM:** runtime r2 lacked HTTP ingress preparation present in the local source. Runtime r3 adds the required functionality using 23 relevant classes; 243 scoped regression tests passed. All 13 underlying Java source files already match `D:/mcmp/mc-application-manager` by SHA256. Managed preparation reached READY, adding HTTP 80 while preserving existing 443. Both LB addresses returned HTTP 200 from the allowed client. Requests from the non-allowed AM server, including a spoofed X-Forwarded-For header, returned 403. Both ALB replicas and two workers became Ready.
- **NCP network:** Workflow 10 failed at the three-VPC account limit. The user approved reuse of VPC 148385 (`tbqg8ghs3egav4q9p8ph`, 10.156.0.0/16), which had no attached compute/NICs. Its subnets 323783/323784 were registered; a subnet-registration metadata mismatch was corrected with backups. No existing VPC was deleted and no quota was increased. Workflow 13 completed successfully in approximately 24 minutes.
- **NCP specification limitation:** the driver defaults to XEN and matches legacy products by CPU/RAM, causing the requested c2-g3 to provision as c2-g2-s50 / Ubuntu 20.04.3. A KVM replacement would require a new cluster and minimum 100GB root disk. After explanation, the user prioritized nginx functional verification with the already-created cluster. No replacement KVM cluster was created and no existing cluster was deleted. **c2-g3 provisioning has not been verified or fixed in the infrastructure driver.**
- **NHN:** the requested general Ubuntu Server 22.04.5 LTS (2026.03.10) image `0f07c795-2a46-44fc-a61b-fa0d96763ce2` became the corresponding Container image `107cc02d-02d8-44fd-84d6-6c316045b817`, with the same OS release/image date. Actual worker Ubuntu 22.04.5 LTS / Kubernetes v1.35.5 was verified; Workflow 11 passed all stages.

## Runtime, source, and evidence

AM runs `mc-application-manager:nginx-catalog-ingress-ip-20260916-r3`. Compose recreation must include `/home/ubuntu/am-nginx-20260916/override-r3.yaml` with base `/home/ubuntu/mcmp/2026/0910/mc-admin-cli/conf/docker/docker-compose.yaml`. The r2 image, `override-r2.yaml`, and `am-r2.jar` remain for rollback. Other r2 JAR entries were preserved; existing deployments remained Running after the AM restart.

The D-drive project contains the catalog, Helm chart, domain/public-IP detail changes, compiled frontend assets, tests, and verification documents. The original checkout HEAD is preserved; **no Git commit or push was performed**. The prior full backend run passed 622 tests (0 failures/errors, 1 skipped), frontend typecheck/build and Helm lint passed, and the additional runtime IBM patch passed 243 scoped tests. `git diff --check` passed.

Status-refresh limitation: with all clusters monitored, the cached AM status can lag actual Pod readiness by several minutes. NCP initially displayed INSTALL despite a Running Pod and HTTP 200; the regular monitor subsequently updated it to RUNNING without manual status edits.

Server evidence: `/home/ubuntu/nginx-csp-test-20260916/`, including per-CSP details and `final-am-verification.json`. Runtime backups: `/home/ubuntu/am-nginx-20260916/`. Credentials are not included in this report.

API references: [GKE nodePools.update](https://docs.cloud.google.com/kubernetes-engine/docs/reference/rest/v1/projects.locations.clusters.nodePools/update), [Alibaba InstallClusterAddons](https://www.alibabacloud.com/help/tc/ack/ack-managed-and-ack-dedicated/developer-reference/api-install-a-component-in-an-ack-cluster), [NCP createCluster](https://api.ncloud-docs.com/docs/nks-createcluster).

