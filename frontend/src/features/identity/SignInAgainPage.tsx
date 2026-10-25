import { RefreshCw } from 'lucide-react'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { buttonVariants } from '@/components/ui/button-variants'
import { t } from '@/i18n/t'
import { cn } from '@/lib/utils'
import { gitHubSignInUrl } from './api'

/**
 * "Đăng nhập lại bằng GitHub" (`/dang-nhap-lai`, FR-13, author decision 2026-10-21): a signed-in Customer
 * whose GitHub token vibefix can no longer use goes through GitHub's sign-in again, which keeps a new token
 * and closes the to-do; nothing else of the account changes.
 */
export function SignInAgainPage() {
  usePageTitle(t('screen.signInAgain.title'))
  return (
    <div className="mx-auto flex max-w-[640px] flex-col gap-4 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      <PageHeading>{t('screen.signInAgain.title')}</PageHeading>
      <p className="text-body">{t('screen.signInAgain.body')}</p>
      <p className="text-body-sm text-muted-foreground">{t('screen.signInAgain.safe')}</p>
      <a href={gitHubSignInUrl('/viec-can-lam')} className={cn(buttonVariants(), 'self-start')}>
        <RefreshCw aria-hidden="true" className="size-4" />
        {t('screen.signInAgain.button')}
      </a>
    </div>
  )
}
