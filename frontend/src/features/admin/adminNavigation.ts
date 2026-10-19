import { IdCard, LayoutDashboard, ShieldCheck, type LucideIcon } from 'lucide-react'
import type { MessageKey } from '@/i18n/t'

export interface AdminDestination {
  label: MessageKey
  to: string
  icon: LucideIcon
  /** The ADM-1 queue whose waiting count the sidebar shows beside the label. */
  count?: 'studentCards'
}

/**
 * The Admin sidebar (UX-DR13): only the destinations whose screens are built, in the frames' order.
 * Story 1.17 adds "Người hướng dẫn", and so on.
 */
export const ADMIN_DESTINATIONS: AdminDestination[] = [
  { label: 'screen.admin.nav.overview', to: '/quan-tri', icon: LayoutDashboard },
  { label: 'screen.admin.nav.studentCards', to: '/quan-tri/the-sinh-vien', icon: IdCard, count: 'studentCards' },
  { label: 'screen.admin.nav.administrators', to: '/quan-tri/quan-tri-vien', icon: ShieldCheck },
]
