import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { routes } from '@/app/routes'
import { fakeBackend, json, signedIn } from '@/test/fakeBackend'

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
  scanState: 'AWAITING_SCAN',
  openFindings: 0,
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
  it('shows the empty state with the way to register', async () => {
    fakeBackend(signedIn('CUSTOMER'), { 'GET /api/v1/products': () => json([]) })
    renderAt('/san-pham')

    expect(await screen.findByText('Chưa có sản phẩm nào.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Đăng ký sản phẩm' })).toHaveAttribute('href', '/san-pham/moi')
    expect(screen.getAllByRole('link', { name: /Sản phẩm/ }).length).toBeGreaterThan(0)
  })

  it('lists each Product with its state and Source Repository', async () => {
    fakeBackend(signedIn('CUSTOMER'), { 'GET /api/v1/products': () => json([product]) })
    renderAt('/san-pham')

    const list = await screen.findByRole('list', { name: 'Sản phẩm của bạn' })
    expect(within(list).getByRole('link', { name: 'Shop Gốm Bát Tràng' })).toHaveAttribute('href', '/san-pham/p1')
    expect(within(list).getByText('Chờ kiểm tra')).toBeInTheDocument()
    expect(within(list).getByText(/Dự án gốc: lan-nguyen\/shop-gom/)).toBeInTheDocument()
  })
})

describe('CUS-3, registering a Product (FR-7, AD-13)', () => {
  it('goes through the four steps and opens CUS-4', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    let registered: Record<string, unknown> | undefined
    fakeBackend(signedIn('CUSTOMER'), {
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
    const start = screen.getByRole('button', { name: 'Bắt đầu kiểm tra' })
    expect(start).toBeDisabled()
    expect(screen.getByText('Đánh dấu đủ 3 mục để bắt đầu.')).toBeInTheDocument()
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
        publicNoticeVersion: '1.0',
        copyPermissionVersion: '1.0',
        detectionNoticeVersion: '1.0',
      }),
    )
    expect(await screen.findByRole('heading', { level: 1, name: 'Shop Gốm Bát Tràng' })).toBeInTheDocument()
  })

  it('shows a refused repository in the checklist and keeps the input', { timeout: 15_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(signedIn('CUSTOMER'), {
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

  it('finds nothing for another role', async () => {
    fakeBackend(signedIn('STUDENT'))
    renderAt('/san-pham/moi')

    expect(await screen.findByText('Không tìm thấy nội dung này.')).toBeInTheDocument()
  })
})

describe('CUS-4, a Product (FR-7)', () => {
  it('shows the Product with its source, state, and information', async () => {
    fakeBackend(signedIn('CUSTOMER'), { 'GET /api/v1/products/p1': () => json(product) })
    renderAt('/san-pham/p1')

    expect(await screen.findByRole('heading', { level: 1, name: 'Shop Gốm Bát Tràng' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Mở dự án gốc/ })).toHaveAttribute('href', 'https://github.com/lan-nguyen/shop-gom')
    expect(screen.getAllByText('Chờ kiểm tra').length).toBeGreaterThan(0)
    expect(screen.getByText('Lovable')).toBeInTheDocument()
    expect(screen.getByText('lovable-templates/shop-starter')).toBeInTheDocument()
  })

  it('shows the not-found screen for a Product that is not the Customer\'s', async () => {
    fakeBackend(signedIn('CUSTOMER'), {
      'GET /api/v1/products/p9': () => refusal(404, { code: 'NOT_FOUND' }),
    })
    renderAt('/san-pham/p9')

    expect(await screen.findByText('Không tìm thấy nội dung này.')).toBeInTheDocument()
  })

  it('keeps planted payloads as text', async () => {
    const payload = '<img src=x onerror=alert(1)>'
    fakeBackend(signedIn('CUSTOMER'), {
      'GET /api/v1/products/p1': () => json({ ...product, name: payload, description: payload }),
    })
    renderAt('/san-pham/p1')

    expect(await screen.findByRole('heading', { level: 1, name: payload })).toBeInTheDocument()
    expect(document.querySelector('img[src="x"]')).toBeNull()
  })
})
