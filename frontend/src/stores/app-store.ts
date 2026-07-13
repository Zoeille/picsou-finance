import { create } from 'zustand'
import { persist } from 'zustand/middleware'

export type DateFormat = 'locale' | 'iso'
export type SidebarStyle = 'current' | 'classic'

interface AppState {
  sidebarCollapsed: boolean
  demoMode: boolean
  dateFormat: DateFormat
  sidebarStyle: SidebarStyle
  toggleSidebar: () => void
  setDemoMode: (enabled: boolean) => void
  setDateFormat: (format: DateFormat) => void
  setSidebarStyle: (style: SidebarStyle) => void
}

export const useAppStore = create<AppState>()(
  persist(
    (set) => ({
      sidebarCollapsed: false,
      demoMode: import.meta.env.VITE_DEMO_MODE === 'true',
      dateFormat: 'locale',
      sidebarStyle: 'current',
      toggleSidebar: () => set((s) => ({ sidebarCollapsed: !s.sidebarCollapsed })),
      setDemoMode: (enabled) => set({ demoMode: enabled }),
      setDateFormat: (format) => set({ dateFormat: format }),
      setSidebarStyle: (style) => set({ sidebarStyle: style }),
    }),
    {
      name: 'picsou-app',
      partialize: (s) => ({
        sidebarCollapsed: s.sidebarCollapsed,
        dateFormat: s.dateFormat,
        sidebarStyle: s.sidebarStyle,
      }),
    }
  )
)
