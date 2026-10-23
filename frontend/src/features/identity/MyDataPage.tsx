import { Dialog } from '@base-ui/react/dialog'
import { useState } from 'react'
import { Link } from 'react-router'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { Button } from '@/components/ui/button'
import { buttonVariants } from '@/components/ui/button-variants'
import { NotFoundScreen } from '@/features/shared/NotFoundScreen'
import { t, type MessageKey } from '@/i18n/t'
import { formatDate } from '@/lib/dates'
import { problemMessage } from '@/lib/problemMessage'
import { EXPORT_URL, useMyData, useRequestDeletion, type MyData } from './myDataApi'
import { useSession } from './useSession'

/**
 * ALL-2 "Dữ liệu của tôi" (`/tai-khoan/du-lieu`, NFR-9, NFR-10, NFR-11): what vibefix keeps and why (PUB-7), the
 * download of every module's data about the person, and the account deletion request. Once sent, the page
 * reads "Yêu cầu xóa tài khoản đã gửi ngày {date}. Quản trị viên sẽ xử lý.", with what the law keeps and what
 * keeps the request waiting. Never an Administrator's.
 */
export function MyDataPage() {
  usePageTitle(t('screen.myData.title'))
  const role = useSession().data?.account?.role
  const data = useMyData()
  if (role === 'ADMINISTRATOR') {
    return <NotFoundScreen />
  }
  return (
    <div className="mx-auto flex max-w-[860px] flex-col gap-6 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      <PageHeading>{t('screen.myData.title')}</PageHeading>
      <section aria-labelledby="my-data-kept" className="flex flex-col gap-2 rounded-lg border border-border bg-card p-5">
        <h2 id="my-data-kept" className="text-h3">
          {t('screen.myData.keptTitle')}
        </h2>
        <p className="text-body-sm">{t('screen.myData.kept')}</p>
        <Link to="/quyen-rieng-tu" className="w-fit text-body-sm text-primary underline-offset-4 hover:underline">
          {t('screen.myData.privacy')}
        </Link>
      </section>
      <section aria-labelledby="my-data-export" className="flex flex-col gap-2 rounded-lg border border-border bg-card p-5">
        <h2 id="my-data-export" className="text-h3">
          {t('screen.myData.exportTitle')}
        </h2>
        <p className="text-body-sm">{t('screen.myData.exportBody')}</p>
        <a href={EXPORT_URL} download className={buttonVariants({ variant: 'secondary', className: 'w-fit' })}>
          {t('screen.myData.export')}
        </a>
      </section>
      <RegionState query={data} skeleton={<Skeleton className="h-40 w-full" />}>
        {data.data && <Deletion data={data.data} />}
      </RegionState>
    </div>
  )
}

function Deletion({ data }: { data: MyData }) {
  const [asking, setAsking] = useState(false)
  const [error, setError] = useState<string>()
  const request = useRequestDeletion()
  return (
    <section aria-labelledby="my-data-delete" className="flex flex-col gap-3 rounded-lg border border-border bg-card p-5">
      <h2 id="my-data-delete" className="text-h3">
        {t('screen.myData.deleteTitle')}
      </h2>
      {data.deletionRequestedAt ? (
        <>
          <p role="status" className="rounded-md bg-muted p-3 text-body-sm font-semibold">
            {t('screen.myData.requested', { date: formatDate(data.deletionRequestedAt) })}
          </p>
          <p className="text-body-sm">{t('screen.myData.lawKeeps')}</p>
          {data.blockers.length > 0 && (
            <div className="flex flex-col gap-1">
              <p className="text-label">{t('screen.myData.waiting')}</p>
              <ul className="flex flex-col gap-1 text-body-sm">
                {data.blockers.map((blocker, index) => (
                  <li key={`${blocker.reason}-${blocker.jobId ?? index}`}>
                    {blocker.jobId ? (
                      <Link to={`/cong-viec/${blocker.jobId}`} className="text-primary underline-offset-4 hover:underline">
                        {t(`screen.myData.blocker.${blocker.reason}` as MessageKey)}
                      </Link>
                    ) : (
                      t(`screen.myData.blocker.${blocker.reason}` as MessageKey)
                    )}
                  </li>
                ))}
              </ul>
            </div>
          )}
          <p className="text-caption text-muted-foreground">
            {t(data.tokenKept ? 'screen.myData.githubRevoked' : 'screen.myData.githubYourself')}
          </p>
        </>
      ) : (
        <>
          <p className="text-body-sm">{t('screen.myData.deleteBody')}</p>
          <p className="text-body-sm">{t('screen.myData.lawKeeps')}</p>
          {error && (
            <p role="alert" className="text-body-sm text-destructive">
              {error}
            </p>
          )}
          <div>
            <Button type="button" variant="destructive" onClick={() => setAsking(true)}>
              {t('screen.myData.delete')}
            </Button>
          </div>
        </>
      )}
      <Dialog.Root open={asking} onOpenChange={setAsking}>
        <Dialog.Portal>
          <Dialog.Backdrop className="fixed inset-0 z-40 bg-foreground/50" />
          <Dialog.Popup className="fixed top-24 left-1/2 z-50 flex w-[min(480px,calc(100vw-32px))] -translate-x-1/2 flex-col gap-4 rounded-lg bg-popover p-6 text-popover-foreground shadow-md">
            <Dialog.Title className="text-h2">{t('screen.myData.askTitle')}</Dialog.Title>
            <Dialog.Description className="text-body-sm">{t('screen.myData.askBody')}</Dialog.Description>
            <div className="flex flex-wrap justify-between gap-3">
              <Dialog.Close render={<Button type="button" variant="secondary" />}>{t('screen.myData.back')}</Dialog.Close>
              <Button
                type="button"
                variant="destructive"
                disabled={request.isPending}
                onClick={() =>
                  request.mutate(undefined, {
                    onSuccess: () => {
                      setError(undefined)
                      setAsking(false)
                    },
                    onError: (failure) => {
                      setError(problemMessage(failure, { module: 'identity' }).text)
                      setAsking(false)
                    },
                  })
                }
              >
                {t('screen.myData.confirm')}
              </Button>
            </div>
          </Dialog.Popup>
        </Dialog.Portal>
      </Dialog.Root>
    </section>
  )
}
