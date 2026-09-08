# Common application CIDR access

Every new K8s application installation with Ingress enabled requires a restricted
IPv4 CIDR and ingress class `nginx`, regardless of Object Storage configuration.
Internal applications with Ingress disabled remain ClusterIP-only; no firewall
opening is requested by the UI. Existing VM access configuration is unchanged.

The form defaults to allowing the CIDR on the worker Security Group, TCP 30880.
Uncheck this only when the firewall is already configured externally. The
per-application Ingress CIDR is still required and applied. Automatic SG changes
resolve actual worker attachments using Tumblebug's `forward/cspvm/{id}` read API,
and require one unambiguous SG shared by all discovered workers in the same VPC.
A declared cluster SG is used only if attached to every worker. For AWS, the
separately reported `ClusterSecurityGroupId` is preferred next, again only after
verifying every worker attachment; EKS SSH source SGs are not worker attachments.
An already registered matching SG is reused. Otherwise AM registers the existing
CSP SG via `POST .../securityGroup?option=register`, with a deterministic name,
then adds only the requested CIDR/TCP 30880 rule. AM calls no CSP API directly.
Missing worker IDs, unavailable attachment metadata, VPC mismatches or ambiguous
SGs fail closed. This does not claim every provider's cspvm metadata is sufficient;
providers must expose the needed identifiers and attachments through Spider.
Imported SG resources remain registered after uninstall; AM never deletes the
provider-created SG, and releases only its owned rule through the existing ledger.
Shared and operator-owned rules use the existing exposure ownership ledger.

Ingress Controller must preserve source IP with `externalTrafficPolicy: Local`.
For hosts-based testing, map the application's Ingress hostname to the public IP
or Floating IP of a node with a ready Ingress Controller endpoint, then access
`http://<hostname>:30880`. The CIDR is the client's public IP/range, not the node IP.
Ingress readiness, node routing and cloud/host firewalls must also allow access.

Helm values support Grafana and standard root Ingress, Prometheus server Ingress,
Rclone main Ingress and Loki gateway Ingress. Before installation, a Helm dry-run
verifies that all rendered Ingresses (including hooks) carry the exact CIDR and
nginx class, and rejects NodePort/LoadBalancer application Services that bypass it.
Charts with different schemas fail closed until their mapping is added; AM never
silently reports CIDR applied to an unsupported chart. Jupyter's native deployment
retains its existing CIDR annotation and grant handling.

After Helm installation, AM records the release then optionally provisions the
shared SG rule. A provisioning failure attempts uninstall and records FAILED or
DELETE_PENDING. Successful uninstall releases only bindings for that release;
rules still needed by other applications are retained. Failed release of a rule
can be retried by uninstalling again.

This change applies to new installations. It does not update running releases,
the shared Ingress Controller, or a development-server deployment automatically.
Pod-to-AM Gateway networking for Jupyter remains a separate requirement.
