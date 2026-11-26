import { vi } from 'vitest'
import type { Capabilities, Session } from '@/features/identity/api'

type Handler = (request: Request) => Response | Promise<Response>

export const anonymous: Session = { status: 'ANONYMOUS', privacyNoticeVersion: '3.1', selfMentoredJobs: false }

/** A Google account on PUB-3, waiting to accept N-10 (Story 19.1). */
export const welcome: Session = { status: 'SIGNING_UP', displayName: 'Nguyễn Thị Lan', privacyNoticeVersion: '3.1', selfMentoredJobs: false }

/** What an account can show for itself (Story 20.1): by default nothing verified, and unlocked. */
export function capabilities(overrides: Partial<Capabilities> = {}): Capabilities {
  return { githubVerified: false, linkedinVerified: false, studentVerified: false, locked: false, ...overrides }
}

/** A signed-in Google account: a `USER` with no role and nothing verified yet (Stories 19.1, 20.1). */
export const signedInWithGoogle: Session = {
  status: 'SIGNED_IN',
  privacyNoticeVersion: '3.1', selfMentoredJobs: false,
  displayName: 'Nguyễn Thị Lan',
  account: { id: '00000000-0000-7000-8000-000000000002', kind: 'USER', capabilities: capabilities(), theme: 'LIGHT' },
}

/**
 * A signed-in `USER` with GitHub linked. No account has a role (Story 20.6): a test gives the user their role in
 * the Jobs it opens with {@link inEveryJob}, and their other relations with the handlers it answers.
 */
export const signedInUser: Session = {
  status: 'SIGNED_IN',
  privacyNoticeVersion: '2.0', selfMentoredJobs: false,
  githubLogin: 'octo-cat',
  account: {
    id: '00000000-0000-7000-8000-000000000001',
    kind: 'USER',
    capabilities: capabilities({ githubVerified: true }),
    theme: 'LIGHT',
  },
}

/** A signed-in Administrator's account, told by its kind (Story 20.1), without the Administrator session view. */
export const administratorAccount: NonNullable<Session['account']> = {
  id: '00000000-0000-7000-8000-00000000a001',
  kind: 'ADMINISTRATOR',
  capabilities: capabilities(),
  theme: 'LIGHT',
}

/**
 * The user's role in every Job the test opens (AD-43, Story 20.6): what `GET /api/v1/jobs/{jobId}/role` answers,
 * which picks each Job screen's variant and JOB-H's "Bạn là: …". A Crew member is `ACTIVE` unless `crewKind` says
 * otherwise. Spread it into {@link fakeBackend}'s handlers; without it, every Job answers `NOT_FOUND`.
 */
export function inEveryJob(
  role: 'CUSTOMER' | 'MENTOR' | 'CUSTOMER_MENTOR' | 'CREW',
  crewKind: 'ASSIGNED' | 'ACTIVE' | 'FORMER' = 'ACTIVE',
): Record<string, Handler> {
  return { 'GET /api/v1/jobs/*/role': () => json(role === 'CREW' ? { role, crewKind } : { role }) }
}

/** One Job of "Công việc" with the user's role in it, as `GET /api/v1/my-jobs` lists it (Story 20.6). */
export interface HeldJob {
  jobId: string
  title: string
  role: 'CUSTOMER' | 'MENTOR' | 'CUSTOMER_MENTOR' | 'CREW'
  crewKind?: 'ASSIGNED' | 'ACTIVE' | 'FORMER'
  state?: string
}

/** "Công việc" (`GET /api/v1/my-jobs`) listing these Jobs, each on "Sổ đặt bàn Quán Mây" and Active by default. */
export function heldJobs(...jobs: HeldJob[]): Record<string, Handler> {
  return {
    'GET /api/v1/my-jobs': () =>
      json(
        jobs.map((job) => ({
          productName: 'Sổ đặt bàn Quán Mây',
          state: 'ACTIVE',
          updatedAt: '2026-10-20T03:00:00Z',
          ...job,
        })),
      ),
  }
}

/**
 * ACC-2's GitHub row (Story 19.2): by default a session with a GitHub username is linked, with nothing
 * keeping the link, and any other is not linked.
 */
export function gitHubLink(session: Session, overrides: Record<string, unknown> = {}): Record<string, unknown> {
  const linked = session.githubLogin !== undefined
  return {
    linked,
    ...(linked ? { login: session.githubLogin, linkedAt: '2026-10-20T03:00:00Z' } : {}),
    blockers: [],
    noticeVersion: '1.1',
    ...overrides,
  }
}

/** ACC-1's read (Story 19.3): by default an empty profile with nothing verified. */
export function profileView(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    kind: 'INDIVIDUAL',
    skills: [],
    links: [],
    repositories: [],
    hideStudentDetails: false,
    verified: {},
    ...overrides,
  }
}

export function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' },
  })
}

/** The published `vibefix.yml` as `GET /api/v1/products/workflow-file` serves it (Story 23.5): a short stand-in. */
export const workflowFile = {
  path: '.github/workflows/vibefix.yml',
  content: 'name: vibefix\non:\n  workflow_dispatch:\n',
  blob: '0123456789abcdef0123456789abcdef01234567',
}

/**
 * Replaces `fetch`, and the XHR that `uploadWithProgress` sends (Story 24.3c), with a backend that answers
 * `GET /api/v1/session` with `session` and any other `METHOD /path` from `handlers`, where a `*` in a handler's path
 * stands for one path segment; the to-dos, notifications, Jobs held (`/my-jobs`), the Customer's own Jobs (`/jobs`),
 * bids and Crew Applications are empty, GitHub is as {@link gitHubLink} says, the profile is {@link profileView}'s,
 * the published workflow is {@link workflowFile}, and the verification is `UNVERIFIED` unless a handler answers them, and an unknown request
 * answers 404. Both answer from the same handlers and record their requests in the one list it returns. An XHR
 * reports, once sent, half its files' bytes as uploaded, all of them once its handler answers, and then loads; the
 * upload reports only when a progress listener was there at `send`, and headers are taken only between `open` and
 * `send` (`InvalidStateError` otherwise). A handler that rejects is a network failure (`error`), and an XHR aborted
 * before its answer drops it (`abort`).
 */
export function fakeBackend(session: Session, handlers: Record<string, Handler> = {}): Request[] {
  const seen: Request[] = []
  const answer = async (request: Request): Promise<Response> => {
    const key = `${request.method} ${new URL(request.url, 'http://localhost').pathname}`
    const handler = handlers[key] ?? matching(handlers, key)
    if (handler) {
      return handler(request)
    }
    if (key === 'GET /api/v1/session') {
      return json(session)
    }
    if (key === 'GET /api/v1/environment') {
      return json({ name: 'dev' })
    }
    // A signed-in account with nothing to do and nothing new, unless the test says otherwise.
    if (key === 'GET /api/v1/todos') {
      return json({ items: [], total: 0 })
    }
    if (key === 'GET /api/v1/notifications/unread-count') {
      return json({ count: 0 })
    }
    if (
      key === 'GET /api/v1/my-jobs' ||
      key === 'GET /api/v1/jobs' ||
      key === 'GET /api/v1/mentor/applications' ||
      key === 'GET /api/v1/mentor/crew-applications'
    ) {
      return json([])
    }
    if (key === 'GET /api/v1/notifications') {
      return json({ items: [], page: 0, size: 20, total: 0 })
    }
    if (key === 'GET /api/v1/identity/github') {
      return json(gitHubLink(session))
    }
    if (key === 'GET /api/v1/profile') {
      return json(profileView())
    }
    if (key === 'GET /api/v1/products/workflow-file') {
      return json(workflowFile)
    }
    // A Student who has not verified yet.
    if (key === 'GET /api/v1/verification') {
      return json({ status: 'UNVERIFIED' })
    }
    return json({ status: 404, code: 'NOT_FOUND' }, 404)
  }
  vi.stubGlobal(
    'fetch',
    vi.fn(async (request: Request) => {
      seen.push(request)
      return answer(request)
    }),
  )
  vi.stubGlobal('XMLHttpRequest', fakeXhr(answer, seen))
  return seen
}

type EventName = 'progress' | 'load' | 'error' | 'abort' | 'loadend'

/** What the fake's events carry: what an upload's progress listener reads. */
interface FakeEvent {
  type: EventName
  lengthComputable: boolean
  loaded: number
  total: number
}

type Listener = (event: FakeEvent) => void

/** The listeners and `on{event}` handlers that an XHR and its `upload` both take. */
class FakeEvents {
  onprogress: Listener | null = null
  onload: Listener | null = null
  onerror: Listener | null = null
  onabort: Listener | null = null
  onloadend: Listener | null = null
  private listeners: { type: string; listener: Listener; once: boolean }[] = []

  addEventListener(type: string, listener: Listener, options?: boolean | AddEventListenerOptions) {
    const once = typeof options === 'object' && options.once === true
    this.listeners.push({ type, listener, once })
  }

  removeEventListener(type: string, listener: Listener) {
    this.listeners = this.listeners.filter((entry) => entry.type !== type || entry.listener !== listener)
  }

  /** Whether anything listens for `type` now, as a listener or as `on{type}`. */
  listens(type: EventName): boolean {
    return this[`on${type}`] !== null || this.listeners.some((entry) => entry.type === type)
  }

  fire(type: EventName, loaded = 0, total = 0) {
    const event: FakeEvent = { type, lengthComputable: total > 0, loaded, total }
    this[`on${type}`]?.(event)
    for (const entry of this.listeners.filter((candidate) => candidate.type === type)) {
      if (entry.once) {
        this.removeEventListener(type, entry.listener)
      }
      entry.listener(event)
    }
  }
}

/** The XHR stand-in: what `uploadWithProgress` calls, answered by `answer` and recorded in `seen`. */
function fakeXhr(answer: (request: Request) => Promise<Response>, seen: Request[]) {
  return class FakeXhr extends FakeEvents {
    readonly upload = new FakeEvents()
    readyState = 0
    status = 0
    responseText = ''
    private method = 'GET'
    private url = ''
    private readonly headers = new Headers()
    private answered: Headers | undefined
    private sent = false
    private aborted = false
    // As a browser does, the upload reports only to a progress listener that was there at `send`.
    private reports = false

    open(method: string, url: string | URL) {
      this.method = method
      this.url = new URL(url, window.location.origin).href
      this.readyState = 1
    }

    setRequestHeader(name: string, value: string) {
      if (this.readyState !== 1 || this.sent) {
        throw new DOMException('setRequestHeader needs an opened request that is not sent', 'InvalidStateError')
      }
      this.headers.set(name, value)
    }

    getResponseHeader(name: string): string | null {
      return this.answered?.get(name) ?? null
    }

    send(body: BodyInit | null = null) {
      const request = new Request(this.url, { method: this.method, headers: this.headers, body })
      // Node's `Request` cannot read jsdom's files back, so a handler reads the form as it was sent.
      if (body instanceof FormData) {
        Object.defineProperty(request, 'formData', { value: async () => body })
      }
      seen.push(request)
      this.sent = true
      this.reports = this.upload.listens('progress')
      void this.respond(request, fileBytes(body))
    }

    abort() {
      if (!this.sent || this.aborted || this.readyState === 4) {
        return
      }
      this.aborted = true
      this.readyState = 4
      if (this.reports) {
        this.upload.fire('abort')
      }
      this.fire('abort')
      this.fire('loadend')
    }

    private async respond(request: Request, total: number) {
      // A browser's events come after `send` returns.
      await Promise.resolve()
      if (this.aborted) {
        return
      }
      if (this.reports) {
        this.upload.fire('progress', Math.floor(total / 2), total)
      }
      let response: Response
      try {
        response = await answer(request)
      } catch {
        if (!this.aborted) {
          this.readyState = 4
          this.fire('error')
          this.fire('loadend')
        }
        return
      }
      if (this.aborted) {
        return
      }
      if (this.reports) {
        this.upload.fire('progress', total, total)
      }
      const text = await response.text()
      if (this.aborted) {
        return
      }
      this.status = response.status
      this.responseText = text
      this.answered = response.headers
      this.readyState = 4
      this.fire('load')
      this.fire('loadend')
    }
  }
}

/** The summed sizes of the files a multipart body carries. */
function fileBytes(body: BodyInit | null): number {
  let total = 0
  if (body instanceof FormData) {
    body.forEach((value) => {
      total += typeof value === 'string' ? 0 : value.size
    })
  }
  return total
}

/** The handler whose key has `*` segments matching `key`, if any. */
function matching(handlers: Record<string, Handler>, key: string): Handler | undefined {
  const found = Object.keys(handlers).find((pattern) => {
    if (!pattern.includes('*')) {
      return false
    }
    const parts = pattern.split('*').map((part) => part.replace(/[.+?^${}()|[\]\\]/g, '\\$&'))
    return new RegExp(`^${parts.join('[^/]+')}$`).test(key)
  })
  return found === undefined ? undefined : handlers[found]
}
