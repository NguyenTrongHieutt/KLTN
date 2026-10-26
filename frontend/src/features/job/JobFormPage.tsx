import { Dialog } from '@base-ui/react/dialog'
import { FileText, Info, TriangleAlert, X } from 'lucide-react'
import { useId, useRef, useState, type ChangeEvent, type FormEvent, type ReactNode } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { inputClass } from '@/components/formField-props'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { SkillPicker } from '@/components/SkillPicker'
import { TermHint } from '@/components/TermHint'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { useSession } from '@/features/identity/useSession'
import { useProduct, type Product } from '@/features/product/productApi'
import { NotFoundScreen } from '@/features/shared/NotFoundScreen'
import { t, type MessageKey } from '@/i18n/t'
import { ApiError } from '@/lib/apiClient'
import { formatSize } from '@/lib/fileSize'
import { isNotFound, problemMessage } from '@/lib/problemMessage'
import { cn } from '@/lib/utils'
import {
  BUDGETS,
  JOB_TYPES,
  useAddScreenshot,
  useJob,
  usePublishJob,
  useRemoveScreenshot,
  useSaveJob,
  useSkillSuggestions,
  type Budget,
  type Job,
  type JobDraft,
  type JobType,
} from './jobApi'

/** The answers publishing needs besides the title, in the order CUS-6 asks them (FR-9). */
const REQUIRED = ['problem', 'expected', 'appUsers', 'jobTypes', 'budget', 'deadline'] as const

type Required = (typeof REQUIRED)[number]

function missingAnswers(values: FormValues): Required[] {
  return REQUIRED.filter((field) => {
    const value = values[field]
    return Array.isArray(value) ? value.length === 0 : value.trim() === ''
  })
}

function missingText(fields: string[]): string {
  const labels = fields
    .filter((field): field is Required => (REQUIRED as readonly string[]).includes(field))
    .map((field) => t(`screen.job.form.missing${field.charAt(0).toUpperCase()}${field.slice(1)}` as MessageKey))
  return t('screen.job.form.publishMissing', { fields: labels.join(', ') })
}

/** The values CUS-6 edits, before they are sent. */
interface FormValues {
  title: string
  problem: string
  expected: string
  appUsers: string
  doneCheck: string
  jobTypes: JobType[]
  skillIds: string[]
  budget: Budget | ''
  deadline: string
}

/**
 * CUS-6 (`/san-pham/{productId}/cong-viec-moi`, `/cong-viec/{jobId}/sua`, FR-9, UX-DR66): the guided
 * questions with their examples, the Job Types or "Chưa rõ — đánh giá trước", the Skills suggested
 * from the repository and the AI tools, the budget range, the deadline, and screenshots. While the
 * Product has an open Finding only the Assessment can be chosen. "Lưu nháp" keeps a Draft; "Đăng tin"
 * saves it, shows N-3, and publishes it as a Job Post (J1), landing on JOB-1.
 */
export function JobFormPage() {
  const { productId, jobId } = useParams()
  const job = useJob(jobId)
  const role = useSession().data?.account?.role
  usePageTitle(t(jobId ? 'screen.job.form.editTitle' : 'screen.job.form.newTitle'))
  if ((role !== undefined && role !== 'CUSTOMER') || (job.isError && isNotFound(job.error))) {
    return <NotFoundScreen />
  }
  return (
    <div className="mx-auto flex max-w-[760px] flex-col gap-6 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      {jobId ? (
        <RegionState query={job} skeleton={<Skeleton className="h-96 w-full" />}>
          {job.data && <ProductStep productId={job.data.productId} job={job.data} />}
        </RegionState>
      ) : (
        productId && <ProductStep productId={productId} />
      )}
    </div>
  )
}

function ProductStep({ productId, job }: { productId: string; job?: Job }) {
  const product = useProduct(productId)
  const suggestions = useSkillSuggestions(job ? undefined : productId)
  // FR-38: "Đăng công việc ghép mã" from a ZIP Fallback asks a Mentor to merge that code.
  const [params] = useSearchParams()
  const mergeCode = !job && params.has('ghep-ma')
  if (product.isError && isNotFound(product.error)) {
    return <NotFoundScreen />
  }
  const ready = product.data && (job || !suggestions.isPending)
  return (
    <RegionState query={product} skeleton={<Skeleton className="h-96 w-full" />}>
      {ready && product.data && (
        <JobForm
          product={product.data}
          job={job}
          initial={
            job
              ? fromJob(job)
              : mergeCode
                ? mergingCode(empty((suggestions.data ?? []).map((skill) => skill.id)))
                : empty((suggestions.data ?? []).map((skill) => skill.id))
          }
          known={
            job
              ? job.skills
              : (suggestions.data ?? []).map((skill) => ({ id: skill.id, name: skill.name, active: true }))
          }
        />
      )}
    </RegionState>
  )
}

function empty(skillIds: string[]): FormValues {
  return {
    title: '',
    problem: '',
    expected: '',
    appUsers: '',
    doneCheck: '',
    jobTypes: [],
    skillIds: skillIds.slice(0, 10),
    budget: '',
    deadline: '',
  }
}

/** CUS-6 pre-filled to merge a ZIP Fallback's code into the project (FR-38, N-17). */
function mergingCode(values: FormValues): FormValues {
  return {
    ...values,
    title: t('screen.closure.zip.prefill.title'),
    problem: t('screen.closure.zip.prefill.problem'),
    expected: t('screen.closure.zip.prefill.expected'),
    jobTypes: ['MODIFICATION'],
  }
}

function fromJob(job: Job): FormValues {
  return {
    title: job.title,
    problem: job.problem ?? '',
    expected: job.expected ?? '',
    appUsers: job.appUsers ?? '',
    doneCheck: job.doneCheck ?? '',
    jobTypes: job.jobTypes,
    skillIds: job.skills.map((skill) => skill.id),
    budget: job.budget ?? '',
    deadline: job.deadline ?? '',
  }
}

function JobForm({
  product,
  job,
  initial,
  known,
}: {
  product: Product
  job?: Job
  initial: FormValues
  known: { id: string; name: string; active: boolean }[]
}) {
  const [values, setValues] = useState(initial)
  const [titleMissing, setTitleMissing] = useState(false)
  const [missing, setMissing] = useState<string[]>([])
  const [confirming, setConfirming] = useState(false)
  const save = useSaveJob(job?.id)
  const publish = usePublishJob()
  const toasts = useToasts()
  const navigate = useNavigate()
  const ids = useId()
  const findings = product.openFindings
  const assessment = values.jobTypes.includes('ASSESSMENT')
  const set = <K extends keyof FormValues>(key: K, value: FormValues[K]) =>
    setValues((current) => ({ ...current, [key]: value }))
  // Assessment never combines with another Job Type (FR-9).
  const toggleType = (type: JobType, checked: boolean) => {
    if (!checked) {
      set('jobTypes', values.jobTypes.filter((other) => other !== type))
    } else {
      set('jobTypes', type === 'ASSESSMENT' ? ['ASSESSMENT'] : [...values.jobTypes.filter((other) => other !== 'ASSESSMENT'), type])
    }
  }
  const body = (): JobDraft => ({
    productId: product.id,
    title: values.title.trim(),
    problem: values.problem,
    expected: values.expected,
    appUsers: values.appUsers,
    doneCheck: values.doneCheck,
    jobTypes: values.jobTypes,
    skillIds: values.skillIds,
    budget: values.budget === '' ? undefined : values.budget,
    deadline: values.deadline === '' ? undefined : values.deadline,
  })
  // "Đăng tin": every answer first, then N-3 (FR-9, UX-DR8).
  const startPublish = () => {
    if (values.title.trim() === '') {
      setTitleMissing(true)
      return
    }
    const unanswered = missingAnswers(values)
    setMissing(unanswered)
    if (unanswered.length === 0) {
      setConfirming(true)
    }
  }
  const confirmPublish = async () => {
    let id: string
    try {
      id = await save.mutateAsync(body())
    } catch (error) {
      setConfirming(false)
      toasts.error(problemMessage(error, { module: 'job' }).text)
      return
    }
    try {
      await publish.mutateAsync(id)
      setConfirming(false)
      toasts.success(t('screen.job.form.published'))
      void navigate(`/cong-viec/${id}`)
    } catch (error) {
      setConfirming(false)
      const problem = error instanceof ApiError ? error.problem : undefined
      if (problem?.reason === 'INTAKE_FINDING_OPEN') {
        toasts.error(t('screen.job.form.publishFindings', { n: problem.count ?? findings }))
      } else if (problem?.reason === 'JOB_INCOMPLETE') {
        setMissing(problem.missing ?? [])
      } else {
        const message = problemMessage(error, { module: 'job', action: t('screen.job.form.publishAction') })
        toasts.error(message.text, message.link)
      }
      if (!job) {
        void navigate(`/cong-viec/${id}/sua`, { replace: true })
      }
    }
  }
  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (values.title.trim() === '') {
      setTitleMissing(true)
      return
    }
    save.mutate(
      body(),
      {
        onSuccess: (id) => {
          toasts.success(t('screen.job.form.saved'))
          if (!job) {
            void navigate(`/cong-viec/${id}/sua`, { replace: true })
          }
        },
        onError: (error) => toasts.error(problemMessage(error, { module: 'job' }).text),
      },
    )
  }
  return (
    <>
      <nav aria-label={t('screen.job.form.breadcrumbProducts')} className="text-caption text-muted-foreground">
        <Link to="/san-pham" className="text-primary underline-offset-4 hover:underline">
          {t('screen.job.form.breadcrumbProducts')}
        </Link>{' '}
        /{' '}
        <Link to={`/san-pham/${product.id}`} className="break-words text-primary underline-offset-4 hover:underline">
          {product.name}
        </Link>{' '}
        / {t(job ? 'screen.job.form.editTitle' : 'screen.job.form.newTitle')}
      </nav>
      <div className="flex flex-col gap-1">
        <PageHeading>{t(job ? 'screen.job.form.editTitle' : 'screen.job.form.newTitle')}</PageHeading>
        <p className="text-body-sm text-muted-foreground">{t('screen.job.form.lead')}</p>
      </div>
      <form onSubmit={submit} className="flex flex-col gap-6" noValidate>
        <Question
          id={`${ids}-title`}
          label={t('screen.job.form.titleLabel')}
          example={t('screen.job.form.titleExample')}
          error={titleMissing ? t('screen.job.form.titleRequired') : undefined}
        >
          {(props) => (
            <input
              {...props}
              type="text"
              maxLength={120}
              value={values.title}
              onChange={(event) => {
                set('title', event.target.value)
                setTitleMissing(false)
              }}
              className={inputClass}
            />
          )}
        </Question>
        <TextQuestion id={`${ids}-problem`} field="problem" values={values} set={set} max={2000} />
        <TextQuestion id={`${ids}-expected`} field="expected" values={values} set={set} max={2000} />
        <TextQuestion id={`${ids}-appUsers`} field="appUsers" values={values} set={set} max={1000} />
        <TextQuestion id={`${ids}-doneCheck`} field="doneCheck" values={values} set={set} max={1000} />

        <fieldset className="flex flex-col gap-2.5">
          <legend className="mb-1 text-label font-semibold">{t('screen.job.form.jobTypes')}</legend>
          {findings > 0 && (
            <div id={`${ids}-findings`} role="note" className="flex gap-3 rounded-md border-l-4 border-destructive bg-destructive-soft p-4">
              <Info aria-hidden="true" className="size-5 shrink-0 text-destructive" />
              <div className="flex flex-col gap-0.5">
                <p className="text-label font-semibold">{t('screen.job.form.openFindings', { n: findings })}</p>
                <p className="text-body-sm">
                  {t('screen.job.form.openFindingsBody')}{' '}
                  <Link to={`/san-pham/${product.id}`} className="text-primary underline-offset-4 hover:underline">
                    {t('screen.job.form.openFindingsLink')}
                  </Link>
                </p>
              </div>
            </div>
          )}
          <div className="grid gap-2 sm:grid-cols-3">
            {JOB_TYPES.map((type) => {
              const disabled = type !== 'ASSESSMENT' && (findings > 0 || assessment)
              return (
                <label
                  key={type}
                  className={cn(
                    'flex items-start gap-2 rounded-md border border-border bg-card p-3 text-body-sm',
                    values.jobTypes.includes(type) && 'border-primary',
                    disabled && 'opacity-60',
                  )}
                >
                  <input
                    type="checkbox"
                    className="mt-1"
                    checked={values.jobTypes.includes(type)}
                    disabled={disabled}
                    aria-describedby={disabled && findings > 0 ? `${ids}-findings` : undefined}
                    onChange={(event) => toggleType(type, event.target.checked)}
                  />
                  <TermHint term={`jobType${type}`} />
                </label>
              )
            })}
            <label
              className={cn(
                'flex items-start gap-2 rounded-md border border-border bg-card p-3 text-body-sm',
                assessment && 'border-primary',
              )}
            >
              <input
                type="checkbox"
                className="mt-1"
                checked={assessment}
                aria-describedby={`${ids}-notSure`}
                onChange={(event) => set('jobTypes', event.target.checked ? ['ASSESSMENT'] : [])}
              />
              <span className="flex flex-col gap-0.5">
                <span>{t('screen.job.form.notSure')}</span>
                <span id={`${ids}-notSure`} className="text-caption text-muted-foreground">
                  {t('screen.job.form.notSureHint')}
                </span>
              </span>
            </label>
          </div>
        </fieldset>

        <div className="flex flex-col gap-1.5">
          <label htmlFor={`${ids}-skills`} className="text-label font-semibold">
            {t('screen.job.form.skillsLabel')}
          </label>
          <p id={`${ids}-skillsHint`} className="text-caption text-muted-foreground">
            {t('screen.job.form.skillsHint')}
          </p>
          <SkillPicker
            id={`${ids}-skills`}
            value={values.skillIds}
            onChange={(skillIds) => set('skillIds', skillIds)}
            known={known}
            max={10}
            showHints
            describedBy={`${ids}-skillsHint ${ids}-skillsCount`}
          />
          <p id={`${ids}-skillsCount`} className="text-caption text-muted-foreground">
            {t('screen.job.form.skillsCount', { n: values.skillIds.length })}
          </p>
        </div>

        <div className="grid gap-4 sm:grid-cols-2">
          <div className="flex flex-col gap-1">
            <label htmlFor={`${ids}-budget`} className="text-label font-semibold">
              {t('screen.job.form.budgetLabel')}
            </label>
            <select
              id={`${ids}-budget`}
              value={values.budget}
              onChange={(event) => set('budget', event.target.value as Budget | '')}
              aria-describedby={`${ids}-budgetNote`}
              className={inputClass}
            >
              <option value="">{t('screen.job.form.budgetNone')}</option>
              {BUDGETS.map((budget) => (
                <option key={budget} value={budget}>
                  {t(`screen.job.form.budget${budget}` as MessageKey)}
                </option>
              ))}
            </select>
            <p id={`${ids}-budgetNote`} className="text-caption text-muted-foreground">
              {t('screen.job.form.budgetNote')}
            </p>
          </div>
          <div className="flex flex-col gap-1">
            <label htmlFor={`${ids}-deadline`} className="text-label font-semibold">
              {t('screen.job.form.deadlineLabel')}
            </label>
            <input
              id={`${ids}-deadline`}
              type="date"
              value={values.deadline}
              onChange={(event) => set('deadline', event.target.value)}
              className={inputClass}
            />
          </div>
        </div>

        <Screenshots job={job} />

        <div role="note" className="flex gap-3 rounded-md border-l-4 border-warning bg-warning-soft p-4">
          <TriangleAlert aria-hidden="true" className="size-5 shrink-0 text-warning" />
          <p className="text-body-sm">{t('screen.job.form.confidentiality')}</p>
        </div>

        {missing.length > 0 && (
          <p role="alert" className="text-body-sm text-destructive">
            {missingText(missing)}
          </p>
        )}
        <div className="flex flex-wrap justify-between gap-3">
          <Button type="submit" variant="secondary" disabled={save.isPending}>
            {t(save.isPending ? 'screen.job.form.saving' : 'screen.job.form.save')}
          </Button>
          <Button type="button" onClick={startPublish} disabled={save.isPending || publish.isPending}>
            {t('screen.job.form.publish')}
          </Button>
        </div>
      </form>
      <Dialog.Root open={confirming} onOpenChange={setConfirming}>
        <Dialog.Portal>
          <Dialog.Backdrop className="fixed inset-0 z-40 bg-foreground/50" />
          <Dialog.Popup className="fixed top-24 left-1/2 z-50 flex w-[min(520px,calc(100vw-32px))] -translate-x-1/2 flex-col gap-4 rounded-lg border-t-4 border-warning bg-popover p-6 text-popover-foreground shadow-md">
            <div className="flex items-start justify-between gap-3">
              <Dialog.Title className="text-h2">{t('screen.job.form.publishTitle')}</Dialog.Title>
              <Dialog.Close
                aria-label={t('screen.product.finding.close')}
                className="rounded-md p-1 text-muted-foreground hover:bg-muted"
              >
                <X aria-hidden="true" className="size-5" />
              </Dialog.Close>
            </div>
            <Dialog.Description className="text-body-sm">{t('screen.job.form.confidentiality')}</Dialog.Description>
            <div className="flex flex-wrap justify-between gap-3">
              <Dialog.Close render={<Button variant="secondary" />}>{t('screen.job.form.publishBack')}</Dialog.Close>
              <Button type="button" onClick={() => void confirmPublish()} disabled={save.isPending || publish.isPending}>
                {t(save.isPending || publish.isPending ? 'screen.job.form.publishing' : 'screen.job.form.publish')}
              </Button>
            </div>
          </Dialog.Popup>
        </Dialog.Portal>
      </Dialog.Root>
    </>
  )
}

type ControlProps = {
  id: string
  'aria-describedby': string
  'aria-invalid'?: boolean
}

/** One guided question: its label, its example for a non-technical reader, and its control. */
function Question({
  id,
  label,
  example,
  error,
  children,
}: {
  id: string
  label: string
  example: string
  error?: string
  children: (props: ControlProps) => ReactNode
}) {
  return (
    <div className="flex flex-col gap-1">
      <label htmlFor={id} className="text-label font-semibold">
        {label}
      </label>
      <p id={`${id}-example`} className="text-caption text-muted-foreground">
        {example}
      </p>
      {children({
        id,
        'aria-describedby': `${id}-example${error ? ` ${id}-error` : ''}`,
        'aria-invalid': error ? true : undefined,
      })}
      {error && (
        <p id={`${id}-error`} role="alert" className="text-body-sm text-destructive">
          {error}
        </p>
      )}
    </div>
  )
}

function TextQuestion({
  id,
  field,
  values,
  set,
  max,
}: {
  id: string
  field: 'problem' | 'expected' | 'appUsers' | 'doneCheck'
  values: FormValues
  set: (key: 'problem' | 'expected' | 'appUsers' | 'doneCheck', value: string) => void
  max: number
}) {
  return (
    <Question
      id={id}
      label={t(`screen.job.form.${field}Label` as MessageKey)}
      example={t(`screen.job.form.${field}Example` as MessageKey)}
    >
      {(props) => (
        <textarea
          {...props}
          rows={3}
          maxLength={max}
          value={values[field]}
          onChange={(event) => set(field, event.target.value)}
          className={cn(inputClass, 'min-h-24')}
        />
      )}
    </Question>
  )
}

/** The screenshots of a saved Draft, with the N-4 reminder (FR-9, NFR-3). */
function Screenshots({ job }: { job?: Job }) {
  const ids = useId()
  return (
    <div className="flex flex-col gap-2">
      <p id={`${ids}-label`} className="text-label font-semibold">
        {t('screen.job.form.screenshotsLabel')}
      </p>
      {job ? (
        <ScreenshotUpload job={job} labelledBy={`${ids}-label`} />
      ) : (
        <p className="text-body-sm text-muted-foreground">{t('screen.job.form.saveFirst')}</p>
      )}
    </div>
  )
}

function ScreenshotUpload({ job, labelledBy }: { job: Job; labelledBy: string }) {
  const add = useAddScreenshot(job.id)
  const remove = useRemoveScreenshot(job.id)
  const toasts = useToasts()
  const input = useRef<HTMLInputElement>(null)
  const pick = (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0]
    event.target.value = ''
    if (file) {
      add.mutate(file, { onError: (error) => toasts.error(problemMessage(error, { module: 'job' }).text) })
    }
  }
  return (
    <>
      <div className="flex flex-col items-center gap-1.5 rounded-lg border border-dashed border-border bg-card p-5 text-center">
        <input
          ref={input}
          type="file"
          accept="image/*,application/pdf"
          className="sr-only"
          aria-labelledby={labelledBy}
          onChange={pick}
        />
        <Button type="button" variant="secondary" onClick={() => input.current?.click()} disabled={add.isPending}>
          {t(add.isPending ? 'screen.job.form.uploading' : 'screen.job.form.choose')}
        </Button>
        <p className="text-caption text-muted-foreground">{t('screen.job.form.fileRule')}</p>
        <p className="text-caption text-warning">{t('screen.job.form.personalData')}</p>
      </div>
      {job.screenshots.length > 0 && (
        <ul className="flex flex-col gap-2">
          {job.screenshots.map((shot) => (
            <li key={shot.fileId} className="flex items-center gap-3 rounded-md border border-border bg-card px-4 py-2.5">
              <FileText aria-hidden="true" className="size-5 shrink-0 text-muted-foreground" />
              <a href={shot.url} target="_blank" rel="noopener noreferrer" className="grow text-body-sm break-all text-primary underline-offset-4 hover:underline">
                {shot.fileName} · {formatSize(shot.sizeBytes)}
              </a>
              <Button
                type="button"
                variant="ghost"
                aria-label={t('screen.job.form.removeFile', { name: shot.fileName })}
                onClick={() => remove.mutate(shot.fileId)}
              >
                {t('screen.job.form.remove')}
              </Button>
            </li>
          ))}
        </ul>
      )}
    </>
  )
}
