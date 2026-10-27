import { CircleCheck, TriangleAlert, UserPlus } from 'lucide-react'
import { useId, useState } from 'react'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { t } from '@/i18n/t'
import { ApiError } from '@/lib/apiClient'
import { problemMessage } from '@/lib/problemMessage'
import { cn } from '@/lib/utils'
import { useChangeCrew, type CrewCandidate, type CrewPanel as Panel, type CrewRow } from './crewApi'
import { Avatar, ShareField, TeamPicker } from './crewParts'
import { LARGE_CREW_LIMIT, percentOf, shareProblem } from './crewShares'

/** A Student in the draft of the Crew after the change. */
interface Member {
  studentId: string
  name: string
  githubLogin?: string | null
  joining: boolean
}

/**
 * "Đổi nhóm thực hiện" on a running Job (Story 11.3, FR-75, UX-DR60): a draft of the Crew after the change.
 * The Mentor keeps or removes each member, adds free Students from the Team with the Story 4.3 rules, and
 * shares the whole again; nothing changes until "Áp dụng khi mọi người đã xác nhận" saves the draft as one
 * version for every party to confirm. A locked member already left the Crew, so they are not in the draft.
 */
export function CrewChangeEditor({ jobId, panel, onClose }: { jobId: string; panel: Panel; onClose: () => void }) {
  const ids = useId()
  const toasts = useToasts()
  const change = useChangeCrew(jobId)
  const running = panel.rows.filter((row) => row.status !== 'RELEASED')
  // The latest version's parties: a waiting change's own, or the Crew in force.
  const [members, setMembers] = useState<Member[]>(() =>
    running
      .filter((row) => row.percent != null)
      .map((row) => ({ studentId: row.studentId, name: row.name, githubLogin: row.githubLogin, joining: row.status === 'ASSIGNED' })),
  )
  const [shares, setShares] = useState<Record<string, string>>(() =>
    Object.fromEntries(running.filter((row) => row.percent != null).map((row) => [row.studentId, String(row.percent)])),
  )
  const [mentorShare, setMentorShare] = useState(panel.mentor.percent == null ? '' : String(panel.mentor.percent))
  const [picking, setPicking] = useState(false)
  const leaving = running.filter((row) => !members.some((member) => member.studentId === row.studentId))
  const atLimit = members.length >= LARGE_CREW_LIMIT

  const mentorProblem = shareProblem(mentorShare, true, panel.capPercent)
  const problems = Object.fromEntries(
    members.map((member) => [member.studentId, shareProblem(shares[member.studentId] ?? '', false, panel.capPercent)]),
  )
  const sum =
    (percentOf(mentorShare) ?? 0) + members.reduce((total, member) => total + (percentOf(shares[member.studentId] ?? '') ?? 0), 0)
  const sumOk = mentorProblem === undefined && members.every((member) => problems[member.studentId] === undefined) && sum === 100
  const canApply = members.length > 0 && sumOk && !change.isPending

  const remove = (studentId: string) => setMembers(members.filter((member) => member.studentId !== studentId))
  const keep = (row: CrewRow) =>
    setMembers([...members, { studentId: row.studentId, name: row.name, githubLogin: row.githubLogin, joining: row.status === 'ASSIGNED' }])
  const pick = (student: CrewCandidate) => {
    setMembers([...members, { studentId: student.studentId, name: student.name, githubLogin: student.githubLogin, joining: true }])
    setPicking(false)
  }
  const apply = () =>
    change.mutate(
      {
        crewVersion: panel.crewVersion,
        shares: [
          { accountId: panel.mentor.accountId, percent: percentOf(mentorShare) ?? 0 },
          ...members.map((member) => ({ accountId: member.studentId, percent: percentOf(shares[member.studentId] ?? '') ?? 0 })),
        ],
      },
      {
        onSuccess: (saved) => {
          toasts.success(t('screen.crew.panel.changeSaved', { n: saved.change?.waitingVersionNo ?? 0 }))
          onClose()
        },
        onError: (error) => {
          if (error instanceof ApiError && error.problem.code === 'STALE') {
            toasts.error(t('error.STALE'))
          } else if (error instanceof ApiError && error.problem.reason === 'SHARES_INVALID') {
            toasts.error(t('reason.job.SHARES_INVALID'))
          } else {
            toasts.error(problemMessage(error, { module: 'job', action: t('screen.crew.panel.changeAction') }).text)
          }
        },
      },
    )

  return (
    <section aria-labelledby={`${ids}-title`} className="flex flex-col gap-4 rounded-md border border-primary bg-background p-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex flex-col gap-0.5">
          <h3 id={`${ids}-title`} className="text-h3">
            {t('screen.crew.panel.changeTitle')}
          </h3>
          <p className="text-caption text-muted-foreground">{t('screen.crew.panel.changeLead')}</p>
        </div>
        <Button
          type="button"
          variant="secondary"
          size="compact"
          aria-expanded={picking}
          aria-controls={`${ids}-pick`}
          disabled={atLimit || change.isPending}
          onClick={() => setPicking(!picking)}
        >
          <UserPlus aria-hidden="true" className="size-4" />
          {t('screen.crew.panel.add')}
        </Button>
      </div>
      {picking && !atLimit && (
        <TeamPicker
          id={`${ids}-pick`}
          team={panel.team.filter((student) => !members.some((member) => member.studentId === student.studentId))}
          disabled={change.isPending}
          onPick={pick}
        />
      )}
      <div className="overflow-x-auto">
        <table className="w-full min-w-[560px] text-left text-body-sm">
          <thead className="text-caption text-muted-foreground">
            <tr className="border-b border-border">
              <th scope="col" className="py-2 pr-3 font-semibold">
                {t('screen.crew.panel.member')}
              </th>
              <th scope="col" className="py-2 pr-3 font-semibold">
                {t('screen.crew.panel.share')}
              </th>
              <th scope="col" className="py-2 pr-3 font-semibold">
                {t('screen.crew.panel.status')}
              </th>
              <th scope="col" className="py-2 font-normal">
                {t('screen.crew.panel.actions')}
              </th>
            </tr>
          </thead>
          <tbody>
            <tr className="border-b border-border align-top">
              <td className="py-3 pr-3">
                <span className="flex items-center gap-2">
                  <Avatar name={panel.mentor.displayName} />
                  <span className="font-semibold">{t('screen.crew.panel.you', { name: panel.mentor.displayName })}</span>
                </span>
              </td>
              <td className="py-3 pr-3">
                <ShareField
                  id={`${ids}-mentor`}
                  label={t('screen.crew.panel.shareLabel', { name: panel.mentor.displayName })}
                  value={mentorShare}
                  locked={false}
                  problem={mentorProblem}
                  cap={panel.capPercent}
                  mentor
                  onChange={setMentorShare}
                />
              </td>
              <td className="py-3 pr-3">
                <span className="rounded-full bg-success-soft px-2 py-0.5 text-caption font-semibold text-success">
                  {t('screen.crew.panel.mentorRole')}
                </span>
              </td>
              <td />
            </tr>
            {members.map((member) => (
              <tr key={member.studentId} className="border-b border-border align-top">
                <td className="py-3 pr-3">
                  <span className="flex items-center gap-2">
                    <Avatar name={member.name} />
                    <span className="flex min-w-0 flex-col">
                      <span className="font-semibold break-words">{member.name}</span>
                      {member.githubLogin && <span className="font-mono text-caption text-muted-foreground">{member.githubLogin}</span>}
                    </span>
                  </span>
                </td>
                <td className="py-3 pr-3">
                  <ShareField
                    id={`${ids}-${member.studentId}`}
                    label={t('screen.crew.panel.shareLabel', { name: member.name })}
                    value={shares[member.studentId] ?? ''}
                    locked={false}
                    problem={problems[member.studentId]}
                    cap={panel.capPercent}
                    onChange={(value) => setShares({ ...shares, [member.studentId]: value })}
                  />
                </td>
                <td className="py-3 pr-3">
                  <span
                    className={cn(
                      'rounded-full px-2 py-0.5 text-caption font-semibold',
                      member.joining ? 'bg-muted text-foreground' : 'bg-success-soft text-success',
                    )}
                  >
                    {t(member.joining ? 'screen.crew.panel.changeNew' : 'screen.crew.panel.changeStays')}
                  </span>
                </td>
                <td className="py-3">
                  <Button
                    type="button"
                    variant="ghost"
                    size="compact"
                    aria-label={t('screen.crew.panel.removeNamed', { name: member.name })}
                    disabled={change.isPending}
                    onClick={() => remove(member.studentId)}
                  >
                    {t('screen.crew.panel.remove')}
                  </Button>
                </td>
              </tr>
            ))}
            {leaving.map((row) => (
              <tr key={row.rowId} className="border-b border-border align-top last:border-b-0">
                <td className="py-3 pr-3">
                  <span className="flex items-center gap-2">
                    <Avatar name={row.name} />
                    <span className="flex min-w-0 flex-col">
                      <span className="font-semibold break-words">{row.name}</span>
                      {row.githubLogin && <span className="font-mono text-caption text-muted-foreground">{row.githubLogin}</span>}
                    </span>
                  </span>
                </td>
                <td className="py-3 pr-3">
                  <span className="text-caption text-muted-foreground">—</span>
                </td>
                <td className="py-3 pr-3">
                  <span className="rounded-full bg-muted px-2 py-0.5 text-caption font-semibold text-muted-foreground">
                    {t('screen.crew.panel.changeLeaving')}
                  </span>
                </td>
                <td className="py-3">
                  <Button
                    type="button"
                    variant="ghost"
                    size="compact"
                    aria-label={t('screen.crew.panel.changeKeepNamed', { name: row.name })}
                    disabled={change.isPending || atLimit}
                    onClick={() => keep(row)}
                  >
                    {t('screen.crew.panel.changeKeep')}
                  </Button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p aria-live="polite" className={cn('flex items-center gap-1.5 text-body-sm font-semibold', !sumOk && 'text-destructive')}>
        {sumOk ? <CircleCheck aria-hidden="true" className="size-4 text-success" /> : <TriangleAlert aria-hidden="true" className="size-4" />}
        {sum === 100 ? t('screen.crew.panel.sum', { n: sum }) : t('screen.crew.panel.sumBad', { n: sum })}
      </p>
      <p className="text-caption text-muted-foreground">{t('screen.crew.panel.changeNote')}</p>
      <div className="flex flex-wrap justify-end gap-3">
        <Button type="button" variant="secondary" size="compact" disabled={change.isPending} onClick={onClose}>
          {t('screen.crew.panel.changeCancel')}
        </Button>
        <Button type="button" size="compact" disabled={!canApply} onClick={apply}>
          {t('screen.crew.panel.changeApply')}
        </Button>
      </div>
    </section>
  )
}
