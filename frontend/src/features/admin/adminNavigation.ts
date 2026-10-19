import { LayoutDashboard, ShieldCheck, type LucideIcon } from 'lucide-react'
import type { MessageKey } from '@/i18n/t'

export interface AdminDestination {
  label: MessageKey
  to: string
  icon: LucideIcon
}

/**
 * The Admin sidebar (UX-DR13): only the destinations whose screens are built. Story 1.13 adds
 * "Thẻ sinh viên", Story 1.17 "Người hướng dẫn", and so on, in the frames' order.
 */
export const ADMIN_DESTINATIONS: AdminDestination[] = [
  { label: 'screen.admin.nav.overview', to: '/quan-tri', icon: LayoutDashboard },
  { label: 'screen.admin.nav.administrators', to: '/quan-tri/quan-tri-vien', icon: ShieldCheck },
]
