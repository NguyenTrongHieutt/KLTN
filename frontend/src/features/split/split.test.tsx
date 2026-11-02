import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { routes } from '@/app/routes'
import { fakeBackend, json, signedInUser, inEveryJob } from '@/test/fakeBackend'
import type { SplitCard, SplitVersion } from './splitApi'

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
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

const parties = [
  { partyId: 'p0', name: 'Đỗ Mạnh Hùng', mentor: true, wholeInterval: true, activeAtRelease: true },
  { partyId: 'p1', name: 'Trần Thảo Vy', mentor: false, wholeInterval: true, activeAtRelease: true },
  {
    partyId: 'p2',
    name: 'Nguyễn Văn Minh',
    mentor: false,
    wholeInterval: false,
    activeAtRelease: false,
    leftAt: '2026-11-02T03:00:00Z',
  },
]

function version(overrides: Partial<SplitVersion> = {}): SplitVersion {
  return {
    versionNo: 1,
    kind: 'DRAFT',
    capPercent: 30,
    createdAt: '2026-11-03T03:00:00Z',
    lines: [
      { partyId: 'p0', percent: 30, amountVnd: 600_000, confirmed: false },
      { partyId: 'p1', percent: 40, amountVnd: 800_000, confirmed: false },
      { partyId: 'p2', percent: 30, amountVnd: 600_000, confirmed: false },
    ],
    ...overrides,
  }
}

function card(overrides: Partial<SplitCard> = {}): SplitCard {
  return {
    splitId: 's1',
    milestoneId: 'm1',
    milestoneNumber: 2,
    jobTitle: 'Sửa sổ đặt bàn',
    status: 'AWAITING_DRAFT',
    pooledVnd: 2_000_000,
    paid: false,
    capApplies: true,
    cap: 30,
    editsLeft: 3,
    editLimit: 3,
    draftDueAt: '2026-11-10T03:00:00Z',
    draftNear: false,
    draftLate: false,
    nextConfirmationDueAt: '2026-11-06T03:00:00Z',
    confirmationNear: false,
    pastDue: false,
    k: 0,
    n: 2,
    viewerIsMentor: true,
    viewerConfirmed: false,
    parties,
    earlier: [],
    ...overrides,
  }
}

function proposed(overrides: Partial<SplitCard> = {}): SplitCard {
  return card({ status: 'PROPOSED', confirmationDueAt: '2026-11-06T03:00:00Z', latest: version(), ...overrides })
}

const member = { viewerIsMentor: false }

function backend(cards: SplitCard[], handlers: Parameters<typeof fakeBackend>[1] = {}) {
  return fakeBackend(signedInUser, {
    'GET /api/v1/jobs/j1/splits': () => json(cards),
    ...handlers,
  })
}

function typePercent(name: RegExp, value: string) {
  fireEvent.change(screen.getByRole('textbox', { name }), { target: { value } })
}

describe('JOB-10, the Mentor', () => {
  it('drafts with whole percentages, the sum, the server preview, the chip and N-29', { timeout: 20_000 }, async () => {
    const seen = backend([card()], {
      'POST /api/v1/splits/s1/preview': () =>
        json({
          pooledVnd: 2_000_000,
          lines: [
            { partyId: 'p0', amountVnd: 600_000 },
            { partyId: 'p1', amountVnd: 800_000 },
            { partyId: 'p2', amountVnd: 600_000 },
          ],
        }),
      'POST /api/v1/splits/s1/versions': () =>
        json({ versionNo: 1, status: 'PROPOSED', confirmationDueAt: '2026-11-06T03:00:00Z' }),
    })
    renderAt('/cong-viec/j1/phan-chia')

    expect(await screen.findByText('Soạn phân chia trước 10/11')).toBeInTheDocument()
    expect(screen.getByText(/2\.000\.000 ₫/)).toBeInTheDocument()
    expect(screen.getByText('của Giai đoạn 2', { exact: false })).toBeInTheDocument()
    expect(screen.getByText('Đã rời 02/11')).toBeInTheDocument()
    expect(screen.getByText('Tối đa 30%')).toBeInTheDocument()
    expect(screen.getByText('Còn 3/3 lần sửa')).toBeInTheDocument()
    expect(
      screen.getByText(
        'Mỗi thành viên xác nhận trước 06/11, hoặc nhờ quản trị viên xem xét. Mỗi lần bạn sửa, các xác nhận cũ bị xóa và thời hạn bắt đầu lại; bạn còn 3/3 lần sửa. Phần của bạn tối đa 30%. Khách hàng không thấy phần chia.',
      ),
    ).toBeInTheDocument()
    const send = screen.getByRole('button', { name: 'Gửi để xác nhận' })
    expect(send).toBeDisabled()

    typePercent(/Đỗ Mạnh Hùng/, '30')
    typePercent(/Trần Thảo Vy/, '40')
    typePercent(/Nguyễn Văn Minh/, '20')
    expect(screen.getByText('Tổng 90%, cần đúng 100%')).toBeInTheDocument()
    expect(send).toBeDisabled()
    expect(seen.some((request) => request.url.endsWith('/preview'))).toBe(false)

    typePercent(/Nguyễn Văn Minh/, '30')
    expect(await screen.findByText(/800\.000 ₫/)).toBeInTheDocument()
    expect(send).toBeEnabled()
    fireEvent.click(send)

    await waitFor(() => expect(seen.some((request) => request.url.endsWith('/versions'))).toBe(true))
    const save = seen.find((request) => request.url.endsWith('/versions'))
    expect(await save?.clone().json()).toEqual({
      shownVersionNo: 0,
      shares: [
        { partyId: 'p0', percent: 30 },
        { partyId: 'p1', percent: 40 },
        { partyId: 'p2', percent: 30 },
      ],
    })
  })

  it('turns a broken rule red and disables the send with the reason', { timeout: 20_000 }, async () => {
    backend([card()])
    renderAt('/cong-viec/j1/phan-chia')
    await screen.findByText('Soạn phân chia trước 10/11')

    typePercent(/Đỗ Mạnh Hùng/, '31')
    typePercent(/Trần Thảo Vy/, '39')
    typePercent(/Nguyễn Văn Minh/, '30')
    expect(screen.getByText('Phần của bạn tối đa 30%')).toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: /Đỗ Mạnh Hùng/ })).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByRole('button', { name: 'Gửi để xác nhận' })).toBeDisabled()

    typePercent(/Đỗ Mạnh Hùng/, '30')
    typePercent(/Trần Thảo Vy/, '0')
    typePercent(/Nguyễn Văn Minh/, '70')
    expect(screen.getByText('Thành viên làm cả giai đoạn phải lớn hơn 0%')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Gửi để xác nhận' })).toBeDisabled()
  })

  it('has no cap and allows 100% when nobody was left at the acceptance (X5)', { timeout: 20_000 }, async () => {
    backend(
      [
        card({
          capApplies: false,
          cap: undefined,
          parties: [parties[0], parties[2]],
        }),
      ],
      {
        'POST /api/v1/splits/s1/preview': () => json({ pooledVnd: 2_000_000, lines: [] }),
      },
    )
    renderAt('/cong-viec/j1/phan-chia')

    expect(
      await screen.findByText(
        'Không còn thành viên nào trong nhóm khi giai đoạn được nghiệm thu: phần của người hướng dẫn không bị giới hạn, và người đã rời có thể nhận 0%.',
      ),
    ).toBeInTheDocument()
    expect(
      screen.getByText(/Giai đoạn này không còn thành viên nào trong nhóm khi được nghiệm thu/),
    ).toBeInTheDocument()
    expect(screen.queryByText(/Phần của bạn tối đa/)).toBeNull()
    expect(screen.queryByText(/Tối đa/)).toBeNull()

    typePercent(/Đỗ Mạnh Hùng/, '100')
    typePercent(/Nguyễn Văn Minh/, '0')
    expect(screen.getByRole('button', { name: 'Gửi để xác nhận' })).toBeEnabled()
  })

  it('reads a sent split with each party’s status and edits it from what it showed', { timeout: 20_000 }, async () => {
    const latest = version({
      lines: [
        { partyId: 'p0', percent: 30, amountVnd: 600_000, confirmed: false },
        { partyId: 'p1', percent: 40, amountVnd: 800_000, confirmed: true },
        { partyId: 'p2', percent: 30, amountVnd: 600_000, confirmed: false },
      ],
    })
    const seen = backend([proposed({ latest, k: 1, editsLeft: 2 })], {
      'POST /api/v1/splits/s1/preview': () => json({ pooledVnd: 2_000_000, lines: [] }),
      'POST /api/v1/splits/s1/versions': () =>
        json({ versionNo: 2, status: 'PROPOSED', confirmationDueAt: '2026-11-07T03:00:00Z' }),
    })
    renderAt('/cong-viec/j1/phan-chia')

    expect(await screen.findByText('Đã gửi · 1/2 đã xác nhận · hạn 06/11')).toBeInTheDocument()
    expect(screen.getByText('Đã xác nhận')).toBeInTheDocument()
    expect(screen.getByText('Chờ xác nhận')).toBeInTheDocument()
    expect(screen.getByText('Còn 2/3 lần sửa')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Sửa phân chia' }))
    expect(screen.getByRole('textbox', { name: /Trần Thảo Vy/ })).toHaveValue('40')
    typePercent(/Trần Thảo Vy/, '35')
    typePercent(/Nguyễn Văn Minh/, '35')
    fireEvent.click(screen.getByRole('button', { name: 'Gửi để xác nhận' }))

    await waitFor(() => expect(seen.some((request) => request.url.endsWith('/versions'))).toBe(true))
    const save = seen.find((request) => request.url.endsWith('/versions'))
    expect(await save?.clone().json()).toMatchObject({ shownVersionNo: 1 })
  })

  it('disables the edit once every edit is used', { timeout: 20_000 }, async () => {
    backend([proposed({ editsLeft: 0 })])
    renderAt('/cong-viec/j1/phan-chia')

    expect(await screen.findByRole('button', { name: 'Sửa phân chia' })).toBeDisabled()
    expect(screen.getByText('Đã dùng hết 3 lần sửa cho giai đoạn này.')).toBeInTheDocument()
  })

  it('cannot edit the default split, and reads it with its figures', { timeout: 20_000 }, async () => {
    backend([proposed({ latest: version({ kind: 'DEFAULT' }) })])
    renderAt('/cong-viec/j1/phan-chia')

    expect(await screen.findByText('Chia mặc định · không sửa được · 0/2 đã xác nhận · hạn 06/11')).toBeInTheDocument()
    expect(
      screen.getByText(
        'Người hướng dẫn chưa soạn phân chia trước 10/11, nên hệ thống đã chia: người hướng dẫn 30% (tối đa 30%), phần còn lại chia đều cho các thành viên.',
      ),
    ).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Sửa phân chia' })).toBeNull()
  })

  it('leaves the cap out of the default’s text when none applied (X5)', { timeout: 20_000 }, async () => {
    backend([
      proposed({ capApplies: false, cap: undefined, latest: version({ kind: 'DEFAULT', capPercent: undefined }) }),
    ])
    renderAt('/cong-viec/j1/phan-chia')

    expect(
      await screen.findByText(
        'Người hướng dẫn chưa soạn phân chia trước 10/11, nên hệ thống đã chia: người hướng dẫn 30%, phần còn lại chia đều cho các thành viên.',
      ),
    ).toBeInTheDocument()
  })

  it('offers no draft once the draft deadline passed', { timeout: 20_000 }, async () => {
    backend([card({ draftLate: true })])
    renderAt('/cong-viec/j1/phan-chia')

    expect(await screen.findByText('Đã quá hạn soạn phân chia · hệ thống đang chia mặc định')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Gửi để xác nhận' })).toBeNull()
  })

  it('reads the card of a Mentor alone', { timeout: 20_000 }, async () => {
    backend([
      card({
        status: 'FINAL',
        finality: 'MENTOR_ONLY',
        paid: true,
        finalAt: '2026-11-03T03:00:00Z',
        k: 0,
        n: 0,
        parties: [parties[0]],
        latest: version({
          kind: 'MENTOR_ONLY',
          capPercent: undefined,
          lines: [{ partyId: 'p0', percent: 100, amountVnd: 2_000_000, confirmed: false }],
        }),
      }),
    ])
    renderAt('/cong-viec/j1/phan-chia')

    expect(
      await screen.findByText('Không có thành viên nào tham gia giai đoạn này: bạn nhận 100%, không cần phân chia.'),
    ).toBeInTheDocument()
    expect(screen.getByText('Không cần phân chia · bạn nhận 100%')).toBeInTheDocument()
    expect(screen.getByText('Đã về số dư')).toBeInTheDocument()
  })
})

describe('JOB-10, a party', () => {
  it('waits for the Mentor’s draft with no inputs', { timeout: 20_000 }, async () => {
    backend([card({ ...member })])
    renderAt('/cong-viec/j1/phan-chia')

    expect(await screen.findByText('Chờ người hướng dẫn soạn phân chia (hạn 10/11)')).toBeInTheDocument()
    expect(screen.getByText('Trần Thảo Vy')).toBeInTheDocument()
    expect(screen.queryByRole('textbox')).toBeNull()
    expect(screen.queryByRole('button', { name: 'Gửi để xác nhận' })).toBeNull()
  })

  it('reads the whole split, N-30, and confirms the version it was shown', { timeout: 20_000 }, async () => {
    const seen = backend([proposed({ ...member })], {
      'POST /api/v1/splits/s1/confirmations': () => json({ versionNo: 1, status: 'PROPOSED' }),
    })
    renderAt('/cong-viec/j1/phan-chia')

    expect(await screen.findByText('Chờ bạn xác nhận trước 06/11')).toBeInTheDocument()
    const lines = screen.getByRole('table')
    expect(within(lines).getByText('Đỗ Mạnh Hùng')).toBeInTheDocument()
    expect(within(lines).getByText('Trần Thảo Vy')).toBeInTheDocument()
    expect(within(lines).getByText('Nguyễn Văn Minh')).toBeInTheDocument()
    expect(
      screen.getByText(
        'Khi mọi người xác nhận, hoặc đến 06/11 mà không ai nhờ xem xét, tiền được chuyển theo phần chia này. Nếu bạn thấy chưa đúng, hãy nhờ quản trị viên xem xét trước 06/11; khi đó tiền của cả giai đoạn được giữ lại cho đến khi có quyết định.',
      ),
    ).toBeInTheDocument()
    // The request for a resolution lands with Story 22.3.
    expect(screen.queryByRole('button', { name: 'Nhờ quản trị viên xem xét' })).toBeNull()

    fireEvent.click(screen.getByRole('button', { name: 'Xác nhận' }))

    await waitFor(() => expect(seen.some((request) => request.url.endsWith('/confirmations'))).toBe(true))
    const confirm = seen.find((request) => request.url.endsWith('/confirmations'))
    expect(await confirm?.clone().json()).toEqual({ splitVersionNo: 1 })
  })

  it('reads that it confirmed, with no button', { timeout: 20_000 }, async () => {
    backend([proposed({ ...member, viewerConfirmed: true })])
    renderAt('/cong-viec/j1/phan-chia')

    expect(await screen.findByText('Đã xác nhận')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Xác nhận' })).toBeNull()
  })

  it('is told the Mentor edited and to confirm again', { timeout: 20_000 }, async () => {
    backend([proposed({ ...member, editsLeft: 2, latest: version({ versionNo: 2 }) })])
    renderAt('/cong-viec/j1/phan-chia')

    expect(
      await screen.findByText(
        'Người hướng dẫn đã sửa phân chia (lần 1/3). Các xác nhận cũ đã bị xóa; hãy xác nhận lại trước 06/11.',
      ),
    ).toBeInTheDocument()
  })

  it('reads the default split as such', { timeout: 20_000 }, async () => {
    backend([proposed({ ...member, latest: version({ kind: 'DEFAULT' }) })])
    renderAt('/cong-viec/j1/phan-chia')

    expect(await screen.findByText('Hệ thống đã chia đều · chờ bạn xác nhận trước 06/11')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Xác nhận' })).toBeInTheDocument()
  })

  it('reloads with the Stale dialog on an old version', { timeout: 20_000 }, async () => {
    backend([proposed({ ...member })], {
      'POST /api/v1/splits/s1/confirmations': () => json({ status: 409, code: 'STALE' }, 409),
    })
    renderAt('/cong-viec/j1/phan-chia')

    fireEvent.click(await screen.findByRole('button', { name: 'Xác nhận' }))

    expect(await screen.findByText(/Phân chia vừa thay đổi/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Xem bản mới' }))
    await waitFor(() => expect(screen.queryByText(/Phân chia vừa thay đổi/)).toBeNull())
  })

  it('says why a command past the due time was refused (A-09)', { timeout: 20_000 }, async () => {
    backend([proposed({ ...member })], {
      'POST /api/v1/splits/s1/confirmations': () =>
        json({ status: 422, code: 'GUARD_FAILED', reason: 'SPLIT_FINAL', pastDue: true }, 422),
    })
    renderAt('/cong-viec/j1/phan-chia')

    fireEvent.click(await screen.findByRole('button', { name: 'Xác nhận' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'đã hết hạn xác nhận; phân chia đang được chốt theo bản hiện tại',
    )
  })
})

describe('JOB-10, for everyone in the split', () => {
  for (const [role, viewer] of [
    ['the Mentor', { viewerIsMentor: true }],
    ['a party', member],
  ] as const) {
    it(`offers ${role} nothing while it is being finalized (A-09)`, { timeout: 20_000 }, async () => {
      backend([proposed({ ...viewer, pastDue: true })])
      renderAt('/cong-viec/j1/phan-chia')

      expect(await screen.findByText('Đã hết hạn xác nhận · đang chốt theo bản hiện tại')).toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Xác nhận' })).toBeNull()
      expect(screen.queryByRole('button', { name: 'Sửa phân chia' })).toBeNull()
    })

    it(`offers ${role} nothing under a resolution`, { timeout: 20_000 }, async () => {
      backend([proposed({ ...viewer, status: 'IN_RESOLUTION' })])
      renderAt('/cong-viec/j1/phan-chia')

      expect(
        await screen.findByText(
          'Đang chờ quản trị viên xem xét. Tiền của cả giai đoạn được giữ; thời hạn xác nhận tạm dừng.',
        ),
      ).toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Xác nhận' })).toBeNull()
      expect(screen.queryByRole('button', { name: 'Sửa phân chia' })).toBeNull()
    })

    it(`shows ${role} a final split, how it became final, and what was paid`, { timeout: 20_000 }, async () => {
      backend([
        proposed({
          ...viewer,
          status: 'FINAL',
          finality: 'DEADLINE',
          paid: true,
          finalAt: '2026-11-06T03:00:00Z',
          latest: version({
            kind: 'DEFAULT',
            lines: [
              { partyId: 'p0', percent: 30, amountVnd: 600_000, confirmed: false },
              { partyId: 'p1', percent: 70, amountVnd: 1_400_000, confirmed: false },
              { partyId: 'p2', percent: 0, amountVnd: 0, confirmed: false },
            ],
          }),
        }),
      ])
      renderAt('/cong-viec/j1/phan-chia')

      expect(await screen.findByText('Đã chốt ngày 06/11/2026 · hết hạn xác nhận · chia mặc định')).toBeInTheDocument()
      expect(screen.getAllByText('Đã về số dư')).toHaveLength(2)
    })
  }

  it('keeps the earlier versions collapsed and read-only', { timeout: 20_000 }, async () => {
    backend([
      proposed({
        ...member,
        status: 'FINAL',
        finality: 'CONFIRMED',
        paid: true,
        finalAt: '2026-11-06T03:00:00Z',
        latest: version({ versionNo: 2 }),
        earlier: [
          version({
            lines: [
              { partyId: 'p0', percent: 30, amountVnd: 600_000, confirmed: false },
              { partyId: 'p1', percent: 35, amountVnd: 700_000, confirmed: false },
              { partyId: 'p2', percent: 35, amountVnd: 700_000, confirmed: true },
            ],
          }),
        ],
      }),
    ])
    renderAt('/cong-viec/j1/phan-chia')

    const summary = await screen.findByText('Các bản trước')
    const details = summary.closest('details')
    expect(details).not.toHaveAttribute('open')
    fireEvent.click(summary)
    expect(screen.getByText('Bản 1 · 03/11/2026')).toBeInTheDocument()
    expect(screen.getByRole('table', { name: 'Các phần chia của bản 1' })).toBeInTheDocument()
    expect(screen.getAllByText('35%')).toHaveLength(2)
    expect(within(details as HTMLElement).queryByRole('button')).toBeNull()
    expect(within(details as HTMLElement).queryByRole('textbox')).toBeNull()
  })

  it('never says a Dispute paused anything', { timeout: 20_000 }, async () => {
    backend([proposed({ ...member })])
    renderAt('/cong-viec/j1/phan-chia')

    await screen.findByText('Chờ bạn xác nhận trước 06/11')
    expect(screen.queryByText(/Tạm dừng/)).toBeNull()
  })

  it('reads every 30 s while a split is open', { timeout: 20_000 }, async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const seen = backend([proposed({ ...member })])
    renderAt('/cong-viec/j1/phan-chia')
    await screen.findByText('Chờ bạn xác nhận trước 06/11')
    const reads = () => seen.filter((request) => request.url.endsWith('/api/v1/jobs/j1/splits')).length
    const before = reads()

    await vi.advanceTimersByTimeAsync(30_000)

    await waitFor(() => expect(reads()).toBeGreaterThan(before))
  })

  it('shows anyone else, the Customer included, the not-found screen', { timeout: 20_000 }, async () => {
    fakeBackend(signedInUser, {
      'GET /api/v1/jobs/j1/splits': () => json({ status: 404, code: 'NOT_FOUND' }, 404),
    })
    renderAt('/cong-viec/j1/phan-chia')

    expect(await screen.findByRole('heading', { name: /Không tìm thấy/ })).toBeInTheDocument()
  })

  it('stands after the Job is gone, on the split’s own rows', { timeout: 20_000 }, async () => {
    backend([proposed({ ...member, jobTitle: undefined })])
    renderAt('/cong-viec/j1/phan-chia/m1')

    expect(await screen.findByText('Chờ bạn xác nhận trước 06/11')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Về công việc' })).toHaveAttribute('href', '/cong-viec')
  })
})

describe('The ways to JOB-10', () => {
  const mentorJob = {
    id: 'j1',
    title: 'Sửa sổ đặt bàn',
    productName: 'Sổ đặt bàn Quán Mây',
    state: 'ACTIVE',
    version: 12,
    jobTypes: ['BUG_FIX'],
    selectedAt: '2026-10-18T03:00:00Z',
    codeJob: true,
    commitEmailNoticeAcknowledged: true,
    agreementConfirmed: true,
  }

  it('gives the Mentor the tab from the first split on', { timeout: 20_000 }, async () => {
    backend([card()], {
      ...inEveryJob('MENTOR'),
      'GET /api/v1/mentor/jobs/j1': () => json(mentorJob),
    })
    renderAt('/cong-viec/j1/phan-chia')

    expect(await screen.findByRole('link', { name: 'Phân chia tiền' })).toHaveAttribute(
      'href',
      '/cong-viec/j1/phan-chia',
    )
  })

  it('keeps it hidden with no split yet', { timeout: 20_000 }, async () => {
    backend([], {
      ...inEveryJob('MENTOR'),
      'GET /api/v1/mentor/jobs/j1': () => json(mentorJob),
    })
    renderAt('/cong-viec/j1/phan-chia')

    expect(
      await screen.findByText('Chưa có giai đoạn nào được nghiệm thu, nên chưa có gì để phân chia.'),
    ).toBeInTheDocument()
    expect(await screen.findByRole('link', { name: 'Tuyển thành viên' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Phân chia tiền' })).toBeNull()
  })

  const crewMe = (former: boolean) => ({
    jobId: 'j1',
    title: 'Sửa sổ đặt bàn',
    productName: 'Sổ đặt bàn Quán Mây',
    state: 'ACTIVE',
    former,
    canLeave: false,
    codeJob: false,
    mentorName: 'Đỗ Mạnh Hùng',
    partyMilestones: [],
  })

  for (const former of [false, true]) {
    it(`links it for a party ${former ? 'who left' : 'still active'}`, { timeout: 20_000 }, async () => {
      backend([proposed({ ...member })], {
        ...inEveryJob('CREW'),
        'GET /api/v1/jobs/j1/crew/me': () => json(crewMe(former)),
      })
      renderAt('/cong-viec/j1')

      expect(await screen.findByRole('link', { name: 'Phân chia tiền' })).toHaveAttribute(
        'href',
        '/cong-viec/j1/phan-chia',
      )
    })
  }

  it('links nothing for a member who is no party', { timeout: 20_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CREW'),
      'GET /api/v1/jobs/j1/crew/me': () => json(crewMe(false)),
    })
    renderAt('/cong-viec/j1')

    expect(await screen.findByRole('link', { name: 'Trao đổi nhóm' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Phân chia tiền' })).toBeNull()
  })
})
