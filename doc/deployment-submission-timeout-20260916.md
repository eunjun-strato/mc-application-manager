# Deployment tracking and gateway timeout correction

## Observed cause (2026-09-16)

The AM HTTPS location in `mc-iam-manager-nginx` overrides the global 600-second timeout with `proxy_read_timeout 60s`. Both existing `/applications/vm/deploy` and `/applications/k8s/deploy` wait synchronously for remote installation. The browser's Axios timeout is 300 seconds, so the gateway can return 504 first while AM continues working.

Read-only evidence for the user's VM Jupyter deployment 22 (UTC):

- Started: `05:50:59.362730`.
- Gateway: `05:51:59`, `POST /applications/vm/deploy`, HTTP 504, `upstream timed out ... while reading response header`.
- Persisted completion: `05:52:58.656698`, `SUCCESS`, about 119 seconds after start.

The deployment and its VM were not modified during diagnosis. Sanitized server evidence is `/home/ubuntu/am-deployment-504-20260916/diagnosis.json`. Kubernetes installations can encounter the same timeout because their original controller follows the same synchronous pattern.

## Changed installation flow

The SW Catalog form now submits VM and Kubernetes installs to `POST /applications/deployment-submissions/{VM|K8S}` with a client-generated UUID `Idempotency-Key`. A short response returns a durable receipt. The form polls `GET /applications/deployment-submissions/{id}?namespace=...` until the persisted result is terminal; a successful receipt alone is not installation success. `FAILED` and `PARTIAL_SUCCESS` do not produce a success toast.

The original synchronous APIs remain compatible. External clients that continue calling them can still hit the gateway timeout; this change does not increase gateway timeouts or silently retry their deployments.

The receipt table `application_deployment_submission` stores scope, owner subject, request hash, target identity, state and history linkage. It stores no raw deployment body, bearer token or credentials. Namespace authorization uses the existing IAM project validation; both querying and closing a receipt additionally require its workspace, project and authenticated user subject. The asynchronous worker copies the security context transiently and clears it when finished.

Four workers and a queue of 16 bound execution. A database-unique active target/catalog key prevents overlapping async installs; VM locking conservatively covers the catalog across its MCI, including overlapping node groups. The same operation ID and payload return the same receipt even after a lost response. Different settings under the same ID are rejected. A gateway/network error causes lookup of that ID, never automatic submission of a replacement installation. The browser retains only nonsecret target identifiers and an operation ID in session storage. UUID generation uses `getRandomValues`, so existing HTTP-origin installations do not depend on secure-context-only `crypto.subtle` or `randomUUID`.

If settings change after the previous receipt is terminal, a 409 is followed by a status lookup, the stale browser receipt is cleared, and the user is instructed to review Apps Status and click Deploy again. There is no automatic second POST. Polling failure or dialog/project changes stop tracking, not the underlying work; the UI reports uncertainty rather than falsely declaring installation failure. Error toasts from intermediate receipt HTTP 404s are suppressed only for this tracked endpoint family, and final outcome handling stays with the tracker.

## Restart and interrupted tracking

This implementation follows the existing single-AM-instance deployment model. At startup, never-started `QUEUED` receipts become `FAILED` and release their guards. Previously `RUNNING` receipts become `INTERRUPTED`; work is not replayed.

An interrupted receipt can reconcile only when exactly one subsequent matching persisted installation history is terminal, and only for Kubernetes or an explicitly single-VM request without a node group. A deleted history releases the guard without claiming installation success. Multiple histories, multi-VM/node-group jobs, missing histories or still-running histories remain interrupted rather than inferring success.

For those cases the form offers **Close interrupted tracking** after instructing the user to inspect Apps Status and confirm no installation remains active. The owner/project-scoped `POST /applications/deployment-submissions/{id}/close-interrupted?namespace=...` accepts only `INTERRUPTED`, changes tracking to `ABANDONED`, and releases its guard. It does not cancel, uninstall, restart, delete, or resubmit the actual application, nor rewrite its deployment history. Closing ordinary running tracking through this endpoint is rejected. The user must separately decide whether a new installation is appropriate.

The existing synchronous endpoints do not participate in the new receipt lock. Do not run legacy and tracked installation requests concurrently for the same target. Interrupted history correlation is deliberately conservative and cannot substitute for inspecting provider/application state after an interrupted multi-target deployment.

## Validation

- Nine H2-backed service tests passed: immediate receipt with a blocked remote worker, idempotency, conflicting submissions, failed/partial results, restart state, owner/project separation, no secret persistence, single-history recovery, multi-VM non-reconciliation and explicit tracking closure.
- Two controller tests passed for the VM and K8S routes with namespace authorization.
- Frontend tracker tests passed for 504 response recovery with one submission, terminal outcomes, cancellation/network uncertainty, mismatched receipts, HTTP-compatible UUID generation, interrupted closure and changed-payload 409 handling.
- Existing ingress-preparation/form regression suite passed 38 cases. Its fixture gained the existing Jupyter unsupported-provider guard.
- Frontend type checking and production build passed. These tests did not create new cloud resources.

Runtime rollout and live verification are recorded separately; this document does not claim that a new VM or Kubernetes installation has been exercised after deployment.
