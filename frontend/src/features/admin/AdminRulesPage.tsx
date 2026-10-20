import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useId, useState, type FormEvent } from 'react'
import type { components } from '@/api/schema'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { inputClass } from '@/components/formField-props'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { t, type MessageKey } from '@/i18n/t'
import { apiClient, unwrap } from '@/lib/apiClient'
import { formatDayMonthTime } from '@/lib/dates'
import { cn } from '@/lib/utils'
import { StepUpDialog } from './StepUpDialog'

type Rules = components['schemas']['RulesView']
type RuleKey = components['schemas']['RuleView']['key']

const RULES_QUERY_KEY = ['admin', 'rules'] as const

/**
 * ADM-10 (`/quan-tri/quy-tac`, FR-61, SG-10, UX-DR42): each rule value within its bounds and the school
 * allowlist, one domain per line; saving asks for the authenticator code. Only Jobs published after a
 * change take it; the latest changes are listed with their old and new values.
 */
export function AdminRulesPage() {
  usePageTitle(t('screen.admin.rules.title'))
  const rules = useQuery({
    queryKey: RULES_QUERY_KEY,
    queryFn: () => unwrap(apiClient.GET('/api/v1/admin/rules')),
    retry: false,
  })
  return (
    <div className="flex flex-col gap-6 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      <PageHeading>{t('screen.admin.rules.heading')}</PageHeading>
      <p className="max-w-prose text-body-sm text-muted-foreground">{t('screen.admin.rules.lead')}</p>
      <RegionState query={rules} skeleton={<Skeleton className="h-96 w-full" />}>
        {rules.data && <RulesForm key={rules.dataUpdatedAt} rules={rules.data} />}
      </RegionState>
    </div>
  )
}

function RulesForm({ rules }: { rules: Rules }) {
  const ids = useId()
  const toasts = useToasts()
  const queryClient = useQueryClient()
  const [values, setValues] = useState<Record<string, string>>(
    Object.fromEntries(rules.rules.map((rule) => [rule.key, String(rule.value)])),
  )
  const [domains, setDomains] = useState(rules.schoolDomains.join('\n'))
  const [stepUp, setStepUp] = useState(false)
  const save = useMutation({
    mutationFn: (code: string) =>
      unwrap(
        apiClient.PUT('/api/v1/admin/rules', {
          body: {
            values: Object.fromEntries(rules.rules.map((rule) => [rule.key, Number(values[rule.key])])) as Record<RuleKey, number>,
            schoolDomains: domains
              .split('\n')
              .map((line) => line.trim())
              .filter((line) => line !== ''),
            stepUpCode: code,
          },
        }),
      ),
    onSuccess: (saved) => {
      queryClient.setQueryData(RULES_QUERY_KEY, saved)
      setStepUp(false)
      toasts.success(t('screen.admin.rules.saved'))
    },
  })
  const invalid = rules.rules.filter((rule) => {
    const value = Number(values[rule.key])
    return !Number.isInteger(value) || value < rule.min || value > rule.max
  })

  function submit(event: FormEvent) {
    event.preventDefault()
    if (invalid.length === 0) {
      setStepUp(true)
    }
  }

  return (
    <>
      <form onSubmit={submit} className="flex flex-col gap-6">
      <section aria-labelledby={`${ids}-values`} className="flex flex-col gap-3 rounded-lg border border-border bg-card p-5">
        <h2 id={`${ids}-values`} className="text-h2">
          {t('screen.admin.rules.values')}
        </h2>
        <div className="grid gap-x-6 gap-y-3 md:grid-cols-2">
          {rules.rules.map((rule) => {
            const wrong = invalid.some((bad) => bad.key === rule.key)
            return (
              <div key={rule.key} className="flex flex-col gap-1">
                <label htmlFor={`${ids}-${rule.key}`} className="text-label">
                  {t(`screen.admin.rules.rule.${rule.key}` as MessageKey)}
                </label>
                <input
                  id={`${ids}-${rule.key}`}
                  type="number"
                  inputMode="numeric"
                  min={rule.min}
                  max={rule.max}
                  value={values[rule.key]}
                  aria-invalid={wrong || undefined}
                  aria-describedby={`${ids}-${rule.key}-bounds`}
                  onChange={(event) => setValues({ ...values, [rule.key]: event.target.value })}
                  className={cn(inputClass, wrong && 'border-destructive')}
                />
                <span id={`${ids}-${rule.key}-bounds`} className={cn('text-caption', wrong ? 'text-destructive' : 'text-muted-foreground')}>
                  {t('screen.admin.rules.bounds', { min: rule.min, max: rule.max })}
                </span>
              </div>
            )
          })}
        </div>
      </section>
      <section aria-labelledby={`${ids}-domains`} className="flex flex-col gap-2 rounded-lg border border-border bg-card p-5">
        <h2 id={`${ids}-domains`} className="text-h2">
          {t('screen.admin.rules.domains')}
        </h2>
        <label htmlFor={`${ids}-domain-list`} className="text-label">
          {t('screen.admin.rules.domainList')}
        </label>
        <textarea
          id={`${ids}-domain-list`}
          value={domains}
          onChange={(event) => setDomains(event.target.value)}
          className={cn(inputClass, 'min-h-32 font-mono')}
        />
        <p className="text-caption text-muted-foreground">{t('screen.admin.rules.domainNote')}</p>
      </section>
      <div className="flex flex-wrap items-center justify-end gap-3">
        <p className="text-caption text-muted-foreground">{t('screen.admin.rules.appliesLater')}</p>
        <Button type="submit" disabled={invalid.length > 0}>
          {t('screen.admin.rules.save')}
        </Button>
      </div>
      <section aria-labelledby={`${ids}-history`} className="flex flex-col gap-2 rounded-lg border border-border bg-card p-5 text-body-sm">
        <h2 id={`${ids}-history`} className="text-h2">
          {t('screen.admin.rules.history')}
        </h2>
        {rules.history.length === 0 ? (
          <p className="text-muted-foreground">{t('screen.admin.rules.noHistory')}</p>
        ) : (
          <ul className="flex flex-col gap-1">
            {rules.history.map((change, index) => (
              <li key={index}>
                {formatDayMonthTime(change.changedAt)} ·{' '}
                {change.kind === 'RULE' && change.rule
                  ? t('screen.admin.rules.changed', {
                      rule: t(`screen.admin.rules.rule.${change.rule}` as MessageKey),
                      from: change.oldValue ?? 0,
                      to: change.newValue ?? 0,
                    })
                  : t(change.kind === 'DOMAIN_ADDED' ? 'screen.admin.rules.added' : 'screen.admin.rules.removed', {
                      domain: change.domain ?? '',
                    })}
              </li>
            ))}
          </ul>
        )}
      </section>
      </form>
      <StepUpDialog
        open={stepUp}
        onOpenChange={setStepUp}
        confirmLabel={t('screen.admin.rules.save')}
        pending={save.isPending}
        onConfirm={(code, refused) => save.mutate(code, { onError: refused })}
      />
    </>
  )
}
