import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { routes } from '@/app/routes'
import { fakeBackend, json, signedInUser, inEveryJob } from '@/test/fakeBackend'
import { PAYLOADS } from '@/test/payloads'

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

const capacity = (reasons: string[] = []) => json({ teamSize: 6, freeStudents: 2, activeJobs: 0, jobLimit: 3, reasons })

const application = (overrides: object = {}) => ({
  id: 'a1',
  jobId: 'j1',
  jobTitle: 'Sửa lỗi mất đơn hàng',
  status: 'SUBMITTED',
  priceVnd: 3_500_000,
  timelineDays: 7,
  approach: 'Tái hiện lỗi, sửa và thêm kiểm thử.',
  submittedAt: '2026-10-16T03:00:00Z',
  ...overrides,
})

const openJob = {
  id: 'j1',
  productId: 'p1',
  productName: 'Shop Gốm Bát Tràng',
  state: 'OPEN',
  title: 'Sửa lỗi mất đơn hàng',
  problem: 'Đơn không hiện.',
  jobTypes: ['BUG_FIX'],
  skills: [],
  budget: 'FROM_2M_TO_5M',
  deadline: '2026-11-10',
  screenshots: [],
  createdAt: '2026-10-15T03:00:00Z',
  updatedAt: '2026-10-15T03:00:00Z',
  version: 1,
  openedAt: '2026-10-15T03:00:00Z',
  lapsesAt: '2026-10-29T03:00:00Z',
}

const hungApplication = {
  id: 'a1',
  status: 'SUBMITTED',
  priceVnd: 3_500_000,
  timelineDays: 7,
  approach: 'Tái hiện lỗi, sửa và thêm kiểm thử.',
  submittedAt: '2026-10-16T03:00:00Z',
  mentor: { displayName: 'Đỗ Mạnh Hùng', githubLogin: 'hungdm', yearsOfExperience: 8, teamSize: 6 },
}

const haApplication = {
  id: 'a2',
  status: 'SUBMITTED',
  priceVnd: 2_800_000,
  timelineDays: 10,
  approach: 'Tập trung vào lộ dữ liệu khách hàng.',
  submittedAt: '2026-10-16T04:00:00Z',
  mentor: { displayName: 'Phạm Thu Hà', githubLogin: 'hatp', yearsOfExperience: 6, teamSize: 1 },
}

const publicHung = {
  accountId: 'm1',
  githubLogin: 'hungdm',
  displayName: 'Đỗ Mạnh Hùng',
  experience: 'Kỹ sư phần mềm.',
  yearsOfExperience: 8,
  memberSince: '2026-06-01T03:00:00Z',
  skillGroups: [],
  available: true,
  teamSize: 6,
  sizeLimit: 10,
  listedMembers: [],
}

const selectingJob = {
  ...openJob,
  state: 'SELECTING',
  version: 2,
  openedAt: undefined,
  lapsesAt: undefined,
  mentor: { displayName: 'Đỗ Mạnh Hùng', githubLogin: 'hungdm' },
  selectedAt: '2026-10-18T03:00:00Z',
  ndaDueAt: '2026-10-21T03:00:00Z',
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('MEN-3, applying with a quote (FR-13)', () => {
  it('formats the price while typing, needs N-5, and sends the Application', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    let sent: unknown
    let applied = false
    fakeBackend(signedInUser, {
      ...inEveryJob('MENTOR'),
      'GET /api/v1/mentor/job-posts/j1': () => json(post),
      'GET /api/v1/mentor/job-posts/j1/customer-rating': () => json({ count: 0 }),
      'GET /api/v1/mentor/capacity': () => capacity(),
      'GET /api/v1/mentor/applications': () => json(applied ? [application({ priceVnd: 800_000 })] : []),
      'POST /api/v1/mentor/job-posts/j1/applications': async (request) => {
        sent = await request.json()
        applied = true
        return json(application({ priceVnd: 800_000 }), 201)
      },
    })
    renderAt('/tim-viec/j1')

    const price = await screen.findByLabelText('Giá đề xuất')
    await user.type(price, '800000')
    expect(price).toHaveValue('800.000')
    await user.type(screen.getByLabelText('Thời gian làm (ngày)'), '7')
    await user.type(screen.getByLabelText('Cách bạn sẽ làm'), 'Tái hiện lỗi, sửa và thêm kiểm thử.')
    await user.click(screen.getByRole('button', { name: 'Ứng tuyển' }))
    expect(await screen.findByText('Hãy đánh dấu ô xác nhận trước khi ứng tuyển.')).toBeInTheDocument()
    await user.click(screen.getByRole('checkbox', { name: /Tôi hiểu mã tôi và nhóm làm/ }))
    await user.click(screen.getByRole('button', { name: 'Ứng tuyển' }))

    await waitFor(() =>
      expect(sent).toEqual({
        priceVnd: 800000,
        timelineDays: 7,
        approach: 'Tái hiện lỗi, sửa và thêm kiểm thử.',
        publicCodeAcknowledged: true,
      }),
    )
    expect(await screen.findByText('Bạn đã ứng tuyển công việc này')).toBeInTheDocument()
    expect(screen.getByText(/800\.000 ₫/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Rút đơn' })).toBeInTheDocument()
  })

  it('says GitHub must be linked before bidding and links to ACC-2 (FR-80)', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(signedInUser, {
      ...inEveryJob('MENTOR'),
      'GET /api/v1/mentor/job-posts/j1': () => json(post),
      'GET /api/v1/mentor/job-posts/j1/customer-rating': () => json({ count: 0 }),
      'GET /api/v1/mentor/capacity': () => capacity(),
      'GET /api/v1/mentor/applications': () => json([]),
      'POST /api/v1/mentor/job-posts/j1/applications': () => json({ status: 403, code: 'FORBIDDEN', reason: 'GITHUB_NOT_LINKED' }, 403),
    })
    renderAt('/tim-viec/j1')

    await user.type(await screen.findByLabelText('Giá đề xuất'), '800000')
    await user.type(screen.getByLabelText('Thời gian làm (ngày)'), '7')
    await user.type(screen.getByLabelText('Cách bạn sẽ làm'), 'Tái hiện lỗi, sửa và thêm kiểm thử.')
    await user.click(screen.getByRole('checkbox', { name: /Tôi hiểu mã tôi và nhóm làm/ }))
    await user.click(screen.getByRole('button', { name: 'Ứng tuyển' }))

    expect(await screen.findAllByText('Bạn không thể làm việc này. Hãy liên kết GitHub trước.')).not.toHaveLength(0)
    // Base UI hides a toast from the accessibility tree until the toasts region is reached (F6).
    expect(screen.getAllByRole('link', { name: 'Liên kết GitHub', hidden: true })[0]).toHaveAttribute('href', '/ho-so/xac-minh')
  })

  it('says a bid needs a display name and links to ACC-1 (Story 19.7)', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(signedInUser, {
      ...inEveryJob('MENTOR'),
      'GET /api/v1/mentor/job-posts/j1': () => json(post),
      'GET /api/v1/mentor/job-posts/j1/customer-rating': () => json({ count: 0 }),
      'GET /api/v1/mentor/capacity': () => capacity(),
      'GET /api/v1/mentor/applications': () => json([]),
      'POST /api/v1/mentor/job-posts/j1/applications': () =>
        json({ status: 422, code: 'GUARD_FAILED', reason: 'PROFILE_REQUIRED' }, 422),
    })
    renderAt('/tim-viec/j1')

    await user.type(await screen.findByLabelText('Giá đề xuất'), '800000')
    await user.type(screen.getByLabelText('Thời gian làm (ngày)'), '7')
    await user.type(screen.getByLabelText('Cách bạn sẽ làm'), 'Tái hiện lỗi, sửa và thêm kiểm thử.')
    await user.click(screen.getByRole('checkbox', { name: /Tôi hiểu mã tôi và nhóm làm/ }))
    await user.click(screen.getByRole('button', { name: 'Ứng tuyển' }))

    expect(
      await screen.findAllByText('Hãy đặt tên hiển thị trong hồ sơ trước khi ứng tuyển, để khách hàng biết ai đang ứng tuyển.'),
    ).not.toHaveLength(0)
    expect(screen.getAllByRole('link', { name: 'Mở hồ sơ', hidden: true })[0]).toHaveAttribute('href', '/ho-so')
  })

  it('keeps "Ứng tuyển" disabled while FR-13 refuses and says the reason from the server', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(signedInUser, {
      ...inEveryJob('MENTOR'),
      'GET /api/v1/mentor/job-posts/j1': () => json(post),
      'GET /api/v1/mentor/job-posts/j1/customer-rating': () => json({ count: 0 }),
      'GET /api/v1/mentor/capacity': () => capacity(['NO_FREE_STUDENT']),
      'GET /api/v1/mentor/applications': () => json([]),
    })
    renderAt('/tim-viec/j1')

    expect(await screen.findByRole('button', { name: 'Ứng tuyển' })).toBeDisabled()
    expect(screen.getAllByText('Nhóm của bạn chưa có thành viên rảnh, nên chưa ứng tuyển được.').length).toBeGreaterThan(0)
    await user.type(screen.getByLabelText('Giá đề xuất'), 'abc')
    expect(screen.getByLabelText('Giá đề xuất')).toHaveValue('')
  })

  it('withdraws the Application after a confirmation', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    let withdrawn = false
    fakeBackend(signedInUser, {
      ...inEveryJob('MENTOR'),
      'GET /api/v1/mentor/job-posts/j1': () => json(post),
      'GET /api/v1/mentor/job-posts/j1/customer-rating': () => json({ count: 0 }),
      'GET /api/v1/mentor/capacity': () => capacity(),
      'GET /api/v1/mentor/applications': () =>
        json([application(withdrawn ? { status: 'WITHDRAWN', endReason: 'WITHDRAWN', endedAt: '2026-10-17T03:00:00Z' } : {})]),
      'POST /api/v1/mentor/applications/a1/withdraw': () => {
        withdrawn = true
        return json(application({ status: 'WITHDRAWN', endReason: 'WITHDRAWN' }))
      },
    })
    renderAt('/tim-viec/j1')

    await user.click(await screen.findByRole('button', { name: 'Rút đơn' }))
    const dialog = await screen.findByRole('dialog', { name: 'Rút đơn ứng tuyển này?' })
    await user.click(within(dialog).getByRole('button', { name: 'Rút đơn' }))

    expect(await screen.findByText('Đơn của bạn đã được rút')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Rút đơn' })).toBeNull()
  })
})

describe('"Việc tôi hướng dẫn" on CUS-7, and CUS-8 (UX-DR78, UX-DR66, UX-DR86)', () => {
  it('lists the user\'s bids by status in "Việc tôi hướng dẫn"', { timeout: 15_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('MENTOR'),
      'GET /api/v1/mentor/applications': () =>
        json([
          application(),
          application({ id: 'a2', jobId: 'j2', jobTitle: 'Thêm đăng nhập Zalo', status: 'CLOSED', endReason: 'JOB_LAPSED' }),
        ]),
    })
    renderAt('/cong-viec')

    expect(await screen.findByRole('heading', { level: 2, name: /Đơn đang chờ/ })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Sửa lỗi mất đơn hàng/ })).toHaveAttribute('href', '/tim-viec/j1')
    expect(screen.getByText('Đang chờ khách hàng')).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 2, name: /Đơn đã đóng/ })).toBeInTheDocument()
    expect(screen.getByText('Tin đã hết hạn')).toBeInTheDocument()
    expect(screen.getByText(/Bạn chưa nhận công việc nào/)).toBeInTheDocument()
  })

  it('shows each Application with its Rating summary and opens the profile in a Sheet', { timeout: 15_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1': () => json(openJob),
      'GET /api/v1/jobs/j1/applications': () => json([hungApplication, haApplication]),
      'GET /api/v1/mentors/hungdm/reputation': () => json({ average: 4.8, count: 6, newcomer: false, portfolio: [] }),
      'GET /api/v1/mentors/hatp/reputation': () => json({ count: 0, newcomer: true, portfolio: [] }),
      'GET /api/v1/mentors/hungdm': () => json(publicHung),
    })
    renderAt('/cong-viec/j1/ung-tuyen')

    expect(await screen.findByText('Đỗ Mạnh Hùng')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Đơn ứng tuyển (2)' })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByText(/8 năm kinh nghiệm · nhóm 6 thành viên/)).toBeInTheDocument()
    expect(screen.getByText('3.500.000 ₫')).toBeInTheDocument()
    expect(await screen.findByText('4,8 sao')).toBeInTheDocument()
    expect(await screen.findByText('Mới')).toBeInTheDocument()
    expect(screen.getAllByText('0 mục hồ sơ năng lực').length).toBe(2)

    await user.click(screen.getAllByRole('button', { name: 'Xem hồ sơ' })[0])
    const sheet = await screen.findByRole('dialog')
    expect(await within(sheet).findByText('Kỹ sư phần mềm.')).toBeInTheDocument()
  })

  it('pins up to three Applications in "So sánh"', { timeout: 15_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1': () => json(openJob),
      'GET /api/v1/jobs/j1/applications': () => json([hungApplication, haApplication]),
    })
    renderAt('/cong-viec/j1/ung-tuyen')

    await user.click((await screen.findAllByRole('checkbox', { name: 'So sánh' }))[0])

    expect(await screen.findByRole('heading', { level: 2, name: 'Đang so sánh (1)' })).toBeInTheDocument()
    expect(screen.getByText('Đang so sánh 1 đơn')).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 2, name: 'Tất cả đơn ứng tuyển (2)' })).toBeInTheDocument()
  })

  it('selects after the Confirmation dialog with the loaded version and lands on JOB-1', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    let sent: unknown
    let selected = false
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1': () => json(selected ? selectingJob : openJob),
      'GET /api/v1/jobs/j1/applications': () => json([hungApplication, haApplication]),
      'POST /api/v1/jobs/j1/applications/a1/select': async (request) => {
        sent = await request.json()
        selected = true
        return json({ version: 2 })
      },
    })
    renderAt('/cong-viec/j1/ung-tuyen')

    await user.click((await screen.findAllByRole('button', { name: 'Chọn' }))[0])
    const dialog = await screen.findByRole('dialog', { name: 'Chọn Đỗ Mạnh Hùng?' })
    expect(within(dialog).getByText(/Đỗ Mạnh Hùng có 3 ngày để ký cam kết bảo mật/)).toBeInTheDocument()
    expect(within(dialog).getByText(/1 đơn còn lại chuyển sang tạm giữ/)).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Chọn Đỗ Mạnh Hùng' }))

    await waitFor(() => expect(sent).toEqual({ version: 1 }))
    expect(await screen.findByRole('heading', { level: 2, name: 'Đang chờ Đỗ Mạnh Hùng ký cam kết bảo mật' })).toBeInTheDocument()
    expect(screen.getByText('Chờ người hướng dẫn ký cam kết bảo mật')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /Đơn ứng tuyển/ })).toBeNull()
  })

  it('shows the Stale dialog for a Job that moved on, and never re-sends', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    let sends = 0
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1': () => json(openJob),
      'GET /api/v1/jobs/j1/applications': () => json([hungApplication]),
      'POST /api/v1/jobs/j1/applications/a1/select': () => {
        sends += 1
        return json({ status: 409, code: 'STALE', reason: 'STATE_CHANGED' }, 409)
      },
    })
    renderAt('/cong-viec/j1/ung-tuyen')

    await user.click(await screen.findByRole('button', { name: 'Chọn' }))
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Chọn Đỗ Mạnh Hùng' }))

    const stale = await screen.findByRole('dialog', { name: 'Thông tin đã thay đổi trong lúc bạn xem.' })
    expect(within(stale).getByText(/Quyết định của bạn chưa được gửi/)).toBeInTheDocument()
    await user.click(within(stale).getByRole('button', { name: 'Tải lại và xem' }))
    expect(sends).toBe(1)
  })

  it('counts the Applications on JOB-1', { timeout: 15_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1': () => json(openJob),
      'GET /api/v1/jobs/j1/applications': () =>
        json([
          {
            id: 'a1',
            status: 'SUBMITTED',
            priceVnd: 1000,
            timelineDays: 1,
            approach: 'x',
            submittedAt: '2026-10-16T03:00:00Z',
            mentor: { displayName: 'A', yearsOfExperience: 1, teamSize: 1 },
          },
        ]),
      'GET /api/v1/products/p1': () => json({ id: 'p1', openFindings: 0 }),
    })
    renderAt('/cong-viec/j1')

    expect(await screen.findByRole('heading', { level: 2, name: '1 đơn ứng tuyển' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Xem các đơn ứng tuyển' })).toHaveAttribute('href', '/cong-viec/j1/ung-tuyen')
  })

  it('keeps planted payloads in Applications as text (SG-30)', { timeout: 15_000 }, async () => {
    const payload = PAYLOADS[0]
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1': () => json(openJob),
      'GET /api/v1/jobs/j1/applications': () =>
        json([
          {
            id: 'a1',
            status: 'SUBMITTED',
            priceVnd: 1000,
            timelineDays: 1,
            approach: payload,
            submittedAt: '2026-10-16T03:00:00Z',
            mentor: { displayName: payload, yearsOfExperience: 1, teamSize: 1 },
          },
        ]),
    })
    renderAt('/cong-viec/j1/ung-tuyen')

    expect((await screen.findAllByText(payload)).length).toBeGreaterThan(0)
    expect(document.querySelector('img[src="x"]')).toBeNull()
  })
})
