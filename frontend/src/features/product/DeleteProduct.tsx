import { Dialog } from '@base-ui/react/dialog'
import { ExternalLink, X } from 'lucide-react'
import { useState } from 'react'
import { useNavigate } from 'react-router'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { t } from '@/i18n/t'
import { problemMessage } from '@/lib/problemMessage'
import { useDeleteProduct, type Product, type SourceApp } from './productApi'
import { domainOf } from './productText'

/** What CUS-2 shows once a Product is gone: its name, and the Source App guidance (R23). */
export interface DeletedProduct {
  name: string
  repository: string
  sourceApp?: SourceApp
}

/**
 * CUS-4 "Xóa sản phẩm" (FR-7, UX-DR41, R23): a destructive Confirmation dialog lists what is deleted
 * before anything happens; the Product's information, its acknowledgments, and its Source App binding go with
 * it, and the repository on GitHub is not touched. vibefix never uninstalls the Customer's Source App, so the
 * dialog, and CUS-2 after the deletion, tell them to deselect the repository on GitHub, or to uninstall the App
 * when no other live Product uses it, with GitHub's installation settings link. A Product a running Job pins is
 * refused.
 */
export function DeleteProduct({ product }: { product: Product }) {
  const [open, setOpen] = useState(false)
  const remove = useDeleteProduct(product.id)
  const toasts = useToasts()
  const navigate = useNavigate()
  const confirm = () =>
    remove.mutate(undefined, {
      onSuccess: () => {
        setOpen(false)
        toasts.success(t('screen.product.detail.deleted', { product: product.name }))
        const deleted: DeletedProduct = {
          name: product.name,
          repository: product.repositoryFullName,
          sourceApp: product.sourceApp,
        }
        void navigate('/san-pham', { state: { deleted } })
      },
      onError: (error) => {
        setOpen(false)
        toasts.error(problemMessage(error, { module: 'product' }).text)
      },
    })
  return (
    <section aria-labelledby="delete-heading" className="flex flex-col gap-2 rounded-lg border border-border bg-card p-5">
      <h2 id="delete-heading" className="text-h3">
        {t('screen.product.detail.deleteHeading')}
      </h2>
      <p className="text-body-sm text-muted-foreground">{t('screen.product.detail.deleteBody')}</p>
      <Button
        type="button"
        variant="secondary"
        className="w-fit border-destructive text-destructive"
        onClick={() => setOpen(true)}
      >
        {t('screen.product.detail.delete')}
      </Button>
      <Dialog.Root open={open} onOpenChange={setOpen}>
        <Dialog.Portal>
          <Dialog.Backdrop className="fixed inset-0 z-40 bg-foreground/50" />
          <Dialog.Popup className="fixed top-24 left-1/2 z-50 flex max-h-[calc(100vh-128px)] w-[min(520px,calc(100vw-32px))] -translate-x-1/2 flex-col gap-4 overflow-y-auto rounded-lg border-t-4 border-destructive bg-popover p-6 text-popover-foreground shadow-md">
            <div className="flex items-start justify-between gap-3">
              <Dialog.Title className="text-h2 break-words">
                {t('screen.product.detail.deleteTitle', { product: product.name })}
              </Dialog.Title>
              <Dialog.Close
                aria-label={t('screen.shared.close')}
                className="rounded-md p-1 text-muted-foreground hover:bg-muted"
              >
                <X aria-hidden="true" className="size-5" />
              </Dialog.Close>
            </div>
            <div className="flex flex-col gap-1.5">
              <p className="text-label font-semibold">{t('screen.product.detail.deleteWhat')}</p>
              <ul className="flex list-disc flex-col gap-1 pl-5 text-body-sm">
                <li>{t('screen.product.detail.deleteInfo')}</li>
                {product.sourceApp && <li>{t('screen.product.detail.deleteBinding')}</li>}
              </ul>
              <p className="text-body-sm text-muted-foreground">{t('screen.product.detail.deleteGithub')}</p>
            </div>
            {product.sourceApp && <SourceAppGuidance repository={product.repositoryFullName} sourceApp={product.sourceApp} />}
            <div className="flex flex-wrap justify-between gap-3">
              <Dialog.Close render={<Button variant="secondary" />}>{t('screen.product.detail.deleteBack')}</Dialog.Close>
              <Button type="button" variant="destructive" onClick={confirm} disabled={remove.isPending}>
                {t('screen.product.detail.delete')}
              </Button>
            </div>
          </Dialog.Popup>
        </Dialog.Portal>
      </Dialog.Root>
    </section>
  )
}

/**
 * R23: deselect the repository from the Source App when another live Product uses its installation, else
 * uninstall the App; both on GitHub's installation settings page (the user or the organization form).
 */
export function SourceAppGuidance({ repository, sourceApp }: { repository: string; sourceApp: SourceApp }) {
  const safe = /^https:\/\//i.test(sourceApp.settingsUrl)
  return (
    <div className="flex flex-col gap-1.5 rounded-md bg-muted p-3">
      <p className="text-label font-semibold">{t('screen.product.detail.appGuidanceHeading')}</p>
      <p className="text-body-sm">
        {sourceApp.otherProducts > 0
          ? t('screen.product.detail.appDeselect', { repo: repository })
          : t('screen.product.detail.appUninstall')}
      </p>
      {safe && (
        <a
          href={sourceApp.settingsUrl}
          target="_blank"
          rel="noopener noreferrer"
          className="inline-flex w-fit flex-wrap items-center gap-1 text-body-sm text-primary underline-offset-4 hover:underline"
        >
          {t('screen.product.detail.appSettings')}
          <ExternalLink aria-hidden="true" className="size-3.5" />
          <span className="text-caption text-muted-foreground">
            {t('screen.product.domain', { domain: domainOf(sourceApp.settingsUrl) })}
          </span>
          <span className="sr-only">{t('screen.product.newTab')}</span>
        </a>
      )}
    </div>
  )
}
