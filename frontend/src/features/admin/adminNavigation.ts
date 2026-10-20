import { Gavel, IdCard, LayoutDashboard, ShieldCheck, UserCheck, type LucideIcon } from 'lucide-react'
import type { MessageKey } from '@/i18n/t'

export interface AdminDestination {
  label: MessageKey
  to: string
  icon: LucideIcon
  /** The ADM-1 queue whose waiting count the sidebar shows beside the label. */
  count?: 'studentCards' | 'mentorApplications' | 'disputes'
}

/**
 * The Admin sidebar (UX-DR13): only the destinations whose screens are built, in the frames' order.
 */
export const ADMIN_DESTINATIONS: AdminDestination[] = [
  { label: 'screen.admin.nav.overview', to: '/quan-tri', icon: LayoutDashboard },
  { label: 'screen.admin.nav.mentors', to: '/quan-tri/nguoi-huong-dan', icon: UserCheck, count: 'mentorApplications' },
  { label: 'screen.admin.nav.studentCards', to: '/quan-tri/the-sinh-vien', icon: IdCard, count: 'studentCards' },
  { label: 'screen.admin.nav.disputes', to: '/quan-tri/tranh-chap', icon: Gavel, count: 'disputes' },
  { label: 'screen.admin.nav.administrators', to: '/quan-tri/quan-tri-vien', icon: ShieldCheck },
]
