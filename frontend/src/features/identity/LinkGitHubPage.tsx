import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { t } from '@/i18n/t'

/**
 * `/ho-so/xac-minh`, where the `identity.link_github` to-do leads (Story 19.1). A placeholder until
 * Story 19.2 builds ACC-2's GitHub row here.
 */
export function LinkGitHubPage() {
  usePageTitle(t('screen.linkGitHub.title'))
  return (
    <div className="mx-auto flex max-w-[640px] flex-col gap-4 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      <PageHeading>{t('screen.linkGitHub.title')}</PageHeading>
      <p className="text-body">{t('screen.linkGitHub.body')}</p>
    </div>
  )
}
