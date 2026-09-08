# K8s Jupyter SSH transport

New installations default to `OBJECT_STORAGE_K8S_TRANSPORT=SSH_TUNNEL`.
The existing common application CIDR, Helm preflight and SG ownership work in
`k8s-cidr.md` is preserved. The Jupyter UI and sample notebook are unchanged.

```text
AM -> Kubernetes API (authenticated port-forward) -> Pod loopback SSH :2222
Jupyter -> Pod loopback :18084 -> reverse SSH -> AM allowlisted Gateway proxy
```

Neither worker SSH, a public Pod IP, a host socket, nor inbound access to AM's
HTTP Gateway is required. Jupyter still accesses CSP presigned URLs directly,
so Pod egress to Object Storage endpoints must be available.

## Public image and deployment configuration

AM uses the unmodified public LinuxServer.io OpenSSH image, pinned to a tested
multi-platform manifest digest. No custom image build, registry account or push is
required. The cluster must be able to pull from `lscr.io` and its backing registry.
The default configuration is:

```text
OBJECT_STORAGE_K8S_TRANSPORT=SSH_TUNNEL
OBJECT_STORAGE_K8S_SSH_IMAGE=lscr.io/linuxserver/openssh-server@sha256:39ba37d50fdd6be1bf70644c871e5dcb9234ee79ac56424ea03ca08cadf1e7b0
```

No public `OBJECT_STORAGE_K8S_GATEWAY_URL` or HTTP opt-in is needed in this mode.
An immutable, deployment-owned ConfigMap supplies startup/readiness scripts, SSH
configuration and a minimal password-disabled account database. AM overrides the
image entrypoint to run `/usr/sbin/sshd.pam` directly, without its general-purpose
initialization or runtime package installation. Only the SSH container mounts the
account files; no passwords or private keys are stored in this ConfigMap. The
Secret remains separate. AM needs `ssh` and `ssh-keygen` (already in its image).

Image overrides must retain this LinuxServer image's paths/tools and be retested.
Review and explicitly advance the pinned digest for updates.
Upstream documentation: https://docs.linuxserver.io/images/docker-openssh-server/

AM's Kubernetes identity needs the existing resource permissions plus Pod get/list
and `pods/portforward` create/get. Kubernetes authentication is resolved again on
reconnection through the existing provider-specific resolver. Do not grant these
permissions to the Jupyter Pod: its ServiceAccount token stays unmounted.

The sidecar's SSH daemon uses UID 0 for OpenSSH privilege separation, drops to the
`mcmp` account, has a read-only root filesystem, and has only SETUID, SETGID,
SYS_CHROOT and CHOWN capabilities. Clusters enforcing a non-root-only policy need
an approved policy exception or an alternative compatible sidecar implementation.
There are no host namespaces, privileged containers, hostPath mounts or SSH Services.

## Identity and lifecycle

- Each installation generates distinct client and host Ed25519 keys. The immutable
  `<release>-ssh` Secret persists across Pod restarts. Its client private key is read
  only by AM; only host-key and authorized_keys are projected into the sidecar.
  Jupyter mounts neither key. No keys/tokens are stored in the tunnel table or logs.
- AM pins the host public key and binds its local port-forward to 127.0.0.1. The
  SSH server also listens on Pod loopback only. SSH sessions/shells, arbitrary
  destination ports, public reverse listeners and local forwarding are disabled.
- The reverse listener is restricted to 127.0.0.1:18084. AM reuses the existing
  allowlisted proxy: storages, objects, presigned-url and an internal readiness check.
- Pod readiness includes the reverse-path health check, and Jupyter startup checks
  authenticated Object Storage listing. Ingress is still created only after ready.
- A new `k8s_object_storage_tunnel` table stores the workload/Secret UIDs, desired
  state and a renewable 120-second ownership lease. With the project's default
  `DDL_AUTO=update`, Hibernate creates it. With schema validation, provision this
  entity's table through the environment's migration process before deployment.
- The AM owner reconciles every 10 seconds; SSH keepalives detect failed links.
  Pod replacement closes the old stream and establishes a new port-forward.
  Another AM instance takes over after lease release/expiry. Host/Secret identity
  mismatch is rejected instead of silently trusting a replacement resource.
- Stop suspends the tunnel; start/restart resumes it. Uninstall or failed install
  disables the tunnel, revokes grants and deletes owned resources. Notebook PVC
  retention follows the existing Jupyter behavior. Shutdown closes local SSH/API
  connections and removes temporary key files without deleting desired state.
- With `app.scheduling.enabled=false`, a local AM maintains only its own tunnels;
  it does not take over another AM's installations in a shared development DB.

Existing DIRECT deployments are not silently converted by changing the default.
New SSH installations require the new sidecar resources; reinstall through AM.
`OBJECT_STORAGE_K8S_TRANSPORT=DIRECT` retains the prior explicit Gateway URL and
HTTP opt-in behavior. Neither mode changes Ingress/CIDR policy or firewall settings.

## Validation

Unit/integration tests cover ownership leases, stop/delete, Pod replacement,
disconnection/reconnection decisions, key isolation and unchanged CIDR manifests.
The public-image Docker test runs real OpenSSH with the same capabilities/read-only filesystem,
verifies reverse HTTP and persistent TCP, rejects shell/extra ports/public binding,
and checks readiness loss/recovery after disconnect/reconnect. It publishes no ports:

```bash
# Official Python image used only as the HTTP test fixture:
docker pull python:3.12-slim-bookworm@sha256:782412e85d0f0984994c290652577d4018aff08145c85b262bb63dc0c7522254
python src/test/python/test_k8s_public_ssh_sidecar.py
```

These local tests do not prove Kubernetes streaming works on every CSP. After
cluster recreation, verify sustained port-forward from the actual AM environment,
Pod replacement, AM restart, read-only/read-write Object Storage operations and
Ingress CIDR behavior. The earlier EKS check confirmed create/get permission only;
cleanup removed the test target before the sustained stream test.
