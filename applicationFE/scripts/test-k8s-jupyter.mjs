import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import ts from 'typescript'

const source = await readFile(new URL('../src/views/softwareCatalog/components/applicationInstallationForm.vue', import.meta.url), 'utf8')
const begin = source.indexOf('function buildObjectStorageConfig()')
const end = source.indexOf('\nfunction buildVmAdditionalConfig()', begin)
assert.ok(begin > 0 && end > begin)
const code = ts.transpileModule(source.slice(begin, end), { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText
const make = new Function('selectInfra', 'isJupyterObjectStorageCatalog', 'objectStorageData', 'isHttpEndpoint', code + '\nreturn buildObjectStorageConfig();')
const storage = { value: { enabled: true, selectedStorageIds: ['tencent-test', 'aws-test'], prefix: 'data/', accessMode: 'READ_WRITE', jupyterToken: 'test-login-token', accessKey: 'must-not-send', secretKey: 'must-not-send', endpoint: 'https://example.test', backendType: 's3' } }
for (const infra of ['VM', 'K8S']) {
  const result = make({ value: infra }, { value: true }, storage, () => false)
  assert.deepEqual(result.storages.map(s => s.objectStorageId), ['tencent-test', 'aws-test'])
  assert.equal(result.storages[0].accessMode, 'READ_WRITE')
  assert.equal(result.storages[0].prefix, 'data/')
  assert.equal(result.jupyterToken, 'test-login-token')
  assert.ok(!('accessKey' in result) && !('secretKey' in result))
}
const loki = make({ value: 'K8S' }, { value: false }, storage, () => false)
assert.equal(loki.backendType, 's3')
assert.equal(loki.accessKey, 'must-not-send')
console.log('VM/K8s Jupyter registered-storage payload and existing Loki payload passed (3 cases).')
