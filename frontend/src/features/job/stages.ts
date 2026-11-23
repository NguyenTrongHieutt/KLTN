import type { Job } from './jobApi'

/** The 7 Stages of a Job (PUB-1, PRD §4). */
export const STAGES = [1, 2, 3, 4, 5, 6, 7] as const

/**
 * The current stage of the 7 Stages (PUB-1) for a Job state (EXPERIENCE.md, "UX changes of 2026-11-23"): Draft 1;
 * Open, Selecting and Agreement 3 (stage 2, the Assessment or the Agreement draft, counts as passed); Starting 4;
 * Active 5; Closing 7; Closed past the last stage (8). A cancelled Job has no strip.
 */
export function currentStage(state: Job['state']): number | undefined {
  switch (state) {
    case 'DRAFT':
      return 1
    case 'OPEN':
    case 'SELECTING':
    case 'AGREEMENT':
      return 3
    case 'STARTING':
      return 4
    case 'ACTIVE':
      return 5
    case 'CLOSING':
      return 7
    case 'CLOSED':
      return 8
    default:
      return undefined
  }
}
