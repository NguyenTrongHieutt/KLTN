import { afterEach, describe, expect, it, vi } from 'vitest'
import { apiClient, readCookie, unwrap } from './apiClient'

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
