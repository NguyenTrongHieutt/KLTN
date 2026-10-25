import { Dialog } from '@base-ui/react/dialog'
import { CircleAlert, CircleCheck, ExternalLink, X } from 'lucide-react'
import { useState } from 'react'
import { useSearchParams } from 'react-router'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { t, type MessageKey } from '@/i18n/t'
import { formatMonthYear } from '@/lib/dates'
import { problemMessage } from '@/lib/problemMessage'
import { cn } from '@/lib/utils'
import { GitHubNoticeDialog } from './GitHubNoticeDialog'
import { useGitHubLink, useUnlinkGitHub, type GitHubLink, type GitHubLinkBlocker } from './githubApi'

/** What GitHub's return says in ACC-2's address (`?ket-qua=…` or `?loi=…`), as the backend writes it. */
const RESULTS = ['da-lien-ket']

const ERRORS = ['da-lien-ket-tai-khoan-khac', 'sai-tai-khoan', 'that-bai']

/**
 * ACC-2 (`/ho-so/xac-minh`, FR-80, UX-DR91): the verification hub's GitHub row, "Bắt buộc". Not linked,
 * it starts linking after N-24; linked, it shows the username and "Xác minh qua GitHub · {tháng/năm}";
 * "Cần liên kết lại" when the kept token stopped working (`identity.relink_github`, which replaced the
 * `/dang-nhap-lai` page). "Bỏ liên kết" is disabled while something keeps the link (`GITHUB_CHANGE_BLOCKED`,
 * R14) and confirmed otherwise. GitHub's return shows its result or its error at the top. The LinkedIn
 * and student email rows come with Stories 19.4 and 19.6.
 */
export function VerificationPage() {
  usePageTitle(t('screen.verification.title'))
  const link = useGitHubLink()
  const [params] = useSearchParams()
  const result = params.get('ket-qua')
  const error = params.get('loi')
  return (
    <div className="mx-auto flex max-w-[760px] flex-col gap-6 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      <div className="flex flex-col gap-1">
        <PageHeading>{t('screen.verification.heading')}</PageHeading>
        <p className="text-body-sm text-muted-foreground">{t('screen.verification.lead')}</p>
      </div>
      {result !== null && RESULTS.includes(result) && (
        <div role="status" className="flex gap-3 rounded-md border-l-4 border-success bg-success-soft p-4">
          <CircleCheck aria-hidden="true" className="size-5 shrink-0 text-success" />
          <p className="text-body-sm">{t(`screen.verification.github.result.${result}` as MessageKey)}</p>
        </div>
      )}
      {error !== null && (
        <div role="alert" className="flex gap-3 rounded-md border-l-4 border-destructive bg-destructive-soft p-4">
          <CircleAlert aria-hidden="true" className="size-5 shrink-0 text-destructive" />
          <p className="text-body-sm">
            {t(`screen.verification.github.error.${ERRORS.includes(error) ? error : 'that-bai'}` as MessageKey)}
          </p>
        </div>
      )}
      <RegionState query={link} skeleton={<Skeleton className="h-40 w-full" />}>
        {link.data && <GitHubRow link={link.data} />}
      </RegionState>
    </div>
  )
}

function GitHubRow({ link }: { link: GitHubLink }) {
  const [notice, setNotice] = useState(false)
  const relink = link.linked && link.relinkNeeded
  const status = !link.linked ? 'notLinked' : relink ? 'relink' : 'linked'
  return (
    <section aria-labelledby="github-heading" className="flex flex-col gap-4 rounded-lg border border-border bg-card p-5">
      <div className="flex flex-wrap items-center gap-2">
        <h2 id="github-heading" className="text-h3">
          {t('screen.verification.github.name')}
        </h2>
        <span className="rounded-full bg-primary-soft px-2 py-0.5 text-caption font-semibold text-primary">
          {t('screen.verification.github.required')}
        </span>
        <span
          className={cn(
            'rounded-full px-2 py-0.5 text-caption font-semibold',
            status === 'linked' && 'bg-success-soft text-success',
            status === 'relink' && 'bg-warning-soft text-warning',
            status === 'notLinked' && 'bg-muted text-foreground',
          )}
        >
          {t(`screen.verification.github.${status}`)}
        </span>
      </div>
      {!link.linked ? (
        <div className="flex flex-col gap-3">
          <p className="text-body-sm">{t('screen.verification.github.why')}</p>
          <Button type="button" className="self-start" onClick={() => setNotice(true)}>
            {t('screen.verification.github.link')}
            <ExternalLink aria-hidden="true" className="size-4" />
          </Button>
        </div>
      ) : (
        <div className="flex flex-col gap-3">
          <p className="text-body-sm font-semibold break-all">
            {t('screen.verification.github.verifiedLine', {
              login: link.login ?? '',
              month: link.linkedAt ? formatMonthYear(link.linkedAt) : '',
            })}
          </p>
          {relink && (
            <div className="flex flex-col gap-3 rounded-md border-l-4 border-warning bg-warning-soft p-4">
              <p className="text-body-sm">{t('screen.verification.github.linkExpired')}</p>
              <p className="text-body-sm text-muted-foreground">{t('screen.verification.github.relinkSafe')}</p>
              <Button type="button" className="self-start" onClick={() => setNotice(true)}>
                {t('screen.verification.github.relinkButton')}
                <ExternalLink aria-hidden="true" className="size-4" />
              </Button>
            </div>
          )}
          <Unlink blockers={link.blockers} />
        </div>
      )}
      <GitHubNoticeDialog open={notice} onOpenChange={setNotice} purpose="PROFILE" relink={relink} />
    </section>
  )
}

/** "Bỏ liên kết": disabled, naming what keeps the link, while anything does (R14); otherwise confirmed. */
function Unlink({ blockers }: { blockers: GitHubLinkBlocker[] }) {
  const [open, setOpen] = useState(false)
  const unlink = useUnlinkGitHub()
  const toasts = useToasts()
  const blocked = blockers.length > 0
  const confirm = () =>
    unlink.mutate(undefined, {
      onSuccess: () => {
        setOpen(false)
        toasts.success(t('screen.verification.github.unlinked'))
      },
      onError: (failure) => {
        setOpen(false)
        toasts.error(problemMessage(failure, { module: 'identity' }).text)
      },
    })
  return (
    <div className="flex flex-col gap-2 border-t border-border pt-3">
      <Button
        type="button"
        variant="secondary"
        className="w-fit"
        disabled={blocked}
        aria-describedby={blocked ? 'unlink-blocked' : undefined}
        onClick={() => setOpen(true)}
      >
        {t('screen.verification.github.unlink')}
      </Button>
      {blocked && (
        <div id="unlink-blocked" className="flex flex-col gap-1 text-body-sm text-muted-foreground">
          <p>{t('screen.verification.github.changeBlocked')}</p>
          <p>{t('screen.verification.github.blockersHeading')}</p>
          <ul className="list-disc pl-5">
            {blockers.map((blocker) => (
              <li key={`${blocker.reason}-${blocker.subjectId}`} className="break-words">
                {blockerText(blocker)}
              </li>
            ))}
          </ul>
        </div>
      )}
      <Dialog.Root open={open} onOpenChange={setOpen}>
        <Dialog.Portal>
          <Dialog.Backdrop className="fixed inset-0 z-40 bg-foreground/50" />
          <Dialog.Popup className="fixed top-24 left-1/2 z-50 flex w-[min(520px,calc(100vw-32px))] -translate-x-1/2 flex-col gap-4 rounded-lg border-t-4 border-destructive bg-popover p-6 text-popover-foreground shadow-md">
            <div className="flex items-start justify-between gap-3">
              <Dialog.Title className="text-h2">{t('screen.verification.github.unlinkTitle')}</Dialog.Title>
              <Dialog.Close
                aria-label={t('notice.github.cancel')}
                className="rounded-md p-1 text-muted-foreground hover:bg-muted"
              >
                <X aria-hidden="true" className="size-5" />
              </Dialog.Close>
            </div>
            <Dialog.Description className="text-body-sm">{t('screen.verification.github.unlinkBody')}</Dialog.Description>
            <div className="flex flex-wrap justify-between gap-3">
              <Dialog.Close render={<Button variant="secondary" />}>{t('notice.github.cancel')}</Dialog.Close>
              <Button type="button" variant="destructive" onClick={confirm} disabled={unlink.isPending}>
                {unlink.isPending ? t('screen.verification.github.unlinking') : t('screen.verification.github.unlinkConfirm')}
              </Button>
            </div>
          </Dialog.Popup>
        </Dialog.Portal>
      </Dialog.Root>
    </div>
  )
}

/** "Sản phẩm {name}", "Công việc {name}", "Đơn chào giá cho {name}", or the kind alone without a name. */
function blockerText(blocker: GitHubLinkBlocker): string {
  const reason = ['LIVE_PRODUCT', 'JOB_ROLE', 'OPEN_BID'].includes(blocker.reason) ? blocker.reason : 'JOB_ROLE'
  return blocker.name
    ? t(`screen.verification.github.blocker.${reason}` as MessageKey, { name: blocker.name })
    : t(`screen.verification.github.blockerUnnamed.${reason}` as MessageKey)
}
