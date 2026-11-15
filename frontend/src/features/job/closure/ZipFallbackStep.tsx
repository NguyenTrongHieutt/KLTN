import { Download, TriangleAlert } from 'lucide-react'
import { useId } from 'react'
import { Link } from 'react-router'
import { buttonVariants } from '@/components/ui/button-variants'
import { SensitiveChangeList } from '@/features/delivery/SensitiveChangeList'
import { t, type MessageKey } from '@/i18n/t'
import { cn } from '@/lib/utils'
import { MERGE_CODE_PARAM, type FinalMerge } from './finalMergeApi'

/**
 * N-17 on JOB-1 (FR-38, UX-DR8, UX-DR55): the ZIP Fallback replaces the merge steps. It says why the
 * merge could not happen, offers "Tải mã về (tệp ZIP)" (an attachment served to the Customer alone, which
 * may contain programs), lists the Sensitive Changes, and offers "Đăng công việc ghép mã". Nothing asks
 * the Customer to run anything. While the ZIP is built, it says so.
 * <p>
 * Story 23.10a (D10) took away the Customer's own "Nhận mã dạng ZIP": they confirm the code receipt in the Closing
 * choice cards instead, and no choice of theirs starts a ZIP any more. What is left here shows a ZIP a failed Final
 * Merge or the FR-37 deadline still builds, until Story 23.10c deletes the machinery.
 */
export function ZipFallbackStep({ jobId, merge }: { jobId: string; merge: FinalMerge }) {
  const ids = useId()
  const zip = merge.zip
  if (!zip) {
    return null
  }
  if (zip.status !== 'READY' || !zip.fileId) {
    return (
      <p role="status" className="text-body-sm text-muted-foreground">
        {t('screen.closure.zip.building')}
      </p>
    )
  }
  return (
    <section aria-labelledby={`${ids}-zip`} className="flex flex-col gap-3 rounded-md border-l-4 border-warning bg-warning-soft p-4">
      <p id={`${ids}-zip`} className="flex items-start gap-2 text-body-sm">
        <TriangleAlert aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-warning" />
        {t('screen.closure.zip.lead', { reason: t(`screen.closure.zip.reason.${zip.reason}` as MessageKey) })}
      </p>
      <div className="flex flex-col gap-1">
        <a
          href={`/api/v1/files/${zip.fileId}`}
          download
          className={cn(buttonVariants(), 'w-fit no-underline')}
        >
          <Download aria-hidden="true" className="size-4" />
          {t('term.zipFallback.label')}
        </a>
        <p className="text-caption text-muted-foreground">{t('screen.closure.zip.programs')}</p>
      </div>
      <SensitiveChangeList changes={zip.sensitive} headingId={`${ids}-sensitive`} />
      <Link
        to={`/san-pham/${merge.productId}/cong-viec-moi?${MERGE_CODE_PARAM}=${jobId}`}
        className={cn(buttonVariants({ variant: 'secondary' }), 'w-fit no-underline')}
      >
        {t('screen.closure.zip.post')}
      </Link>
    </section>
  )
}
