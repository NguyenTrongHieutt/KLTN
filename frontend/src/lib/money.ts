/** U+00A0, written as an escape so the source holds no invisible character. */
const NO_BREAK_SPACE = '\u00A0'

/**
 * Whole VND as the screens write it, "800.000 VND" (UX-DR20; the code "VND" since 2026-11-24, where the sign "₫"
 * stood before): groups of three with a dot and the currency code after a space. Built by hand, because ICU's `vi-VN`
 * separators differ between runtimes. The space is a no-break space, so a line never ends on the number with "VND"
 * alone on the next one. The backend writes the amounts in its notifications the same way.
 */
export function formatVnd(amount: number): string {
  return `${groupDigits(String(Math.trunc(amount)))}${NO_BREAK_SPACE}VND`
}

/** "800.000" for the digits typed so far, for a price field formatted while typing. */
export function groupDigits(digits: string): string {
  const plain = digits.replace(/\D/g, '').replace(/^0+(?=\d)/, '')
  return plain.replace(/\B(?=(\d{3})+(?!\d))/g, '.')
}

/** The whole VND a price field holds, or `undefined` when it holds no digit. */
export function parseVnd(text: string): number | undefined {
  const digits = text.replace(/\D/g, '')
  return digits === '' ? undefined : Number(digits)
}
