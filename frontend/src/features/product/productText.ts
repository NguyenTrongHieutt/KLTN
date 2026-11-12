import type { Tool } from './productApi'

/** The AI tools of CUS-3 step 1, in the frames' order (FR-7). */
export const TOOLS: Tool[] = ['LOVABLE', 'BOLT', 'V0', 'REPLIT', 'BASE44', 'CURSOR', 'CLAUDE_CODE', 'CODEX', 'OTHER']

/**
 * Each tool's documentation for getting its code into a GitHub repository, public or private. The help text
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

/** N-2's credential items (PRD addendum §B6), each with the page where its keys are changed or revoked. */
export const SECRET_PROVIDERS = [
  { key: 'SUPABASE', provider: 'Supabase', url: 'https://supabase.com/dashboard/project/_/settings/api-keys' },
  { key: 'FIREBASE', provider: 'Firebase', url: 'https://console.firebase.google.com' },
  { key: 'OPENAI', provider: 'OpenAI', url: 'https://platform.openai.com/api-keys' },
  { key: 'VNPAY', provider: 'VNPay', url: 'https://vnpay.vn' },
] as const

/**
 * GitHub's new-file page in the repository, prefilled with the published workflow's path and content (AD-45),
 * where the Customer commits `vibefix.yml` on the default branch. A branch such as `release/v2` keeps its
 * slashes as path segments, each segment encoded on its own.
 */
export function newWorkflowUrl(htmlUrl: string, branch: string, path: string, content: string): string {
  const query = `filename=${encodeURIComponent(path)}&value=${encodeURIComponent(content)}`
  const segments = branch.split('/').map(encodeURIComponent).join('/')
  return `${htmlUrl.replace(/\/+$/, '')}/new/${segments}?${query}`
}

/** The repository's Actions page for `vibefix.yml`, where the Customer may run the optional gitleaks scan. */
export function workflowActionsUrl(htmlUrl: string): string {
  return `${htmlUrl.replace(/\/+$/, '')}/actions/workflows/vibefix.yml`
}

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
