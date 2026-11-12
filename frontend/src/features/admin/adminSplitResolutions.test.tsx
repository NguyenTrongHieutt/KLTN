import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { routes } from '@/app/routes'
import type { Session } from '@/features/identity/api'
import { administratorAccount, fakeBackend, json } from '@/test/fakeBackend'

function renderAt(path: string) {
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  render(
    <QueryClientProvider client={createQueryClient()}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return router
}

const minutes = (n: number) => new Date(Date.now() + n * 60_000).toISOString()

const administrator: Session = {
  status: 'SIGNED_IN',
  privacyNoticeVersion: '2.0',
  account: administratorAccount,
  administrator: {
    email: 'phuong@vibefix.vn',
    displayName: 'Hà Phương',
    idleExpiresAt: minutes(30),
    absoluteExpiresAt: minutes(480),
  },
}

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

const row = (overrides: object = {}) => ({
  id: 'r1',
  jobId: 'j1',
  jobTitle: 'Sửa lỗi đặt bàn',
  milestoneNumber: 2,
  status: 'OPEN',
  raisedAt: '2026-10-23T03:00:00Z',
  undecidedLong: false,
  assigned: false,
  assignedToMe: false,
  ...overrides,
})

const parties = [
  { partyId: 'p0', name: 'Đỗ Mạnh Hùng', mentor: true, wholeInterval: true, activeAtRelease: true },
  { partyId: 'p1', name: 'Trần Thảo Vy', mentor: false, wholeInterval: true, activeAtRelease: true },
  { partyId: 'p2', name: 'Nguyễn Văn Minh', mentor: false, wholeInterval: false, activeAtRelease: false },
]

/** A case as an Administrator who has not taken it reads it: that it exists, and none of its words (A-18). */
const unassigned = (overrides: object = {}) => ({
  id: 'r1',
  jobId: 'j1',
  jobTitle: 'Sửa lỗi đặt bàn',
  milestoneNumber: 2,
  pooledVnd: 2_000_000,
  status: 'OPEN',
  raisedAt: '2026-10-23T03:00:00Z',
  daysUndecided: 3,
  undecidedLong: false,
  assigned: false,
  assignedToMe: false,
  evidenceReadable: false,
  statements: [],
  parties: [],
  capApplies: true,
  ...overrides,
})

const mine = (overrides: object = {}) =>
  unassigned({
    assigned: true,
    assignedToMe: true,
    evidenceReadable: true,
    raiserName: 'Trần Thảo Vy',
    reason: 'Phần của tôi quá ít',
    statements: [
      { partyId: 'p0', name: 'Đỗ Mạnh Hùng', text: 'Tôi làm phần lớn việc', submittedAt: '2026-10-24T03:00:00Z' },
    ],
    parties,
    cap: 30,
    ...overrides,
  })

const emptyEvidence = {
  'GET /api/v1/admin/split-resolutions/r1/evidence/versions': () =>
    json({
      parties: [
        {
          partyId: 'p0',
          name: 'Đỗ Mạnh Hùng',
          mentor: true,
          wholeInterval: true,
          activeAtRelease: true,
          activeFrom: '2026-10-01T03:00:00Z',
          activeTo: '2026-10-20T03:00:00Z',
        },
        { partyId: 'p1', name: 'Trần Thảo Vy', mentor: false, wholeInterval: true, activeAtRelease: true },
      ],
      versions: [
        {
          versionNo: 1,
          kind: 'DRAFT',
          authorName: 'Đỗ Mạnh Hùng',
          capPercent: 30,
          createdAt: '2026-10-21T03:00:00Z',
          lines: [
            { partyId: 'p0', percent: 30 },
            { partyId: 'p1', percent: 70, confirmedAt: '2026-10-22T03:00:00Z' },
          ],
        },
      ],
    }),
}

const record = {
  reasoning: 'Vy làm phần lớn việc',
  decidedAt: '2026-10-25T03:00:00Z',
  versionNo: 2,
  pooledVnd: 2_000_000,
  lines: [{ partyId: 'p1', name: 'Trần Thảo Vy', percent: 50, amountVnd: 1_000_000 }],
  evidenceSnapshot: {
    takenAt: '2026-10-25T03:00:00Z',
    versions: [],
    workItems: [],
    commits: [],
    crewChat: { messageCount: 0 },
    personLinks: 0,
  },
}

const previewOf = () =>
  json({
    pooledVnd: 2_000_000,
    lines: [
      { partyId: 'p0', amountVnd: 500_000 },
      { partyId: 'p1', amountVnd: 1_000_000 },
      { partyId: 'p2', amountVnd: 500_000 },
    ],
  })

/** Whole percentages summing to 100, and the reasoning. */
function typeDecision() {
  fireEvent.change(screen.getByRole('textbox', { name: /Đỗ Mạnh Hùng/ }), { target: { value: '25' } })
  fireEvent.change(screen.getByRole('textbox', { name: /Trần Thảo Vy/ }), { target: { value: '50' } })
  fireEvent.change(screen.getByRole('textbox', { name: /Nguyễn Văn Minh/ }), { target: { value: '25' } })
  fireEvent.change(screen.getByRole('textbox', { name: 'Lý do quyết định' }), {
    target: { value: 'Vy làm phần lớn việc' },
  })
}

describe('ADM-4 "Phân chia tiền" (FR-84, AD-40, UX-DR107)', () => {
  it(
    'shows an Administrator that a case exists and none of its words until they take it',
    { timeout: 20_000 },
    async () => {
      fakeBackend(administrator, {
        'GET /api/v1/admin/split-resolutions': () => json([row()]),
        'GET /api/v1/admin/split-resolutions/r1': () => json(unassigned()),
      })
      renderAt('/quan-tri/tranh-chap/phan-chia/r1')

      expect(await screen.findByRole('heading', { name: 'Sửa lỗi đặt bàn' })).toBeInTheDocument()
      expect(screen.getByRole('link', { name: 'Phân chia tiền' })).toHaveAttribute('aria-current', 'page')
      expect(screen.getByText(/2\.000\.000 ₫ của cả giai đoạn/)).toBeInTheDocument()
      expect(screen.getByText('3 ngày chưa quyết định')).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Nhận xử lý' })).toBeInTheDocument()
      expect(screen.queryByText('Lý do')).not.toBeInTheDocument()
      expect(screen.queryByRole('tablist')).not.toBeInTheDocument()
      expect(screen.queryByText('Quyết định')).not.toBeInTheDocument()
    },
  )

  it(
    'reads the case again after taking it, whose words the take does not answer with',
    { timeout: 20_000 },
    async () => {
      let taken = false
      fakeBackend(administrator, {
        'GET /api/v1/admin/split-resolutions': () => json([row()]),
        'GET /api/v1/admin/split-resolutions/r1': () => json(taken ? mine() : unassigned()),
        'POST /api/v1/admin/split-resolutions/r1/take': () => {
          taken = true
          return json({ id: 'r1', assignedToMe: true })
        },
        ...emptyEvidence,
      })
      renderAt('/quan-tri/tranh-chap/phan-chia/r1')

      fireEvent.click(await screen.findByRole('button', { name: 'Nhận xử lý' }))

      expect(await screen.findByText('Phần của tôi quá ít')).toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Nhận xử lý' })).not.toBeInTheDocument()
      expect(screen.getByRole('tablist')).toBeInTheDocument()
    },
  )

  it('filters by the server, showing the flag it set from the stored due time', { timeout: 20_000 }, async () => {
    const seen = fakeBackend(administrator, {
      'GET /api/v1/admin/split-resolutions': (request) =>
        json(
          new URL(request.url).searchParams.get('undecided') === 'true'
            ? [row({ undecidedLong: true })]
            : [row(), row({ id: 'r2', status: 'WITHDRAWN' })],
        ),
    })
    renderAt('/quan-tri/tranh-chap/phan-chia')

    expect(await screen.findByText(/Đã rút/)).toBeInTheDocument()
    expect(screen.queryByText('Chưa quyết định quá hạn', { selector: 'span' })).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Chưa quyết định quá hạn' }))

    expect(await screen.findByText('Chưa quyết định quá hạn', { selector: 'span' })).toBeInTheDocument()
    expect(screen.queryByText(/Đã rút/)).not.toBeInTheDocument()
    expect(seen.some((request) => request.url.includes('undecided=true'))).toBe(true)
  })

  it('names no account or signal when an Administrator cannot take a case', { timeout: 20_000 }, async () => {
    fakeBackend(administrator, {
      'GET /api/v1/admin/split-resolutions': () => json([row()]),
      'GET /api/v1/admin/split-resolutions/r1': () => json(unassigned()),
      'POST /api/v1/admin/split-resolutions/r1/take': () =>
        json({ code: 'GUARD_FAILED', reason: 'CONFLICT_OF_INTEREST', title: 'x' }, 422),
    })
    renderAt('/quan-tri/tranh-chap/phan-chia/r1')

    fireEvent.click(await screen.findByRole('button', { name: 'Nhận xử lý' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Bạn không thể nhận vụ này vì bạn có liên quan đến một tài khoản trong vụ. Hãy để quản trị viên khác nhận.',
    )
  })

  it('reads each evidence tab on opening it, pairs by kind and never a signal', { timeout: 20_000 }, async () => {
    const seen = fakeBackend(administrator, {
      'GET /api/v1/admin/split-resolutions': () => json([row({ assigned: true, assignedToMe: true })]),
      'GET /api/v1/admin/split-resolutions/r1': () => json(mine()),
      ...emptyEvidence,
      'GET /api/v1/admin/split-resolutions/r1/evidence/work-items': () =>
        json([
          {
            workItemId: 'w1',
            key: 'VNM-1',
            title: 'Làm phần thanh toán',
            status: 'DONE',
            assigneeName: 'Trần Thảo Vy',
            history: [
              { action: 'RETURNED', actorName: 'Đỗ Mạnh Hùng', comment: 'Sửa lại', at: '2026-10-10T03:00:00Z' },
            ],
          },
        ]),
      'GET /api/v1/admin/split-resolutions/r1/evidence/commits': () =>
        json([
          {
            handoverId: 'h1',
            round: 1,
            sha: 'a'.repeat(40),
            message: 'Sửa phần thanh toán',
            authorGithubId: 42,
            partyId: 'p1',
            partyName: 'Trần Thảo Vy',
          },
          { handoverId: 'h1', round: 1, sha: 'b'.repeat(40), message: 'Một người ngoài', authorGithubId: 99 },
          { handoverId: 'h1', round: 1, sha: 'c'.repeat(40), message: 'Không rõ tác giả' },
        ]),
      'GET /api/v1/admin/split-resolutions/r1/evidence/crew-chat': () =>
        json({
          from: '2026-10-01T03:00:00Z',
          to: '2026-10-20T03:00:00Z',
          total: 1,
          messages: [
            { messageId: 'm1', author: 'Trần Thảo Vy', body: 'Tin trong giai đoạn', sentAt: '2026-10-10T03:00:00Z' },
          ],
        }),
      'GET /api/v1/admin/split-resolutions/r1/evidence/person-links': () =>
        json([
          {
            firstName: 'Trần Thảo Vy',
            secondName: 'Nguyễn Lan',
            kind: 'EMAIL',
            firstCustomer: false,
            secondCustomer: true,
          },
        ]),
    })
    renderAt('/quan-tri/tranh-chap/phan-chia/r1')

    expect(await screen.findByText('Phần của tôi quá ít')).toBeInTheDocument()
    expect(screen.getByText('Tôi làm phần lớn việc')).toBeInTheDocument()
    const versions = await screen.findByText(/Bản 1 · người hướng dẫn soạn/)
    expect(versions).toBeInTheDocument()
    expect(screen.getByText(/Trần Thảo Vy · 70%/)).toHaveTextContent('xác nhận')
    expect(screen.getByText(/Không còn thông tin về thời gian tham gia/)).toBeInTheDocument()
    expect(seen.some((request) => request.url.endsWith('/evidence/work-items'))).toBe(false)

    fireEvent.click(screen.getByRole('tab', { name: 'Hạng mục' }))
    expect(await screen.findByText('Làm phần thanh toán')).toBeInTheDocument()
    expect(screen.getByText(/Sửa lại/)).toBeInTheDocument()

    fireEvent.click(screen.getByRole('tab', { name: 'Commit' }))
    expect(await screen.findByText(/Trần Thảo Vy · GitHub 42/)).toBeInTheDocument()
    expect(screen.getByText(/Không thuộc ai trong phân chia · GitHub 99/)).toBeInTheDocument()
    expect(screen.getByText(/GitHub không nhận ra tác giả/)).toBeInTheDocument()

    fireEvent.click(screen.getByRole('tab', { name: 'Trao đổi nhóm' }))
    expect(await screen.findByText('Tin trong giai đoạn')).toBeInTheDocument()
    expect(screen.queryByText(/tin mới nhất/)).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('tab', { name: 'Tài khoản có thể cùng một người' }))
    expect(await screen.findByText('Trần Thảo Vy và Nguyễn Lan (khách hàng)')).toBeInTheDocument()
    expect(screen.getByText('cùng một địa chỉ email đã xác minh')).toBeInTheDocument()
  })

  it(
    'decides with whole percentages, a live sum, the server preview, reasoning and the step-up',
    { timeout: 20_000 },
    async () => {
      const user = userEvent.setup()
      const decided: unknown[] = []
      const done = mine({
        status: 'DECIDED',
        endedAt: '2026-10-25T03:00:00Z',
        decision: {
          reasoning: 'Vy làm phần lớn việc',
          decidedAt: '2026-10-25T03:00:00Z',
          versionNo: 2,
          pooledVnd: 2_000_000,
          lines: [{ partyId: 'p1', name: 'Trần Thảo Vy', percent: 50, amountVnd: 1_000_000 }],
          evidenceSnapshot: {
            takenAt: '2026-10-25T03:00:00Z',
            versions: [],
            workItems: [],
            commits: [],
            crewChat: { messageCount: 0 },
            personLinks: 0,
          },
        },
      })
      fakeBackend(administrator, {
        'GET /api/v1/admin/split-resolutions': () => json([row({ assigned: true, assignedToMe: true })]),
        'GET /api/v1/admin/split-resolutions/r1': () => json(decided.length > 0 ? done : mine()),
        ...emptyEvidence,
        'POST /api/v1/admin/split-resolutions/r1/preview': () =>
          json({
            pooledVnd: 2_000_000,
            lines: [
              { partyId: 'p0', amountVnd: 500_000 },
              { partyId: 'p1', amountVnd: 1_000_000 },
              { partyId: 'p2', amountVnd: 500_000 },
            ],
          }),
        'POST /api/v1/admin/split-resolutions/r1/decision': async (request) => {
          decided.push(await request.json())
          return json(done)
        },
      })
      renderAt('/quan-tri/tranh-chap/phan-chia/r1')

      const send = await screen.findByRole('button', { name: 'Ghi quyết định' })
      expect(send).toBeDisabled()
      expect(screen.getByText('Tối đa 30%')).toBeInTheDocument()

      fireEvent.change(screen.getByRole('textbox', { name: /Đỗ Mạnh Hùng/ }), { target: { value: '31' } })
      expect(screen.getByText('Người hướng dẫn tối đa 30%')).toBeInTheDocument()
      fireEvent.change(screen.getByRole('textbox', { name: /Đỗ Mạnh Hùng/ }), { target: { value: '25' } })
      fireEvent.change(screen.getByRole('textbox', { name: /Trần Thảo Vy/ }), { target: { value: '0' } })
      expect(screen.getByText('Thành viên làm cả giai đoạn phải lớn hơn 0%')).toBeInTheDocument()
      fireEvent.change(screen.getByRole('textbox', { name: /Trần Thảo Vy/ }), { target: { value: '50' } })
      fireEvent.change(screen.getByRole('textbox', { name: /Nguyễn Văn Minh/ }), { target: { value: '20' } })
      expect(screen.getByText('Tổng 95%, cần đúng 100%')).toBeInTheDocument()
      fireEvent.change(screen.getByRole('textbox', { name: /Nguyễn Văn Minh/ }), { target: { value: '25' } })
      expect(
        await screen.findByText(
          /Sau quyết định: Đỗ Mạnh Hùng 25% · 500\.000 ₫, Trần Thảo Vy 50% · 1\.000\.000 ₫, Nguyễn Văn Minh 25% · 500\.000 ₫\. Tổng vẫn 2\.000\.000 ₫/,
        ),
      ).toBeInTheDocument()
      expect(send).toBeDisabled()

      fireEvent.change(screen.getByRole('textbox', { name: 'Lý do quyết định' }), {
        target: { value: 'Vy làm phần lớn việc' },
      })
      expect(send).toBeEnabled()
      await user.click(send)
      const dialog = await screen.findByRole('dialog')
      await user.type(within(dialog).getByRole('textbox'), '123456')
      await user.click(within(dialog).getByRole('button', { name: 'Ghi quyết định' }))

      await waitFor(() => expect(decided).toHaveLength(1))
      expect(decided[0]).toEqual({
        shares: [
          { partyId: 'p0', percent: 25 },
          { partyId: 'p1', percent: 50 },
          { partyId: 'p2', percent: 25 },
        ],
        reasoning: 'Vy làm phần lớn việc',
        stepUpCode: '123456',
      })
      expect(await screen.findByText('Vy làm phần lớn việc')).toBeInTheDocument()
      expect(screen.getByText(/Trần Thảo Vy 50% · 1\.000\.000 ₫/)).toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Ghi quyết định' })).not.toBeInTheDocument()
    },
  )

  it('has no cap to show or to hold under X5', { timeout: 20_000 }, async () => {
    fakeBackend(administrator, {
      'GET /api/v1/admin/split-resolutions': () => json([row({ assigned: true, assignedToMe: true })]),
      'GET /api/v1/admin/split-resolutions/r1': () => json(mine({ cap: undefined, capApplies: false })),
      ...emptyEvidence,
      'POST /api/v1/admin/split-resolutions/r1/preview': () => json({ pooledVnd: 2_000_000, lines: [] }),
    })
    renderAt('/quan-tri/tranh-chap/phan-chia/r1')

    await screen.findByRole('button', { name: 'Ghi quyết định' })
    expect(screen.queryByText('Tối đa 30%')).not.toBeInTheDocument()

    fireEvent.change(screen.getByRole('textbox', { name: /Đỗ Mạnh Hùng/ }), { target: { value: '100' } })
    fireEvent.change(screen.getByRole('textbox', { name: /Trần Thảo Vy/ }), { target: { value: '0' } })
    fireEvent.change(screen.getByRole('textbox', { name: /Nguyễn Văn Minh/ }), { target: { value: '0' } })
    expect(screen.queryByText('Thành viên làm cả giai đoạn phải lớn hơn 0%')).not.toBeInTheDocument()
    expect(screen.getByText('Tổng 100%, cần đúng 100%')).toBeInTheDocument()
  })

  it('shows a withdrawn case with no form and no evidence', { timeout: 20_000 }, async () => {
    fakeBackend(administrator, {
      'GET /api/v1/admin/split-resolutions': () => json([row({ status: 'WITHDRAWN' })]),
      'GET /api/v1/admin/split-resolutions/r1': () =>
        json(mine({ status: 'WITHDRAWN', endedAt: '2026-10-24T03:00:00Z', evidenceReadable: false })),
    })
    renderAt('/quan-tri/tranh-chap/phan-chia/r1')

    expect(await screen.findByText(/Đã rút/, { selector: 'p' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Ghi quyết định' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Nhận xử lý' })).not.toBeInTheDocument()
    expect(screen.queryByRole('tablist')).not.toBeInTheDocument()
  })

  it('says how many of the interval the Crew Chat shows', { timeout: 20_000 }, async () => {
    fakeBackend(administrator, {
      'GET /api/v1/admin/split-resolutions': () => json([row({ assigned: true, assignedToMe: true })]),
      'GET /api/v1/admin/split-resolutions/r1': () => json(mine()),
      ...emptyEvidence,
      'GET /api/v1/admin/split-resolutions/r1/evidence/crew-chat': () =>
        json({
          from: '2026-10-01T03:00:00Z',
          to: '2026-10-20T03:00:00Z',
          total: 1204,
          messages: [{ messageId: 'm1', author: 'Trần Thảo Vy', body: 'Tin mới', sentAt: '2026-10-10T03:00:00Z' }],
        }),
    })
    renderAt('/quan-tri/tranh-chap/phan-chia/r1')

    fireEvent.click(await screen.findByRole('tab', { name: 'Trao đổi nhóm' }))

    expect(await screen.findByText('Hiện 1 tin mới nhất trong 1204 tin của giai đoạn.')).toBeInTheDocument()
  })

  it(
    'reads an open case again every 30 s, keeping what is typed through it and through a failed read',
    { timeout: 20_000 },
    async () => {
      vi.useFakeTimers({ shouldAdvanceTime: true })
      let answer = () => json(mine())
      fakeBackend(administrator, {
        'GET /api/v1/admin/split-resolutions': () => json([row({ assigned: true, assignedToMe: true })]),
        'GET /api/v1/admin/split-resolutions/r1': () => answer(),
        ...emptyEvidence,
        'POST /api/v1/admin/split-resolutions/r1/preview': previewOf,
      })
      renderAt('/quan-tri/tranh-chap/phan-chia/r1')
      await screen.findByRole('button', { name: 'Ghi quyết định' })
      typeDecision()
      const typed = () => {
        expect(screen.getByRole('textbox', { name: /Trần Thảo Vy/ })).toHaveValue('50')
        expect(screen.getByRole('textbox', { name: 'Lý do quyết định' })).toHaveValue('Vy làm phần lớn việc')
      }

      answer = () =>
        json(
          mine({
            statements: [
              {
                partyId: 'p2',
                name: 'Nguyễn Văn Minh',
                text: 'Tôi cũng có ý kiến',
                submittedAt: '2026-10-24T04:00:00Z',
              },
            ],
          }),
        )
      await vi.advanceTimersByTimeAsync(30_000)

      expect(await screen.findByText('Tôi cũng có ý kiến')).toBeInTheDocument()
      typed()

      answer = () => json({ status: 500 }, 500)
      await vi.advanceTimersByTimeAsync(30_000)
      await vi.advanceTimersByTimeAsync(10)

      typed()
      expect(screen.getByText('Tôi cũng có ý kiến')).toBeInTheDocument()
      expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    },
  )

  it('stops reading a case again once it has ended', { timeout: 20_000 }, async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const seen = fakeBackend(administrator, {
      'GET /api/v1/admin/split-resolutions': () => json([row({ status: 'WITHDRAWN' })]),
      'GET /api/v1/admin/split-resolutions/r1': () =>
        json(mine({ status: 'WITHDRAWN', endedAt: '2026-10-24T03:00:00Z', evidenceReadable: false })),
    })
    const reads = () => seen.filter((request) => request.url.endsWith('/api/v1/admin/split-resolutions/r1')).length
    renderAt('/quan-tri/tranh-chap/phan-chia/r1')
    await screen.findByText(/Đã rút/, { selector: 'p' })
    const before = reads()

    await vi.advanceTimersByTimeAsync(30_000)
    await vi.advanceTimersByTimeAsync(10)

    expect(reads()).toBe(before)
  })

  it('keeps "Ghi quyết định" disabled while the preview loads', { timeout: 20_000 }, async () => {
    const seen = fakeBackend(administrator, {
      'GET /api/v1/admin/split-resolutions': () => json([row({ assigned: true, assignedToMe: true })]),
      'GET /api/v1/admin/split-resolutions/r1': () => json(mine()),
      ...emptyEvidence,
      'POST /api/v1/admin/split-resolutions/r1/preview': () => new Promise<Response>(() => {}),
    })
    renderAt('/quan-tri/tranh-chap/phan-chia/r1')
    const send = await screen.findByRole('button', { name: 'Ghi quyết định' })

    typeDecision()

    await waitFor(() => expect(seen.some((request) => request.url.endsWith('/preview'))).toBe(true))
    expect(send).toBeDisabled()
  })

  it('keeps "Ghi quyết định" disabled when the preview failed, and says why', { timeout: 20_000 }, async () => {
    fakeBackend(administrator, {
      'GET /api/v1/admin/split-resolutions': () => json([row({ assigned: true, assignedToMe: true })]),
      'GET /api/v1/admin/split-resolutions/r1': () => json(mine()),
      ...emptyEvidence,
      'POST /api/v1/admin/split-resolutions/r1/preview': () => json({ code: 'CONFLICT', reason: 'NOT_ASSIGNED' }, 409),
    })
    renderAt('/quan-tri/tranh-chap/phan-chia/r1')
    const send = await screen.findByRole('button', { name: 'Ghi quyết định' })

    typeDecision()

    expect(await screen.findByRole('alert')).toHaveTextContent('Chỉ quản trị viên đã nhận xử lý mới ghi quyết định.')
    expect(send).toBeDisabled()
  })

  it('shows each party, their percent and their VND in the step-up', { timeout: 20_000 }, async () => {
    fakeBackend(administrator, {
      'GET /api/v1/admin/split-resolutions': () => json([row({ assigned: true, assignedToMe: true })]),
      'GET /api/v1/admin/split-resolutions/r1': () => json(mine()),
      ...emptyEvidence,
      'POST /api/v1/admin/split-resolutions/r1/preview': previewOf,
    })
    renderAt('/quan-tri/tranh-chap/phan-chia/r1')
    const send = await screen.findByRole('button', { name: 'Ghi quyết định' })
    typeDecision()
    await waitFor(() => expect(send).toBeEnabled())

    fireEvent.click(send)
    const dialog = await screen.findByRole('dialog')

    for (const line of [
      /Đỗ Mạnh Hùng 25% · 500\.000 ₫/,
      /Trần Thảo Vy 50% · 1\.000\.000 ₫/,
      /Nguyễn Văn Minh 25% · 500\.000 ₫/,
    ]) {
      expect(within(dialog).getByText(line).closest('li')).toHaveTextContent('THỬ NGHIỆM')
    }
  })

  it('keeps a wrong code in the step-up, with the attempts left', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(administrator, {
      'GET /api/v1/admin/split-resolutions': () => json([row({ assigned: true, assignedToMe: true })]),
      'GET /api/v1/admin/split-resolutions/r1': () => json(mine()),
      ...emptyEvidence,
      'POST /api/v1/admin/split-resolutions/r1/preview': previewOf,
      'POST /api/v1/admin/split-resolutions/r1/decision': () =>
        json({ code: 'GUARD_FAILED', reason: 'STEP_UP_FAILED', attemptsLeft: 4 }, 422),
    })
    renderAt('/quan-tri/tranh-chap/phan-chia/r1')
    const send = await screen.findByRole('button', { name: 'Ghi quyết định' })
    typeDecision()
    await waitFor(() => expect(send).toBeEnabled())
    await user.click(send)
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByRole('textbox'), '123456')
    await user.click(within(dialog).getByRole('button', { name: 'Ghi quyết định' }))

    expect(await within(dialog).findByText('Mã chưa đúng. Bạn còn 4 lần thử.')).toBeInTheDocument()
    expect(screen.getByRole('dialog')).toBe(dialog)
  })

  for (const [reason, status, code, wording, after] of [
    [
      'SPLIT_RESOLUTION_ENDED',
      422,
      'GUARD_FAILED',
      'Yêu cầu xem xét này đã kết thúc.',
      () => mine({ status: 'WITHDRAWN', endedAt: '2026-10-24T03:00:00Z', evidenceReadable: false }),
    ],
    [
      'NOT_ASSIGNED',
      409,
      'CONFLICT',
      'Chỉ quản trị viên đã nhận xử lý mới ghi quyết định.',
      () => unassigned({ assigned: true }),
    ],
    [
      'ALREADY_DECIDED',
      409,
      'CONFLICT',
      'Yêu cầu này đã có quyết định.',
      () => mine({ status: 'DECIDED', endedAt: '2026-10-25T03:00:00Z', decision: record }),
    ],
    [
      'SPLIT_INVALID',
      400,
      'VALIDATION',
      'Phần trăm phải là số nguyên, tổng đúng 100%; người hướng dẫn tối đa 30%; thành viên làm cả giai đoạn phải lớn hơn 0%.',
      () => mine(),
    ],
    [
      'CONFLICT_OF_INTEREST',
      422,
      'GUARD_FAILED',
      'Bạn không thể nhận vụ này vì bạn có liên quan đến một tài khoản trong vụ.',
      () => mine(),
    ],
  ] as const) {
    it(`keeps ${reason}'s wording above the form, whatever the read after it did`, { timeout: 20_000 }, async () => {
      const user = userEvent.setup()
      let refused = false
      fakeBackend(administrator, {
        'GET /api/v1/admin/split-resolutions': () => json([row({ assigned: true, assignedToMe: true })]),
        'GET /api/v1/admin/split-resolutions/r1': () => json(refused ? after() : mine()),
        ...emptyEvidence,
        'POST /api/v1/admin/split-resolutions/r1/preview': previewOf,
        'POST /api/v1/admin/split-resolutions/r1/decision': () => {
          refused = true
          return json({ code, reason }, status)
        },
      })
      renderAt('/quan-tri/tranh-chap/phan-chia/r1')
      const send = await screen.findByRole('button', { name: 'Ghi quyết định' })
      typeDecision()
      await waitFor(() => expect(send).toBeEnabled())
      await user.click(send)
      const dialog = await screen.findByRole('dialog')
      await user.type(within(dialog).getByRole('textbox'), '123456')
      await user.click(within(dialog).getByRole('button', { name: 'Ghi quyết định' }))

      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
      expect(await screen.findByRole('alert')).toHaveTextContent(wording)
      if (reason === 'SPLIT_INVALID') {
        expect(screen.getByRole('textbox', { name: 'Lý do quyết định' })).toHaveValue('Vy làm phần lớn việc')
        expect(screen.getByRole('textbox', { name: /Trần Thảo Vy/ })).toHaveValue('50')
      }
    })
  }

  it(
    'shows any Administrator a decided case’s record with its version, and none of its words',
    { timeout: 20_000 },
    async () => {
      fakeBackend(administrator, {
        'GET /api/v1/admin/split-resolutions': () => json([row({ status: 'DECIDED', assigned: true })]),
        'GET /api/v1/admin/split-resolutions/r1': () =>
          json(unassigned({ status: 'DECIDED', endedAt: '2026-10-25T03:00:00Z', assigned: true, decision: record })),
      })
      renderAt('/quan-tri/tranh-chap/phan-chia/r1')

      expect(await screen.findByText('Vy làm phần lớn việc')).toBeInTheDocument()
      expect(screen.getByText(/Bản 2 · quyết định lúc/)).toBeInTheDocument()
      expect(screen.getByText(/Trần Thảo Vy 50% · 1\.000\.000 ₫/)).toBeInTheDocument()
      expect(screen.queryByText('Lý do')).not.toBeInTheDocument()
      expect(screen.queryByRole('tablist')).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Nhận xử lý' })).not.toBeInTheDocument()
      expect(screen.queryByText(/theo thời hạn lưu/)).not.toBeInTheDocument()
    },
  )

  it(
    'hides the evidence once the Job’s content has gone, and says so beside the record',
    { timeout: 20_000 },
    async () => {
      const seen = fakeBackend(administrator, {
        'GET /api/v1/admin/split-resolutions': () =>
          json([row({ status: 'DECIDED', assigned: true, assignedToMe: true })]),
        'GET /api/v1/admin/split-resolutions/r1': () =>
          json(
            mine({
              status: 'DECIDED',
              endedAt: '2026-10-25T03:00:00Z',
              decision: record,
              evidenceReadable: false,
              jobContentGone: true,
            }),
          ),
      })
      renderAt('/quan-tri/tranh-chap/phan-chia/r1')

      expect(
        await screen.findByText('Nội dung của công việc đã xóa theo thời hạn lưu; bản ghi quyết định vẫn còn.'),
      ).toBeInTheDocument()
      expect(screen.getByText('Vy làm phần lớn việc')).toBeInTheDocument()
      expect(screen.queryByRole('tablist')).not.toBeInTheDocument()
      expect(seen.some((request) => request.url.includes('/evidence/'))).toBe(false)
    },
  )

  it('counts the open cases on ADM-1 and leads to the tab', { timeout: 20_000 }, async () => {
    fakeBackend(administrator, {
      'GET /api/v1/admin/overview': () =>
        json({
          failedOperations: { count: 0 },
          stalledEvents: { count: 0 },
          activeAdministrators: 1,
          disputes: { count: 0 },
          lowFeedback: { count: 0 },
          failedExecutions: { count: 0 },
          teamIssues: { count: 0 },
          skillSuggestions: { count: 0 },
          splitResolutions: { count: 2, oldestAt: '2026-10-20T03:00:00Z' },
        }),
    })
    renderAt('/quan-tri')

    const card = (await screen.findByText('Yêu cầu xem xét phân chia đang mở')).closest('a')
    expect(card).toHaveAttribute('href', '/quan-tri/tranh-chap/phan-chia')
    expect(card).toHaveTextContent('2')
  })
})
