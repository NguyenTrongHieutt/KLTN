import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { routes } from '@/app/routes'
import type { Session } from '@/features/identity/api'
import { sessionExpiry } from '@/features/identity/sessionExpiry'
import { anonymous, fakeBackend, json, signedIn } from '@/test/fakeBackend'

function renderAt(path: string) {
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  render(
    <QueryClientProvider client={createQueryClient()}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return router
}

const minutes = (n: number) => new Date(Date.now() + n * 60_000).toISOString()

function administrator(idleIn = 30, absoluteIn = 480): Session {
  return {
    status: 'SIGNED_IN',
    privacyNoticeVersion: '2.0',
    account: { id: '00000000-0000-7000-8000-00000000a001', role: 'ADMINISTRATOR', theme: 'LIGHT' },
    administrator: {
      email: 'ha.phuong@vibefix.vn',
      displayName: 'Hà Phương',
      idleExpiresAt: minutes(idleIn),
      absoluteExpiresAt: minutes(absoluteIn),
    },
  }
}

const overview = (failed = 1, stalled = 0) => () =>
  json({
    failedOperations: { count: failed, ...(failed ? { oldestAt: '2026-10-16T01:00:00Z' } : {}) },
    stalledEvents: { count: stalled, ...(stalled ? { oldestAt: '2026-10-16T04:20:00Z' } : {}) },
  })

const refusal = (status: number, code: string, reason: string, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify({ status, code, reason }), {
    status,
    headers: { 'Content-Type': 'application/problem+json', ...headers },
  })

afterEach(() => {
  vi.unstubAllGlobals()
  sessionExpiry.dismiss()
})

describe('PUB-6 Administrator sign-in (FR-2, AD-19)', () => {
  it('asks for the email, the password, and the 6-digit code, and signs in to ADM-1', async () => {
    const user = userEvent.setup()
    let session: Session = anonymous
    const seen = fakeBackend(anonymous, {
      'GET /api/v1/session': () => json(session),
      'POST /api/v1/admin/sign-in': () => {
        session = administrator()
        return new Response(null, { status: 204 })
      },
      'GET /api/v1/admin/overview': overview(0),
    })
    const router = renderAt('/quan-tri/dang-nhap')

    expect(await screen.findByRole('heading', { level: 1, name: 'Đăng nhập quản trị' })).toBeInTheDocument()
    const code = screen.getByLabelText('Mã xác thực 6 số')
    expect(code).toHaveAttribute('inputmode', 'numeric')
    expect(code).toHaveAttribute('autocomplete', 'one-time-code')
    await user.type(screen.getByLabelText('Email'), 'ha.phuong@vibefix.vn')
    await user.type(screen.getByLabelText('Mật khẩu'), 'dung-mat-khau-dai-12')
    await user.type(code, '482107')
    await user.click(screen.getByRole('button', { name: 'Đăng nhập' }))

    await waitFor(() => expect(router.state.location.pathname).toBe('/quan-tri'))
    expect(await screen.findByRole('heading', { level: 1, name: 'Tổng quan' })).toBeInTheDocument()
    const request = seen.find((entry) => entry.url.endsWith('/api/v1/admin/sign-in'))!
    expect(await request.clone().json()).toEqual({
      email: 'ha.phuong@vibefix.vn',
      password: 'dung-mat-khau-dai-12',
      code: '482107',
    })
  })

  it('says only that something is wrong, and clears the code', async () => {
    const user = userEvent.setup()
    fakeBackend(anonymous, {
      'POST /api/v1/admin/sign-in': () => refusal(422, 'GUARD_FAILED', 'ADMIN_SIGN_IN_FAILED'),
    })
    renderAt('/quan-tri/dang-nhap')

    await user.type(await screen.findByLabelText('Email'), 'ha.phuong@vibefix.vn')
    await user.type(screen.getByLabelText('Mật khẩu'), 'sai')
    await user.type(screen.getByLabelText('Mã xác thực 6 số'), '123456')
    await user.click(screen.getByRole('button', { name: 'Đăng nhập' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Email, mật khẩu hoặc mã xác thực chưa đúng.')
    expect(screen.getByLabelText('Mã xác thực 6 số')).toHaveValue('')
  })

  it('says the sign-in is locked for 15 minutes after 5 failures', async () => {
    const user = userEvent.setup()
    fakeBackend(anonymous, {
      'POST /api/v1/admin/sign-in': () =>
        refusal(429, 'RATE_LIMITED', 'ADMIN_SIGN_IN_LOCKED', { 'Retry-After': '900' }),
    })
    renderAt('/quan-tri/dang-nhap')

    await user.type(await screen.findByLabelText('Email'), 'ha.phuong@vibefix.vn')
    await user.type(screen.getByLabelText('Mật khẩu'), 'sai')
    await user.type(screen.getByLabelText('Mã xác thực 6 số'), '123456')
    await user.click(screen.getByRole('button', { name: 'Đăng nhập' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Tạm khóa đăng nhập 15 phút')
    expect(alert).toHaveTextContent('Các quản trị viên khác đã được báo.')
  })

  it('is linked from no public page', async () => {
    fakeBackend(anonymous)
    renderAt('/')

    await screen.findByRole('heading', { level: 1 })
    const links = screen.getAllByRole('link').map((link) => link.getAttribute('href') ?? '')
    expect(links.filter((href) => href.startsWith('/quan-tri'))).toEqual([])
  })
})

describe('the Admin shell (UX-DR13, SG-10)', () => {
  it('lists the built destinations, ADM-1 counts, and the alert list', async () => {
    fakeBackend(administrator(), {
      'GET /api/v1/admin/overview': overview(1, 3),
      'GET /api/v1/notifications': () =>
        json({
          items: [
            {
              id: 'n1',
              type: 'identity.admin_sign_in_locked',
              params: { email: 'tuan@vibefix.vn' },
              link: '/quan-tri',
              icon: 'alert',
              read: false,
              createdAt: '2026-10-16T01:12:00Z',
            },
          ],
          page: 0,
          size: 10,
          total: 1,
        }),
    })
    renderAt('/quan-tri')

    expect(await screen.findByRole('heading', { level: 1, name: 'Tổng quan' })).toBeInTheDocument()
    const sidebar = screen.getAllByRole('navigation', { name: 'Quản trị' })[0]
    expect(within(sidebar).getByRole('link', { name: 'Tổng quan' })).toHaveAttribute('aria-current', 'page')
    expect(await screen.findByText('Thao tác lỗi')).toBeInTheDocument()
    expect(screen.getByText('Cũ nhất: 16/10')).toBeInTheDocument()
    expect(await screen.findByText('Đăng nhập quản trị bị tạm khóa 15 phút: tuan@vibefix.vn (5 lần thử sai)')).toBeInTheDocument()
    expect(screen.getByText('Bảo mật')).toBeInTheDocument()
    expect(screen.getByText('3 sự kiện chưa gửi được sau 5 lần thử')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Tài khoản của Hà Phương' })).toHaveTextContent('HP')
  })

  it('opens the destinations as a sheet from the menu button', async () => {
    const user = userEvent.setup()
    fakeBackend(administrator(), { 'GET /api/v1/admin/overview': overview(0) })
    renderAt('/quan-tri')

    await user.click(await screen.findByRole('button', { name: 'Mở menu quản trị' }))
    const sheet = await screen.findByRole('dialog', { name: 'Quản trị' })
    expect(within(sheet).getByRole('link', { name: 'Tổng quan' })).toBeInTheDocument()
  })

  it('sends anyone not signed in to PUB-6, shows other roles nothing, and keeps Administrators out of the user shell', async () => {
    fakeBackend(anonymous)
    const router = renderAt('/quan-tri')
    await waitFor(() => expect(router.state.location.pathname).toBe('/quan-tri/dang-nhap'))
  })

  it('shows a Customer the not-found screen', async () => {
    fakeBackend(signedIn('CUSTOMER'))
    renderAt('/quan-tri')
    expect(await screen.findByRole('heading', { level: 1, name: /Không tìm thấy/ })).toBeInTheDocument()
  })

  it('sends an Administrator from the user shell to ADM-1', async () => {
    fakeBackend(administrator(), { 'GET /api/v1/admin/overview': overview(0) })
    const router = renderAt('/viec-can-lam')
    await waitFor(() => expect(router.state.location.pathname).toBe('/quan-tri'))
  })

  it('warns 2 minutes before the idle limit, and "Tiếp tục phiên" extends it', async () => {
    const user = userEvent.setup()
    let session = administrator(1)
    const seen = fakeBackend(administrator(1), {
      'GET /api/v1/session': () => json(session),
      'GET /api/v1/admin/overview': overview(0),
      'POST /api/v1/session/activity': () => {
        session = administrator(30)
        return json(session)
      },
    })
    renderAt('/quan-tri')

    const dialog = await screen.findByRole('dialog', { name: 'Phiên làm việc sắp hết' })
    expect(dialog).toHaveTextContent('Việc tự làm mới danh sách không giữ phiên.')
    await user.click(within(dialog).getByRole('button', { name: 'Tiếp tục phiên' }))

    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Phiên làm việc sắp hết' })).not.toBeInTheDocument())
    expect(seen.some((request) => request.method === 'POST' && request.url.endsWith('/api/v1/session/activity'))).toBe(true)
  })

  it('warns before the absolute limit without offering to continue', async () => {
    fakeBackend(administrator(30, 1), { 'GET /api/v1/admin/overview': overview(0) })
    renderAt('/quan-tri')

    const dialog = await screen.findByRole('dialog', { name: 'Phiên làm việc sắp hết' })
    expect(dialog).toHaveTextContent('gần đủ 8 giờ')
    expect(within(dialog).queryByRole('button', { name: 'Tiếp tục phiên' })).not.toBeInTheDocument()
  })

  it('sends a session that ended back to PUB-6 from the expired dialog', async () => {
    fakeBackend(administrator(), {
      'GET /api/v1/admin/overview': () => json({ status: 401 }, 401),
    })
    renderAt('/quan-tri')

    const dialog = await screen.findByRole('dialog', { name: 'Phiên đăng nhập đã hết' })
    expect(within(dialog).getByRole('link', { name: 'Đăng nhập lại' })).toHaveAttribute('href', '/quan-tri/dang-nhap')
  })
})
