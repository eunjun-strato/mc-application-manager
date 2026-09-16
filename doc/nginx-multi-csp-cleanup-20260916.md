# Kubernetes nginx test cleanup — 2026-09-16

Scope: uninstall nginx and remove the seven non-AWS Kubernetes test clusters and their owned infrastructure. Preserve AWS archiving VM/K8s and pre-existing reused resources. No Git commit or push.

## Verified removal

| CSP | Cluster / resources |
| --- | --- |
| Azure | AKS cluster, generated node resource group, test VNet/subnets/security group removed; native cluster, node RG and VNet return 404. |
| GCP | GKE cluster/workers removed; test VPC, both subnets and five test firewall rules absent in native API. |
| Alibaba | ACK cluster, two workers/disks, endpoint LB/EIP absent; prior shared VPC retained. |
| Tencent | TKE cluster, two workers/disks, nodepool, ASG, launch configuration and orphan endpoint LB removed; prior VPC retained. |
| NHN | NKS cluster/workers absent; no matching cluster ports, security groups, load balancers or volumes found. |
| NCP | NKS cluster, worker, public-IP allocation and cluster ACG absent; test LB subnets 323974/323975 removed. |
| IBM | Cluster/workers, managed LB, owned VPC/subnet/PGW, six endpoint gateways, six security groups and test PGW Floating IP all absent. Older resources verified unchanged by exact ID. |

## Application Manager and AWS

All seven nginx UNINSTALL actions and AM cleanup calls succeeded. Old non-AWS failed/uninstalled deployment records were also cleaned. Audit/log rows were preserved; nullable references blocking cleanup were detached only for targeted status rows, with reference mappings backed up. A monitor cycle recreated IBM/NCP status rows after cleanup; these were removed through the cleanup API once uninstall completion was verified.

Final AM application-status list contains only AWS IDs 3, 4 and 5, all RUNNING (archiving VM nginx, K8s Jupyter and K8s nginx). Tumblebug Kubernetes list contains only `k8s-mariadb-data-init-aws-archiving-01`, Active. Final external HTTP checks: AWS VM 54.180.127.114:80 = 200; AWS K8s 3.36.93.224:30880 = 200. SW Catalog entries and deployed AM runtime were retained.

## Existing resources intentionally retained

- NCP reused VPC 148385 and original GENERAL subnets 323783/323784.
- NHN September 14 VPC/subnets and pre-existing Floating IP 180.210.82.26 (allocation bbb36eac-3b74-4f7e-98a5-730e5c7f0959), now detached. The address allocation remains reserved.
- Alibaba/Tencent prior shared VPCs and existing shared security groups; no target VPC ENIs remain. Tencent test-created SG sg-nab2s4d1 was deleted after the user explicitly approved treating it the same as other CSPs. Native associations were zero before deletion; platform and native absence were verified. Prior SGs sg-7cvsbyyf and sg-bnq06prt remain.
- Other unrelated/older infrastructure, shared credentials and SSH keys.

## Alibaba exception

Before deletion, prior September 15 NAT `ngw-mj7x2i4lkixtdmff0lj1m` and EIP `eip-mj790in8dhgumfs911q45` existed in the reused VPC. Both were absent in exact-ID queries after the normal platform cluster DELETE. No separate native deletion was sent for either resource. Provider/driver cascade is suspected, but causality was not established. The VPC remains. This exception is not represented as an intentionally preserved resource.

## Evidence

Server: `/home/ubuntu/nginx-csp-cleanup-20260916/` (restricted directory), including before identities, uninstall/cleanup results, audit-reference mappings and provider final checks. Supplementary Alibaba/Tencent snapshots are under `/home/ubuntu/nginx-csp-test-20260916/`. Reports contain no cloud credentials or kubeconfigs.

## Follow-up: Workflow cleanup and Tumblebug metadata verification

The initial report's NCP platform-absence conclusion was incomplete: HTTP 404 can also wrap a provider lookup failure while a Tumblebug record remains. Exact-ID reinspection found the NCP record still referencing native cluster 3f62d0e5-686e-4968-8321-8820045cd8c9. The other six clusters returned the explicit `not exist` message. All seven separate infra IDs returned `does not exist`.

At the user's request, Workflow 107 (`k8s-mariadb-data-init-cleanup`) was run for namespace `default`, cluster `k8s-mariadb-data-init-ncp-01`, with the workflow's force cleanup option. Build #4 was marked FAILED because Tumblebug returned HTTP 200 with `is not deleted`, although its force path removed the stale metadata. A subsequent exact-ID query returned the explicit `not exist` message. The same workflow was rerun as build #5 and completed SUCCESS, including its cleanup stage.

Final exact-ID checks: Azure, GCP, Alibaba, Tencent, IBM, NCP and NHN K8s records all return `not exist`; all seven separate infra IDs return `does not exist`. No cleanup workflow was needed for the other six because their records were already absent. AWS archiving resources and the intentionally retained existing/shared networks were excluded from these runs.

Evidence: `/home/ubuntu/nginx-csp-cleanup-20260916/workflow107-ncp-dispatch.json`, `workflow107-ncp-result.json`, `workflow107-ncp-stage4.json` (restricted raw log), `workflow107-ncp-retry-dispatch.json`, `workflow107-ncp-retry-result.json`, and the final per-ID query snapshot `workflow-precheck.json`.
