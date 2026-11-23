import type { ReactNode } from 'react'
import { t } from '@/i18n/t'

/**
 * The "THỬ NGHIỆM" tag after every amount (FR-45, UX-DR20), announced as "tiền thử nghiệm, không phải tiền thật".
 * In a table of amounts under the test-mode banner the column's header says it once instead ({@link TestColumn}).
 */
export function TestTag() {
  return (
    <span className="inline-block rounded-sm border border-dashed border-test-mode bg-test-mode-soft px-1.5 py-px align-baseline text-tag whitespace-nowrap text-test-mode uppercase">
      <span aria-hidden="true">{t('screen.matching.test')}</span>
      <span className="sr-only">{t('shell.testMoney')}</span>
    </span>
  )
}

/**
 * The header of a table column of test money on a page under the test-mode banner (the author's decision of
 * 2026-11-23): "Số tiền (thử nghiệm)" once, instead of the tag after every amount of the column. Screen readers hear
 * it with each cell's header.
 */
export function TestColumn({ children }: { children: ReactNode }) {
  return (
    <>
      {children} <span className="font-normal whitespace-nowrap">({t('shell.testColumn')})</span>
    </>
  )
}
