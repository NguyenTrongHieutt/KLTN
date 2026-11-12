import { t } from '@/i18n/t'
import { ApiError } from '@/lib/apiClient'
import { formatDayMonthTime } from '@/lib/dates'
import { problemMessage } from '@/lib/problemMessage'
import type { SourceStatus, Tool } from './productApi'

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

/**
 * GitHub's edit page for a file that already exists on the branch (Story 23.5c): the new-file page refuses an existing
 * path, and the edit page cannot be prefilled, so the Customer pastes the copied content there. The branch and the
 * path keep their slashes as segments, each segment encoded on its own.
 */
export function editWorkflowUrl(htmlUrl: string, branch: string, path: string): string {
  const segments = (value: string) => value.split('/').map(encodeURIComponent).join('/')
  return `${htmlUrl.replace(/\/+$/, '')}/edit/${segments(branch)}/${segments(path)}`
}

/** The repository's Actions page for `vibefix.yml`, where the Customer may run the optional gitleaks scan. */
export function workflowActionsUrl(htmlUrl: string): string {
  return `${htmlUrl.replace(/\/+$/, '')}/actions/workflows/vibefix.yml`
}

/** The one tone of each Workflow status, on CUS-2's badge and on CUS-4's card alike (Story 23.5c). */
export function statusTone(status: SourceStatus): 'success' | 'warning' {
  return status === 'READY' ? 'success' : 'warning'
}

/** The host of a link, without `www.`, for the "(domain)" note beside an outside link. */
export function domainOf(url: string): string {
  try {
    return new URL(url).hostname.replace(/^www\./, '')
  } catch {
    return url
  }
}

/**
 * A refused "Kiểm tra" or "Kiểm tra lại" in plain words: past the "Kiểm tra" limit (AD-34, Story 23.5c), when to try
 * again from `Retry-After`; anything else as `product` words it.
 */
export function checkProblem(error: unknown): string {
  if (error instanceof ApiError && error.problem.reason === 'CHECK_LIMIT') {
    const at = new Date(Date.now() + (error.retryAfter ?? 3600) * 1000).toISOString()
    return t('reason.product.CHECK_LIMIT', { time: formatDayMonthTime(at) })
  }
  return problemMessage(error, { module: 'product' }).text
}

/** A repository size in MB from GitHub's kilobytes, as "38 MB". */
export function megabytes(sizeKb: number): string {
  const number = new Intl.NumberFormat('vi-VN', { maximumFractionDigits: 1 })
  return `${number.format(Math.max(0.1, sizeKb / 1024))} MB`
}
