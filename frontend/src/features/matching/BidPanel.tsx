import { Dialog } from '@base-ui/react/dialog'
import { X } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router'
import { Skeleton } from '@/components/Skeleton'
import { useToasts } from '@/components/toasts'
import { TestTag } from '@/components/TestTag'
import { Button } from '@/components/ui/button'
import { buttonVariants } from '@/components/ui/button-variants'
import { CapacityBanners } from '@/features/job/CapacityBanners'
import { useMentorCapacity } from '@/features/job/jobPostApi'
import { t } from '@/i18n/t'
import { formatVnd } from '@/lib/money'
import { problemMessage } from '@/lib/problemMessage'
import { cn } from '@/lib/utils'
import { useMyApplications, useWithdrawApplication, type Application } from './applicationApi'
import { bidStateText } from './bidText'

/**
 * MEN-3's bid action (FR-13, UX-DR98): "Chào giá làm người hướng dẫn" opens FND-1, or the Mentor Job limit's plain
 * reason stands in its place; after a bid, the user's bid state with "Xem" (FND-1 with the sent bid) and, while it
 * can still be withdrawn, "Rút" behind a confirmation. No Team and no free Student is asked for (Story 21.2).
 */
export function BidPanel({ postId }: { postId: string }) {
  const mine = useMyApplications()
  const capacity = useMentorCapacity()
  if (mine.isPending) {
    return <Skeleton className="h-32 w-full" />
  }
  const existing = mine.data?.find((application) => application.jobId === postId)
  if (existing) {
    return <BidState application={existing} />
  }
  const atLimit = capacity.data?.reasons.includes('MENTOR_JOB_LIMIT') ?? false
  return (
    <section aria-labelledby="bid-heading" className="flex flex-col gap-3 rounded-lg border border-border bg-card p-5">
      <h2 id="bid-heading" className="text-h2">
        {t('screen.matching.apply.heading')}
      </h2>
      {atLimit ? (
        <CapacityBanners capacity={capacity.data} />
      ) : (
        <>
          <p className="text-body-sm text-muted-foreground">{t('screen.matching.apply.lead')}</p>
          <Link to={`/tim-viec/${postId}/chao-gia`} className={cn(buttonVariants(), 'w-fit')}>
            {t('screen.matching.apply.heading')}
          </Link>
        </>
      )}
    </section>
  )
}

/** The user's bid on MEN-3, with "Xem" and, before selection, "Rút". */
function BidState({ application }: { application: Application }) {
  const [confirming, setConfirming] = useState(false)
  const withdraw = useWithdrawApplication()
  const toasts = useToasts()
  const confirm = () =>
    withdraw.mutate(application.id, {
      onSuccess: () => {
        setConfirming(false)
        toasts.success(t('screen.matching.applied.withdrawn'))
      },
      onError: (error) => {
        setConfirming(false)
        toasts.error(problemMessage(error, { module: 'matching' }).text)
      },
    })
  const withdrawable = application.status === 'SUBMITTED' || application.status === 'ON_HOLD'
  return (
    <section aria-labelledby="bid-heading" className="flex flex-col gap-2 rounded-lg border border-border bg-card p-5">
      <h2 id="bid-heading" className="text-h2">
        {t('screen.matching.applied.readTitle')}
      </h2>
      <p role="status" className="text-label font-semibold">
        {bidStateText(application)}
      </p>
      <p className="text-body-sm">
        {formatVnd(application.priceVnd)}{' '}
        <TestTag />
        {' · '}
        {t('screen.matching.days', { n: application.timelineDays })}
      </p>
      <div className="flex flex-wrap items-center gap-3">
        <Link to={`/tim-viec/${application.jobId}/chao-gia`} className={buttonVariants({ variant: 'secondary' })}>
          {t('screen.matching.applied.view')}
        </Link>
        {withdrawable && (
          <Button type="button" variant="secondary" onClick={() => setConfirming(true)}>
            {t('screen.matching.applied.withdraw')}
          </Button>
        )}
      </div>
      <Dialog.Root open={confirming} onOpenChange={setConfirming}>
        <Dialog.Portal>
          <Dialog.Backdrop className="fixed inset-0 z-40 bg-foreground/50" />
          <Dialog.Popup className="fixed top-24 left-1/2 z-50 flex w-[min(520px,calc(100vw-32px))] -translate-x-1/2 flex-col gap-4 rounded-lg bg-popover p-6 text-popover-foreground shadow-md">
            <div className="flex items-start justify-between gap-3">
              <Dialog.Title className="text-h2">{t('screen.matching.applied.withdrawTitle')}</Dialog.Title>
              <Dialog.Close
                aria-label={t('screen.product.finding.close')}
                className="rounded-md p-1 text-muted-foreground hover:bg-muted"
              >
                <X aria-hidden="true" className="size-5" />
              </Dialog.Close>
            </div>
            <Dialog.Description className="text-body-sm">{t('screen.matching.applied.withdrawBody')}</Dialog.Description>
            <div className="flex flex-wrap justify-between gap-3">
              <Dialog.Close render={<Button variant="secondary" />}>{t('screen.matching.applied.keep')}</Dialog.Close>
              <Button type="button" onClick={confirm} disabled={withdraw.isPending}>
                {t('screen.matching.applied.withdraw')}
              </Button>
            </div>
          </Dialog.Popup>
        </Dialog.Portal>
      </Dialog.Root>
    </section>
  )
}
