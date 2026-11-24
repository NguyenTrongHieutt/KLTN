import { ReceiptText, UserRound, Wallet, type LucideIcon } from 'lucide-react'
import { Link, NavLink } from 'react-router'
import { useMyJobs } from '@/features/job/jobRoleApi'
import { t, type MessageKey } from '@/i18n/t'
import { cn } from '@/lib/utils'

export interface Destination {
  label: MessageKey
  to: string
  icon: LucideIcon
}

/** States in which a Job no longer runs, as on Trang chủ. */
const ENDED = ['CLOSED', 'CANCELLED']

/** How many running Jobs the sidebar lists before "Xem tất cả công việc". */
const SHORTCUTS = 5

/** The account pages worth one click from anywhere; the avatar menu keeps the whole list. */
const ACCOUNT: Destination[] = [
  { label: 'shell.balance', to: '/so-du', icon: Wallet },
  { label: 'shell.paymentHistory', to: '/lich-su-thanh-toan', icon: ReceiptText },
  { label: 'shell.profile', to: '/ho-so', icon: UserRound },
]

/** The sidebar's id, which the top bar's toggle names in `aria-controls`. */
export const SIDEBAR_ID = 'thanh-ben'

const group = 'px-3 pt-1 pb-1.5 text-caption font-semibold text-muted-foreground'

const row =
  'flex min-h-10 items-center gap-3 rounded-lg px-3 text-body-sm text-foreground no-underline hover:bg-muted'

/**
 * The sidebar of the signed-in shell (ui-ux-pro-max `adaptive-navigation`, `nav-label-icon`, `nav-state-active`): from
 * 768 px a 72 px rail with each destination's icon over its name, and from 1024 px a 240 px column with the name
 * beside the icon. It sticks under the top bar and scrolls on its own when it is taller than the window. `wide`
 * (1024 px and up) adds two groups under the destinations: the user's running Jobs, at most five, and the account
 * pages. `collapsed` (the top bar's toggle, from 1024 px) brings the column back to the rail, for more room on a
 * wide table or a long Agreement. Phones keep the bottom tab bar instead.
 */
export function AppSidebar({
  destinations,
  wide,
  collapsed = false,
}: {
  destinations: Destination[]
  wide: boolean
  collapsed?: boolean
}) {
  const full = wide && !collapsed
  return (
    <aside
      id={SIDEBAR_ID}
      aria-label={t('shell.sidebar')}
      className={cn(
        'sticky top-header-height hidden h-[calc(100dvh-var(--spacing-header-height))] shrink-0 self-start overflow-y-auto border-r border-border bg-card md:block',
        full ? 'w-60' : 'w-18',
      )}
    >
      <div className={cn('flex flex-col gap-3 py-3', full ? 'px-3' : 'px-1.5')}>
        <nav aria-label={t('shell.mainNav')}>
          <ul className="flex flex-col gap-1">
            {destinations.map((destination) => (
              <li key={destination.to}>
                <NavLink
                  to={destination.to}
                  className={({ isActive }) =>
                    cn(
                      'flex items-center rounded-lg no-underline',
                      full
                        ? 'min-h-11 gap-3 px-3 text-left text-label'
                        : 'flex-col gap-1 px-1 py-2 text-center text-caption leading-tight',
                      isActive ? 'bg-primary-soft font-semibold text-primary' : 'text-foreground hover:bg-muted',
                    )
                  }
                >
                  <destination.icon aria-hidden="true" className="size-5.5 shrink-0" />
                  {t(destination.label)}
                </NavLink>
              </li>
            ))}
          </ul>
        </nav>
        {full && <RunningJobs />}
        {full && (
          <nav aria-label={t('shell.accountNav')} className="border-t border-border pt-3">
            <p className={group}>{t('shell.accountNav')}</p>
            <ul className="flex flex-col gap-0.5">
              {ACCOUNT.map((page) => (
                <li key={page.to}>
                  <NavLink
                    to={page.to}
                    end
                    className={({ isActive }) => cn(row, isActive && 'bg-primary-soft font-semibold text-primary hover:bg-primary-soft')}
                  >
                    <page.icon aria-hidden="true" className="size-4.5 shrink-0 text-muted-foreground" />
                    {t(page.label)}
                  </NavLink>
                </li>
              ))}
            </ul>
          </nav>
        )}
      </div>
    </aside>
  )
}

/** The running Jobs as shortcuts: each a letter tile and the Job's title on one line, the full title on hover. */
function RunningJobs() {
  const jobs = useMyJobs()
  const running = (jobs.data ?? []).filter((job) => !ENDED.includes(job.state) && !job.left)
  if (running.length === 0) {
    return null
  }
  return (
    <nav aria-label={t('shell.runningNav')} className="border-t border-border pt-3">
      <p className={group}>{t('shell.runningNav')}</p>
      <ul className="flex flex-col gap-0.5">
        {running.slice(0, SHORTCUTS).map((job) => (
          <li key={job.jobId}>
            <NavLink
              to={`/cong-viec/${job.jobId}`}
              title={job.title}
              className={({ isActive }) => cn(row, isActive && 'bg-primary-soft font-semibold text-primary hover:bg-primary-soft')}
            >
              <span
                aria-hidden="true"
                className="flex size-7 shrink-0 items-center justify-center rounded-md bg-primary-soft text-caption font-bold text-primary uppercase"
              >
                {job.title.trim().charAt(0)}
              </span>
              <span className="truncate">{job.title}</span>
            </NavLink>
          </li>
        ))}
      </ul>
      {running.length > SHORTCUTS && (
        <Link to="/cong-viec" className="mt-1 block px-3 py-1.5 text-caption text-primary underline-offset-4 hover:underline">
          {t('shell.runningAll')}
        </Link>
      )}
    </nav>
  )
}
