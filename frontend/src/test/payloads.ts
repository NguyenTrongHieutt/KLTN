/**
 * The planted payloads of SG-30 (NFR-3, AD-21): XSS strings that every user-written field must show
 * as inert text in every view.
 */
export const PAYLOADS = [
  '<img src=x onerror=alert(1)>',
  '<script>alert(1)</script>',
  '"><svg onload=alert(1)>',
  'javascript:alert(1)',
  "'-alert(1)-'",
  '<a href="javascript:alert(1)">bấm</a>',
]
