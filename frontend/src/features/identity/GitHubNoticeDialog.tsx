import { Dialog } from '@base-ui/react/dialog'
import { ExternalLink, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { t } from '@/i18n/t'
import { problemMessage } from '@/lib/problemMessage'
import { GITHUB_NOTICE_VERSION, leaveForGitHub, useStartGitHubLink, type GitHubLinkPurpose } from './githubApi'

/**
 * N-24 (FR-80, NFR-9, UX-DR94), shown before the browser leaves for GitHub, from ACC-2's GitHub row and
 * from the GitHub gate: what vibefix receives from GitHub and what the link holds, with the notice's
 * version. Going ahead records the version, then opens GitHub's page in this tab.
 */
export function GitHubNoticeDialog({
  open,
  onOpenChange,
  purpose,
  returnTo,
  relink = false,
  beforeLeaving,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  purpose: GitHubLinkPurpose
  returnTo?: string
  relink?: boolean
  /** Runs once the link has started, just before the browser leaves: the gate keeps its draft here. */
  beforeLeaving?: () => void
}) {
  const start = useStartGitHubLink()
  const error = start.isError ? problemMessage(start.error, { module: 'identity' }).text : undefined
  return (
    <Dialog.Root
      open={open}
      onOpenChange={(next) => {
        if (!next) {
          start.reset()
        }
        onOpenChange(next)
      }}
    >
      <Dialog.Portal>
        <Dialog.Backdrop className="fixed inset-0 z-40 bg-foreground/50" />
        <Dialog.Popup className="fixed top-16 left-1/2 z-50 flex max-h-[calc(100vh-96px)] w-[min(560px,calc(100vw-32px))] -translate-x-1/2 flex-col gap-4 overflow-y-auto rounded-lg border-t-4 border-primary bg-popover p-6 text-popover-foreground shadow-md">
          <div className="flex items-start justify-between gap-3">
            <Dialog.Title className="text-h2">{t('notice.github.title')}</Dialog.Title>
            <Dialog.Close
              aria-label={t('notice.github.cancel')}
              className="rounded-md p-1 text-muted-foreground hover:bg-muted"
            >
              <X aria-hidden="true" className="size-5" />
            </Dialog.Close>
          </div>
          <Dialog.Description render={<div />} className="flex flex-col gap-2 text-body-sm">
            <p>{t('notice.github.body')}</p>
            <p>{t('notice.github.emails')}</p>
            <p>{t('notice.github.repository')}</p>
          </Dialog.Description>
          <p className="text-caption text-muted-foreground">
            {t('notice.github.versionLine', { version: GITHUB_NOTICE_VERSION })}
          </p>
          {error && (
            <p role="alert" className="text-body-sm text-destructive">
              {error}
            </p>
          )}
          <div className="flex flex-wrap justify-between gap-3">
            <Dialog.Close render={<Button variant="secondary" />}>{t('notice.github.cancel')}</Dialog.Close>
            <Button
              type="button"
              disabled={start.isPending || start.isSuccess}
              onClick={() =>
                start.mutate(
                  { purpose, returnTo },
                  {
                    onSuccess: (started) => {
                      beforeLeaving?.()
                      leaveForGitHub(started.authorizationUrl)
                    },
                  },
                )
              }
            >
              {start.isPending || start.isSuccess
                ? t('screen.verification.github.opening')
                : t(relink ? 'notice.github.relinkButton' : 'notice.github.button')}
              {!start.isPending && !start.isSuccess && <ExternalLink aria-hidden="true" className="size-4" />}
            </Button>
          </div>
        </Dialog.Popup>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
