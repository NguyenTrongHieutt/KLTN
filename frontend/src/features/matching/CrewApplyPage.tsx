import { FileText, Link2, Paperclip, X } from 'lucide-react'
import { useId, useRef, useState, type FormEvent } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { FormField } from '@/components/FormField'
import { fieldProps, inputClass } from '@/components/formField-props'
import { Skeleton } from '@/components/Skeleton'
import { SkillPicker } from '@/components/SkillPicker'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { buttonVariants } from '@/components/ui/button-variants'
import { MAX_UPLOAD_BYTES } from '@/features/files/uploadRules'
import { GitHubGate } from '@/features/identity/GitHubGate'
import { useProfile, type Profile } from '@/features/identity/profileApi'
import { useSession } from '@/features/identity/useSession'
import { ReadSessionAgain } from '@/features/job/FindWorkGate'
import { useJobPost, type JobPost } from '@/features/job/jobPostApi'
import { roleLabel, useJobRole, useMyJobs, type JobRoleView, type MyJob } from '@/features/job/jobRoleApi'
import { t } from '@/i18n/t'
import { formatDayMonth } from '@/lib/dates'
import { formatSize } from '@/lib/fileSize'
import { isNotFound, problemMessage } from '@/lib/problemMessage'
import { cn } from '@/lib/utils'
import { MAX_CAPTION, MAX_PROOF, isProofLink, type BidProofRequest, type BidProofUpload } from './applicationApi'
import { proofUploadRefusal } from './bidText'
import {
  MAX_APPLICATION_NOTE,
  MAX_CREW_SKILLS,
  useAddCrewProofFile,
  useApplyToCrew,
  useCrewProofUploads,
  useMyCrewApplications,
  useRemoveCrewProofFile,
  type CrewApplication,
} from './crewApplicationApi'
import { crewApplicationStateText, crewRefusal, runningCrewJob } from './crewText'
import { SkillChips } from './SkillChips'
import { ProofList } from './ProofList'

/**
 * FND-2 Xin vào nhóm (`/tim-viec/{jobId}/xin-vao-nhom`, FR-81, UX-DR103, Story 21.4), a focused task route: the Mentor's
 * ask first ("Người hướng dẫn cần: {skills}" and their note), then a note to the Mentor, the Skills pre-filled from
 * ACC-1, and at most five files or `https` links under N-27 ({who} is "người hướng dẫn"), the required N-5, and "Gửi
 * đơn". No price and no share (R5). The GitHub gate stands in for the form while GitHub is not linked; the Job's own
 * people see their role, a member who left it, or who is on another running Crew, the plain reason (UX-DR95), and a Job
 * no longer recruiting says so. With a pending application, FND-2 shows it, read-only.
 */
export function CrewApplyPage() {
  const { postId } = useParams()
  usePageTitle(t('screen.crewApply.title'))
  const account = useSession().data?.account
  return (
    <div className="mx-auto flex max-w-[760px] flex-col gap-6 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      {account && !account.capabilities.githubVerified ? (
        <>
          <PageHeading>{t('screen.crewApply.heading')}</PageHeading>
          <GitHubGate
            action={t('screen.githubGate.action.join')}
            returnTo={`/tim-viec/${postId ?? ''}/xin-vao-nhom`}
            keepsInput={false}
            onLeave={() => undefined}
          >
            <ReadSessionAgain />
          </GitHubGate>
        </>
      ) : (
        postId && <CrewApplyRoute postId={postId} />
      )}
    </div>
  )
}

/** What FND-2 shows for this Job and user: the pending application, a gate, or the form. */
function CrewApplyRoute({ postId }: { postId: string }) {
  const mine = useMyCrewApplications()
  const post = useJobPost(postId)
  const role = useJobRole(postId)
  const jobs = useMyJobs()
  if (mine.isPending || post.isPending || role.isPending || jobs.isPending) {
    return <Skeleton className="h-96 w-full" />
  }
  const pending = mine.data?.find((application) => application.jobId === postId && application.status === 'PENDING')
  if (pending) {
    return <SentApplication application={pending} />
  }
  if (role.data) {
    return <RoleGate jobId={postId} role={role.data} />
  }
  if (post.isError || !post.data) {
    return isNotFound(post.error) ? (
      <Gate text={t('reason.matching.RECRUITMENT_CLOSED')} />
    ) : (
      <p role="alert" className="text-body-sm text-destructive">
        {problemMessage(post.error).text}
      </p>
    )
  }
  if (!post.data.recruitment) {
    return <Gate text={t('reason.matching.RECRUITMENT_CLOSED')} />
  }
  const busy = runningCrewJob(jobs.data, postId)
  if (busy) {
    return <Gate text={t('reason.matching.ON_ANOTHER_CREW', { job: busy.title })} />
  }
  return <CrewApplyForm post={post.data} busyJob={busy} />
}

/** A plain reason with "Tìm việc khác" (UX-DR95). */
function Gate({ text }: { text: string }) {
  return (
    <div role="status" className="flex flex-col items-center gap-3 rounded-lg border border-border bg-card px-6 py-14 text-center">
      <PageHeading className="text-h3">{t('screen.crewApply.notRecruiting')}</PageHeading>
      <p className="text-body-sm text-muted-foreground">{text}</p>
      <Link to="/tim-viec" className={buttonVariants()}>
        {t('screen.jobPost.detail.findOther')}
      </Link>
    </div>
  )
}

/**
 * The Job's own people never join its Crew (AD-43): "Đây là công việc bạn đăng.", their role, or for a member who left
 * "Bạn đã rời công việc này nên không xin vào lại được." (R3), with the way to the Job.
 */
function RoleGate({ jobId, role }: { jobId: string; role: JobRoleView }) {
  const text =
    role.crewKind === 'FORMER'
      ? t('reason.matching.CREW_REJOIN_REFUSED')
      : role.role === 'CUSTOMER'
        ? t('reason.matching.ROLE_TAKEN')
        : t('reason.matching.ROLE_IN_JOB_TAKEN', { role: roleLabel(role.role) })
  return (
    <div role="status" className="flex flex-col gap-3 rounded-lg border border-border bg-card p-5">
      <PageHeading className="text-h3">{text}</PageHeading>
      <Link to={`/cong-viec/${jobId}`} className={cn(buttonVariants({ variant: 'secondary' }), 'w-fit')}>
        {t('screen.jobPost.detail.openJob')}
      </Link>
    </div>
  )
}

/** FND-2 with the pending application, read-only ("Xem" on MEN-3). */
function SentApplication({ application }: { application: CrewApplication }) {
  return (
    <section aria-label={t('screen.crewApply.readTitle')} className="flex flex-col gap-4">
      <div className="flex flex-col gap-1">
        <PageHeading>{t('screen.crewApply.readTitle')}</PageHeading>
        <p className="text-body-sm break-words text-muted-foreground">{application.jobTitle}</p>
      </div>
      <p role="status" className="text-label font-semibold">
        {crewApplicationStateText(application)}
      </p>
      <p className="text-body-sm text-muted-foreground">{t('screen.crewApply.readLead')}</p>
      <div className="flex flex-col gap-3 rounded-lg border border-border bg-card p-5 text-body-sm">
        <div className="flex flex-col gap-1">
          <p className="text-label font-semibold">{t('screen.crewApply.note')}</p>
          <p className="break-words whitespace-pre-line">{application.note}</p>
        </div>
        {application.skills.length > 0 && (
          <div className="flex flex-col gap-1.5">
            <p className="text-label font-semibold">{t('screen.crewApply.skills')}</p>
            <SkillChips names={application.skills.map((skill) => skill.name)} />
          </div>
        )}
        <div className="flex flex-col gap-1.5">
          <h2 className="text-label font-semibold">{t('screen.matching.proof.heading')}</h2>
          <ProofList proof={application.proof} />
        </div>
      </div>
      <Link to={`/tim-viec/${application.jobId}`} className={cn(buttonVariants({ variant: 'secondary' }), 'w-fit')}>
        {t('screen.crewApply.back')}
      </Link>
    </section>
  )
}

function CrewApplyForm({ post, busyJob }: { post: JobPost; busyJob: MyJob | undefined }) {
  const profile = useProfile()
  if (profile.isPending) {
    return <Skeleton className="h-96 w-full" />
  }
  return <CrewApplyFields post={post} profile={profile.data} busyJob={busyJob} />
}

/** One link proof item the applicant added on this page; files come from the waiting uploads. */
interface LinkDraft {
  url: string
  caption: string
}

/** A proof item in the applicant's order: a waiting upload's file, or a link. */
type ProofItem =
  | { key: string; kind: 'FILE'; upload: BidProofUpload }
  | { key: string; kind: 'LINK'; url: string }

function CrewApplyFields({
  post,
  profile,
  busyJob,
}: {
  post: JobPost
  profile: Profile | undefined
  busyJob: MyJob | undefined
}) {
  const ids = useId()
  const navigate = useNavigate()
  const toasts = useToasts()
  const role = useJobRole(post.id)
  const apply = useApplyToCrew(post.id)
  const uploads = useCrewProofUploads(post.id)
  const add = useAddCrewProofFile(post.id)
  const remove = useRemoveCrewProofFile(post.id)
  const fileInput = useRef<HTMLInputElement>(null)
  const recruitment = post.recruitment
  const [note, setNote] = useState('')
  const [skillIds, setSkillIds] = useState<string[]>(() =>
    (profile?.skills ?? []).filter((skill) => skill.active).map((skill) => skill.id).slice(0, MAX_CREW_SKILLS),
  )
  const [acknowledged, setAcknowledged] = useState(false)
  const [order, setOrder] = useState<string[]>([])
  const [links, setLinks] = useState<Record<string, LinkDraft>>({})
  const [captions, setCaptions] = useState<Record<string, string>>({})
  const [linkUrl, setLinkUrl] = useState('')
  const [linkCaption, setLinkCaption] = useState('')
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [proofError, setProofError] = useState<string>()
  const [refusal, setRefusal] = useState<string>()

  // Waiting uploads not placed yet (after a reload) come first, in their upload order; then the items as added.
  const fileKeys = (uploads.data ?? []).map((upload) => `file:${upload.fileId}`)
  const keys = [
    ...fileKeys.filter((key) => !order.includes(key)),
    ...order.filter((key) => (key.startsWith('file:') ? fileKeys.includes(key) : key in links)),
  ]
  const items: ProofItem[] = keys.flatMap((key): ProofItem[] => {
    if (!key.startsWith('file:')) {
      return [{ key, kind: 'LINK', url: links[key].url }]
    }
    const upload = (uploads.data ?? []).find((waiting) => `file:${waiting.fileId}` === key)
    return upload ? [{ key, kind: 'FILE', upload }] : []
  })
  const caption = (item: ProofItem) => (item.kind === 'LINK' ? links[item.key].caption : (captions[item.key] ?? ''))
  const roomLeft = () => {
    if (items.length >= MAX_PROOF) {
      setProofError(t('screen.crewApply.refusal.PROOF_LIMIT'))
      return false
    }
    setProofError(undefined)
    return true
  }
  const refusalText = (error: unknown) => crewRefusal(error, { role: role.data?.role, busyJob: busyJob?.title })

  const addFile = (file: File) => {
    if (!roomLeft()) {
      return
    }
    if (file.size > MAX_UPLOAD_BYTES) {
      setProofError(t('reason.files.FILE_TOO_LARGE'))
      return
    }
    add.mutate(file, {
      onSuccess: (upload) => setOrder((current) => [...current, `file:${upload.fileId}`]),
      onError: (error) => setProofError(proofUploadRefusal(error)),
    })
  }

  const addLink = () => {
    if (!roomLeft()) {
      return
    }
    if (!isProofLink(linkUrl)) {
      setProofError(t('reason.matching.LINK_INVALID'))
      return
    }
    if (linkCaption.trim() === '') {
      setProofError(t('screen.matching.apply.captionRequired'))
      return
    }
    const key = `link:${Date.now()}:${keys.length}`
    setLinks((current) => ({ ...current, [key]: { url: linkUrl.trim(), caption: linkCaption.slice(0, MAX_CAPTION) } }))
    setOrder((current) => [...current, key])
    setLinkUrl('')
    setLinkCaption('')
  }

  const removeItem = (item: ProofItem) => {
    setProofError(undefined)
    if (item.kind === 'LINK') {
      setOrder((current) => current.filter((key) => key !== item.key))
      setLinks((current) => {
        const next = { ...current }
        delete next[item.key]
        return next
      })
      return
    }
    remove.mutate(item.upload.fileId, {
      onSuccess: () => setOrder((current) => current.filter((key) => key !== item.key)),
      onError: (error) => setProofError(proofUploadRefusal(error)),
    })
  }

  const submit = (event: FormEvent) => {
    event.preventDefault()
    setRefusal(undefined)
    const found: Record<string, string> = {}
    if (note.trim() === '') {
      found.note = t('screen.crewApply.noteRequired')
    }
    if (items.some((item) => caption(item).trim() === '')) {
      found.proof = t('screen.matching.apply.captionRequired')
    }
    if (items.length > MAX_PROOF) {
      found.proof = t('screen.crewApply.refusal.PROOF_LIMIT')
    }
    if (!acknowledged) {
      found.acknowledged = t('screen.crewApply.acknowledgeRequired')
    }
    setErrors(found)
    if (Object.keys(found).length > 0) {
      return
    }
    const proof: BidProofRequest[] = items.map((item) =>
      item.kind === 'FILE'
        ? { kind: 'FILE', fileId: item.upload.fileId, caption: caption(item).trim() }
        : { kind: 'LINK', url: item.url, caption: caption(item).trim() },
    )
    apply.mutate(
      { note: note.trim(), skillIds, publicCodeAcknowledged: true, proof },
      {
        onSuccess: (sent) => {
          toasts.success(t('screen.crewApply.sent', { date: formatDayMonth(sent.lapsesAt) }))
          void navigate(`/tim-viec/${post.id}`)
        },
        onError: (error) => setRefusal(refusalText(error)),
      },
    )
  }

  return (
    <form onSubmit={submit} noValidate aria-label={t('screen.crewApply.heading')} className="flex flex-col gap-5">
      <div className="flex flex-col gap-1">
        <PageHeading>{t('screen.crewApply.heading')}</PageHeading>
        <p className="text-body-sm break-words text-muted-foreground">{post.title}</p>
        <p className="text-body-sm text-muted-foreground">{t('screen.crewApply.lead')}</p>
      </div>
      {recruitment && (
        <section aria-label={t('screen.jobPost.detail.crewNote')} className="flex flex-col gap-2 rounded-lg border border-border bg-card p-5 text-body-sm">
          <p className="font-semibold break-words">
            {recruitment.skills.length > 0
              ? t('screen.crewApply.ask', { skills: recruitment.skills.join(', ') })
              : t('screen.crewApply.askAny')}
          </p>
          {recruitment.note && <p className="break-words whitespace-pre-line">{recruitment.note}</p>}
        </section>
      )}
      <FormField id={`${ids}-note`} label={t('screen.crewApply.note')} helper={t('screen.crewApply.noteHint')} error={errors.note}>
        <textarea
          {...fieldProps(`${ids}-note`, errors.note, t('screen.crewApply.noteHint'))}
          rows={4}
          maxLength={MAX_APPLICATION_NOTE}
          value={note}
          onChange={(event) => setNote(event.target.value)}
          className={cn(inputClass, 'min-h-24')}
        />
      </FormField>
      <div className="flex flex-col gap-1.5">
        <label htmlFor={`${ids}-skills`} className="text-label">
          {t('screen.crewApply.skills')}
        </label>
        <SkillPicker
          id={`${ids}-skills`}
          value={skillIds}
          onChange={setSkillIds}
          known={profile?.skills}
          max={MAX_CREW_SKILLS}
          describedBy={`${ids}-skills-hint`}
        />
        <p id={`${ids}-skills-hint`} className="text-caption text-muted-foreground">
          {t('screen.crewApply.skillsHint')}
        </p>
      </div>
      <section aria-labelledby={`${ids}-proof`} className="flex flex-col gap-3 rounded-lg border border-border bg-card p-5">
        <div role="note" className="rounded-md border-l-4 border-primary bg-primary-soft p-4 text-body-sm">
          {t('notice.proofConfidentiality', { who: t('screen.crewApply.proofWho') })}
        </div>
        <h2 id={`${ids}-proof`} className="text-h3">
          {t('screen.matching.apply.proofHeading')}
        </h2>
        <p className="text-caption text-muted-foreground">{t('screen.matching.apply.proofHint')}</p>
        {items.length > 0 && (
          <ul className="flex flex-col gap-3">
            {items.map((item) => {
              const name = item.kind === 'FILE' ? item.upload.fileName : item.url
              return (
                <li key={item.key} className="flex flex-col gap-2 rounded-md border border-border p-3">
                  <div className="flex items-start gap-2 text-body-sm">
                    {item.kind === 'FILE' ? (
                      <FileText aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
                    ) : (
                      <Link2 aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
                    )}
                    <span className="min-w-0 flex-1 break-all">
                      {item.kind === 'FILE'
                        ? t('screen.matching.proof.file', { name, size: formatSize(item.upload.sizeBytes) })
                        : `${name} (${new URL(item.url).hostname.toLowerCase()})`}
                    </span>
                    <Button
                      type="button"
                      variant="ghost"
                      size="compact"
                      aria-label={t('screen.matching.apply.removeItem', { name })}
                      disabled={remove.isPending}
                      onClick={() => removeItem(item)}
                    >
                      <X aria-hidden="true" className="size-4" />
                    </Button>
                  </div>
                  {item.kind === 'FILE' ? (
                    <input
                      aria-label={t('screen.matching.apply.caption', { name })}
                      maxLength={MAX_CAPTION}
                      value={captions[item.key] ?? ''}
                      onChange={(event) => setCaptions((current) => ({ ...current, [item.key]: event.target.value }))}
                      className={inputClass}
                    />
                  ) : (
                    <p className="text-body-sm break-words text-muted-foreground">{links[item.key].caption}</p>
                  )}
                </li>
              )
            })}
          </ul>
        )}
        <p className="text-caption text-muted-foreground">{t('screen.matching.apply.proofCount', { n: items.length })}</p>
        <div className="grid gap-3 sm:grid-cols-2">
          <FormField id={`${ids}-link`} label={t('screen.matching.apply.linkUrl')}>
            <input
              {...fieldProps(`${ids}-link`)}
              type="url"
              inputMode="url"
              maxLength={2000}
              placeholder="https://"
              value={linkUrl}
              onChange={(event) => setLinkUrl(event.target.value)}
              className={inputClass}
            />
          </FormField>
          <FormField id={`${ids}-link-caption`} label={t('screen.matching.apply.linkCaption')}>
            <input
              {...fieldProps(`${ids}-link-caption`)}
              maxLength={MAX_CAPTION}
              value={linkCaption}
              onChange={(event) => setLinkCaption(event.target.value)}
              className={inputClass}
            />
          </FormField>
        </div>
        <div className="flex flex-wrap gap-3">
          <Button type="button" variant="secondary" onClick={addLink}>
            <Link2 aria-hidden="true" className="size-4" />
            {t('screen.matching.apply.addLink')}
          </Button>
          <input
            ref={fileInput}
            type="file"
            accept="image/png,image/jpeg,image/gif,image/bmp,application/pdf"
            className="sr-only"
            tabIndex={-1}
            aria-hidden="true"
            onChange={(event) => {
              const file = event.target.files?.[0]
              event.target.value = ''
              if (file) {
                addFile(file)
              }
            }}
          />
          <Button type="button" variant="secondary" disabled={add.isPending} onClick={() => fileInput.current?.click()}>
            <Paperclip aria-hidden="true" className="size-4" />
            {t(add.isPending ? 'screen.matching.apply.uploading' : 'screen.matching.apply.addFile')}
          </Button>
        </div>
        {(proofError ?? errors.proof) && (
          <p role="alert" className="text-body-sm text-destructive">
            {proofError ?? errors.proof}
          </p>
        )}
      </section>
      <div className="flex flex-col gap-1">
        <label className="flex items-start gap-2 text-body-sm">
          <input
            type="checkbox"
            className="mt-1"
            checked={acknowledged}
            onChange={(event) => setAcknowledged(event.target.checked)}
            aria-invalid={errors.acknowledged ? true : undefined}
          />
          <span>{t('screen.matching.apply.n5')}</span>
        </label>
        {errors.acknowledged && (
          <p role="alert" className="text-body-sm text-destructive">
            {errors.acknowledged}
          </p>
        )}
      </div>
      {refusal && (
        <div role="alert" className="rounded-md border-l-4 border-destructive bg-card p-4 text-body-sm">
          <p>{refusal}</p>
        </div>
      )}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <Link to={`/tim-viec/${post.id}`} className={buttonVariants({ variant: 'secondary' })}>
          {t('screen.crewApply.back')}
        </Link>
        <Button type="submit" disabled={apply.isPending}>
          {t(apply.isPending ? 'screen.crewApply.sending' : 'screen.crewApply.submit')}
        </Button>
      </div>
    </form>
  )
}
