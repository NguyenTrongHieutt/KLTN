import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { routes } from '@/app/routes'
import { RegionState, STILL_LOADING_MS } from '@/components/RegionState'
import { failuresInARow, MAX_BACKOFF_MS, pollEvery } from '@/config/polling'
import { sessionExpiry } from '@/features/identity/sessionExpiry'
import { fakeBackend, json, signedIn } from '@/test/fakeBackend'

// Notification types join vi.json with the stories that send them; these stand in for them here.
vi.mock('@/i18n/vi.json', async (importOriginal) => {
  const original = await importOriginal<{ default: Record<string, unknown> }>()
  return {
    default: {
      ...original.default,
      notification: {
        test: {
          update: { title: 'Có tin mới cho {name}', body: 'Mở để xem chi tiết.' },
          info: { title: 'Thông tin mới' },
        },
      },
    },
  }
})

function renderAt(path: string) {
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  render(
    <QueryClientProvider client={createQueryClient()}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return router
}

const todo = (key: string, extra: Record<string, unknown> = {}) => ({
  id: crypto.randomUUID(),
  key,
  tier: 3,
  params: {},
  link: '/ho-so',
  deadlineNear: false,
  openedAt: '2026-10-19T00:00:00Z',
  ...extra,
})

const notification = (type: string, extra: Record<string, unknown> = {}) => ({
  id: crypto.randomUUID(),
  type,
  icon: 'bell',
  params: {},
  link: '/viec-can-lam',
  createdAt: '2026-10-19T07:05:00Z',
  read: false,
  ...extra,
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.useRealTimers()
  sessionExpiry.dismiss()
})

describe('the next-action strip and the to-do list (FR-28, UX-DR14)', () => {
  it('shows the most urgent to-do with its deadline, its button, and the others', async () => {
    fakeBackend(signedIn('STUDENT'), {
      'GET /api/v1/todos': () =>
        json({
          items: [
            todo('identity.complete_mentor_application', {
              tier: 1,
              deadlineAt: '2026-10-20T10:00:00Z',
              deadlineNear: true,
              link: '/ho-so',
            }),
            todo('identity.verify_student'),
          ],
          total: 2,
        }),
    })
    renderAt('/viec-can-lam')

    const strip = await screen.findByRole('region', { name: 'Việc cần làm tiếp theo' })
    expect(strip).toHaveTextContent('Hoàn thành hồ sơ người hướng dẫn')
    expect(strip).toHaveTextContent('Hạn 20/10')
    expect(within(strip).getByRole('link', { name: 'Làm ngay' })).toHaveAttribute('href', '/ho-so')
    expect(within(strip).getByRole('link', { name: 'và 1 việc khác' })).toHaveAttribute('href', '/viec-can-lam')

    const list = screen.getByRole('list', { name: 'Việc cần làm của bạn' })
    expect(within(list).getAllByRole('link').map((link) => link.textContent)).toEqual([
      expect.stringContaining('Hoàn thành hồ sơ người hướng dẫn'),
      expect.stringContaining('Xác minh là sinh viên để vào nhóm của người hướng dẫn'),
    ])
    const tab = within(screen.getByRole('navigation', { name: 'Điều hướng nhanh' })).getByRole('link', {
      name: /Việc cần làm/,
    })
    expect(tab).toHaveTextContent('2')
  })

  it('hides the strip and shows the Empty state when nothing is open', async () => {
    fakeBackend(signedIn('CUSTOMER'))
    renderAt('/viec-can-lam')

    expect(await screen.findByText('Bạn không có việc nào cần làm.')).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Việc cần làm tiếp theo' })).not.toBeInTheDocument()
  })

  it('keeps the strip for a Mentor who is not approved, without the bell', async () => {
    fakeBackend(signedIn('MENTOR', 'PENDING'), {
      'GET /api/v1/todos': () => json({ items: [todo('identity.complete_mentor_application', { tier: 1 })], total: 1 }),
    })
    renderAt('/ho-so/nguoi-huong-dan')

    expect(await screen.findByRole('region', { name: 'Việc cần làm tiếp theo' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /^Thông báo/ })).not.toBeInTheDocument()
  })
})

describe('notifications (FR-48, UX-DR11, UX-DR16)', () => {
  it('ALL-1 lists them with title, body, time and unread dot, and opening one marks it read', async () => {
    const user = userEvent.setup()
    let unread = 1
    const first = notification('test.update', { params: { name: 'Lan' }, link: '/ho-so' })
    const seen = fakeBackend(signedIn('STUDENT'), {
      'GET /api/v1/notifications': () =>
        json({ items: [first, notification('test.info', { read: true, icon: 'info' })], page: 0, size: 20, total: 2 }),
      'GET /api/v1/notifications/unread-count': () => json({ count: unread }),
      [`POST /api/v1/notifications/${first.id}/read`]: () => {
        unread = 0
        return new Response(null, { status: 204 })
      },
    })
    const router = renderAt('/thong-bao')

    const item = await screen.findByRole('button', { name: /Có tin mới cho Lan/ })
    expect(item).toHaveTextContent('Mở để xem chi tiết.')
    expect(item).toHaveTextContent('14:05 19/10')
    expect(item).toHaveTextContent('Chưa đọc')
    expect(screen.getByRole('button', { name: /Thông tin mới/ })).not.toHaveTextContent('Chưa đọc')
    expect(await screen.findByRole('link', { name: 'Thông báo, 1 chưa đọc' })).toHaveAttribute('href', '/thong-bao')

    await user.click(item)

    await waitFor(() => expect(router.state.location.pathname).toBe('/ho-so'))
    expect(seen.some((request) => request.method === 'POST' && request.url.endsWith(`/${first.id}/read`))).toBe(true)
    // The successful command refreshed the unread count.
    await waitFor(() => expect(screen.queryByRole('link', { name: 'Thông báo, 1 chưa đọc' })).not.toBeInTheDocument())
  })

  it('offers "Đánh dấu đã đọc tất cả" only while something is unread', async () => {
    const user = userEvent.setup()
    let unread = 3
    fakeBackend(signedIn('CUSTOMER'), {
      'GET /api/v1/notifications/unread-count': () => json({ count: unread }),
      'POST /api/v1/notifications/read-all': () => {
        unread = 0
        return new Response(null, { status: 204 })
      },
    })
    renderAt('/thong-bao')

    expect(await screen.findByText('Chưa có thông báo nào.')).toBeInTheDocument()
    const markAll = await screen.findByRole('button', { name: 'Đánh dấu đã đọc tất cả' })
    await waitFor(() => expect(markAll).toBeEnabled())
    await user.click(markAll)

    await waitFor(() => expect(markAll).toBeDisabled())
  })

  it('the bell opens a popover of the latest on desktop', async () => {
    const user = userEvent.setup()
    vi.stubGlobal('matchMedia', (query: string) => ({
      matches: query === '(min-width: 768px)',
      media: query,
      addEventListener: () => {},
      removeEventListener: () => {},
    }))
    fakeBackend(signedIn('CUSTOMER'), {
      'GET /api/v1/notifications': () => json({ items: [notification('test.info')], page: 0, size: 10, total: 1 }),
      'GET /api/v1/notifications/unread-count': () => json({ count: 1 }),
    })
    renderAt('/viec-can-lam')

    await user.click(await screen.findByRole('button', { name: 'Thông báo, 1 chưa đọc' }))

    expect(await screen.findByText('Thông báo mới')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Thông tin mới/ })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Xem tất cả thông báo' })).toHaveAttribute('href', '/thong-bao')
    expect(document.querySelectorAll('style')).toHaveLength(0)
  })
})

describe('offline and loading states (UX-DR26, UX-DR18)', () => {
  it('a failing poll shows the offline line and disables commands', async () => {
    fakeBackend(signedIn('CUSTOMER'), {
      'GET /api/v1/todos': () => json({ status: 503 }, 503),
      'GET /api/v1/notifications/unread-count': () => json({ count: 2 }),
    })
    renderAt('/thong-bao')

    expect(await screen.findByText('Mất kết nối · đang thử lại')).toBeInTheDocument()
    expect(await screen.findByRole('button', { name: 'Đánh dấu đã đọc tất cả' })).toBeDisabled()
  })

  it('a poll answered 401 opens the session-expired dialog, not the offline line', async () => {
    fakeBackend(signedIn('CUSTOMER'), {
      'GET /api/v1/todos': () => json({ status: 401 }, 401),
    })
    renderAt('/viec-can-lam')

    expect(await screen.findByRole('dialog', { name: 'Phiên đăng nhập đã hết' })).toBeInTheDocument()
    expect(screen.queryByText('Mất kết nối · đang thử lại')).not.toBeInTheDocument()
  })

  it('a region still loading after 10 s says so and offers Retry; a failed one shows its own error', () => {
    vi.useFakeTimers()
    const refetch = vi.fn()
    const { rerender } = render(
      <RegionState query={{ isPending: true, isError: false, error: null, refetch }} skeleton={<p>bộ khung</p>}>
        <p>nội dung</p>
      </RegionState>,
    )
    expect(screen.getByText('bộ khung')).toBeInTheDocument()
    expect(screen.queryByText('Vẫn đang tải…')).not.toBeInTheDocument()

    act(() => vi.advanceTimersByTime(STILL_LOADING_MS))
    expect(screen.getByText('Vẫn đang tải…')).toBeInTheDocument()
    act(() => screen.getByRole('button', { name: 'Thử lại' }).click())
    expect(refetch).toHaveBeenCalledOnce()

    rerender(
      <RegionState query={{ isPending: false, isError: true, error: new Error('x'), refetch }} skeleton={<p>bộ khung</p>}>
        <p>nội dung</p>
      </RegionState>,
    )
    expect(screen.getByRole('alert')).toHaveTextContent('Đã có lỗi. Hãy thử lại sau ít phút.')
  })

  it('polls back off, doubling after each failure in a row, to at most 5 minutes', () => {
    const query = { state: { dataUpdateCount: 1, errorUpdateCount: 0, status: 'success' } }
    const every = pollEvery(60_000)
    expect(every(query)).toBe(60_000)
    query.state = { dataUpdateCount: 1, errorUpdateCount: 1, status: 'error' }
    expect(every(query)).toBe(120_000)
    query.state = { dataUpdateCount: 1, errorUpdateCount: 2, status: 'error' }
    expect(every(query)).toBe(240_000)
    query.state = { dataUpdateCount: 1, errorUpdateCount: 5, status: 'error' }
    expect(every(query)).toBe(MAX_BACKOFF_MS)
    query.state = { dataUpdateCount: 2, errorUpdateCount: 5, status: 'success' }
    expect(failuresInARow(query)).toBe(0)
    expect(every(query)).toBe(60_000)
    query.state = { dataUpdateCount: 2, errorUpdateCount: 6, status: 'error' }
    expect(failuresInARow(query)).toBe(1)
  })
})
