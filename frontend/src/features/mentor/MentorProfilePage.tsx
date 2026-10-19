import { CircleAlert, CircleCheck, Clock, FileText, Info } from 'lucide-react'
import { useRef, useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { z } from 'zod'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { usePollHealth } from '@/app/usePollHealth'
import { ErrorSummary } from '@/components/ErrorSummary'
import { FormField } from '@/components/FormField'
import { fieldProps, inputClass } from '@/components/formField-props'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { SkillPicker } from '@/components/SkillPicker'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { IMAGE_OR_PDF, precheckUpload, uploadMessage } from '@/features/files/uploadRules'
import {
  useDeleteMentorDocument,
  useProfile,
  useSaveMentorProfile,
  useUploadMentorDocument,
  type MentorProfile,
} from '@/features/identity/profileApi'
import { useSession } from '@/features/identity/useSession'
import { NotFoundScreen } from '@/features/shared/NotFoundScreen'
import { t, type MessageKey } from '@/i18n/t'
import vi from '@/i18n/vi.json'
import { ApiError } from '@/lib/apiClient'
import { formatDate, formatDayMonth } from '@/lib/dates'
import { formatSize } from '@/lib/fileSize'
import { fieldMessage, serverFieldErrors, zodFieldErrors } from '@/lib/formErrors'
import { problemMessage } from '@/lib/problemMessage'
import { cn } from '@/lib/utils'

const MAX_SKILLS = 20

const LINKEDIN = /^https:\/\/www\.linkedin\.com\/in\/[A-Za-z0-9_%-]{3,100}\/?$/

const schema = z.object({
  displayName: z.string().trim().min(1, 'required').max(100, 'tooLong'),
  experience: z.string().trim().min(1, 'required').max(4000, 'tooLong'),
  yearsOfExperience: z.string().trim().regex(/^\d{1,2}$/, 'format').refine((value) => Number(value) <= 60, 'range'),
  linkedinUrl: z.string().trim().min(1, 'required').regex(LINKEDIN, 'format'),
  otherLink: z.string().trim().regex(/^(?:https:\/\/\S+)?$/, 'format').max(300, 'tooLong'),
  skillIds: z.array(z.string()).min(1, 'skills').max(MAX_SKILLS, 'tooLong'),
})

type Values = z.input<typeof schema>

type Field = keyof Values | 'privacy'

const LABELS: Record<Field, string> = {
  displayName: t('screen.mentor.profile.displayName'),
  experience: t('screen.mentor.profile.experience'),
  yearsOfExperience: t('screen.mentor.profile.years'),
  linkedinUrl: t('screen.mentor.profile.linkedin'),
  skillIds: t('screen.mentor.profile.skills'),
  otherLink: t('screen.mentor.profile.otherLink'),
  privacy: t('screen.mentor.profile.privacyAccept'),
}

const PRIVACY_VERSION = (vi as { notice: { privacy: { version: string } } }).notice.privacy.version

/** "17/11" from the server's `YYYY-MM-DD`. */
function dayMonthOf(date: string): string {
  const [, month, day] = date.split('-')
  return `${day}/${month}`
}

/** While a rejection's 30-day wait runs, the application cannot change (FR-74). */
function waiting(mentor: MentorProfile): boolean {
  return mentor.approvalStatus === 'REJECTED' && !!mentor.reapplyAfter && Date.parse(mentor.reapplyAfter) > Date.now()
}

/** Which field a server refusal belongs to, in its own words. */
function refusalOf(error: unknown): { field: Field; message: string } | undefined {
  if (!(error instanceof ApiError)) {
    return undefined
  }
  const { reason, limit } = error.problem as { reason?: string; limit?: number }
  switch (reason) {
    case 'MENTOR_LINKEDIN_IN_USE':
      return { field: 'linkedinUrl', message: t('reason.identity.MENTOR_LINKEDIN_IN_USE') }
    case 'PRIVACY_NOTICE_REQUIRED':
      return { field: 'privacy', message: t('screen.mentor.profile.privacyRequired') }
    case 'SKILL_INACTIVE':
    case 'SKILL_LIMIT':
      return { field: 'skillIds', message: t(`reason.catalog.${reason}` as MessageKey, { n: limit ?? MAX_SKILLS }) }
    default:
      return undefined
  }
}

/**
 * MEN-5 (`/ho-so`), the Mentor application and profile (FR-74, UX-DR78): the only screen of a Mentor
 * not yet approved. Before the first submission it ends with the personal-data notice to accept,
 * N-22, and "Gửi hồ sơ"; afterwards it reads "Đang chờ duyệt" and keeps every field editable with
 * "Lưu thay đổi". Once approved it reads "Đã duyệt" and stays editable; once rejected it shows the
 * reason, and the fields and "Gửi lại hồ sơ" stay disabled until the 30-day wait ends. The document
 * is read by Administrators only; the Mentor sees its name.
 */
export function MentorProfilePage() {
  const session = useSession()
  const account = session.data?.account
  if (account?.role !== 'MENTOR') {
    return <NotFoundScreen />
  }
  return <MentorProfileScreen />
}

function MentorProfileScreen() {
  usePageTitle(t('screen.mentor.profile.title'))
  const profile = useProfile()
  const mentor = profile.data?.mentor

  return (
    <div className="mx-auto flex max-w-[760px] flex-col gap-6 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex flex-col gap-1">
          <PageHeading>{t('screen.mentor.profile.heading')}</PageHeading>
          <p className="text-body-sm text-muted-foreground">{t('screen.mentor.profile.lead')}</p>
        </div>
        {mentor && <StatusBadge mentor={mentor} />}
      </div>
      {mentor && <StatusBlock mentor={mentor} />}
      {mentor?.approvalStatus !== 'APPROVED' && (
        <section className="flex flex-col gap-2 rounded-lg bg-muted p-4" aria-labelledby="criteria-heading">
          <h2 id="criteria-heading" className="text-label font-semibold">
            {t('screen.mentor.profile.criteriaHeading')}
          </h2>
          <ul className="flex list-disc flex-col gap-1 pl-5 text-body-sm">
            <li>{t('screen.mentor.profile.criteria1')}</li>
            <li>{t('screen.mentor.profile.criteria2')}</li>
            <li>{t('screen.mentor.profile.criteria3')}</li>
          </ul>
        </section>
      )}
      <RegionState query={profile} skeleton={<Skeleton className="h-96 w-full" />}>
        {mentor && (
          <ApplicationForm key={`${mentor.approvalStatus}-${mentor.submittedAt ?? 'draft'}`} mentor={mentor} />
        )}
      </RegionState>
    </div>
  )
}

function StatusBadge({ mentor }: { mentor: MentorProfile }) {
  const [label, tone]: [MessageKey, string] =
    mentor.approvalStatus === 'APPROVED'
      ? ['screen.mentor.profile.statusApproved', 'bg-success-soft text-success']
      : mentor.approvalStatus === 'REJECTED'
        ? ['screen.mentor.profile.statusRejected', 'bg-destructive-soft text-destructive']
        : mentor.submittedAt
          ? ['screen.mentor.profile.statusPending', 'bg-primary-soft text-primary']
          : ['screen.mentor.profile.statusDraft', 'bg-muted text-muted-foreground']
  return <span className={cn('rounded-full px-3 py-1 text-label', tone)}>{t(label)}</span>
}

/** Where the application stands: waiting, approved on a day, or rejected with its reason and wait. */
function StatusBlock({ mentor }: { mentor: MentorProfile }) {
  if (mentor.approvalStatus === 'APPROVED') {
    return mentor.decidedAt ? (
      <div role="status" className="flex gap-3 rounded-md border-l-4 border-success bg-success-soft p-4">
        <CircleCheck aria-hidden="true" className="size-5 shrink-0 text-success" />
        <p className="text-body-sm">{t('screen.mentor.profile.approvedBody', { date: formatDate(mentor.decidedAt) })}</p>
      </div>
    ) : null
  }
  if (mentor.approvalStatus === 'REJECTED') {
    return (
      <div role="status" className="flex gap-3 rounded-md border-l-4 border-destructive bg-destructive-soft p-4">
        <CircleAlert aria-hidden="true" className="size-5 shrink-0 text-destructive" />
        <div className="flex min-w-0 flex-col gap-0.5">
          {mentor.rejectionReason && (
            <p className="text-label font-semibold break-words">
              {t('screen.mentor.profile.rejectedTitle', { reason: mentor.rejectionReason })}
            </p>
          )}
          <p className="text-body-sm">
            {waiting(mentor) && mentor.reapplyAfter
              ? t('screen.mentor.profile.reapplyFrom', { date: formatDayMonth(mentor.reapplyAfter) })
              : t('screen.mentor.profile.reapplyOpen')}
          </p>
        </div>
      </div>
    )
  }
  return mentor.submittedAt ? (
    <div role="status" className="flex gap-3 rounded-md border-l-4 border-primary bg-primary-soft p-4">
      <Clock aria-hidden="true" className="size-5 shrink-0 text-primary" />
      <div className="flex flex-col gap-0.5">
        <p className="text-label font-semibold">{t('screen.mentor.profile.pendingTitle')}</p>
        <p className="text-body-sm">{t('screen.mentor.profile.pendingBody', { date: formatDayMonth(mentor.submittedAt) })}</p>
      </div>
    </div>
  ) : null
}

function ApplicationForm({ mentor }: { mentor: MentorProfile }) {
  const submitted = !!mentor.submittedAt
  const rejected = mentor.approvalStatus === 'REJECTED'
  const locked = waiting(mentor)
  const [values, setValues] = useState<Values>({
    displayName: mentor.displayName ?? '',
    experience: mentor.experience ?? '',
    yearsOfExperience: mentor.yearsOfExperience === undefined ? '' : String(mentor.yearsOfExperience),
    linkedinUrl: mentor.linkedinUrl ?? '',
    otherLink: mentor.otherLink ?? '',
    skillIds: mentor.skills.map((skill) => skill.id),
  })
  const [accepted, setAccepted] = useState(false)
  const [errors, setErrors] = useState<Partial<Record<Field, string>>>({})
  const [summary, setSummary] = useState(false)
  const save = useSaveMentorProfile()
  const toasts = useToasts()
  const { offline } = usePollHealth()

  function validate(field: Exclude<keyof Values, 'skillIds'>) {
    const result = schema.shape[field].safeParse(values[field])
    setErrors((current) => {
      const next = { ...current }
      if (result.success) {
        delete next[field]
      } else {
        next[field] = fieldMessage(result.error.issues[0]?.message ?? 'format')
      }
      return next
    })
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    const result = schema.safeParse(values)
    const next: Partial<Record<Field, string>> = result.success ? {} : zodFieldErrors(result.error)
    if (!result.success && next.skillIds) {
      next.skillIds = t('screen.mentor.profile.skillsRequired')
    }
    if (!submitted && !accepted) {
      next.privacy = t('screen.mentor.profile.privacyRequired')
    }
    if (!result.success || Object.keys(next).length > 0) {
      setErrors(next)
      setSummary(true)
      return
    }
    const data = result.data
    save.mutate(
      {
        displayName: data.displayName,
        experience: data.experience,
        yearsOfExperience: Number(data.yearsOfExperience),
        linkedinUrl: data.linkedinUrl,
        otherLink: data.otherLink || undefined,
        skillIds: data.skillIds,
        privacyNoticeVersion: submitted ? undefined : PRIVACY_VERSION,
      },
      {
        onSuccess: (answer) => {
          setErrors({})
          setSummary(false)
          toasts.success(answer.submitted ? t('screen.mentor.profile.submitted') : t('screen.mentor.profile.saved'))
        },
        onError: (error) => {
          if (error instanceof ApiError && error.problem.reason === 'MENTOR_REAPPLY_TOO_SOON') {
            const { date } = error.problem as { date?: string }
            toasts.error(t('reason.identity.MENTOR_REAPPLY_TOO_SOON', { date: date ? dayMonthOf(date) : '' }))
            return
          }
          const refusal = refusalOf(error)
          const fields = refusal ? { [refusal.field]: refusal.message } : serverFieldErrors(error)
          setErrors(fields)
          setSummary(Object.keys(fields).length > 0)
          toasts.error(refusal?.message ?? problemMessage(error, { module: 'identity' }).text)
        },
      },
    )
  }

  const listed = (Object.keys(LABELS) as Field[])
    .filter((field) => errors[field])
    .map((field) => ({ id: field === 'privacy' ? 'privacyAccept' : field, label: LABELS[field], message: errors[field]! }))

  return (
    <form noValidate onSubmit={submit} className="flex flex-col gap-5">
      {summary && listed.length > 0 && <ErrorSummary errors={listed} />}
      <p className="text-caption text-muted-foreground">{t('screen.mentor.profile.required')}</p>
      <fieldset disabled={locked} className="flex min-w-0 flex-col gap-5">
        <FormField id="displayName" label={LABELS.displayName} error={errors.displayName}>
          <input
            {...fieldProps('displayName', errors.displayName)}
            className={inputClass}
            maxLength={100}
            autoComplete="name"
            value={values.displayName}
            onChange={(event) => setValues({ ...values, displayName: event.target.value })}
            onBlur={() => validate('displayName')}
          />
        </FormField>
        <FormField
          id="experience"
          label={LABELS.experience}
          helper={t('screen.mentor.profile.experienceHelper')}
          error={errors.experience}
        >
          <textarea
            {...fieldProps('experience', errors.experience, t('screen.mentor.profile.experienceHelper'))}
            className={cn(inputClass, 'min-h-32')}
            maxLength={4000}
            value={values.experience}
            onChange={(event) => setValues({ ...values, experience: event.target.value })}
            onBlur={() => validate('experience')}
          />
        </FormField>
        <div className="grid gap-5 md:grid-cols-2">
          <FormField id="yearsOfExperience" label={LABELS.yearsOfExperience} error={errors.yearsOfExperience}>
            <input
              {...fieldProps('yearsOfExperience', errors.yearsOfExperience)}
              className={inputClass}
              inputMode="numeric"
              maxLength={2}
              value={values.yearsOfExperience}
              onChange={(event) => setValues({ ...values, yearsOfExperience: event.target.value.replace(/[^0-9]/g, '') })}
              onBlur={() => validate('yearsOfExperience')}
            />
          </FormField>
          <FormField
            id="linkedinUrl"
            label={LABELS.linkedinUrl}
            helper={t('screen.mentor.profile.linkedinHelper')}
            error={errors.linkedinUrl}
          >
            <input
              {...fieldProps('linkedinUrl', errors.linkedinUrl, t('screen.mentor.profile.linkedinHelper'))}
              type="url"
              className={inputClass}
              value={values.linkedinUrl}
              onChange={(event) => setValues({ ...values, linkedinUrl: event.target.value })}
              onBlur={() => validate('linkedinUrl')}
            />
          </FormField>
        </div>
        <FormField id="skillIds" label={LABELS.skillIds} helper={t('screen.mentor.profile.skillsHelper')} error={errors.skillIds}>
          <SkillPicker
            id="skillIds"
            value={values.skillIds}
            onChange={(skillIds) => setValues((current) => ({ ...current, skillIds }))}
            known={mentor.skills}
            max={MAX_SKILLS}
            invalid={!!errors.skillIds}
            describedBy={['skillIds-helper', errors.skillIds ? 'skillIds-error' : null].filter(Boolean).join(' ')}
          />
          <p className="text-caption text-muted-foreground">
            {t('screen.mentor.profile.skillsCount', { n: values.skillIds.length })}
          </p>
        </FormField>
        <FormField
          id="otherLink"
          label={LABELS.otherLink}
          helper={t('screen.mentor.profile.otherLinkHelper')}
          error={errors.otherLink}
        >
          <input
            {...fieldProps('otherLink', errors.otherLink, t('screen.mentor.profile.otherLinkHelper'))}
            type="url"
            className={inputClass}
            maxLength={300}
            value={values.otherLink}
            onChange={(event) => setValues({ ...values, otherLink: event.target.value })}
            onBlur={() => validate('otherLink')}
          />
        </FormField>
        <MentorDocument document={mentor.document} disabled={locked} />
      </fieldset>
      {!submitted && (
        <>
          <section aria-labelledby="privacy-heading" className="flex flex-col gap-2 rounded-lg border border-border bg-card p-4">
            <h2 id="privacy-heading" className="text-label font-semibold">
              {t('notice.privacy.versionLine', { version: PRIVACY_VERSION })}
            </h2>
            <p className="text-body-sm">
              {t('screen.mentor.profile.privacySummary')}{' '}
              <Link to="/quyen-rieng-tu" className="text-primary underline-offset-4 hover:underline">
                {t('screen.mentor.profile.privacyRead')}
              </Link>
            </p>
            <label className="flex cursor-pointer items-start gap-3">
              <input
                id="privacyAccept"
                type="checkbox"
                checked={accepted}
                aria-invalid={errors.privacy ? true : undefined}
                aria-describedby={errors.privacy ? 'privacyAccept-error' : undefined}
                onChange={(event) => setAccepted(event.target.checked)}
                className="mt-0.5 size-5 shrink-0 accent-primary"
              />
              <span className="text-body-sm">{t('screen.mentor.profile.privacyAccept')}</span>
            </label>
            {errors.privacy && (
              <p id="privacyAccept-error" className="text-caption text-destructive">
                {errors.privacy}
              </p>
            )}
          </section>
          <p className="flex gap-3 rounded-md border-l-4 border-primary bg-primary-soft p-4 text-body-sm">
            <Info aria-hidden="true" className="size-5 shrink-0 text-primary" />
            {t('screen.mentor.profile.n22')}
          </p>
        </>
      )}
      <div className="flex flex-wrap items-center gap-3">
        <Button
          type="submit"
          disabled={save.isPending || offline || locked}
          aria-describedby={locked ? 'reapply-line' : undefined}
        >
          {save.isPending
            ? submitted && !rejected
              ? t('screen.mentor.profile.saving')
              : t('screen.mentor.profile.submitting')
            : rejected
              ? t('screen.mentor.profile.resubmit')
              : submitted
                ? t('screen.mentor.profile.save')
                : t('screen.mentor.profile.submit')}
        </Button>
        {locked && mentor.reapplyAfter && (
          <p id="reapply-line" className="text-caption text-muted-foreground">
            {t('screen.mentor.profile.reapplyFrom', { date: formatDayMonth(mentor.reapplyAfter) })}
          </p>
        )}
      </div>
    </form>
  )
}

/** "Tài liệu kèm theo": the attached CV or certificate by name and size, or the button to choose one. */
function MentorDocument({ document, disabled }: { document?: MentorProfile['document']; disabled: boolean }) {
  const upload = useUploadMentorDocument()
  const remove = useDeleteMentorDocument()
  const toasts = useToasts()
  const { offline } = usePollHealth()
  const input = useRef<HTMLInputElement>(null)
  const [error, setError] = useState<string>()

  function choose(file: File | undefined) {
    if (!file) {
      return
    }
    const refusal = precheckUpload(file, IMAGE_OR_PDF)
    if (refusal) {
      setError(refusal)
      return
    }
    setError(undefined)
    upload.mutate(file, {
      onSuccess: () => toasts.success(t('screen.mentor.profile.documentUploaded')),
      onError: (failure) => setError(uploadMessage(failure)),
    })
  }

  return (
    <div className="flex flex-col gap-1.5">
      <p className="text-label">{t('screen.mentor.profile.document')}</p>
      <input
        ref={input}
        type="file"
        hidden
        accept={IMAGE_OR_PDF.types.join(',')}
        onChange={(event) => {
          choose(event.target.files?.[0])
          event.target.value = ''
        }}
      />
      {document ? (
        <div className="flex items-center gap-3 rounded-lg border border-border bg-card px-4 py-2.5">
          <FileText aria-hidden="true" className="size-5 shrink-0 text-muted-foreground" />
          <div className="flex min-w-0 grow flex-col gap-0.5">
            <p className="truncate text-body-sm">
              {t('screen.mentor.profile.documentLine', { name: document.name, size: formatSize(document.size) })}
            </p>
            <p className="text-caption text-muted-foreground">{t('screen.mentor.profile.documentCaption')}</p>
          </div>
          <Button
            type="button"
            variant="ghost"
            size="compact"
            aria-label={t('screen.mentor.profile.documentRemoveLabel', { name: document.name })}
            disabled={remove.isPending || offline || disabled}
            onClick={() =>
              remove.mutate(undefined, { onSuccess: () => toasts.success(t('screen.mentor.profile.documentRemoved')) })
            }
          >
            {t('screen.mentor.profile.documentRemove')}
          </Button>
        </div>
      ) : (
        <div className="flex flex-col items-start gap-1.5 rounded-lg border-2 border-dashed border-border bg-card p-4">
          <Button
            type="button"
            variant="secondary"
            size="compact"
            disabled={upload.isPending || offline || disabled}
            onClick={() => input.current?.click()}
          >
            {upload.isPending ? t('screen.mentor.profile.documentUploading') : t('screen.mentor.profile.documentChoose')}
          </Button>
          <p className="text-caption text-muted-foreground">
            {t('screen.mentor.profile.documentHint')} {t('screen.mentor.profile.documentCaption')}
          </p>
        </div>
      )}
      {error && (
        <p role="alert" className="text-caption text-destructive">
          {error}
        </p>
      )}
    </div>
  )
}
