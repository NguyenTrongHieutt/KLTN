import createClient from 'openapi-fetch'
import type { paths } from '../api/schema'

/**
 * The only way the SPA talks to the backend (AD-23): the client generated from the backend's
 * OpenAPI document. ESLint bans `fetch` and `openapi-fetch` everywhere else.
 */
export const apiClient = createClient<paths>({
  // The page's own origin (AD-26), spelled out: a `Request` outside a browser needs an absolute URL.
  baseUrl: window.location.origin,
  // Looked up on each call rather than kept from start-up, so tests can stand in for the backend.
  fetch: (request) => globalThis.fetch(request),
})

const SAFE_METHODS = ['GET', 'HEAD', 'OPTIONS']

/**
 * CSRF is cookie-to-header (AD-19): the backend sets the `XSRF-TOKEN` cookie, and every command
 * sends it back in `X-XSRF-TOKEN`.
 */
apiClient.use({
  onRequest({ request }) {
    if (!SAFE_METHODS.includes(request.method)) {
      const token = readCookie('XSRF-TOKEN')
      if (token) {
        request.headers.set('X-XSRF-TOKEN', token)
      }
    }
    return request
  },
})

/**
 * A multipart body for an upload: the generated types call a file part a string, while the browser
 * sends the `FormData` with its own boundary (openapi-fetch leaves the Content-Type to it).
 */
export function formBody<T>(form: FormData): T {
  return form as unknown as T
}

export function readCookie(name: string): string | undefined {
  const prefix = `${name}=`
  const cookie = document.cookie.split('; ').find((entry) => entry.startsWith(prefix))
  return cookie === undefined ? undefined : decodeURIComponent(cookie.slice(prefix.length))
}

/** The RFC 9457 Problem Details body of an error response; the SPA maps `code` and `reason` to Vietnamese. */
export interface Problem {
  status?: number
  code?: string
  reason?: string
  errors?: { field: string; code: string }[]
  /** Only on a 5xx: the request's correlation ID, which the SPA shows as a reference. */
  correlationId?: string
  /** A wrong step-up code: how many tries are left before sign-in locks (SG-10). */
  attemptsLeft?: number
  /** `INTAKE_FINDING_OPEN`: how many Intake Findings are still open (FR-8). */
  count?: number
  /** `JOB_INCOMPLETE`: the answers publishing still needs (FR-9). */
  missing?: string[]
}

export class ApiError extends Error {
  readonly status: number
  readonly problem: Problem
  /** `RATE_LIMITED`'s `Retry-After`, in seconds (AD-34). */
  readonly retryAfter?: number

  constructor(status: number, problem: Problem, retryAfter?: number) {
    super(`API error ${status}${problem.code ? ` ${problem.code}` : ''}`)
    this.name = 'ApiError'
    this.status = status
    this.problem = problem
    this.retryAfter = retryAfter
  }
}

/**
 * Resolves to the response data, or rejects with an {@link ApiError} carrying the Problem Details.
 * A successful response without a body (204, or an empty 200 from a command) resolves to
 * `undefined`, which the operation's type then says.
 */
export async function unwrap<T>(
  request: Promise<{ data?: T; error?: unknown; response: Response }>,
): Promise<T> {
  const { data, error, response } = await request
  if (!response.ok) {
    const retryAfter = Number(response.headers.get('Retry-After'))
    throw new ApiError(
      response.status,
      isProblem(error) ? error : {},
      Number.isFinite(retryAfter) && retryAfter > 0 ? retryAfter : undefined,
    )
  }
  return data as T
}

function isProblem(value: unknown): value is Problem {
  return typeof value === 'object' && value !== null
}
