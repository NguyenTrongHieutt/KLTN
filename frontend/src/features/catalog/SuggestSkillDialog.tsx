import { Dialog } from '@base-ui/react/dialog'
import { useMutation } from '@tanstack/react-query'
import { useId, useState, type FormEvent } from 'react'
import { inputClass } from '@/components/formField-props'
import { Button } from '@/components/ui/button'
import { t } from '@/i18n/t'
import { ApiError, apiClient, unwrap } from '@/lib/apiClient'
import { problemMessage } from '@/lib/problemMessage'
import { cn } from '@/lib/utils'
import { useCatalog } from './catalogApi'

/** An active Skill the catalog already has, as `SKILL_ALREADY_EXISTS` names it. */
interface Existing {
  id: string
  name: string
}

/**
 * "Đề xuất thêm kỹ năng này" (FR-73, UX-DR77): the name, pre-filled with what the person typed, an optional Skill
 * Category, and an optional note. A name an active Skill already has offers "Chọn kỹ năng này" instead; the 6th
 * suggestion in a day reads "Bạn đã gửi đủ 5 đề xuất hôm nay. Hãy thử lại vào ngày mai."
 */
export function SuggestSkillDialog({
  name: initialName,
  onPick,
  onClose,
}: {
  name: string
  /** Adds an existing Skill to the picker. */
  onPick: (skillId: string) => void
  onClose: () => void
}) {
  const ids = useId()
  const catalog = useCatalog()
  const [name, setName] = useState(initialName)
  const [category, setCategory] = useState('')
  const [note, setNote] = useState('')
  const [error, setError] = useState<string>()
  const [existing, setExisting] = useState<Existing>()
  const [sent, setSent] = useState(false)
  const suggest = useMutation({
    mutationFn: () =>
      unwrap(
        apiClient.POST('/api/v1/skill-suggestions', {
          body: { name: name.trim(), categoryId: category || undefined, note: note.trim() || undefined },
        }),
      ),
  })

  function submit(event: FormEvent) {
    event.preventDefault()
    if (name.trim().length < 2) {
      setError(t('screen.shared.suggestSkill.nameShort'))
      return
    }
    setError(undefined)
    setExisting(undefined)
    suggest.mutate(undefined, {
      onSuccess: () => setSent(true),
      onError: (failure) => {
        if (failure instanceof ApiError && failure.problem.reason === 'SKILL_ALREADY_EXISTS') {
          const problem = failure.problem as { skillId?: string; skillName?: string }
          if (problem.skillId && problem.skillName) {
            setExisting({ id: problem.skillId, name: problem.skillName })
            return
          }
        }
        setError(
          failure instanceof ApiError && failure.problem.reason === 'SKILL_SUGGESTION_LIMIT'
            ? t('reason.admin.SKILL_SUGGESTION_LIMIT')
            : problemMessage(failure, { module: 'admin' }).text,
        )
      },
    })
  }

  return (
    <Dialog.Root open onOpenChange={(open) => !open && onClose()}>
      <Dialog.Portal>
        <Dialog.Backdrop className="fixed inset-0 z-[70] bg-foreground/50" />
        <Dialog.Popup className="fixed top-24 left-1/2 z-[80] flex w-[min(480px,calc(100vw-32px))] -translate-x-1/2 flex-col gap-4 rounded-lg bg-popover p-6 text-popover-foreground shadow-md">
          <Dialog.Title className="text-h2">{t('screen.shared.suggestSkill.title')}</Dialog.Title>
          {sent ? (
            <>
              <p role="status" className="text-body-sm">
                {t('screen.shared.suggestSkill.sent')}
              </p>
              <div className="flex justify-end">
                <Dialog.Close render={<Button type="button" />}>{t('screen.shared.suggestSkill.close')}</Dialog.Close>
              </div>
            </>
          ) : (
            <form noValidate onSubmit={submit} className="flex flex-col gap-3">
              <Dialog.Description className="text-body-sm">{t('screen.shared.suggestSkill.body')}</Dialog.Description>
              <label htmlFor={`${ids}-name`} className="text-label">
                {t('screen.shared.suggestSkill.name')}
              </label>
              <input
                id={`${ids}-name`}
                value={name}
                maxLength={60}
                onChange={(event) => setName(event.target.value)}
                className={inputClass}
              />
              <label htmlFor={`${ids}-category`} className="text-label">
                {t('screen.shared.suggestSkill.category')}
              </label>
              <select
                id={`${ids}-category`}
                value={category}
                onChange={(event) => setCategory(event.target.value)}
                className={inputClass}
              >
                <option value="">{t('screen.shared.suggestSkill.anyCategory')}</option>
                {(catalog.data?.categories ?? []).map((option) => (
                  <option key={option.id} value={option.id}>
                    {option.name}
                  </option>
                ))}
              </select>
              <label htmlFor={`${ids}-note`} className="text-label">
                {t('screen.shared.suggestSkill.note')}
              </label>
              <textarea
                id={`${ids}-note`}
                value={note}
                maxLength={500}
                onChange={(event) => setNote(event.target.value)}
                className={cn(inputClass, 'min-h-20')}
              />
              {existing && (
                <div role="alert" className="flex flex-wrap items-center justify-between gap-2 rounded-md bg-muted p-3 text-body-sm">
                  <span>{t('screen.shared.suggestSkill.exists', { name: existing.name })}</span>
                  <Button
                    type="button"
                    variant="secondary"
                    size="compact"
                    onClick={() => {
                      onPick(existing.id)
                      onClose()
                    }}
                  >
                    {t('screen.shared.suggestSkill.pick')}
                  </Button>
                </div>
              )}
              {error && (
                <p role="alert" className="text-body-sm text-destructive">
                  {error}
                </p>
              )}
              <div className="flex flex-wrap justify-between gap-3">
                <Dialog.Close render={<Button type="button" variant="secondary" />}>{t('screen.shared.suggestSkill.cancel')}</Dialog.Close>
                <Button type="submit" disabled={suggest.isPending}>
                  {t('screen.shared.suggestSkill.send')}
                </Button>
              </div>
            </form>
          )}
        </Dialog.Popup>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
