import { t, type MessageKey } from '@/i18n/t'

/** One thing that keeps an account deletion waiting (NFR-11, AD-24), as ALL-2 and ADM-2 receive it. */
export interface ErasureBlocker {
  reason: string
  jobId?: string | null
  name?: string | null
}

/** Who reads the blockers: the person about their own request on ALL-2, or an Administrator on ADM-2. */
export type BlockerReader = 'self' | 'admin'

const LABELS: Record<BlockerReader, string> = {
  self: 'screen.myData.blocker',
  admin: 'screen.admin.users.deletion.blocker',
}

/** "Công việc đang chạy · {Job}", or the reason alone when the blocker names no Job (Story 20.4). */
export function erasureBlockerText(blocker: ErasureBlocker, reader: BlockerReader = 'self'): string {
  const reason = t(`${LABELS[reader]}.${blocker.reason}` as MessageKey)
  return blocker.name ? t('screen.myData.blockerOn', { reason, name: blocker.name }) : reason
}
