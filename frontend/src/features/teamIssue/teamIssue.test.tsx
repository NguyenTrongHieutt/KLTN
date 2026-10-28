import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import type { ReactNode } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { routes } from '@/app/routes'
import { Toaster } from '@/components/Toaster'
import { fakeBackend, json, signedInUser } from '@/test/fakeBackend'
import { JobTeamIssues } from './JobTeamIssues'
import { TeamIssueSection } from './TeamIssueSection'

const issue = {
  id: 'i1',
  jobId: 'j1',
  subject: 'SHARE',
  milestoneId: 'm1',
  milestoneNumber: 2,
  raiserName: 'Trần Thảo Vy',
  mine: false,
  reason: 'Mình làm gần hết phần đặt bàn khi Minh ốm.',
  phase: 'ANSWER',
  raisedAt: '2026-10-23T03:00:00Z',
  answerDueAt: '2026-10-28T03:00:00Z',
}

const panel = (overrides: object = {}) => ({
  jobId: 'j1',
  title: 'Sửa lỗi đặt bàn',
  mentorName: 'Đỗ Mạnh Hùng',
  shares: [{ milestoneId: 'm1', number: 2, title: 'Đặt bàn theo giờ' }],
  ratings: [
    {
      ratingId: 'r1',
      stars: 2,
      visibleAt: '2026-10-01T03:00:00Z',
      closesAt: '2026-10-15T03:00:00Z',
      refusal: 'TEAM_ISSUE_ALREADY_RAISED',
      endedAt: '2026-10-10T03:00:00Z',
    },
  ],
  issues: [],
  ...overrides,
})

function renderWith(node: ReactNode) {
  render(
    <QueryClientProvider client={createQueryClient()}>
      <Toaster>{node}</Toaster>
    </QueryClientProvider>,
  )
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('STU-4 "Khiếu nại nội bộ" (FR-79, AD-40, UX-DR63)', () => {
  it('sends a share issue with its reason, and a used option says why it is disabled', { timeout: 15_000 }, async () => {
    const user = userEvent.setup()
    const sent: unknown[] = []
    let raised = false
    fakeBackend(signedInUser, {
      'GET /api/v1/team-issues': () =>
        json([raised ? panel({ shares: [{ milestoneId: 'm1', number: 2, title: 'Đặt bàn theo giờ', refusal: 'TEAM_ISSUE_ALREADY_RAISED' }], issues: [{ ...issue, mine: true, raiserName: null }] }) : panel()]),
      'POST /api/v1/team-issues': async (request) => {
        sent.push(await request.json())
        raised = true
        return json({ ...issue, mine: true }, 201)
      },
    })
    renderWith(<TeamIssueSection />)

    expect(await screen.findByText('Người hướng dẫn: Đỗ Mạnh Hùng')).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: /Điểm người hướng dẫn chấm tôi/ })).toBeDisabled()
    expect(screen.getByText(/Bạn đã khiếu nại điểm này; khiếu nại kết thúc ngày 10\/10/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Gửi khiếu nại' })).toBeDisabled()

    await user.click(screen.getByRole('radio', { name: /Phần chia giai đoạn 2/ }))
    await user.type(screen.getByRole('textbox', { name: 'Lý do' }), 'Mình làm gần hết phần đặt bàn.')
    await user.click(screen.getByRole('button', { name: 'Gửi khiếu nại' }))

    expect(await screen.findAllByText('Đã gửi khiếu nại nội bộ cho người hướng dẫn.')).not.toHaveLength(0)
    expect(sent).toEqual([{ jobId: 'j1', subject: 'SHARE', milestoneId: 'm1', reason: 'Mình làm gần hết phần đặt bàn.' }])
    expect(await screen.findByText('Chờ người hướng dẫn trả lời trước 28/10')).toBeInTheDocument()
    expect(screen.getByText('Phần tiền của hai bạn cho giai đoạn này được giữ lại cho đến khi có kết quả')).toBeInTheDocument()
  })

  it('reads a refusal in plain words', { timeout: 15_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(signedInUser, {
      'GET /api/v1/team-issues': () => json([panel({ ratings: [] })]),
      'POST /api/v1/team-issues': () => json({ code: 'GUARD_FAILED', reason: 'MILESTONE_NOT_HELD' }, 422),
    })
    renderWith(<TeamIssueSection />)

    await user.click(await screen.findByRole('radio', { name: /Phần chia giai đoạn 2/ }))
    await user.type(screen.getByRole('textbox', { name: 'Lý do' }), 'Chưa công bằng.')
    await user.click(screen.getByRole('button', { name: 'Gửi khiếu nại' }))

    expect(await screen.findAllByText(/giai đoạn chưa được thanh toán hoặc đã chuyển tiền/)).not.toHaveLength(0)
  })

  it('shows nothing when there is nothing to raise', async () => {
    let read = false
    fakeBackend(signedInUser, {
      'GET /api/v1/team-issues': () => {
        read = true
        return json([])
      },
    })
    renderWith(<TeamIssueSection />)

    await vi.waitFor(() => expect(read).toBe(true))
    expect(screen.queryByRole('heading', { name: 'Khiếu nại nội bộ' })).toBeNull()
  })
})

describe('JOB-1: the Mentor reads the Team Issues of the Job (AD-40)', () => {
  it('lists who raised what and by when to answer', async () => {
    fakeBackend(signedInUser, {
      'GET /api/v1/boards/j1/team-issues': () => json({ jobId: 'j1', mentor: true, issues: [issue] }),
    })
    renderWith(<JobTeamIssues jobId="j1" />)

    expect(await screen.findByText('Trần Thảo Vy · Phần chia giai đoạn 2')).toBeInTheDocument()
    expect(screen.getByText('Mình làm gần hết phần đặt bàn khi Minh ốm.')).toBeInTheDocument()
    expect(screen.getByText('Hãy trả lời trước 28/10')).toBeInTheDocument()
  })
})

const split = { basisVnd: 1000000, mentorPercent: 20, raiserPercent: 40, capPoints: 20, others: [40] }

describe('Story 12.2: the Mentor answers once, the raiser accepts or escalates (AD-40)', () => {
  it('previews a transfer from the same split, refuses one over the cap, and sends it after confirming', { timeout: 15_000 }, async () => {
    const user = userEvent.setup()
    const sent: unknown[] = []
    fakeBackend(signedInUser, {
      'GET /api/v1/boards/j1/team-issues': () => json({ jobId: 'j1', mentor: true, issues: [{ ...issue, split }] }),
      'POST /api/v1/boards/j1/team-issues/i1/answer': async (request) => {
        sent.push(await request.json())
        return json({ ...issue, phase: 'ESCALATION', answerKind: 'TRANSFER', answerPoints: 10 })
      },
    })
    renderWith(<JobTeamIssues jobId="j1" />)

    await user.click(await screen.findByRole('radio', { name: 'Chuyển điểm phần trăm từ phần của tôi cho Trần Thảo Vy' }))
    expect(screen.getByText('Tối đa 20 điểm: phần của bạn ở Giai đoạn 2 là 20%.')).toBeInTheDocument()
    await user.type(screen.getByRole('textbox', { name: 'Số điểm phần trăm' }), '25')
    expect(screen.getByText(/tối đa 20 điểm phần trăm/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Gửi câu trả lời' })).toBeDisabled()

    await user.clear(screen.getByRole('textbox', { name: 'Số điểm phần trăm' }))
    await user.type(screen.getByRole('textbox', { name: 'Số điểm phần trăm' }), '10')
    expect(screen.getByText(/bạn 10% · 100\.000 ₫, Trần Thảo Vy 50% · 500\.000 ₫/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Gửi câu trả lời' }))
    const dialog = await screen.findByRole('dialog', { name: 'Gửi câu trả lời này?' })
    await user.click(within(dialog).getByRole('button', { name: 'Gửi câu trả lời' }))

    expect(await screen.findAllByText('Đã gửi câu trả lời.')).not.toHaveLength(0)
    expect(sent).toEqual([{ kind: 'TRANSFER', points: 10 }])
  })

  it('shows the raiser the answer with its preview, then accepts it', { timeout: 15_000 }, async () => {
    const user = userEvent.setup()
    const chosen: string[] = []
    const answered = {
      ...issue,
      mine: true,
      raiserName: null,
      phase: 'ESCALATION',
      answerKind: 'TRANSFER',
      answerPoints: 10,
      escalationDueAt: '2026-11-02T03:00:00Z',
      split: { ...split, capPoints: null, others: [] },
      answerPreview: { raiserPercent: 50, raiserVnd: 500000, mentorPercent: 10, mentorVnd: 100000 },
    }
    fakeBackend(signedInUser, {
      'GET /api/v1/team-issues': () => json([panel({ shares: [], ratings: [], issues: [answered] })]),
      'POST /api/v1/team-issues/i1/accept': () => {
        chosen.push('accept')
        return json({ ...answered, phase: 'ENDED', ending: 'ACCEPTED', outcomePoints: 10, endedAt: '2026-10-25T03:00:00Z' })
      },
    })
    renderWith(<TeamIssueSection />)

    expect(await screen.findByText('Câu trả lời: chuyển 10 điểm phần trăm từ phần của người hướng dẫn')).toBeInTheDocument()
    expect(screen.getByText(/bạn 50% · 500\.000 ₫, Đỗ Mạnh Hùng 10% · 100\.000 ₫/)).toBeInTheDocument()
    expect(screen.getByText(/Nếu bạn không chọn trước 02\/11/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Nhờ quản trị viên xem xét' })).toBeEnabled()
    await user.click(screen.getByRole('button', { name: 'Chấp nhận' }))

    expect(await screen.findAllByText('Đã chấp nhận câu trả lời.')).not.toHaveLength(0)
    expect(chosen).toEqual(['accept'])
  })

  it('offers only "Nhờ quản trị viên xem xét" after a silent Mentor', async () => {
    fakeBackend(signedInUser, {
      'GET /api/v1/team-issues': () =>
        json([panel({ shares: [], ratings: [], issues: [{ ...issue, mine: true, phase: 'ESCALATION', escalationDueAt: '2026-11-02T03:00:00Z' }] })]),
    })
    renderWith(<TeamIssueSection />)

    expect(await screen.findByText('Người hướng dẫn chưa trả lời trong thời hạn.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Chấp nhận' })).toBeNull()
    expect(screen.getByRole('button', { name: 'Nhờ quản trị viên xem xét' })).toBeInTheDocument()
  })
})

describe('STU-7: a held part is "Đang giữ" and not in the Balance (AD-40)', () => {
  it('lists the held part apart from the Balance', { timeout: 15_000 }, async () => {
    fakeBackend(signedInUser, {
      'GET /api/v1/balance': () =>
        json({
          balanceVnd: 0,
          releases: 0,
          lines: [],
          heldParts: [
            { jobId: 'j1', jobTitle: 'Sửa lỗi đặt bàn', number: 2, milestoneTitle: 'Đặt bàn', amountVnd: 400000, heldAt: '2026-10-23T03:00:00Z' },
          ],
        }),
    })
    const router = createMemoryRouter(routes, { initialEntries: ['/so-du'] })
    render(
      <QueryClientProvider client={createQueryClient()}>
        <RouterProvider router={router} />
      </QueryClientProvider>,
    )

    expect(await screen.findByRole('heading', { name: 'Đang giữ' })).toBeInTheDocument()
    expect(screen.getByText('Sửa lỗi đặt bàn · Giai đoạn 2')).toBeInTheDocument()
    expect(screen.getByText(/Phần này chưa tính vào số dư/)).toBeInTheDocument()
  })
})
