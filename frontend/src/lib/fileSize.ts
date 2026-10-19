/** A file size as the screens show it: "1,2 MB" or "350 KB". */
export function formatSize(bytes: number): string {
  const number = new Intl.NumberFormat('vi-VN', { maximumFractionDigits: 1 })
  return bytes >= 1024 * 1024 ? `${number.format(bytes / 1024 / 1024)} MB` : `${Math.max(1, Math.round(bytes / 1024))} KB`
}
