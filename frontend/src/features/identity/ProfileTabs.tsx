import { NavLink } from 'react-router'
import { t } from '@/i18n/t'
import { cn } from '@/lib/utils'

const tab = ({ isActive }: { isActive: boolean }) =>
  cn(
    'flex min-h-tap-min items-center border-b-2 px-3 text-label',
    isActive ? 'border-primary font-semibold text-primary' : 'border-transparent text-muted-foreground hover:text-foreground',
  )

/** "Hồ sơ và xác minh"'s two tabs: ACC-1 "Hồ sơ" and ACC-2 "Xác minh" (EXPERIENCE.md Surfaces). */
export function ProfileTabs() {
  return (
    <nav aria-label={t('screen.profile.tabs.label')} className="flex gap-1 border-b border-border">
      <NavLink to="/ho-so" end className={tab}>
        {t('screen.profile.tabs.profile')}
      </NavLink>
      <NavLink to="/ho-so/xac-minh" end className={tab}>
        {t('screen.profile.tabs.verification')}
      </NavLink>
    </nav>
  )
}
