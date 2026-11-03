import { Link } from 'react-router'
import { TestTag } from '@/components/TestTag'
import { youAreText } from '@/features/job/jobRoleApi'
import { t } from '@/i18n/t'
import { formatDayMonth } from '@/lib/dates'
import { formatVnd } from '@/lib/money'
import { cn } from '@/lib/utils'
import type { BalanceLine } from './paymentApi'
import { finalText, hasMentorLine, partyName, pendingText, pendingTone } from './partText'

/**
 * The reader's own lines (FR-44, AD-15, AD-20, UX-DR106): MEN-7's, the second section of "Lịch sử thanh toán", and a
 * Crew member's JOB-7. Each line is a Job and Milestone with the role held there: the reader's part with its
 * percentage, a zero part at 0 ₫, or a split still pending, whose pooled amount shows as "Chờ phân chia" and counts in
 * no Balance, with the way to JOB-10. The Crew's parts show only on a line whose split names the reader its Mentor,
 * and only with `crew`. With `left`, for one Job's lines, each role reads as JOB-H's for a member who left ("· đã
 * rời"). The page computes no money: every figure is the server's.
 */
export function PartLines({
  lines,
  crew,
  job = true,
  left,
}: {
  lines: BalanceLine[]
  crew: boolean
  job?: boolean
  left?: boolean
}) {
  const crewColumn = crew && hasMentorLine(lines)
  return (
    <div className="overflow-x-auto rounded-lg border border-border bg-card">
      <table className="w-full min-w-[640px] text-left text-body-sm">
        <thead className="text-caption text-muted-foreground">
          <tr className="border-b border-border">
            <th scope="col" className="px-4 py-2 font-semibold">
              {t(job ? 'screen.payment.balance.job' : 'screen.payment.balance.milestone')}
            </th>
            <th scope="col" className="px-4 py-2 font-semibold">
              {t('screen.payment.balance.date')}
            </th>
            <th scope="col" className="px-4 py-2 font-semibold">
              {t('screen.payment.amount')}
            </th>
            <th scope="col" className="px-4 py-2 font-semibold">
              {t('screen.payment.balance.yourPart')}
            </th>
            {crewColumn && (
              <th scope="col" className="px-4 py-2 font-semibold">
                {t('screen.payment.balance.crew')}
              </th>
            )}
            <th scope="col" className="px-4 py-2 font-semibold">
              {t('screen.payment.balance.terms')}
            </th>
          </tr>
        </thead>
        <tbody>
          {lines.map((line, index) => (
            <tr key={`${line.milestoneId}-${index}`} className="border-b border-border align-top last:border-b-0">
              <td className="px-4 py-2.5">
                {job && (
                  <p className="font-semibold break-words">{line.jobTitle || t('screen.payment.balance.deletedJob')}</p>
                )}
                <p className={cn(job ? 'text-caption text-muted-foreground' : 'font-semibold')}>
                  {line.milestoneTitle
                    ? t('screen.payment.balance.milestoneLine', { n: line.number, title: line.milestoneTitle })
                    : t('screen.payment.balance.milestoneNumber', { n: line.number })}
                </p>
                {line.role && (
                  <p className="text-caption text-muted-foreground">{youAreText({ role: line.role, left })}</p>
                )}
              </td>
              <td className="px-4 py-2.5">{formatDayMonth(line.releasedAt)}</td>
              <td className="px-4 py-2.5 tabular-nums">
                {formatVnd(line.baseAmountVnd)} <TestTag />
              </td>
              <td className="px-4 py-2.5 tabular-nums">
                {line.pending ? <Pending line={line} /> : <Part line={line} />}
              </td>
              {crewColumn && (
                <td className="px-4 py-2.5">
                  {line.role === 'MENTOR' &&
                    line.crew.map((part, partIndex) => (
                      <p key={`${part.name}-${partIndex}`} className="tabular-nums">
                        <span className="break-words">{partyName(part)}</span> {formatVnd(part.amountVnd)} <TestTag />
                        {part.percent != null && (
                          <span className="text-caption text-muted-foreground">
                            {' '}
                            · {t('screen.payment.balance.percent', { n: part.percent })}
                          </span>
                        )}
                      </p>
                    ))}
                </td>
              )}
              <td className="px-4 py-2.5">
                {line.splitVersionNo != null && (
                  <p>{t('screen.payment.balance.version', { n: line.splitVersionNo })}</p>
                )}
                {!line.pending && line.status === 'FINAL' && (
                  <p className="text-caption text-muted-foreground">{finalText(line.finality, line.finalAt)}</p>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function Part({ line }: { line: BalanceLine }) {
  return (
    <>
      {formatVnd(line.yourPartVnd)} <TestTag />
      {line.yourPercent != null && (
        <p className="text-caption text-muted-foreground">
          {t('screen.payment.balance.percent', { n: line.yourPercent })}
        </p>
      )}
    </>
  )
}

/** EXPERIENCE.md's "{amount} THỬ NGHIỆM của Giai đoạn {n} đang chờ phân chia", with the way to JOB-10. */
function Pending({ line }: { line: BalanceLine }) {
  return (
    <div className="flex flex-col items-start gap-1">
      <span className={cn('rounded-full px-2 py-0.5 text-caption font-semibold', pendingTone(line.status))}>
        {pendingText(line.status)}
      </span>
      <p className="text-caption text-muted-foreground">
        {formatVnd(line.baseAmountVnd)} <TestTag /> {t('screen.split.ofMilestonePending', { n: line.number })}
      </p>
      <Link
        to={`/cong-viec/${line.jobId}/phan-chia/${line.milestoneId}`}
        className="text-caption text-primary underline-offset-4 hover:underline"
      >
        {t('screen.payment.balance.openSplit')}
      </Link>
    </div>
  )
}
