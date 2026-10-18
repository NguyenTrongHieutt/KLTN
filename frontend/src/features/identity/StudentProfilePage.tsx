import { Plus, Trash2 } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { z } from 'zod'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { usePollHealth } from '@/app/usePollHealth'
import { ErrorSummary } from '@/components/ErrorSummary'
import { FormField } from '@/components/FormField'
import { fieldProps, inputClass } from '@/components/formField-props'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { t } from '@/i18n/t'
import { formatDayMonthTime } from '@/lib/dates'
import { fieldMessage, serverFieldErrors, zodFieldErrors } from '@/lib/formErrors'
import { problemMessage } from '@/lib/problemMessage'
import { useProfile, useSaveStudentProfile, type Profile } from './profileApi'

/** The repositories a Student shows, at most 5 (FR-4). */
export const MAX_REPOSITORIES = 5

const REPOSITORY = /^(?:https:\/\/github\.com\/)?[A-Za-z0-9][A-Za-z0-9-]{0,38}\/[A-Za-z0-9._-]{1,100}?(?:\.git)?\/?$/

const schema = z.object({
  realName: z.string().trim().max(100, 'tooLong'),
  school: z.string().trim().max(150, 'tooLong'),
  yearOfStudy: z.string(),
  linkedinUrl: z
    .string()
    .trim()
    .regex(/^(?:https:\/\/www\.linkedin\.com\/in\/[A-Za-z0-9_%-]{3,100}\/?)?$/, 'format'),
  showNameAndSchool: z.boolean(),
  repositories: z.array(z.string().trim().regex(REPOSITORY, 'format')).max(MAX_REPOSITORIES, 'tooLong'),
})

type Values = z.input<typeof schema>

type Field = 'realName' | 'school' | 'linkedinUrl' | 'repositories'

const LABELS: Record<Field, string> = {
  realName: t('screen.student.profile.realName'),
  school: t('screen.student.profile.school'),
  linkedinUrl: t('screen.student.profile.linkedin'),
  repositories: t('screen.student.profile.repositories'),
}

/**
 * STU-5 (`/ho-so`), the profile part (FR-4, FR-51): real name, school, year of study, LinkedIn, the
 * public-name choice (hidden by default), and up to 5 showcased repositories, shown as links only;
 * their code is never fetched. The GitHub username comes from sign-in. Verification arrives with
 * Stories 1.9 and 1.13.
 */
export function StudentProfilePage() {
  usePageTitle(t('screen.student.profile.title'))
  const profile = useProfile()

  return (
    <div className="mx-auto flex max-w-reading-max flex-col gap-6 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      <PageHeading>{t('screen.student.profile.heading')}</PageHeading>
      <p className="rounded-lg border border-border bg-card p-4 text-body-sm">{t('screen.student.profile.next')}</p>
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
        {profile.data && <StudentForm profile={profile.data} />}
      </RegionState>
    </div>
  )
}

function StudentForm({ profile }: { profile: Profile }) {
  const saved = profile.student
  const [values, setValues] = useState<Values>({
    realName: saved?.realName ?? '',
    school: saved?.school ?? '',
    yearOfStudy: saved?.yearOfStudy ? String(saved.yearOfStudy) : '',
    linkedinUrl: saved?.linkedinUrl ?? '',
    showNameAndSchool: saved?.showNameAndSchool ?? false,
    repositories: saved?.repositories ?? [],
  })
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [summary, setSummary] = useState(false)
  const [savedAt, setSavedAt] = useState<string | null>(null)
  const save = useSaveStudentProfile()
  const toasts = useToasts()
  const { offline } = usePollHealth()

  function validate(field: Exclude<Field, 'repositories'>) {
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

  function setRepository(index: number, value: string) {
    setValues((current) => ({
      ...current,
      repositories: current.repositories.map((repository, at) => (at === index ? value : repository)),
    }))
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    const result = schema.safeParse(values)
    if (!result.success) {
      setErrors(zodFieldErrors(result.error))
      setSummary(true)
      return
    }
    const data = result.data
    save.mutate(
      {
        realName: data.realName || undefined,
        school: data.school || undefined,
        yearOfStudy: data.yearOfStudy ? Number(data.yearOfStudy) : undefined,
        linkedinUrl: data.linkedinUrl || undefined,
        showNameAndSchool: data.showNameAndSchool,
        repositories: data.repositories.filter((repository) => repository !== ''),
      },
      {
        onSuccess: () => {
          setErrors({})
          setSummary(false)
          setSavedAt(new Date().toISOString())
          toasts.success(t('screen.student.profile.saved'))
        },
        onError: (error) => {
          const fields = serverFieldErrors(error)
          setErrors(fields)
          setSummary(Object.keys(fields).length > 0)
          toasts.error(problemMessage(error).text)
        },
      },
    )
  }

  const listed = (Object.keys(LABELS) as Field[])
    .filter((field) => errors[field])
    .map((field) => ({ id: field, label: LABELS[field], message: errors[field] }))

  return (
    <form noValidate onSubmit={submit} className="flex flex-col gap-5">
      {summary && <ErrorSummary errors={listed} />}
      <div className="flex flex-col gap-1">
        <p className="text-label">{t('screen.student.profile.github')}</p>
        <p className="text-body">{profile.githubLogin}</p>
        <p className="text-caption text-muted-foreground">{t('screen.student.profile.githubHelper')}</p>
      </div>
      <FormField id="realName" label={LABELS.realName} error={errors.realName}>
        <input
          {...fieldProps('realName', errors.realName)}
          className={inputClass}
          value={values.realName}
          maxLength={100}
          autoComplete="name"
          onChange={(event) => setValues({ ...values, realName: event.target.value })}
          onBlur={() => validate('realName')}
        />
      </FormField>
      <FormField id="school" label={LABELS.school} error={errors.school}>
        <input
          {...fieldProps('school', errors.school)}
          className={inputClass}
          value={values.school}
          maxLength={150}
          onChange={(event) => setValues({ ...values, school: event.target.value })}
          onBlur={() => validate('school')}
        />
      </FormField>
      <FormField id="yearOfStudy" label={t('screen.student.profile.year')}>
        <select
          {...fieldProps('yearOfStudy')}
          className={inputClass}
          value={values.yearOfStudy}
          onChange={(event) => setValues({ ...values, yearOfStudy: event.target.value })}
        >
          <option value="">{t('screen.student.profile.yearNone')}</option>
          {[1, 2, 3, 4, 5, 6, 7, 8].map((year) => (
            <option key={year} value={String(year)}>
              {t('screen.student.profile.yearN', { n: year })}
            </option>
          ))}
        </select>
      </FormField>
      <label className="flex cursor-pointer items-start gap-3">
        <input
          type="checkbox"
          checked={values.showNameAndSchool}
          onChange={(event) => setValues({ ...values, showNameAndSchool: event.target.checked })}
          className="mt-0.5 size-5 shrink-0 accent-primary"
        />
        <span className="flex flex-col gap-0.5">
          <span className="text-body-sm">{t('screen.student.profile.showNameAndSchool')}</span>
          <span className="text-caption text-muted-foreground">{t('screen.student.profile.showHelper')}</span>
        </span>
      </label>
      <FormField
        id="linkedinUrl"
        label={LABELS.linkedinUrl}
        helper={t('screen.student.profile.linkedinHelper')}
        error={errors.linkedinUrl}
      >
        <input
          {...fieldProps('linkedinUrl', errors.linkedinUrl, t('screen.student.profile.linkedinHelper'))}
          type="url"
          className={inputClass}
          value={values.linkedinUrl}
          onChange={(event) => setValues({ ...values, linkedinUrl: event.target.value })}
          onBlur={() => validate('linkedinUrl')}
        />
      </FormField>
      <fieldset id="repositories" tabIndex={-1} className="flex flex-col gap-2 focus:outline-none">
        <legend className="mb-1 text-label">{LABELS.repositories}</legend>
        <p className="text-caption text-muted-foreground">{t('screen.student.profile.repositoriesHelper')}</p>
        {values.repositories.map((repository, index) => (
          <div key={index} className="flex items-center gap-2">
            <input
              aria-label={t('screen.student.profile.repositoryN', { n: index + 1 })}
              className={inputClass}
              value={repository}
              placeholder="owner/ten-kho"
              onChange={(event) => setRepository(index, event.target.value)}
            />
            <Button
              type="button"
              variant="ghost"
              size="compact"
              aria-label={t('screen.student.profile.removeRepository', { n: index + 1 })}
              onClick={() =>
                setValues({ ...values, repositories: values.repositories.filter((_, at) => at !== index) })
              }
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
            onClick={() => setValues({ ...values, repositories: [...values.repositories, ''] })}
          >
            <Plus aria-hidden="true" />
            {t('screen.student.profile.addRepository')}
          </Button>
        )}
        {saved && saved.repositories.length > 0 && (
          <ul className="flex flex-col gap-1 text-body-sm">
            {saved.repositories.map((repository) => (
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
      <div className="flex flex-wrap items-center gap-3">
        <Button type="submit" disabled={save.isPending || offline}>
          {save.isPending ? t('screen.customer.profile.saving') : t('screen.customer.profile.save')}
        </Button>
        {savedAt && (
          <p role="status" className="text-body-sm text-muted-foreground">
            {t('screen.customer.profile.savedAt', { time: formatDayMonthTime(savedAt) })}
          </p>
        )}
      </div>
    </form>
  )
}
