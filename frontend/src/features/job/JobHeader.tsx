import type { ReactNode } from 'react'
import { Link } from 'react-router'
import { PageHeading } from '@/app/PageHeading'
import { t } from '@/i18n/t'
import type { Job } from './jobApi'
import { JobStateBadge } from './JobStateBadge'

/** A Job screen's top (UX-DR17): the breadcrumb, the title with its state, the meta line, and the tabs. */
export function JobHeader({
  title,
  state,
  meta,
  audience,
  children,
}: {
  title: string
  state: string
  meta: string
  audience?: 'mentor'
  children: ReactNode
}) {
  return (
    <div className="flex flex-col gap-3">
      <nav aria-label={t('screen.job.detail.breadcrumb')} className="text-caption text-muted-foreground">
        <Link to="/cong-viec" className="text-primary underline-offset-4 hover:underline">
          {t('screen.job.detail.breadcrumb')}
        </Link>{' '}
        / <span className="break-words">{title}</span>
      </nav>
      <div className="flex flex-col gap-1">
        <div className="flex flex-wrap items-center gap-2">
          <PageHeading>{title}</PageHeading>
          <JobStateBadge state={state as Job['state']} audience={audience} />
        </div>
        <p className="text-caption text-muted-foreground break-words">{meta}</p>
      </div>
      {children}
    </div>
  )
}
