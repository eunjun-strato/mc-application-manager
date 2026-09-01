import router from "./router/index";
import { useUserStore } from '@/stores/user'

let messageListenerRegistered = false

router.beforeEach(async (to, from, next) => {
  if (!messageListenerRegistered) {
    window.addEventListener("message", function (event) {
      const data = event.data
      if (!data || typeof data !== 'object') return
      const accessToken = String(data.accessToken ?? '').trim()
      const workspaceId = data.workspaceInfo?.id ?? data.workspaceInfo?.Id
      const projectId = data.projectInfo?.id ?? data.projectInfo?.Id
      if (!accessToken || ['undefined', 'null'].includes(accessToken.toLowerCase()) || !workspaceId || !projectId) return

      try {
        const userinfo = useUserStore()
        userinfo.setUser(data)
      } catch (error) {
        console.error("Unable to apply Web Console project context.")
      }
    })
    messageListenerRegistered = true
  }

  next()
})
