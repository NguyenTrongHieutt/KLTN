import { BadgeCheck, Plus, Trash2 } from 'lucide-react'
import { useState, type FormEvent, type ReactNode } from 'react'
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
import { t, type MessageKey } from '@/i18n/t'
import { ApiError } from '@/lib/apiClient'
import { formatCalendarDate, formatDayMonthTime, formatMonthYear } from '@/lib/dates'
import { fieldMessage, serverFieldErrors, zodFieldErrors } from '@/lib/formErrors'
import { problemMessage } from '@/lib/problemMessage'
import { cn } from '@/lib/utils'
import { MENTOR_PROFILE_PATH } from './api'
import { ProfileTabs } from './ProfileTabs'
import { useProfile, useSaveProfile, type Profile, type ProfileRequest } from './profileApi'
import { useSession } from './useSession'

/** At most 20 Skills, 5 links, and 5 repositories (FR-4, AD-38). */
export const MAX_SKILLS = 20

export const MAX_LINKS = 5

export const MAX_REPOSITORIES = 5

const LINK = /^https:\/\/\S+$/

const REPOSITORY = /^(?:https:\/\/github\.com\/)?[A-Za-z0-9][A-Za-z0-9-]{0,38}\/[A-Za-z0-9._-]{1,100}?(?:\.git)?\/?$/

const schema = z.object({
  kind: z.enum(['INDIVIDUAL', 'BUSINESS']),
  displayName: z.string().trim().min(1, 'required').max(100, 'tooLong'),
  contactEmail: z
    .string()
    .trim()
    .max(254, 'tooLong')
    .refine((value) => value === '' || z.email().safeParse(value).success, 'email'),
  phone: z
    .string()
    .trim()
    .regex(/^(?:\+?(?:[0-9] ?){7,14}[0-9])?$/, 'format'),
  experience: z.string().trim().max(2000, 'tooLong'),
  yearsOfExperience: z
    .string()
    .trim()
    .regex(/^(?:\d{1,2})?$/, 'format')
    .refine((value) => value === '' || Number(value) <= 60, 'range'),
  skillIds: z.array(z.string()).max(MAX_SKILLS, 'tooLong'),
  links: z.array(z.string().trim().max(500, 'tooLong').regex(LINK, 'format')).max(MAX_LINKS, 'tooLong'),
  repositories: z.array(z.string().trim().regex(REPOSITORY, 'format')).max(MAX_REPOSITORIES, 'tooLong'),
  hideStudentDetails: z.boolean(),
})

type Values = z.input<typeof schema>

type Field =
  | 'displayName'
  | 'contactEmail'
  | 'phone'
  | 'experience'
  | 'yearsOfExperience'
  | 'skillIds'
  | 'links'
  | 'repositories'

const LABELS: Record<Field, string> = {
  displayName: t('screen.profile.typed.displayName'),
  contactEmail: t('screen.profile.typed.contactEmail'),
  phone: t('screen.profile.typed.phone'),
  experience: t('screen.profile.typed.experience'),
  yearsOfExperience: t('screen.profile.typed.years'),
  skillIds: t('screen.profile.typed.skills'),
  links: t('screen.profile.typed.links'),
  repositories: t('screen.profile.typed.repositories'),
}

/** A `catalog` refusal of the chosen Skills, in its own words. */
function skillRefusal(error: unknown): string | undefined {
  if (!(error instanceof ApiError)) {
    return undefined
  }
  const { reason, limit } = error.problem as { reason?: string; limit?: number }
  return reason === 'SKILL_INACTIVE' || reason === 'SKILL_LIMIT'
    ? t(`reason.catalog.${reason}` as MessageKey, { n: limit ?? MAX_SKILLS })
    : undefined
}

/**
 * ACC-1 (`/ho-so`, Story 19.3, FR-3, FR-4, FR-51, UX-DR90): the one profile of every account, whatever
 * role it holds in a Job. "Bạn tự nhập" is the form, saved with no approval: the display name,
 * individual or business, the contact email and phone, the experience, the years, Skills from the
 * Skill picker (UX-DR75), links marked "Tự khai", and showcased repositories, shown as links only.
 * "Đã xác minh" below is read-only: GitHub, the student status with the details declared at
 * verification, and LinkedIn, each "Chưa xác minh" with a link to ACC-2 until it is verified.
 */
export function ProfilePage() {
  usePageTitle(t('screen.profile.title'))
  const profile = useProfile()
  const account = useSession().data?.account
  return (
    <div className="mx-auto flex max-w-[760px] flex-col gap-6 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      <div className="flex flex-col gap-3">
        <PageHeading>{t('screen.profile.heading')}</PageHeading>
        <ProfileTabs />
      </div>
      <RegionState
        query={profile}
        skeleton={
          <div className="flex flex-col gap-4">
            {[0, 1, 2, 3].map((row) => (
              <Skeleton key={row} className="h-16 w-full" />
            ))}
          </div>
        }
      >
        {profile.data && (
          <>
            <section
              aria-labelledby="typed-heading"
              className="flex flex-col gap-4 rounded-lg border border-border bg-card p-4 md:p-6"
            >
              <div className="flex flex-col gap-1">
                <h2 id="typed-heading" className="text-h2">
                  {t('screen.profile.typed.heading')}
                </h2>
                <p className="text-body-sm text-muted-foreground">{t('screen.profile.typed.lead')}</p>
              </div>
              <TypedForm profile={profile.data} />
            </section>
            <VerifiedZone profile={profile.data} />
          </>
        )}
      </RegionState>
      {account?.role === 'MENTOR' && (
        <section aria-labelledby="mentor-heading" className="flex flex-col gap-2 rounded-lg bg-muted p-4">
          <h2 id="mentor-heading" className="text-label font-semibold">
            {t('screen.profile.mentor.heading')}
          </h2>
          <p className="text-body-sm">{t('screen.profile.mentor.body')}</p>
          <Link to={MENTOR_PROFILE_PATH} className="self-start text-body-sm text-primary underline-offset-4 hover:underline">
            {t('screen.profile.mentor.link')}
          </Link>
        </section>
      )}
    </div>
  )
}

function TypedForm({ profile }: { profile: Profile }) {
  const [values, setValues] = useState<Values>({
    kind: profile.kind,
    displayName: profile.displayName ?? '',
    contactEmail: profile.contactEmail ?? '',
    phone: profile.phone ?? '',
    experience: profile.experience ?? '',
    yearsOfExperience: profile.yearsOfExperience === undefined ? '' : String(profile.yearsOfExperience),
    skillIds: profile.skills.map((skill) => skill.id),
    links: profile.links,
    repositories: profile.repositories,
    hideStudentDetails: profile.hideStudentDetails,
  })
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [summary, setSummary] = useState(false)
  const [savedAt, setSavedAt] = useState<string | null>(null)
  const save = useSaveProfile()
  const toasts = useToasts()
  const { offline } = usePollHealth()

  function set(field: keyof Values, value: Values[keyof Values]) {
    setValues((current) => ({ ...current, [field]: value }))
  }

  function validate(field: 'displayName' | 'contactEmail' | 'phone' | 'experience' | 'yearsOfExperience') {
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

  function setItem(list: 'links' | 'repositories', index: number, value: string) {
    setValues((current) => ({ ...current, [list]: current[list].map((item, at) => (at === index ? value : item)) }))
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    // An empty row added and left blank is not a link or a repository.
    const result = schema.safeParse({
      ...values,
      links: values.links.filter((link) => link.trim() !== ''),
      repositories: values.repositories.filter((repository) => repository.trim() !== ''),
    })
    if (!result.success) {
      setErrors(zodFieldErrors(result.error))
      setSummary(true)
      return
    }
    const data = result.data
    const body: ProfileRequest = {
      displayName: data.displayName,
      kind: data.kind,
      contactEmail: data.contactEmail || undefined,
      phone: data.phone || undefined,
      experience: data.experience || undefined,
      yearsOfExperience: data.yearsOfExperience === '' ? undefined : Number(data.yearsOfExperience),
      skillIds: data.skillIds,
      links: data.links,
      repositories: data.repositories,
      hideStudentDetails: data.hideStudentDetails,
    }
    save.mutate(body, {
      onSuccess: () => {
        setErrors({})
        setSummary(false)
        setSavedAt(new Date().toISOString())
        toasts.success(t('screen.profile.typed.saved'))
      },
      onError: (error) => {
        const skills = skillRefusal(error)
        const fields = skills ? { skillIds: skills } : serverFieldErrors(error)
        setErrors(fields)
        setSummary(Object.keys(fields).length > 0)
        toasts.error(skills ?? problemMessage(error, { module: 'identity' }).text)
      },
    })
  }

  const listed = (Object.keys(LABELS) as Field[])
    .filter((field) => errors[field])
    .map((field) => ({ id: field, label: LABELS[field], message: errors[field] }))

  return (
    <form noValidate onSubmit={submit} className="flex flex-col gap-5">
      {summary && listed.length > 0 && <ErrorSummary errors={listed} />}
      <fieldset className="flex flex-col gap-2">
        <legend className="mb-1 text-label">{t('screen.profile.typed.kind')}</legend>
        <div className="flex flex-wrap gap-3">
          {(['INDIVIDUAL', 'BUSINESS'] as const).map((kind) => (
            <label
              key={kind}
              className={cn(
                'flex min-h-tap-min cursor-pointer items-center gap-2 rounded-md border px-3',
                values.kind === kind ? 'border-primary bg-primary-soft' : 'border-border',
              )}
            >
              <input
                type="radio"
                name="kind"
                value={kind}
                checked={values.kind === kind}
                onChange={() => set('kind', kind)}
                className="size-5 accent-primary"
              />
              {t(kind === 'INDIVIDUAL' ? 'screen.profile.typed.individual' : 'screen.profile.typed.business')}
            </label>
          ))}
        </div>
      </fieldset>
      <FormField id="displayName" label={LABELS.displayName} error={errors.displayName}>
        <input
          {...fieldProps('displayName', errors.displayName)}
          className={inputClass}
          value={values.displayName}
          maxLength={100}
          autoComplete="name"
          onChange={(event) => set('displayName', event.target.value)}
          onBlur={() => validate('displayName')}
        />
      </FormField>
      <FormField
        id="contactEmail"
        label={LABELS.contactEmail}
        helper={t('screen.profile.typed.contactHelper')}
        error={errors.contactEmail}
      >
        <input
          {...fieldProps('contactEmail', errors.contactEmail, t('screen.profile.typed.contactHelper'))}
          type="email"
          autoComplete="email"
          className={inputClass}
          value={values.contactEmail}
          onChange={(event) => set('contactEmail', event.target.value)}
          onBlur={() => validate('contactEmail')}
        />
      </FormField>
      <FormField id="phone" label={LABELS.phone} helper={t('screen.profile.typed.phoneHelper')} error={errors.phone}>
        <input
          {...fieldProps('phone', errors.phone, t('screen.profile.typed.phoneHelper'))}
          type="tel"
          autoComplete="tel"
          className={inputClass}
          value={values.phone}
          onChange={(event) => set('phone', event.target.value)}
          onBlur={() => validate('phone')}
        />
      </FormField>
      <FormField
        id="experience"
        label={LABELS.experience}
        helper={t('screen.profile.typed.experienceHelper')}
        error={errors.experience}
      >
        <textarea
          {...fieldProps('experience', errors.experience, t('screen.profile.typed.experienceHelper'))}
          className={cn(inputClass, 'min-h-32')}
          maxLength={2000}
          value={values.experience}
          onChange={(event) => set('experience', event.target.value)}
          onBlur={() => validate('experience')}
        />
      </FormField>
      <FormField id="yearsOfExperience" label={LABELS.yearsOfExperience} error={errors.yearsOfExperience}>
        <input
          {...fieldProps('yearsOfExperience', errors.yearsOfExperience)}
          className={cn(inputClass, 'md:max-w-40')}
          inputMode="numeric"
          maxLength={2}
          value={values.yearsOfExperience}
          onChange={(event) => set('yearsOfExperience', event.target.value.replace(/[^0-9]/g, ''))}
          onBlur={() => validate('yearsOfExperience')}
        />
      </FormField>
      <FormField id="skillIds" label={LABELS.skillIds} helper={t('screen.profile.typed.skillsHelper')} error={errors.skillIds}>
        <SkillPicker
          id="skillIds"
          value={values.skillIds}
          onChange={(skillIds) => set('skillIds', skillIds)}
          known={profile.skills}
          max={MAX_SKILLS}
          invalid={!!errors.skillIds}
          describedBy={['skillIds-helper', errors.skillIds ? 'skillIds-error' : null].filter(Boolean).join(' ')}
        />
      </FormField>
      <fieldset id="links" tabIndex={-1} className="flex flex-col gap-2 focus:outline-none">
        <legend className="mb-1 text-label">{LABELS.links}</legend>
        <p className="text-caption text-muted-foreground">{t('screen.profile.typed.linksHelper')}</p>
        {values.links.map((link, index) => (
          <div key={index} className="flex items-center gap-2">
            <input
              aria-label={t('screen.profile.typed.linkN', { n: index + 1 })}
              type="url"
              className={inputClass}
              value={link}
              maxLength={500}
              placeholder="https://"
              onChange={(event) => setItem('links', index, event.target.value)}
            />
            <SelfDeclared />
            <Button
              type="button"
              variant="ghost"
              size="compact"
              aria-label={t('screen.profile.typed.removeLink', { n: index + 1 })}
              onClick={() => set('links', values.links.filter((_, at) => at !== index))}
            >
              <Trash2 aria-hidden="true" />
            </Button>
          </div>
        ))}
        {errors.links && <p className="text-caption text-destructive">{errors.links}</p>}
        {values.links.length < MAX_LINKS && (
          <Button
            type="button"
            variant="secondary"
            size="compact"
            className="self-start"
            onClick={() => set('links', [...values.links, ''])}
          >
            <Plus aria-hidden="true" />
            {t('screen.profile.typed.addLink')}
          </Button>
        )}
      </fieldset>
      <fieldset id="repositories" tabIndex={-1} className="flex flex-col gap-2 focus:outline-none">
        <legend className="mb-1 text-label">{LABELS.repositories}</legend>
        <p className="text-caption text-muted-foreground">{t('screen.profile.typed.repositoriesHelper')}</p>
        {values.repositories.map((repository, index) => (
          <div key={index} className="flex items-center gap-2">
            <input
              aria-label={t('screen.profile.typed.repositoryN', { n: index + 1 })}
              className={inputClass}
              value={repository}
              placeholder="chu-so-huu/ten-kho"
              onChange={(event) => setItem('repositories', index, event.target.value)}
            />
            <Button
              type="button"
              variant="ghost"
              size="compact"
              aria-label={t('screen.profile.typed.removeRepository', { n: index + 1 })}
              onClick={() => set('repositories', values.repositories.filter((_, at) => at !== index))}
            >
              <Trash2 aria-hidden="true" />
            </Button>
          </div>
        ))}
        {errors.repositories && <p className="text-caption text-destructive">{errors.repositories}</p>}
        {values.repositories.length < MAX_REPOSITORIES && (
          <Button
            type="button"
            variant="secondary"
            size="compact"
            className="self-start"
            onClick={() => set('repositories', [...values.repositories, ''])}
          >
            <Plus aria-hidden="true" />
            {t('screen.profile.typed.addRepository')}
          </Button>
        )}
        {profile.repositories.length > 0 && (
          <ul className="flex flex-col gap-1 text-body-sm">
            {profile.repositories.map((repository) => (
              <li key={repository}>
                <a
                  href={`https://github.com/${repository.split('/').map(encodeURIComponent).join('/')}`}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="text-primary underline-offset-4 hover:underline"
                >
                  github.com/{repository}
                </a>
              </li>
            ))}
          </ul>
        )}
      </fieldset>
      {profile.studentDetails && (
        <label className="flex cursor-pointer items-start gap-3">
          <input
            type="checkbox"
            role="switch"
            checked={values.hideStudentDetails}
            aria-describedby="hideStudentDetails-helper"
            onChange={(event) => set('hideStudentDetails', event.target.checked)}
            className="mt-0.5 size-5 shrink-0 accent-primary"
          />
          <span className="flex flex-col gap-0.5">
            <span className="text-body-sm">{t('screen.profile.typed.hideStudentDetails')}</span>
            <span id="hideStudentDetails-helper" className="text-caption text-muted-foreground">
              {t('screen.profile.typed.hideHelper')}
            </span>
          </span>
        </label>
      )}
      <div className="flex flex-wrap items-center gap-3">
        <Button type="submit" disabled={save.isPending || offline}>
          {save.isPending ? t('screen.profile.typed.saving') : t('screen.profile.typed.save')}
        </Button>
        {savedAt && (
          <p role="status" className="text-body-sm text-muted-foreground">
            {t('screen.profile.typed.savedAt', { time: formatDayMonthTime(savedAt) })}
          </p>
        )}
      </div>
    </form>
  )
}

/** "Tự khai": what the person typed, which nothing checked. */
function SelfDeclared() {
  return (
    <span className="shrink-0 rounded-full bg-muted px-2 py-0.5 text-caption font-semibold text-muted-foreground">
      {t('screen.profile.typed.selfDeclared')}
    </span>
  )
}

/**
 * "Đã xác minh": one line per fact with its source, and "Chưa xác minh" linking to ACC-2 for each
 * verification not done or lapsed. Text only: no fact here is a control (AD-42).
 */
function VerifiedZone({ profile }: { profile: Profile }) {
  const { github, student } = profile.verified
  const details = profile.studentDetails
  return (
    <section
      aria-labelledby="verified-heading"
      className="flex flex-col gap-4 rounded-lg border border-border bg-card p-4 md:p-6"
    >
      <div className="flex flex-col gap-1">
        <h2 id="verified-heading" className="text-h2">
          {t('screen.profile.verified.heading')}
        </h2>
        <p className="text-body-sm text-muted-foreground">{t('screen.profile.verified.lead')}</p>
      </div>
      <dl className="flex flex-col divide-y divide-border">
        <FactRow label={t('screen.profile.verified.github')}>
          {github ? (
            <VerifiedLine>
              {t('screen.profile.verified.githubLine', { login: github.login, month: formatMonthYear(github.since) })}
            </VerifiedLine>
          ) : (
            <Unverified />
          )}
        </FactRow>
        <FactRow label={t('screen.profile.verified.student')}>
          <div className="flex flex-col gap-1.5">
            {student ? (
              <>
                <VerifiedLine>
                  {t('screen.profile.verified.studentLine', { date: formatCalendarDate(student.validUntil) })}
                </VerifiedLine>
                <p className="text-caption text-muted-foreground">
                  {t(
                    student.method === 'STUDENT_CARD'
                      ? 'screen.profile.verified.methodStudentCard'
                      : 'screen.profile.verified.methodSchoolEmail',
                  )}
                </p>
              </>
            ) : (
              <Unverified />
            )}
            {details && (
              <ul className="flex flex-col gap-1 text-body-sm">
                {details.name && <Declared label={t('screen.profile.verified.studentName')} value={details.name} />}
                {details.school && <Declared label={t('screen.profile.verified.studentSchool')} value={details.school} />}
                {details.year && (
                  <Declared
                    label={t('screen.profile.verified.studentYear')}
                    value={t('screen.profile.verified.yearN', { n: details.year })}
                  />
                )}
              </ul>
            )}
          </div>
        </FactRow>
        <FactRow label={t('screen.profile.verified.linkedin')}>
          <Unverified />
        </FactRow>
      </dl>
      <Link to="/ho-so/xac-minh" className="self-start text-body-sm text-primary underline-offset-4 hover:underline">
        {t('screen.profile.verified.manage')}
      </Link>
    </section>
  )
}

function FactRow({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-1 py-3 first:pt-0 last:pb-0 md:flex-row md:gap-4">
      <dt className="text-label md:w-40 md:shrink-0">{label}</dt>
      <dd className="min-w-0">{children}</dd>
    </div>
  )
}

function VerifiedLine({ children }: { children: ReactNode }) {
  return (
    <p className="flex items-start gap-2 text-body-sm font-semibold break-words">
      <BadgeCheck aria-hidden="true" className="size-4 shrink-0 translate-y-0.5 text-success" />
      {children}
    </p>
  )
}

/** "Chưa xác minh", in the neutral tone, leading to ACC-2. */
function Unverified() {
  return (
    <Link to="/ho-so/xac-minh" className="text-body-sm text-muted-foreground underline underline-offset-4">
      {t('screen.profile.verified.unverified')}
    </Link>
  )
}

/** A detail declared at verification: "Khai khi xác minh". */
function Declared({ label, value }: { label: string; value: string }) {
  return (
    <li className="flex flex-wrap items-center gap-x-2 gap-y-1">
      <span className="text-muted-foreground">{label}:</span>
      <span className="break-words">{value}</span>
      <span className="rounded-full bg-muted px-2 py-0.5 text-caption font-semibold text-muted-foreground">
        {t('screen.profile.verified.declared')}
      </span>
    </li>
  )
}
