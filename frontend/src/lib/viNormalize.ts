/**
 * The SPA's copy of `platform.vi_normalize` (AD-38, SG-50): accents and the letter case removed,
 * "đ" as "d", whitespace collapsed and trimmed. The Skill picker filters with it, so a search for
 * "bao mat" finds "Bảo mật" exactly as the database's unique names and duplicate checks compare.
 * `vi-normalize.fixtures.json` is shared with the backend's test of the database function.
 */
export function viNormalize(value: string): string {
  return value
    .normalize('NFD')
    .replace(/\p{M}/gu, '')
    .replace(/đ/g, 'd')
    .replace(/Đ/g, 'D')
    .toLowerCase()
    .replace(/\s+/g, ' ')
    .trim()
}
