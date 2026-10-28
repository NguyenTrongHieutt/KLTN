import { Menu } from '@base-ui/react/menu'
import { Check } from 'lucide-react'
import { useNavigate } from 'react-router'
import { initials } from '@/features/team/teamText'
import { t, type MessageKey } from '@/i18n/t'
import { applyTheme, DEFAULT_THEME, type ThemeChoice } from '@/theme/accountTheme'
import type { Session } from './api'
import { useSaveTheme } from './profileApi'
import { useSignOut } from './useSession'

const THEMES: { value: ThemeChoice; label: MessageKey }[] = [
  { value: 'LIGHT', label: 'theme.light' },
  { value: 'DARK', label: 'theme.dark' },
  { value: 'SYSTEM', label: 'theme.system' },
]

const item =
  'flex min-h-tap-min cursor-pointer items-center gap-2 rounded-md px-3 text-body-sm data-disabled:cursor-not-allowed data-disabled:opacity-50 data-highlighted:bg-muted'

/** The avatar menu's pages, the same for every user (UX-DR85): each shows its Empty state when there is nothing. */
const PAGES: { to: string; label: MessageKey }[] = [
  { to: '/ho-so', label: 'shell.profile' },
  { to: '/ho-so/cong-khai', label: 'shell.publicProfile' },
  { to: '/so-du', label: 'shell.balance' },
  { to: '/lich-su-thanh-toan', label: 'shell.paymentHistory' },
  { to: '/tai-khoan/du-lieu', label: 'shell.myData' },
]

/**
 * The one avatar menu (UX-DR11, UX-DR5, UX-DR85): "Hồ sơ và xác minh" (ACC-1), "Hồ sơ công khai", "Số dư thử
 * nghiệm", "Lịch sử thanh toán", "Dữ liệu của tôi", the Theme toggle ("Sáng", "Tối", "Theo hệ thống"), which
 * applies at once and saves to the account, and "Đăng xuất". Every item is always listed, whatever roles the user
 * holds in their Jobs. The avatar is initials: the CSP loads no image from GitHub.
 */
export function AccountMenu({ session, offline }: { session: Session; offline: boolean }) {
  const navigate = useNavigate()
  const signOut = useSignOut()
  const saveTheme = useSaveTheme()
  // A Google account has no GitHub username until Story 19.2 links it: its Google name stands in.
  const login = session.githubLogin ?? session.displayName ?? ''
  const theme = session.account?.theme ?? DEFAULT_THEME

  function chooseTheme(choice: ThemeChoice) {
    applyTheme(choice)
    saveTheme.mutate(choice)
  }

  return (
    <Menu.Root>
      <Menu.Trigger
        aria-label={t('shell.accountMenu', { login })}
        className="flex size-10 items-center justify-center rounded-full bg-primary-soft text-label font-bold text-primary"
      >
        {initials(login)}
      </Menu.Trigger>
      <Menu.Portal>
        <Menu.Positioner align="end" sideOffset={6} className="z-50">
          <Menu.Popup className="min-w-52 rounded-lg border border-border bg-popover p-1 text-popover-foreground shadow-md">
            {PAGES.map((page) => (
              <Menu.Item key={page.to} className={item} onClick={() => navigate(page.to)}>
                {t(page.label)}
              </Menu.Item>
            ))}
            <Menu.Group>
              <Menu.GroupLabel className="px-3 pt-2 pb-1 text-caption text-muted-foreground">
                {t('shell.theme')}
              </Menu.GroupLabel>
              <Menu.RadioGroup value={theme} onValueChange={(value) => chooseTheme(value as ThemeChoice)}>
                {THEMES.map((choice) => (
                  <Menu.RadioItem key={choice.value} value={choice.value} className={item} disabled={offline}>
                    <span className="flex size-4 items-center justify-center">
                      <Menu.RadioItemIndicator>
                        <Check aria-hidden="true" className="size-4" />
                      </Menu.RadioItemIndicator>
                    </span>
                    {t(choice.label)}
                  </Menu.RadioItem>
                ))}
              </Menu.RadioGroup>
            </Menu.Group>
            <Menu.Separator className="my-1 h-px bg-border" />
            <Menu.Item
              disabled={offline}
              className={item}
              onClick={() =>
                signOut.mutate(undefined, {
                  onSuccess: () => navigate('/', { replace: true }),
                })
              }
            >
              {t('shell.signOut')}
            </Menu.Item>
          </Menu.Popup>
        </Menu.Positioner>
      </Menu.Portal>
    </Menu.Root>
  )
}
