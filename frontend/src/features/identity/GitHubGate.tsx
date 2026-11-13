import { ExternalLink, Link2 } from 'lucide-react'
import { useState, type ReactNode } from 'react'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { Button } from '@/components/ui/button'
import { t } from '@/i18n/t'
import { GitHubNoticeDialog } from './GitHubNoticeDialog'
import { useGitHubLink } from './githubApi'

/**
 * The GitHub gate (UX-DR95, FR-80): it replaces the form, never the page, while GitHub is not linked. It
 * gives one reason, N-24, and one way forward: "Liên kết GitHub ↗ (github.com)". The link keeps identity
 * only (R31), so a linked account always passes. Before the browser leaves, the page keeps its unsent input
 * ({@link onLeave}, a `sessionStorage` draft), and GitHub returns to `returnTo`, where the form shows at once.
 * The API refuses the action too (`GITHUB_NOT_LINKED`), so the gate is never the only guard.
 */
export function GitHubGate({
  action,
  returnTo,
  keepsInput = true,
  onLeave,
  onBack,
  children,
}: {
  /** What the person is about to do, completing "Liên kết GitHub để …", for example "đăng ký sản phẩm". */
  action: string
  returnTo: string
  /** Whether the page holds the person's input (a form): false where nothing was typed, such as "Tìm việc". */
  keepsInput?: boolean
  onLeave: () => void
  onBack?: () => void
  children: ReactNode
}) {
  const link = useGitHubLink()
  const [notice, setNotice] = useState(false)
  const open = link.data !== undefined && link.data.linked
  return (
    <RegionState query={link} skeleton={<Skeleton className="h-48 w-full" />}>
      {open ? (
        children
      ) : (
        <section aria-labelledby="github-gate-heading" className="flex flex-col gap-4 rounded-lg border-l-4 border-primary bg-card p-5">
          <div className="flex items-start gap-3">
            <Link2 aria-hidden="true" className="mt-0.5 size-6 shrink-0 text-primary" />
            <div className="flex flex-col gap-2">
              <h2 id="github-gate-heading" className="text-h3">
                {t('screen.githubGate.heading', { action })}
              </h2>
              <p className="text-body-sm">{t('screen.githubGate.why')}</p>
              <p className="text-caption text-muted-foreground">
                {t(keepsInput ? 'screen.githubGate.kept' : 'screen.githubGate.returnsHere')}
              </p>
            </div>
          </div>
          <div className="flex flex-wrap items-center justify-between gap-3">
            {onBack ? (
              <Button type="button" variant="secondary" onClick={onBack}>
                {t('screen.product.wizard.back')}
              </Button>
            ) : (
              <span />
            )}
            <Button type="button" onClick={() => setNotice(true)}>
              {t('notice.github.button')}
              <ExternalLink aria-hidden="true" className="size-4" />
            </Button>
          </div>
          <GitHubNoticeDialog open={notice} onOpenChange={setNotice} returnTo={returnTo} beforeLeaving={onLeave} />
        </section>
      )}
    </RegionState>
  )
}
