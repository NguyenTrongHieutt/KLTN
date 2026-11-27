import { render, screen, within } from '@testing-library/react'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { routes } from '@/app/routes'
import { fakeBackend, json, signedInUser, inEveryJob } from '@/test/fakeBackend'

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
})

/** One line of MEN-7: by default the Mentor's paid part of a final split, with the Crew's part beside it. */
const release = (overrides: object = {}) => ({
  jobId: 'j1',
  jobTitle: 'Chỉnh giao diện điện thoại',
  jobLive: true,
  milestoneId: 'm1',
  number: 1,
  milestoneTitle: 'Giao diện điện thoại',
  role: 'MENTOR',
  status: 'FINAL',
  finality: 'CONFIRMED',
  finalAt: '2026-08-25T03:00:00Z',
  pending: false,
  releasedAt: '2026-08-25T03:00:00Z',
  baseAmountVnd: 1200000,
  yourPartVnd: 240000,
  yourPercent: 20,
  splitVersionNo: 1,
  crew: [{ name: 'Trần Quang Minh', erased: false, amountVnd: 960000, percent: 80 }],
  ...overrides,
})

/** A split the reader is a party of, not paid out yet: its pool and no part. */
const pending = (overrides: object = {}) =>
  release({
    jobId: 'j2',
    jobTitle: 'Sửa lỗi đặt bàn',
    milestoneId: 'm7',
    number: 2,
    milestoneTitle: 'Sửa lỗi trùng giờ',
    role: 'CREW',
    status: 'AWAITING_DRAFT',
    finality: undefined,
    finalAt: undefined,
    pending: true,
    baseAmountVnd: 3086420,
    yourPartVnd: 0,
    yourPercent: undefined,
    splitVersionNo: undefined,
    crew: [],
    ...overrides,
  })

const customerHistory = (jobs: object[] = []) => ({ heldVnd: 2000000, releasedVnd: 1200000, refundedVnd: 500000, jobs })

const customerJob = {
  jobId: 'j1',
  title: 'Sửa lỗi đặt bàn và thêm nhắc lịch',
  state: 'ACTIVE',
  rows: [
    { at: '2026-10-20T07:55:00Z', kind: 'REFUND', number: 1, amountVnd: 500000, status: 'CONFIRMED', simulated: true, paymentOrderId: 'o2' },
    { at: '2026-10-20T07:30:00Z', kind: 'FUNDING', number: 1, amountVnd: 2000000, status: 'PAID', simulated: false, paymentOrderId: 'o1' },
  ],
  milestones: [{ milestoneId: 'm1', number: 1, title: 'Sửa lỗi trùng giờ', state: 'ACCEPTED', releasedTotalVnd: 1200000 }],
}

const balance = (lines: object[], overrides: object = {}) => ({ balanceVnd: 0, releases: 0, lines, ...overrides })

describe('MEN-7: a release of a deleted Product (AD-24)', () => {
  it('names the Job as deleted and the Milestone by its number alone', { timeout: 15_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('MENTOR'),
      'GET /api/v1/balance': () =>
        json(
          balance([release({ jobLive: false, jobTitle: '', number: 2, milestoneTitle: '' })], {
            balanceVnd: 240000,
            releases: 1,
            sentToCrewVnd: 960000,
            heldVnd: 0,
          }),
        ),
    })
    renderAt('/so-du')

    expect(await screen.findByText('Công việc đã xóa')).toBeInTheDocument()
    expect(screen.getByText('Giai đoạn 2')).toBeInTheDocument()
    expect(screen.queryByText(/Giai đoạn 2 ·/)).toBeNull()
    expect(screen.getByText(/Trần Quang Minh/)).toBeInTheDocument()
  })

  it('keeps a pending line of a deleted Product on its way to JOB-10', { timeout: 15_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CREW'),
      'GET /api/v1/balance': () => json(balance([pending({ jobLive: false, jobTitle: '', milestoneTitle: '' })])),
    })
    renderAt('/so-du')

    expect(await screen.findByText('Công việc đã xóa')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Xem phân chia' })).toHaveAttribute('href', '/cong-viec/j2/phan-chia/m7')
  })
})

describe('CUS-10 "Lịch sử thanh toán": two sections (FR-44, UX-DR85)', () => {
  it('lists fundings and refunds per Job with one released total per Milestone', { timeout: 15_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/payments/history': () => json(customerHistory([customerJob])),
      'GET /api/v1/balance': () => json(balance([])),
    })
    renderAt('/lich-su-thanh-toan')

    expect(await screen.findByRole('heading', { level: 1, name: 'Lịch sử thanh toán' })).toBeInTheDocument()
    expect(screen.getByText('Giao dịch thử nghiệm — không dùng tiền thật')).toBeInTheDocument()
    expect(screen.getByText(/được hoàn với vai trò khách hàng, theo từng công việc/)).toBeInTheDocument()
    expect((await screen.findByText('vibefix đang giữ')).parentElement).toHaveTextContent('2.000.000 VND')
    expect(screen.getByText('Đã chuyển cho người hướng dẫn và nhóm thực hiện').parentElement).toHaveTextContent(
      '1.200.000 VND',
    )
    expect(screen.getByText('Đã hoàn lại').parentElement).toHaveTextContent('500.000 VND')
    const table = screen.getByRole('table')
    expect(within(table).getByText('Hoàn tiền mô phỏng · Giai đoạn 1')).toBeInTheDocument()
    expect(within(table).getByText('Đã thanh toán')).toBeInTheDocument()
    expect(screen.getByText('Giai đoạn 1 · Sửa lỗi trùng giờ: đã chuyển 1.200.000 VND')).toBeInTheDocument()
    // Under the test-mode banner the table says it once, in its amount column's header; amounts outside it keep the tag.
    expect(within(table).getByRole('columnheader', { name: 'Số tiền (thử nghiệm)' })).toBeInTheDocument()
    expect(within(table).queryByText('tiền thử nghiệm, không phải tiền thật')).toBeNull()
    expect(screen.getAllByText('tiền thử nghiệm, không phải tiền thật').length).toBeGreaterThan(2)
    expect(screen.queryByRole('heading', { name: 'Phần của bạn' })).toBeNull()
  })

  it('keeps what the user received on "Số dư thử nghiệm" alone, and links there (2026-11-24)', { timeout: 15_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/payments/history': () => json(customerHistory([customerJob])),
      'GET /api/v1/balance': () =>
        json(
          balance([
            release({ role: 'CREW', crew: [], jobId: 'j3', jobTitle: 'Báo cáo bảo mật' }),
            pending(),
            // A Mentor's line with a Crew part: the history lists the user's own parts, never the Crew's.
            release(),
          ]),
        ),
    })
    renderAt('/lich-su-thanh-toan')

    expect(
      await screen.findByRole('heading', { level: 2, name: 'Bạn thanh toán với vai trò khách hàng' }),
    ).toBeInTheDocument()
    // The same lines stood here a second time as "Phần của bạn"; they are MEN-7's, with the Crew's parts.
    expect(screen.queryByRole('heading', { name: 'Phần của bạn' })).toBeNull()
    expect(screen.queryByText('Báo cáo bảo mật')).toBeNull()
    expect(screen.queryByText('Chờ phân chia')).toBeNull()
    expect(
      screen.getByRole('link', { name: 'Phần bạn nhận khi hướng dẫn hoặc tham gia nhóm nằm ở Số dư thử nghiệm.' }),
    ).toHaveAttribute('href', '/so-du')
  })

  it('says a user who funded nothing has no payment, whatever they received', { timeout: 15_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CREW'),
      'GET /api/v1/payments/history': () => json(customerHistory()),
      'GET /api/v1/balance': () => json(balance([release({ role: 'CREW', crew: [] })])),
    })
    renderAt('/lich-su-thanh-toan')

    expect(await screen.findByText('Bạn chưa có khoản thanh toán nào.')).toBeInTheDocument()
    expect(screen.queryByText('vibefix đang giữ')).toBeNull()
    expect(screen.queryByRole('heading', { name: 'Phần của bạn' })).toBeNull()
    expect(screen.getByRole('link', { name: /nằm ở Số dư thử nghiệm/ })).toHaveAttribute('href', '/so-du')
  })

  it('says so when there is nothing at all', { timeout: 15_000 }, async () => {
    fakeBackend(signedInUser, {
      'GET /api/v1/payments/history': () => json(customerHistory()),
      'GET /api/v1/balance': () => json(balance([])),
    })
    renderAt('/lich-su-thanh-toan')

    expect(await screen.findByText('Bạn chưa có khoản thanh toán nào.')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Phần của bạn' })).toBeNull()
  })
})

describe('MEN-7: the test Balance (FR-44, AD-15, AD-20, UX-DR106)', () => {
  it('shows the Mentor the Crew parts of each release', { timeout: 15_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('MENTOR'),
      'GET /api/v1/balance': () =>
        json(balance([release()], { balanceVnd: 240000, releases: 1, sentToCrewVnd: 960000, heldVnd: 2000000 })),
    })
    renderAt('/so-du')

    expect(await screen.findByRole('heading', { level: 1, name: 'Số dư thử nghiệm' })).toBeInTheDocument()
    expect(screen.getByText(/Số dư này không rút được/)).toBeInTheDocument()
    expect((await screen.findByText('Đã chuyển cho nhóm thực hiện')).parentElement).toHaveTextContent('960.000 VND')
    expect(screen.getByRole('columnheader', { name: 'Nhóm thực hiện' })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: 'Phiên bản phân chia' })).toBeInTheDocument()
    expect(screen.getByText(/Trần Quang Minh/)).toBeInTheDocument()
    expect(screen.getByText('Phiên bản 1')).toBeInTheDocument()
    expect(screen.getByText(/^Đã chốt ngày .* · mọi người đã xác nhận$/)).toBeInTheDocument()
    expect(screen.getByText('Bạn là: Người hướng dẫn')).toBeInTheDocument()
    expect(screen.getByText(/không thấy phần của từng người/)).toBeInTheDocument()
  })

  it("gives a Mentor with no line yet the Mentor's lead and note", { timeout: 15_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('MENTOR'),
      'GET /api/v1/balance': () => json(balance([], { sentToCrewVnd: 0, heldVnd: 3000000 })),
    })
    renderAt('/so-du')

    expect((await screen.findByText('Đang giữ chờ nghiệm thu')).parentElement).toHaveTextContent('3.000.000 VND')
    expect(screen.getByText(/chuyển theo phân chia đã chốt của giai đoạn đó/)).toBeInTheDocument()
    expect(screen.queryByText(/phần của bạn về số dư khi phân chia đã chốt/)).toBeNull()
    expect(screen.getByText(/không thấy phần của từng người/)).toBeInTheDocument()
    expect(screen.getByText('Chưa có lần chuyển tiền nào.')).toBeInTheDocument()
  })

  it('shows a Crew member only their own parts', { timeout: 15_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CREW'),
      'GET /api/v1/balance': () =>
        json(
          balance([release({ role: 'CREW', yourPartVnd: 960000, yourPercent: 80, crew: [] })], {
            balanceVnd: 960000,
            releases: 1,
          }),
        ),
    })
    renderAt('/so-du')

    expect(await screen.findByText('Từ 1 lần chuyển tiền')).toBeInTheDocument()
    expect(screen.queryByRole('columnheader', { name: 'Nhóm thực hiện' })).toBeNull()
    expect(screen.queryByText('Đã chuyển cho nhóm thực hiện')).toBeNull()
    expect(screen.getByText('80%')).toBeInTheDocument()
    expect(screen.getByText('Bạn là: Thành viên nhóm')).toBeInTheDocument()
    expect(screen.getByText(/phần của bạn về số dư khi phân chia đã chốt/)).toBeInTheDocument()
  })

  it('decides per line: a Mentor of one Job reads another Job as its Crew', { timeout: 15_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('MENTOR'),
      'GET /api/v1/balance': () =>
        json(
          balance(
            [
              release(),
              release({
                jobId: 'j3',
                jobTitle: 'Báo cáo bảo mật',
                milestoneId: 'm9',
                role: 'CREW',
                yourPartVnd: 700000,
                crew: [],
              }),
            ],
            { balanceVnd: 940000, releases: 2, sentToCrewVnd: 960000, heldVnd: 0 },
          ),
        ),
    })
    renderAt('/so-du')

    const rows = await screen.findAllByRole('row')
    const crewRow = rows.find((row) => within(row).queryByText('Báo cáo bảo mật'))!
    expect(within(crewRow).getByText('Bạn là: Thành viên nhóm')).toBeInTheDocument()
    expect(within(crewRow).queryByText(/Trần Quang Minh/)).toBeNull()
    const mentorRow = rows.find((row) => within(row).queryByText('Chỉnh giao diện điện thoại'))!
    expect(within(mentorRow).getByText(/Trần Quang Minh/)).toBeInTheDocument()
  })

  it('shows a pending split as "Chờ phân chia" with its pool, outside the Balance', { timeout: 15_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CREW'),
      'GET /api/v1/balance': () => json(balance([pending()])),
    })
    renderAt('/so-du')

    expect(await screen.findByText('Chờ phân chia')).toBeInTheDocument()
    expect(screen.getByText(/của Giai đoạn 2 đang chờ phân chia/)).toHaveTextContent('3.086.420 VND')
    expect(screen.getByText('Số dư của bạn').parentElement).toHaveTextContent('0 VND')
    expect(screen.getByRole('link', { name: 'Xem phân chia' })).toHaveAttribute('href', '/cong-viec/j2/phan-chia/m7')
    expect(screen.queryByText(/Đã chốt ngày/)).toBeNull()
  })

  it('shows a split under resolution as waiting for an Administrator', { timeout: 15_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CREW', 'FORMER'),
      'GET /api/v1/balance': () => json(balance([pending({ status: 'IN_RESOLUTION' })])),
    })
    renderAt('/so-du')

    expect(await screen.findByText('Đang chờ quản trị viên xem xét')).toBeInTheDocument()
    expect(screen.queryByText('Chờ phân chia')).toBeNull()
    expect(screen.getByText(/của Giai đoạn 2 đang chờ phân chia/)).toHaveTextContent('3.086.420 VND')
  })

  it('keeps a final split waiting for its payout pending', { timeout: 15_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CREW'),
      'GET /api/v1/balance': () =>
        json(balance([pending({ status: 'FINAL', finality: 'DEADLINE', finalAt: '2026-10-30T03:00:00Z' })])),
    })
    renderAt('/so-du')

    expect(await screen.findByText('Chờ phân chia')).toBeInTheDocument()
    expect(screen.queryByText(/Đã chốt ngày/)).toBeNull()
  })

  it('shows a zero part at 0 VND with the split final', { timeout: 15_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CREW'),
      'GET /api/v1/balance': () =>
        json(
          balance([
            release({
              role: 'CREW',
              yourPartVnd: 0,
              yourPercent: 0,
              splitVersionNo: undefined,
              finality: 'DECISION',
              crew: [],
            }),
          ]),
        ),
    })
    renderAt('/so-du')

    expect(await screen.findByText('0%')).toBeInTheDocument()
    expect(screen.getByText(/^Đã chốt ngày .* · quản trị viên đã quyết định$/)).toBeInTheDocument()
    expect(screen.queryByText('Chờ phân chia')).toBeNull()
  })

  it('says a split made final at its deadline was', { timeout: 15_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CREW'),
      'GET /api/v1/balance': () => json(balance([release({ role: 'CREW', finality: 'DEADLINE', crew: [] })])),
    })
    renderAt('/so-du')

    expect(await screen.findByText(/^Đã chốt ngày .* · hết hạn xác nhận$/)).toBeInTheDocument()
  })

  it('says the Mentor alone needed no split', { timeout: 15_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('MENTOR'),
      'GET /api/v1/balance': () =>
        json(balance([release({ finality: 'MENTOR_ONLY', yourPartVnd: 1200000, yourPercent: 100, crew: [] })])),
    })
    renderAt('/so-du')

    expect(await screen.findByText('Không cần phân chia · bạn nhận 100%')).toBeInTheDocument()
    expect(screen.queryByText(/^Đã chốt ngày/)).toBeNull()
  })

  it('names an erased party as JOB-10 does, and keeps their amount', { timeout: 15_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('MENTOR'),
      'GET /api/v1/balance': () =>
        json(
          balance(
            [
              release({
                crew: [
                  { name: '', erased: true, amountVnd: 480000, percent: 40 },
                  { name: '', erased: false, amountVnd: 480000, percent: 40 },
                ],
              }),
            ],
            { sentToCrewVnd: 960000, heldVnd: 0 },
          ),
        ),
    })
    renderAt('/so-du')

    expect((await screen.findByText('Người đã xóa tài khoản')).parentElement).toHaveTextContent('480.000 VND')
    expect(screen.getByText('Một thành viên')).toBeInTheDocument()
  })
})

const mentorJob = {
  id: 'j1',
  title: 'Sửa lỗi đặt bàn và thêm nhắc lịch',
  productName: 'Sổ đặt bàn Quán Mây',
  state: 'ACTIVE',
  version: 12,
  jobTypes: ['BUG_FIX'],
  selectedAt: '2026-10-18T03:00:00Z',
  codeJob: true,
  commitEmailNoticeAcknowledged: true,
  agreementConfirmed: true,
}

const mentorRelease = (overrides: object) => ({
  erased: false,
  splitVersionNo: 1,
  releasedAt: '2026-10-25T03:00:00Z',
  ...overrides,
})

const mentorMilestone = (overrides: object = {}) => ({
  milestoneId: 'm1',
  number: 1,
  title: 'Sửa lỗi trùng giờ',
  priceVnd: 2000000,
  state: 'ACCEPTED',
  fundedVnd: 2000000,
  topUpVnd: 0,
  refundedVnd: 0,
  releasedTotalVnd: 2000000,
  splitStatus: 'FINAL',
  finality: 'CONFIRMED',
  finalAt: '2026-10-25T03:00:00Z',
  pooledVnd: 2000000,
  paid: true,
  releases: [
    mentorRelease({ name: 'Đỗ Mạnh Hùng', role: 'MENTOR', amountVnd: 400000, percent: 20 }),
    mentorRelease({ name: 'Trần Quang Minh', role: 'CREW', amountVnd: 1600000, percent: 80 }),
  ],
  ...overrides,
})

/** A split not paid out yet, its pool still whole. */
const open = (splitStatus: string) => ({ splitStatus, finality: undefined, finalAt: undefined, paid: false, releases: [] })

function mentorBackend(milestone: object) {
  fakeBackend(signedInUser, {
    ...inEveryJob('MENTOR'),
    'GET /api/v1/mentor/jobs/j1': () => json(mentorJob),
    'GET /api/v1/jobs/j1/chat': () => json({ chatId: 'c1', unread: false }),
    'GET /api/v1/jobs/j1/splits': () => json([]),
    'GET /api/v1/mentor/jobs/j1/funding': () =>
      json({
        jobId: 'j1',
        title: 'Sửa lỗi đặt bàn và thêm nhắc lịch',
        state: 'ACTIVE',
        milestones: [milestone],
        orders: [{ number: 1, amountVnd: 2000000, status: 'PAID', createdAt: '2026-10-20T07:30:00Z' }],
      }),
  })
}

describe('JOB-7 for the Mentor: each split pending, then each party by part (FR-44, AD-20)', () => {
  it('lists the releases with their role, version, and percentage', { timeout: 15_000 }, async () => {
    mentorBackend(mentorMilestone())
    renderAt('/cong-viec/j1/thanh-toan')

    expect(await screen.findByText('Trần Quang Minh')).toBeInTheDocument()
    expect(screen.getByText('Nhóm thực hiện')).toBeInTheDocument()
    expect(screen.queryByText(/Điều khoản phiên bản/)).not.toBeInTheDocument()
    expect(screen.getByText('80%')).toBeInTheDocument()
    expect(screen.getByText(/^Đã chuyển:/)).toHaveTextContent('2.000.000 VND')
    expect(screen.getByText(/^Đã chốt ngày .* · mọi người đã xác nhận$/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Thanh toán' })).toHaveAttribute('aria-current', 'page')
  })

  it('shows an open split as "Chờ phân chia" with its pool and no "Đã chuyển"', { timeout: 15_000 }, async () => {
    mentorBackend(mentorMilestone(open('AWAITING_DRAFT')))
    renderAt('/cong-viec/j1/thanh-toan')

    expect(await screen.findByText('Chờ phân chia')).toBeInTheDocument()
    expect(screen.getByText(/của Giai đoạn 1 đang chờ phân chia/)).toHaveTextContent('2.000.000 VND')
    expect(screen.queryByText(/^Đã chuyển:/)).toBeNull()
    expect(screen.queryByText('Chưa chuyển tiền')).toBeNull()
    expect(screen.getByRole('link', { name: 'Xem phân chia' })).toHaveAttribute('href', '/cong-viec/j1/phan-chia/m1')
  })

  it('shows a split under resolution as waiting for an Administrator', { timeout: 15_000 }, async () => {
    mentorBackend(mentorMilestone(open('IN_RESOLUTION')))
    renderAt('/cong-viec/j1/thanh-toan')

    expect(await screen.findByText('Đang chờ quản trị viên xem xét')).toBeInTheDocument()
    expect(screen.queryByText(/^Đã chuyển:/)).toBeNull()
  })

  it('says nothing was released before any release', { timeout: 15_000 }, async () => {
    mentorBackend(
      mentorMilestone({
        state: 'FUNDED',
        releasedTotalVnd: 0,
        splitStatus: undefined,
        finality: undefined,
        finalAt: undefined,
        pooledVnd: undefined,
        paid: false,
        releases: [],
      }),
    )
    renderAt('/cong-viec/j1/thanh-toan')

    expect(await screen.findByText('Chưa chuyển tiền')).toBeInTheDocument()
    expect(screen.queryByText('Chờ phân chia')).toBeNull()
  })

  it('names an erased party as JOB-10 does', { timeout: 15_000 }, async () => {
    mentorBackend(
      mentorMilestone({
        releases: [
          mentorRelease({ name: '', erased: true, role: 'CREW', amountVnd: 1600000, percent: 80 }),
        ],
      }),
    )
    renderAt('/cong-viec/j1/thanh-toan')

    expect((await screen.findByText('Người đã xóa tài khoản')).parentElement).toHaveTextContent('1.600.000 VND')
  })
})

describe("JOB-7 for a Crew member: their own lines of the Job (D1, UX-DR106)", () => {
  const crewMe = {
    jobId: 'j2',
    title: 'Sửa lỗi đặt bàn',
    productName: 'Sổ đặt bàn Quán Mây',
    state: 'ACTIVE',
    former: false,
    canLeave: true,
    codeJob: false,
    mentorName: 'Đỗ Mạnh Hùng',
    partyMilestones: [],
  }

  it('shows a party their parts and split status of this Job only', { timeout: 15_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CREW'),
      'GET /api/v1/jobs/j2/crew/me': () => json(crewMe),
      'GET /api/v1/balance': () =>
        json(
          balance([
            pending(),
            release({ jobId: 'j2', jobTitle: 'Sửa lỗi đặt bàn', milestoneId: 'm6', role: 'CREW', yourPartVnd: 700000 }),
            release({ jobId: 'j9', jobTitle: 'Việc khác', milestoneId: 'm9', role: 'CREW', yourPartVnd: 555000 }),
          ]),
        ),
    })
    renderAt('/cong-viec/j2/thanh-toan')

    expect(await screen.findByRole('heading', { level: 1, name: 'Thanh toán · Sửa lỗi đặt bàn' })).toBeInTheDocument()
    expect(await screen.findByText('Chờ phân chia')).toBeInTheDocument()
    expect(screen.getByText(/^700\.000 VND/)).toBeInTheDocument()
    expect(screen.queryByText(/555\.000/)).toBeNull()
    expect(screen.queryByText('Việc khác')).toBeNull()
    expect(screen.getByText('Giao dịch thử nghiệm — không dùng tiền thật')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Về công việc' })).toHaveAttribute('href', '/cong-viec/j2')
    expect(screen.getAllByText('Bạn là: Thành viên nhóm')).toHaveLength(3)
  })

  it('reads each line of a member who left as JOB-H does', { timeout: 15_000 }, async () => {
    fakeBackend(signedInUser, {
      'GET /api/v1/jobs/*/role': () => json({ role: 'CREW', crewKind: 'FORMER', left: true, noCrew: false }),
      'GET /api/v1/jobs/j2/crew/me': () => json({ ...crewMe, former: true, canLeave: false }),
      'GET /api/v1/balance': () =>
        json(balance([pending(), release({ jobId: 'j2', milestoneId: 'm6', role: 'CREW', yourPartVnd: 700000 })])),
    })
    renderAt('/cong-viec/j2/thanh-toan')

    expect(await screen.findByText('Chờ phân chia')).toBeInTheDocument()
    // JOB-H's line under the heading, and each of the two lines.
    expect(screen.getAllByText('Bạn là: Thành viên nhóm · đã rời')).toHaveLength(3)
    expect(screen.queryByText('Bạn là: Thành viên nhóm')).toBeNull()
  })

  it('shows a member of no split an empty state', { timeout: 15_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CREW'),
      'GET /api/v1/jobs/j2/crew/me': () => json(crewMe),
      'GET /api/v1/balance': () => json(balance([release({ jobId: 'j9', role: 'CREW', crew: [] })])),
    })
    renderAt('/cong-viec/j2/thanh-toan')

    expect(await screen.findByText(/Bạn chưa có phần nào trong công việc này/)).toBeInTheDocument()
    expect(screen.queryByRole('table')).toBeNull()
  })
})

describe('JOB-7 for the Customer of a Self-mentored Job: the members’ parts beside the funding (UX-DR131)', () => {
  const selfJob = {
    id: 'j1',
    productId: 'p1',
    productName: 'Sổ đặt bàn Quán Mây',
    state: 'ACTIVE',
    title: 'Sửa lỗi đặt bàn và thêm nhắc lịch',
    jobTypes: ['BUG_FIX'],
    skills: [],
    screenshots: [],
    createdAt: '2026-10-12T03:00:00Z',
    updatedAt: '2026-10-18T03:00:00Z',
    version: 12,
    selfMentored: true,
    canCancelSelection: false,
    agreementShared: true,
    agreementConfirmed: true,
  }
  const customerFunding = {
    jobId: 'j1',
    title: selfJob.title,
    state: 'ACTIVE',
    version: 12,
    configured: true,
    milestones: [
      {
        milestoneId: 'm1',
        number: 1,
        title: 'Sửa lỗi trùng giờ',
        priceVnd: 2000000,
        state: 'ACCEPTED',
        fundable: false,
        fundedVnd: 2000000,
        topUpVnd: 0,
        refundedVnd: 0,
        releasedTotalVnd: 2000000,
        refundAtEndVnd: 0,
      },
    ],
    orders: [],
  }
  const memberRelease = (overrides: object) => mentorRelease({ role: 'CREW', ...overrides })

  function customerMentorBackend(role: 'CUSTOMER' | 'CUSTOMER_MENTOR', milestone: object) {
    return fakeBackend(signedInUser, {
      ...inEveryJob(role),
      'GET /api/v1/jobs/j1': () => json(role === 'CUSTOMER' ? { ...selfJob, selfMentored: false } : selfJob),
      'GET /api/v1/jobs/j1/funding': () => json(customerFunding),
      'GET /api/v1/jobs/j1/splits': () => json([]),
      'GET /api/v1/mentor/jobs/j1/funding': () =>
        json({ jobId: 'j1', title: selfJob.title, state: 'ACTIVE', milestones: [milestone], orders: [] }),
    })
  }

  it('keeps the funding and shows “Chờ phân chia” for a split not final, with the way to JOB-10', { timeout: 25_000 }, async () => {
    customerMentorBackend('CUSTOMER_MENTOR', mentorMilestone(open('AWAITING_DRAFT')))
    renderAt('/cong-viec/j1/thanh-toan')

    expect(await screen.findByRole('heading', { name: 'Phân chia tiền cho thành viên' })).toBeInTheDocument()
    expect(await screen.findByText('Chờ phân chia')).toBeInTheDocument()
    expect(screen.getByText(/của Giai đoạn 1 đang chờ phân chia/)).toHaveTextContent('2.000.000 VND')
    expect(screen.getByRole('link', { name: 'Xem phân chia' })).toHaveAttribute('href', '/cong-viec/j1/phan-chia/m1')
    // The Customer's own funding table is still there, and the combined role's tab row marks this tab.
    expect(screen.getByRole('table')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Thanh toán' })).toHaveAttribute('aria-current', 'page')
    expect(screen.queryByText('Người hướng dẫn')).toBeNull()
  })

  it('lists each member’s part once the split is final, and never the Customer as a payee', { timeout: 25_000 }, async () => {
    customerMentorBackend(
      'CUSTOMER_MENTOR',
      mentorMilestone({
        releases: [
          memberRelease({ name: 'Trần Quang Minh', amountVnd: 1200000, percent: 60 }),
          memberRelease({ name: 'Lê Thu Hà', amountVnd: 800000, percent: 40 }),
        ],
      }),
    )
    renderAt('/cong-viec/j1/thanh-toan')

    expect((await screen.findByText('Trần Quang Minh')).parentElement).toHaveTextContent('1.200.000 VND')
    expect(screen.getByText('Lê Thu Hà').parentElement).toHaveTextContent('800.000 VND')
    expect(screen.getByText('60%')).toBeInTheDocument()
    expect(screen.getAllByText('Nhóm thực hiện')).toHaveLength(2)
    expect(screen.getByText(/^Đã chốt ngày .* · mọi người đã xác nhận$/)).toBeInTheDocument()
    expect(screen.queryByText('Người hướng dẫn')).toBeNull()
  })

  it('says a Crew of one needed no split', { timeout: 25_000 }, async () => {
    customerMentorBackend(
      'CUSTOMER_MENTOR',
      mentorMilestone({
        finality: 'SOLE_MEMBER',
        releases: [memberRelease({ name: 'Trần Quang Minh', amountVnd: 2000000, percent: 100 })],
      }),
    )
    renderAt('/cong-viec/j1/thanh-toan')

    expect(await screen.findByText('Không cần phân chia · thành viên nhận 100%')).toBeInTheDocument()
    expect(screen.getByText('Trần Quang Minh').parentElement).toHaveTextContent('2.000.000 VND')
  })

  it('leaves a Customer of a normal Job with the funding alone, and asks for no split read', { timeout: 25_000 }, async () => {
    const seen = customerMentorBackend('CUSTOMER', mentorMilestone())
    renderAt('/cong-viec/j1/thanh-toan')

    expect(await screen.findByText('Chưa có lệnh thanh toán nào.')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Phân chia tiền cho thành viên' })).toBeNull()
    expect(seen.some((request) => request.url.includes('/mentor/jobs/j1/funding'))).toBe(false)
  })
})

describe('MEN-7 for the Customer of a Self-mentored Job and a member of a Crew of one (UX-DR131)', () => {
  it('shows an account with no line and no figure no Mentor lead, no held and no sent', { timeout: 25_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER_MENTOR'),
      'GET /api/v1/balance': () => json(balance([])),
    })
    renderAt('/so-du')

    expect(await screen.findByText('Chưa có lần chuyển tiền nào.')).toBeInTheDocument()
    expect(screen.queryByText('Đã chuyển cho nhóm thực hiện')).toBeNull()
    expect(screen.queryByText('Đang giữ chờ nghiệm thu')).toBeNull()
    expect(screen.queryByText(/Phần lẻ dưới 1 đồng thuộc về người hướng dẫn/)).toBeNull()
    expect(screen.queryByText(/không thấy phần của từng người/)).toBeNull()
    expect(screen.queryByRole('columnheader', { name: 'Nhóm thực hiện' })).toBeNull()
  })

  it('tells the one member of a Crew of one that no split was needed', { timeout: 25_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CREW'),
      'GET /api/v1/balance': () =>
        json(
          balance(
            [release({ role: 'CREW', finality: 'SOLE_MEMBER', yourPartVnd: 1200000, yourPercent: 100, crew: [] })],
            { balanceVnd: 1200000, releases: 1 },
          ),
        ),
    })
    renderAt('/so-du')

    expect(await screen.findByText('Không cần phân chia · bạn nhận 100%')).toBeInTheDocument()
    expect(screen.getByText('100%')).toBeInTheDocument()
  })
})
