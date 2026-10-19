import { act, render, screen, waitFor, within } from '@testing-library/react'
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
  scanState: 'SCANNING',
  scan: scanOf('PENDING'),
  openFindings: 0,
  jobs: 0,
}

function scanOf(status: 'PENDING' | 'RUNNING' | 'RETRYING' | 'SUCCEEDED' | 'FAILED', extra: object = {}) {
  const finished = status === 'SUCCEEDED' || status === 'FAILED'
  return {
    id: 's1',
    status,
    requestedAt: '2026-10-16T03:00:00Z',
    ...(finished ? { finishedAt: '2026-10-16T03:02:00Z', found: 0 } : {}),
    unscannedCount: 0,
    unscanned: [],
    ...extra,
  }
}

const finding = {
  id: 'f1',
  category: 'CREDENTIAL',
  type: 'Khóa dịch vụ Supabase',
  path: '.env',
  commit: 'a3f91c2' + '0'.repeat(33),
  committedAt: '2026-09-26T03:00:00Z',
  maskedPreview: 'eyJh••••••••X2kQ',
  status: 'OPEN',
  createdAt: '2026-10-16T03:02:00Z',
}

const withFindings = { ...product, scanState: 'FINDINGS', scan: scanOf('SUCCEEDED', { found: 1 }), openFindings: 1 }

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
  vi.useRealTimers()
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
    expect(within(list).getByText('Đang kiểm tra')).toBeInTheDocument()
    expect(within(list).getByText(/Dự án gốc: lan-nguyen\/shop-gom/)).toBeInTheDocument()
  })

  it('shows each scan state, with the open Findings counted (FR-8)', async () => {
    fakeBackend(signedIn('CUSTOMER'), {
      'GET /api/v1/products': () =>
        json([
          { ...withFindings, id: 'p1', openFindings: 2 },
          { ...product, id: 'p2', scanState: 'CLEAN', scan: scanOf('SUCCEEDED') },
          { ...product, id: 'p3', scanState: 'NOT_FINISHED', scan: scanOf('RETRYING') },
          { ...product, id: 'p4', scanState: 'AWAITING_SCAN', scan: undefined },
        ]),
    })
    renderAt('/san-pham')

    const list = await screen.findByRole('list', { name: 'Sản phẩm của bạn' })
    expect(within(list).getByText('Còn 2 vấn đề cần xử lý')).toBeInTheDocument()
    expect(within(list).getByText('Không phát hiện vấn đề')).toBeInTheDocument()
    expect(within(list).getByText('Chưa kiểm tra xong')).toBeInTheDocument()
    expect(within(list).getByText('Chờ kiểm tra')).toBeInTheDocument()
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
    fakeBackend(signedIn('CUSTOMER'), {
      'GET /api/v1/products/p1': () => json({ ...product, scanState: 'AWAITING_SCAN', scan: undefined }),
    })
    renderAt('/san-pham/p1')

    expect(await screen.findByRole('heading', { level: 1, name: 'Shop Gốm Bát Tràng' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Mở dự án gốc/ })).toHaveAttribute('href', 'https://github.com/lan-nguyen/shop-gom')
    expect(screen.getAllByText('Chờ kiểm tra').length).toBeGreaterThan(0)
    expect(screen.getByText('Lovable')).toBeInTheDocument()
    expect(screen.getByText('lovable-templates/shop-starter')).toBeInTheDocument()
  })

  it('polls every 5 s under the Busy banner while the scan runs, then lists the Findings (UX-DR21)', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    let reads = 0
    fakeBackend(signedIn('CUSTOMER'), {
      'GET /api/v1/products/p1': () => json(++reads === 1 ? product : withFindings),
      'GET /api/v1/products/p1/findings': () => json(reads === 1 ? [] : [finding]),
      'GET /api/v1/products/p1/scans': () => json([reads === 1 ? scanOf('PENDING', { purpose: 'INTAKE' }) : scanOf('SUCCEEDED', { purpose: 'INTAKE', found: 1 })]),
    })
    renderAt('/san-pham/p1')

    expect(
      await screen.findByText('Đang kiểm tra lịch sử mã… thường mất vài phút. Bạn có thể rời trang; vibefix sẽ báo khi xong.'),
    ).toBeInTheDocument()
    expect(screen.getByText('Kết quả hiện ở đây khi kiểm tra xong.')).toBeInTheDocument()
    await act(() => vi.advanceTimersByTimeAsync(5_000))

    const list = await screen.findByRole('list', { name: 'Vấn đề cần xử lý' })
    expect(reads).toBe(2)
    expect(screen.getByText('Còn 1 vấn đề cần xử lý trước khi sửa mã.')).toBeInTheDocument()
    expect(within(list).getByRole('heading', { level: 3, name: 'Khóa dịch vụ Supabase' })).toBeInTheDocument()
    expect(within(list).getByText('.env')).toBeInTheDocument()
    expect(within(list).getByText('a3f91c2')).toBeInTheDocument()
    expect(within(list).getByText('eyJh••••••••X2kQ')).toBeInTheDocument()
    expect(within(list).getByText('Chưa xử lý')).toBeInTheDocument()
    expect(screen.queryByText(/Đang kiểm tra lịch sử mã/)).not.toBeInTheDocument()
    await act(() => vi.advanceTimersByTimeAsync(10_000))
    expect(reads).toBe(2)
  })

  it('says a clean scan found nothing and what the check cannot promise', async () => {
    fakeBackend(signedIn('CUSTOMER'), {
      'GET /api/v1/products/p1': () => json({ ...product, scanState: 'CLEAN', scan: scanOf('SUCCEEDED') }),
      'GET /api/v1/products/p1/findings': () => json([]),
      'GET /api/v1/products/p1/scans': () => json([]),
    })
    renderAt('/san-pham/p1')

    expect(await screen.findByText('Không phát hiện vấn đề nào ở lần kiểm tra gần nhất.')).toBeInTheDocument()
    const checks = await screen.findByRole('region', { name: 'Kết quả từng loại kiểm tra' })
    expect(within(checks).getByText('Không phát hiện khóa bí mật')).toBeInTheDocument()
    expect(within(checks).getByText('Không phát hiện dữ liệu cá nhân')).toBeInTheDocument()
    expect(screen.getByText('Kiểm tra tự động, không dùng AI, và không thể phát hiện mọi thứ.')).toBeInTheDocument()
    expect(screen.getByText('Không phát hiện vấn đề')).toBeInTheDocument()
  })

  it('shows a personal-data Finding by file, with its lines and masked samples (FR-65)', async () => {
    const personal = {
      ...finding,
      id: 'f2',
      category: 'PERSONAL_DATA',
      type: 'Email và số điện thoại khách hàng',
      path: 'seed/customers.json',
      commit: 'a71c0e4' + '0'.repeat(33),
      maskedPreview: 'ng••••@gmail.com · 09•• ••• •12',
      matchCount: 48,
    }
    fakeBackend(signedIn('CUSTOMER'), {
      'GET /api/v1/products/p1': () => json({ ...withFindings, openFindings: 2 }),
      'GET /api/v1/products/p1/findings': () => json([finding, personal]),
    })
    renderAt('/san-pham/p1')

    const list = await screen.findByRole('list', { name: 'Vấn đề cần xử lý' })
    const card = within(list).getByRole('heading', { level: 3, name: 'Email và số điện thoại khách hàng' }).closest('li')
    expect(card).not.toBeNull()
    expect(within(card as HTMLElement).getByText('seed/customers.json')).toBeInTheDocument()
    expect(within(card as HTMLElement).getByText(/48 dòng/)).toBeInTheDocument()
    expect(within(card as HTMLElement).getByText('Ví dụ đã che:')).toBeInTheDocument()
    expect(within(card as HTMLElement).getByText('ng••••@gmail.com · 09•• ••• •12')).toBeInTheDocument()
    const checks = screen.getByRole('region', { name: 'Kết quả từng loại kiểm tra' })
    expect(within(checks).getAllByText('1 vấn đề')).toHaveLength(2)
  })

  it('lists every Finding with its state and the scan history with each run', async () => {
    fakeBackend(signedIn('CUSTOMER'), {
      'GET /api/v1/products/p1': () => json(withFindings),
      'GET /api/v1/products/p1/findings': () =>
        json([
          finding,
          { ...finding, id: 'f2', type: 'Khóa bí mật Stripe', status: 'RESOLVED', resolution: 'ROTATED', resolvedAt: '2026-10-12T03:00:00Z' },
          { ...finding, id: 'f3', type: 'Khóa API Google', status: 'FALSE_POSITIVE', resolution: 'FALSE_POSITIVE', resolvedAt: '2026-10-13T03:00:00Z', resolutionReason: 'Khóa mẫu' },
        ]),
      'GET /api/v1/products/p1/scans': () =>
        json([
          { ...scanOf('SUCCEEDED', { found: 1 }), id: 's2', purpose: 'RESCAN', requestedAt: '2026-10-14T02:12:00Z' },
          { ...scanOf('SUCCEEDED', { found: 0 }), id: 's1', purpose: 'INTAKE', requestedAt: '2026-10-10T09:40:00Z' },
        ]),
    })
    renderAt('/san-pham/p1')

    const list = await screen.findByRole('list', { name: 'Vấn đề cần xử lý' })
    expect(within(list).getByText('Chưa xử lý')).toBeInTheDocument()
    expect(within(list).getByText('Đã xử lý 12/10')).toBeInTheDocument()
    expect(within(list).getByText('Không phải khóa thật')).toBeInTheDocument()
    expect(within(list).getAllByRole('link', { name: 'Xem chi tiết' })).toHaveLength(2)
    const history = await screen.findByRole('table')
    expect(within(history).getByText('Kiểm tra lại theo yêu cầu')).toBeInTheDocument()
    expect(within(history).getByText('Lần đầu đăng ký')).toBeInTheDocument()
    expect(within(history).getByText('1 vấn đề')).toBeInTheDocument()
    expect(within(history).getByText('Không có vấn đề')).toBeInTheDocument()
  })

  it('runs both scans again on "Kiểm tra lại" and says when the day\'s limit frees up', { timeout: 15_000 }, async () => {
    const user = userEvent.setup()
    let rescans = 0
    fakeBackend(signedIn('CUSTOMER'), {
      'GET /api/v1/products/p1': () => json({ ...product, scanState: 'CLEAN', scan: scanOf('SUCCEEDED') }),
      'GET /api/v1/products/p1/findings': () => json([]),
      'GET /api/v1/products/p1/scans': () => json([]),
      'POST /api/v1/products/p1/scans': () => {
        rescans++
        return rescans === 1
          ? json(scanOf('PENDING', { purpose: 'RESCAN' }), 202)
          : new Response(JSON.stringify({ status: 429, code: 'RATE_LIMITED', reason: 'SCAN_LIMIT' }), {
              status: 429,
              headers: { 'Content-Type': 'application/problem+json', 'Retry-After': '3600' },
            })
      },
    })
    renderAt('/san-pham/p1')

    await user.click(await screen.findByRole('button', { name: 'Kiểm tra lại' }))
    expect(await screen.findAllByText('vibefix đang kiểm tra lại sản phẩm này.')).not.toHaveLength(0)
    await user.click(screen.getByRole('button', { name: 'Kiểm tra lại' }))
    expect(await screen.findAllByText(/Mỗi sản phẩm được kiểm tra lại tối đa 5 lần mỗi ngày/)).not.toHaveLength(0)
  })

  it('says a scan that did not finish will be tried again, and lists the files too large to check', async () => {
    fakeBackend(signedIn('CUSTOMER'), {
      'GET /api/v1/products/p1': () =>
        json({
          ...product,
          scanState: 'NOT_FINISHED',
          scan: scanOf('FAILED', {
            unscannedCount: 4,
            unscanned: [
              { path: 'public/video/gioi-thieu.mp4', sizeBytes: 32 * 1024 * 1024, reason: 'TOO_LARGE' },
              { path: 'exports/khach-hang.xlsx', sizeBytes: 350 * 1024, reason: 'NOT_EXTRACTED' },
            ],
          }),
        }),
    })
    renderAt('/san-pham/p1')

    expect(await screen.findByText('Chưa kiểm tra xong. vibefix sẽ thử lại; bạn chưa cần làm gì.')).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 2, name: 'Tệp chưa kiểm tra được' })).toBeInTheDocument()
    expect(screen.getByText('public/video/gioi-thieu.mp4')).toBeInTheDocument()
    expect(screen.getByText('32 MB · lớn hơn giới hạn kiểm tra')).toBeInTheDocument()
    expect(screen.getByText('350 KB · chưa đọc được loại tệp này')).toBeInTheDocument()
    expect(screen.getByText('Và 2 tệp khác chưa kiểm tra được.')).toBeInTheDocument()
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
