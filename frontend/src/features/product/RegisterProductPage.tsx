import { useQueryClient } from '@tanstack/react-query'
import { Check, CircleAlert, ExternalLink, GitFork, X } from 'lucide-react'
import { useEffect, useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router'
import { z } from 'zod'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { ErrorSummary } from '@/components/ErrorSummary'
import { FormField } from '@/components/FormField'
import { fieldProps, inputClass } from '@/components/formField-props'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { GitHubGate } from '@/features/identity/GitHubGate'
import { GITHUB_LINK_QUERY_KEY } from '@/features/identity/githubApi'
import { t, type MessageKey } from '@/i18n/t'
import vi from '@/i18n/vi.json'
import { ApiError } from '@/lib/apiClient'
import { fieldMessage, zodFieldErrors } from '@/lib/formErrors'
import { problemMessage } from '@/lib/problemMessage'
import { cn } from '@/lib/utils'
import { useRegisterProduct, useRepositoryCheck, type RepositoryCheck, type Tool } from './productApi'
import { MAKE_PUBLIC_DOCS, TOOL_DOCS, TOOLS, domainOf, megabytes } from './productText'

const NOTICE_VERSION = (vi as { notice: { product: { version: string } } }).notice.product.version

/** CUS-3 step 2's checklist, in the server's order; each item names the reason that fails it. */
const CHECKS: { label: MessageKey; reason: string }[] = [
  { label: 'screen.product.wizard.checkFound', reason: 'REPO_NOT_FOUND' },
  { label: 'screen.product.wizard.checkPublic', reason: 'REPO_PRIVATE' },
  { label: 'screen.product.wizard.checkAdmin', reason: 'NO_ADMIN_PERMISSION' },
  { label: 'screen.product.wizard.checkNotLinked', reason: 'REPO_ALREADY_LINKED' },
  { label: 'screen.product.wizard.checkContent', reason: 'REPO_UNSUPPORTED_CONTENT' },
  { label: 'screen.product.wizard.checkSize', reason: 'REPO_TOO_LARGE' },
]

const details = z.object({
  name: z.string().trim().min(1, 'required').max(80, 'tooLong'),
  description: z.string().trim().min(1, 'required').max(1000, 'tooLong'),
  demoUrl: z.string().trim().max(300, 'tooLong').regex(/^(?:https:\/\/\S+)?$/, 'format'),
})

type Details = z.input<typeof details>

/** The outcome of the last repository check: passed, or refused with a reason. */
type Checked = { kind: 'passed'; repository: RepositoryCheck } | { kind: 'refused'; reason: string; message: string }

/** Where the GitHub gate returns, and where the wizard's unsent input waits meanwhile (UX-DR95). */
const ROUTE = '/san-pham/moi'

/** The answers that send the wizard back to the GitHub gate: no link, or no kept token that GitHub accepts. */
const GATE_REASONS = ['RELINK_GITHUB', 'GITHUB_NOT_LINKED']

const DRAFT_KEY = 'vibefix.product.registration'

/** What the wizard holds when the GitHub gate sends the browser to GitHub; the repository is checked again on return. */
interface Draft {
  step: number
  tools: Tool[]
  url: string
  values: Details
}

function readDraft(): Draft | undefined {
  try {
    const stored = window.sessionStorage.getItem(DRAFT_KEY)
    if (stored) {
      const draft = JSON.parse(stored) as Draft
      if (typeof draft.step === 'number' && Array.isArray(draft.tools) && typeof draft.url === 'string' && draft.values) {
        return draft
      }
    }
  } catch {
    // A blocked or full storage only loses the draft.
  }
  return undefined
}

function writeDraft(draft: Draft | undefined) {
  try {
    if (draft) {
      window.sessionStorage.setItem(DRAFT_KEY, JSON.stringify(draft))
    } else {
      window.sessionStorage.removeItem(DRAFT_KEY)
    }
  } catch {
    // As above.
  }
}

/**
 * CUS-3 (`/san-pham/moi`, FR-7, AD-13), a focused task route in four steps: the AI tool and how
 * to get its code onto GitHub; the repository with live checks; the name, description, and demo
 * link; and the three acknowledgments, after which the Product is registered and CUS-4 opens. The
 * GitHub gate (UX-DR95, FR-80) replaces step 2's form until GitHub is linked with a kept token; the
 * input waits in a `sessionStorage` draft while the browser is on GitHub, and comes back at step 2.
 */
export function RegisterProductPage() {
  usePageTitle(t('screen.product.wizard.title'))
  return <Wizard />
}

function Wizard() {
  const [draft] = useState(readDraft)
  const [step, setStep] = useState(draft?.step ?? 1)
  const [tools, setTools] = useState<Tool[]>(draft?.tools ?? [])
  const [url, setUrl] = useState(draft?.url ?? '')
  const [checked, setChecked] = useState<Checked>()
  const [values, setValues] = useState<Details>(draft?.values ?? { name: '', description: '', demoUrl: '' })
  // The backend said GitHub must be linked again: the gate takes over step 2 whatever the link read says.
  const [relink, setRelink] = useState(false)
  const queryClient = useQueryClient()
  // A draft comes back once: it is taken as the wizard opens.
  useEffect(() => writeDraft(undefined), [])
  const toGate = () => {
    void queryClient.invalidateQueries({ queryKey: GITHUB_LINK_QUERY_KEY })
    setRelink(true)
    setChecked(undefined)
    setStep(2)
  }

  return (
    <div className="mx-auto flex max-w-[760px] flex-col gap-6 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      <div className="flex flex-col gap-2">
        <p className="text-label text-muted-foreground">{t('screen.product.wizard.step', { n: step })}</p>
        <div
          role="progressbar"
          aria-label={t('screen.product.wizard.progress')}
          aria-valuemin={1}
          aria-valuemax={4}
          aria-valuenow={step}
          className="h-1.5 w-full overflow-hidden rounded-full bg-muted"
        >
          <div className="h-full rounded-full bg-primary" style={{ width: `${step * 25}%` }} />
        </div>
      </div>
      {step === 1 && <ToolStep tools={tools} onChange={setTools} onNext={() => setStep(2)} />}
      {step === 2 && (
        <GitHubGate
          action={t('screen.githubGate.action.registerProduct')}
          returnTo={ROUTE}
          needsRepositoryToken
          forceRelink={relink}
          onLeave={() => writeDraft({ step: 2, tools, url, values })}
          onBack={() => setStep(1)}
        >
          <RepositoryStep
            tool={tools[0]}
            url={url}
            checked={checked}
            onUrl={(next) => {
              setUrl(next)
              setChecked(undefined)
            }}
            onChecked={setChecked}
            onGate={toGate}
            onBack={() => setStep(1)}
            onNext={() => setStep(3)}
          />
        </GitHubGate>
      )}
      {step === 3 && <DetailsStep values={values} onChange={setValues} onBack={() => setStep(2)} onNext={() => setStep(4)} />}
      {step === 4 && (
        <NoticeStep
          registration={{ url, tools, values }}
          onBack={() => setStep(3)}
          onRefused={() => {
            setChecked(undefined)
            setStep(2)
          }}
          onGate={toGate}
        />
      )}
    </div>
  )
}

function ToolStep({ tools, onChange, onNext }: { tools: Tool[]; onChange: (tools: Tool[]) => void; onNext: () => void }) {
  const [error, setError] = useState<string>()
  const shown = tools[0]
  return (
    <form
      noValidate
      className="flex flex-col gap-5"
      onSubmit={(event) => {
        event.preventDefault()
        if (tools.length === 0) {
          setError(t('screen.product.wizard.toolsRequired'))
          return
        }
        onNext()
      }}
    >
      <div className="flex flex-col gap-1">
        <PageHeading>{t('screen.product.wizard.step1Heading')}</PageHeading>
        <p className="text-body-sm text-muted-foreground">{t('screen.product.wizard.step1Lead')}</p>
      </div>
      <fieldset className="flex flex-col gap-2" aria-describedby={error ? 'tools-error' : undefined}>
        <legend className="mb-2 text-label">{t('screen.product.wizard.toolsLabel')}</legend>
        <div className="flex flex-wrap gap-2">
          {TOOLS.map((tool) => {
            const on = tools.includes(tool)
            return (
              <button
                key={tool}
                type="button"
                aria-pressed={on}
                onClick={() => {
                  setError(undefined)
                  onChange(on ? tools.filter((other) => other !== tool) : [...tools, tool])
                }}
                className={cn(
                  'min-h-control-compact rounded-full border px-3 text-label focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring',
                  on ? 'border-primary bg-primary-soft text-foreground' : 'border-border bg-card text-muted-foreground hover:bg-muted',
                )}
              >
                {t(`screen.product.tools.${tool}` as MessageKey)}
              </button>
            )
          })}
        </div>
        {error && (
          <p id="tools-error" role="alert" className="text-caption text-destructive">
            {error}
          </p>
        )}
      </fieldset>
      {shown && (
        <section aria-labelledby="tool-help-heading" className="flex flex-col gap-2 rounded-lg border border-border bg-card p-5">
          <h2 id="tool-help-heading" className="text-h3">
            {t('screen.product.wizard.helpHeading', { tool: t(`screen.product.tools.${shown}` as MessageKey) })}
          </h2>
          <p className="text-caption text-muted-foreground">{t('screen.product.wizard.helpLead')}</p>
          <p className="text-body-sm">{t(`screen.product.toolHelp.${shown}` as MessageKey)}</p>
          <a href={TOOL_DOCS[shown]} target="_blank" rel="noopener noreferrer" className="inline-flex w-fit items-center gap-1 text-body-sm text-primary underline-offset-4 hover:underline">
            {t('screen.product.wizard.helpLink', { tool: t(`screen.product.tools.${shown}` as MessageKey) })}
            <ExternalLink aria-hidden="true" className="size-3.5" />
            <span className="text-caption text-muted-foreground">{t('screen.product.domain', { domain: domainOf(TOOL_DOCS[shown]) })}</span>
            <span className="sr-only">{t('screen.product.newTab')}</span>
          </a>
        </section>
      )}
      <NextSteps text={t('screen.product.wizard.step1Next')} />
      <div className="flex justify-end">
        <Button type="submit">{t('screen.product.wizard.next')}</Button>
      </div>
    </form>
  )
}

function RepositoryStep({
  tool,
  url,
  checked,
  onUrl,
  onChecked,
  onGate,
  onBack,
  onNext,
}: {
  tool?: Tool
  url: string
  checked?: Checked
  onUrl: (url: string) => void
  onChecked: (checked: Checked) => void
  /** No link, or no kept token GitHub accepts: back to the GitHub gate (AD-10). */
  onGate: () => void
  onBack: () => void
  onNext: () => void
}) {
  const check = useRepositoryCheck()
  const [checkedUrl, setCheckedUrl] = useState<string>()

  function run(value: string) {
    const target = value.trim()
    if (!target || target === checkedUrl) {
      return
    }
    setCheckedUrl(target)
    check.mutate(target, {
      onSuccess: (repository) => onChecked({ kind: 'passed', repository }),
      onError: (error) => {
        const reason = error instanceof ApiError ? (error.problem.reason ?? '') : ''
        if (GATE_REASONS.includes(reason)) {
          onGate()
          return
        }
        onChecked({ kind: 'refused', reason, message: problemMessage(error, { module: 'product' }).text })
      },
    })
  }

  const refused = checked?.kind === 'refused' ? checked : undefined
  const fieldError = refused?.message
  return (
    <form
      noValidate
      className="flex flex-col gap-5"
      onSubmit={(event: FormEvent) => {
        event.preventDefault()
        if (checked?.kind === 'passed') {
          onNext()
        } else {
          run(url)
        }
      }}
    >
      <div className="flex flex-col gap-1">
        <PageHeading>{t('screen.product.wizard.step2Heading')}</PageHeading>
        <p className="text-body-sm text-muted-foreground">
          {t('screen.product.wizard.step2Lead')}
          {tool && ` (${t(`screen.product.tools.${tool}` as MessageKey)})`}
        </p>
      </div>
      <FormField id="repositoryUrl" label={t('screen.product.wizard.urlLabel')} helper={t('screen.product.wizard.urlHelper')} error={fieldError}>
        <div className="flex gap-2">
          <input
            {...fieldProps('repositoryUrl', fieldError, t('screen.product.wizard.urlHelper'))}
            type="url"
            inputMode="url"
            autoComplete="off"
            maxLength={300}
            className={cn(inputClass, 'grow')}
            value={url}
            onChange={(event) => onUrl(event.target.value)}
            onPaste={(event) => {
              const pasted = event.clipboardData.getData('text')
              if (pasted) {
                event.preventDefault()
                onUrl(pasted.trim())
                run(pasted)
              }
            }}
            onBlur={() => run(url)}
          />
          <Button type="button" variant="secondary" disabled={!url.trim() || check.isPending} onClick={() => run(url)}>
            {check.isPending ? t('screen.product.wizard.checking') : t('screen.product.wizard.check')}
          </Button>
        </div>
      </FormField>
      {checked && !(refused && refused.reason === 'NOT_GITHUB') && <Checklist checked={checked} />}
      {checked?.kind === 'passed' && checked.repository.forkParent && (
        <div role="status" className="flex gap-3 rounded-md border-l-4 border-primary bg-primary-soft p-4">
          <GitFork aria-hidden="true" className="size-5 shrink-0 text-primary" />
          <div className="flex flex-col gap-0.5">
            <p className="text-label font-semibold break-all">
              {t('screen.product.wizard.forkAlert', { parent: checked.repository.forkParent })}
            </p>
            <p className="text-body-sm">{t('screen.product.wizard.forkBody')}</p>
          </div>
        </div>
      )}
      <NextSteps text={t('screen.product.wizard.step2Next')} />
      <div className="flex flex-wrap items-center justify-between gap-3">
        <Button type="button" variant="secondary" onClick={onBack}>
          {t('screen.product.wizard.back')}
        </Button>
        <div className="flex flex-wrap items-center gap-3">
          {checked?.kind !== 'passed' && (
            <span id="continue-hint" className="text-caption text-muted-foreground">
              {t('screen.product.wizard.continueWhenPassed')}
            </span>
          )}
          <Button type="submit" disabled={checked?.kind !== 'passed'} aria-describedby={checked?.kind !== 'passed' ? 'continue-hint' : undefined}>
            {t('screen.product.wizard.next')}
          </Button>
        </div>
      </div>
    </form>
  )
}

/** The checks: passed ones, the refused one with how to fix it, and those not reached. */
function Checklist({ checked }: { checked: Checked }) {
  const failedAt = checked.kind === 'refused' ? CHECKS.findIndex((item) => item.reason === checked.reason) : -1
  if (checked.kind === 'refused' && failedAt < 0) {
    return null
  }
  return (
    <section aria-labelledby="checks-heading" className="flex flex-col gap-2 rounded-lg border border-border bg-card p-5">
      <h2 id="checks-heading" className="text-label font-semibold">
        {t('screen.product.wizard.resultHeading')}
      </h2>
      {checked.kind === 'passed' && <p className="text-body-sm font-semibold break-all">{checked.repository.fullName}</p>}
      <ul className="flex flex-col gap-1.5">
        {CHECKS.map((item, index) => {
          const passed = checked.kind === 'passed' || index < failedAt
          const failed = index === failedAt
          const label =
            item.reason === 'REPO_TOO_LARGE' && checked.kind === 'passed'
              ? t('screen.product.wizard.checkSizePassed', { size: megabytes(checked.repository.sizeKb) })
              : t(item.label)
          return (
            <li key={item.reason} className="flex items-start gap-2 text-body-sm">
              {passed ? (
                <Check aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-success" />
              ) : failed ? (
                <X aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-destructive" />
              ) : (
                <span aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-center text-muted-foreground">
                  ·
                </span>
              )}
              <span className={cn(!passed && !failed && 'text-muted-foreground', failed && 'font-semibold')}>
                {label}
                {!passed && !failed && ` · ${t('screen.product.wizard.notChecked')}`}
              </span>
            </li>
          )
        })}
      </ul>
      {checked.kind === 'refused' && (checked.reason === 'REPO_PRIVATE' || checked.reason === 'REPO_NOT_FOUND') && (
        <div className="flex gap-2 rounded-md bg-muted p-3 text-body-sm">
          <CircleAlert aria-hidden="true" className="size-4 shrink-0" />
          <p>
            {t('screen.product.wizard.makePublic')}{' '}
            <a href={MAKE_PUBLIC_DOCS} target="_blank" rel="noopener noreferrer" className="text-primary underline-offset-4 hover:underline">
              {t('screen.product.wizard.makePublicLink')}
              <span className="sr-only">{t('screen.product.newTab')}</span>
            </a>{' '}
            <span className="text-caption text-muted-foreground">{t('screen.product.domain', { domain: 'docs.github.com' })}</span>
          </p>
        </div>
      )}
    </section>
  )
}

function DetailsStep({
  values,
  onChange,
  onBack,
  onNext,
}: {
  values: Details
  onChange: (values: Details) => void
  onBack: () => void
  onNext: () => void
}) {
  const [errors, setErrors] = useState<Partial<Record<keyof Details, string>>>({})
  const labels: Record<keyof Details, string> = {
    name: t('screen.product.wizard.name'),
    description: t('screen.product.wizard.description'),
    demoUrl: t('screen.product.wizard.demo'),
  }
  const demoValid = /^https:\/\/\S+$/.test(values.demoUrl.trim())
  const listed = (Object.keys(labels) as (keyof Details)[])
    .filter((field) => errors[field])
    .map((field) => ({ id: field, label: labels[field], message: errors[field]! }))
  return (
    <form
      noValidate
      className="flex flex-col gap-5"
      onSubmit={(event) => {
        event.preventDefault()
        const result = details.safeParse(values)
        if (!result.success) {
          setErrors(zodFieldErrors(result.error))
          return
        }
        setErrors({})
        onNext()
      }}
    >
      <div className="flex flex-col gap-1">
        <PageHeading>{t('screen.product.wizard.step3Heading')}</PageHeading>
        <p className="text-body-sm text-muted-foreground">{t('screen.product.wizard.step3Lead')}</p>
      </div>
      {listed.length > 0 && <ErrorSummary errors={listed} />}
      <FormField id="name" label={labels.name} error={errors.name}>
        <input
          {...fieldProps('name', errors.name)}
          className={inputClass}
          maxLength={80}
          value={values.name}
          onChange={(event) => onChange({ ...values, name: event.target.value })}
        />
      </FormField>
      <FormField id="description" label={labels.description} helper={t('screen.product.wizard.descriptionHelper')} error={errors.description}>
        <textarea
          {...fieldProps('description', errors.description, t('screen.product.wizard.descriptionHelper'))}
          className={cn(inputClass, 'min-h-28')}
          maxLength={1000}
          value={values.description}
          onChange={(event) => onChange({ ...values, description: event.target.value })}
        />
      </FormField>
      <FormField id="demoUrl" label={labels.demoUrl} helper={t('screen.product.wizard.demoHelper')} error={errors.demoUrl}>
        <input
          {...fieldProps('demoUrl', errors.demoUrl, t('screen.product.wizard.demoHelper'))}
          type="url"
          inputMode="url"
          maxLength={300}
          className={inputClass}
          value={values.demoUrl}
          onChange={(event) => onChange({ ...values, demoUrl: event.target.value })}
          onBlur={() =>
            setErrors((current) => {
              const result = details.shape.demoUrl.safeParse(values.demoUrl)
              const next = { ...current }
              if (result.success) {
                delete next.demoUrl
              } else {
                next.demoUrl = fieldMessage(result.error.issues[0]?.message ?? 'format')
              }
              return next
            })
          }
        />
      </FormField>
      {demoValid && (
        <p role="note" className="rounded-md bg-warning-soft p-3 text-body-sm">
          {t('screen.product.wizard.demoWarning', { domain: domainOf(values.demoUrl.trim()) })}
        </p>
      )}
      <div className="flex justify-between gap-3">
        <Button type="button" variant="secondary" onClick={onBack}>
          {t('screen.product.wizard.back')}
        </Button>
        <Button type="submit">{t('screen.product.wizard.next')}</Button>
      </div>
    </form>
  )
}

function NoticeStep({
  registration,
  onBack,
  onRefused,
  onGate,
}: {
  registration: { url: string; tools: Tool[]; values: Details }
  onBack: () => void
  onRefused: () => void
  onGate: () => void
}) {
  const [ticked, setTicked] = useState({ public: false, copy: false, detection: false })
  const [error, setError] = useState<string>()
  const register = useRegisterProduct()
  const navigate = useNavigate()
  const toasts = useToasts()
  const all = ticked.public && ticked.copy && ticked.detection

  function submit(event: FormEvent) {
    event.preventDefault()
    if (!all) {
      return
    }
    const { values } = registration
    register.mutate(
      {
        repositoryUrl: registration.url.trim(),
        tools: registration.tools,
        name: values.name.trim(),
        description: values.description.trim(),
        demoUrl: values.demoUrl.trim() || undefined,
        publicNoticeVersion: NOTICE_VERSION,
        copyPermissionVersion: NOTICE_VERSION,
        detectionNoticeVersion: NOTICE_VERSION,
      },
      {
        onSuccess: (created) => {
          toasts.success(t('screen.product.wizard.registered', { name: values.name.trim() }))
          navigate(`/san-pham/${created.id}`)
        },
        onError: (failure) => {
          const reason = failure instanceof ApiError ? failure.problem.reason : undefined
          const message = problemMessage(failure, { module: 'product' }).text
          if (reason && GATE_REASONS.includes(reason)) {
            onGate()
            return
          }
          if (reason && CHECKS.some((item) => item.reason === reason)) {
            toasts.error(message)
            onRefused()
            return
          }
          setError(message)
        },
      },
    )
  }

  const items: { key: keyof typeof ticked; text: MessageKey }[] = [
    { key: 'public', text: 'notice.product.public' },
    { key: 'copy', text: 'notice.product.copy' },
    { key: 'detection', text: 'notice.product.detection' },
  ]
  return (
    <form noValidate className="flex flex-col gap-5" onSubmit={submit}>
      <div className="flex flex-col gap-1">
        <PageHeading>{t('screen.product.wizard.step4Heading')}</PageHeading>
        <p className="text-body-sm text-muted-foreground">{t('screen.product.wizard.step4Lead')}</p>
        <p className="text-caption text-muted-foreground">{t('notice.product.versionLine', { version: NOTICE_VERSION })}</p>
      </div>
      <div className="flex flex-col gap-3">
        {items.map((item) => (
          <label key={item.key} className="flex cursor-pointer items-start gap-3 rounded-lg border border-border bg-card p-4">
            <input
              type="checkbox"
              checked={ticked[item.key]}
              onChange={(event) => setTicked({ ...ticked, [item.key]: event.target.checked })}
              className="mt-0.5 size-5 shrink-0 accent-primary"
            />
            <span className="text-body-sm">{t(item.text)}</span>
          </label>
        ))}
      </div>
      <section aria-labelledby="after-heading" className="flex flex-col gap-1 rounded-md bg-muted p-4">
        <h2 id="after-heading" className="text-label font-semibold">
          {t('screen.product.wizard.afterHeading')}
        </h2>
        <p className="text-body-sm">{t('screen.product.wizard.afterBody')}</p>
      </section>
      {error && (
        <p role="alert" className="text-body-sm text-destructive">
          {error}
        </p>
      )}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <Button type="button" variant="secondary" onClick={onBack}>
          {t('screen.product.wizard.back')}
        </Button>
        <div className="flex flex-wrap items-center gap-3">
          {!all && (
            <span id="tick-hint" className="text-caption text-muted-foreground">
              {t('screen.product.wizard.tickAll')}
            </span>
          )}
          <Button type="submit" disabled={!all || register.isPending} aria-describedby={!all ? 'tick-hint' : undefined}>
            {register.isPending ? t('screen.product.wizard.submitting') : t('screen.product.wizard.submit')}
          </Button>
        </div>
      </div>
    </form>
  )
}

function NextSteps({ text }: { text: string }) {
  return (
    <section className="flex flex-col gap-1 rounded-md bg-muted p-4">
      <h2 className="text-label font-semibold">{t('screen.product.wizard.nextSteps')}</h2>
      <p className="text-body-sm">{text}</p>
    </section>
  )
}
