import { useLocation } from 'react-router'
import { TabNav } from '@/components/ui/tab-nav'
import { t } from '@/i18n/t'

/** "Hồ sơ và xác minh"'s two tabs: ACC-1 "Hồ sơ" and ACC-2 "Xác minh" (EXPERIENCE.md Surfaces). */
export function ProfileTabs() {
  const { pathname } = useLocation()
  return (
    <TabNav
      label={t('screen.profile.tabs.label')}
      current={pathname.replace(/\/$/, '') === '/ho-so/xac-minh' ? 'verification' : 'profile'}
      items={[
        { key: 'profile', to: '/ho-so', label: t('screen.profile.tabs.profile') },
        { key: 'verification', to: '/ho-so/xac-minh', label: t('screen.profile.tabs.verification') },
      ]}
    />
  )
}
