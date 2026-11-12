import { useQueryClient } from '@tanstack/react-query'
import { Check, CircleAlert, CircleCheck, Copy, Download, ExternalLink, GitFork, TriangleAlert, X } from 'lucide-react'
import { useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react'
import { useNavigate, useSearchParams } from 'react-router'
import { z } from 'zod'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { ErrorSummary } from '@/components/ErrorSummary'
import { FormField } from '@/components/FormField'
import { fieldProps, inputClass } from '@/components/formField-props'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { TermHint, TermScope } from '@/components/TermHint'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { buttonVariants } from '@/components/ui/button-variants'
import { GitHubGate } from '@/features/identity/GitHubGate'
import { GITHUB_LINK_QUERY_KEY } from '@/features/identity/githubApi'
import { rich } from '@/i18n/rich'
import { t, type MessageKey } from '@/i18n/t'
import vi from '@/i18n/vi.json'
import { ApiError } from '@/lib/apiClient'
import { fieldMessage, zodFieldErrors } from '@/lib/formErrors'
import { problemMessage } from '@/lib/problemMessage'
import { redirectTo } from '@/lib/redirect'
import { cn } from '@/lib/utils'
import {
  useRegisterProduct,
  useRepositoryCheck,
  useSourceAppInstallLink,
  useWorkflowCheck,
  useWorkflowFile,
  type RepositoryCheck,
  type Tool,
  type WorkflowFile,
} from './productApi'
import {
  SECRET_PROVIDERS,
  TOOL_DOCS,
  TOOLS,
  domainOf,
  megabytes,
  newWorkflowUrl,
  workflowActionsUrl,
} from './productText'

const NOTICE_VERSION = (vi as { notice: { product: { version: string } } }).notice.product.version

/** The wizard's five steps (EXPERIENCE.md CUS-3, rewritten 2026-11-12). */
const STEPS = 5

/** CUS-3 step 2's checklist, in the server's order; each item names the reasons that fail it. */
const CHECKS: { label: MessageKey; reasons: string[] }[] = [
  { label: 'screen.product.wizard.checkSourceApp', reasons: ['SOURCE_APP_MISSING', 'SOURCE_APP_WRONG_REPOSITORY'] },
  { label: 'screen.product.wizard.checkFound', reasons: ['REPO_NOT_FOUND'] },
  { label: 'screen.product.wizard.checkAdmin', reasons: ['NO_ADMIN_PERMISSION'] },
  { label: 'screen.product.wizard.checkNotLinked', reasons: ['REPO_ALREADY_LINKED'] },
  { label: 'screen.product.wizard.checkContent', reasons: ['REPO_UNSUPPORTED_CONTENT'] },
  { label: 'screen.product.wizard.checkSize', reasons: ['REPO_TOO_LARGE'] },
]

/** The workflow file's refusals, which send the Customer back to step 3. */
const WORKFLOW_REASONS = ['WORKFLOW_MISSING', 'WORKFLOW_MISMATCH']

const details = z.object({
  name: z.string().trim().min(1, 'required').max(80, 'tooLong'),
  description: z.string().trim().min(1, 'required').max(1000, 'tooLong'),
  demoUrl: z.string().trim().max(300, 'tooLong').regex(/^(?:https:\/\/\S+)?$/, 'format'),
})

type Details = z.input<typeof details>

/** The outcome of the last repository check: passed, or refused with a reason. */
type Checked = { kind: 'passed'; repository: RepositoryCheck } | { kind: 'refused'; reason: string; message: string }

/** Where the GitHub gate and the Source App's Setup URL return, and where the wizard's input waits meanwhile. */
const ROUTE = '/san-pham/moi'

/** The Setup URL's marker on the return from GitHub (`ung-dung=…`): installed, waiting for an owner, or nothing seen. */
const RETURNED = 'ung-dung'

type Returned = 'da-cai' | 'cho-duyet' | 'chua-thay'

/** The answers that send the wizard back to the GitHub gate: no link, or no kept token that GitHub accepts. */
const GATE_REASONS = ['RELINK_GITHUB', 'GITHUB_NOT_LINKED']

const DRAFT_KEY = 'vibefix.product.registration'

/** What the wizard holds while the browser is on GitHub; the repository is checked again on return. */
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

/** The pasted address's `owner/name`, as the copy names it; empty for an address that is not GitHub's. */
function repositoryOf(url: string): { owner: string; name: string } | undefined {
  const match = /github\.com\/([A-Za-z0-9-]+)\/([A-Za-z0-9._-]+?)(?:\.git)?(?:[/?#]|$)/i.exec(url.trim())
  return match ? { owner: match[1], name: match[2] } : undefined
}

/**
 * CUS-3 (`/san-pham/moi`, FR-7, AD-13, AD-45, UX-DR113), a focused task route in five steps: the AI tool and how
 * to get its code onto GitHub; the repository, public or private, with the Source App installed on exactly it
 * and the live checks through it; the published workflow file (the Workflow setup card) with N-2's secret
 * guidance; the name, description, and demo link; and N-1, N-1b, and N-2's checkbox, after which the Product
 * is registered and CUS-4 opens. The GitHub gate (UX-DR95, FR-80) replaces step 2's form until GitHub is linked.
 * The input waits in a `sessionStorage` draft while the browser is on GitHub, and the wizard comes back at
 * step 2, where the Setup URL's marker says what GitHub did and the check runs again.
 */
export function RegisterProductPage() {
  usePageTitle(t('screen.product.wizard.title'))
  return <Wizard />
}

function Wizard() {
  const [draft] = useState(readDraft)
  const [params, setParams] = useSearchParams()
  // The Setup URL's marker is read once: it stays until the next check, and a reload shows none.
  const [returned] = useState(() => {
    const marker = params.get(RETURNED)
    return marker === 'da-cai' || marker === 'cho-duyet' || marker === 'chua-thay' ? (marker as Returned) : undefined
  })
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
  useEffect(() => {
    if (params.has(RETURNED)) {
      setParams({}, { replace: true })
    }
  }, [params, setParams])
  const toGate = () => {
    void queryClient.invalidateQueries({ queryKey: GITHUB_LINK_QUERY_KEY })
    setRelink(true)
    setChecked(undefined)
    setStep(2)
  }
  const leave = () => writeDraft({ step: 2, tools, url, values })
  return (
    <TermScope>
      <div className="mx-auto flex max-w-[760px] flex-col gap-6 px-gutter-mobile py-section-gap md:px-gutter-desktop">
        <div className="flex flex-col gap-2">
          <p className="text-label text-muted-foreground">{t('screen.product.wizard.step', { n: step })}</p>
          <div
            role="progressbar"
            aria-label={t('screen.product.wizard.progress')}
            aria-valuemin={1}
            aria-valuemax={STEPS}
            aria-valuenow={step}
            className="h-1.5 w-full overflow-hidden rounded-full bg-muted"
          >
            <div className="h-full rounded-full bg-primary" style={{ width: `${(step * 100) / STEPS}%` }} />
          </div>
        </div>
        {step === 1 && <ToolStep tools={tools} onChange={setTools} onNext={() => setStep(2)} />}
        {step === 2 && (
          <GitHubGate
            action={t('screen.githubGate.action.registerProduct')}
            returnTo={ROUTE}
            needsRepositoryToken
            forceRelink={relink}
            onLeave={leave}
            onBack={() => setStep(1)}
          >
            <RepositoryStep
              tool={tools[0]}
              url={url}
              checked={checked}
              returned={returned}
              autoCheck={draft?.step === 2 && draft.url.trim() !== ''}
              onUrl={(next) => {
                setUrl(next)
                setChecked(undefined)
              }}
              onChecked={setChecked}
              onGate={toGate}
              onLeave={leave}
              onBack={() => setStep(1)}
              onNext={() => setStep(3)}
            />
          </GitHubGate>
        )}
        {step === 3 && checked?.kind === 'passed' && (
          <WorkflowStep url={url} repository={checked.repository} onGate={toGate} onBack={() => setStep(2)} onNext={() => setStep(4)} />
        )}
        {step === 4 && <DetailsStep values={values} onChange={setValues} onBack={() => setStep(3)} onNext={() => setStep(5)} />}
        {step === 5 && (
          <NoticeStep
            registration={{ url, tools, values }}
            onBack={() => setStep(4)}
            onRefused={(reason) => {
              if (WORKFLOW_REASONS.includes(reason)) {
                setStep(3)
                return
              }
              setChecked(undefined)
              setStep(2)
            }}
            onGate={toGate}
          />
        )}
      </div>
    </TermScope>
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
          <OutLink href={TOOL_DOCS[shown]}>
            {t('screen.product.wizard.helpLink', { tool: t(`screen.product.tools.${shown}` as MessageKey) })}
          </OutLink>
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
  returned,
  autoCheck,
  onUrl,
  onChecked,
  onGate,
  onLeave,
  onBack,
  onNext,
}: {
  tool?: Tool
  url: string
  checked?: Checked
  /** What GitHub did, from the Setup URL's marker on the return. */
  returned?: Returned
  /** The wizard came back from GitHub with this address: the check runs again at once. */
  autoCheck: boolean
  onUrl: (url: string) => void
  onChecked: (checked: Checked) => void
  /** No link, or no kept token GitHub accepts: back to the GitHub gate (AD-10). */
  onGate: () => void
  /** The browser goes to GitHub: the input waits in the draft. */
  onLeave: () => void
  onBack: () => void
  onNext: () => void
}) {
  const check = useRepositoryCheck()
  const link = useSourceAppInstallLink()
  const toasts = useToasts()
  const [checkedUrl, setCheckedUrl] = useState<string>()
  const [linkError, setLinkError] = useState<string>()
  // The Setup URL's marker holds until the next check says what is true now.
  const [marker, setMarker] = useState(returned)
  const autoRan = useRef(false)

  /** Runs the checks; a paste or a blur only for a new address, "Kiểm tra" always. */
  function run(value: string, force: boolean) {
    const target = value.trim()
    if (!target || check.isPending || (!force && target === checkedUrl)) {
      return
    }
    check.mutate(target, {
      onSuccess: (repository) => {
        setCheckedUrl(target)
        setMarker(undefined)
        onChecked({ kind: 'passed', repository })
      },
      onError: (error) => {
        setCheckedUrl(target)
        const reason = error instanceof ApiError ? (error.problem.reason ?? '') : ''
        if (GATE_REASONS.includes(reason)) {
          onGate()
          return
        }
        onChecked({ kind: 'refused', reason, message: problemMessage(error, { module: 'product' }).text })
      },
    })
  }

  useEffect(() => {
    if (autoCheck && !autoRan.current) {
      autoRan.current = true
      run(url, true)
    }
  })

  function install() {
    const target = url.trim()
    if (!target) {
      return
    }
    setLinkError(undefined)
    link.mutate(target, {
      onSuccess: (next) => {
        onLeave()
        redirectTo(next.url)
      },
      onError: (error) => setLinkError(problemMessage(error, { module: 'product' }).text),
    })
  }

  const refused = checked?.kind === 'refused' ? checked : undefined
  const fieldError = refused?.message
  const address = repositoryOf(url)
  const repo = address ? `${address.owner}/${address.name}` : url.trim()
  const org = address?.owner ?? ''
  const appRefused = refused !== undefined && CHECKS[0].reasons.includes(refused.reason)
  const showInstall = checked?.kind !== 'passed' && (refused === undefined || appRefused) && marker !== 'cho-duyet'
  const message = t('screen.product.wizard.message', { repo, org })
  return (
    <form
      noValidate
      className="flex flex-col gap-5"
      onSubmit={(event: FormEvent) => {
        event.preventDefault()
        if (checked?.kind === 'passed') {
          onNext()
        } else {
          run(url, true)
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
                run(pasted, false)
              }
            }}
            onBlur={() => run(url, false)}
          />
          <Button type="button" variant="secondary" disabled={!url.trim() || check.isPending} onClick={() => run(url, true)}>
            {check.isPending ? t('screen.product.wizard.checking') : t('screen.product.wizard.check')}
          </Button>
        </div>
      </FormField>
      {marker === 'cho-duyet' && checked?.kind !== 'passed' && (
        <section aria-labelledby="waiting-heading" className="flex flex-col gap-2 rounded-md border border-border bg-card p-4">
          <p id="waiting-heading" className="w-fit rounded-full bg-warning-soft px-2 py-0.5 text-caption font-semibold">
            {t('screen.product.wizard.waitingBadge')}
          </p>
          <p className="text-body-sm">{t('screen.product.wizard.waiting', { org, repo })}</p>
          <div className="flex flex-col gap-2 rounded-md bg-muted p-3">
            <p className="text-caption font-semibold">{t('screen.product.wizard.messageTitle')}</p>
            <p className="text-body-sm break-words">{message}</p>
            <Button
              type="button"
              variant="secondary"
              size="compact"
              className="w-fit"
              onClick={() => {
                void navigator.clipboard
                  .writeText(message)
                  .then(() => toasts.success(t('screen.product.wizard.copied')))
                  .catch(() => toasts.error(t('screen.product.wizard.copyFailed')))
              }}
            >
              {t('screen.product.wizard.copyMessage')}
            </Button>
          </div>
        </section>
      )}
      {marker === 'chua-thay' && checked?.kind !== 'passed' && (
        <p role="status" className="rounded-md bg-muted p-3 text-body-sm">
          {t('screen.product.wizard.notSeen', { org, repo })}
        </p>
      )}
      {checked && !(refused && refused.reason === 'NOT_GITHUB') && <Checklist checked={checked} />}
      {showInstall && url.trim() !== '' && (
        <section aria-labelledby="install-heading" className="flex flex-col gap-2 rounded-lg border-l-4 border-primary bg-card p-5">
          <h2 id="install-heading" className="text-h3">
            {rich('screen.product.wizard.installHeading', { app: <TermHint term="ungDungVibefix" /> })}
          </h2>
          {refused?.reason === 'SOURCE_APP_MISSING' && (
            <p className="text-body-sm">{t('screen.product.wizard.appMissing', { repo })}</p>
          )}
          <p className="text-caption text-muted-foreground">{t('screen.product.wizard.installHint')}</p>
          {linkError && (
            <p role="alert" className="text-body-sm text-destructive">
              {linkError}
            </p>
          )}
          <Button type="button" className="w-fit gap-1.5" disabled={link.isPending} onClick={install}>
            <ExternalLink aria-hidden="true" className="size-4" />
            {link.isPending
              ? t('screen.product.wizard.opening')
              : t(
                  refused?.reason === 'SOURCE_APP_WRONG_REPOSITORY'
                    ? 'screen.product.wizard.reselect'
                    : marker === 'chua-thay'
                      ? 'screen.product.wizard.installAgain'
                      : 'screen.product.wizard.install',
                )}
            <span className="text-caption">{t('screen.product.domain', { domain: 'github.com' })}</span>
          </Button>
        </section>
      )}
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
      <StepNav
        onBack={onBack}
        ready={checked?.kind === 'passed'}
        hint={t('screen.product.wizard.continueWhenPassed')}
      />
    </form>
  )
}

/** The checks: passed ones, the refused one with how to fix it, and those not reached. */
function Checklist({ checked }: { checked: Checked }) {
  const failedAt = checked.kind === 'refused' ? CHECKS.findIndex((item) => item.reasons.includes(checked.reason)) : -1
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
            item.reasons.includes('REPO_TOO_LARGE') && checked.kind === 'passed'
              ? t('screen.product.wizard.checkSizePassed', { size: megabytes(checked.repository.sizeKb) })
              : t(item.label)
          return (
            <li key={item.label} className="flex items-start gap-2 text-body-sm">
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
    </section>
  )
}

/** "Kiểm tra"'s outcome on the Workflow setup card. */
type WorkflowOutcome = { kind: 'MATCHES' } | { kind: 'REFUSED'; reason: string; message: string; lineEndings: boolean }

/**
 * CUS-3 step 3, the Workflow setup card (AD-45, UX-DR113): "Thêm file workflow ↗" opens GitHub's new-file page in
 * the repository, prefilled with `.github/workflows/vibefix.yml` and the published content; "Kiểm tra" reads the
 * default branch's file through the Source App and shows one outcome; the copy and the download are the fallback;
 * and N-2's guidance, with the optional gitleaks run on the repository's Actions tab, sits below it.
 */
function WorkflowStep({
  url,
  repository,
  onGate,
  onBack,
  onNext,
}: {
  url: string
  repository: RepositoryCheck
  onGate: () => void
  onBack: () => void
  onNext: () => void
}) {
  const file = useWorkflowFile()
  const check = useWorkflowCheck()
  const [outcome, setOutcome] = useState<WorkflowOutcome>()

  function run() {
    check.mutate(url.trim(), {
      onSuccess: () => setOutcome({ kind: 'MATCHES' }),
      onError: (error) => {
        const problem = error instanceof ApiError ? error.problem : undefined
        const reason = problem?.reason ?? ''
        if (GATE_REASONS.includes(reason)) {
          onGate()
          return
        }
        setOutcome({
          kind: 'REFUSED',
          reason,
          message: problemMessage(error, { module: 'product' }).text,
          lineEndings: problem?.lineEndings === true,
        })
      },
    })
  }

  const repo = repository.fullName
  return (
    <form
      noValidate
      className="flex flex-col gap-5"
      onSubmit={(event: FormEvent) => {
        event.preventDefault()
        if (outcome?.kind === 'MATCHES') {
          onNext()
        }
      }}
    >
      <div className="flex flex-col gap-1">
        <PageHeading>{t('screen.product.wizard.workflowHeading')}</PageHeading>
        <p className="text-body-sm text-muted-foreground">{t('screen.product.wizard.workflowLead', { repo })}</p>
      </div>
      <RegionState query={file} skeleton={<Skeleton className="h-40 w-full" />}>
        {file.data && (
          <section aria-labelledby="workflow-card" className="flex flex-col gap-3 rounded-lg border border-border bg-card p-5">
            <h2 id="workflow-card" className="text-h3">
              {rich('screen.product.wizard.workflowCardHeading', { file: <TermHint term="fileWorkflow" /> })}
            </h2>
            <a
              href={newWorkflowUrl(repository.htmlUrl, repository.defaultBranch, file.data.path, file.data.content)}
              target="_blank"
              rel="noopener noreferrer"
              className={cn(buttonVariants(), 'w-fit gap-1.5')}
            >
              <ExternalLink aria-hidden="true" className="size-4" />
              {t('screen.product.wizard.addWorkflow')}
              <span className="text-caption">{t('screen.product.domain', { domain: 'github.com' })}</span>
              <span className="sr-only">{t('screen.product.newTab')}</span>
            </a>
            <p className="text-caption text-muted-foreground">
              {t('screen.product.wizard.addWorkflowHint', { path: file.data.path })}
            </p>
            <div className="flex flex-wrap items-center gap-3">
              <Button type="button" variant="secondary" disabled={check.isPending} onClick={run}>
                {check.isPending ? t('screen.product.wizard.checking') : t('screen.product.wizard.check')}
              </Button>
              {!outcome && <span className="text-caption text-muted-foreground">{t('screen.product.wizard.workflowNotChecked')}</span>}
            </div>
            {outcome && <WorkflowResult outcome={outcome} repo={repo} />}
            <Fallback file={file.data} repository={repository} />
          </section>
        )}
      </RegionState>
      <Secrets repository={repository} />
      <NextSteps text={t('screen.product.wizard.step3Next')} />
      <StepNav onBack={onBack} ready={outcome?.kind === 'MATCHES'} hint={t('screen.product.wizard.continueWhenMatches')} />
    </form>
  )
}

function WorkflowResult({ outcome, repo }: { outcome: WorkflowOutcome; repo: string }) {
  if (outcome.kind === 'MATCHES') {
    return (
      <p role="status" className="flex items-center gap-2 text-body-sm font-semibold text-success">
        <CircleCheck aria-hidden="true" className="size-4 shrink-0" />
        {t('screen.product.wizard.workflowMatches')}
      </p>
    )
  }
  const title =
    outcome.reason === 'WORKFLOW_MISSING'
      ? t('screen.product.wizard.workflowMissing', { repo })
      : outcome.reason === 'WORKFLOW_MISMATCH'
        ? t('screen.product.wizard.workflowMismatch')
        : undefined
  return (
    <div role="alert" className="flex gap-3 rounded-md border-l-4 border-destructive bg-destructive-soft p-4">
      <CircleAlert aria-hidden="true" className="size-5 shrink-0 text-destructive" />
      <div className="flex min-w-0 flex-col gap-1">
        {title && <p className="text-label font-semibold break-words">{title}</p>}
        <p className="text-body-sm break-words">{outcome.message}</p>
        {outcome.lineEndings && (
          <p className="flex gap-2 text-body-sm">
            <TriangleAlert aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
            {t('screen.product.wizard.workflowLineEndings')}
          </p>
        )}
      </div>
    </div>
  )
}

/** For when GitHub's prefilled page does not open: the content to copy or download, and where it goes. */
function Fallback({ file, repository }: { file: WorkflowFile; repository: RepositoryCheck }) {
  const toasts = useToasts()
  return (
    <div className="flex flex-col gap-2 rounded-md bg-muted p-3">
      <p className="text-caption font-semibold">{t('screen.product.wizard.fallbackHeading')}</p>
      <p className="text-body-sm break-words">
        {t('screen.product.wizard.fallbackBody', {
          path: file.path,
          branch: repository.defaultBranch,
          repo: repository.fullName,
        })}
      </p>
      <div className="flex flex-wrap gap-2">
        <Button
          type="button"
          variant="secondary"
          size="compact"
          className="gap-1.5"
          onClick={() => {
            void navigator.clipboard
              .writeText(file.content)
              .then(() => toasts.success(t('screen.product.wizard.contentCopied')))
              .catch(() => toasts.error(t('screen.product.wizard.copyFailed')))
          }}
        >
          <Copy aria-hidden="true" className="size-4" />
          {t('screen.product.wizard.copyContent')}
        </Button>
        <a
          href={`data:text/yaml;charset=utf-8,${encodeURIComponent(file.content)}`}
          download="vibefix.yml"
          className={cn(buttonVariants({ variant: 'secondary', size: 'compact' }), 'gap-1.5')}
        >
          <Download aria-hidden="true" className="size-4" />
          {t('screen.product.wizard.download')}
        </a>
      </div>
    </div>
  )
}

/** N-2's guidance (FR-8, D8): what to check by hand, each credential's page, and the optional gitleaks run. */
function Secrets({ repository }: { repository: RepositoryCheck }) {
  return (
    <section aria-labelledby="secrets-heading" className="flex flex-col gap-2 rounded-lg border border-border bg-card p-5">
      <h2 id="secrets-heading" className="text-h3">
        {t('screen.product.wizard.secretsHeading')}
      </h2>
      <p className="text-body-sm">{t('notice.product.guidance')}</p>
      <p className="text-label">{t('screen.product.wizard.secretsItemsHeading')}</p>
      <ul className="flex flex-col gap-1.5">
        {SECRET_PROVIDERS.map((item) => (
          <li key={item.key} className="flex flex-wrap items-center gap-x-2 text-body-sm">
            <span>{t(`screen.product.wizard.secret.${item.key}` as MessageKey)}</span>
            <OutLink href={item.url}>{t('screen.product.wizard.secretLink', { provider: item.provider })}</OutLink>
          </li>
        ))}
      </ul>
      <p className="text-label">
        {rich('screen.product.wizard.selfScanTerm', { term: <TermHint term="tuKiemTraKhoaBiMat" /> })}
      </p>
      <OutLink href={workflowActionsUrl(repository.htmlUrl)}>{t('screen.product.wizard.selfScan')}</OutLink>
      <p className="text-caption text-muted-foreground">{t('screen.product.wizard.selfScanHint')}</p>
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
        <PageHeading>{t('screen.product.wizard.detailsHeading')}</PageHeading>
        <p className="text-body-sm text-muted-foreground">{t('screen.product.wizard.detailsLead')}</p>
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
  /** A check refused again at registration: back to the step that fixes it. */
  onRefused: (reason: string) => void
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
          if (reason && (CHECKS.some((item) => item.reasons.includes(reason)) || WORKFLOW_REASONS.includes(reason))) {
            toasts.error(message)
            onRefused(reason)
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
        <PageHeading>{t('screen.product.wizard.noticesHeading')}</PageHeading>
        <p className="text-body-sm text-muted-foreground">{t('screen.product.wizard.noticesLead')}</p>
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

/** Back, and "Tiếp tục" once the step's checks pass, with why it waits. */
function StepNav({ onBack, ready, hint }: { onBack: () => void; ready: boolean; hint: string }) {
  return (
    <div className="flex flex-wrap items-center justify-between gap-3">
      <Button type="button" variant="secondary" onClick={onBack}>
        {t('screen.product.wizard.back')}
      </Button>
      <div className="flex flex-wrap items-center gap-3">
        {!ready && (
          <span id="continue-hint" className="text-caption text-muted-foreground">
            {hint}
          </span>
        )}
        <Button type="submit" disabled={!ready} aria-describedby={!ready ? 'continue-hint' : undefined}>
          {t('screen.product.wizard.next')}
        </Button>
      </div>
    </div>
  )
}

/** An outside link: a new tab without the opener, with its domain beside it (AD-35). */
function OutLink({ href, children }: { href: string; children: ReactNode }) {
  return (
    <a
      href={href}
      target="_blank"
      rel="noopener noreferrer"
      className="inline-flex w-fit flex-wrap items-center gap-1 text-body-sm text-primary underline-offset-4 hover:underline"
    >
      {children}
      <ExternalLink aria-hidden="true" className="size-3.5" />
      <span className="text-caption text-muted-foreground">{t('screen.product.domain', { domain: domainOf(href) })}</span>
      <span className="sr-only">{t('screen.product.newTab')}</span>
    </a>
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
