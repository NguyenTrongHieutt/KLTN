import { useId, useState } from 'react'
import { FormField } from '@/components/FormField'
import { fieldProps, inputClass } from '@/components/formField-props'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { t } from '@/i18n/t'
import { problemMessage } from '@/lib/problemMessage'
import { cn } from '@/lib/utils'
import {
  useExternalAccess,
  useGrantAccess,
  useSaveIdentifier,
  type ExternalAccess,
  type ExternalAccessItem,
} from './externalAccessApi'

/**
 * External Access after confirmation (FR-66, UX-DR83). The Customer gets N-8 per item: whom to invite,
 * each person's account beside their GitHub username, and "Đã cấp quyền". The Mentor (JOB-6) and a Crew
 * member (STU-4) give their own account per item; only the Customer reads it. Nothing shows before the
 * confirmation or for an Agreement without items. `level` is the section heading's level: 3 inside a card.
 */
export function ExternalAccessSection({ jobId, level = 2 }: { jobId: string; level?: 2 | 3 }) {
  const access = useExternalAccess(jobId)
  const headingId = useId()
  const Heading = level === 2 ? 'h2' : 'h3'
  if (!access.data || access.data.items.length === 0) {
    return null
  }
  const customer = access.data.viewer === 'CUSTOMER'
  return (
    <section aria-labelledby={headingId} className="flex flex-col gap-3">
      <div className="flex flex-col gap-0.5">
        <Heading id={headingId} className={level === 2 ? 'text-h2' : 'text-h3'}>
          {t(customer ? 'screen.agreement.externalGuide.customerTitle' : 'screen.agreement.externalGuide.teamTitle')}
        </Heading>
        <p className="text-body-sm text-muted-foreground">
          {t(customer ? 'screen.agreement.externalGuide.customerIntro' : 'screen.agreement.externalGuide.teamIntro')}
        </p>
      </div>
      {!access.data.open && <p className="text-body-sm text-muted-foreground">{t('screen.agreement.externalGuide.ended')}</p>}
      {access.data.items.map((item) =>
        customer ? (
          <Guidance key={item.id} jobId={jobId} access={access.data} item={item} level={level} />
        ) : (
          <OwnAccount key={item.id} jobId={jobId} access={access.data} item={item} level={level} />
        ),
      )}
    </section>
  )
}

function ItemHeading({ item, level }: { item: ExternalAccessItem; level: 2 | 3 }) {
  const Heading = level === 2 ? 'h3' : 'h4'
  return (
    <div className="flex flex-wrap items-center justify-between gap-2">
      <Heading className={cn('break-words', level === 2 ? 'text-h3' : 'text-label font-semibold')}>
        {item.system} · {item.purpose}
      </Heading>
      <span
        className={cn(
          'rounded-full px-2 py-0.5 text-caption font-semibold',
          item.live ? 'bg-destructive-soft text-destructive' : 'bg-muted',
        )}
      >
        {t(item.live ? 'screen.agreement.envPRODUCTION' : 'screen.agreement.envNON_PRODUCTION')}
      </span>
    </div>
  )
}

interface ItemProps {
  jobId: string
  access: ExternalAccess
  item: ExternalAccessItem
  level: 2 | 3
}

/** N-8 for one item, with the people's accounts and the tick. */
function Guidance({ jobId, access, item, level }: ItemProps) {
  const grant = useGrantAccess(jobId)
  const toasts = useToasts()
  return (
    <article className="flex flex-col gap-2.5 rounded-lg border border-border bg-card p-5">
      <ItemHeading item={item} level={level} />
      {access.open && (
        <>
          <ol className="flex list-decimal flex-col gap-1 pl-5 text-body-sm">
            <li>{t('screen.agreement.externalGuide.step1')}</li>
            <li>{t('screen.agreement.externalGuide.step2')}</li>
          </ol>
          {item.identifiers.length > 0 ? (
            <ul className="flex flex-col gap-1">
              {item.identifiers.map((person) => (
                <li key={person.githubLogin} className="flex flex-wrap items-baseline gap-x-3 text-body-sm">
                  <span className="text-muted-foreground">@{person.githubLogin}</span>
                  <span className="font-mono break-all">{person.identifier}</span>
                </li>
              ))}
            </ul>
          ) : (
            <p className="text-body-sm text-muted-foreground">{t('screen.agreement.externalGuide.noIdentifiers')}</p>
          )}
        </>
      )}
      <label className="flex items-center gap-2 text-body-sm">
        <input
          type="checkbox"
          checked={item.granted === true}
          disabled={item.granted === true || grant.isPending}
          onChange={() =>
            grant.mutate(item.id, { onError: (error) => toasts.error(problemMessage(error, { module: 'job' }).text) })
          }
        />
        {t('screen.agreement.externalGuide.granted')}
      </label>
    </article>
  )
}

/** The Mentor's or a Crew member's own account on one item's system. */
function OwnAccount({ jobId, access, item, level }: ItemProps) {
  const [value, setValue] = useState(item.mine ?? '')
  const [error, setError] = useState<string>()
  const save = useSaveIdentifier(jobId)
  const toasts = useToasts()
  const id = useId()
  return (
    <article className="flex flex-col gap-2.5 rounded-lg border border-border bg-card p-5">
      <ItemHeading item={item} level={level} />
      <form
        className="flex flex-col gap-2"
        onSubmit={(event) => {
          event.preventDefault()
          setError(undefined)
          save.mutate(
            { accessId: item.id, identifier: value },
            {
              onSuccess: () => toasts.success(t('screen.agreement.externalGuide.saved')),
              onError: (failed) => setError(problemMessage(failed, { module: 'job' }).text),
            },
          )
        }}
      >
        <FormField
          id={id}
          label={t('screen.agreement.externalGuide.yourAccount', { system: item.system })}
          helper={t('screen.agreement.externalGuide.yourAccountHint')}
          error={error}
        >
          <input
            {...fieldProps(id, error, t('screen.agreement.externalGuide.yourAccountHint'))}
            className={inputClass}
            value={value}
            maxLength={200}
            autoComplete="off"
            spellCheck={false}
            disabled={!access.open}
            onChange={(event) => setValue(event.target.value)}
          />
        </FormField>
        <Button type="submit" className="self-end" disabled={!access.open || save.isPending}>
          {t('screen.agreement.externalGuide.save')}
        </Button>
      </form>
    </article>
  )
}
