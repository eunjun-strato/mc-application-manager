# VM Object Storage deployment notes

## Slow image downloads (including Alibaba)

The first Jupyter image download can take many minutes on a bandwidth-limited
VM. An AM HTTP timeout must not leave the same remote script to create and start
a container later, without AM completing the SSH tunnel and security-group steps.

AM now prepares the image in a separate command and creates the container only
after that command completes successfully. Cached images are reused. Image pulls
are bounded by GNU `timeout`; supported Linux VMs must have that command available.

- `VM_IMAGE_PULL_TIMEOUT_SECONDS`: defaults to `1800` (30 minutes), range 60–7200.
- Tumblebug VM command POSTs use a response timeout of that value plus 120 seconds.
  Other API requests retain their existing timeout behavior.
- Single- and multi-VM deployment coordinators wait for their workers to finish,
  instead of failing an active download after a separate 10/30-minute deadline.
- Deployment logs retain the failed phase/cause, and terminal history status is saved.
- Object Storage tunnel setup and restricted inbound rules still follow successful
  container startup. Download failure does not open those rules.

This accommodates a slow network; it does **not** increase CSP bandwidth. Check VM
bandwidth and registry reachability if downloads exceed the limit. On an HTTP
communication failure, check the VM before retrying: cancellation of an AM request
does not guarantee cancellation of remote work. Splitting the pull phase prevents
that phase from subsequently creating a container, but is not a general distributed
transaction or automatic reconciliation of older orphaned deployments.

Existing failed records/containers from before this change are not modified. Review
them separately before retrying; do not delete shared VM resources automatically.

## NCP and NHN download HTTP 403 / SignatureDoesNotMatch

The observed signed URL hosts contained uppercase region names:

- NCP: `KR.object.ncloudstorage.com`
- NHN: `KR1-api-object-storage.nhncloudservice.com`

Python requests normalized these URL hosts to lowercase. The resulting Host header
differed from the value used in the signature. Listing objects succeeded because it
is a different request path from downloading with a presigned URL.

On 2026-09-04, a fresh NHN GET URL was tested inside the existing Jupyter VM.
The default request and an explicit lowercase Host both returned
`403 SignatureDoesNotMatch`. Preserving the original Host returned `200` and the
expected 2,700-byte CSV. Neither the bucket permissions nor stored data was changed.

For granted Object Storage resources whose provider is NCP or NHN, AM now supplies
the original signed authority in `requiredHeaders.Host`, only for recognized
provider-specific Object Storage endpoints and URLs that sign `host`.
NHN support is limited to public endpoints matching
`<region>-api-object-storage.nhncloudservice.com`; it does not treat arbitrary
NHN subdomains or other providers as equivalent. The signed URL itself is never
rewritten; existing required headers (including an existing Host) are preserved.
The VM's provider does not determine this behavior. Other storage providers pass
through unchanged, and grant/prefix/read-only checks are still enforced.

The bundled notebook already forwards `requiredHeaders` for previews, downloads
and uploads. After updating AM, request a fresh URL by rerunning the preview cell;
reinstalling that notebook/VM is not required. Custom notebooks must also pass
`headers=ticket.get('requiredHeaders') or {}`. Do not print full presigned URLs or
tokens when troubleshooting. This compatibility fix does not address unrelated
403 causes such as expired URLs, missing permissions or changed object keys.

The long-term upstream fix is consistent endpoint-host normalization **before**
NCP/NHN signing. Changing an already signed URL is not equivalent.

CB-Spider's [shared S3 manager](https://github.com/cloud-barista/cb-spider/blob/master/api-runtime/common-runtime/S3Manager.go)
constructs the NHN endpoint as `fmt.Sprintf("%s-api-object-storage.nhncloudservice.com", regionID)`.
The observed uppercase region therefore carries into the host used for signing.

References: [NHN S3-compatible API guide](https://docs.nhncloud.com/ko/Storage/Object%20Storage/ko/s3-api-guide/),
[NCP Object Storage API](https://api.ncloud-docs.com/docs/common-objectstorageapi-objectstorageapi).
