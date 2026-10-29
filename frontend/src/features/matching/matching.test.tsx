import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { routes } from '@/app/routes'
import { fakeBackend, json, signedInUser, inEveryJob } from '@/test/fakeBackend'
import { PAYLOADS } from '@/test/payloads'
import { emptyMetrics, personReputation, publicPerson, shortHistory, verifiedFacts } from '@/test/people'

function renderAt(path: string) {
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  render(
    <QueryClientProvider client={createQueryClient()}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return router
}

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
  mentor: {
    displayName: 'Đỗ Mạnh Hùng',
    githubLogin: 'hungdm',
    yearsOfExperience: 8,
    memberSince: '2026-06-01T03:00:00Z',
    verified: verifiedFacts('hungdm', { school: 'Đại học Bách khoa Hà Nội', name: 'Đỗ Mạnh Hùng', major: 'Phần mềm' }),
  },
  invited: false,
  fitMetrics: emptyMetrics({
    sharedSkills: ['React'],
    jobTypeMatches: [{ jobType: 'BUG_FIX', asMentor: 2, asCrew: 1 }],
    jobsAsMentor: 3,
    jobsAsCrew: 1,
    onTime: { onTime: 3, total: 4 },
    activeJobsAsMentor: 1,
    verifications: ['GITHUB', 'STUDENT'],
  }),
  proof: [
    {
      position: 1,
      kind: 'FILE',
      caption: 'Báo cáo kiểm thử',
      fileId: 'f1',
      fileName: 'bao-cao.pdf',
      sizeBytes: 2048,
      fileUrl: '/api/v1/files/f1',
    },
    { position: 2, kind: 'LINK', caption: 'Bài đánh giá', url: 'https://docs.example.vn/danh-gia', domain: 'docs.example.vn' },
  ],
}

const haApplication = {
  id: 'a2',
  status: 'SUBMITTED',
  priceVnd: 2_800_000,
  timelineDays: 10,
  approach: 'Tập trung vào lộ dữ liệu khách hàng.',
  submittedAt: '2026-10-16T04:00:00Z',
  mentor: { displayName: 'Phạm Thu Hà', githubLogin: 'hatp', yearsOfExperience: 6, verified: verifiedFacts('hatp') },
  invited: true,
  fitMetrics: emptyMetrics(),
  proof: [],
}

const minhApplication = {
  ...haApplication,
  id: 'a3',
  submittedAt: '2026-10-17T04:00:00Z',
  priceVnd: 4_000_000,
  mentor: { displayName: 'Ngô Văn Minh', githubLogin: 'minhnv', yearsOfExperience: 2, verified: verifiedFacts('minhnv') },
  invited: false,
}

/** The bids' histories in short: Hùng has history in both roles, Hà and Minh none. */
const histories = () =>
  json([
    shortHistory('hungdm', {
      mentor: { jobs: 3, average: 4.8, count: 6, portfolio: 2 },
      crew: { jobs: 1, average: 4, count: 1, portfolio: 1 },
      newcomer: false,
    }),
    shortHistory('hatp'),
    shortHistory('minhnv'),
  ])

/** CUS-8's handlers: the Open Job, its bids in the server's order (oldest first), and their histories. */
const bidHandlers = (bids: object[] = [hungApplication, haApplication, minhApplication]) => ({
  ...inEveryJob('CUSTOMER'),
  'GET /api/v1/jobs/j1': () => json(openJob),
  'GET /api/v1/jobs/j1/applications': () => json(bids),
  'GET /api/v1/reputation/histories': histories,
})

/** The Bid cards' names, top to bottom. */
function cardNames(): string[] {
  return screen.getAllByRole('listitem').flatMap((item) => {
    const name = item.getAttribute('aria-labelledby')
    return name ? [document.getElementById(name)?.textContent ?? ''] : []
  })
}

/** CUS-13's stacked columns, one per bid, in their order. */
function bidColumns(): HTMLElement[] {
  return screen.getAllByRole('region').filter((region) => region.querySelector('dl') !== null)
}

/** A wide screen for CUS-13 (≥ 1024 px), and the default narrow one otherwise. */
function wideScreen() {
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: query === '(min-width: 1024px)',
    media: query,
    addEventListener: () => {},
    removeEventListener: () => {},
  }))
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

  it('lists the Bid cards oldest first in the fixed order, with "Đã mời" only as a chip (UX-DR100)', { timeout: 20_000 }, async () => {
    const seen = fakeBackend(signedInUser, bidHandlers())
    renderAt('/cong-viec/j1/ung-tuyen')

    expect(await screen.findByText('Đỗ Mạnh Hùng')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Đơn chào giá (3)' })).toHaveAttribute('aria-current', 'page')
    expect(cardNames()).toEqual(['Đỗ Mạnh Hùng', 'Phạm Thu Hà', 'Ngô Văn Minh'])
    const [hung, ha, minh] = screen.getAllByRole('listitem').filter((item) => item.hasAttribute('aria-labelledby'))
    // The fixed order: Verified facts, what was posted, the two histories, then the Fit metrics.
    const bands = within(hung)
      .getAllByRole('region')
      .map((band) => band.getAttribute('aria-label'))
    expect(bands).toEqual(['Đã xác minh', 'Đơn chào giá', 'Lịch sử', 'Mức phù hợp với công việc này'])
    expect(within(hung).getByText('Xác minh qua GitHub · 10/2026')).toBeInTheDocument()
    expect(within(hung).getByText('Xác minh qua email trường · 09/2026')).toBeInTheDocument()
    expect(within(hung).getByText(/Đại học Bách khoa Hà Nội · Đỗ Mạnh Hùng · Phần mềm/)).toBeInTheDocument()
    expect(within(ha).getAllByText('Chưa xác minh')).toHaveLength(2)
    expect(within(hung).getByText('3.500.000 ₫')).toBeInTheDocument()
    expect(within(hung).getByText('2 bằng chứng')).toBeInTheDocument()
    expect(within(hung).getByText('3 công việc hoàn thành · 2 mục hồ sơ năng lực')).toBeInTheDocument()
    expect(within(hung).getByText('1 công việc hoàn thành · 1 mục hồ sơ năng lực')).toBeInTheDocument()
    // The Fit metrics, each with its formula as a Term hint, the on-time Milestones x/y, and no score.
    expect(within(hung).getByText('1/2 kỹ năng cần có (React)')).toBeInTheDocument()
    expect(within(hung).getByText('Đã làm 2 công việc Sửa lỗi khi làm người hướng dẫn · 1 khi làm thành viên nhóm')).toBeInTheDocument()
    expect(within(hung).getByText('3/4 giai đoạn')).toBeInTheDocument()
    expect(within(hung).getByText('2/3: GitHub, Sinh viên đã xác minh')).toBeInTheDocument()
    expect(within(hung).getByRole('button', { name: 'Đúng hạn' })).toBeInTheDocument()
    expect(within(hung).getByText('Chấm sao khi làm người hướng dẫn: 4,8 sao (6 lượt)')).toBeInTheDocument()
    expect(within(hung).getByText('Tham gia vibefix từ 06/2026')).toBeInTheDocument()
    // A new user: every history metric is "Chưa có dữ liệu", with "Mới" beside them; their histories read "Chưa có".
    expect(within(minh).getAllByText('Chưa có dữ liệu')).toHaveLength(5)
    expect(within(minh).getByText('Chấm sao khi làm người hướng dẫn: Mới')).toBeInTheDocument()
    expect(within(minh).getAllByText('Chưa có')).toHaveLength(2)
    expect(within(ha).getByText('Đã mời')).toBeInTheDocument()
    expect(within(hung).queryByText('Đã mời')).toBeNull()
    expect(within(minh).queryByText('Đã mời')).toBeNull()
    expect(screen.queryByText(/điểm phù hợp|xếp hạng #|thành viên trong nhóm/i)).toBeNull()
    // One batch of histories for the page, never one request per card (SG-43).
    const calls = seen.filter((request) => request.url.includes('/reputation/histories'))
    expect(calls).toHaveLength(1)
    expect(new URL(calls[0].url).searchParams.getAll('username')).toEqual(['hungdm', 'hatp', 'minhnv'])
  })

  it('opens CUS-12 at ?ho-so= with the bid in full, PUB-4, and the Fit metrics', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(signedInUser, {
      ...bidHandlers(),
      'GET /api/v1/users/hungdm': () => json(publicPerson('hungdm')),
      'GET /api/v1/users/hungdm/reputation': () => json(personReputation({ newcomer: false })),
    })
    const router = renderAt('/cong-viec/j1/ung-tuyen')

    await screen.findByText('Đỗ Mạnh Hùng')
    const hung = screen.getAllByRole('listitem').find((item) => item.hasAttribute('aria-labelledby')) as HTMLElement
    await user.click(within(hung).getByRole('button', { name: 'Xem hồ sơ' }))

    await waitFor(() => expect(router.state.location.search).toBe('?ho-so=a1'))
    const sheet = await screen.findByRole('dialog', { name: 'Đỗ Mạnh Hùng' })
    expect(within(sheet).getByText('Hồ sơ người chào giá')).toBeInTheDocument()
    expect(within(sheet).getByText('Báo cáo kiểm thử')).toBeInTheDocument()
    expect(within(sheet).getByRole('link', { name: /bao-cao\.pdf/ })).toHaveAttribute('href', '/api/v1/files/f1')
    expect(within(sheet).getByRole('link', { name: /docs\.example\.vn\/danh-gia/ })).toHaveAttribute('target', '_blank')
    expect(within(sheet).getByText(/Trang này nằm ngoài vibefix \(docs\.example\.vn\)/)).toBeInTheDocument()
    expect(await within(sheet).findByText('Kỹ sư phần mềm.')).toBeInTheDocument()
    expect(within(sheet).getByRole('heading', { name: 'Khi làm thành viên nhóm' })).toBeInTheDocument()
    expect(within(sheet).getByText('3/4 giai đoạn')).toBeInTheDocument()
    expect(within(sheet).getByRole('checkbox', { name: 'So sánh' })).toBeInTheDocument()
    await user.click(within(sheet).getByRole('button', { name: 'Chọn' }))

    expect(await screen.findByRole('dialog', { name: 'Chọn Đỗ Mạnh Hùng?' })).toBeInTheDocument()
  })

  it('ticks bids for "So sánh" in place, never moving a card, and opens CUS-13 with them', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(signedInUser, bidHandlers())
    renderAt('/cong-viec/j1/ung-tuyen')

    const boxes = await screen.findAllByRole('checkbox', { name: 'So sánh' })
    await user.click(boxes[2])
    expect(screen.getByText('Chọn 2 hoặc 3 đơn để so sánh.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'So sánh (1)' })).toBeDisabled()
    await user.click(boxes[0])

    expect(cardNames()).toEqual(['Đỗ Mạnh Hùng', 'Phạm Thu Hà', 'Ngô Văn Minh'])
    expect(screen.getByRole('link', { name: 'So sánh (2)' })).toHaveAttribute(
      'href',
      '/cong-viec/j1/ung-tuyen/so-sanh?ids=a3,a1',
    )
    await user.click(boxes[1])
    expect(screen.getByText('Đã chọn đủ 3 đơn.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'So sánh (3)' })).toBeInTheDocument()
  })

  it('compares the chosen bids in fixed rows, stacked under a sticky header of names below 1024 px (UX-DR101)', { timeout: 20_000 }, async () => {
    fakeBackend(signedInUser, bidHandlers())
    renderAt('/cong-viec/j1/ung-tuyen/so-sanh?ids=a2,a1')

    expect(await screen.findByRole('heading', { level: 1, name: 'So sánh 2 đơn chào giá' })).toBeInTheDocument()
    const header = screen.getByRole('list', { name: 'So sánh 2 đơn chào giá' })
    expect(within(header).getAllByRole('listitem').map((item) => item.textContent)).toEqual(['Phạm Thu Hà', 'Đỗ Mạnh Hùng'])
    const columns = bidColumns()
    expect(columns.map((column) => column.getAttribute('aria-label'))).toEqual(['Phạm Thu Hà', 'Đỗ Mạnh Hùng'])
    const rows = within(columns[1])
      .getAllByRole('term')
      .map((term) => term.textContent)
    expect(rows).toEqual([
      'Đã xác minh',
      'Giá và thời gian',
      'Cách làm',
      'Bằng chứng năng lực',
      'Khi làm người hướng dẫn',
      'Khi làm thành viên nhóm',
      'Kỹ năng khớp',
      'Cùng loại công việc',
      'Hoàn thành khi làm người hướng dẫn',
      'Hoàn thành khi làm thành viên nhóm',
      'Đúng hạn',
      'Đang hướng dẫn',
      'Xác minh đang có',
      'Thông tin hồ sơ',
    ])
    expect(within(columns[0]).getByText('Đã mời')).toBeInTheDocument()
    expect(within(columns[1]).getByText('3/4 giai đoạn')).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: 'Chọn' })).toHaveLength(2)
    expect(screen.queryByText(/^(tốt nhất|hạng \d+|tổng điểm.*)$/i)).toBeNull()
  })

  it('lays CUS-13 out as a table of columns on a wide screen, with "Chọn" under each', { timeout: 20_000 }, async () => {
    wideScreen()
    fakeBackend(signedInUser, bidHandlers())
    renderAt('/cong-viec/j1/ung-tuyen/so-sanh?ids=a1,a2,a3')

    const table = await screen.findByRole('table', { name: 'So sánh 3 đơn chào giá' })
    expect(within(table).getAllByRole('columnheader').map((header) => header.textContent)).toEqual([
      expect.stringContaining('Đỗ Mạnh Hùng'),
      expect.stringContaining('Phạm Thu Hà'),
      expect.stringContaining('Ngô Văn Minh'),
    ])
    expect(within(table).getAllByRole('rowheader').map((header) => header.textContent)[0]).toBe('Đã xác minh')
    expect(within(table).getAllByRole('button', { name: 'Chọn' })).toHaveLength(3)
  })

  it('goes back to CUS-8 with fewer than two of the Job\'s bids', { timeout: 15_000 }, async () => {
    fakeBackend(signedInUser, bidHandlers())
    const router = renderAt('/cong-viec/j1/ung-tuyen/so-sanh?ids=a1,khong-co')

    await waitFor(() => expect(router.state.location.pathname).toBe('/cong-viec/j1/ung-tuyen'))
  })

  it('removes a bid from CUS-13, and goes back below two', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(signedInUser, bidHandlers())
    const router = renderAt('/cong-viec/j1/ung-tuyen/so-sanh?ids=a1,a2,a3')

    await screen.findByRole('heading', { level: 1, name: 'So sánh 3 đơn chào giá' })
    await user.click(within(bidColumns()[1]).getByRole('button', { name: 'Bỏ khỏi so sánh' }))
    await waitFor(() => expect(router.state.location.search).toBe('?ids=a1,a3'))
    await user.click(within(bidColumns()[0]).getByRole('button', { name: 'Bỏ khỏi so sánh' }))
    await waitFor(() => expect(router.state.location.pathname).toBe('/cong-viec/j1/ung-tuyen'))
  })

  it('selects after the Confirmation dialog with the loaded version and lands on JOB-1', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    let sent: unknown
    let selected = false
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1': () => json(selected ? selectingJob : openJob),
      'GET /api/v1/jobs/j1/applications': () => json([hungApplication, haApplication]),
      'GET /api/v1/reputation/histories': histories,
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
    expect(screen.queryByRole('link', { name: /Đơn chào giá/ })).toBeNull()
  })

  it('shows the Stale dialog for a Job that moved on, and never re-sends', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    let sends = 0
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1': () => json(openJob),
      'GET /api/v1/jobs/j1/applications': () => json([hungApplication]),
      'GET /api/v1/reputation/histories': histories,
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
            mentor: { displayName: 'A', yearsOfExperience: 1 },
            proof: [],
          },
        ]),
      'GET /api/v1/products/p1': () => json({ id: 'p1', openFindings: 0 }),
    })
    renderAt('/cong-viec/j1')

    expect(await screen.findByRole('heading', { level: 2, name: '1 đơn chào giá' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Xem các đơn chào giá' })).toHaveAttribute('href', '/cong-viec/j1/ung-tuyen')
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
            mentor: {
              displayName: payload,
              yearsOfExperience: 1,
              verified: verifiedFacts('x', { school: payload, name: payload, major: payload }),
            },
            invited: false,
            fitMetrics: emptyMetrics({ sharedSkills: [payload] }),
            proof: [{ position: 1, kind: 'LINK', caption: payload, url: 'https://a.vn/x', domain: 'a.vn' }],
          },
        ]),
    })
    renderAt('/cong-viec/j1/ung-tuyen')

    expect((await screen.findAllByText(payload)).length).toBeGreaterThan(0)
    expect(document.querySelector('img[src="x"]')).toBeNull()
  })
})
