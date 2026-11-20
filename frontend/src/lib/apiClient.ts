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
  /** `JOB_INCOMPLETE`: the answers publishing still needs (FR-9). */
  missing?: string[]
  /** A repository check's refusal (`product`): the repository's `owner/name`, which fills a reason's `{repo}`. */
  repository?: string
  /** `WORKFLOW_MISMATCH`: the file holds a carriage return, so the copy warns about line endings (AD-45). */
  lineEndings?: boolean
  /** `STALE` of a J7 self-confirmation: the Source Repository's head read now, which moved since the page showed it. */
  head?: string
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
 * `undefined`, which the operation's type then says. The backend writes a `@Nullable` field as
 * `null`, while the generated types make it optional, so such fields are left out here and read as
 * `undefined`, as the types say.
 */
export async function unwrap<T>(
  request: Promise<{ data?: T; error?: unknown; response: Response }>,
): Promise<T> {
  const { data, error, response } = await request
  if (!response.ok) {
    throw new ApiError(response.status, isProblem(error) ? error : {}, retryAfterOf(response.headers.get('Retry-After')))
  }
  return withoutNulls(data) as T
}

/** What {@link uploadWithProgress} reports while it sends, and how the caller stops it. */
export interface UploadOptions {
  /** The bytes sent so far and the whole body, each time the browser reports them with a known length. */
  onProgress?: (loaded: number, total: number) => void
  /** Aborts the upload: the promise then rejects with a `DOMException` named `AbortError`. */
  signal?: AbortSignal
}

/**
 * A multipart upload that reports its progress (AD-23, UX-DR45). `fetch` reports nothing about a request body, so
 * this POST goes through `XMLHttpRequest`, the one other way to the backend, kept here beside the client. It sends
 * what the client's middleware sends on a command, the CSRF cookie in `X-XSRF-TOKEN` (AD-19), and settles as
 * {@link unwrap} does: the parsed body without its `null` fields, or `undefined` for an empty one. A non-2xx answer
 * rejects with an {@link ApiError} carrying the Problem Details when the body parses as JSON, `{}` otherwise, and the
 * `Retry-After`. A failure without an answer rejects with a plain `Error`, which reads as unexpected, and an abort
 * through `signal` with a `DOMException` named `AbortError`.
 */
export function uploadWithProgress<T>(path: string, form: FormData, { onProgress, signal }: UploadOptions = {}): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    if (signal?.aborted) {
      reject(aborted())
      return
    }
    const xhr = new XMLHttpRequest()
    xhr.open('POST', new URL(path, window.location.origin).href)
    xhr.setRequestHeader('Accept', 'application/json')
    const token = readCookie('XSRF-TOKEN')
    if (token) {
      xhr.setRequestHeader('X-XSRF-TOKEN', token)
    }
    // Listened to before `send`, or the browser reports nothing about the body.
    xhr.upload.addEventListener('progress', (event) => {
      if (event.lengthComputable) {
        onProgress?.(event.loaded, event.total)
      }
    })
    const abort = () => xhr.abort()
    signal?.addEventListener('abort', abort, { once: true })
    const settle = (outcome: () => void) => {
      signal?.removeEventListener('abort', abort)
      outcome()
    }
    xhr.addEventListener('load', () =>
      settle(() => {
        const text = xhr.responseText
        if (xhr.status < 200 || xhr.status > 299) {
          const problem = jsonOrUndefined(text)
          reject(new ApiError(xhr.status, isProblem(problem) ? problem : {}, retryAfterOf(xhr.getResponseHeader('Retry-After'))))
          return
        }
        try {
          resolve(withoutNulls(text ? JSON.parse(text) : undefined) as T)
        } catch (error) {
          reject(error)
        }
      }),
    )
    xhr.addEventListener('error', () => settle(() => reject(new Error('The upload got no answer'))))
    xhr.addEventListener('abort', () => settle(() => reject(aborted())))
    xhr.send(form)
  })
}

/** `Retry-After` in seconds (AD-34), when the answer gives a positive number of them. */
function retryAfterOf(header: string | null): number | undefined {
  const seconds = Number(header)
  return Number.isFinite(seconds) && seconds > 0 ? seconds : undefined
}

/** An error answer's body as JSON, or `undefined` when it is not JSON (an HTML page from a proxy, say). */
function jsonOrUndefined(text: string): unknown {
  try {
    return JSON.parse(text)
  } catch {
    return undefined
  }
}

function aborted(): DOMException {
  return new DOMException('The upload was cancelled', 'AbortError')
}

/**
 * A copy of parsed JSON without the object fields whose value is `null`; array entries stay where
 * they are, and anything but plain objects and arrays (a `Blob`, say) is returned as it is.
 */
function withoutNulls(value: unknown): unknown {
  if (Array.isArray(value)) {
    return value.map(withoutNulls)
  }
  if (typeof value !== 'object' || value === null || Object.getPrototypeOf(value) !== Object.prototype) {
    return value
  }
  return Object.fromEntries(
    Object.entries(value)
      .filter(([, entry]) => entry !== null)
      .map(([key, entry]) => [key, withoutNulls(entry)]),
  )
}

function isProblem(value: unknown): value is Problem {
  return typeof value === 'object' && value !== null
}
