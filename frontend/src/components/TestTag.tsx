import { t } from '@/i18n/t'

/**
 * The "THỬ NGHIỆM" tag after every amount (FR-45, UX-DR20): never hidden, even in dense tables, and
 * announced as "tiền thử nghiệm, không phải tiền thật".
 */
export function TestTag() {
  return (
    <span className="rounded bg-warning-soft px-1 text-caption font-semibold text-warning">
      <span aria-hidden="true">{t('screen.matching.test')}</span>
      <span className="sr-only">{t('shell.testMoney')}</span>
    </span>
  )
}
