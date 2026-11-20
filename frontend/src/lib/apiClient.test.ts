import { afterEach, describe, expect, it, vi } from 'vitest'
import { anonymous, fakeBackend, json } from '@/test/fakeBackend'
import { ApiError, apiClient, readCookie, unwrap, uploadWithProgress } from './apiClient'

describe('apiClient (AD-19, AD-23)', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT'
  })

  it('sends the CSRF cookie back in X-XSRF-TOKEN on a command, not on a query', async () => {
    document.cookie = 'XSRF-TOKEN=abc-123'
    const seen: Request[] = []
    vi.stubGlobal(
      'fetch',
      vi.fn(async (request: Request) => {
        seen.push(request)
        return new Response(null, { status: 204 })
      }),
    )

    await apiClient.GET('/api/v1/session')
    await apiClient.POST('/api/v1/session/sign-out')

    expect(seen[0].headers.get('X-XSRF-TOKEN')).toBeNull()
    expect(seen[1].headers.get('X-XSRF-TOKEN')).toBe('abc-123')
  })

  it('leaves out the null fields the backend writes, as the generated types say', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(
        async () =>
          new Response(
            JSON.stringify({
              status: 'ANONYMOUS',
              account: null,
              nested: { kept: 0, gone: null, list: [{ a: null, b: '' }, null] },
            }),
            { status: 200, headers: { 'Content-Type': 'application/json' } },
          ),
      ),
    )

    const data: unknown = await unwrap(apiClient.GET('/api/v1/session'))

    expect(data).toStrictEqual({ status: 'ANONYMOUS', nested: { kept: 0, list: [{ b: '' }, null] } })
  })

  it('reads one cookie by name', () => {
    document.cookie = 'XSRF-TOKEN=x%2Fy'

    expect(readCookie('XSRF-TOKEN')).toBe('x/y')
    expect(readCookie('NOPE')).toBeUndefined()
  })
})

describe('uploadWithProgress (AD-19, AD-23, UX-DR45)', () => {
  const PATH = '/api/v1/boards/j1/handovers/screenshots'

  afterEach(() => {
    vi.unstubAllGlobals()
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT'
  })

  const form = (bytes = 1000) => {
    const body = new FormData()
    body.append('file', new File([new Uint8Array(bytes)], 'dat-ban.png', { type: 'image/png' }))
    return body
  }

  it('posts the form with the CSRF cookie in X-XSRF-TOKEN and asks for JSON', async () => {
    document.cookie = 'XSRF-TOKEN=abc-123'
    const seen = fakeBackend(anonymous, { [`POST ${PATH}`]: () => json({ fileId: 'f1' }, 201) })

    await uploadWithProgress(PATH, form())

    expect(seen).toHaveLength(1)
    expect(seen[0].method).toBe('POST')
    expect(new URL(seen[0].url).pathname).toBe(PATH)
    expect(seen[0].headers.get('X-XSRF-TOKEN')).toBe('abc-123')
    expect(seen[0].headers.get('Accept')).toBe('application/json')
  })

  it('leaves out the null fields of the answer, as unwrap does', async () => {
    fakeBackend(anonymous, {
      [`POST ${PATH}`]: () => json({ fileId: 'f1', note: null, nested: { kept: 0, gone: null, list: [{ a: null }, null] } }, 201),
    })

    const data: unknown = await uploadWithProgress(PATH, form())

    expect(data).toStrictEqual({ fileId: 'f1', nested: { kept: 0, list: [{}, null] } })
  })

  it('rejects a refusal with its Problem Details and Retry-After, and an answer that is not JSON with none', async () => {
    fakeBackend(anonymous, {
      [`POST ${PATH}`]: () =>
        new Response(JSON.stringify({ status: 400, code: 'VALIDATION', reason: 'TOO_MANY_SCREENSHOTS' }), {
          status: 400,
          headers: { 'Content-Type': 'application/problem+json', 'Retry-After': '30' },
        }),
      'POST /api/v1/proxy': () => new Response('<html>Bad gateway</html>', { status: 502, headers: { 'Content-Type': 'text/html' } }),
    })

    const refusal = await uploadWithProgress(PATH, form()).catch((error: unknown) => error)
    const proxy = await uploadWithProgress('/api/v1/proxy', form()).catch((error: unknown) => error)

    expect(refusal).toBeInstanceOf(ApiError)
    expect(refusal).toMatchObject({ status: 400, retryAfter: 30, problem: { code: 'VALIDATION', reason: 'TOO_MANY_SCREENSHOTS' } })
    expect(proxy).toBeInstanceOf(ApiError)
    expect(proxy).toMatchObject({ status: 502, problem: {}, retryAfter: undefined })
  })

  it('resolves an empty answer to undefined', async () => {
    fakeBackend(anonymous, { [`POST ${PATH}`]: () => new Response(null, { status: 201 }) })

    await expect(uploadWithProgress(PATH, form())).resolves.toBeUndefined()
  })

  it('reports the bytes sent as the upload goes', async () => {
    fakeBackend(anonymous, { [`POST ${PATH}`]: () => json({ fileId: 'f1' }, 201) })
    const onProgress = vi.fn()

    await uploadWithProgress(PATH, form(1000), { onProgress })

    expect(onProgress.mock.calls).toEqual([
      [500, 1000],
      [1000, 1000],
    ])
  })

  it('rejects with an AbortError once its signal aborts, and sends nothing for a signal aborted already', async () => {
    const seen = fakeBackend(anonymous, { [`POST ${PATH}`]: () => new Promise<Response>(() => undefined) })
    const controller = new AbortController()

    const upload = uploadWithProgress(PATH, form(), { signal: controller.signal }).catch((error: unknown) => error)
    controller.abort()
    const error = await upload

    expect(error).toBeInstanceOf(DOMException)
    expect(error).toHaveProperty('name', 'AbortError')
    expect(seen).toHaveLength(1)
    const late = await uploadWithProgress(PATH, form(), { signal: controller.signal }).catch((reason: unknown) => reason)
    expect(late).toHaveProperty('name', 'AbortError')
    expect(seen).toHaveLength(1)
  })

  it('rejects a failure without an answer with a plain Error, not an ApiError', async () => {
    fakeBackend(anonymous, {
      [`POST ${PATH}`]: () => {
        throw new TypeError('Failed to fetch')
      },
    })

    const error = await uploadWithProgress(PATH, form()).catch((reason: unknown) => reason)

    expect(error).toBeInstanceOf(Error)
    expect(error).not.toBeInstanceOf(ApiError)
    expect(error).not.toBeInstanceOf(DOMException)
  })
})
