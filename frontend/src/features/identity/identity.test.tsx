import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { routes } from '@/app/routes'
import { anonymous, fakeBackend, json, signedIn } from '@/test/fakeBackend'
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

describe('route guards (AD-19, FR-74)', () => {
  it('sends an anonymous visitor to PUB-2, and the GitHub button returns to the route asked for', async () => {
    fakeBackend(anonymous)
    const router = renderAt('/viec-can-lam')

    expect(await screen.findByRole('heading', { level: 1, name: 'Đăng nhập vibefix' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/dang-nhap')
    expect(screen.getByRole('link', { name: 'Đăng nhập với GitHub' })).toHaveAttribute(
      'href',
      '/oauth2/authorization/github?quay-lai=%2Fviec-can-lam',
    )
  })

  it('sends a first sign-in still choosing its role to PUB-3', async () => {
    fakeBackend({ status: 'SIGNING_UP', githubLogin: 'octo-cat', privacyNoticeVersion: '2.0' })
    const router = renderAt('/viec-can-lam')

    expect(await screen.findByRole('heading', { level: 1, name: /hãy chọn cách bạn dùng vibefix/ })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/bat-dau')
  })

  it('keeps a Mentor that is not approved on MEN-5, with no other destination', async () => {
    fakeBackend(signedIn('MENTOR', 'PENDING'))
    const router = renderAt('/viec-can-lam')

    expect(await screen.findByRole('heading', { level: 1, name: 'Hồ sơ người hướng dẫn' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/ho-so')
    expect(screen.getByText('Đang chờ duyệt')).toBeInTheDocument()
    const nav = screen.getByRole('navigation', { name: 'Điều hướng chính' })
    expect(within(nav).getAllByRole('link').map((link) => link.textContent)).toEqual(['Hồ sơ người hướng dẫn'])
  })

  it('shows each role its own profile on /ho-so', async () => {
    fakeBackend(signedIn('CUSTOMER'), {
      'GET /api/v1/profile': () => json({ role: 'CUSTOMER', githubLogin: 'octo-cat', customer: null, student: null }),
    })
    renderAt('/ho-so')

    expect(await screen.findByRole('heading', { level: 1, name: 'Hồ sơ của bạn' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Hồ sơ người hướng dẫn' })).not.toBeInTheDocument()
  })

  it('sends a signed-in account away from PUB-2 and PUB-3 to where it lands', async () => {
    fakeBackend(signedIn('CUSTOMER'))
    const router = renderAt('/dang-nhap')

    expect(await screen.findByRole('heading', { level: 1, name: 'Việc cần làm' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/viec-can-lam')
  })

  it('offers "Vào trang của bạn" in the public header when signed in', async () => {
    fakeBackend(signedIn('STUDENT'))
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
    ['chua-thiet-lap', 'Đăng nhập GitHub chưa được thiết lập trên máy chủ này.'],
    ['that-bai', 'Chưa đăng nhập được. Hãy thử lại.'],
  ])('explains ?loi=%s', async (code, message) => {
    fakeBackend(anonymous)
    renderAt(`/dang-nhap?loi=${code}`)

    expect(await screen.findByRole('alert')).toHaveTextContent(message)
  })
})

describe('PUB-3 choose a role (FR-1, NFR-9)', () => {
  it('needs a role and the accepted notice, then sends the server-named version and lands', async () => {
    const user = userEvent.setup()
    document.cookie = 'XSRF-TOKEN=token-1'
    const seen = fakeBackend(
      { status: 'SIGNING_UP', githubLogin: 'octo-cat', privacyNoticeVersion: '2.0' },
      { 'POST /api/v1/session/sign-up': () => json(signedIn('STUDENT'), 201) },
    )
    const router = renderAt('/bat-dau')

    const submit = await screen.findByRole('button', { name: 'Tiếp tục' })
    expect(screen.getByText(/Vai trò gắn cố định với tài khoản octo-cat/)).toBeInTheDocument()
    expect(screen.getByText('Phiên bản 2.0 · 18/10/2026')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Đọc toàn văn thông báo quyền riêng tư' })).toHaveAttribute('target', '_blank')
    expect(submit).toBeDisabled()
    await user.click(screen.getByRole('radio', { name: /Sinh viên/ }))
    expect(submit).toBeDisabled()
    await user.click(screen.getByRole('checkbox', { name: /phiên bản 2.0/ }))
    await user.click(submit)

    await waitFor(() => expect(router.state.location.pathname).toBe('/viec-can-lam'))
    const request = seen.find((entry) => entry.method === 'POST')!
    expect(await request.clone().json()).toEqual({ role: 'STUDENT', privacyNoticeVersion: '2.0' })
    expect(request.headers.get('X-XSRF-TOKEN')).toBe('token-1')
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT'
  })

  it('says so when the notice changed meanwhile, and asks to accept the new version', async () => {
    const user = userEvent.setup()
    let version = '2.0'
    fakeBackend(anonymous, {
      'GET /api/v1/session': () => json({ status: 'SIGNING_UP', githubLogin: 'octo-cat', privacyNoticeVersion: version }),
      'POST /api/v1/session/sign-up': () => {
        version = '2.1'
        return json({ status: 409, code: 'STALE' }, 409)
      },
    })
    renderAt('/bat-dau')

    await user.click(await screen.findByRole('radio', { name: /Khách hàng/ }))
    await user.click(screen.getByRole('checkbox'))
    await user.click(screen.getByRole('button', { name: 'Tiếp tục' }))

    expect(await screen.findByRole('alert')).toBeInTheDocument()
    const checkbox = await screen.findByRole('checkbox', { name: /phiên bản 2.1/ })
    expect(checkbox).not.toBeChecked()
    expect(screen.getByRole('button', { name: 'Tiếp tục' })).toBeDisabled()
  })
})

describe('signed-in shell (UX-DR10 to UX-DR12, UX-DR26)', () => {
  it('shows the role destinations in both navigations and signs out from the avatar menu', async () => {
    const user = userEvent.setup()
    const seen = fakeBackend(signedIn('CUSTOMER'), {
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
    fakeBackend(signedIn('CUSTOMER'), {
      'POST /api/v1/session/sign-out': () => json({ status: 401 }, 401),
    })
    renderAt('/viec-can-lam')

    await user.click(await screen.findByRole('button', { name: 'Tài khoản của octo-cat' }))
    await user.click(await screen.findByRole('menuitem', { name: 'Đăng xuất' }))

    const dialog = await screen.findByRole('dialog', { name: 'Phiên đăng nhập đã hết' })
    expect(within(dialog).getByRole('link', { name: 'Đăng nhập lại' })).toHaveAttribute(
      'href',
      '/oauth2/authorization/github?quay-lai=%2Fviec-can-lam',
    )
    // The screen stays behind the modal dialog, hidden from assistive technology meanwhile.
    expect(screen.getByRole('heading', { level: 1, name: 'Việc cần làm', hidden: true })).toBeInTheDocument()
  })

  it('injects no style element at run time with the menu and the dialog open', async () => {
    const user = userEvent.setup()
    fakeBackend(signedIn('CUSTOMER'), {
      'POST /api/v1/session/sign-out': () => json({ status: 401 }, 401),
    })
    renderAt('/viec-can-lam')

    await user.click(await screen.findByRole('button', { name: 'Tài khoản của octo-cat' }))
    await user.click(await screen.findByRole('menuitem', { name: 'Đăng xuất' }))
    await screen.findByRole('dialog')

    expect(document.querySelectorAll('style')).toHaveLength(0)
  })
})
