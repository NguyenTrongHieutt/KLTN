import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { routes } from '@/app/routes'
import { fakeBackend, json, signedInUser, signedInWithGoogle } from '@/test/fakeBackend'

function renderAt(path: string) {
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  render(
    <QueryClientProvider client={createQueryClient()}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return router
}

const product = {
  id: 'p1',
  name: 'Shop Gốm Bát Tràng',
  description: 'Cửa hàng online bán gốm.',
  demoUrl: 'https://shop-gom.lovable.app',
  tools: ['LOVABLE'],
  repositoryFullName: 'lan-nguyen/shop-gom',
  repositoryUrl: 'https://github.com/lan-nguyen/shop-gom',
  repositoryOwnerType: 'User',
  forkParent: 'lovable-templates/shop-starter',
  createdAt: '2026-10-16T03:00:00Z',
  jobs: 0,
}

const checked = {
  githubId: 4001,
  fullName: 'lan-nguyen/shop-gom',
  htmlUrl: 'https://github.com/lan-nguyen/shop-gom',
  ownerType: 'User',
  sizeKb: 38912,
  forkParent: 'lovable-templates/shop-starter',
  defaultBranch: 'main',
}

const refusal = (status: number, body: object) =>
  new Response(JSON.stringify({ status, ...body }), { status, headers: { 'Content-Type': 'application/problem+json' } })

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('CUS-2, the Products (FR-7)', () => {
  // The first test of the file also pays for loading the lazy routes.
  it('shows the empty state with the way to register', { timeout: 15_000 }, async () => {
    fakeBackend(signedInUser, { 'GET /api/v1/products': () => json([]) })
    renderAt('/san-pham')

    expect(await screen.findByText('Chưa có sản phẩm nào.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Đăng ký sản phẩm' })).toHaveAttribute('href', '/san-pham/moi')
    expect(screen.getAllByRole('link', { name: /Sản phẩm/ }).length).toBeGreaterThan(0)
  })

  it('lists the own Products of a user with no role (Story 20.3)', async () => {
    const seen = fakeBackend(signedInWithGoogle, { 'GET /api/v1/products': () => json([]) })
    renderAt('/san-pham')

    expect(await screen.findByText('Chưa có sản phẩm nào.')).toBeInTheDocument()
    expect(seen.some((request) => new URL(request.url, 'http://localhost').pathname === '/api/v1/products')).toBe(true)
  })

  it('lists each Product with its Source Repository and no scan state (Story 23.1)', async () => {
    fakeBackend(signedInUser, { 'GET /api/v1/products': () => json([product]) })
    renderAt('/san-pham')

    const list = await screen.findByRole('list', { name: 'Sản phẩm của bạn' })
    expect(within(list).getByRole('link', { name: 'Shop Gốm Bát Tràng' })).toHaveAttribute('href', '/san-pham/p1')
    expect(within(list).getByText(/Dự án gốc: lan-nguyen\/shop-gom/)).toBeInTheDocument()
    expect(within(list).queryByText(/kiểm tra|vấn đề/i)).toBeNull()
  })
})

describe('CUS-3, registering a Product (FR-7, AD-13)', () => {
  it('goes through the four steps, registers with no scan, and opens CUS-4', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    let registered: Record<string, unknown> | undefined
    fakeBackend(signedInUser, {
      'POST /api/v1/products/repository-check': () => json(checked),
      'POST /api/v1/products': async (request) => {
        registered = await request.json()
        return json({ id: 'p1' }, 201)
      },
      'GET /api/v1/products/p1': () => json(product),
    })
    renderAt('/san-pham/moi')

    expect(await screen.findByText('Bước 1/4')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Việc cần làm' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Lovable' }))
    expect(screen.getByText('Đưa mã từ Lovable lên GitHub')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Mở hướng dẫn của Lovable/ })).toHaveAttribute('href', 'https://docs.lovable.dev')
    await user.click(screen.getByRole('button', { name: 'Tiếp tục' }))

    expect(await screen.findByText('Bước 2/4')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Tiếp tục' })).toBeDisabled()
    await user.type(screen.getByLabelText('Đường dẫn kho mã trên GitHub'), 'https://github.com/lan-nguyen/shop-gom')
    await user.click(screen.getByRole('button', { name: 'Kiểm tra' }))
    expect(await screen.findByText('Kho mã này được sao từ lovable-templates/shop-starter')).toBeInTheDocument()
    expect(screen.getByText('Dung lượng 38 MB, dưới giới hạn 250 MB')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Tiếp tục' }))

    expect(await screen.findByText('Bước 3/4')).toBeInTheDocument()
    await user.type(screen.getByLabelText('Tên sản phẩm'), 'Shop Gốm Bát Tràng')
    await user.type(screen.getByLabelText('Mô tả ngắn'), 'Cửa hàng online bán gốm.')
    await user.type(screen.getByLabelText('Đường dẫn bản chạy thử (không bắt buộc)'), 'https://shop-gom.lovable.app')
    expect(screen.getByText(/Trang này nằm ngoài vibefix \(shop-gom.lovable.app\)/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Tiếp tục' }))

    expect(await screen.findByText('Bước 4/4')).toBeInTheDocument()
    // N-2 lost its scan sentence under notice version 1.1 (Story 23.1).
    expect(screen.getByText('Lưu ý khi đăng ký sản phẩm · phiên bản 1.1 · 12/11/2026')).toBeInTheDocument()
    expect(screen.getByText(/^Tôi hiểu vibefix không quét mã\./)).toBeInTheDocument()
    const start = screen.getByRole('button', { name: 'Đăng ký sản phẩm' })
    expect(start).toBeDisabled()
    expect(screen.getByText('Đánh dấu đủ 3 mục để đăng ký.')).toBeInTheDocument()
    for (const box of screen.getAllByRole('checkbox')) {
      await user.click(box)
    }
    await user.click(start)

    await waitFor(() =>
      expect(registered).toMatchObject({
        repositoryUrl: 'https://github.com/lan-nguyen/shop-gom',
        tools: ['LOVABLE'],
        name: 'Shop Gốm Bát Tràng',
        demoUrl: 'https://shop-gom.lovable.app',
        publicNoticeVersion: '1.1',
        copyPermissionVersion: '1.1',
        detectionNoticeVersion: '1.1',
      }),
    )
    expect(await screen.findByRole('heading', { level: 1, name: 'Shop Gốm Bát Tràng' })).toBeInTheDocument()
  })

  it('shows a refused repository in the checklist and keeps the input', { timeout: 15_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(signedInUser, {
      'POST /api/v1/products/repository-check': () =>
        refusal(422, { code: 'GUARD_FAILED', reason: 'REPO_PRIVATE', repository: 'lan-nguyen/shop-gom' }),
    })
    renderAt('/san-pham/moi')

    await user.click(await screen.findByRole('button', { name: 'Lovable' }))
    await user.click(screen.getByRole('button', { name: 'Tiếp tục' }))
    const field = await screen.findByLabelText('Đường dẫn kho mã trên GitHub')
    await user.type(field, 'https://github.com/lan-nguyen/shop-gom')
    await user.click(screen.getByRole('button', { name: 'Kiểm tra' }))

    expect(await screen.findAllByText('Kho mã đang ở chế độ riêng tư. vibefix hiện chỉ nhận kho mã công khai.')).not.toHaveLength(0)
    expect(screen.getByText('Tìm thấy kho mã')).toBeInTheDocument()
    expect(screen.getByText('Bạn có quyền quản trị kho mã này · chưa kiểm tra')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Xem cách làm/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Tiếp tục' })).toBeDisabled()
    expect(field).toHaveValue('https://github.com/lan-nguyen/shop-gom')
    expect(field).toHaveAttribute('aria-invalid', 'true')
  })

  it('opens the wizard for a user with no role, whose GitHub gate guards step 2 (Story 20.3)', async () => {
    fakeBackend(signedInWithGoogle)
    renderAt('/san-pham/moi')

    expect(await screen.findByText('Bước 1/4')).toBeInTheDocument()
    expect(screen.queryByText('Không tìm thấy nội dung này.')).not.toBeInTheDocument()
  })
})

describe('CUS-4, a Product (FR-7)', () => {
  it('shows the Product with its source and information, and posts a Job at once (Story 23.1)', async () => {
    const seen = fakeBackend(signedInUser, { 'GET /api/v1/products/p1': () => json(product) })
    renderAt('/san-pham/p1')

    expect(await screen.findByRole('heading', { level: 1, name: 'Shop Gốm Bát Tràng' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Mở dự án gốc/ })).toHaveAttribute('href', 'https://github.com/lan-nguyen/shop-gom')
    expect(screen.getByRole('link', { name: 'Đăng công việc' })).toHaveAttribute('href', '/san-pham/p1/cong-viec-moi')
    expect(screen.getByText('Lovable')).toBeInTheDocument()
    expect(screen.getByText('lovable-templates/shop-starter')).toBeInTheDocument()
    // vibefix does not scan code: no scan result, history, or "Kiểm tra lại", and no read but the Product.
    expect(screen.queryByRole('button', { name: 'Kiểm tra lại' })).toBeNull()
    expect(screen.queryByText(/vấn đề/)).toBeNull()
    expect(screen.queryByRole('table')).toBeNull()
    const reads = seen
      .map((request) => new URL(request.url, 'http://localhost').pathname)
      .filter((pathname) => pathname.startsWith('/api/v1/products/'))
    expect(new Set(reads)).toEqual(new Set(['/api/v1/products/p1']))
  })

  it('shows the not-found screen for an old CUS-5 link (Story 23.1)', async () => {
    fakeBackend(signedInUser, { 'GET /api/v1/products/p1': () => json(product) })
    renderAt('/san-pham/p1/van-de/f1')

    expect(await screen.findByText('Không tìm thấy nội dung này.')).toBeInTheDocument()
  })

  it('deletes the Product after the destructive dialog lists what goes, then shows CUS-2', { timeout: 15_000 }, async () => {
    const user = userEvent.setup()
    let deleted = false
    fakeBackend(signedInUser, {
      'GET /api/v1/products/p1': () => json(product),
      'GET /api/v1/products': () => json([]),
      'DELETE /api/v1/products/p1': () => {
        deleted = true
        return new Response(null, { status: 204 })
      },
    })
    renderAt('/san-pham/p1')

    await user.click(await screen.findByRole('button', { name: 'Xóa sản phẩm' }))
    const dialog = await screen.findByRole('dialog', { name: 'Xóa Shop Gốm Bát Tràng?' })
    expect(within(dialog).getByText('Thông tin sản phẩm và các lưu ý bạn đã xác nhận')).toBeInTheDocument()
    expect(within(dialog).queryByText(/Kết quả kiểm tra/)).toBeNull()
    expect(within(dialog).getByText('Dự án gốc trên GitHub không bị ảnh hưởng.')).toBeInTheDocument()
    expect(deleted).toBe(false)
    await user.click(within(dialog).getByRole('button', { name: 'Xóa sản phẩm' }))

    await waitFor(() => expect(deleted).toBe(true))
    expect(await screen.findByText('Chưa có sản phẩm nào.')).toBeInTheDocument()
    expect(await screen.findAllByText('Đã xóa Shop Gốm Bát Tràng.')).not.toHaveLength(0)
  })

  it('says a Product a running Job uses cannot be deleted', { timeout: 15_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(signedInUser, {
      'GET /api/v1/products/p1': () => json(product),
      'DELETE /api/v1/products/p1': () =>
        new Response(JSON.stringify({ status: 422, code: 'GUARD_FAILED', reason: 'PRODUCT_HAS_ACTIVE_JOB' }), {
          status: 422,
          headers: { 'Content-Type': 'application/problem+json' },
        }),
    })
    renderAt('/san-pham/p1')

    await user.click(await screen.findByRole('button', { name: 'Xóa sản phẩm' }))
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Xóa sản phẩm' }))

    expect(await screen.findAllByText('Không thể xóa sản phẩm khi còn công việc đang thực hiện.')).not.toHaveLength(0)
    expect(screen.getByRole('heading', { level: 1, name: 'Shop Gốm Bát Tràng' })).toBeInTheDocument()
  })

  it('shows the not-found screen for a Product that is not the Customer\'s', async () => {
    fakeBackend(signedInUser, {
      'GET /api/v1/products/p9': () => refusal(404, { code: 'NOT_FOUND' }),
    })
    renderAt('/san-pham/p9')

    expect(await screen.findByText('Không tìm thấy nội dung này.')).toBeInTheDocument()
  })

  it('keeps planted payloads as text', async () => {
    const payload = '<img src=x onerror=alert(1)>'
    fakeBackend(signedInUser, {
      'GET /api/v1/products/p1': () => json({ ...product, name: payload, description: payload }),
    })
    renderAt('/san-pham/p1')

    expect(await screen.findByRole('heading', { level: 1, name: payload })).toBeInTheDocument()
    expect(document.querySelector('img[src="x"]')).toBeNull()
  })
})
