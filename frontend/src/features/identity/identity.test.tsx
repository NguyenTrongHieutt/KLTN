import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { routes } from '@/app/routes'
import { anonymous, fakeBackend, json, signedInUser, signedInWithGoogle, welcome } from '@/test/fakeBackend'
import { sessionExpiry } from './sessionExpiry'

function renderAt(path: string) {
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  render(
    <QueryClientProvider client={createQueryClient()}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return router
}

afterEach(() => {
  vi.unstubAllGlobals()
  sessionExpiry.dismiss()
})

describe('route guards (AD-19)', () => {
  it('sends an anonymous visitor to PUB-2, and the Google button returns to the route asked for', async () => {
    fakeBackend(anonymous)
    const router = renderAt('/viec-can-lam')

    expect(await screen.findByRole('heading', { level: 1, name: 'Đăng nhập vibefix' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/dang-nhap')
    expect(screen.getByRole('link', { name: 'Đăng nhập bằng Google' })).toHaveAttribute(
      'href',
      '/oauth2/authorization/google?quay-lai=%2Fviec-can-lam',
    )
    expect(screen.queryByRole('link', { name: /GitHub/ })).not.toBeInTheDocument()
  })

  it('sends a Google sign-in that has not accepted N-10 to PUB-3 from any route', async () => {
    fakeBackend(welcome)
    const router = renderAt('/viec-can-lam')

    expect(await screen.findByRole('heading', { level: 1, name: 'Chào mừng Nguyễn Thị Lan' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/bat-dau')
  })

  it.each([
    ['a Google account with nothing linked', signedInWithGoogle],
    ['a user with GitHub linked', signedInUser],
  ])('lands %s on the to-do list with the same five destinations (UX-DR85, Story 20.6)', async (_, session) => {
    fakeBackend(session)
    const router = renderAt('/dang-nhap')

    expect(await screen.findByRole('heading', { level: 1, name: 'Việc cần làm' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/viec-can-lam')
    for (const name of ['Điều hướng chính', 'Điều hướng nhanh']) {
      const nav = screen.getByRole('navigation', { name })
      expect(within(nav).getAllByRole('link').map((link) => link.textContent)).toEqual([
        'Việc cần làm',
        'Công việc',
        'Tìm việc',
        'Sản phẩm',
        'Thông báo',
      ])
    }
    // No mode switcher, and no "Nhóm": the Team endpoints answer nothing since Story 20.3.
    expect(screen.queryByRole('link', { name: 'Nhóm' })).not.toBeInTheDocument()
  })

  it('names the avatar by the Google name until GitHub is linked', async () => {
    fakeBackend(signedInWithGoogle)
    renderAt('/viec-can-lam')

    expect(await screen.findByRole('button', { name: 'Tài khoản của Nguyễn Thị Lan' })).toHaveTextContent('TL')
  })

  it('keeps four tab-bar cells under 360 px, "Thông báo" going into the bell (UX-DR85)', async () => {
    vi.stubGlobal(
      'matchMedia',
      (query: string) =>
        ({
          matches: query === '(max-width: 359px)',
          media: query,
          addEventListener: () => {},
          removeEventListener: () => {},
        }) as unknown as MediaQueryList,
    )
    fakeBackend(signedInUser)
    renderAt('/viec-can-lam')

    const tabs = await screen.findByRole('navigation', { name: 'Điều hướng nhanh' })
    expect(within(tabs).getAllByRole('link').map((link) => link.textContent)).toEqual([
      'Việc cần làm',
      'Công việc',
      'Tìm việc',
      'Sản phẩm',
    ])
    const nav = screen.getByRole('navigation', { name: 'Điều hướng chính' })
    const bell = screen.getAllByRole('link', { name: 'Thông báo' }).filter((link) => !nav.contains(link))
    expect(bell).toHaveLength(1)
    expect(bell[0]).toHaveAttribute('href', '/thong-bao')
  })

  it("leads the identity.link_github to-do to ACC-2's GitHub row (C-8, Story 19.2)", { timeout: 15_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(signedInWithGoogle, {
      'GET /api/v1/todos': () =>
        json({
          items: [
            {
              id: '00000000-0000-7000-8000-0000000000aa',
              key: 'identity.link_github',
              tier: 1,
              params: {},
              link: '/ho-so/xac-minh',
              deadlineNear: false,
              openedAt: '2026-10-25T00:00:00Z',
            },
          ],
          total: 1,
        }),
    })
    const router = renderAt('/viec-can-lam')

    const list = await screen.findByRole('list', { name: 'Việc cần làm của bạn' })
    await user.click(within(list).getByRole('link', { name: /Liên kết tài khoản GitHub/ }))

    expect(await screen.findByRole('heading', { level: 1, name: 'Hồ sơ và xác minh' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/ho-so/xac-minh')
    expect(await screen.findByRole('region', { name: 'GitHub' })).toHaveTextContent('Chưa liên kết')
  })

  it('answers the retired Mentor application route with the not-found screen (Story 19.7)', async () => {
    fakeBackend(signedInUser)
    const router = renderAt('/ho-so/nguoi-huong-dan')

    expect(await screen.findByRole('heading', { level: 1, name: /Không tìm thấy/ })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/ho-so/nguoi-huong-dan')
  })

  it.each([
    ['a user with GitHub linked', signedInUser],
    ['a Google account with nothing linked', signedInWithGoogle],
  ])('shows %s the one profile on /ho-so', async (_, session) => {
    fakeBackend(session)
    renderAt('/ho-so')

    expect(await screen.findByRole('heading', { level: 1, name: 'Hồ sơ và xác minh' })).toBeInTheDocument()
    expect(await screen.findByRole('heading', { level: 2, name: 'Bạn tự nhập' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 2, name: 'Đã xác minh' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Hồ sơ người hướng dẫn' })).not.toBeInTheDocument()
  })

  it.each([
    ['a Google account with nothing linked', signedInWithGoogle],
    ['a user with GitHub linked', signedInUser],
  ])('gives %s the one avatar menu with every item listed (UX-DR85)', async (_, session) => {
    const user = userEvent.setup()
    fakeBackend(session)
    renderAt('/viec-can-lam')

    await user.click(await screen.findByRole('button', { name: /^Tài khoản của / }))
    await screen.findByRole('menuitem', { name: 'Đăng xuất' })
    expect(screen.getAllByRole('menuitem').map((item) => item.textContent)).toEqual([
      'Hồ sơ và xác minh',
      'Hồ sơ công khai',
      'Số dư thử nghiệm',
      'Lịch sử thanh toán',
      'Dữ liệu của tôi',
      'Đăng xuất',
    ])
    expect(screen.getByText('Giao diện')).toBeInTheDocument()
    expect(screen.getAllByRole('menuitemradio').map((item) => item.textContent)).toEqual([
      'Sáng',
      'Tối',
      'Theo hệ thống',
    ])
  })

  it('opens the public profile by the GitHub username from "Hồ sơ công khai"', async () => {
    fakeBackend(signedInUser)
    const router = renderAt('/ho-so/cong-khai')

    await waitFor(() => expect(router.state.location.pathname).toBe('/sv/octo-cat'))
  })

  it('shows the Empty state and the GitHub gate on "Hồ sơ công khai" without GitHub', async () => {
    fakeBackend(signedInWithGoogle)
    renderAt('/ho-so/cong-khai')

    expect(await screen.findByRole('heading', { level: 1, name: 'Hồ sơ công khai' })).toBeInTheDocument()
    expect(screen.getByText('Bạn chưa có hồ sơ công khai.')).toBeInTheDocument()
    expect(await screen.findByRole('heading', { name: 'Liên kết GitHub để có hồ sơ công khai' })).toBeInTheDocument()
  })

  it('sends a signed-in account away from PUB-2 and PUB-3 to where it lands', async () => {
    fakeBackend(signedInUser)
    const router = renderAt('/dang-nhap')

    expect(await screen.findByRole('heading', { level: 1, name: 'Việc cần làm' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/viec-can-lam')
  })

  it('offers "Vào trang của bạn" in the public header when signed in', async () => {
    fakeBackend(signedInUser)
    renderAt('/quyen-rieng-tu')

    expect(await screen.findByRole('link', { name: 'Vào trang của bạn' })).toHaveAttribute('href', '/viec-can-lam')
    expect(screen.queryByRole('link', { name: 'Đăng nhập' })).not.toBeInTheDocument()
  })
})

describe('PUB-2 sign in (FR-1, FR-59)', () => {
  it('tells a locked account why and whom to contact', async () => {
    fakeBackend({
      status: 'LOCKED',
      lockReason: 'Vi phạm quy định',
      supportEmail: 'hotro@vibefix.vn',
      privacyNoticeVersion: '2.0',
    })
    renderAt('/dang-nhap')

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Tài khoản của bạn đã bị khóa')
    expect(alert).toHaveTextContent('Lý do: Vi phạm quy định')
    expect(alert).toHaveTextContent('Liên hệ hotro@vibefix.vn')
  })

  it.each([
    ['chua-thiet-lap', 'Đăng nhập Google chưa được thiết lập trên máy chủ này.'],
    ['that-bai', 'Chưa đăng nhập được. Hãy thử lại.'],
    [
      'email-chua-xac-minh',
      'Google chưa xác minh địa chỉ email của tài khoản này. Hãy xác minh email trong tài khoản Google, rồi đăng nhập lại.',
    ],
  ])('explains ?loi=%s', async (code, message) => {
    fakeBackend(anonymous)
    renderAt(`/dang-nhap?loi=${code}`)

    expect(await screen.findByRole('alert')).toHaveTextContent(message)
  })
})

describe('PUB-3 "Chào mừng" (FR-1, NFR-9, UX-DR89)', () => {
  it('greets by the Google name with no role to choose, needs the notice, sends the server-named version, and lands', async () => {
    const user = userEvent.setup()
    document.cookie = 'XSRF-TOKEN=token-1'
    const seen = fakeBackend(welcome, { 'POST /api/v1/session/sign-up': () => json(signedInWithGoogle, 201) })
    const router = renderAt('/bat-dau')

    const submit = await screen.findByRole('button', { name: 'Đồng ý và bắt đầu' })
    expect(screen.getByRole('heading', { level: 1, name: 'Chào mừng Nguyễn Thị Lan' })).toBeInTheDocument()
    expect(document.title).toBe('Chào mừng · vibefix')
    for (const line of [
      'Đăng sản phẩm và tin công việc để nhận hỗ trợ.',
      'Chào giá để làm người hướng dẫn cho công việc của người khác.',
      'Xin vào nhóm thực hiện của một người hướng dẫn.',
    ]) {
      expect(screen.getByText(line)).toBeInTheDocument()
    }
    expect(screen.queryByRole('radio')).not.toBeInTheDocument()
    expect(screen.getByText('Phiên bản 3.1 · 27/10/2026')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Đọc toàn văn thông báo quyền riêng tư' })).toHaveAttribute('target', '_blank')
    expect(submit).toBeDisabled()
    await user.click(screen.getByRole('checkbox', { name: /phiên bản 3.1/ }))
    await user.click(submit)

    await waitFor(() => expect(router.state.location.pathname).toBe('/viec-can-lam'))
    const request = seen.find((entry) => entry.method === 'POST')!
    expect(await request.clone().json()).toEqual({ privacyNoticeVersion: '3.1' })
    expect(request.headers.get('X-XSRF-TOKEN')).toBe('token-1')
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT'
  })

  it('says so when the notice changed meanwhile, and asks to accept the new version', async () => {
    const user = userEvent.setup()
    let version = '3.1'
    fakeBackend(anonymous, {
      'GET /api/v1/session': () => json({ ...welcome, privacyNoticeVersion: version }),
      'POST /api/v1/session/sign-up': () => {
        version = '3.2'
        return json({ status: 409, code: 'STALE' }, 409)
      },
    })
    renderAt('/bat-dau')

    await user.click(await screen.findByRole('checkbox'))
    await user.click(screen.getByRole('button', { name: 'Đồng ý và bắt đầu' }))

    expect(await screen.findByRole('alert')).toBeInTheDocument()
    const checkbox = await screen.findByRole('checkbox', { name: /phiên bản 3.2/ })
    expect(checkbox).not.toBeChecked()
    expect(screen.getByRole('button', { name: 'Đồng ý và bắt đầu' })).toBeDisabled()
  })

  it('sends a visitor with no pending sign-in on /bat-dau to PUB-2', async () => {
    fakeBackend(anonymous)
    const router = renderAt('/bat-dau')

    expect(await screen.findByRole('heading', { level: 1, name: 'Đăng nhập vibefix' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/dang-nhap')
  })
})

describe('signed-in shell (UX-DR10 to UX-DR12, UX-DR26)', () => {
  it('shows the destinations in both navigations and signs out from the avatar menu', async () => {
    const user = userEvent.setup()
    const seen = fakeBackend(signedInUser, {
      'POST /api/v1/session/sign-out': () => new Response(null, { status: 204 }),
    })
    const router = renderAt('/viec-can-lam')

    await screen.findByRole('heading', { level: 1, name: 'Việc cần làm' })
    for (const name of ['Điều hướng chính', 'Điều hướng nhanh']) {
      const nav = screen.getByRole('navigation', { name })
      expect(within(nav).getByRole('link', { name: 'Việc cần làm' })).toHaveAttribute('aria-current', 'page')
    }
    await user.click(screen.getByRole('button', { name: 'Tài khoản của octo-cat' }))
    await user.click(await screen.findByRole('menuitem', { name: 'Đăng xuất' }))

    await waitFor(() => expect(router.state.location.pathname).toBe('/'))
    expect(seen.some((request) => request.method === 'POST' && request.url.endsWith('/api/v1/session/sign-out'))).toBe(true)
  })

  it('a command that finds the session gone opens the dialog, which signs in back to this route', async () => {
    const user = userEvent.setup()
    fakeBackend(signedInUser, {
      'POST /api/v1/session/sign-out': () => json({ status: 401 }, 401),
    })
    renderAt('/viec-can-lam')

    await user.click(await screen.findByRole('button', { name: 'Tài khoản của octo-cat' }))
    await user.click(await screen.findByRole('menuitem', { name: 'Đăng xuất' }))

    const dialog = await screen.findByRole('dialog', { name: 'Phiên đăng nhập đã hết' })
    expect(within(dialog).getByRole('link', { name: 'Đăng nhập lại' })).toHaveAttribute(
      'href',
      '/oauth2/authorization/google?quay-lai=%2Fviec-can-lam',
    )
    // The screen stays behind the modal dialog, hidden from assistive technology meanwhile.
    expect(screen.getByRole('heading', { level: 1, name: 'Việc cần làm', hidden: true })).toBeInTheDocument()
  })

  it('injects no style element at run time with the menu and the dialog open', async () => {
    const user = userEvent.setup()
    fakeBackend(signedInUser, {
      'POST /api/v1/session/sign-out': () => json({ status: 401 }, 401),
    })
    renderAt('/viec-can-lam')

    await user.click(await screen.findByRole('button', { name: 'Tài khoản của octo-cat' }))
    await user.click(await screen.findByRole('menuitem', { name: 'Đăng xuất' }))
    await screen.findByRole('dialog')

    expect(document.querySelectorAll('style')).toHaveLength(0)
  })
})
