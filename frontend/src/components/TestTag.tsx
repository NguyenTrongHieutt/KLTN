import { t } from '@/i18n/t'

/**
 * The "THỬ NGHIỆM" tag after every amount (FR-45, UX-DR20): never hidden, even in dense tables, and
 * announced as "tiền thử nghiệm, không phải tiền thật".
 */
export function TestTag() {
  return (
    <span className="inline-block rounded-sm border border-dashed border-test-mode bg-test-mode-soft px-1.5 py-px align-baseline text-tag whitespace-nowrap text-test-mode uppercase">
      <span aria-hidden="true">{t('screen.matching.test')}</span>
      <span className="sr-only">{t('shell.testMoney')}</span>
    </span>
  )
}
