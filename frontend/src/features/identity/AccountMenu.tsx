import { Menu } from '@base-ui/react/menu'
import { Monitor, Moon, Sun, type LucideIcon } from 'lucide-react'
import { useNavigate } from 'react-router'
import { t, type MessageKey } from '@/i18n/t'
import { applyTheme, DEFAULT_THEME, type ThemeChoice } from '@/theme/accountTheme'
import type { Session } from './api'
import { initials } from './personText'
import { useSaveTheme } from './profileApi'
import { useSignOut } from './useSession'

const THEMES: { value: ThemeChoice; label: MessageKey; icon: LucideIcon }[] = [
  { value: 'LIGHT', label: 'theme.light', icon: Sun },
  { value: 'DARK', label: 'theme.dark', icon: Moon },
  { value: 'SYSTEM', label: 'theme.system', icon: Monitor },
]

const item =
  'flex min-h-tap-min md:min-h-control-compact cursor-pointer items-center gap-2 rounded-md px-3 text-body-sm data-disabled:cursor-not-allowed data-disabled:opacity-50 data-highlighted:bg-muted'

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
  // The avatar's letters come from the name the menu shows, not from the GitHub username.
  const letters = initials(session.displayName ?? login)
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
        {letters}
      </Menu.Trigger>
      <Menu.Portal>
        <Menu.Positioner align="end" sideOffset={6} className="z-50">
          <Menu.Popup className="w-72 rounded-lg border border-border bg-popover p-1 text-popover-foreground shadow-md">
            <div className="flex items-center gap-3 px-3 pt-2 pb-3">
              <span
                aria-hidden="true"
                className="flex size-9 shrink-0 items-center justify-center rounded-full bg-primary-soft text-label font-bold text-primary"
              >
                {letters}
              </span>
              <span className="flex min-w-0 flex-col">
                <span className="truncate text-label font-semibold">{session.displayName ?? login}</span>
                {session.githubLogin && (
                  <span className="truncate text-caption text-muted-foreground">@{session.githubLogin}</span>
                )}
              </span>
            </div>
            <Menu.Separator className="mb-1 h-px bg-border" />
            {PAGES.map((page) => (
              <Menu.Item key={page.to} className={item} onClick={() => navigate(page.to)}>
                {t(page.label)}
              </Menu.Item>
            ))}
            <Menu.Group>
              <Menu.GroupLabel className="px-3 pt-2 pb-1 text-caption text-muted-foreground">
                {t('shell.theme')}
              </Menu.GroupLabel>
              {/* DESIGN.md Theme toggle: a three-option segmented control, each option with its icon. */}
              <Menu.RadioGroup
                value={theme}
                onValueChange={(value) => chooseTheme(value as ThemeChoice)}
                className="mx-2 mb-1 flex gap-1 rounded-md bg-muted p-1"
              >
                {THEMES.map((choice) => (
                  <Menu.RadioItem
                    key={choice.value}
                    value={choice.value}
                    disabled={offline}
                    className="flex min-h-tap-min flex-1 cursor-pointer flex-col items-center justify-center gap-0.5 rounded-sm px-1 text-caption text-muted-foreground data-checked:bg-card data-checked:font-semibold data-checked:text-primary data-checked:shadow-sm data-disabled:cursor-not-allowed data-disabled:opacity-50 data-highlighted:text-foreground data-highlighted:outline-2 data-highlighted:outline-ring"
                  >
                    <choice.icon aria-hidden="true" className="size-4" />
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
