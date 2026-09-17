import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import ts from 'typescript'
const source = await readFile(new URL('../src/views/softwareCatalog/catalogGrouping.ts', import.meta.url), 'utf8')
const js = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } }).outputText
const { groupCatalogs, catalogDisplayName, loadGroupedDeploymentStatus } = await import(`data:text/javascript;base64,${Buffer.from(js).toString('base64')}`)
const vm = { id: 3, name: 'Nginx', packageInfo: { packageName: 'nginx' }, downloadCount: 1, ratingCount: 2, averageRating: 4 }
const k8s = { id: 12, name: 'Nginx for Kubernetes', helmChart: { chartName: 'nginx', chartRepositoryUrl: 'https://cloudpirates-io.github.io/helm-charts' }, downloadCount: 1, ratingCount: 1, averageRating: 1 }
const jupyter = { id: 11, name: 'JupyterLab for Object Storage', packageInfo: { packageName: 'quay.io/jupyter/scipy-notebook' } }
const catalogs = [vm, jupyter, k8s]
const original = JSON.stringify(catalogs)
const grouped = groupCatalogs(catalogs)
assert.deepEqual(grouped.map(c => c.name), ['Nginx', jupyter.name])
assert.deepEqual(grouped[0].deploymentCatalogIds, [3, 12])
assert.equal(grouped[0].downloadCount, 2)
assert.equal(grouped[0].averageRating, 3)
assert.equal(grouped[0].ratingCount, 3)
assert.equal(catalogDisplayName(k8s, catalogs), 'Nginx')
assert.equal(k8s.id, 12)
assert.equal(JSON.stringify(catalogs), original)
assert.equal(groupCatalogs([vm]).length, 1)
assert.equal(groupCatalogs([k8s]).length, 1)
assert.equal(catalogDisplayName(k8s, [k8s]), k8s.name)
assert.equal(groupCatalogs([vm, {...vm, id: 99}, k8s]).length, 3)
assert.equal(groupCatalogs([vm, {...k8s, helmChart: {...k8s.helmChart, chartRepositoryUrl: 'https://custom.example'}}]).length, 2)
const calls = []
const status = await loadGroupedDeploymentStatus([3, 12, 3], async id => {
  calls.push(id)
  return { data: { deploymentHistories: [{ id: id + 20 }], applicationStatuses: [{id, deploymentHistoryId: id + 20, status: id === 3 ? 'RUNNING' : 'UNINSTALLED'}] } }
})
assert.deepEqual(calls, [3, 12])
assert.deepEqual(status.applicationStatuses.map(s => s.deploymentHistoryId), [23, 32])
assert.equal(status.applicationStatuses[1].status, 'UNINSTALLED')
await assert.rejects(loadGroupedDeploymentStatus([3, 12], async id => { if (id === 12) throw Error('offline'); return { data: {} } }), /offline/)
console.log('Catalog grouping: preservation, aggregation, custom/ambiguous catalogs, names and refresh failure checks passed')
