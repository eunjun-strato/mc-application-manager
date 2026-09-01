import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { Buffer } from 'node:buffer'
import ts from 'typescript'

const source = await readFile(new URL('../src/integration/installTarget.ts', import.meta.url), 'utf8')
const transpiled = ts.transpileModule(source, {
  compilerOptions: {
    module: ts.ModuleKind.ESNext,
    target: ts.ScriptTarget.ES2022
  }
}).outputText
const moduleUrl = `data:text/javascript;base64,${Buffer.from(transpiled).toString('base64')}`
const { parseInstallTarget } = await import(moduleUrl)
const generatedId = () => 'generated-request-id'

const validVm = parseInstallTarget(
  '?targetType=VM&mciId=mci-01&vmId=vm-01&requestId=req-01',
  generatedId
)
assert.equal(validVm.ok, true)
assert.deepEqual(validVm.target, {
  requestId: 'req-01',
  targetType: 'VM',
  mciId: 'mci-01',
  vmId: 'vm-01',
  clusterId: ''
})

const validK8s = parseInstallTarget('?targetType=k8s&clusterId=cluster-01', generatedId)
assert.equal(validK8s.ok, true)
assert.equal(validK8s.target.targetType, 'K8S')
assert.equal(validK8s.target.requestId, 'generated-request-id')

const invalidCases = [
  ['', 'targetType must be VM or K8S.'],
  ['?targetType=VM&mciId=mci-01', 'vmId is required.'],
  ['?targetType=VM&mciId=mci-01&vmId=vm-01&clusterId=cluster-01', 'clusterId cannot be used'],
  ['?targetType=K8S', 'clusterId is required.'],
  ['?targetType=K8S&clusterId=cluster-01&mciId=mci-01', 'mciId and vmId cannot be used'],
  ['?targetType=K8S&clusterId=bad/path', 'unsupported path characters'],
  ['?targetType=VM&mciId=mci-01&mciId=mci-02&vmId=vm-01', 'must be provided only once'],
  ['?targetType=VM&mciId=mci-01&vmId=vm-01&requestId=one&requestId=two', 'must be provided only once'],
  ['?targetType=VM&mciId=mci-01&vmId=vm-01&namespaceId=other', 'must not be supplied in the URL'],
  ['?targetType=K8S&clusterId=cluster-01&nodeGroupId=group-01', 'must not be supplied in the URL'],
  ['?targetType=K8S&clusterId=cluster-01&accessToken=secret', 'Unsupported query parameter'],
  [`?targetType=K8S&clusterId=${'x'.repeat(201)}`, '200 characters or fewer']
]

for (const [query, expectedMessage] of invalidCases) {
  const result = parseInstallTarget(query, generatedId)
  assert.equal(result.ok, false, `Expected invalid query: ${query}`)
  assert.match(result.error, new RegExp(expectedMessage.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')))
}

console.log(`Install target parser tests passed (${invalidCases.length + 2} cases).`)
