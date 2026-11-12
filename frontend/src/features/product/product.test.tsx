import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { routes } from '@/app/routes'
import { redirectTo } from '@/lib/redirect'
import { fakeBackend, json, signedInUser, signedInWithGoogle, workflowFile } from '@/test/fakeBackend'

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
  sourceStatus: 'READY',
  sourceCheckedAt: '2026-10-16T03:00:00Z',
  defaultBranch: 'main',
}

const sourceApp = {
  accountLogin: 'lan-nguyen',
  accountType: 'User',
  settingsUrl: 'https://github.com/settings/installations/21',
  otherProducts: 0,
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

const draft = {
  step: 2,
  tools: ['LOVABLE'],
  url: 'https://github.com/cong-ty/app',
  values: { name: '', description: '', demoUrl: '' },
}

const refusal = (status: number, body: object) =>
  new Response(JSON.stringify({ status, ...body }), { status, headers: { 'Content-Type': 'application/problem+json' } })

/** CUS-3 from the first step to "Đăng ký sản phẩm" on step 5, with both checks passing on the way. */
async function registerThroughTheNotices(user: ReturnType<typeof userEvent.setup>) {
  renderAt('/san-pham/moi')
  await user.click(await screen.findByRole('button', { name: 'Lovable' }))
  await user.click(screen.getByRole('button', { name: 'Tiếp tục' }))
  await user.type(await screen.findByLabelText('Đường dẫn kho mã trên GitHub'), 'https://github.com/lan-nguyen/shop-gom')
  await user.click(screen.getByRole('button', { name: 'Kiểm tra' }))
  await screen.findByText('Dung lượng 38 MB, dưới giới hạn 250 MB')
  await user.click(screen.getByRole('button', { name: 'Tiếp tục' }))
  expect(await screen.findByText('Bước 3/5')).toBeInTheDocument()
  await user.click(await screen.findByRole('button', { name: 'Kiểm tra' }))
  await screen.findByText('Đúng bản vibefix công bố')
  await user.click(screen.getByRole('button', { name: 'Tiếp tục' }))
  await user.type(await screen.findByLabelText('Tên sản phẩm'), 'Shop Gốm Bát Tràng')
  await user.type(screen.getByLabelText('Mô tả ngắn'), 'Cửa hàng online bán gốm.')
  await user.click(screen.getByRole('button', { name: 'Tiếp tục' }))
  expect(await screen.findByText('Bước 5/5')).toBeInTheDocument()
  for (const box of screen.getAllByRole('checkbox')) {
    await user.click(box)
  }
  await user.click(screen.getByRole('button', { name: 'Đăng ký sản phẩm' }))
}

beforeEach(() => {
  window.sessionStorage.clear()
  vi.mocked(redirectTo).mockReset()
})

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

  it('shows each Product\'s recorded Workflow status badge, with no other read (Story 23.5c)', async () => {
    const statuses = ['READY', 'SOURCE_APP_MISSING', 'SOURCE_APP_WRONG_REPOSITORY', 'WORKFLOW_MISSING', 'WORKFLOW_MISMATCH']
    const seen = fakeBackend(signedInUser, {
      'GET /api/v1/products': () =>
        json(statuses.map((sourceStatus, n) => ({ ...product, id: `p${n}`, name: `Sản phẩm ${n}`, sourceStatus }))),
    })
    renderAt('/san-pham')

    const list = await screen.findByRole('list', { name: 'Sản phẩm của bạn' })
    const badges = within(list)
      .getAllByRole('listitem')
      .map((item) => within(item).getByText(/^(Sẵn sàng|Chưa cài ứng dụng vibefix|Thiếu file workflow|File workflow khác bản mẫu)$/).textContent)
    expect(badges).toEqual([
      'Ứng dụng vibefix và file workflow: Sẵn sàng',
      'Ứng dụng vibefix và file workflow: Chưa cài ứng dụng vibefix',
      'Ứng dụng vibefix và file workflow: Chưa cài ứng dụng vibefix',
      'Ứng dụng vibefix và file workflow: Thiếu file workflow',
      'Ứng dụng vibefix và file workflow: File workflow khác bản mẫu',
    ])
    const reads = seen
      .map((request) => new URL(request.url, 'http://localhost').pathname)
      .filter((pathname) => pathname.startsWith('/api/v1/products'))
    expect(new Set(reads)).toEqual(new Set(['/api/v1/products']))
  })
})

describe('CUS-3, registering a Product (FR-7, AD-13, AD-45, Story 23.5)', () => {
  it('goes through the five steps, checks the Source App and the workflow file, and opens CUS-4', { timeout: 30_000 }, async () => {
    const user = userEvent.setup()
    let registered: Record<string, unknown> | undefined
    let checkedWorkflow: Record<string, unknown> | undefined
    fakeBackend(signedInUser, {
      'POST /api/v1/products/repository-check': () => json(checked),
      'POST /api/v1/products/workflow-check': async (request) => {
        checkedWorkflow = await request.json()
        return json({ fullName: 'lan-nguyen/shop-gom', defaultBranch: 'main' })
      },
      'POST /api/v1/products': async (request) => {
        registered = await request.json()
        return json({ id: 'p1' }, 201)
      },
      'GET /api/v1/products/p1': () => json({ ...product, sourceApp }),
    })
    renderAt('/san-pham/moi')

    expect(await screen.findByText('Bước 1/5')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Việc cần làm' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Lovable' }))
    expect(screen.getByText('Đưa mã từ Lovable lên GitHub')).toBeInTheDocument()
    expect(screen.getByText(/công khai hay riêng tư đều được/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Mở hướng dẫn của Lovable/ })).toHaveAttribute('href', 'https://docs.lovable.dev')
    await user.click(screen.getByRole('button', { name: 'Tiếp tục' }))

    expect(await screen.findByText('Bước 2/5')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Tiếp tục' })).toBeDisabled()
    await user.type(screen.getByLabelText('Đường dẫn kho mã trên GitHub'), 'https://github.com/lan-nguyen/shop-gom')
    expect(screen.getByRole('button', { name: /Cài ứng dụng vibefix trên GitHub/ })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Kiểm tra' }))
    expect(await screen.findByText('Kho mã này được sao từ lovable-templates/shop-starter')).toBeInTheDocument()
    expect(screen.getByText('Ứng dụng vibefix được cài cho đúng kho mã này')).toBeInTheDocument()
    expect(screen.getByText('Dung lượng 38 MB, dưới giới hạn 250 MB')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Cài ứng dụng vibefix trên GitHub/ })).toBeNull()
    await user.click(screen.getByRole('button', { name: 'Tiếp tục' }))

    expect(await screen.findByText('Bước 3/5')).toBeInTheDocument()
    const add = await screen.findByRole('link', { name: /Thêm file workflow/ })
    expect(add).toHaveAttribute(
      'href',
      `https://github.com/lan-nguyen/shop-gom/new/main?filename=.github%2Fworkflows%2Fvibefix.yml&value=${encodeURIComponent(workflowFile.content)}`,
    )
    expect(add).toHaveAttribute('target', '_blank')
    expect(screen.getByRole('link', { name: /Tải file vibefix.yml/ })).toHaveAttribute('download', 'vibefix.yml')
    expect(screen.getByText(/vibefix không quét mã của bạn\. Trước khi người khác đọc mã/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Tự kiểm tra khóa bí mật trên GitHub/ })).toHaveAttribute(
      'href',
      'https://github.com/lan-nguyen/shop-gom/actions/workflows/vibefix.yml',
    )
    expect(screen.getByRole('button', { name: 'Tiếp tục' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: 'Kiểm tra' }))
    expect(await screen.findByText('Đúng bản vibefix công bố')).toBeInTheDocument()
    expect(checkedWorkflow).toEqual({ url: 'https://github.com/lan-nguyen/shop-gom' })
    await user.click(screen.getByRole('button', { name: 'Tiếp tục' }))

    expect(await screen.findByText('Bước 4/5')).toBeInTheDocument()
    await user.type(screen.getByLabelText('Tên sản phẩm'), 'Shop Gốm Bát Tràng')
    await user.type(screen.getByLabelText('Mô tả ngắn'), 'Cửa hàng online bán gốm.')
    await user.type(screen.getByLabelText('Đường dẫn bản chạy thử (không bắt buộc)'), 'https://shop-gom.lovable.app')
    expect(screen.getByText(/Trang này nằm ngoài vibefix \(shop-gom.lovable.app\)/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Tiếp tục' }))

    expect(await screen.findByText('Bước 5/5')).toBeInTheDocument()
    // N-1, N-1b and N-2 were rewritten for private copies under notice version 2.0 (Story 23.5).
    expect(screen.getByText('Lưu ý khi đăng ký sản phẩm · phiên bản 2.0 · 12/11/2026')).toBeInTheDocument()
    expect(screen.getByText(/^Tôi hiểu vibefix giữ các bản sao riêng tư của dự án gốc trên GitHub\./)).toBeInTheDocument()
    expect(screen.getByText(/^Tôi hiểu vibefix không quét mã\. Trước khi mỗi công việc bắt đầu/)).toBeInTheDocument()
    expect(screen.queryByText(/công khai: ai cũng xem được mã/)).toBeNull()
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
        publicNoticeVersion: '2.0',
        copyPermissionVersion: '2.0',
        detectionNoticeVersion: '2.0',
      }),
    )
    expect(await screen.findByRole('heading', { level: 1, name: 'Shop Gốm Bát Tràng' })).toBeInTheDocument()
  })

  it('offers the install link when the Source App is missing, keeps the input, and leaves for GitHub', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    let asked: Record<string, unknown> | undefined
    fakeBackend(signedInUser, {
      'POST /api/v1/products/repository-check': () =>
        refusal(422, { code: 'GUARD_FAILED', reason: 'SOURCE_APP_MISSING', repository: 'lan-nguyen/shop-gom' }),
      'POST /api/v1/products/source-app/install-link': async (request) => {
        asked = await request.json()
        return json({ url: 'https://github.com/apps/vibefix-dev-source/installations/new?state=s1' })
      },
    })
    renderAt('/san-pham/moi')

    await user.click(await screen.findByRole('button', { name: 'Lovable' }))
    await user.click(screen.getByRole('button', { name: 'Tiếp tục' }))
    const field = await screen.findByLabelText('Đường dẫn kho mã trên GitHub')
    await user.type(field, 'https://github.com/lan-nguyen/shop-gom')
    await user.click(screen.getByRole('button', { name: 'Kiểm tra' }))

    expect(
      await screen.findAllByText('Ứng dụng vibefix chưa được cài trên lan-nguyen/shop-gom. Hãy cài ứng dụng trên GitHub rồi quay lại.'),
    ).not.toHaveLength(0)
    expect(screen.getByText('Ứng dụng vibefix được cài cho đúng kho mã này')).toBeInTheDocument()
    expect(screen.getByText('Tìm thấy kho mã · chưa kiểm tra')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Tiếp tục' })).toBeDisabled()
    expect(field).toHaveValue('https://github.com/lan-nguyen/shop-gom')
    expect(field).toHaveAttribute('aria-invalid', 'true')

    await user.click(screen.getByRole('button', { name: /Cài ứng dụng vibefix trên GitHub/ }))
    await waitFor(() =>
      expect(redirectTo).toHaveBeenCalledWith('https://github.com/apps/vibefix-dev-source/installations/new?state=s1'),
    )
    expect(asked).toEqual({ url: 'https://github.com/lan-nguyen/shop-gom' })
    expect(JSON.parse(window.sessionStorage.getItem('vibefix.product.registration') ?? '{}')).toMatchObject({
      step: 2,
      tools: ['LOVABLE'],
      url: 'https://github.com/lan-nguyen/shop-gom',
    })
  })

  it('comes back from an organization install request, checks again, and has a message for the owner', { timeout: 15_000 }, async () => {
    window.sessionStorage.setItem('vibefix.product.registration', JSON.stringify(draft))
    let checks = 0
    fakeBackend(signedInUser, {
      'POST /api/v1/products/repository-check': () => {
        checks += 1
        return refusal(422, { code: 'GUARD_FAILED', reason: 'SOURCE_APP_MISSING', repository: 'cong-ty/app' })
      },
    })
    renderAt('/san-pham/moi?ung-dung=cho-duyet')

    expect(await screen.findByText('Chờ chủ tổ chức duyệt')).toBeInTheDocument()
    expect(screen.getByText('Bước 2/5')).toBeInTheDocument()
    expect(screen.getByLabelText('Đường dẫn kho mã trên GitHub')).toHaveValue('https://github.com/cong-ty/app')
    expect(
      screen.getByText(
        'Nhờ duyệt giúp yêu cầu cài ứng dụng vibefix cho đúng kho cong-ty/app trên GitHub. Yêu cầu đang chờ trong phần GitHub Apps của tổ chức cong-ty.',
      ),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Sao chép lời nhắn' })).toBeInTheDocument()
    await waitFor(() => expect(checks).toBe(1))
    expect(screen.queryByRole('button', { name: /Cài ứng dụng vibefix trên GitHub/ })).toBeNull()
  })

  it('says when GitHub came back with nothing installed and offers the install again', { timeout: 15_000 }, async () => {
    window.sessionStorage.setItem('vibefix.product.registration', JSON.stringify(draft))
    fakeBackend(signedInUser, {
      'POST /api/v1/products/repository-check': () =>
        refusal(422, { code: 'GUARD_FAILED', reason: 'SOURCE_APP_MISSING', repository: 'cong-ty/app' }),
    })
    renderAt('/san-pham/moi?ung-dung=chua-thay')

    expect(await screen.findByText(/^Chưa thấy ứng dụng vibefix được cài cho kho cong-ty\/app/)).toBeInTheDocument()
    expect(await screen.findByRole('button', { name: /Cài lại trên GitHub/ })).toBeInTheDocument()
  })

  it('explains a wrong selection and offers to choose again', { timeout: 15_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(signedInUser, {
      'POST /api/v1/products/repository-check': () =>
        refusal(422, { code: 'GUARD_FAILED', reason: 'SOURCE_APP_WRONG_REPOSITORY', repository: 'lan-nguyen/shop-gom' }),
    })
    renderAt('/san-pham/moi')

    await user.click(await screen.findByRole('button', { name: 'Lovable' }))
    await user.click(screen.getByRole('button', { name: 'Tiếp tục' }))
    await user.type(await screen.findByLabelText('Đường dẫn kho mã trên GitHub'), 'https://github.com/lan-nguyen/shop-gom')
    await user.click(screen.getByRole('button', { name: 'Kiểm tra' }))

    expect(await screen.findAllByText(/phải được cài cho đúng một kho là lan-nguyen\/shop-gom/)).not.toHaveLength(0)
    expect(screen.getByRole('button', { name: /Chọn lại trên GitHub/ })).toBeInTheDocument()
  })

  it('shows a different workflow file with the line-ending warning, and a missing one', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    let answer = refusal(422, {
      code: 'GUARD_FAILED',
      reason: 'WORKFLOW_MISMATCH',
      repository: 'lan-nguyen/shop-gom',
      lineEndings: true,
    })
    fakeBackend(signedInUser, {
      'POST /api/v1/products/repository-check': () => json(checked),
      'POST /api/v1/products/workflow-check': () => answer,
    })
    renderAt('/san-pham/moi')

    await user.click(await screen.findByRole('button', { name: 'Lovable' }))
    await user.click(screen.getByRole('button', { name: 'Tiếp tục' }))
    await user.type(await screen.findByLabelText('Đường dẫn kho mã trên GitHub'), 'https://github.com/lan-nguyen/shop-gom')
    await user.click(screen.getByRole('button', { name: 'Kiểm tra' }))
    await screen.findByText('Ứng dụng vibefix được cài cho đúng kho mã này')
    await user.click(screen.getByRole('button', { name: 'Tiếp tục' }))
    await user.click(await screen.findByRole('button', { name: 'Kiểm tra' }))

    expect(await screen.findByText('File workflow khác bản vibefix công bố')).toBeInTheDocument()
    expect(screen.getByText(/kiểu xuống dòng của Windows \(CRLF\)/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Tiếp tục' })).toBeDisabled()
    // Story 23.5c: the file is there already, so GitHub's new-file page would refuse it: copy, then edit it on GitHub.
    expect(screen.queryByRole('link', { name: /Thêm file workflow/ })).toBeNull()
    const edit = screen.getByRole('link', { name: /Sửa file workflow/ })
    expect(edit).toHaveAttribute('href', 'https://github.com/lan-nguyen/shop-gom/edit/main/.github/workflows/vibefix.yml')
    expect(edit).toHaveAttribute('target', '_blank')
    const copy = screen.getAllByRole('button', { name: 'Sao chép nội dung' })
    expect(copy).toHaveLength(1)
    expect(copy[0].compareDocumentPosition(edit) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(screen.getByText(/trang sửa của GitHub không điền sẵn được nội dung/)).toBeInTheDocument()

    answer = refusal(422, { code: 'GUARD_FAILED', reason: 'WORKFLOW_MISSING', repository: 'lan-nguyen/shop-gom' })
    await user.click(screen.getByRole('button', { name: 'Kiểm tra' }))
    expect(await screen.findByText('Chưa có file workflow trong lan-nguyen/shop-gom')).toBeInTheDocument()
    expect(screen.queryByText(/CRLF/)).toBeNull()
    expect(screen.getByRole('link', { name: /Thêm file workflow/ })).toHaveAttribute(
      'href',
      `https://github.com/lan-nguyen/shop-gom/new/main?filename=.github%2Fworkflows%2Fvibefix.yml&value=${encodeURIComponent(workflowFile.content)}`,
    )
    expect(screen.queryByRole('link', { name: /Sửa file workflow/ })).toBeNull()
  })

  it('keeps a passed repository check and a matched workflow file past the "Kiểm tra" limit, and says when to try again (Story 23.5c)', { timeout: 30_000 }, async () => {
    const user = userEvent.setup()
    let limitRepository = false
    let limitWorkflow = false
    const limited = () =>
      new Response(JSON.stringify({ status: 429, code: 'RATE_LIMITED', reason: 'CHECK_LIMIT' }), {
        status: 429,
        headers: { 'Content-Type': 'application/problem+json', 'Retry-After': '1200' },
      })
    fakeBackend(signedInUser, {
      'POST /api/v1/products/repository-check': () => (limitRepository ? limited() : json(checked)),
      'POST /api/v1/products/workflow-check': () =>
        limitWorkflow ? limited() : json({ fullName: 'lan-nguyen/shop-gom', defaultBranch: 'main' }),
    })
    renderAt('/san-pham/moi')
    const tooMany = /^Bạn đã bấm “Kiểm tra” quá nhiều lần trong một giờ\. Hãy thử lại sau \d\d:\d\d \d\d\/\d\d\.$/

    await user.click(await screen.findByRole('button', { name: 'Lovable' }))
    await user.click(screen.getByRole('button', { name: 'Tiếp tục' }))
    await user.type(await screen.findByLabelText('Đường dẫn kho mã trên GitHub'), 'https://github.com/lan-nguyen/shop-gom')
    await user.click(screen.getByRole('button', { name: 'Kiểm tra' }))
    await screen.findByText('Dung lượng 38 MB, dưới giới hạn 250 MB')
    limitRepository = true
    await user.click(screen.getByRole('button', { name: 'Kiểm tra' }))

    expect(await screen.findByText(tooMany)).toBeInTheDocument()
    expect(screen.getByText('Dung lượng 38 MB, dưới giới hạn 250 MB')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Tiếp tục' })).toBeEnabled()
    await user.click(screen.getByRole('button', { name: 'Tiếp tục' }))

    expect(await screen.findByText('Bước 3/5')).toBeInTheDocument()
    await user.click(await screen.findByRole('button', { name: 'Kiểm tra' }))
    await screen.findByText('Đúng bản vibefix công bố')
    limitWorkflow = true
    await user.click(screen.getByRole('button', { name: 'Kiểm tra' }))

    expect(await screen.findByText(tooMany)).toBeInTheDocument()
    expect(screen.getByText('Đúng bản vibefix công bố')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Tiếp tục' })).toBeEnabled()
  })

  it('goes back to the workflow step when the registration finds vibefix.yml changed', { timeout: 30_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(signedInUser, {
      'POST /api/v1/products/repository-check': () => json(checked),
      'POST /api/v1/products/workflow-check': () => json({ fullName: 'lan-nguyen/shop-gom', defaultBranch: 'main' }),
      'POST /api/v1/products': () =>
        refusal(422, { code: 'GUARD_FAILED', reason: 'WORKFLOW_MISMATCH', repository: 'lan-nguyen/shop-gom' }),
    })

    await registerThroughTheNotices(user)

    expect(await screen.findByText('Bước 3/5')).toBeInTheDocument()
    expect(await screen.findByRole('link', { name: /Thêm file workflow/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Tiếp tục' })).toBeDisabled()
  })

  it('goes back to the repository step when the registration finds the Source App missing', { timeout: 30_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(signedInUser, {
      'POST /api/v1/products/repository-check': () => json(checked),
      'POST /api/v1/products/workflow-check': () => json({ fullName: 'lan-nguyen/shop-gom', defaultBranch: 'main' }),
      'POST /api/v1/products': () =>
        refusal(422, { code: 'GUARD_FAILED', reason: 'SOURCE_APP_MISSING', repository: 'lan-nguyen/shop-gom' }),
    })

    await registerThroughTheNotices(user)

    expect(await screen.findByText('Bước 2/5')).toBeInTheDocument()
    expect(await screen.findByLabelText('Đường dẫn kho mã trên GitHub')).toHaveValue('https://github.com/lan-nguyen/shop-gom')
    expect(screen.getByRole('button', { name: 'Tiếp tục' })).toBeDisabled()
  })

  it('opens the wizard for a user with no role, whose GitHub gate guards step 2 (Story 20.3)', async () => {
    fakeBackend(signedInWithGoogle)
    renderAt('/san-pham/moi')

    expect(await screen.findByText('Bước 1/5')).toBeInTheDocument()
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
    // vibefix does not scan code: no scan result or history, and no read but the Product. "Kiểm tra lại" reads the
    // Source App and the workflow file again (Story 23.5c), and the optional scan runs on GitHub only.
    expect(screen.getByText('Sẵn sàng')).toBeInTheDocument()
    expect(screen.getByText('Kiểm tra lần cuối lúc 10:00 16/10')).toBeInTheDocument()
    expect(
      screen.getByText('Ứng dụng vibefix được cài cho đúng kho lan-nguyen/shop-gom, và file workflow đúng bản vibefix công bố.'),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Kiểm tra lại' })).toBeEnabled()
    expect(screen.getByRole('link', { name: /Tự kiểm tra khóa bí mật trên GitHub/ })).toHaveAttribute(
      'href',
      'https://github.com/lan-nguyen/shop-gom/actions/workflows/vibefix.yml',
    )
    expect(screen.queryByRole('link', { name: /Thêm file workflow/ })).toBeNull()
    expect(screen.queryByText(/vấn đề/)).toBeNull()
    expect(screen.queryByRole('table')).toBeNull()
    const reads = seen
      .map((request) => new URL(request.url, 'http://localhost').pathname)
      .filter((pathname) => pathname.startsWith('/api/v1/products/'))
    expect(new Set(reads)).toEqual(new Set(['/api/v1/products/p1']))
  })

  it('shows a missing Source App with the plain install page, and "Kiểm tra lại" records it ready (Story 23.5c)', { timeout: 15_000 }, async () => {
    const user = userEvent.setup()
    let status = 'SOURCE_APP_MISSING'
    let checks = 0
    fakeBackend(signedInUser, {
      'GET /api/v1/products/p1': () =>
        json({
          ...product,
          sourceStatus: status,
          installUrl: status === 'SOURCE_APP_MISSING' ? 'https://github.com/apps/vibefix-dev-source/installations/new' : null,
        }),
      'POST /api/v1/products/p1/source-check': () => {
        checks++
        status = 'READY'
        return json({ status: 'READY', checkedAt: '2026-11-13T03:00:00Z', lineEndings: false })
      },
    })
    renderAt('/san-pham/p1')

    expect(await screen.findByText('Chưa cài ứng dụng vibefix')).toBeInTheDocument()
    expect(
      screen.getByText(
        'Ứng dụng vibefix chưa được cài trên lan-nguyen/shop-gom. vibefix cần ứng dụng này để đọc dự án và chạy việc sao chép.',
      ),
    ).toBeInTheDocument()
    const install = screen.getByRole('link', { name: /Cài ứng dụng vibefix trên GitHub/ })
    expect(install).toHaveAttribute('href', 'https://github.com/apps/vibefix-dev-source/installations/new')
    expect(install).toHaveAttribute('target', '_blank')
    expect(screen.getByText(/GitHub đưa bạn về trang Sản phẩm/)).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Kiểm tra lại' }))

    expect(await screen.findByText('Sẵn sàng')).toBeInTheDocument()
    expect(checks).toBe(1)
    expect(screen.queryByRole('link', { name: /Cài ứng dụng vibefix trên GitHub/ })).toBeNull()
  })

  it('shows the selection link of a Source App on the wrong repositories (Story 23.5c)', async () => {
    fakeBackend(signedInUser, {
      'GET /api/v1/products/p1': () => json({ ...product, sourceStatus: 'SOURCE_APP_WRONG_REPOSITORY', sourceApp }),
    })
    renderAt('/san-pham/p1')

    expect(await screen.findByText(/Ứng dụng vibefix phải được cài cho đúng một kho là lan-nguyen\/shop-gom/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Chọn lại trên GitHub/ })).toHaveAttribute(
      'href',
      'https://github.com/settings/installations/21',
    )
  })

  it('offers "Thêm file workflow" on GitHub\'s new-file page for a missing file (Story 23.5c)', async () => {
    fakeBackend(signedInUser, {
      'GET /api/v1/products/p1': () => json({ ...product, sourceStatus: 'WORKFLOW_MISSING' }),
    })
    renderAt('/san-pham/p1')

    expect(await screen.findByText('Thiếu file workflow')).toBeInTheDocument()
    expect(screen.getByText('Chưa có file workflow trong lan-nguyen/shop-gom')).toBeInTheDocument()
    const add = await screen.findByRole('link', { name: /Thêm file workflow/ })
    expect(add).toHaveAttribute(
      'href',
      `https://github.com/lan-nguyen/shop-gom/new/main?filename=.github%2Fworkflows%2Fvibefix.yml&value=${encodeURIComponent(workflowFile.content)}`,
    )
    expect(add).toHaveAttribute('target', '_blank')
    expect(screen.queryByRole('link', { name: /Sửa file workflow/ })).toBeNull()
    expect(screen.getByRole('link', { name: /Tải file vibefix.yml/ })).toHaveAttribute('download', 'vibefix.yml')
    expect(screen.getByRole('button', { name: 'Sao chép nội dung' })).toBeInTheDocument()
  })

  it('offers the copy and "Sửa file workflow" for a different file in one tone, and the CRLF hint "Kiểm tra lại" found (Story 23.5c)', { timeout: 15_000 }, async () => {
    const user = userEvent.setup()
    let checkedAt = '2026-10-16T03:00:00Z'
    fakeBackend(signedInUser, {
      'GET /api/v1/products/p1': () =>
        json({ ...product, sourceStatus: 'WORKFLOW_MISMATCH', sourceCheckedAt: checkedAt, defaultBranch: 'release/v2' }),
      'POST /api/v1/products/p1/source-check': () => {
        checkedAt = '2026-11-13T03:00:00Z'
        return json({ status: 'WORKFLOW_MISMATCH', checkedAt, lineEndings: true })
      },
    })
    renderAt('/san-pham/p1')

    expect(await screen.findByText('File workflow khác bản mẫu')).toBeInTheDocument()
    expect(screen.getByText('File workflow khác bản vibefix công bố')).toBeInTheDocument()
    const edit = await screen.findByRole('link', { name: /Sửa file workflow/ })
    expect(edit).toHaveAttribute(
      'href',
      'https://github.com/lan-nguyen/shop-gom/edit/release/v2/.github/workflows/vibefix.yml',
    )
    expect(edit).toHaveAttribute('target', '_blank')
    expect(screen.queryByRole('link', { name: /Thêm file workflow/ })).toBeNull()
    const copy = screen.getAllByRole('button', { name: 'Sao chép nội dung' })
    expect(copy).toHaveLength(1)
    expect(copy[0].compareDocumentPosition(edit) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(screen.getByRole('link', { name: /Tải file vibefix.yml/ })).toHaveAttribute('download', 'vibefix.yml')
    expect(screen.queryByText(/kiểu xuống dòng của Windows/)).toBeNull()
    // One tone per status: CUS-2's badge and CUS-4's alert alike.
    expect(screen.getByText('File workflow khác bản mẫu')).toHaveClass('bg-warning-soft')
    expect(screen.getByText('File workflow khác bản vibefix công bố').closest('[role="alert"]')).toHaveClass('bg-warning-soft')

    await user.click(screen.getByRole('button', { name: 'Kiểm tra lại' }))

    expect(await screen.findByText(/kiểu xuống dòng của Windows \(CRLF\)/)).toBeInTheDocument()
  })

  it('drops an earlier "Kiểm tra lại" answer once a later check is recorded (Story 23.5c)', { timeout: 15_000 }, async () => {
    const user = userEvent.setup()
    let checkedAt = '2026-10-16T03:00:00Z'
    fakeBackend(signedInUser, {
      'GET /api/v1/products/p1': () => json({ ...product, sourceStatus: 'WORKFLOW_MISMATCH', sourceCheckedAt: checkedAt }),
      'POST /api/v1/products/p1/source-check': () => {
        checkedAt = '2026-11-13T03:00:00Z'
        return json({ status: 'WORKFLOW_MISMATCH', checkedAt, lineEndings: true })
      },
    })
    const queryClient = createQueryClient()
    render(
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={createMemoryRouter(routes, { initialEntries: ['/san-pham/p1'] })} />
      </QueryClientProvider>,
    )

    await user.click(await screen.findByRole('button', { name: 'Kiểm tra lại' }))
    expect(await screen.findByText(/kiểu xuống dòng của Windows \(CRLF\)/)).toBeInTheDocument()

    // Another check (the drift poll, say) records the same status later: its CRLF state is unknown here.
    checkedAt = '2026-11-14T03:00:00Z'
    await queryClient.invalidateQueries({ queryKey: ['product', 'detail', 'p1'] })
    await waitFor(() => expect(screen.queryByText(/kiểu xuống dòng của Windows/)).toBeNull())
    expect(screen.getByText('Kiểm tra lần cuối lúc 10:00 14/11')).toBeInTheDocument()
  })

  it('says a Product no check has read yet is not checked, instead of ready (Story 23.5c)', async () => {
    fakeBackend(signedInUser, {
      'GET /api/v1/products/p1': () => json({ ...product, sourceCheckedAt: null }),
    })
    renderAt('/san-pham/p1')

    expect(
      await screen.findByText(
        'vibefix chưa kiểm tra ứng dụng vibefix và file workflow của lan-nguyen/shop-gom. Bấm “Kiểm tra lại” để kiểm tra ngay.',
      ),
    ).toBeInTheDocument()
    expect(screen.getByText('Chưa kiểm tra')).toBeInTheDocument()
    expect(screen.queryByText(/đúng bản vibefix công bố/)).toBeNull()
    expect(screen.queryByText(/từ khi đăng ký/)).toBeNull()
    expect(screen.getByRole('button', { name: 'Kiểm tra lại' })).toBeEnabled()
  })

  it('warns of a workflow file nearing its end date, with the copy and the edit page (R12, Story 23.5c)', async () => {
    fakeBackend(signedInUser, {
      'GET /api/v1/products/p1': () => json({ ...product, workflowEndsOn: '2026-01-25' }),
    })
    renderAt('/san-pham/p1')

    expect(
      await screen.findByText(
        'vibefix đã cập nhật file workflow. Hãy thêm lại bản mới trước 25/01/2026, nếu không các công việc của sản phẩm này sẽ không bắt đầu hay kết thúc được.',
      ),
    ).toBeInTheDocument()
    expect(await screen.findByRole('link', { name: /Sửa file workflow/ })).toHaveAttribute(
      'href',
      'https://github.com/lan-nguyen/shop-gom/edit/main/.github/workflows/vibefix.yml',
    )
    expect(screen.queryByRole('link', { name: /Thêm file workflow/ })).toBeNull()
    expect(screen.getAllByRole('button', { name: 'Sao chép nội dung' })).toHaveLength(1)
    expect(screen.getByText('Sẵn sàng')).toBeInTheDocument()
  })

  it('says when to try "Kiểm tra lại" again past the limit, and that GitHub did not answer (Story 23.5c)', { timeout: 15_000 }, async () => {
    const user = userEvent.setup()
    let answer = 0
    fakeBackend(signedInUser, {
      'GET /api/v1/products/p1': () => json(product),
      'POST /api/v1/products/p1/source-check': () => {
        answer++
        return answer === 1
          ? new Response(JSON.stringify({ status: 429, code: 'RATE_LIMITED', reason: 'CHECK_LIMIT' }), {
              status: 429,
              headers: { 'Content-Type': 'application/problem+json', 'Retry-After': '600' },
            })
          : refusal(422, { code: 'GUARD_FAILED', reason: 'GITHUB_UNAVAILABLE' })
      },
    })
    renderAt('/san-pham/p1')

    await user.click(await screen.findByRole('button', { name: 'Kiểm tra lại' }))
    expect(
      await screen.findByText(/^Bạn đã bấm “Kiểm tra” quá nhiều lần trong một giờ\. Hãy thử lại sau \d\d:\d\d \d\d\/\d\d\.$/),
    ).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Kiểm tra lại' }))
    expect(await screen.findByText('Chưa đọc được kho mã từ GitHub. Hãy thử lại sau ít phút.')).toBeInTheDocument()
    expect(screen.getByText('Sẵn sàng')).toBeInTheDocument()
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

  it('tells the Customer to uninstall the Source App before and after the deletion (R23)', { timeout: 15_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(signedInUser, {
      'GET /api/v1/products/p1': () => json({ ...product, sourceApp }),
      'GET /api/v1/products': () => json([]),
      'DELETE /api/v1/products/p1': () => new Response(null, { status: 204 }),
    })
    renderAt('/san-pham/p1')

    await user.click(await screen.findByRole('button', { name: 'Xóa sản phẩm' }))
    const dialog = await screen.findByRole('dialog', { name: 'Xóa Shop Gốm Bát Tràng?' })
    expect(within(dialog).getByText('Liên kết giữa sản phẩm và ứng dụng vibefix trên dự án gốc')).toBeInTheDocument()
    expect(within(dialog).getByText(/hãy gỡ ứng dụng trên GitHub/)).toBeInTheDocument()
    expect(within(dialog).getByRole('link', { name: /Mở cài đặt ứng dụng trên GitHub/ })).toHaveAttribute(
      'href',
      'https://github.com/settings/installations/21',
    )
    await user.click(within(dialog).getByRole('button', { name: 'Xóa sản phẩm' }))

    expect(await screen.findByText('Đã xóa Shop Gốm Bát Tràng. Còn một việc trên GitHub:')).toBeInTheDocument()
    expect(screen.getByText(/hãy gỡ ứng dụng trên GitHub/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Mở cài đặt ứng dụng trên GitHub/ })).toHaveAttribute(
      'href',
      'https://github.com/settings/installations/21',
    )
  })

  it('tells the Customer to deselect the repository when another Product uses the Source App (R23)', { timeout: 15_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(signedInUser, {
      'GET /api/v1/products/p1': () =>
        json({
          ...product,
          sourceApp: {
            ...sourceApp,
            accountLogin: 'cong-ty',
            accountType: 'Organization',
            settingsUrl: 'https://github.com/organizations/cong-ty/settings/installations/21',
            otherProducts: 1,
          },
        }),
    })
    renderAt('/san-pham/p1')

    await user.click(await screen.findByRole('button', { name: 'Xóa sản phẩm' }))
    const dialog = await screen.findByRole('dialog', { name: 'Xóa Shop Gốm Bát Tràng?' })
    expect(within(dialog).getByText(/bỏ chọn lan-nguyen\/shop-gom khỏi các kho mã của ứng dụng/)).toBeInTheDocument()
    expect(within(dialog).getByRole('link', { name: /Mở cài đặt ứng dụng trên GitHub/ })).toHaveAttribute(
      'href',
      'https://github.com/organizations/cong-ty/settings/installations/21',
    )
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
