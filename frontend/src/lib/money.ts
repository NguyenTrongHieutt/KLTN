/**
 * Whole VND as Vietnamese readers write it, "800.000 ₫" (UX-DR20): groups of three with a dot and the
 * dong sign after a space. Built by hand, because ICU's `vi-VN` separators differ between runtimes.
 */
export function formatVnd(amount: number): string {
  return `${groupDigits(String(Math.trunc(amount)))} ₫`
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
