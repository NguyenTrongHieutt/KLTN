import { t, type MessageKey } from '@/i18n/t'
import type { HistoryEntry } from './workApi'

const FIELD_LABELS: Record<string, MessageKey> = {
  type: 'screen.board.item.type',
  title: 'screen.board.item.titleLabel',
  description: 'screen.board.item.description',
  checklist: 'screen.board.item.checklist',
  milestoneId: 'screen.board.item.milestone',
  priority: 'screen.board.item.priority',
  estimateHours: 'screen.board.item.estimate',
  assigneeId: 'screen.board.item.assignee',
}

/** One history line: who did what; a note's text and a review's comment show below it. */
export function entryText(entry: HistoryEntry): string {
  switch (entry.action) {
    case 'SEEDED':
      return t('screen.board.item.historySeeded')
    case 'SUBMITTED':
    case 'APPROVED':
    case 'RETURNED':
      return t(`screen.board.item.history${entry.action}` as MessageKey, { actor: entry.actor })
    case 'MOVED':
      return t('screen.board.item.historyMOVED', { actor: entry.actor })
    case 'CREATED':
      return t('screen.board.item.historyCreated', { actor: entry.actor })
    case 'NOTE':
      return entry.actor
    case 'ATTACHED':
    case 'DETACHED':
      return t(`screen.board.item.history${entry.action}` as MessageKey, {
        actor: entry.actor,
        name: entry.attachment ?? '',
      })
    default:
      if (entry.assignedTo) {
        return t('screen.board.item.historyAssigned', { actor: entry.actor, assignee: entry.assignedTo })
      }
      return t('screen.board.item.historyUpdated', {
        actor: entry.actor,
        fields: entry.fields
          .filter((field) => FIELD_LABELS[field])
          .map((field) => t(FIELD_LABELS[field]).toLowerCase())
          .join(', '),
      })
  }
}
