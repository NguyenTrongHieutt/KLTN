import { Bell, BriefcaseBusiness, ListChecks, Package, Search, type LucideIcon } from 'lucide-react'
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

/**
 * One route table per role (EXPERIENCE.md Navigation). An Administrator, told by the account's kind (Story
 * 20.1), works in the Admin shell and has none here. A Mentor needs no approval (Story 19.7). A Google
 * account has no role (Story 19.1): besides the to-do list and the notifications it has "Tìm việc" and "Sản
 * phẩm" (Story 20.3), whose pages show the GitHub gate, until the one shell of Story 20.6 replaces the
 * transitional role. "Nhóm" is gone: the Team endpoints answer nothing since Story 20.3, and Story 21.7 deletes
 * its route. Destinations whose surface is not built yet are left out.
 */
export function roleNavigation(session: Session): Destination[] {
  const account = session.account
  if (!account || account.kind === 'ADMINISTRATOR') {
    return []
  }
  const all = (() => {
    switch (account.role) {
      case 'CUSTOMER':
        return [todo, products, jobs, notifications]
      case 'MENTOR':
        return [todo, findWork, jobs, notifications]
      case 'STUDENT':
        return [todo, jobs, notifications]
      default:
        return [todo, findWork, products, notifications]
    }
  })()
  return all.filter((destination) => destination.built)
}
