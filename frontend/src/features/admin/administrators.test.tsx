import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { routes } from '@/app/routes'
import type { Session } from '@/features/identity/api'
import { sessionExpiry } from '@/features/identity/sessionExpiry'
import { anonymous, fakeBackend, json } from '@/test/fakeBackend'

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

const administrator: Session = {
  status: 'SIGNED_IN',
  privacyNoticeVersion: '2.0',
  account: { id: '00000000-0000-7000-8000-00000000a001', role: 'ADMINISTRATOR', theme: 'LIGHT' },
  administrator: {
    email: 'ha.phuong@vibefix.vn',
    displayName: 'Hà Phương',
    idleExpiresAt: minutes(30),
    absoluteExpiresAt: minutes(480),
  },
}

const list = () =>
  json([
    {
      id: 'a1',
      email: 'ha.phuong@vibefix.vn',
      displayName: 'Hà Phương',
      status: 'ACTIVE',
      createdAt: '2026-06-01T02:00:00Z',
      signedInAt: '2026-10-17T01:30:00Z',
      you: true,
    },
    {
      id: 'a2',
      email: 'tuan@vibefix.vn',
      displayName: 'Đỗ Văn Tuấn',
      status: 'ACTIVE',
      createdAt: '2026-06-01T02:00:00Z',
      lockedUntil: '2026-10-17T01:27:00Z',
      you: false,
    },
    {
      id: 'a3',
      email: 'lam@vibefix.vn',
      displayName: 'Nguyễn Lâm',
      status: 'ENROLLING',
      createdAt: '2026-10-15T02:00:00Z',
      you: false,
    },
  ])

const refusal = (status: number, body: Record<string, unknown>) =>
  new Response(JSON.stringify({ status, ...body }), { status, headers: { 'Content-Type': 'application/problem+json' } })

afterEach(() => {
  vi.unstubAllGlobals()
  sessionExpiry.dismiss()
})

describe('ADM-11 Administrators (FR-2, SG-10)', () => {
  it('lists each Administrator with the two-factor state and the last sign-in or lock', async () => {
    fakeBackend(administrator, { 'GET /api/v1/admin/administrators': list })
    renderAt('/quan-tri/quan-tri-vien')

    const table = await screen.findByRole('table', { name: 'Danh sách quản trị viên' })
    const rows = within(table).getAllByRole('row')
    expect(rows[1]).toHaveTextContent('Hà Phương (bạn)')
    expect(rows[1]).toHaveTextContent('Đã bật')
    expect(rows[2]).toHaveTextContent('Tạm khóa đến 08:27')
    expect(rows[3]).toHaveTextContent('Chưa thiết lập')
    expect(rows[3]).toHaveTextContent('Chưa đăng nhập')
    const nav = screen.getAllByRole('navigation', { name: 'Quản trị' })[0]
    expect(within(nav).getByRole('link', { name: 'Quản trị viên' })).toHaveAttribute('aria-current', 'page')
  })

  it('creates one after the confirmation and a step-up, showing the attempts left on a wrong code', async () => {
    const user = userEvent.setup()
    let tries = 0
    const seen = fakeBackend(administrator, {
      'GET /api/v1/admin/administrators': list,
      'POST /api/v1/admin/administrators': () => {
        tries += 1
        return tries === 1
          ? refusal(422, { code: 'GUARD_FAILED', reason: 'STEP_UP_FAILED', attemptsLeft: 4 })
          : json({ id: 'a4' }, 201)
      },
    })
    renderAt('/quan-tri/quan-tri-vien')

    await user.type(await screen.findByLabelText('Họ tên'), 'Vũ Minh Quân')
    await user.type(screen.getByLabelText('Email công việc'), 'quan@vibefix.vn')
    await user.click(screen.getByRole('button', { name: 'Tạo quản trị viên' }))

    const confirm = await screen.findByRole('dialog', { name: 'Tạo quản trị viên' })
    expect(confirm).toHaveTextContent('Mọi quản trị viên hiện có nhận cảnh báo về tài khoản mới.')
    expect(confirm).toHaveTextContent('quan@vibefix.vn')
    await user.click(within(confirm).getByRole('button', { name: 'Tiếp tục' }))

    const stepUp = await screen.findByRole('dialog', { name: 'Xác thực lại' })
    const code = within(stepUp).getByLabelText('Mã xác thực')
    expect(code).toHaveAttribute('autocomplete', 'one-time-code')
    await user.type(code, '111111')
    await user.click(within(stepUp).getByRole('button', { name: 'Tạo quản trị viên' }))
    expect(await within(stepUp).findByText('Mã chưa đúng. Bạn còn 4 lần thử.')).toBeInTheDocument()

    await user.type(within(stepUp).getByLabelText('Mã xác thực'), '482107')
    await user.click(within(stepUp).getByRole('button', { name: 'Tạo quản trị viên' }))

    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Xác thực lại' })).not.toBeInTheDocument())
    expect(await screen.findByText(/Đã tạo quản trị viên quan@vibefix.vn/)).toBeInTheDocument()
    const requests = seen.filter((request) => request.method === 'POST' && request.url.endsWith('/administrators'))
    expect(await requests[1].clone().json()).toEqual({
      displayName: 'Vũ Minh Quân',
      email: 'quan@vibefix.vn',
      stepUpCode: '482107',
    })
  })

  it('says an email already belongs to an Administrator', async () => {
    const user = userEvent.setup()
    fakeBackend(administrator, {
      'GET /api/v1/admin/administrators': list,
      'POST /api/v1/admin/administrators': () =>
        refusal(409, { code: 'CONFLICT', reason: 'ADMINISTRATOR_EMAIL_IN_USE' }),
    })
    renderAt('/quan-tri/quan-tri-vien')

    await user.type(await screen.findByLabelText('Họ tên'), 'Tuấn')
    await user.type(screen.getByLabelText('Email công việc'), 'tuan@vibefix.vn')
    await user.click(screen.getByRole('button', { name: 'Tạo quản trị viên' }))
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Tiếp tục' }))
    const stepUp = await screen.findByRole('dialog', { name: 'Xác thực lại' })
    await user.type(within(stepUp).getByLabelText('Mã xác thực'), '482107')
    await user.click(within(stepUp).getByRole('button', { name: 'Tạo quản trị viên' }))

    expect(await within(stepUp).findByText('Email này đã thuộc về một quản trị viên.')).toBeInTheDocument()
  })

  it('warns on ADM-1 while only one Administrator can act', async () => {
    fakeBackend(administrator, {
      'GET /api/v1/admin/overview': () =>
        json({ failedOperations: { count: 0 }, stalledEvents: { count: 0 }, activeAdministrators: 1 }),
    })
    renderAt('/quan-tri')

    expect(await screen.findByText(/Chỉ có một quản trị viên/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Tạo quản trị viên' })).toHaveAttribute('href', '/quan-tri/quan-tri-vien')
  })
})

describe('the enrolment link (FR-2, AD-19)', () => {
  const token = 'ab'.repeat(16)

  it('shows the authenticator secret, then sets the password and signs in', async () => {
    const user = userEvent.setup()
    let session: Session = anonymous
    const seen = fakeBackend(anonymous, {
      'GET /api/v1/session': () => json(session),
      [`GET /api/v1/admin/enrolment/${token}`]: () =>
        json({
          email: 'quan@vibefix.vn',
          displayName: 'Vũ Minh Quân',
          secret: 'JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP',
          otpauthUri: 'otpauth://totp/vibefix%3Aquan%40vibefix.vn?secret=x&issuer=vibefix',
        }),
      [`POST /api/v1/admin/enrolment/${token}`]: () => {
        session = { ...administrator, administrator: { ...administrator.administrator!, email: 'quan@vibefix.vn' } }
        return new Response(null, { status: 204 })
      },
      'GET /api/v1/admin/overview': () =>
        json({ failedOperations: { count: 0 }, stalledEvents: { count: 0 }, activeAdministrators: 2 }),
    })
    const router = renderAt(`/quan-tri/kich-hoat/${token}`)

    expect(await screen.findByText('JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Mở trong ứng dụng xác thực' })).toHaveAttribute(
      'href',
      expect.stringMatching(/^otpauth:\/\/totp\//),
    )
    await user.type(screen.getByLabelText('Mật khẩu'), 'mat-khau-dai-du-12')
    await user.type(screen.getByLabelText('Nhập lại mật khẩu'), 'mat-khau-khac-12')
    await user.type(screen.getByLabelText('Mã 6 số trong ứng dụng'), '482107')
    await user.click(screen.getByRole('button', { name: 'Hoàn tất' }))
    expect(await screen.findByText('Hai mật khẩu chưa giống nhau.')).toBeInTheDocument()
    expect(seen.some((request) => request.method === 'POST')).toBe(false)

    await user.clear(screen.getByLabelText('Nhập lại mật khẩu'))
    await user.type(screen.getByLabelText('Nhập lại mật khẩu'), 'mat-khau-dai-du-12')
    await user.click(screen.getByRole('button', { name: 'Hoàn tất' }))

    await waitFor(() => expect(router.state.location.pathname).toBe('/quan-tri'))
    const request = seen.find((entry) => entry.method === 'POST')!
    expect(await request.clone().json()).toEqual({ password: 'mat-khau-dai-du-12', code: '482107' })
  })

  it('says a used or lapsed link no longer works', async () => {
    fakeBackend(anonymous)
    renderAt(`/quan-tri/kich-hoat/${token}`)

    expect(await screen.findByRole('alert')).toHaveTextContent('Đường dẫn này không còn dùng được')
  })
})
