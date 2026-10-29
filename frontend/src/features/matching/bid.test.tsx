import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { routes } from '@/app/routes'
import { capabilities, fakeBackend, inEveryJob, json, signedInUser } from '@/test/fakeBackend'

function renderAt(path: string) {
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  render(
    <QueryClientProvider client={createQueryClient()}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return router
}

const post = {
  id: 'j1',
  phase: 'FINDING_MENTOR',
  need: 'MENTOR',
  title: 'Sửa lỗi mất đơn hàng',
  productName: 'Shop Gốm Bát Tràng',
  productDescription: 'Cửa hàng gốm.',
  productTools: ['LOVABLE'],
  productOpenFindings: 0,
  problem: 'Đơn không hiện.',
  jobTypes: ['BUG_FIX'],
  skills: [],
  budget: 'FROM_2M_TO_5M',
  deadline: '2026-11-10',
  openedAt: '2026-10-15T03:00:00Z',
  lapsesAt: '2026-10-29T03:00:00Z',
  screenshots: [],
}

const capacity = (reasons: string[] = []) => json({ activeJobs: 0, jobLimit: 3, reasons })

const upload = {
  fileId: 'f1',
  fileName: 'bao-cao.pdf',
  sizeBytes: 2048,
  addedAt: '2026-10-16T03:00:00Z',
  fileUrl: '/api/v1/files/f1',
}

const proof = [
  {
    position: 1,
    kind: 'FILE',
    caption: 'Báo cáo kiểm thử tôi được phép chia sẻ',
    fileId: 'f1',
    fileName: 'bao-cao.pdf',
    sizeBytes: 2048,
    fileUrl: '/api/v1/files/f1',
  },
  {
    position: 2,
    kind: 'LINK',
    caption: 'Bài đánh giá ứng dụng của tôi',
    url: 'https://docs.example.vn/danh-gia',
    domain: 'docs.example.vn',
  },
]

const application = (overrides: object = {}) => ({
  id: 'a1',
  jobId: 'j1',
  jobTitle: 'Sửa lỗi mất đơn hàng',
  status: 'SUBMITTED',
  priceVnd: 800_000,
  timelineDays: 5,
  approach: 'Tái hiện lỗi, sửa và thêm kiểm thử.',
  submittedAt: '2026-10-16T03:00:00Z',
  proof,
  ...overrides,
})

const menThree = {
  'GET /api/v1/mentor/job-posts/j1': () => json(post),
  'GET /api/v1/mentor/job-posts/j1/customer-rating': () => json({ count: 0 }),
  'GET /api/v1/mentor/capacity': () => capacity(),
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('MEN-3, the bid action (UX-DR98, Story 21.2)', () => {
  it('opens FND-1 from "Chào giá làm người hướng dẫn", with no Team copy', { timeout: 15_000 }, async () => {
    fakeBackend(signedInUser, menThree)
    renderAt('/tim-viec/j1')

    const area = await screen.findByRole('region', { name: 'Chào giá làm người hướng dẫn' })
    expect(await within(area).findByRole('link', { name: 'Chào giá làm người hướng dẫn' })).toHaveAttribute(
      'href',
      '/tim-viec/j1/chao-gia',
    )
    expect(within(area).getByText(/bằng chứng năng lực của bạn/)).toBeInTheDocument()
    expect(screen.queryByText(/thành viên trong nhóm của bạn/)).toBeNull()
    expect(screen.queryByLabelText('Giá đề xuất')).toBeNull()
  })

  it('says the Mentor Job limit in place of the action', { timeout: 15_000 }, async () => {
    fakeBackend(signedInUser, { ...menThree, 'GET /api/v1/mentor/capacity': () => capacity(['MENTOR_JOB_LIMIT']) })
    renderAt('/tim-viec/j1')

    expect(await screen.findByText('Bạn đang hướng dẫn đủ 3 công việc. Hãy hoàn thành bớt rồi chào giá tiếp.')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Chào giá làm người hướng dẫn' })).toBeNull()
  })

  it('shows the sent bid with "Xem" and withdraws it with "Rút" after a confirmation', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    let withdrawn = false
    fakeBackend(signedInUser, {
      ...menThree,
      'GET /api/v1/mentor/applications': () =>
        json([application(withdrawn ? { status: 'WITHDRAWN', endReason: 'WITHDRAWN', endedAt: '2026-10-17T03:00:00Z' } : {})]),
      'POST /api/v1/mentor/applications/a1/withdraw': () => {
        withdrawn = true
        return json(application({ status: 'WITHDRAWN', endReason: 'WITHDRAWN' }))
      },
    })
    renderAt('/tim-viec/j1')

    expect(await screen.findByText('Đã gửi chào giá ngày 16/10')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Xem' })).toHaveAttribute('href', '/tim-viec/j1/chao-gia')
    await user.click(screen.getByRole('button', { name: 'Rút' }))
    const dialog = await screen.findByRole('dialog', { name: 'Rút đơn chào giá này?' })
    expect(within(dialog).getByText(/Khách hàng sẽ được báo/)).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Rút' }))

    expect(await screen.findByText('Bạn đã rút đơn chào giá này')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Rút' })).toBeNull()
  })

  it('shows a bid on hold, with no "Rút" once it is selected', { timeout: 15_000 }, async () => {
    fakeBackend(signedInUser, {
      ...menThree,
      'GET /api/v1/mentor/applications': () => json([application({ status: 'SELECTED' })]),
    })
    renderAt('/tim-viec/j1')

    expect(await screen.findByText('Khách hàng đã chọn đơn chào giá của bạn')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Rút' })).toBeNull()
  })
})

describe('FND-1, a bid with a quote and proof (UX-DR99, FR-13)', () => {
  it('sends the quote with a file and a link, N-27 above the proof, N-5 required', { timeout: 30_000 }, async () => {
    const user = userEvent.setup({ applyAccept: false })
    let sent: unknown
    let uploaded = 0
    fakeBackend(signedInUser, {
      ...menThree,
      'GET /api/v1/mentor/job-posts/j1/proof-files': () => json([]),
      'POST /api/v1/mentor/job-posts/j1/proof-files': () => {
        uploaded += 1
        return json(upload, 201)
      },
      'POST /api/v1/mentor/job-posts/j1/applications': async (request) => {
        sent = await request.json()
        return json(application(), 201)
      },
    })
    const router = renderAt('/tim-viec/j1/chao-gia')

    const price = await screen.findByLabelText('Giá đề xuất')
    await user.type(price, '800000')
    expect(price).toHaveValue('800.000')
    await user.type(screen.getByLabelText('Thời gian làm (ngày)'), '5')
    await user.type(screen.getByLabelText('Cách bạn sẽ làm'), 'Tái hiện lỗi, sửa và thêm kiểm thử.')
    const note = screen.getByRole('note')
    expect(note).toHaveTextContent('Tệp và liên kết bạn đính kèm chỉ khách hàng của công việc này xem được')
    expect(screen.getByRole('heading', { name: 'Bằng chứng năng lực' })).toBeInTheDocument()

    const input = document.querySelector('input[type="file"]') as HTMLInputElement
    await user.upload(input, new File(['%PDF-1.4'], 'bao-cao.pdf', { type: 'application/pdf' }))
    await user.type(await screen.findByLabelText('Mô tả cho bao-cao.pdf'), 'Báo cáo kiểm thử tôi được phép chia sẻ')
    await user.type(screen.getByLabelText('Liên kết'), 'https://docs.example.vn/danh-gia')
    await user.type(screen.getByLabelText('Mô tả liên kết'), 'Bài đánh giá ứng dụng của tôi')
    await user.click(screen.getByRole('button', { name: 'Thêm liên kết' }))
    expect(await screen.findByText('https://docs.example.vn/danh-gia (docs.example.vn)')).toBeInTheDocument()
    expect(screen.getByText('Đã thêm 2/5 mục.')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Gửi chào giá' }))
    expect(await screen.findByText('Hãy đánh dấu ô xác nhận trước khi gửi chào giá.')).toBeInTheDocument()
    await user.click(screen.getByRole('checkbox', { name: /Tôi hiểu mã tôi và nhóm làm/ }))
    await user.click(screen.getByRole('button', { name: 'Gửi chào giá' }))

    await waitFor(() =>
      expect(sent).toEqual({
        priceVnd: 800000,
        timelineDays: 5,
        approach: 'Tái hiện lỗi, sửa và thêm kiểm thử.',
        publicCodeAcknowledged: true,
        proof: [
          { kind: 'FILE', fileId: 'f1', caption: 'Báo cáo kiểm thử tôi được phép chia sẻ' },
          { kind: 'LINK', url: 'https://docs.example.vn/danh-gia', caption: 'Bài đánh giá ứng dụng của tôi' },
        ],
      }),
    )
    expect(uploaded).toBe(1)
    await waitFor(() => expect(router.state.location.pathname).toBe('/tim-viec/j1'))
  })

  it('refuses a sixth item and a link that is not https inline (SG-58)', { timeout: 30_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(signedInUser, {
      ...menThree,
      'GET /api/v1/mentor/job-posts/j1/proof-files': () => json([]),
    })
    renderAt('/tim-viec/j1/chao-gia')

    const url = await screen.findByLabelText('Liên kết')
    await user.type(url, 'http://docs.example.vn/x')
    await user.type(screen.getByLabelText('Mô tả liên kết'), 'Một')
    await user.click(screen.getByRole('button', { name: 'Thêm liên kết' }))
    expect(
      await screen.findByText('Liên kết phải bắt đầu bằng https:// và có tên trang, ví dụ https://vi-du.vn/bai-viet.'),
    ).toBeInTheDocument()

    for (let n = 1; n <= 5; n++) {
      await user.clear(url)
      await user.type(url, `https://a.vn/${n}`)
      await user.clear(screen.getByLabelText('Mô tả liên kết'))
      await user.type(screen.getByLabelText('Mô tả liên kết'), `Mục ${n}`)
      await user.click(screen.getByRole('button', { name: 'Thêm liên kết' }))
    }
    expect(await screen.findByText('Đã thêm 5/5 mục.')).toBeInTheDocument()
    await user.type(url, 'https://a.vn/6')
    await user.type(screen.getByLabelText('Mô tả liên kết'), 'Mục 6')
    await user.click(screen.getByRole('button', { name: 'Thêm liên kết' }))
    expect(await screen.findByText('Đơn chào giá có tối đa 5 bằng chứng, tính cả tệp và liên kết.')).toBeInTheDocument()
    expect(screen.getByText('Đã thêm 5/5 mục.')).toBeInTheDocument()
  })

  it.each([
    [
      'GitHub not linked, with the way to ACC-2 (FR-80)',
      { status: 403, code: 'FORBIDDEN', reason: 'GITHUB_NOT_LINKED' },
      'Bạn không thể làm việc này. Hãy liên kết GitHub trước.',
      ['Liên kết GitHub', '/ho-so/xac-minh'],
    ],
    [
      'a profile without a display name, with the way to ACC-1 (Story 19.7)',
      { status: 422, code: 'GUARD_FAILED', reason: 'PROFILE_REQUIRED' },
      'Hãy đặt tên hiển thị trong hồ sơ trước khi chào giá, để khách hàng biết ai đang chào giá.',
      ['Mở hồ sơ', '/ho-so'],
    ],
    [
      'a contributor read GitHub did not answer',
      { status: 422, code: 'GUARD_FAILED', reason: 'CONTRIBUTORS_UNKNOWN' },
      'chưa kiểm tra được kho mã của khách hàng nên chưa nhận đơn chào giá này. Việc này không do bạn; hãy thử lại sau.',
      undefined,
    ],
  ])('says %s inline', async (_, problem, text, link) => {
    const user = userEvent.setup()
    fakeBackend(signedInUser, {
      ...menThree,
      'GET /api/v1/mentor/job-posts/j1/proof-files': () => json([]),
      'POST /api/v1/mentor/job-posts/j1/applications': () => json(problem, problem.status),
    })
    renderAt('/tim-viec/j1/chao-gia')

    await user.type(await screen.findByLabelText('Giá đề xuất'), '800000')
    await user.type(screen.getByLabelText('Thời gian làm (ngày)'), '5')
    await user.type(screen.getByLabelText('Cách bạn sẽ làm'), 'Sửa lỗi.')
    await user.click(screen.getByRole('checkbox', { name: /Tôi hiểu mã tôi và nhóm làm/ }))
    await user.click(screen.getByRole('button', { name: 'Gửi chào giá' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(text)
    if (link) {
      expect(within(alert).getByRole('link', { name: link[0] })).toHaveAttribute('href', link[1])
    }
  }, 30_000)

  it('says a refusal of the API inline, the Job limit with its number', { timeout: 30_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(signedInUser, {
      ...menThree,
      'GET /api/v1/mentor/job-posts/j1/proof-files': () => json([]),
      'POST /api/v1/mentor/job-posts/j1/applications': () =>
        json({ status: 422, code: 'GUARD_FAILED', reason: 'MENTOR_JOB_LIMIT', limit: 3 }, 422),
    })
    renderAt('/tim-viec/j1/chao-gia')

    await user.type(await screen.findByLabelText('Giá đề xuất'), '800000')
    await user.type(screen.getByLabelText('Thời gian làm (ngày)'), '5')
    await user.type(screen.getByLabelText('Cách bạn sẽ làm'), 'Sửa lỗi.')
    await user.click(screen.getByRole('checkbox', { name: /Tôi hiểu mã tôi và nhóm làm/ }))
    await user.click(screen.getByRole('button', { name: 'Gửi chào giá' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Bạn đang hướng dẫn đủ 3 công việc. Hãy hoàn thành bớt rồi chào giá tiếp.',
    )
  })

  it("shows the Job's Customer that it is their own Job, with no form (ROLE_TAKEN)", { timeout: 15_000 }, async () => {
    fakeBackend(signedInUser, { ...menThree, ...inEveryJob('CUSTOMER') })
    renderAt('/tim-viec/j1/chao-gia')

    expect(await screen.findByRole('heading', { name: 'Đây là công việc bạn đăng.' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Mở công việc' })).toHaveAttribute('href', '/cong-viec/j1')
    expect(screen.queryByLabelText('Giá đề xuất')).toBeNull()
  })

  it('shows the GitHub gate in place of the form while GitHub is not linked (X1)', { timeout: 15_000 }, async () => {
    const unlinked = {
      ...signedInUser,
      githubLogin: undefined,
      account: { ...signedInUser.account!, capabilities: capabilities({ githubVerified: false }) },
    }
    fakeBackend(unlinked, menThree)
    renderAt('/tim-viec/j1/chao-gia')

    expect(await screen.findByRole('heading', { name: 'Liên kết GitHub để chào giá' })).toBeInTheDocument()
    expect(screen.queryByLabelText('Giá đề xuất')).toBeNull()
  })

  it('shows the sent bid read-only, its proof with the link domain and N-11', { timeout: 15_000 }, async () => {
    fakeBackend(signedInUser, { ...menThree, 'GET /api/v1/mentor/applications': () => json([application()]) })
    renderAt('/tim-viec/j1/chao-gia')

    expect(await screen.findByRole('heading', { level: 1, name: 'Đơn chào giá của bạn' })).toBeInTheDocument()
    expect(screen.getByText('Đã gửi chào giá ngày 16/10')).toBeInTheDocument()
    expect(screen.getByText('Báo cáo kiểm thử tôi được phép chia sẻ')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /bao-cao\.pdf/ })).toHaveAttribute('href', '/api/v1/files/f1')
    expect(screen.getByRole('link', { name: /docs\.example\.vn\/danh-gia/ })).toHaveAttribute('rel', 'noopener noreferrer')
    expect(screen.getByText('(docs.example.vn)')).toBeInTheDocument()
    expect(screen.getByText(/Trang này nằm ngoài vibefix \(docs\.example\.vn\)/)).toBeInTheDocument()
    expect(screen.queryByLabelText('Giá đề xuất')).toBeNull()
  })

  it('says a Job no longer Open plainly', { timeout: 15_000 }, async () => {
    fakeBackend(signedInUser, {})
    renderAt('/tim-viec/j1/chao-gia')

    expect(await screen.findByRole('heading', { name: 'Công việc không còn nhận chào giá.' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Tìm việc khác' })).toHaveAttribute('href', '/tim-viec')
  })
})
