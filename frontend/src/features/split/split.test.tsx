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
  { partyId: 'p0', name: 'Đỗ Mạnh Hùng', erased: false, mentor: true, wholeInterval: true, activeAtRelease: true },
  { partyId: 'p1', name: 'Trần Thảo Vy', erased: false, mentor: false, wholeInterval: true, activeAtRelease: true },
  {
    partyId: 'p2',
    name: 'Nguyễn Văn Minh',
    erased: false,
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
    jobLive: true,
    status: 'AWAITING_DRAFT',
    pooledVnd: 2_000_000,
    paid: false,
    capApplies: true,
    cap: 30,
    editsUsed: 0,
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
    'GET /api/v1/splits/s1/resolutions': () => json({ viewerMayRaise: false, resolutions: [] }),
    ...handlers,
  })
}

function typePercent(name: RegExp, value: string) {
  fireEvent.change(screen.getByRole('textbox', { name }), { target: { value } })
}

/** The Confirmation dialog of "Gửi để xác nhận" or "Xác nhận": the form's button opens it, and its own button sends. */
async function sendThroughTheDialog(name: string) {
  fireEvent.click(screen.getByRole('button', { name }))
  const dialog = await screen.findByRole('dialog')
  fireEvent.click(within(dialog).getByRole('button', { name }))
}

function typeDraft(mentor: string, vy: string, minh: string) {
  typePercent(/Đỗ Mạnh Hùng/, mentor)
  typePercent(/Trần Thảo Vy/, vy)
  typePercent(/Nguyễn Văn Minh/, minh)
}

const reads = (seen: Request[]) => seen.filter((request) => request.url.endsWith('/api/v1/jobs/j1/splits')).length

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
    await sendThroughTheDialog('Gửi để xác nhận')

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
    await sendThroughTheDialog('Gửi để xác nhận')

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

    await sendThroughTheDialog('Xác nhận')

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
    backend([proposed({ ...member, editsUsed: 1, editsLeft: 2, latest: version({ versionNo: 2 }) })])
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

    await screen.findByRole('button', { name: 'Xác nhận' })
    await sendThroughTheDialog('Xác nhận')

    expect(await screen.findByText(/Phân chia vừa thay đổi/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Xem bản mới' }))
    await waitFor(() => expect(screen.queryByText(/Phân chia vừa thay đổi/)).toBeNull())
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('says why a command past the due time was refused (A-09)', { timeout: 20_000 }, async () => {
    backend([proposed({ ...member })], {
      'POST /api/v1/splits/s1/confirmations': () =>
        json({ status: 422, code: 'GUARD_FAILED', reason: 'SPLIT_FINAL', pastDue: true }, 422),
    })
    renderAt('/cong-viec/j1/phan-chia')

    await screen.findByRole('button', { name: 'Xác nhận' })
    await sendThroughTheDialog('Xác nhận')

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Đã hết hạn xác nhận; phân chia đang được chốt theo bản hiện tại.',
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
      expect(screen.getByText('Tạm dừng')).toBeInTheDocument()
      const edit = screen.queryByRole('button', { name: 'Sửa phân chia' })
      if (proposed(viewer).viewerIsMentor) {
        expect(edit).toBeDisabled()
        expect(screen.getByText('Phân chia đang chờ quản trị viên xem xét.')).toBeInTheDocument()
      } else {
        expect(edit).toBeNull()
      }
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
    backend([proposed({ ...member, jobTitle: undefined, jobLive: false })])
    renderAt('/cong-viec/j1/phan-chia/m1')

    expect(await screen.findByText('Chờ bạn xác nhận trước 06/11')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Về công việc' })).toHaveAttribute('href', '/cong-viec')
  })

  it('links a bare row, which keeps its title, away from the Job’s not-found', { timeout: 20_000 }, async () => {
    backend([proposed({ ...member, jobLive: false })])
    renderAt('/cong-viec/j1/phan-chia')

    expect(await screen.findByText('Sửa sổ đặt bàn')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Về công việc' })).toHaveAttribute('href', '/cong-viec')
  })

  it('links a live Job to its screen', { timeout: 20_000 }, async () => {
    backend([proposed({ ...member })])
    renderAt('/cong-viec/j1/phan-chia')

    expect(await screen.findByRole('link', { name: 'Về công việc' })).toHaveAttribute('href', '/cong-viec/j1')
  })

  it('says the money waits until the split is final, in EXPERIENCE.md’s words', { timeout: 20_000 }, async () => {
    backend([card({ ...member })])
    renderAt('/cong-viec/j1/phan-chia')

    expect(await screen.findByText(/của Giai đoạn 2 đang chờ phân chia/)).toBeInTheDocument()
  })

  it('no longer says so once it is final', { timeout: 20_000 }, async () => {
    backend([
      proposed({ ...member, status: 'FINAL', finality: 'CONFIRMED', finalAt: '2026-11-06T03:00:00Z', paid: true }),
    ])
    renderAt('/cong-viec/j1/phan-chia')

    expect(await screen.findByText(/của Giai đoạn 2/)).toBeInTheDocument()
    expect(screen.queryByText(/đang chờ phân chia/)).toBeNull()
  })

  it('tells someone erased from someone with no name', { timeout: 20_000 }, async () => {
    backend([
      card({
        ...member,
        parties: [
          parties[0],
          { ...parties[1], name: undefined, erased: true },
          { ...parties[2], name: undefined, erased: false },
        ],
      }),
    ])
    renderAt('/cong-viec/j1/phan-chia')

    expect(await screen.findByText('Người đã xóa tài khoản')).toBeInTheDocument()
    expect(screen.getByText('Một thành viên')).toBeInTheDocument()
  })

  it('counts the edits from what the server says, whatever the limit is now', { timeout: 20_000 }, async () => {
    backend([proposed({ ...member, editsUsed: 3, editsLeft: 0, editLimit: 1, latest: version({ versionNo: 4 }) })])
    renderAt('/cong-viec/j1/phan-chia')

    expect(await screen.findByText(/Người hướng dẫn đã sửa phân chia \(lần 3\/1\)/)).toBeInTheDocument()
  })
})

describe('JOB-10, the deadline chip', () => {
  const chip = (text: string) => screen.queryByText(text)?.closest('span')

  it('reads the draft deadline while a draft is awaited', { timeout: 20_000 }, async () => {
    backend([card({ ...member })])
    renderAt('/cong-viec/j1/phan-chia')

    expect(await screen.findByText('Hạn 10/11')).toBeInTheDocument()
    expect(chip('Hạn 10/11')).toHaveClass('bg-muted')
  })

  it('warns when the draft deadline is near', { timeout: 20_000 }, async () => {
    backend([card({ ...member, draftNear: true })])
    renderAt('/cong-viec/j1/phan-chia')

    await screen.findByText('Hạn 10/11')
    expect(chip('Hạn 10/11')).toHaveClass('bg-warning-soft')
  })

  it('reads the confirmation deadline while one is awaited, and warns when near', { timeout: 20_000 }, async () => {
    backend([proposed({ ...member, confirmationNear: true })])
    renderAt('/cong-viec/j1/phan-chia')

    await screen.findByText('Hạn 06/11')
    expect(chip('Hạn 06/11')).toHaveClass('bg-warning-soft')
    expect(screen.queryByText('Hạn 10/11')).toBeNull()
  })

  for (const [state, built] of [
    ['the draft is late', () => card({ ...member, draftLate: true })],
    ['past the due time', () => proposed({ ...member, pastDue: true })],
    ['under a resolution', () => proposed({ ...member, status: 'IN_RESOLUTION' })],
    ['final', () => proposed({ ...member, status: 'FINAL', finality: 'DEADLINE', finalAt: '2026-11-06T03:00:00Z' })],
  ] as const) {
    it(`is gone when ${state}`, { timeout: 20_000 }, async () => {
      backend([built()])
      renderAt('/cong-viec/j1/phan-chia')

      await screen.findByRole('heading', { name: 'Giai đoạn 2' })
      expect(screen.queryByText(/^Hạn /)).toBeNull()
    })
  }
})

describe('JOB-10, the Confirmation dialogs', () => {
  it('asks a party first, and waits for the server', { timeout: 20_000 }, async () => {
    let answer: (response: Response) => void = () => undefined
    const seen = backend([proposed({ ...member })], {
      'POST /api/v1/splits/s1/confirmations': () =>
        new Promise<Response>((resolve) => {
          answer = resolve
        }),
    })
    renderAt('/cong-viec/j1/phan-chia')

    fireEvent.click(await screen.findByRole('button', { name: 'Xác nhận' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText('Xác nhận phân chia Giai đoạn 2?')).toBeInTheDocument()
    expect(
      within(dialog).getByText(
        'Bạn đồng ý với các phần chia ở trên. Khi mọi thành viên đã xác nhận, phân chia sẽ chốt và tiền chuyển vào số dư, không sửa lại được.',
      ),
    ).toBeInTheDocument()
    expect(seen.some((request) => request.url.endsWith('/confirmations'))).toBe(false)

    const send = within(dialog).getByRole('button', { name: 'Xác nhận' })
    fireEvent.click(send)
    await waitFor(() => expect(send).toBeDisabled())
    expect(screen.getByRole('dialog')).toBeInTheDocument()

    answer(json({ versionNo: 1, status: 'PROPOSED' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  })

  it('sends nothing when the party goes back', { timeout: 20_000 }, async () => {
    const seen = backend([proposed({ ...member })])
    renderAt('/cong-viec/j1/phan-chia')

    fireEvent.click(await screen.findByRole('button', { name: 'Xác nhận' }))
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Quay lại' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(seen.some((request) => request.url.endsWith('/confirmations'))).toBe(false)
  })

  it('asks the Mentor before a first send, with the date from the server', { timeout: 20_000 }, async () => {
    const seen = backend([card()], {
      'POST /api/v1/splits/s1/preview': () => json({ pooledVnd: 2_000_000, lines: [] }),
    })
    renderAt('/cong-viec/j1/phan-chia')
    await screen.findByText('Soạn phân chia trước 10/11')
    typeDraft('30', '40', '30')

    fireEvent.click(screen.getByRole('button', { name: 'Gửi để xác nhận' }))

    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText('Gửi phân chia để xác nhận?')).toBeInTheDocument()
    expect(
      within(dialog).getByText(
        'Các thành viên có đến 06/11 để xác nhận. Hết hạn mà chưa đủ xác nhận, phân chia vẫn chốt theo bản này.',
      ),
    ).toBeInTheDocument()
    expect(seen.some((request) => request.url.endsWith('/versions'))).toBe(false)
  })

  it('warns the Mentor that an edit voids the confirmations', { timeout: 20_000 }, async () => {
    backend([proposed()], {
      'POST /api/v1/splits/s1/preview': () => json({ pooledVnd: 2_000_000, lines: [] }),
    })
    renderAt('/cong-viec/j1/phan-chia')
    fireEvent.click(await screen.findByRole('button', { name: 'Sửa phân chia' }))
    typePercent(/Trần Thảo Vy/, '35')
    typePercent(/Nguyễn Văn Minh/, '35')

    fireEvent.click(screen.getByRole('button', { name: 'Gửi để xác nhận' }))

    expect(
      within(await screen.findByRole('dialog')).getByText(
        'Đây là một lần sửa: mọi xác nhận trước đó bị xóa và thời hạn bắt đầu lại. Các thành viên có đến 06/11 để xác nhận. Hết hạn mà chưa đủ xác nhận, phân chia vẫn chốt theo bản này.',
      ),
    ).toBeInTheDocument()
  })

  it('keeps an unchanged edit from being sent', { timeout: 20_000 }, async () => {
    backend([proposed()], {
      'POST /api/v1/splits/s1/preview': () => json({ pooledVnd: 2_000_000, lines: [] }),
    })
    renderAt('/cong-viec/j1/phan-chia')
    fireEvent.click(await screen.findByRole('button', { name: 'Sửa phân chia' }))

    expect(screen.getByRole('button', { name: 'Gửi để xác nhận' })).toBeDisabled()
    typePercent(/Trần Thảo Vy/, '35')
    typePercent(/Nguyễn Văn Minh/, '35')
    expect(screen.getByRole('button', { name: 'Gửi để xác nhận' })).toBeEnabled()
    typePercent(/Trần Thảo Vy/, '40')
    typePercent(/Nguyễn Văn Minh/, '30')
    expect(screen.getByRole('button', { name: 'Gửi để xác nhận' })).toBeDisabled()
  })
})

describe('JOB-10, the refusals of a draft', () => {
  const refused = (body: Record<string, unknown>, status: number) => ({
    'POST /api/v1/splits/s1/preview': () => json({ pooledVnd: 2_000_000, lines: [] }),
    'POST /api/v1/splits/s1/versions': () => json({ status, ...body }, status),
  })

  for (const [what, body, status, text] of [
    [
      'SPLIT_INVALID with the cap',
      { code: 'VALIDATION', reason: 'SPLIT_INVALID', cap: 25 },
      400,
      'Phần trăm phải là số nguyên; người hướng dẫn tối đa 25%; thành viên làm cả giai đoạn phải lớn hơn 0%.',
    ],
    [
      'SPLIT_INVALID without one',
      { code: 'VALIDATION', reason: 'SPLIT_INVALID' },
      400,
      'Phần trăm phải là số nguyên; thành viên làm cả giai đoạn phải lớn hơn 0%.',
    ],
    [
      'SPLIT_EDIT_LIMIT',
      { code: 'GUARD_FAILED', reason: 'SPLIT_EDIT_LIMIT', n: 3 },
      422,
      'Đã dùng hết 3 lần sửa phân chia cho giai đoạn này.',
    ],
    [
      'SPLIT_DRAFT_LATE',
      { code: 'GUARD_FAILED', reason: 'SPLIT_DRAFT_LATE' },
      422,
      'Đã quá hạn soạn phân chia; phân chia mặc định không sửa được.',
    ],
  ] as const) {
    it(`says why on ${what}`, { timeout: 20_000 }, async () => {
      backend([card()], refused(body, status))
      renderAt('/cong-viec/j1/phan-chia')
      await screen.findByText('Soạn phân chia trước 10/11')
      typeDraft('30', '40', '30')

      await sendThroughTheDialog('Gửi để xác nhận')

      expect(await screen.findByRole('alert')).toHaveTextContent(text)
    })
  }

  it('says why a preview was refused', { timeout: 20_000 }, async () => {
    backend([card()], {
      'POST /api/v1/splits/s1/preview': () =>
        json({ status: 400, code: 'VALIDATION', reason: 'SPLIT_INVALID', cap: 25 }, 400),
    })
    renderAt('/cong-viec/j1/phan-chia')
    await screen.findByText('Soạn phân chia trước 10/11')
    typeDraft('30', '40', '30')

    expect(await screen.findByRole('alert')).toHaveTextContent('người hướng dẫn tối đa 25%')
  })

  it('opens the Stale dialog on STALE, with no alert after it closes', { timeout: 20_000 }, async () => {
    backend([proposed()], refused({ code: 'STALE' }, 409))
    renderAt('/cong-viec/j1/phan-chia')
    fireEvent.click(await screen.findByRole('button', { name: 'Sửa phân chia' }))
    typePercent(/Trần Thảo Vy/, '35')
    typePercent(/Nguyễn Văn Minh/, '35')

    await sendThroughTheDialog('Gửi để xác nhận')

    expect(await screen.findByText(/Phân chia vừa thay đổi/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Xem bản mới' }))
    await waitFor(() => expect(screen.queryByText(/Phân chia vừa thay đổi/)).toBeNull())
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('freezes the version the form opened on, and starts again from the newer one', { timeout: 20_000 }, async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    let cards = [proposed()]
    const seen = fakeBackend(signedInUser, {
      'GET /api/v1/jobs/j1/splits': () => json(cards),
      'POST /api/v1/splits/s1/preview': () => json({ pooledVnd: 2_000_000, lines: [] }),
      'POST /api/v1/splits/s1/versions': () => json({ status: 409, code: 'STALE' }, 409),
    })
    renderAt('/cong-viec/j1/phan-chia')
    fireEvent.click(await screen.findByRole('button', { name: 'Sửa phân chia' }))
    typePercent(/Trần Thảo Vy/, '35')
    typePercent(/Nguyễn Văn Minh/, '35')

    // Another tab saved version 2: the next poll brings it under the open form.
    cards = [
      proposed({
        editsUsed: 1,
        editsLeft: 2,
        latest: version({
          versionNo: 2,
          lines: [
            { partyId: 'p0', percent: 20, amountVnd: 400_000, confirmed: false },
            { partyId: 'p1', percent: 50, amountVnd: 1_000_000, confirmed: false },
            { partyId: 'p2', percent: 30, amountVnd: 600_000, confirmed: false },
          ],
        }),
      }),
    ]
    await vi.advanceTimersByTimeAsync(30_000)
    await waitFor(() => expect(screen.getByText('Còn 2/3 lần sửa')).toBeInTheDocument())
    expect(screen.getByRole('textbox', { name: /Trần Thảo Vy/ })).toHaveValue('35')

    await sendThroughTheDialog('Gửi để xác nhận')

    await waitFor(() => expect(seen.some((request) => request.url.endsWith('/versions'))).toBe(true))
    const save = seen.find((request) => request.url.endsWith('/versions'))
    expect(await save?.clone().json()).toMatchObject({ shownVersionNo: 1 })
    fireEvent.click(await screen.findByRole('button', { name: 'Xem bản mới' }))

    await waitFor(() => expect(screen.getByRole('textbox', { name: /Trần Thảo Vy/ })).toHaveValue('50'))
    expect(screen.getByRole('textbox', { name: /Đỗ Mạnh Hùng/ })).toHaveValue('20')
  })
})

describe('JOB-10, reading the splits again', () => {
  it('reads them after a save', { timeout: 20_000 }, async () => {
    const seen = backend([card()], {
      'POST /api/v1/splits/s1/preview': () => json({ pooledVnd: 2_000_000, lines: [] }),
      'POST /api/v1/splits/s1/versions': () =>
        json({ versionNo: 1, status: 'PROPOSED', confirmationDueAt: '2026-11-06T03:00:00Z' }),
    })
    renderAt('/cong-viec/j1/phan-chia')
    await screen.findByText('Soạn phân chia trước 10/11')
    const before = reads(seen)
    typeDraft('30', '40', '30')

    await sendThroughTheDialog('Gửi để xác nhận')

    await waitFor(() => expect(reads(seen)).toBeGreaterThan(before))
  })

  it('reads them after a confirmation', { timeout: 20_000 }, async () => {
    const seen = backend([proposed({ ...member })], {
      'POST /api/v1/splits/s1/confirmations': () => json({ versionNo: 1, status: 'PROPOSED' }),
    })
    renderAt('/cong-viec/j1/phan-chia')
    await screen.findByRole('button', { name: 'Xác nhận' })
    const before = reads(seen)

    await sendThroughTheDialog('Xác nhận')

    await waitFor(() => expect(reads(seen)).toBeGreaterThan(before))
  })

  const finalCard = (paid: boolean) =>
    proposed({ ...member, status: 'FINAL', finality: 'CONFIRMED', finalAt: '2026-11-06T03:00:00Z', paid })

  it('stops polling once every card is final and paid', { timeout: 20_000 }, async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const seen = backend([finalCard(true)])
    renderAt('/cong-viec/j1/phan-chia')
    await screen.findAllByText('Đã về số dư')
    const before = reads(seen)

    await vi.advanceTimersByTimeAsync(61_000)

    expect(reads(seen)).toBe(before)
  })

  it('goes on while a final card is unpaid, so "Đã về số dư" shows', { timeout: 20_000 }, async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    let cards = [finalCard(false)]
    fakeBackend(signedInUser, { 'GET /api/v1/jobs/j1/splits': () => json(cards) })
    renderAt('/cong-viec/j1/phan-chia')
    await screen.findByText(/hết hạn xác nhận|mọi người đã xác nhận/)
    expect(screen.queryByText('Đã về số dư')).toBeNull()

    cards = [finalCard(true)]
    await vi.advanceTimersByTimeAsync(30_000)

    expect(await screen.findAllByText('Đã về số dư')).toHaveLength(3)
  })

  it('keeps the typed percentages through a failed poll', { timeout: 20_000 }, async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    let down = false
    fakeBackend(signedInUser, {
      'GET /api/v1/jobs/j1/splits': () => (down ? json({ status: 500 }, 500) : json([card()])),
    })
    renderAt('/cong-viec/j1/phan-chia')
    await screen.findByText('Soạn phân chia trước 10/11')
    typePercent(/Trần Thảo Vy/, '40')

    down = true
    await vi.advanceTimersByTimeAsync(30_000)
    await vi.advanceTimersByTimeAsync(10)

    expect(screen.getByRole('textbox', { name: /Trần Thảo Vy/ })).toHaveValue('40')
  })

  it('shows its error when the first load fails', { timeout: 20_000 }, async () => {
    fakeBackend(signedInUser, { 'GET /api/v1/jobs/j1/splits': () => json({ status: 500 }, 500) })
    renderAt('/cong-viec/j1/phan-chia')

    expect(await screen.findByRole('alert')).toBeInTheDocument()
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
      // Story 22.5: their own parts of the Job, beside it, outside what a former member loses.
      expect(screen.getByRole('link', { name: 'Thanh toán' })).toHaveAttribute('href', '/cong-viec/j1/thanh-toan')
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
    expect(screen.queryByRole('link', { name: 'Thanh toán' })).toBeNull()
  })
})

describe('JOB-10, a Split Resolution (FR-84, UX-DR106)', () => {
  const none = () => json({ viewerMayRaise: false, resolutions: [] })
  const open = (overrides: object = {}) => ({
    id: 'r1',
    splitVersionNo: 1,
    status: 'OPEN',
    raiserPartyId: 'p1',
    raiserName: 'Trần Thảo Vy',
    reason: 'Phần của tôi quá ít',
    raisedAt: '2026-11-04T03:00:00Z',
    viewerIsRaiser: false,
    viewerMayState: false,
    statements: [],
    ...overrides,
  })
  const n31 =
    'Quản trị viên sẽ xem các bản phân chia và các lần sửa, hạng mục và commit của từng người, Trao đổi nhóm của giai đoạn này, và dấu hiệu cho thấy hai tài khoản có thể của cùng một người, rồi quyết định, trong mức tối đa 30% cho người hướng dẫn nếu giai đoạn còn thành viên trong nhóm khi được nghiệm thu. Trong lúc chờ, người hướng dẫn không sửa được phần chia và tiền của cả giai đoạn được giữ. Bạn có thể rút yêu cầu; khi đó hạn xác nhận bắt đầu lại.'

  it(
    'asks with N-31 verbatim and a required reason, naming the version it was shown',
    { timeout: 20_000 },
    async () => {
      const raised: unknown[] = []
      backend([proposed(member)], {
        'GET /api/v1/splits/s1/resolutions': () => json({ viewerMayRaise: true, resolutions: [] }),
        'POST /api/v1/splits/s1/resolutions': async (request) => {
          raised.push(await request.json())
          return json({ viewerMayRaise: false, resolutions: [open({ viewerIsRaiser: true })] }, 201)
        },
      })
      renderAt('/cong-viec/j1/phan-chia')

      fireEvent.click(await screen.findByRole('button', { name: 'Nhờ quản trị viên xem xét' }))
      const dialog = await screen.findByRole('dialog')
      expect(within(dialog).getByText(n31)).toBeInTheDocument()
      const send = within(dialog).getByRole('button', { name: 'Gửi yêu cầu' })
      expect(within(dialog).getByRole('button', { name: 'Quay lại' })).toBeInTheDocument()
      expect(send).toBeDisabled()

      fireEvent.change(within(dialog).getByRole('textbox'), { target: { value: '  Phần của tôi quá ít ' } })
      expect(send).toBeEnabled()
      fireEvent.click(send)

      await waitFor(() => expect(raised).toEqual([{ splitVersionNo: 1, reason: 'Phần của tôi quá ít' }]))
      await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    },
  )

  it('leaves the cap clause out of N-31 under X5', { timeout: 20_000 }, async () => {
    backend([proposed({ ...member, capApplies: false, latest: version({ capPercent: undefined }) })], {
      'GET /api/v1/splits/s1/resolutions': () => json({ viewerMayRaise: true, resolutions: [] }),
    })
    renderAt('/cong-viec/j1/phan-chia')

    fireEvent.click(await screen.findByRole('button', { name: 'Nhờ quản trị viên xem xét' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).queryByText(/tối đa/)).toBeNull()
    expect(within(dialog).getByText(/rồi quyết định\. Trong lúc chờ/)).toBeInTheDocument()
  })

  it(
    'offers the raise to a party who confirmed, and to nobody the server does not name',
    { timeout: 20_000 },
    async () => {
      backend([proposed({ ...member, viewerConfirmed: true })], {
        'GET /api/v1/splits/s1/resolutions': () => json({ viewerMayRaise: true, resolutions: [] }),
      })
      renderAt('/cong-viec/j1/phan-chia')
      expect(await screen.findByRole('button', { name: 'Nhờ quản trị viên xem xét' })).toBeInTheDocument()
    },
  )

  it('offers the Mentor no raise', { timeout: 20_000 }, async () => {
    const seen = backend([proposed()], { 'GET /api/v1/splits/s1/resolutions': none })
    renderAt('/cong-viec/j1/phan-chia')

    await screen.findByRole('button', { name: 'Sửa phân chia' })
    await waitFor(() => expect(seen.some((request) => request.url.endsWith('/s1/resolutions'))).toBe(true))
    expect(screen.queryByRole('button', { name: 'Nhờ quản trị viên xem xét' })).toBeNull()
  })

  it('says why a raise was refused, in the words of each reason', { timeout: 20_000 }, async () => {
    for (const [problem, text] of [
      [
        { code: 'GUARD_FAILED', reason: 'SPLIT_RESOLUTION_USED' },
        'Bạn đã nhờ xem xét phân chia của giai đoạn này một lần rồi.',
      ],
      [
        { code: 'GUARD_FAILED', reason: 'SPLIT_FINAL', pastDue: true },
        'Đã hết hạn xác nhận nên không nhờ xem xét được nữa. Phân chia đang được chốt theo bản hiện tại.',
      ],
      [{ code: 'GUARD_FAILED', reason: 'SPLIT_FINAL' }, 'Phân chia đã chốt nên không nhờ xem xét được nữa.'],
    ] as const) {
      backend([proposed(member)], {
        'GET /api/v1/splits/s1/resolutions': () => json({ viewerMayRaise: true, resolutions: [] }),
        'POST /api/v1/splits/s1/resolutions': () => json(problem, 422),
      })
      const view = createMemoryRouter(routes, { initialEntries: ['/cong-viec/j1/phan-chia'] })
      const { unmount } = render(
        <QueryClientProvider client={createQueryClient()}>
          <RouterProvider router={view} />
        </QueryClientProvider>,
      )
      fireEvent.click(await screen.findByRole('button', { name: 'Nhờ quản trị viên xem xét' }))
      const dialog = await screen.findByRole('dialog')
      fireEvent.change(within(dialog).getByRole('textbox'), { target: { value: 'Lý do' } })
      fireEvent.click(within(dialog).getByRole('button', { name: 'Gửi yêu cầu' }))
      expect(await within(dialog).findByRole('alert')).toHaveTextContent(text)
      unmount()
    }
  })

  it('says so with Retry when the read fails, and shows the button after it', { timeout: 20_000 }, async () => {
    let down = true
    backend([proposed(member)], {
      'GET /api/v1/splits/s1/resolutions': () =>
        down ? json({ status: 500 }, 500) : json({ viewerMayRaise: true, resolutions: [] }),
    })
    renderAt('/cong-viec/j1/phan-chia')

    const alert = await screen.findByRole('alert')
    expect(screen.queryByRole('button', { name: 'Nhờ quản trị viên xem xét' })).toBeNull()

    down = false
    fireEvent.click(within(alert).getByRole('button', { name: 'Thử lại' }))

    expect(await screen.findByRole('button', { name: 'Nhờ quản trị viên xem xét' })).toBeInTheDocument()
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('shows a skeleton while the first read loads', { timeout: 20_000 }, async () => {
    backend([proposed(member)], { 'GET /api/v1/splits/s1/resolutions': () => new Promise<Response>(() => {}) })
    renderAt('/cong-viec/j1/phan-chia')

    expect(await screen.findByText('Chờ bạn xác nhận trước 06/11')).toBeInTheDocument()
    expect(document.querySelector('[aria-busy="true"]')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('keeps showing the cases through a failed poll', { timeout: 20_000 }, async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    let down = false
    backend([proposed({ ...member, status: 'IN_RESOLUTION' })], {
      'GET /api/v1/splits/s1/resolutions': () =>
        down
          ? json({ status: 500 }, 500)
          : json({ viewerMayRaise: false, resolutions: [open({ viewerMayState: true })] }),
    })
    renderAt('/cong-viec/j1/phan-chia')
    fireEvent.change(await screen.findByRole('textbox', { name: 'Ý kiến của bạn' }), {
      target: { value: 'Tôi cũng thấy ít' },
    })

    down = true
    await vi.advanceTimersByTimeAsync(30_000)
    await vi.advanceTimersByTimeAsync(10)

    expect(screen.getByRole('textbox', { name: 'Ý kiến của bạn' })).toHaveValue('Tôi cũng thấy ít')
  })

  it(
    'keeps the refusal and the reason typed when the read after it takes the button away',
    { timeout: 20_000 },
    async () => {
      let refused = false
      const text = 'Phân chia của giai đoạn này đang chờ quản trị viên xem xét.'
      backend([proposed(member)], {
        'GET /api/v1/splits/s1/resolutions': () =>
          json(refused ? { viewerMayRaise: false, resolutions: [open()] } : { viewerMayRaise: true, resolutions: [] }),
        'POST /api/v1/splits/s1/resolutions': () => {
          refused = true
          return json({ code: 'GUARD_FAILED', reason: 'SPLIT_IN_RESOLUTION' }, 422)
        },
      })
      renderAt('/cong-viec/j1/phan-chia')

      fireEvent.click(await screen.findByRole('button', { name: 'Nhờ quản trị viên xem xét' }))
      const dialog = await screen.findByRole('dialog')
      fireEvent.change(within(dialog).getByRole('textbox'), { target: { value: 'Lý do của tôi' } })
      fireEvent.click(within(dialog).getByRole('button', { name: 'Gửi yêu cầu' }))

      expect(await within(dialog).findByRole('alert')).toHaveTextContent(text)
      expect(await screen.findByText('Phần của tôi quá ít')).toBeInTheDocument()
      expect(screen.getByRole('dialog')).toBe(dialog)
      expect(within(dialog).getByRole('textbox')).toHaveValue('Lý do của tôi')
      expect(within(dialog).getByRole('alert')).toHaveTextContent(text)

      fireEvent.click(within(dialog).getByRole('button', { name: 'Quay lại' }))
      await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())

      expect(await screen.findByRole('alert')).toHaveTextContent(text)
      expect(screen.queryByRole('button', { name: 'Nhờ quản trị viên xem xét' })).toBeNull()
    },
  )

  it(
    'keeps the refusal and the words typed when the read after it ends the statement form',
    { timeout: 20_000 },
    async () => {
      let refused = false
      backend([proposed({ ...member, status: 'IN_RESOLUTION' })], {
        'GET /api/v1/splits/s1/resolutions': () =>
          json({
            viewerMayRaise: false,
            resolutions: [
              refused ? open({ status: 'WITHDRAWN', endedAt: '2026-11-05T03:00:00Z' }) : open({ viewerMayState: true }),
            ],
          }),
        'POST /api/v1/split-resolutions/r1/statements': () => {
          refused = true
          return json({ code: 'GUARD_FAILED', reason: 'SPLIT_RESOLUTION_ENDED' }, 422)
        },
      })
      renderAt('/cong-viec/j1/phan-chia')

      fireEvent.change(await screen.findByRole('textbox', { name: 'Ý kiến của bạn' }), {
        target: { value: 'Tôi cũng thấy ít' },
      })
      fireEvent.click(screen.getByRole('button', { name: 'Gửi ý kiến' }))
      const dialog = await screen.findByRole('dialog')
      fireEvent.click(within(dialog).getByRole('button', { name: 'Gửi ý kiến' }))

      expect(await screen.findByRole('alert')).toHaveTextContent('Yêu cầu xem xét này đã kết thúc.')
      expect(screen.getByRole('textbox', { name: 'Ý kiến của bạn' })).toHaveValue('Tôi cũng thấy ít')
      expect(screen.getByRole('button', { name: 'Gửi ý kiến' })).toBeDisabled()
    },
  )

  it('closes on an old version and offers the newer one', { timeout: 20_000 }, async () => {
    backend([proposed(member)], {
      'GET /api/v1/splits/s1/resolutions': () => json({ viewerMayRaise: true, resolutions: [] }),
      'POST /api/v1/splits/s1/resolutions': () => json({ code: 'STALE' }, 409),
    })
    renderAt('/cong-viec/j1/phan-chia')

    fireEvent.click(await screen.findByRole('button', { name: 'Nhờ quản trị viên xem xét' }))
    const dialog = await screen.findByRole('dialog')
    fireEvent.change(within(dialog).getByRole('textbox'), { target: { value: 'Lý do' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Gửi yêu cầu' }))

    expect(
      await screen.findByText(
        'Phân chia vừa thay đổi nên thao tác của bạn chưa được gửi. Hãy xem lại bản mới rồi thử lại.',
      ),
    ).toBeInTheDocument()
  })

  it(
    'shows another party the open case in place of the button, with one statement form',
    { timeout: 20_000 },
    async () => {
      const stated: unknown[] = []
      backend([proposed({ ...member, status: 'IN_RESOLUTION' })], {
        'GET /api/v1/splits/s1/resolutions': () =>
          json({
            viewerMayRaise: false,
            resolutions: [
              open({
                viewerMayState: true,
                statements: [
                  {
                    partyId: 'p0',
                    name: 'Đỗ Mạnh Hùng',
                    mentor: true,
                    text: 'Tôi làm phần lớn việc',
                    submittedAt: '2026-11-04T04:00:00Z',
                  },
                ],
              }),
            ],
          }),
        'POST /api/v1/split-resolutions/r1/statements': async (request) => {
          stated.push(await request.json())
          return json({ viewerMayRaise: false, resolutions: [open()] }, 201)
        },
      })
      renderAt('/cong-viec/j1/phan-chia')

      expect(await screen.findByText('Phần của tôi quá ít')).toBeInTheDocument()
      expect(screen.getByRole('heading', { name: 'Yêu cầu xem xét của Trần Thảo Vy' })).toBeInTheDocument()
      expect(screen.getByText('Tôi làm phần lớn việc')).toBeInTheDocument()
      expect(screen.getByText(/Đỗ Mạnh Hùng · người hướng dẫn/)).toBeInTheDocument()
      expect(screen.getByText('Đã nhờ xem xét')).toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Nhờ quản trị viên xem xét' })).toBeNull()
      expect(screen.queryByRole('button', { name: 'Rút yêu cầu' })).toBeNull()

      fireEvent.change(screen.getByRole('textbox', { name: 'Ý kiến của bạn' }), {
        target: { value: 'Tôi cũng thấy ít' },
      })
      fireEvent.click(screen.getByRole('button', { name: 'Gửi ý kiến' }))
      const dialog = await screen.findByRole('dialog')
      fireEvent.click(within(dialog).getByRole('button', { name: 'Gửi ý kiến' }))

      await waitFor(() => expect(stated).toEqual([{ text: 'Tôi cũng thấy ít' }]))
    },
  )

  it('shows the Mentor the reason to answer, with their edits locked', { timeout: 20_000 }, async () => {
    backend([proposed({ status: 'IN_RESOLUTION' })], {
      'GET /api/v1/splits/s1/resolutions': () =>
        json({ viewerMayRaise: false, resolutions: [open({ viewerMayState: true })] }),
    })
    renderAt('/cong-viec/j1/phan-chia')

    expect(await screen.findByText('Phần của tôi quá ít')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Sửa phân chia' })).toBeDisabled()
    expect(screen.getByRole('textbox', { name: 'Ý kiến của bạn' })).toBeInTheDocument()
  })

  it('lets the raiser withdraw after a warning, and offers no statement', { timeout: 20_000 }, async () => {
    const withdrawn: Request[] = []
    backend([proposed({ ...member, status: 'IN_RESOLUTION' })], {
      'GET /api/v1/splits/s1/resolutions': () =>
        json({ viewerMayRaise: false, resolutions: [open({ viewerIsRaiser: true })] }),
      'POST /api/v1/split-resolutions/r1/withdraw': (request) => {
        withdrawn.push(request)
        return json({ splitStatus: 'PROPOSED', confirmationDueAt: '2026-11-09T03:00:00Z' })
      },
    })
    renderAt('/cong-viec/j1/phan-chia')

    expect(await screen.findByRole('heading', { name: 'Yêu cầu xem xét của bạn' })).toBeInTheDocument()
    expect(screen.queryByRole('textbox', { name: 'Ý kiến của bạn' })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Rút yêu cầu' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText(/Bạn sẽ không nhờ xem xét được giai đoạn này lần nữa/)).toBeInTheDocument()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Rút yêu cầu' }))

    await waitFor(() => expect(withdrawn).toHaveLength(1))
  })

  it('says a withdrawal restarted the deadline', { timeout: 20_000 }, async () => {
    backend([proposed({ ...member, confirmationDueAt: '2026-11-09T03:00:00Z' })], {
      'GET /api/v1/splits/s1/resolutions': () =>
        json({
          viewerMayRaise: false,
          resolutions: [open({ status: 'WITHDRAWN', endedAt: '2026-11-06T03:00:00Z' })],
        }),
    })
    renderAt('/cong-viec/j1/phan-chia')

    expect(await screen.findByText('Yêu cầu đã được rút. Hạn xác nhận mới: 09/11.')).toBeInTheDocument()
    expect(screen.queryByText('Tạm dừng')).toBeNull()
  })

  it('says a withdrawal found everyone confirmed', { timeout: 20_000 }, async () => {
    backend(
      [proposed({ ...member, status: 'FINAL', finality: 'CONFIRMED', finalAt: '2026-11-06T03:00:00Z', paid: true })],
      {
        'GET /api/v1/splits/s1/resolutions': () =>
          json({
            viewerMayRaise: false,
            resolutions: [open({ status: 'WITHDRAWN', endedAt: '2026-11-06T03:00:00Z' })],
          }),
      },
    )
    renderAt('/cong-viec/j1/phan-chia')

    expect(await screen.findByText('Yêu cầu đã được rút. Đã chốt · mọi người đã xác nhận.')).toBeInTheDocument()
  })

  it('reads the reasoning once decided, and "Đã chốt" names the decision', { timeout: 20_000 }, async () => {
    backend(
      [
        proposed({
          ...member,
          status: 'FINAL',
          finality: 'DECISION',
          finalAt: '2026-11-06T03:00:00Z',
          paid: true,
          latest: version({ versionNo: 2, kind: 'DECISION' }),
        }),
      ],
      {
        'GET /api/v1/splits/s1/resolutions': () =>
          json({
            viewerMayRaise: false,
            resolutions: [
              open({ status: 'DECIDED', endedAt: '2026-11-06T03:00:00Z', reasoning: 'Vy làm phần lớn việc' }),
            ],
          }),
      },
    )
    renderAt('/cong-viec/j1/phan-chia')

    expect(await screen.findByText('Vy làm phần lớn việc')).toBeInTheDocument()
    expect(screen.getByText(/Đã chốt ngày .* · quản trị viên đã quyết định/)).toBeInTheDocument()
    expect(screen.queryByRole('textbox', { name: 'Ý kiến của bạn' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Rút yêu cầu' })).toBeNull()
  })

  it('reads nothing for a Mentor alone or before any draft', { timeout: 20_000 }, async () => {
    const seen = backend([card({ ...member })], { 'GET /api/v1/splits/s1/resolutions': none })
    renderAt('/cong-viec/j1/phan-chia')

    await screen.findByRole('heading', { name: 'Giai đoạn 2' })
    expect(seen.some((request) => request.url.endsWith('/s1/resolutions'))).toBe(false)
  })
})
