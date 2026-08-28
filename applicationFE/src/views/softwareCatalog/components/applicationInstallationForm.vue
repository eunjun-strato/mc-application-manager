<template>
  <div class="modal fade" id="install-form" tabindex="-1">
    <div class="modal-dialog modal-lg" role="document">
      <div class="modal-content">
        <div class="modal-header">
          <h5 class="modal-title">
            {{ modalTitle }}
          </h5>
          <button type="button" class="btn-close" data-bs-dismiss="modal" aria-label="Close" @click="setInit"></button>
        </div>
        <div class="modal-body" style="max-height: calc(100vh - 200px);overflow-y: auto;">

          <div v-if="hasProjectContext" class="alert alert-info py-2" role="status">
            Deployment targets are scoped to
            <strong>{{ projectContextLabel }}</strong>.
          </div>

          <div v-if="projectScopeError" class="alert alert-warning py-2" role="alert">
            {{ projectScopeError }}
          </div>

          <div class="mb-3">
            <label class="form-label">Target Infra</label>
            <p 
              v-if="modalTitle == 'Application Installation'" 
              class="text-muted">
                Select the Infra what is the Infra will be installed
            </p>
            <p 
              v-else-if="modalTitle == 'Application Uninstallation'" 
              class="text-muted">
                Select the Infra what is the Infra will be uninstalled
            </p>
            <select 
              class="form-select" 
              id="infra" 
              v-model="selectInfra">
              <option 
                v-for="infra in infraList" 
                :value=infra.value 
                :key="infra.value">
                  {{ infra.value }}
                </option>
            </select>
          </div>

          <!-- 
            ==============================================================================================
            ============================================= VM =============================================
            ==============================================================================================
          -->
          <template v-if="selectInfra == 'VM'">
            <div class="mb-3">

              <!-- VM :: Namespace -->
              <label class="form-label">Namespace</label>
              <p 
                v-if="modalTitle == 'Application Installation'" 
                class="text-muted">
                Select the namespace where the application will be installed</p>
              <p 
                v-else-if="modalTitle == 'Application Uninstallation'" 
                class="text-muted">
                Select the namespace where the application will be uninstalled</p>
              
              <template v-if="nsIdList.length > 0">
                <select 
                  class="form-select" 
                  id="vm-namespace"
                  v-model="selectNsId"
                  :disabled="isNamespaceLocked"
                  @change="onChangeNsId">
                  <option 
                    v-for="ns in nsIdList" 
                    :value="getNamespaceValue(ns)"
                    :key="getNamespaceValue(ns)">
                    {{ ns.name || ns.id }}
                  </option>
                </select>
              </template>
              
              <template v-else>
                <select 
                  class="form-select" 
                  id="vm-namespace-empty"
                  disabled>
                  <option value="">
                    No namespace available
                  </option>
                </select>
              </template>
            </div>

            <!-- VM :: Infra ID -->
            <div class="mb-3">
              <label class="form-label">Infra ID</label>
              <p 
                v-if="modalTitle == 'Application Installation'" 
                class="text-muted">
                Select the infra ID where the application will be deployed</p>
              <p 
                v-else-if="modalTitle == 'Application Uninstallation'" 
                class="text-muted">
                Remove the application and associated resources from the infra</p>
              <select 
                class="form-select" 
                id="vm-mci"
                :disabled="selectNsId == ''" 
                v-model="selectMci"
                @change="onChangeMci">
                <option v-if="mciList.length === 0" value="">No infra available</option>
                <option 
                  v-for="mci in mciList" 
                  :value="mci.id || mci.name"
                  :key="mci.id || mci.name"
                  :title="mci.id || mci.name">
                    {{ mci.id || mci.name }}
                  </option>
              </select>
            </div>

            <!-- VM :: VM Name -->
            <div class="mb-3">
              <label class="form-label">VM Name</label>
              <p 
                class="text-muted">
                Select the virtual machine (VM) within the chosen multi-cloud infrastructure where the application will be deployed</p>
              <select 
                class="form-select" 
                id="vm-name"
                :disabled="selectMci == ''" 
                v-model="selectVm"
                @change="onSelectVm">
                <option value="">Select VM</option>
                <option 
                  v-for="vm in vmList" 
                  :value="vm.id" 
                  :key="vm.name">
                  {{ vm.name }}
                </option>
              </select>

              <div class="mt-2" style="display: flex; gap: 10px; flex-wrap: wrap;" v-if="selectedVmList.length > 0">
                <label 
                  v-for="(vmId, index) in selectedVmList" 
                  :key="index"
                  class="form-check-label" 
                  style="border: 1px solid #000; padding: 5px; border-radius: 5px; cursor: pointer;">
                  {{ vmId }} 
                  <span @click="removeVm(index)" style="margin-left: 5px; font-weight: bold;">X</span>
                </label>
              </div>
            </div>


            <!-- VM :: Deployment Type -->
            <div class="mb-3">
              <label class="form-label">Deployment Type</label>
              <p class="text-muted">Select the deployment type</p>
              <div style="display: flex; gap: 10px;">
                <div class="form-check">
                  <input class="form-check-input" type="radio" id="Standalone" v-model="selectDeploymentType" value="Standalone">
                  <label class="form-check-label" for="Standalone">Standalone</label>
                </div>
                <div class="form-check">
                  <input class="form-check-input" type="radio" id="Clustering" v-model="selectDeploymentType" value="Clustering">
                  <label class="form-check-label" for="Clustering">Clustering</label>
                </div>
              </div>
            </div>

            <!-- VM :: Application -->
            <div class="mb-3">
              <label class="form-label">Application</label>
              <p class="text-muted">Select the application</p>
              <select 
                class="form-select" 
                v-model="inputApplications" 
                @change="onChangeCatalog">
                <option v-for="(catalog, idx) in filteredCatalogList" :key="idx" :value="catalog.name">
                  [{{ catalog.name }}] {{ catalog.packageInfo?.packageVersion || "latest" }}
                </option>
              </select>
            </div>

            <!-- VM :: Service Port -->
            <div class="mb-3">
              <label class="form-label">Port</label>
              <p class="text-muted">Please enter a port accessible from the outside</p>
              <input type="number"  class="form-control" placeholder="8080"  v-model="inputServicePort">
            </div>

            <div class="mb-3" v-if="modalTitle == 'Application Installation'">
              <label class="form-label">Resource Type</label>
              <select class="form-select" v-model="selectedResourceType">
                <option value="GENERAL_PURPOSE">General Purpose</option>
                <option value="CPU_INTENSIVE">CPU Intensive</option>
                <option value="MEMORY_INTENSIVE">Memory Intensive</option>
              </select>
            </div>
          </template>

          <!-- 
            ==============================================================================================
            ============================================ K8S =============================================
            ==============================================================================================
          -->
          <template v-else-if="selectInfra == 'K8S'">
            
            <!-- K8S :: Namespace -->
            <div class="mb-3">
              <label class="form-label">Namespace</label>
              <p 
                v-if="modalTitle == 'Application Installation'" 
                class="text-muted">Select the namespace where the application will be installed</p>
              <p 
                v-else-if="modalTitle == 'Application Uninstallation'" 
                class="text-muted">Select the namespace where the application will be uninstalled</p>
                
              <template v-if="nsIdList.length > 0">
                <select 
                  class="form-select" 
                  id="k8s-namespace"
                  v-model="selectNsId" 
                  :disabled="isNamespaceLocked"
                  @change="onSelectNamespace">
                  <option 
                    v-for="ns in nsIdList" 
                    :value="getNamespaceValue(ns)"
                    :key="getNamespaceValue(ns)">
                    {{ ns.name || ns.id }}
                  </option>
                </select>
              </template>

              <template v-else>
                <select 
                  class="form-select" 
                  id="k8s-namespace-empty"
                  disabled>
                  <option value="">
                    No namespace available
                  </option>
                </select>
              </template>
            </div>

            <!-- K8S :: ClusterName -->
            <div class="mb-3">
              <label class="form-label">ClusterName</label>
              <p 
                v-if="modalTitle == 'Application Installation'" 
                class="text-muted">Select the name of the cluster where the application will be deployed</p>
              <p 
                v-else-if="modalTitle == 'Application Uninstallation'" 
                class="text-muted">Remove the application and associated resources from the multi-cloud infrastructure</p>

              <select 
                class="form-select" 
                id="k8s-cluster"
                :disabled="selectNsId == ''" 
                v-model="selectCluster"
                @change="onChangeCluster">
                <option v-if="clusterList.length === 0" value="">No cluster available</option>
                <option 
                  v-for="cluster in clusterList" 
                  :value="getClusterValue(cluster)"
                  :key="getClusterValue(cluster)">
                  {{ cluster.name || cluster.id }}
                </option>
              </select>
            </div>

            <!-- K8S :: Helm Chart -->
            <div class="mb-3">
              <label class="form-label">Helm chart</label>
              <p class="text-muted">Select the application</p>
              <select class="form-select" v-model="inputApplications" @change="onChangeCatalog">
                <option v-for="(catalog, idx) in filteredCatalogList" :key="idx" :value="catalog.name">
                  [{{ catalog.name }}] {{ catalog.helmChart?.chartVersion || "latest" }}
                </option>
              </select>
            </div>

            <div class="mb-3" v-if="modalTitle == 'Application Installation'">
              <label class="form-label">Port</label>
              <p class="text-muted">Please enter a service port for the Kubernetes service</p>
              <input type="number" class="form-control" placeholder="80" v-model="inputServicePort">
            </div>

            <div class="mb-3" v-if="modalTitle == 'Application Installation'">
              <label class="form-label">Resource Type</label>
              <select class="form-select" v-model="selectedResourceType">
                <option value="GENERAL_PURPOSE">General Purpose</option>
                <option value="CPU_INTENSIVE">CPU Intensive</option>
                <option value="MEMORY_INTENSIVE">Memory Intensive</option>
              </select>
            </div>

            <div class="mb-3" v-if="modalTitle == 'Application Installation' && showStorageClassConfig">
              <label class="form-label" :class="{ required: storageClassRequired }">Storage Class</label>
              <select
                class="form-select"
                v-model="selectedStorageClass"
                :disabled="storageClassSelectDisabled">
                <option value="" disabled>
                  {{ storageClassPlaceholder }}
                </option>
                <option
                  v-for="storageClass in storageClassList"
                  :key="storageClass.name"
                  :value="storageClass.name">
                  {{ storageClass.name }}{{ storageClass.defaultClass ? ' (default)' : '' }}
                </option>
              </select>
              <p class="text-danger mt-1 mb-0" v-if="storageClassRequired && storageClassErrorMessage">
                {{ storageClassErrorMessage }}
              </p>
            </div>

            <!-- K8S :: HPA -->
            <div class="mb-3" v-if="modalTitle == 'Application Installation'" >
              <label class="form-label">HPA Configuration</label>
              
              <!-- HPA Enabled -->
              <div class="mb-2">
                <div class="form-check">
                  <input 
                    class="form-check-input" 
                    type="checkbox" 
                    id="hpaEnabled" 
                    v-model="hpaData.hpaEnabled">
                  <label class="form-check-label" for="hpaEnabled">
                    Enable HPA (Horizontal Pod Autoscaler)
                  </label>
                </div>
              </div>

              <!-- HPA Fields (shown when enabled) -->
              <div v-if="hpaData.hpaEnabled" class="d-flex justify-content-between">
                <!-- min Replicas -->
                <div>
                  <label class="form-label required">
                    minReplicas
                  </label>
                  <input 
                    type="number" 
                    class="form-control w-90-per" 
                    placeholder="1" 
                    v-model="hpaData.hpaMinReplicas" />
                </div>

                <!-- max Replicas -->
                <div>
                  <label class="form-label required">
                    maxReplicas
                  </label>
                  <input 
                    type="number" 
                    class="form-control w-90-per" 
                    placeholder="10" 
                    v-model="hpaData.hpaMaxReplicas" />
                </div>

                <!-- CPU -->
                <div>
                  <label class="form-check-label mb-2">
                    CPU (%)
                  </label>
                  <input 
                    type="number" 
                    class="form-control w-80-per d-inline" 
                    placeholder="60" 
                    v-model="hpaData.hpaCpuUtilization" /> %
                </div>

                <!-- Memory -->
                <div>
                  <label class="form-check-label mb-2">
                    MEMORY (%)
                  </label>
                  <input 
                    type="number" 
                    class="form-control w-80-per d-inline" 
                    placeholder="80" 
                    v-model="hpaData.hpaMemoryUtilization" /> %
                </div>
              </div>
            </div>

            <div class="mb-3" v-if="modalTitle == 'Application Installation'">
              <label class="form-label">Workload Rebalancing</label>

              <div class="mb-2">
                <div class="form-check">
                  <input
                    class="form-check-input"
                    type="checkbox"
                    id="workloadRebalancingEnabled"
                    v-model="workloadRebalancingEnabled">
                  <label class="form-check-label" for="workloadRebalancingEnabled">
                    Enable Workload Rebalancing
                  </label>
                </div>
              </div>
            </div>

            <div class="mb-3" v-if="modalTitle == 'Application Installation'">
              <label class="form-label">Ingress Configuration</label>
              
              <div class="mb-2">
                <div class="form-check">
                  <input 
                    class="form-check-input" 
                    type="checkbox" 
                    id="ingressEnabled" 
                    v-model="ingressData.ingressEnabled">
                  <label class="form-check-label" for="ingressEnabled">
                    Enable Ingress
                  </label>
                </div>
              </div>

              <div v-if="ingressData.ingressEnabled">
                <div class="mb-2">
                  <label class="form-label">Host</label>
                  <input 
                    type="text" 
                    class="form-control" 
                    placeholder="example.com" 
                    v-model="ingressData.ingressHost">
                </div>

                <div class="mb-2">
                  <label class="form-label">Path</label>
                  <input 
                    type="text" 
                    class="form-control" 
                    placeholder="/" 
                    v-model="ingressData.ingressPath">
                </div>

                <div class="mb-2">
                  <label class="form-label">Ingress Class</label>
                  <input 
                    type="text" 
                    class="form-control" 
                    placeholder="nginx" 
                    v-model="ingressData.ingressClass"
                    disabled>
                </div>

                <!-- <div class="mb-2">
                  <div class="form-check">
                    <input 
                      class="form-check-input" 
                      type="checkbox" 
                      id="ingressTlsEnabled" 
                      v-model="ingressData.ingressTlsEnabled">
                    <label class="form-check-label" for="ingressTlsEnabled">
                      Enable TLS
                    </label>
                  </div>
                </div>

                <div v-if="ingressData.ingressTlsEnabled" class="mb-2">
                  <label class="form-label">TLS Secret Name</label>
                  <input 
                    type="text" 
                    class="form-control" 
                    placeholder="tls-secret" 
                    v-model="ingressData.ingressTlsSecret">
                </div> -->
              </div>
            </div>

            <div class="mb-3" v-if="modalTitle == 'Application Installation' && showObjectStorageConfig">
              <label class="form-label">Object Storage Configuration</label>

              <div class="mb-2">
                <div class="form-check">
                  <input
                    class="form-check-input"
                    type="checkbox"
                    id="objectStorageEnabled"
                    v-model="objectStorageData.enabled"
                    :disabled="objectStorageRequired">
                  <label class="form-check-label" for="objectStorageEnabled">
                    Enable Object Storage
                  </label>
                </div>
              </div>

              <div v-if="objectStorageData.enabled">
                <div class="d-flex justify-content-between">
                  <div class="w-50 me-2">
                    <label class="form-label">Target CSP</label>
                    <input type="text" class="form-control" :value="selectedClusterProvider || '-'" disabled>
                  </div>
                  <div class="w-50 ms-2">
                    <label class="form-label">Storage API</label>
                    <input type="text" class="form-control" value="S3-compatible" disabled>
                  </div>
                </div>

                <div class="mt-2 mb-2">
                  <label class="form-label">S3-compatible Endpoint</label>
                  <input
                    type="text"
                    class="form-control"
                    :placeholder="objectStorageEndpointPlaceholder"
                    v-model="objectStorageData.endpoint">
                </div>

                <div class="d-flex justify-content-between">
                  <div class="w-50 me-2">
                    <label class="form-label">Region</label>
                    <input type="text" class="form-control" :placeholder="objectStorageRegionPlaceholder" v-model="objectStorageData.region">
                  </div>
                  <div class="w-50 ms-2">
                    <label class="form-label">Bucket Name</label>
                    <input type="text" class="form-control" placeholder="object-storage-bucket" v-model="objectStorageData.bucket">
                  </div>
                </div>

                <div class="d-flex justify-content-between mt-2">
                  <div class="w-50 me-2">
                    <label class="form-label">Access Key ID</label>
                    <input type="password" class="form-control" placeholder="access key id" v-model="objectStorageData.accessKey" autocomplete="off">
                  </div>
                  <div class="w-50 ms-2">
                    <label class="form-label">Secret Access Key</label>
                    <input type="password" class="form-control" placeholder="secret access key" v-model="objectStorageData.secretKey" autocomplete="off">
                  </div>
                </div>

                <div class="d-flex gap-4 mt-3">
                  <div class="form-check">
                    <input class="form-check-input" type="checkbox" id="forcePathStyle" v-model="objectStorageData.forcePathStyle">
                    <label class="form-check-label" for="forcePathStyle" title="On: endpoint/bucket/object. Off: bucket.endpoint/object.">Use path-style URL</label>
                  </div>
                </div>

                <div class="alert mt-3" :class="objectStorageCheckResult.success ? 'alert-success' : 'alert-danger'" v-if="objectStorageCheckResult">
                  <div>{{ objectStorageCheckResult.success ? 'Object Storage: SUCCESS' : 'Object Storage: FAILED' }}</div>
                  <ul class="mb-0 ps-3">
                    <li v-for="check in objectStorageCheckResult.checks" :key="check.name">
                      {{ check.name }} - {{ check.success ? 'OK' : 'FAIL' }}
                    </li>
                  </ul>
                </div>
              </div>
            </div>
          </template>
        </div>

        <!-- Footer -->
        <div 
          class="modal-footer d-flex justify-content-between">
          <a 
            class="btn btn-link link-secondary" 
            data-bs-dismiss="modal" 
            @click="setInit">
            Cancel
          </a>

          <div>
            <button
              v-if="modalTitle == 'Application Installation' && shouldRunObjectStorageCheck"
              class="btn btn-outline-danger ms-auto me-1"
              @click="runObjectStorageCheck()"
              :disabled="objectStorageChecking || objectStorageCheckPassed"
              title="Writes, reads, and deletes a temporary object in the selected bucket.">
              {{ objectStorageChecking ? 'Checking...' : 'Storage Check' }}
            </button>
            <button 
              v-if="modalTitle == 'Application Installation'" 
              class="btn btn-danger ms-auto me-1" 
              @click="specCheck" 
              :disabled="!specCheckFlag || Boolean(projectScopeError)">
              Spec Check
            </button>
            <button 
              class="btn btn-primary ms-auto" 
              data-bs-dismiss="modal" 
              @click="runInstall" 
              :disabled="deployDisabled">
              Deploy
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import { useToast } from 'vue-toastification';
import { onMounted, watch, computed } from 'vue';
// @ts-ignore
import _, { slice } from 'lodash';
import { getNsInfo, getMciInfo, getVmInfo, getClusterInfo } from '@/api/tumblebug'
import { getK8sStorageClasses, getSoftwareCatalogList, k8sSpecCheck, objectStorageSmokeCheck, runK8SInstall, runAction, runVmInstall, vmSpecCheck } from '@/api/softwareCatalog'
import { type SoftwareCatalog } from '@/views/type/type'
import { useUserStore } from '@/stores/user'

interface Props {
  nsId: string
  title: string
}
const toast = useToast()
const userStore = useUserStore()

const props = defineProps<Props>()
const modalTitle = computed(() => props.title);

const normalizeScopeValues = (value: unknown): string[] => {
  const values = Array.isArray(value) ? value : [value]

  return values
    .flatMap((item) => typeof item === 'string' ? item.split(',') : [item])
    .map((item: any) => String(item?.id || item?.name || item || '').trim())
    .filter(Boolean)
}

const firstScopeValue = (...values: unknown[]) => {
  for (const value of values) {
    const normalized = normalizeScopeValues(value)
    if (normalized.length > 0) return normalized[0]
  }
  return ''
}

const projectInfo = computed(() => userStore.projectInfo || {})
const workspaceInfo = computed(() => userStore.workspaceInfo || {})
const projectNsId = computed(() => firstScopeValue(
  projectInfo.value.ns_id,
  projectInfo.value.nsId
))
const projectMciIds = computed(() => normalizeScopeValues(
  projectInfo.value.mci_ids
    ?? projectInfo.value.mciIds
    ?? projectInfo.value.mci_id
    ?? projectInfo.value.mciId
))
const projectClusterIds = computed(() => normalizeScopeValues(
  projectInfo.value.cluster_ids
    ?? projectInfo.value.clusterIds
    ?? projectInfo.value.cluster_id
    ?? projectInfo.value.clusterId
))
const hasProjectContext = computed(() => Boolean(
  firstScopeValue(projectInfo.value.id, projectInfo.value.name, projectNsId.value)
))
const isNamespaceLocked = computed(() => hasProjectContext.value && Boolean(projectNsId.value))
const projectContextLabel = computed(() => {
  const workspace = firstScopeValue(workspaceInfo.value.name, workspaceInfo.value.id)
  const project = firstScopeValue(projectInfo.value.name, projectInfo.value.id)
  return [workspace, project].filter(Boolean).join(' / ') || 'the selected project'
})
const projectContextKey = computed(() => JSON.stringify([
  firstScopeValue(workspaceInfo.value.id, workspaceInfo.value.name),
  firstScopeValue(projectInfo.value.id, projectInfo.value.name),
  projectNsId.value,
  projectMciIds.value,
  projectClusterIds.value
]))

const infraList = ref([] as any)
const nsIdList = ref([] as any)
const mciList = ref([] as any)
const vmList = ref([] as any)
const originalVmList = ref([] as any)
const catalogList = ref([] as Array<SoftwareCatalog>)

const selectInfra = ref("" as string)
const selectNsId = ref("" as string)
const selectMci = ref("" as string)
const selectVm = ref("" as string)
const selectedVmList = ref([] as Array<string>)
const selectDeploymentType = ref("Standalone" as string)
const hpaData = ref({} as any)
const workloadRebalancingEnabled = ref(false)
const ingressData = ref({} as any)
const objectStorageData = ref({} as any)
const objectStorageCheckResult = ref(null as any)
const objectStorageChecking = ref(false as boolean)
const selectedResourceType = ref("GENERAL_PURPOSE" as string)
const storageClassList = ref([] as any[])
const selectedStorageClass = ref("" as string)
const storageClassLoading = ref(false as boolean)
const storageClassLoadError = ref(false as boolean)

const clusterList = ref([] as any)
const selectCluster = ref("" as string)
const inputApplications = ref("" as string)
const inputServicePort = ref("" as string)
const specCheckFlag = ref(true as boolean)
const selectedCatalogIdx = ref(0 as number)
const projectScopeError = ref('')
let resourceLoadSequence = 0

const getNamespaceValue = (namespace: any) => namespace?.id || namespace?.name || ''
const getClusterValue = (cluster: any) => cluster?.name || cluster?.id || ''
const matchesScope = (resource: any, allowedIds: string[]) => {
  if (allowedIds.length === 0) return true

  const resourceIds = [resource?.id, resource?.name, resource?.uid]
    .map((value) => String(value || '').trim().toLowerCase())
    .filter(Boolean)
  const normalizedAllowedIds = allowedIds.map((value) => value.toLowerCase())
  return resourceIds.some((value) => normalizedAllowedIds.includes(value))
}

const clearTargetResources = () => {
  nsIdList.value = []
  mciList.value = []
  vmList.value = []
  originalVmList.value = []
  clusterList.value = []
  selectNsId.value = ''
  selectMci.value = ''
  selectVm.value = ''
  selectedVmList.value = []
  selectCluster.value = ''
  projectScopeError.value = ''
}

// watch(modalTitle, async () => {
//   await setInit();
// });

// Handle target infrastructure changes
watch(selectInfra, async (newValue) => {
  if (_.isEmpty(selectNsId.value)) return;
  
  if (newValue === 'VM') {
    // Reset VM related data
    selectMci.value = "";
    selectVm.value = "";
    selectedVmList.value = [];
    vmList.value = [];
    originalVmList.value = [];
    
    // Fetch MCI list
    await _getMciName();
  } else if (newValue === 'K8S') {
    // Reset K8S related data
    selectCluster.value = "";
    
    // Fetch Cluster list
    await _getClusterName();
  }
  
  // Reset application selection
  inputApplications.value = "";
  onChangeForm();
});

watch(objectStorageData, () => {
  objectStorageCheckResult.value = null
}, { deep: true })

watch(selectedStorageClass, () => {
  onChangeForm()
})

watch(projectContextKey, async (newContext, previousContext) => {
  if (newContext === previousContext) return

  resourceLoadSequence += 1
  clearTargetResources()
  inputApplications.value = ''
  selectedCatalogIdx.value = 0
  setSpecCheckFlag()

  const modalElement = document.getElementById('install-form')
  if (modalElement?.classList.contains('show')) {
    await setInit()
  }
})

// Handle deployment type changes
watch(selectDeploymentType, () => {
  if (selectDeploymentType.value === "Standalone") {
    // Reset selected VMs when changing to Standalone mode
    selectedVmList.value = [];
    // Restore vmList from originalVmList
    vmList.value = [...originalVmList.value];
  } else if (selectDeploymentType.value === "Clustering") {
    // Reset selected VMs when changing to Clustering mode
    selectedVmList.value = [];
    // Restore vmList from originalVmList
    vmList.value = [...originalVmList.value];
  }
});

onMounted(async () => {
  const modalElement: any = document.getElementById('install-form');
  // Open Modal Action 
  modalElement.addEventListener('show.bs.modal', async() => {
    await setInit()
    await _getSoftwareCatalogList()
  });
})

const setInit = async () => {
  const loadSequence = ++resourceLoadSequence
  selectInfra.value = "VM"
  clearTargetResources()
  selectDeploymentType.value = "Standalone"
  hpaData.value = {
    hpaEnabled: false,
    hpaMinReplicas: 1,
    hpaMaxReplicas: 10,
    hpaCpuUtilization: 60,
    hpaMemoryUtilization: 80
  }
  workloadRebalancingEnabled.value = false
  ingressData.value = {
    ingressEnabled: false,
    ingressHost: '',
    ingressPath: '/',
    ingressClass: 'nginx',
    ingressTlsEnabled: false,
    ingressTlsSecret: ''
  }
  objectStorageData.value = getDefaultObjectStorageData()
  objectStorageCheckResult.value = null
  objectStorageChecking.value = false
  storageClassList.value = []
  selectedStorageClass.value = ""
  storageClassLoading.value = false
  storageClassLoadError.value = false
  selectedResourceType.value = "GENERAL_PURPOSE"
  inputServicePort.value = ""
  inputApplications.value = ""
  selectedCatalogIdx.value = 0

  setInfraList()
  setSpecCheckFlag()

  await _getNsId(loadSequence)
}

const normalizeIngressHost = (host: string) => {
  let normalized = (host || '').trim()
  if (!normalized) return normalized

  normalized = normalized.replace(/^[a-zA-Z][a-zA-Z0-9+.-]*:\/\//, '')
  const atIndex = normalized.lastIndexOf('@')
  if (atIndex >= 0) normalized = normalized.slice(atIndex + 1)

  const delimiterIndex = normalized.search(/[/?#]/)
  if (delimiterIndex >= 0) normalized = normalized.slice(0, delimiterIndex)

  const firstColonIndex = normalized.indexOf(':')
  if (firstColonIndex >= 0 && normalized.indexOf(':', firstColonIndex + 1) < 0) {
    normalized = normalized.slice(0, firstColonIndex)
  }

  return normalized.trim().toLowerCase()
}

const _getSoftwareCatalogList = async () => {
  await getSoftwareCatalogList("").then(({ data }) => {
    catalogList.value = data
  })
}

const setInfraList = () => {
  infraList.value = [
    {
      key: "VM",
      value: "VM"
    },
    {
      key: "k8s",
      value: "K8S"
    }
  ]
}

const setSpecCheckFlag = () => {
  if (modalTitle.value === 'Application Uninstallation')
    specCheckFlag.value = false
  else
    specCheckFlag.value = true
}

const _getNsId = async (loadSequence = resourceLoadSequence) => {
  try {
    const { data } = await getNsInfo()
    if (loadSequence !== resourceLoadSequence) return

    const namespaces = Array.isArray(data) ? data : []
    if (hasProjectContext.value) {
      if (_.isEmpty(projectNsId.value)) {
        projectScopeError.value = `Project "${projectContextLabel.value}" has no namespace mapping.`
        return
      }

      const scopedNamespace = namespaces.find((namespace: any) =>
        matchesScope(namespace, [projectNsId.value])
      )
      if (!scopedNamespace) {
        projectScopeError.value = `Namespace "${projectNsId.value}" assigned to this project was not found.`
        return
      }

      nsIdList.value = [scopedNamespace]
      selectNsId.value = getNamespaceValue(scopedNamespace)
    } else {
      nsIdList.value = namespaces
      const preferredNsId = firstScopeValue(props.nsId)
      const preferredNamespace = preferredNsId
        ? namespaces.find((namespace: any) => matchesScope(namespace, [preferredNsId]))
        : undefined
      const selectedNamespace = preferredNamespace || namespaces[0]
      selectNsId.value = selectedNamespace ? getNamespaceValue(selectedNamespace) : ''
    }

    if (!_.isEmpty(selectNsId.value)) {
      if (selectInfra.value === 'VM') await _getMciName(loadSequence)
      else if (selectInfra.value === 'K8S') await _getClusterName(loadSequence)
    }
  } catch (error) {
    if (loadSequence !== resourceLoadSequence) return
    projectScopeError.value = 'Namespaces could not be loaded for the selected project.'
  }
}

const _getMciName = async (loadSequence = resourceLoadSequence) => {
  projectScopeError.value = ''
  try {
    const { data } = await getMciInfo(selectNsId.value)
    if (loadSequence !== resourceLoadSequence) return

    const allMcis = Array.isArray(data) ? data : []
    mciList.value = allMcis.filter((mci: any) => matchesScope(mci, projectMciIds.value))
    if (mciList.value.length > 0) {
      selectMci.value = mciList.value[0].id || mciList.value[0].name
      await _getVmName(loadSequence)
    } else {
      selectMci.value = ''
      projectScopeError.value = projectMciIds.value.length > 0
        ? `Infra "${projectMciIds.value.join(', ')}" assigned to this project was not found.`
        : 'No VM infrastructure is available in this project.'
    }
  } catch (error) {
    if (loadSequence !== resourceLoadSequence) return
    projectScopeError.value = 'VM infrastructure could not be loaded for the selected project.'
  }
}

const _getVmName = async (loadSequence = resourceLoadSequence) => {
  const params = {
    nsId: selectNsId.value,
    mciId: selectMci.value
  }
  try {
    const { data } = await getVmInfo(params)
    if (loadSequence !== resourceLoadSequence) return

    originalVmList.value = Array.isArray(data?.node) ? data.node : []
    // Set vmList excluding VMs that are already in selectedVmList
    vmList.value = originalVmList.value.filter((vm: any) => 
      !selectedVmList.value.includes(vm.id)
    )
    selectVm.value = ''
    if (vmList.value.length === 0) {
      projectScopeError.value = 'No VM is available in the infrastructure assigned to this project.'
    }
  } catch (error) {
    if (loadSequence !== resourceLoadSequence) return
    projectScopeError.value = 'VMs could not be loaded for the selected project.'
  }
}

const _getClusterName = async (loadSequence = resourceLoadSequence) => {
  projectScopeError.value = ''
  try {
    const { data } = await getClusterInfo(selectNsId.value)
    if (loadSequence !== resourceLoadSequence) return

    const allClusters = Array.isArray(data) ? data : []
    clusterList.value = allClusters.filter((cluster: any) => matchesScope(cluster, projectClusterIds.value))
    if (clusterList.value.length > 0) {
      selectCluster.value = getClusterValue(clusterList.value[0])
    } else {
      selectCluster.value = ''
      projectScopeError.value = projectClusterIds.value.length > 0
        ? `Cluster "${projectClusterIds.value.join(', ')}" assigned to this project was not found.`
        : 'No Kubernetes cluster is available in this project.'
    }
    objectStorageData.value = getDefaultObjectStorageData()
    objectStorageCheckResult.value = null
    await fetchStorageClasses()
  } catch (error) {
    if (loadSequence !== resourceLoadSequence) return
    projectScopeError.value = 'Kubernetes clusters could not be loaded for the selected project.'
  }
}

const fetchStorageClasses = async () => {
  storageClassList.value = []
  selectedStorageClass.value = ""
  storageClassLoadError.value = false

  if (
    selectInfra.value !== 'K8S'
    || !supportsStorageClassConfig.value
    || _.isEmpty(selectNsId.value)
    || _.isEmpty(selectCluster.value)
  ) {
    return
  }

  storageClassLoading.value = true
  try {
    const { data } = await getK8sStorageClasses({
      namespace: selectNsId.value,
      clusterName: selectCluster.value
    })
    storageClassList.value = Array.isArray(data) ? data : []
    selectedStorageClass.value = getInitialStorageClass(storageClassList.value)
  } catch (error) {
    storageClassLoadError.value = true
    selectedStorageClass.value = ""
  } finally {
    storageClassLoading.value = false
  }
}

const getInitialStorageClass = (items: any[]) => {
  const defaultClass = items.find((item: any) => item.defaultClass)
  return defaultClass?.name || items[0]?.name || ""
}

const onChangeNsId = async () => {
  selectedVmList.value = [];
  await _getMciName(resourceLoadSequence);
  onChangeForm();
}

const onChangeMci = async () => {
  selectedVmList.value = [];
  projectScopeError.value = ''
  await _getVmName(resourceLoadSequence);
  onChangeForm();
}

const onSelectNamespace = async () =>{
  await _getClusterName(resourceLoadSequence);
  onChangeForm();
}

const onChangeForm = () => {
  if(modalTitle.value === 'Application Installation')
    specCheckFlag.value = true
  
  else if(modalTitle.value === 'Application Uninstallation')
    specCheckFlag.value = false
}

const onSelectVm = () => {
  if (selectVm.value === "") return;
  
  // In Standalone mode, only one VM can be selected
  if (selectDeploymentType.value === "Standalone") {
    selectedVmList.value = [selectVm.value];
  } 
  // In Clustering mode, add after checking for duplicates
  else if (selectDeploymentType.value === "Clustering") {
    if (!selectedVmList.value.includes(selectVm.value)) {
      selectedVmList.value.push(selectVm.value);
      
      // Remove the selected VM from vmList
      const vmIndex = vmList.value.findIndex((vm: any) => vm.id === selectVm.value);
      if (vmIndex !== -1) {
        vmList.value.splice(vmIndex, 1);
      }
    }
  }
  
  // Reset selection
  selectVm.value = "";
  onChangeForm();
}

const removeVm = (index: number) => {
  const removedVmId = selectedVmList.value[index];
  selectedVmList.value.splice(index, 1);
  
  // Add back to vmList only in Clustering mode
  if (selectDeploymentType.value === "Clustering") {
    // Find the removed VM from originalVmList and add it to vmList
    const removedVm = originalVmList.value.find((vm: any) => vm.id === removedVmId);
    if (removedVm) {
      vmList.value.push(removedVm);
    }
  }
  
  onChangeForm();
}

const runInstall = async () => {
  let appList = [] as Array<String>
  let res = {} as any

  if (selectInfra.value === 'VM') {
    // History: The initial design has changed, currently only sending 1 Application (previously it could receive multiple apps)
    appList = inputApplications.value.split(",").map(item => item.toLowerCase().trim());
    
    let params = {} as any
    if (modalTitle.value == 'Application Installation') {
      // Generate clusterName (only required in Clustering mode)
      const clusterName = selectDeploymentType.value === "Clustering" 
        ? `${inputApplications.value}-cluster` 
        : `${inputApplications.value}-standalone`;
      const servicePort = inputServicePort.value === "" ? undefined : Number(inputServicePort.value);
      
      params = {
        namespace: selectNsId.value,
        mciId: selectMci.value,
        vmIds: selectedVmList.value,
        clusterName: clusterName,
        catalogId: selectedCatalogIdx.value,
        servicePort,
        username: "admin",
        deploymentType: selectInfra.value,
        vmDeploymentMode: selectDeploymentType.value.toUpperCase(),
        resourceType: selectedResourceType.value,
      }
      res = await runVmInstall(params)
    } else {
      res = await runAction(params)
    }

    if(res.data) {
      toast.success('SUCCESS')
    } else {
      toast.error('FAIL')
    }
  }

  else if (selectInfra.value === 'K8S') {
    if (!validateStorageClassSelection()) return

    // History: The initial design has changed, currently only sending 1 Application (previously it could receive multiple apps)
    appList = inputApplications.value.split(",").map(item => item.toLowerCase().trim());
    const servicePort = inputServicePort.value === "" ? undefined : Number(inputServicePort.value);
    const additionalConfig = buildK8sAdditionalConfig()
    let params = {
      namespace: selectNsId.value,
      clusterName: selectCluster.value,
      catalogId: selectedCatalogIdx.value,
      servicePort,
      username: "",
      deploymentType: selectInfra.value,
      hpaEnabled: hpaData.value.hpaEnabled,
      minReplicas: hpaData.value.hpaMinReplicas,
      maxReplicas: hpaData.value.hpaMaxReplicas,
      cpuThreshold: hpaData.value.hpaCpuUtilization,
      memoryThreshold: hpaData.value.hpaMemoryUtilization,
      workloadRebalancingEnabled: workloadRebalancingEnabled.value,
      resourceType: selectedResourceType.value,
      ingressEnabled: ingressData.value.ingressEnabled,
      ingressHost: normalizeIngressHost(ingressData.value.ingressHost),
      ingressPath: ingressData.value.ingressPath,
      ingressClass: ingressData.value.ingressClass,
      ingressTlsEnabled: ingressData.value.ingressTlsEnabled,
      ingressTlsSecret: ingressData.value.ingressTlsSecret,
      additionalConfig
    }

    if(modalTitle.value == 'Application Installation') {
      res = await runK8SInstall(params)
    } else {
      res = await runAction(params)
    }

    if(res.data) {
      toast.success('SUCCESS')
    } else {
      toast.error('FAIL')
    }
  }
}

const specCheck = async () => {
  if (projectScopeError.value) {
    toast.error(projectScopeError.value)
    return
  }

  if (selectInfra.value !== 'VM' && selectInfra.value !== 'K8S') {
    toast.error("Please Select Infra")
    return
  }
  if (!validateStorageClassSelection()) return

  const checkedValue = await specCheckCallback()
  let data = true;

  if (checkedValue == null) {
    toast.error('Please select all items')
    return;
  }

  else if (checkedValue === false) {
    let infraName = "";

    if (selectInfra.value === 'VM') infraName = "VM"
    else if (selectInfra.value === 'K8S') infraName = "CLUSTER"

    const comment = 'Your selected ' + infraName + ' has lower specifications than recommended. Would you like to continue with the installation?'
    data = confirm(comment)
  }

  if (!data) return

  toast.success('Please click RUN')
  specCheckFlag.value = false
}

const specCheckCallback = async () => {
  let result = false as boolean;

  if (selectInfra.value === 'VM') {
    if (
      selectNsId.value === "" ||
      selectMci.value === "" ||
      selectedVmList.value.length === 0 ||
      selectedCatalogIdx.value === 0) {
      return null;
    }
    else {
      // Spec check with the first VM among selected VMs (or all VMs could be checked)
      const params = {
        namespace: selectNsId.value,
        mciName: selectMci.value,
        vmName: selectedVmList.value[0],
        catalogId: selectedCatalogIdx.value 
      }

      await vmSpecCheck(params).then(({ data }) => {
        result = data
      })
    }
  }
  else if (selectInfra.value === 'K8S') {
    if (
      selectNsId.value === "" ||
      selectCluster.value === "" ||
      selectedCatalogIdx.value === 0) {
      return null;
    }
    const params = {
      namespace: selectNsId.value,
      clusterName: selectCluster.value,
      catalogId: selectedCatalogIdx.value
    }
    await k8sSpecCheck(params).then(({ data }) => {
      result = data
    })
  }

  return result;
}

const selectedCatalogInfo = computed(() => {
  return catalogList.value.find((catalog) => catalog.id === selectedCatalogIdx.value)
})

const selectedClusterProvider = computed(() => {
  const cluster = clusterList.value.find((item: any) => item.id === selectCluster.value || item.name === selectCluster.value)
  return cluster?.connectionConfig?.providerName || cluster?.connectionName || ''
})

const selectedCatalogChartName = computed(() => {
  return String(selectedCatalogInfo.value?.helmChart?.chartName || '').toLowerCase()
})

const OBJECT_STORAGE_CAPABILITY = 'object-storage'
const STORAGE_CLASS_CAPABILITY = 'storage-class'
const CONFIG_CAPABILITY_REF_TYPES = ['CAPABILITY', 'TAG']

const isLokiCatalog = computed(() => selectedCatalogChartName.value === 'loki')

const supportsStorageClassConfig = computed(() => {
  if (selectInfra.value !== 'K8S') return false
  if (!selectedCatalogInfo.value?.helmChart) return false
  if (!isLokiCatalog.value) return false
  return hasCatalogCapability(selectedCatalogInfo.value, STORAGE_CLASS_CAPABILITY)
})

const storageClassRequired = computed(() => {
  return supportsStorageClassConfig.value && isLokiCatalog.value
})

const showStorageClassConfig = computed(() => {
  return supportsStorageClassConfig.value
    && modalTitle.value === 'Application Installation'
    && (storageClassRequired.value || storageClassList.value.length > 0 || storageClassLoadError.value)
})

const storageClassSelectDisabled = computed(() => {
  return storageClassLoading.value || storageClassList.value.length <= 1
})

const storageClassPlaceholder = computed(() => {
  if (storageClassLoading.value) return 'Loading StorageClasses...'
  if (storageClassLoadError.value) return 'Failed to load StorageClasses'
  if (storageClassList.value.length === 0) return 'No StorageClass found'
  return 'Select StorageClass'
})

const storageClassErrorMessage = computed(() => {
  if (!storageClassRequired.value) return ''
  if (storageClassLoading.value) return 'StorageClass list is loading.'
  if (storageClassLoadError.value) return 'StorageClass list could not be loaded.'
  if (storageClassList.value.length === 0) return 'Loki requires a StorageClass, but none was found.'
  if (_.isEmpty(selectedStorageClass.value)) return 'Loki requires a StorageClass.'
  return ''
})

const objectStorageEndpointPlaceholder = computed(() => {
  return isAwsProvider(selectedClusterProvider.value)
    ? 'Optional: https://s3.ap-northeast-2.amazonaws.com'
    : 'https://object-storage.example.com'
})

const objectStorageRegionPlaceholder = computed(() => {
  return isAwsProvider(selectedClusterProvider.value)
    ? 'ap-northeast-2'
    : 'region from object storage service'
})

const showObjectStorageConfig = computed(() => {
  if (selectInfra.value !== 'K8S') return false
  if (!selectedCatalogInfo.value?.helmChart) return false
  return isLokiCatalog.value || hasObjectStorageCapability(selectedCatalogInfo.value)
})

const shouldRunObjectStorageCheck = computed(() => {
  return selectInfra.value === 'K8S' && showObjectStorageConfig.value && objectStorageData.value.enabled
})

const objectStorageCheckPassed = computed(() => {
  return !shouldRunObjectStorageCheck.value || objectStorageCheckResult.value?.success === true
})

const deployDisabled = computed(() => {
  return Boolean(projectScopeError.value)
    || specCheckFlag.value
    || !objectStorageCheckPassed.value
    || (storageClassRequired.value && !_.isEmpty(storageClassErrorMessage.value))
})

function getDefaultObjectStorageData(provider = selectedClusterProvider.value, enabled = objectStorageRequired.value) {
  const isAws = isAwsProvider(provider)

  return {
    enabled: Boolean(enabled),
    backendType: 's3',
    endpoint: '',
    region: '',
    bucket: '',
    accessKey: '',
    secretKey: '',
    forcePathStyle: !isAws
  }
}

function isAwsProvider(provider: string) {
  return String(provider || '').toLowerCase().includes('aws')
}

const objectStorageRequired = computed(() => {
  return selectInfra.value === 'K8S' && isLokiCatalog.value
})

function hasObjectStorageCapability(catalog: SoftwareCatalog) {
  return hasCatalogCapability(catalog, OBJECT_STORAGE_CAPABILITY)
}

function hasCatalogCapability(catalog: SoftwareCatalog, capability: string) {
  const refs = catalog.catalogRefs || []
  return refs.some((ref: any) => {
    const refType = String(ref.refType || '').toUpperCase()
    const refValue = String(ref.refValue || '').toLowerCase()
    return refValue === capability && CONFIG_CAPABILITY_REF_TYPES.includes(refType)
  })
}

function buildObjectStorageConfig() {
  return {
    enabled: objectStorageData.value.enabled,
    backendType: objectStorageData.value.backendType,
    endpoint: objectStorageData.value.endpoint,
    region: objectStorageData.value.region,
    bucket: objectStorageData.value.bucket,
    accessKey: objectStorageData.value.accessKey,
    secretKey: objectStorageData.value.secretKey,
    forcePathStyle: objectStorageData.value.forcePathStyle,
    insecure: isHttpEndpoint(objectStorageData.value.endpoint)
  }
}

function buildK8sAdditionalConfig() {
  const config = {} as Record<string, any>
  if (storageClassRequired.value && !_.isEmpty(selectedStorageClass.value)) {
    config.storageClass = selectedStorageClass.value
  }
  if (showObjectStorageConfig.value && objectStorageData.value.enabled) {
    config.objectStorage = buildObjectStorageConfig()
  }
  return Object.keys(config).length > 0 ? config : undefined
}

function validateStorageClassSelection() {
  if (!storageClassRequired.value) return true

  const message = storageClassErrorMessage.value
  if (!_.isEmpty(message)) {
    toast.error(message)
    return false
  }
  return true
}

function isHttpEndpoint(endpoint: string) {
  return String(endpoint || '').trim().toLowerCase().startsWith('http://')
}

const runObjectStorageCheck = async (showToast = true) => {
  if (!shouldRunObjectStorageCheck.value) return true

  objectStorageChecking.value = true
  objectStorageCheckResult.value = null

  const params = {
    namespace: selectNsId.value,
    clusterName: selectCluster.value,
    catalogId: selectedCatalogIdx.value,
    objectStorage: buildObjectStorageConfig()
  }

  try {
    const { data } = await objectStorageSmokeCheck(params)
    objectStorageCheckResult.value = data

    if (data?.success) {
      if (showToast) toast.success('Object Storage check succeeded')
      return true
    }

    if (showToast) toast.error('Object Storage check failed')
    return false
  } catch (error) {
    if (showToast) toast.error('Object Storage check failed')
    return false
  } finally {
    objectStorageChecking.value = false
  }
}

// Filter catalog list based on selected infrastructure
const filteredCatalogList = computed(() => {
  if (selectInfra.value === 'VM') {
    return catalogList.value.filter(catalog => catalog.packageInfo)
  } else if (selectInfra.value === 'K8S') {
    return catalogList.value.filter(catalog => catalog.helmChart)
  }
  return catalogList.value
})

const onChangeCatalog = async () => {
  if(modalTitle.value === 'Application Installation') specCheckFlag.value = true

  const catalogInfo = catalogList.value.find((catalog) => inputApplications.value === catalog.name)
  if (catalogInfo) {
    selectedCatalogIdx.value = catalogInfo.id
    inputServicePort.value = catalogInfo.defaultPort ? String(catalogInfo.defaultPort) : ""
    hpaData.value = {
      hpaEnabled: Boolean(catalogInfo.hpaEnabled),
      hpaMinReplicas: catalogInfo.minReplicas || 1,
      hpaMaxReplicas: catalogInfo.maxReplicas || 10,
      hpaCpuUtilization: catalogInfo.cpuThreshold || 60,
      hpaMemoryUtilization: catalogInfo.memoryThreshold || 80
    }
    ingressData.value = {
      ingressEnabled: Boolean(catalogInfo.ingressEnabled),
      ingressHost: catalogInfo.ingressHost || '',
      ingressPath: catalogInfo.ingressPath || '/',
      ingressClass: catalogInfo.ingressClass || 'nginx',
      ingressTlsEnabled: Boolean(catalogInfo.ingressTlsEnabled),
      ingressTlsSecret: catalogInfo.ingressTlsSecret || ''
    }
    objectStorageData.value = getDefaultObjectStorageData()
    objectStorageCheckResult.value = null
  }

  await fetchStorageClasses()
}

const onChangeCluster = async () => {
  if(modalTitle.value === 'Application Installation') specCheckFlag.value = true
  objectStorageData.value = getDefaultObjectStorageData()
  objectStorageCheckResult.value = null
  await fetchStorageClasses()
}

</script>
<style scoped>
.w-80-per {
  width: 80% !important;
}
.w-90-per {
  width: 90% !important;
}
</style>
