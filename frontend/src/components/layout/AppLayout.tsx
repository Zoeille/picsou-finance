import { Outlet } from 'react-router-dom'
import { AppSidebar } from './AppSidebar'
import { MobileBottomNav } from './MobileBottomNav'
import { DegradedModeBanner } from '@/components/shared/DegradedModeBanner'

export function AppLayout() {
  return (
    <div className="flex h-screen md:p-4 md:gap-4">
      <AppSidebar />
      <main className="flex min-w-0 flex-1 flex-col overflow-hidden pb-20 md:pb-0">
        <DegradedModeBanner />
        <div className="flex-1 overflow-auto px-1 pb-1">
          <Outlet />
        </div>
      </main>
      <MobileBottomNav />
    </div>
  )
}
