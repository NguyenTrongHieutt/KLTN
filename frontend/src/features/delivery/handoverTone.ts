import type { StatusTone } from '@/components/ui/status-badge'

/**
 * The one status-to-tone map of a handover, so the list and the Delivery Report agree (DESIGN.md, Status badge):
 * accepted is done; a submission waits on the Customer and is in progress for the team; a revision request waits on
 * the team; a void is neutral.
 */
export function handoverTone(status: string, auto: boolean, customer: boolean): StatusTone {
  if (auto || status === 'ACCEPTED') {
    return 'done'
  }
  if (status === 'SUBMITTED') {
    return customer ? 'waiting' : 'progress'
  }
  if (status === 'REVISION_REQUESTED') {
    return customer ? 'progress' : 'waiting'
  }
  return 'neutral'
}
