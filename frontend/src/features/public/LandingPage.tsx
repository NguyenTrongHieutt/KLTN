import { FlaskConical, Globe } from 'lucide-react'
import { Link } from 'react-router'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { buttonVariants } from '@/components/ui/button-variants'
import { TermHint, TermScope } from '@/components/TermHint'
import { rich } from '@/i18n/rich'
import { t } from '@/i18n/t'
import { cn } from '@/lib/utils'

const STAGES = ['1', '2', '3', '4', '5', '6', '7'] as const

const ROLES = [
  { label: 'screen.public.landing.roleCustomer', body: 'screen.public.landing.roleCustomerBody', primary: true },
  { label: 'screen.public.landing.roleMentor', body: 'screen.public.landing.roleMentorBody', primary: false },
  { label: 'screen.public.landing.roleStudent', body: 'screen.public.landing.roleStudentBody', primary: false },
] as const

/**
 * PUB-1, the landing page (FR-45, NFR-12, PRD §6.5 #2, UX-DR65): what vibefix does in plain
 * Vietnamese, the seven Stages, the test-money and public-code notices, and one way in for each role.
 * Every way in leads to sign-in, where the role is chosen once.
 */
export function LandingPage() {
  usePageTitle(t('screen.public.landing.title'))

  return (
    <TermScope>
      <div className="mx-auto flex max-w-reading-max flex-col gap-section-gap px-gutter-mobile py-section-gap md:px-gutter-desktop">
        <section className="flex flex-col gap-3">
          <PageHeading className="text-display-mobile md:text-display">{t('screen.public.landing.heading')}</PageHeading>
          <p>{rich('screen.public.landing.lead', { mentor: <TermHint term="mentor" lower /> })}</p>
        </section>

        <section className="flex flex-col gap-3" aria-labelledby="hua-hen">
          <h2 id="hua-hen" className="text-h2">
            {t('screen.public.landing.promisesHeading')}
          </h2>
          <ul className="flex list-disc flex-col gap-2 ps-6">
            <li>
              {rich('screen.public.landing.promisePay', {
                milestone: <TermHint term="milestone" lower />,
                accept: <TermHint term="accept" lower />,
              })}
            </li>
            <li>{t('screen.public.landing.promiseDecide')}</li>
            <li>{t('screen.public.landing.promiseNext')}</li>
          </ul>
        </section>

        <section className="flex flex-col gap-3">
          <h2 id="cac-buoc" className="text-h2">
            {t('screen.public.landing.stagesHeading')}
          </h2>
          <ol aria-labelledby="cac-buoc" className="flex flex-wrap gap-2">
            {STAGES.map((stage) => (
              <li
                key={stage}
                className="flex min-w-0 grow basis-40 flex-col gap-0.5 rounded-md border border-border bg-card px-3 py-2"
              >
                <span className="text-caption text-muted-foreground">
                  {t('screen.public.landing.stageNumber', { n: stage })}
                </span>
                <span className="text-label">{t(`screen.public.landing.stages.${stage}`)}</span>
              </li>
            ))}
          </ol>
        </section>

        <section className="flex flex-col gap-3" aria-labelledby="truoc-khi-bat-dau">
          <h2 id="truoc-khi-bat-dau" className="text-h2">
            {t('screen.public.landing.noticesHeading')}
          </h2>
          <div className="flex gap-3 rounded-lg border border-dashed border-test-mode bg-test-mode-soft p-4">
            <FlaskConical aria-hidden="true" className="mt-0.5 size-5 shrink-0 text-test-mode" />
            <div className="flex min-w-0 flex-col gap-1">
              <h3 className="text-label font-semibold">{t('screen.public.landing.testMoneyTitle')}</h3>
              <p className="text-body-sm">
                {rich('screen.public.landing.testMoneyBody', { vnpayTest: <TermHint term="vnpayThuNghiem" /> })}
              </p>
            </div>
          </div>
          <div className="flex gap-3 rounded-md border-l-4 border-info bg-info-soft p-4">
            <Globe aria-hidden="true" className="mt-0.5 size-5 shrink-0 text-info" />
            <div className="flex min-w-0 flex-col gap-1">
              <h3 className="text-label font-semibold">
                {rich('screen.public.landing.publicCodeTitle', {
                  public: <TermHint term="congKhai" />,
                  github: <TermHint term="github" />,
                })}
              </h3>
              <p className="text-body-sm">{t('screen.public.landing.publicCodeBody')}</p>
            </div>
          </div>
        </section>

        <section className="flex flex-col gap-3" aria-labelledby="vai-tro">
          <h2 id="vai-tro" className="text-h2">
            {t('screen.public.landing.rolesHeading')}
          </h2>
          <ul className="flex flex-col gap-3">
            {ROLES.map((role) => (
              <li key={role.label}>
                <Link
                  to="/dang-nhap"
                  className={cn(
                    buttonVariants({ variant: role.primary ? 'primary' : 'secondary' }),
                    'h-auto w-full flex-col items-start gap-0.5 py-3 text-start whitespace-normal',
                  )}
                >
                  <span>{t(role.label)}</span>
                  <span className={cn('text-body-sm font-normal', role.primary ? 'opacity-90' : 'text-muted-foreground')}>
                    {t(role.body)}
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        </section>
      </div>
    </TermScope>
  )
}
