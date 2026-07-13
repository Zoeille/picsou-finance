import { create } from 'zustand'
import { persist } from 'zustand/middleware'

export type DateFormat = 'locale' | 'iso'
export type SidebarStyle = 'current' | 'classic'

interface AppState {
  sidebarCollapsed: boolean
  demoMode: boolean
  dateFormat: DateFormat
  sidebarStyle: SidebarStyle
  hasSeenSidebarStylePrompt: boolean
  toggleSidebar: () => void
  setDemoMode: (enabled: boolean) => void
  setDateFormat: (format: DateFormat) => void
  setSidebarStyle: (style: SidebarStyle) => void
  setHasSeenSidebarStylePrompt: (seen: boolean) => void
}

export const useAppStore = create<AppState>()(
  persist(
    (set) => ({
      sidebarCollapsed: false,
      demoMode: import.meta.env.VITE_DEMO_MODE === 'true',
      dateFormat: 'locale',
      sidebarStyle: 'current',
      hasSeenSidebarStylePrompt: false,
      toggleSidebar: () => set((s) => ({ sidebarCollapsed: !s.sidebarCollapsed })),
      setDemoMode: (enabled) => set({ demoMode: enabled }),
      setDateFormat: (format) => set({ dateFormat: format }),
      setSidebarStyle: (style) => set({ sidebarStyle: style }),
      setHasSeenSidebarStylePrompt: (seen) => set({ hasSeenSidebarStylePrompt: seen }),
    }),
    {
      name: 'picsou-app',
      partialize: (s) => ({
        sidebarCollapsed: s.sidebarCollapsed,
        dateFormat: s.dateFormat,
        sidebarStyle: s.sidebarStyle,
        hasSeenSidebarStylePrompt: s.hasSeenSidebarStylePrompt,
      }),
    }
  )
)
