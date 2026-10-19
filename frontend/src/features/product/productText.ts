import type { Tool } from './productApi'

/** The AI tools of CUS-3 step 1, in the frames' order (FR-7). */
export const TOOLS: Tool[] = ['LOVABLE', 'BOLT', 'V0', 'REPLIT', 'BASE44', 'CURSOR', 'CLAUDE_CODE', 'CODEX', 'OTHER']

/**
 * Each tool's documentation for getting its code into a public GitHub repository. The help text
 * itself is in `vi.json` (`screen.product.toolHelp`); both are drafts until OQ7 settles them.
 */
export const TOOL_DOCS: Record<Tool, string> = {
  LOVABLE: 'https://docs.lovable.dev',
  BOLT: 'https://support.bolt.new',
  V0: 'https://vercel.com/docs/v0',
  REPLIT: 'https://docs.replit.com',
  BASE44: 'https://docs.base44.com',
  CURSOR: 'https://docs.cursor.com',
  CLAUDE_CODE: 'https://docs.anthropic.com/en/docs/claude-code/overview',
  CODEX: 'https://developers.openai.com/codex',
  OTHER: 'https://docs.github.com/en/repositories/creating-and-managing-repositories/quickstart-for-repositories',
}

/** How to make a repository public, linked from `REPO_PRIVATE` and `REPO_NOT_FOUND`. */
export const MAKE_PUBLIC_DOCS =
  'https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/managing-repository-settings/setting-repository-visibility'

/** The host of a link, without `www.`, for the "(domain)" note beside an outside link. */
export function domainOf(url: string): string {
  try {
    return new URL(url).hostname.replace(/^www\./, '')
  } catch {
    return url
  }
}

/** A repository size in MB from GitHub's kilobytes, as "38 MB". */
export function megabytes(sizeKb: number): string {
  const number = new Intl.NumberFormat('vi-VN', { maximumFractionDigits: 1 })
  return `${number.format(Math.max(0.1, sizeKb / 1024))} MB`
}
