import { Dialog } from '@base-ui/react/dialog'
import { ArrowLeft, CircleCheck, ExternalLink, TriangleAlert, X } from 'lucide-react'
import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { useSession } from '@/features/identity/useSession'
import { NotFoundScreen } from '@/features/shared/NotFoundScreen'
import { t, type MessageKey } from '@/i18n/t'
import { formatDate, formatDayMonthTime } from '@/lib/dates'
import { isNotFound, problemMessage } from '@/lib/problemMessage'
import { cn } from '@/lib/utils'
import { CleanRepositoryGuide } from './CleanRepositoryGuide'
import { guidanceGroup, KEY_PAGES } from './guidance'
import { useConfirmRotation, useFinding, type FindingDetail, type Tool } from './productApi'
import { domainOf } from './productText'

/**
 * CUS-5 (`/san-pham/{productId}/van-de/{findingId}`, FR-8, FR-65, UX-DR30, UX-DR31), a focused task
 * route: the Finding card with its plain type, file, commit, date, and masked value (no reveal, no
 * copy), why it matters, and numbered guidance. For a credential the guidance links the provider's
 * key page and ends with "Tôi đã đổi khóa", which the Confirmation dialog confirms; the card's bar
 * then turns green. Personal data shows the clean-repository steps and "Đăng ký kho mã sạch" (FR-65).
 */
export function FindingPage() {
  const { productId, findingId } = useParams()
  const finding = useFinding(productId, findingId)
  usePageTitle(t('screen.product.finding.title'))
  const role = useSession().data?.account?.role
  if ((role !== undefined && role !== 'CUSTOMER') || (finding.isError && isNotFound(finding.error))) {
    return <NotFoundScreen />
  }
  return (
    <div className="mx-auto flex max-w-[760px] flex-col gap-5 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      <RegionState query={finding} skeleton={<Skeleton className="h-96 w-full" />}>
        {finding.data && productId && <FindingDetailView productId={productId} detail={finding.data} />}
      </RegionState>
    </div>
  )
}

function FindingDetailView({ productId, detail }: { productId: string; detail: FindingDetail }) {
  const finding = detail.finding
  const personal = finding.category === 'PERSONAL_DATA'
  const resolved = finding.status === 'RESOLVED'
  const tool = toolName(detail.tools)
  return (
    <>
      <Link
        to={`/san-pham/${productId}`}
        className="flex w-fit items-center gap-1 text-label font-semibold text-primary underline-offset-4 hover:underline"
      >
        <ArrowLeft aria-hidden="true" className="size-4" />
        <span className="break-words">{t('screen.product.finding.back', { product: detail.productName })}</span>
      </Link>
      <div className="flex flex-col gap-1">
        <div className="flex flex-wrap items-center gap-2">
          <PageHeading>{t('screen.product.finding.heading')}</PageHeading>
          <span
            className={cn(
              'rounded-full px-2 py-0.5 text-caption font-semibold',
              resolved ? 'bg-success-soft text-success' : 'bg-destructive-soft text-destructive',
            )}
          >
            {t(resolved ? 'screen.product.finding.resolved' : 'screen.product.finding.open')}
          </span>
        </div>
        <p className="text-body-sm text-muted-foreground">
          {t(personal ? 'screen.product.finding.leadPersonal' : 'screen.product.finding.leadCredential')}
        </p>
      </div>
      {!resolved && (
        <div role="note" className="flex gap-3 rounded-md border-l-4 border-warning bg-warning-soft p-4">
          <TriangleAlert aria-hidden="true" className="size-5 shrink-0 text-warning" />
          <div className="flex flex-col gap-0.5">
            <p className="text-label font-semibold">{t('screen.product.finding.whyTitle')}</p>
            <p className="text-body-sm">
              {t(personal ? 'screen.product.finding.whyPersonal' : 'screen.product.finding.whyCredential')}
            </p>
          </div>
        </div>
      )}
      <article
        aria-labelledby="finding-type"
        className={cn(
          'flex flex-col gap-4 rounded-lg border border-l-4 border-border bg-card p-5',
          resolved ? 'border-l-success' : 'border-l-destructive',
        )}
      >
        <div className="flex flex-col gap-1">
          <h2 id="finding-type" className="text-h3 break-words">
            {finding.type}
          </h2>
          <p className="text-caption text-muted-foreground">
            {t('screen.product.detail.findingFile')} <span className="font-mono break-all text-foreground">{finding.path}</span>{' '}
            · {t('screen.product.detail.findingCommit')}{' '}
            <span className="font-mono text-foreground">{finding.commit.slice(0, 7)}</span> · {formatDate(finding.committedAt)}
            {personal && finding.matchCount !== undefined && (
              <> · {t('screen.product.detail.findingLines', { n: finding.matchCount })}</>
            )}
          </p>
        </div>
        <p className="font-mono text-body break-all select-none">{finding.maskedPreview}</p>
        {resolved && finding.resolvedAt && (
          <p role="status" className="flex items-center gap-2 text-body-sm text-success">
            <CircleCheck aria-hidden="true" className="size-4 shrink-0" />
            {finding.resolution === 'CLEAN_REPOSITORY'
              ? t('screen.product.finding.clean.accepted', { name: detail.cleanRepository?.repositoryFullName ?? '' })
              : t('screen.product.finding.rotatedAt', { time: formatDayMonthTime(finding.resolvedAt) })}
          </p>
        )}
        {!resolved && (personal ? <PersonalSteps path={finding.path} tool={tool} /> : <RotationSteps ruleId={finding.ruleId} tool={tool} />)}
        {!resolved && !personal && detail.canResolve && (
          <>
            <hr className="border-border" />
            <ConfirmRotation productId={productId} findingId={finding.id} />
          </>
        )}
        {!resolved && personal && detail.canResolve && (
          <>
            <hr className="border-border" />
            <CleanRepositoryGuide productId={productId} clean={detail.cleanRepository} />
          </>
        )}
        <p className="text-caption text-muted-foreground">
          {t(personal ? 'screen.product.finding.keptPersonal' : 'screen.product.finding.keptCredential')}
        </p>
      </article>
    </>
  )
}

function toolName(tools: Tool[]): string {
  const first = tools.find((tool) => tool !== 'OTHER')
  return first ? t(`screen.product.tools.${first}` as MessageKey) : t('screen.product.finding.anyTool')
}

/** The four steps of the frame: the provider's key page, its own step, the AI tool's secrets, and back here. */
function RotationSteps({ ruleId, tool }: { ruleId: string; tool: string }) {
  const group = guidanceGroup(ruleId)
  const page = KEY_PAGES[group]
  const text = (field: string) => t(`screen.product.finding.guidance.${group}.${field}` as MessageKey)
  const provider = page ? text('provider') : ''
  return (
    <div className="flex flex-col gap-2">
      <p className="text-label font-semibold">{t('screen.product.finding.howCredential')}</p>
      <ol className="flex list-decimal flex-col gap-1.5 pl-5 text-body-sm">
        <li>
          {page ? (
            <>
              {t('screen.product.finding.stepOpenPage', { provider })}{' '}
              <a
                href={page}
                target="_blank"
                rel="noopener noreferrer"
                className="inline-flex items-center gap-1 text-primary underline-offset-4 hover:underline"
              >
                {t('screen.product.finding.stepOpenLink', { provider })}
                <ExternalLink aria-hidden="true" className="size-3.5" />
                <span className="sr-only">{t('screen.product.newTab')}</span>
              </a>{' '}
              <span className="text-caption text-muted-foreground">{t('screen.product.domain', { domain: domainOf(page) })}</span>
            </>
          ) : (
            text('where')
          )}
        </li>
        <li>{text('step')}</li>
        <li>{t('screen.product.finding.stepPaste', { tool })}</li>
        <li>{t('screen.product.finding.stepReturn')}</li>
      </ol>
    </div>
  )
}

/** The CUS-5 personal-data frame's steps; "Đăng ký kho mã sạch" itself comes with Story 2.6. */
function PersonalSteps({ path, tool }: { path: string; tool: string }) {
  return (
    <div className="flex flex-col gap-2">
      <p className="text-label font-semibold">{t('screen.product.finding.howPersonal')}</p>
      <ol className="flex list-decimal flex-col gap-1.5 pl-5 text-body-sm">
        <li>{t('screen.product.finding.personalSteps.remove', { path, tool })}</li>
        <li>{t('screen.product.finding.personalSteps.newRepository', { tool })}</li>
        <li>{t('screen.product.finding.personalSteps.register')}</li>
        <li>{t('screen.product.finding.personalSteps.oldRepository')}</li>
      </ol>
    </div>
  )
}

/** "Tôi đã đổi khóa" and its Confirmation dialog, which lists what will happen (UX-DR41). */
function ConfirmRotation({ productId, findingId }: { productId: string; findingId: string }) {
  const [open, setOpen] = useState(false)
  const rotation = useConfirmRotation(productId, findingId)
  const toasts = useToasts()
  const confirm = () =>
    rotation.mutate(undefined, {
      onSuccess: () => {
        setOpen(false)
        toasts.success(t('screen.product.finding.rotatedToast'))
      },
      onError: (error) => {
        setOpen(false)
        toasts.error(problemMessage(error, { module: 'scanning' }).text)
      },
    })
  return (
    <>
      <div className="flex flex-wrap gap-3">
        <Button type="button" onClick={() => setOpen(true)}>
          {t('screen.product.finding.rotate')}
        </Button>
      </div>
      <Dialog.Root open={open} onOpenChange={setOpen}>
        <Dialog.Portal>
          <Dialog.Backdrop className="fixed inset-0 z-40 bg-foreground/50" />
          <Dialog.Popup className="fixed top-24 left-1/2 z-50 flex w-[min(520px,calc(100vw-32px))] -translate-x-1/2 flex-col gap-4 rounded-lg bg-popover p-6 text-popover-foreground shadow-md">
            <div className="flex items-start justify-between gap-3">
              <Dialog.Title className="text-h2">{t('screen.product.finding.confirmTitle')}</Dialog.Title>
              <Dialog.Close
                aria-label={t('screen.product.finding.close')}
                className="rounded-md p-1 text-muted-foreground hover:bg-muted"
              >
                <X aria-hidden="true" className="size-5" />
              </Dialog.Close>
            </div>
            <div className="flex flex-col gap-1.5">
              <p className="text-label font-semibold">{t('screen.product.finding.confirmWhat')}</p>
              <ul className="flex list-disc flex-col gap-1 pl-5 text-body-sm">
                <li>{t('screen.product.finding.confirmResolved')}</li>
                <li>{t('screen.product.finding.confirmHistory')}</li>
                <li>{t('screen.product.finding.confirmJobs')}</li>
              </ul>
            </div>
            <div className="flex flex-wrap justify-between gap-3">
              <Dialog.Close render={<Button variant="secondary" />}>{t('screen.product.finding.confirmBack')}</Dialog.Close>
              <Button type="button" onClick={confirm} disabled={rotation.isPending}>
                {t('screen.product.finding.rotate')}
              </Button>
            </div>
          </Dialog.Popup>
        </Dialog.Portal>
      </Dialog.Root>
    </>
  )
}
