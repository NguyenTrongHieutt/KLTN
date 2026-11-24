import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { routes } from '@/app/routes'
import { fakeBackend, heldJobs, json, signedInUser } from '@/test/fakeBackend'

function renderAt(path: string) {
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  render(
    <QueryClientProvider client={createQueryClient()}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return router
}

/** A window at least `width` px wide: every `(min-width: N px)` query up to it matches, and no `max-width` one. */
function windowOf(width: number) {
  vi.stubGlobal(
    'matchMedia',
    (query: string) =>
      ({
        matches: Number(/\(min-width: (\d+)px\)/.exec(query)?.[1] ?? Infinity) <= width,
        media: query,
        addEventListener: () => {},
        removeEventListener: () => {},
      }) as unknown as MediaQueryList,
  )
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('the signed-in shell from 768 px (the layout of 2026-11-23)', () => {
  it('searches Tìm việc from the top bar on Enter, where it stays the one search field', { timeout: 15_000 }, async () => {
    windowOf(768)
    const user = userEvent.setup()
    fakeBackend(signedInUser, { 'GET /api/v1/products': () => json([]) })
    const router = renderAt('/viec-can-lam')

    const input = await screen.findByRole('searchbox', { name: 'Tìm công việc trên vibefix' })
    expect(input).toHaveAttribute('maxLength', '100')
    await user.type(input, '  ban hang {Enter}')

    await waitFor(() => expect(router.state.location.pathname).toBe('/tim-viec'))
    expect(new URLSearchParams(router.state.location.search).get('q')).toBe('ban hang')
    // The layout of 2026-11-24: Tìm việc has no Search bar of its own from 768 px; the top bar's holds its keyword.
    expect(await screen.findByRole('heading', { level: 1, name: 'Tìm việc' })).toBeInTheDocument()
    await waitFor(() => expect(screen.getAllByRole('searchbox')).toHaveLength(1))
    expect(screen.getByRole('searchbox', { name: 'Tìm công việc trên vibefix' })).toHaveValue('ban hang')
  })

  it('on Tìm việc keeps the filters for a new keyword, starts at the first page, and clears the keyword', { timeout: 15_000 }, async () => {
    windowOf(768)
    const user = userEvent.setup()
    fakeBackend(signedInUser, { 'GET /api/v1/products': () => json([]) })
    const router = renderAt('/tim-viec?q=ban+hang&loai=BUG_FIX&trang=3')

    const input = await screen.findByRole('searchbox', { name: 'Tìm công việc trên vibefix' })
    expect(input).toHaveValue('ban hang')
    await user.clear(input)
    await user.type(input, 'supabase{Enter}')

    await waitFor(() => expect(new URLSearchParams(router.state.location.search).get('q')).toBe('supabase'))
    expect(new URLSearchParams(router.state.location.search).get('loai')).toBe('BUG_FIX')
    expect(new URLSearchParams(router.state.location.search).get('trang')).toBeNull()

    await user.click(await screen.findByRole('button', { name: 'Xóa từ khóa' }))
    await waitFor(() => expect(new URLSearchParams(router.state.location.search).get('q')).toBeNull())
    expect(new URLSearchParams(router.state.location.search).get('loai')).toBe('BUG_FIX')
    expect(screen.getByRole('searchbox', { name: 'Tìm công việc trên vibefix' })).toHaveValue('')
    expect(screen.queryByRole('button', { name: 'Xóa từ khóa' })).toBeNull()
  })

  it('does not print a destination\'s name above its page, since the navigation shows it selected', { timeout: 15_000 }, async () => {
    windowOf(768)
    fakeBackend(signedInUser, { 'GET /api/v1/products': () => json([]) })
    renderAt('/san-pham')

    // The h1 stays for a screen reader and for the focus after a route change.
    expect(await screen.findByRole('heading', { level: 1, name: 'Sản phẩm' })).toHaveClass('sr-only')
    expect(screen.queryByText(/Ứng dụng bạn đã làm bằng công cụ AI/)).toBeNull()
  })

  it('does not search an empty keyword', { timeout: 15_000 }, async () => {
    windowOf(768)
    const user = userEvent.setup()
    fakeBackend(signedInUser, { 'GET /api/v1/products': () => json([]) })
    const router = renderAt('/viec-can-lam')

    await user.type(await screen.findByRole('searchbox', { name: 'Tìm công việc trên vibefix' }), '   {Enter}')
    expect(router.state.location.pathname).toBe('/viec-can-lam')
  })

  it('"Tạo" posts a Job on the one Product, and registers a Product', { timeout: 15_000 }, async () => {
    windowOf(768)
    const user = userEvent.setup()
    fakeBackend(signedInUser, {
      'GET /api/v1/products': () => json([{ id: 'p1', name: 'Sổ đặt bàn Quán Mây', sourceStatus: 'READY' }]),
    })
    const router = renderAt('/thong-bao')

    await user.click(await screen.findByRole('button', { name: 'Tạo' }))
    expect((await screen.findAllByRole('menuitem')).map((item) => item.textContent)).toEqual([
      'Đăng công việc',
      'Đăng ký sản phẩm',
    ])
    await user.click(screen.getByRole('menuitem', { name: 'Đăng công việc' }))
    await waitFor(() => expect(router.state.location.pathname).toBe('/san-pham/p1/cong-viec-moi'))
  })

  it('"Tạo" sends a user with no Product, or several, to Sản phẩm to pick one', { timeout: 15_000 }, async () => {
    windowOf(768)
    const user = userEvent.setup()
    fakeBackend(signedInUser, { 'GET /api/v1/products': () => json([]) })
    const router = renderAt('/thong-bao')

    await user.click(await screen.findByRole('button', { name: 'Tạo' }))
    await user.click(await screen.findByRole('menuitem', { name: 'Đăng công việc' }))
    await waitFor(() => expect(router.state.location.pathname).toBe('/san-pham'))
  })

  it('keeps the sidebar to the four destinations until 1024 px', { timeout: 15_000 }, async () => {
    windowOf(768)
    fakeBackend(signedInUser, {
      'GET /api/v1/products': () => json([]),
      ...heldJobs({ jobId: 'a', title: 'Sửa lỗi thanh toán', role: 'CUSTOMER' }),
    })
    renderAt('/thong-bao')

    const sidebar = await screen.findByRole('complementary', { name: 'Thanh bên' })
    expect(within(sidebar).getAllByRole('navigation')).toHaveLength(1)
    expect(within(sidebar).getAllByRole('link').map((link) => link.textContent)).toEqual([
      'Trang chủ',
      'Công việc',
      'Tìm việc',
      'Sản phẩm',
    ])
  })

  it('lists the running Jobs and the account pages in the sidebar from 1024 px', { timeout: 15_000 }, async () => {
    windowOf(1024)
    fakeBackend(signedInUser, {
      'GET /api/v1/products': () => json([]),
      ...heldJobs(
        { jobId: 'a', title: 'Sửa lỗi thanh toán', role: 'CUSTOMER' },
        { jobId: 'b', title: 'Việc đã đóng', role: 'MENTOR', state: 'CLOSED' },
        { jobId: 'c', title: 'Việc đã hủy', role: 'CREW', state: 'CANCELLED' },
      ),
    })
    renderAt('/thong-bao')

    const running = await screen.findByRole('navigation', { name: 'Việc đang chạy' })
    const links = within(running).getAllByRole('link')
    expect(links).toHaveLength(1)
    expect(links[0]).toHaveTextContent('Sửa lỗi thanh toán')
    expect(links[0]).toHaveAttribute('href', '/cong-viec/a')
    const account = screen.getByRole('navigation', { name: 'Tài khoản' })
    expect(within(account).getAllByRole('link').map((link) => link.getAttribute('href'))).toEqual([
      '/so-du',
      '/lich-su-thanh-toan',
      '/ho-so',
    ])
  })

  it('shows no "Việc đang chạy" group when nothing runs', { timeout: 15_000 }, async () => {
    windowOf(1024)
    fakeBackend(signedInUser, { 'GET /api/v1/products': () => json([]) })
    renderAt('/thong-bao')

    await screen.findByRole('navigation', { name: 'Tài khoản' })
    expect(screen.queryByRole('navigation', { name: 'Việc đang chạy' })).toBeNull()
  })
})

describe('folding the sidebar from 1024 px', () => {
  afterEach(() => {
    window.localStorage.clear()
  })

  it('folds the column into the rail and back, and remembers the choice in this browser', { timeout: 15_000 }, async () => {
    windowOf(1024)
    const user = userEvent.setup()
    fakeBackend(signedInUser, { 'GET /api/v1/products': () => json([]) })
    renderAt('/thong-bao')

    const sidebar = await screen.findByRole('complementary', { name: 'Thanh bên' })
    await screen.findByRole('navigation', { name: 'Tài khoản' })
    const fold = screen.getByRole('button', { name: 'Thu nhỏ thanh bên' })
    expect(fold).toHaveAttribute('aria-expanded', 'true')
    expect(fold).toHaveAttribute('aria-controls', sidebar.id)

    await user.click(fold)
    expect(screen.queryByRole('navigation', { name: 'Tài khoản' })).toBeNull()
    expect(within(sidebar).getAllByRole('link').map((link) => link.textContent)).toEqual([
      'Trang chủ',
      'Công việc',
      'Tìm việc',
      'Sản phẩm',
    ])
    const unfold = screen.getByRole('button', { name: 'Mở rộng thanh bên' })
    expect(unfold).toHaveAttribute('aria-expanded', 'false')
    expect(window.localStorage.getItem('vibefix.sidebar.collapsed')).toBe('1')

    await user.click(unfold)
    expect(await screen.findByRole('navigation', { name: 'Tài khoản' })).toBeInTheDocument()
    expect(window.localStorage.getItem('vibefix.sidebar.collapsed')).toBe('0')
  })

  it('opens folded when this browser left it folded', { timeout: 15_000 }, async () => {
    windowOf(1024)
    window.localStorage.setItem('vibefix.sidebar.collapsed', '1')
    fakeBackend(signedInUser, { 'GET /api/v1/products': () => json([]) })
    renderAt('/thong-bao')

    expect(await screen.findByRole('button', { name: 'Mở rộng thanh bên' })).toBeInTheDocument()
    expect(screen.queryByRole('navigation', { name: 'Tài khoản' })).toBeNull()
  })

  it('has no fold button under 1024 px, where the sidebar is already the rail', { timeout: 15_000 }, async () => {
    windowOf(768)
    fakeBackend(signedInUser, { 'GET /api/v1/products': () => json([]) })
    renderAt('/thong-bao')

    await screen.findByRole('complementary', { name: 'Thanh bên' })
    expect(screen.queryByRole('button', { name: /thanh bên/ })).toBeNull()
  })
})
