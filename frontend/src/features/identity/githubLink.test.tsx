import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { routes } from '@/app/routes'
import { redirectTo } from '@/lib/redirect'
import { fakeBackend, gitHubLink, json, signedInUser, signedInWithGoogle } from '@/test/fakeBackend'

vi.mock('@/lib/redirect', () => ({ redirectTo: vi.fn() }))

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
  vi.mocked(redirectTo).mockReset()
  window.sessionStorage.clear()
})

const AUTHORIZATION = 'https://github.com/login/oauth/authorize?client_id=dev&state=abc'

const linkedLan = { ...signedInWithGoogle, githubLogin: 'lan-dev' }

describe('ACC-2, the GitHub row (FR-80, UX-DR91)', () => {
  it('shows N-24 before leaving for GitHub, and the start names its version and the purpose', { timeout: 15_000 }, async () => {
    const user = userEvent.setup()
    let started: Record<string, unknown> | undefined
    fakeBackend(signedInWithGoogle, {
      'POST /api/v1/identity/github/link': async (request) => {
        started = await request.json()
        return json({ authorizationUrl: AUTHORIZATION })
      },
    })
    renderAt('/ho-so/xac-minh')

    expect(await screen.findByRole('heading', { level: 1, name: 'Hồ sơ và xác minh' })).toBeInTheDocument()
    expect(document.title).toBe('Xác minh · vibefix')
    const row = await screen.findByRole('region', { name: 'GitHub' })
    expect(within(row).getByText('Bắt buộc')).toBeInTheDocument()
    expect(within(row).getByText('Chưa liên kết')).toBeInTheDocument()
    expect(within(row).getByText('Bắt buộc để đăng sản phẩm, chào giá hoặc xin vào nhóm.')).toBeInTheDocument()
    await user.click(within(row).getByRole('button', { name: /Liên kết GitHub/ }))

    const notice = await screen.findByRole('dialog', { name: 'Trước khi liên kết GitHub' })
    expect(within(notice).getByText(/vibefix sẽ nhận từ GitHub tên tài khoản và mã số tài khoản của bạn/)).toBeInTheDocument()
    expect(within(notice).getByText('Lưu ý khi liên kết GitHub · phiên bản 1.0')).toBeInTheDocument()
    expect(started).toBeUndefined()
    await user.click(within(notice).getByRole('button', { name: /Liên kết GitHub ↗/ }))

    await waitFor(() => expect(redirectTo).toHaveBeenCalledWith(AUTHORIZATION))
    expect(started).toEqual({ noticeVersion: '1.0', purpose: 'PROFILE' })
  })

  it('shows a refused start in the notice and stays on vibefix', { timeout: 15_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(signedInWithGoogle, {
      'POST /api/v1/identity/github/link': () =>
        json({ status: 422, code: 'GUARD_FAILED', reason: 'GITHUB_NOT_SET_UP' }, 422),
    })
    renderAt('/ho-so/xac-minh')

    await user.click(await screen.findByRole('button', { name: /Liên kết GitHub/ }))
    const notice = await screen.findByRole('dialog', { name: 'Trước khi liên kết GitHub' })
    await user.click(within(notice).getByRole('button', { name: /Liên kết GitHub ↗/ }))

    expect(await within(notice).findByRole('alert')).toHaveTextContent('Môi trường này chưa thiết lập việc liên kết GitHub.')
    expect(redirectTo).not.toHaveBeenCalled()
  })

  it('shows the linked account with the month, and unlinks after the confirmation', { timeout: 15_000 }, async () => {
    const user = userEvent.setup()
    let unlinked = false
    fakeBackend(linkedLan, {
      'GET /api/v1/identity/github': () =>
        json(unlinked ? gitHubLink(signedInWithGoogle) : gitHubLink(linkedLan, { repositoryTokenKept: false })),
      'DELETE /api/v1/identity/github': () => {
        unlinked = true
        return new Response(null, { status: 204 })
      },
    })
    renderAt('/ho-so/xac-minh')

    expect(await screen.findByText('@lan-dev · Xác minh qua GitHub · 10/2026')).toBeInTheDocument()
    expect(screen.getByText('Đã liên kết')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Bỏ liên kết' }))
    const confirm = await screen.findByRole('dialog', { name: 'Bỏ liên kết GitHub?' })
    expect(within(confirm).getByText(/Dấu mã hóa dùng để chống gian lận vẫn được giữ/)).toBeInTheDocument()
    expect(unlinked).toBe(false)
    await user.click(within(confirm).getByRole('button', { name: 'Bỏ liên kết' }))

    await waitFor(() => expect(unlinked).toBe(true))
    expect(await screen.findAllByText('Đã bỏ liên kết GitHub.')).not.toHaveLength(0)
    expect(await screen.findByText('Chưa liên kết')).toBeInTheDocument()
  })

  it('disables "Bỏ liên kết" while something keeps the link, naming each (R14)', async () => {
    fakeBackend(linkedLan, {
      'GET /api/v1/identity/github': () =>
        json(
          gitHubLink(linkedLan, {
            blockers: [
              { module: 'product', reason: 'LIVE_PRODUCT', subjectId: 'p1', name: 'Sổ đặt bàn Quán Mây' },
              { module: 'job', reason: 'JOB_ROLE', subjectId: 'j1', name: 'Sửa lỗi đặt bàn' },
              { module: 'job', reason: 'MILESTONE_PENDING', subjectId: 'j3', name: 'Trang đặt món' },
              { module: 'matching', reason: 'OPEN_BID', subjectId: 'j2', name: '' },
            ],
          }),
        ),
    })
    renderAt('/ho-so/xac-minh')

    const unlink = await screen.findByRole('button', { name: 'Bỏ liên kết' })
    expect(unlink).toBeDisabled()
    expect(unlink).toHaveAccessibleDescription(/Bạn chưa bỏ liên kết GitHub được khi bạn đang có sản phẩm, có đơn chào giá đang mở/)
    expect(screen.getByText('Sản phẩm Sổ đặt bàn Quán Mây')).toBeInTheDocument()
    expect(screen.getByText('Công việc Sửa lỗi đặt bàn')).toBeInTheDocument()
    expect(screen.getByText('Cột mốc bạn đã tham gia chưa thanh toán xong · Trang đặt món')).toBeInTheDocument()
    expect(screen.getByText('Một đơn chào giá đang mở')).toBeInTheDocument()
  })

  it('asks a live Product\'s owner to re-link once the kept token stopped working', { timeout: 15_000 }, async () => {
    const user = userEvent.setup()
    let started: Record<string, unknown> | undefined
    fakeBackend(linkedLan, {
      'GET /api/v1/identity/github': () => json(gitHubLink(linkedLan, { relinkNeeded: true })),
      'POST /api/v1/identity/github/link': async (request) => {
        started = await request.json()
        return json({ authorizationUrl: AUTHORIZATION })
      },
    })
    renderAt('/ho-so/xac-minh')

    expect(await screen.findByText('Cần liên kết lại')).toBeInTheDocument()
    expect(screen.getByText('vibefix không còn đọc được GitHub của bạn. Hãy liên kết lại.')).toBeInTheDocument()
    expect(screen.getByText(/Bạn không mất dữ liệu nào/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /Liên kết lại GitHub/ }))
    const notice = await screen.findByRole('dialog', { name: 'Trước khi liên kết GitHub' })
    await user.click(within(notice).getByRole('button', { name: /Liên kết lại GitHub ↗/ }))

    await waitFor(() => expect(redirectTo).toHaveBeenCalledWith(AUTHORIZATION))
    expect(started).toEqual({ noticeVersion: '1.0', purpose: 'PROFILE' })
  })

  it('reads GitHub\'s return once, so a reload shows no banner', async () => {
    fakeBackend(linkedLan)
    const router = renderAt('/ho-so/xac-minh?ket-qua=da-lien-ket')

    expect(await screen.findByText('Đã liên kết GitHub.')).toBeInTheDocument()
    await waitFor(() => expect(router.state.location.search).toBe(''))
    expect(screen.getByText('Đã liên kết GitHub.')).toBeInTheDocument()
  })

  it.each([
    ['ket-qua=da-lien-ket', 'status', 'Đã liên kết GitHub.'],
    ['loi=da-lien-ket-tai-khoan-khac', 'alert', 'Tài khoản GitHub này đã liên kết với một tài khoản vibefix khác.'],
    ['loi=sai-tai-khoan', 'alert', 'Đây không phải tài khoản GitHub bạn đã liên kết.'],
    ['loi=that-bai', 'alert', 'Chưa liên kết được GitHub. Hãy thử lại.'],
    ['loi=khac', 'alert', 'Chưa liên kết được GitHub. Hãy thử lại.'],
  ])('says what GitHub\'s return led to (%s)', async (query, role, text) => {
    fakeBackend(linkedLan)
    renderAt(`/ho-so/xac-minh?${query}`)

    const banner = await screen.findByText(text, { exact: false })
    expect(banner.closest(`[role="${role}"]`)).not.toBeNull()
  })

  it('sends the old sign-in-again page to ACC-2', async () => {
    fakeBackend(signedInUser)
    const router = renderAt('/dang-nhap-lai')

    expect(await screen.findByRole('heading', { level: 1, name: 'Hồ sơ và xác minh' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/ho-so/xac-minh')
  })
})

describe('The GitHub gate on CUS-3 step 2 (UX-DR95)', () => {
  it('replaces the form until GitHub is linked, keeps the input, and links for the Product', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    let started: Record<string, unknown> | undefined
    fakeBackend(signedInUser, {
      'GET /api/v1/identity/github': () => json(gitHubLink(signedInWithGoogle)),
      'POST /api/v1/identity/github/link': async (request) => {
        started = await request.json()
        return json({ authorizationUrl: AUTHORIZATION })
      },
    })
    renderAt('/san-pham/moi')

    await user.click(await screen.findByRole('button', { name: 'Lovable' }))
    await user.click(screen.getByRole('button', { name: 'Tiếp tục' }))

    expect(await screen.findByText('Bước 2/4')).toBeInTheDocument()
    expect(await screen.findByRole('heading', { name: 'Liên kết GitHub để đăng ký sản phẩm' })).toBeInTheDocument()
    expect(screen.queryByLabelText('Đường dẫn kho mã trên GitHub')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /Liên kết GitHub ↗/ }))
    const notice = await screen.findByRole('dialog', { name: 'Trước khi liên kết GitHub' })
    await user.click(within(notice).getByRole('button', { name: /Liên kết GitHub ↗/ }))

    await waitFor(() => expect(redirectTo).toHaveBeenCalledWith(AUTHORIZATION))
    expect(started).toEqual({ noticeVersion: '1.0', purpose: 'PRODUCT', returnTo: '/san-pham/moi' })
    expect(JSON.parse(window.sessionStorage.getItem('vibefix.product.registration') ?? '{}')).toMatchObject({
      step: 2,
      tools: ['LOVABLE'],
    })
  })

  it.each([
    [403, 'FORBIDDEN', 'GITHUB_NOT_LINKED', 'Liên kết GitHub để đăng ký sản phẩm'],
    [422, 'GUARD_FAILED', 'RELINK_GITHUB', 'Liên kết lại GitHub để đăng ký sản phẩm'],
  ])('takes over again once the repository check answers %s %s %s', { timeout: 20_000 }, async (status, code, reason, heading) => {
    const user = userEvent.setup()
    let refused = false
    fakeBackend(signedInUser, {
      'GET /api/v1/identity/github': () =>
        json(refused && reason === 'GITHUB_NOT_LINKED' ? gitHubLink(signedInWithGoogle) : gitHubLink(signedInUser)),
      'POST /api/v1/products/repository-check': () => {
        refused = true
        return json({ status, code, reason }, status)
      },
    })
    renderAt('/san-pham/moi')

    await user.click(await screen.findByRole('button', { name: 'Lovable' }))
    await user.click(screen.getByRole('button', { name: 'Tiếp tục' }))
    await user.type(await screen.findByLabelText('Đường dẫn kho mã trên GitHub'), 'https://github.com/lan-nguyen/shop-gom')
    await user.click(screen.getByRole('button', { name: 'Kiểm tra' }))

    expect(await screen.findByRole('heading', { name: heading })).toBeInTheDocument()
    expect(screen.queryByLabelText('Đường dẫn kho mã trên GitHub')).not.toBeInTheDocument()
    expect(screen.getByText('Bước 2/4')).toBeInTheDocument()
  })

  it('links again when no token is kept, and comes back to step 2 with the input', { timeout: 15_000 }, async () => {
    window.sessionStorage.setItem(
      'vibefix.product.registration',
      JSON.stringify({
        step: 2,
        tools: ['LOVABLE'],
        url: 'https://github.com/lan-nguyen/shop-gom',
        values: { name: '', description: '', demoUrl: '' },
      }),
    )
    let kept = false
    fakeBackend(signedInUser, {
      'GET /api/v1/identity/github': () => json(gitHubLink(signedInUser, { repositoryTokenKept: kept })),
    })
    renderAt('/san-pham/moi')

    expect(await screen.findByRole('heading', { name: 'Liên kết lại GitHub để đăng ký sản phẩm' })).toBeInTheDocument()
    expect(screen.getByText('Bước 2/4')).toBeInTheDocument()
    expect(window.sessionStorage.getItem('vibefix.product.registration')).toBeNull()

    cleanup()
    kept = true
    window.sessionStorage.setItem(
      'vibefix.product.registration',
      JSON.stringify({
        step: 2,
        tools: ['LOVABLE'],
        url: 'https://github.com/lan-nguyen/shop-gom',
        values: { name: '', description: '', demoUrl: '' },
      }),
    )
    renderAt('/san-pham/moi')

    expect(await screen.findByLabelText('Đường dẫn kho mã trên GitHub')).toHaveValue('https://github.com/lan-nguyen/shop-gom')
  })
})
