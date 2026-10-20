/** Leaves the app for another site in the same tab, as VNPay's sandbox page needs (AD-14). */
export function redirectTo(url: string): void {
  window.location.assign(url)
}
