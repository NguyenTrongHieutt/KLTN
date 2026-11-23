import { BriefcaseBusiness, CircleCheck, FlaskConical, LockKeyhole, Package, Users } from 'lucide-react'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { buttonVariants } from '@/components/ui/button-variants'
import { TermHint, TermScope } from '@/components/TermHint'
import { signInUrl } from '@/features/identity/api'
import { rich } from '@/i18n/rich'
import { t } from '@/i18n/t'
import { cn } from '@/lib/utils'
import { Card } from '@/components/ui/card'

const STAGES = ['1', '2', '3', '4', '5', '6', '7'] as const

const ROLES = [
  { label: 'screen.public.landing.roleCustomer', body: 'screen.public.landing.roleCustomerBody', icon: Package },
  { label: 'screen.public.landing.roleMentor', body: 'screen.public.landing.roleMentorBody', icon: BriefcaseBusiness },
  { label: 'screen.public.landing.roleCrew', body: 'screen.public.landing.roleCrewBody', icon: Users },
] as const

/**
 * PUB-1, the landing page (FR-45, NFR-12, PRD §6.5 #2, UX-DR65, UX-DR89): what vibefix does in plain
 * Vietnamese with "Đăng nhập bằng Google" on the first screen and the promises beside it, the seven Stages, the
 * test-money notice and the private-copies line side by side (the public-code notice went with public repositories,
 * Story 23.5), three cards that each explain one role, and "Đăng nhập bằng Google" again below them. One account
 * holds every role, a role per Job, so the cards pick nothing. The page takes the width of the header.
 */
export function LandingPage() {
  usePageTitle(t('screen.public.landing.title'))

  return (
    <TermScope>
      <div className="mx-auto flex max-w-content-max flex-col gap-12 px-gutter-mobile py-section-gap md:px-gutter-desktop md:py-12">
        {/* The first screen says what vibefix does and offers the sign-in at once, with the promises beside it. */}
        <section className="grid items-center gap-8 lg:grid-cols-[minmax(0,1fr)_400px]">
          <div className="flex max-w-reading-max flex-col gap-4">
            <PageHeading className="text-display-mobile md:text-display">{t('screen.public.landing.heading')}</PageHeading>
            <p className="text-body">{rich('screen.public.landing.lead', { mentor: <TermHint term="mentor" lower /> })}</p>
            <div className="flex flex-wrap gap-3">
              <a href={signInUrl()} className={cn(buttonVariants(), 'w-full sm:w-auto')}>
                {t('screen.public.landing.signIn')}
              </a>
              <a href="#cac-buoc" className={cn(buttonVariants({ variant: 'secondary' }), 'w-full sm:w-auto')}>
                {t('screen.public.landing.seeStages')}
              </a>
            </div>
          </div>
          <Card as="section" aria-labelledby="hua-hen" className="flex flex-col gap-3">
            <h2 id="hua-hen" className="text-h3">
              {t('screen.public.landing.promisesHeading')}
            </h2>
            <ul className="flex flex-col gap-3 text-body-sm">
              {[
                rich('screen.public.landing.promisePay', {
                  milestone: <TermHint term="milestone" lower />,
                  accept: <TermHint term="accept" lower />,
                }),
                t('screen.public.landing.promiseDecide'),
                t('screen.public.landing.promiseNext'),
              ].map((promise, index) => (
                <li key={index} className="flex gap-2.5">
                  <CircleCheck aria-hidden="true" className="mt-0.5 size-5 shrink-0 text-success" />
                  <span>{promise}</span>
                </li>
              ))}
            </ul>
          </Card>
        </section>

        <section id="cac-buoc" aria-labelledby="cac-buoc-tieu-de" className="flex flex-col gap-4">
          <h2 id="cac-buoc-tieu-de" className="text-h2">
            {t('screen.public.landing.stagesHeading')}
          </h2>
          {/* Seven equal steps in one row from 1024 px; one under the other below it, never a lone last card. */}
          <ol aria-labelledby="cac-buoc-tieu-de" className="grid gap-2 lg:grid-cols-7 lg:gap-3">
            {STAGES.map((stage) => (
              <Card
                as="li"
                key={stage}
                padding="none"
                className="flex items-center gap-3 px-3 py-2.5 lg:flex-col lg:items-start lg:gap-2 lg:py-3"
              >
                <span
                  aria-hidden="true"
                  className="flex size-7 shrink-0 items-center justify-center rounded-full bg-primary-soft text-label font-semibold text-primary"
                >
                  {stage}
                </span>
                <span className="text-label">{t(`screen.public.landing.stages.${stage}`)}</span>
              </Card>
            ))}
          </ol>
        </section>

        <section className="flex flex-col gap-4" aria-labelledby="truoc-khi-bat-dau">
          <h2 id="truoc-khi-bat-dau" className="text-h2">
            {t('screen.public.landing.noticesHeading')}
          </h2>
          <div className="grid gap-3 md:grid-cols-2">
            <div className="flex gap-3 rounded-md border border-dashed border-test-mode bg-test-mode-soft p-4">
              <FlaskConical aria-hidden="true" className="mt-0.5 size-5 shrink-0 text-test-mode" />
              <div className="flex min-w-0 flex-col gap-1">
                <h3 className="text-label font-semibold">{t('screen.public.landing.testMoneyTitle')}</h3>
                <p className="text-body-sm">
                  {rich('screen.public.landing.testMoneyBody', { vnpayTest: <TermHint term="vnpayThuNghiem" /> })}
                </p>
              </div>
            </div>
            <div className="flex gap-3 rounded-md border-l-4 border-info bg-info-soft p-4">
              <LockKeyhole aria-hidden="true" className="mt-0.5 size-5 shrink-0 text-info" />
              <div className="flex min-w-0 flex-col gap-1">
                <h3 className="text-label font-semibold">
                  {rich('screen.public.landing.privateCodeTitle', { github: <TermHint term="github" /> })}
                </h3>
                <p className="text-body-sm">{t('screen.public.landing.privateCodeBody')}</p>
              </div>
            </div>
          </div>
        </section>

        <section className="flex flex-col gap-4" aria-labelledby="vai-tro">
          <div className="flex flex-col gap-1">
            <h2 id="vai-tro" className="text-h2">
              {t('screen.public.landing.rolesHeading')}
            </h2>
            <p className="text-body-sm text-muted-foreground">{t('screen.public.landing.rolesLead')}</p>
          </div>
          <ul className="grid gap-3 md:grid-cols-3">
            {ROLES.map((role) => (
              <Card as="li" key={role.label} className="flex gap-3">
                <role.icon aria-hidden="true" className="mt-0.5 size-5 shrink-0 text-primary" />
                <div className="flex min-w-0 flex-col gap-0.5">
                  <h3 className="text-label font-semibold">{t(role.label)}</h3>
                  <p className="text-body-sm text-muted-foreground">{t(role.body)}</p>
                </div>
              </Card>
            ))}
          </ul>
          <a href={signInUrl()} className={cn(buttonVariants(), 'w-full sm:w-auto sm:self-start')}>
            {t('screen.public.landing.signIn')}
          </a>
        </section>
      </div>
    </TermScope>
  )
}
