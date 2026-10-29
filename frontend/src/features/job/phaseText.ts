import { t, type MessageKey } from '@/i18n/t'
import type { JobNeed, JobPhase } from './jobPostApi'

/** The words of a phase (§4.0, X6): "Đang tìm người hướng dẫn", "Đang tuyển thêm thành viên", … */
export function phaseText(phase: JobPhase): string {
  return t(`screen.jobPost.phase.${phase}` as MessageKey)
}

/** What a listed Job needs (UX-DR97): "Cần người hướng dẫn", or "Cần thêm {n} thành viên nhóm". */
export function needText(need: JobNeed, openSlots: number | undefined): string {
  return need === 'MENTOR' ? t('screen.jobPost.needMentor') : t('screen.jobPost.needCrew', { n: openSlots ?? 0 })
}
