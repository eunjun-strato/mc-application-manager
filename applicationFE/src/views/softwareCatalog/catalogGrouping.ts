// Group only the unique AM-provided Docker/Helm pair. Keep persistent IDs intact.
export function nginxCatalogPair(catalogs: any[]) {
  const vm = catalogs.filter(c => c.name === 'Nginx' && c.packageInfo?.packageName === 'nginx' && !c.helmChart)
  const k8s = catalogs.filter(c => c.name === 'Nginx for Kubernetes' && !c.packageInfo
    && c.helmChart?.chartName === 'nginx'
    && c.helmChart?.chartRepositoryUrl?.replace(/\/$/, '') === 'https://cloudpirates-io.github.io/helm-charts')
  return vm.length === 1 && k8s.length === 1 ? { vm: vm[0], k8s: k8s[0] } : null
}

export function catalogDisplayName(catalog: any, catalogs: any[]): string {
  const pair = nginxCatalogPair(catalogs)
  return pair && catalog.id === pair.k8s.id ? pair.vm.name : catalog.name
}

export function groupCatalogs(catalogs: any[]) {
  const pair = nginxCatalogPair(catalogs)
  return catalogs.filter(c => !pair || c.id !== pair.k8s.id).map(c => {
    const members = pair && c.id === pair.vm.id ? [pair.vm, pair.k8s] : [c]
    const ratingCount = members.reduce((sum, m) => sum + Number(m.ratingCount || 0), 0)
    return { ...c, catalogMembers: members, deploymentCatalogIds: members.map(m => m.id),
      downloadCount: members.reduce((sum, m) => sum + Number(m.downloadCount || 0), 0),
      ratingCount, averageRating: ratingCount
        ? members.reduce((sum, m) => sum + Number(m.averageRating || 0) * Number(m.ratingCount || 0), 0) / ratingCount : 0 }
  })
}

// Fail the refresh as a whole if one source fails, rather than hiding its deployments.
export async function loadGroupedDeploymentStatus(ids: number[], load: (id: number) => Promise<any>) {
  const responses = await Promise.all([...new Set(ids)].map(load))
  return {
    deploymentHistories: responses.flatMap(r => r.data?.deploymentHistories || []),
    applicationStatuses: responses.flatMap(r => r.data?.applicationStatuses || [])
  }
}
