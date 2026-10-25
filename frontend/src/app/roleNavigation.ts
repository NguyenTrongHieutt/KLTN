import { Bell, BriefcaseBusiness, ListChecks, Package, Search, UserRound, Users, type LucideIcon } from 'lucide-react'
import type { Session } from '@/features/identity/api'
import type { MessageKey } from '@/i18n/t'

export interface Destination {
  label: MessageKey
  to: string
  icon: LucideIcon
  /** Whether its surface exists yet; only built destinations are shown (UX-DR10). */
  built: boolean
}

const todo: Destination = { label: 'nav.todo', to: '/viec-can-lam', icon: ListChecks, built: true }
const products: Destination = { label: 'nav.products', to: '/san-pham', icon: Package, built: true }
const jobs: Destination = { label: 'nav.jobs', to: '/cong-viec', icon: BriefcaseBusiness, built: true }
const notifications: Destination = { label: 'nav.notifications', to: '/thong-bao', icon: Bell, built: true }
const findWork: Destination = { label: 'nav.findWork', to: '/tim-viec', icon: Search, built: true }
const team: Destination = { label: 'nav.team', to: '/nhom', icon: Users, built: true }
const mentorProfile: Destination = { label: 'nav.mentorProfile', to: '/ho-so', icon: UserRound, built: true }

/**
 * One route table per role (EXPERIENCE.md Navigation). A Mentor that is not approved has MEN-5 only
 * (FR-74). A Google account has no role (Story 19.1): it has the to-do list and the notifications
 * until the one shell of Story 20.6. Destinations whose surface is not built yet are left out.
 */
export function roleNavigation(session: Session): Destination[] {
  const account = session.account
  if (!account) {
    return []
  }
  const all = (() => {
    switch (account.role) {
      case 'CUSTOMER':
        return [todo, products, jobs, notifications]
      case 'MENTOR':
        return account.mentorApprovalStatus === 'APPROVED'
          ? [todo, findWork, jobs, team, notifications]
          : [mentorProfile]
      case 'STUDENT':
        return [todo, jobs, team, notifications]
      case 'ADMINISTRATOR':
        return []
      default:
        return [todo, notifications]
    }
  })()
  return all.filter((destination) => destination.built)
}
