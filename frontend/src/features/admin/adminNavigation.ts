import {
  Briefcase,
  EyeOff,
  Gavel,
  IdCard,
  LayoutDashboard,
  ScrollText,
  ShieldCheck,
  SlidersHorizontal,
  UserCheck,
  Users,
  type LucideIcon,
} from 'lucide-react'
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
  { label: 'screen.admin.nav.users', to: '/quan-tri/nguoi-dung', icon: Users },
  { label: 'screen.admin.nav.mentors', to: '/quan-tri/nguoi-huong-dan', icon: UserCheck, count: 'mentorApplications' },
  { label: 'screen.admin.nav.studentCards', to: '/quan-tri/the-sinh-vien', icon: IdCard, count: 'studentCards' },
  { label: 'screen.admin.nav.jobs', to: '/quan-tri/cong-viec', icon: Briefcase },
  { label: 'screen.admin.nav.disputes', to: '/quan-tri/tranh-chap', icon: Gavel, count: 'disputes' },
  { label: 'screen.admin.nav.moderation', to: '/quan-tri/kiem-duyet', icon: EyeOff },
  { label: 'screen.admin.nav.audit', to: '/quan-tri/nhat-ky', icon: ScrollText },
  { label: 'screen.admin.nav.rules', to: '/quan-tri/quy-tac', icon: SlidersHorizontal },
  { label: 'screen.admin.nav.administrators', to: '/quan-tri/quan-tri-vien', icon: ShieldCheck },
]
