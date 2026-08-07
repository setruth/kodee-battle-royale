import { ref, onMounted, onBeforeUnmount } from 'vue'

export function useFullscreen() {
  const isFullscreen = ref(typeof document !== 'undefined' && !!document.fullscreenElement)

  const updateState = () => {
    isFullscreen.value = !!document.fullscreenElement
  }

  const toggleFullscreen = async () => {
    try {
      if (!document.fullscreenElement) {
        const el = document.documentElement as HTMLElement & {
          webkitRequestFullscreen?: () => Promise<void>
        }
        if (el.requestFullscreen) {
          await el.requestFullscreen()
        } else if (el.webkitRequestFullscreen) {
          await el.webkitRequestFullscreen()
        }
      } else {
        const doc = document as Document & {
          webkitExitFullscreen?: () => Promise<void>
        }
        if (doc.exitFullscreen) {
          await doc.exitFullscreen()
        } else if (doc.webkitExitFullscreen) {
          await doc.webkitExitFullscreen()
        }
      }
    } catch {
      /* Fullscreen request ignored/denied */
    }
  }

  onMounted(() => {
    document.addEventListener('fullscreenchange', updateState)
    document.addEventListener('webkitfullscreenchange', updateState)
  })

  onBeforeUnmount(() => {
    document.removeEventListener('fullscreenchange', updateState)
    document.removeEventListener('webkitfullscreenchange', updateState)
  })

  return {
    isFullscreen,
    toggleFullscreen,
  }
}
