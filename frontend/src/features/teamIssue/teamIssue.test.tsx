import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { Toaster } from '@/components/Toaster'
import { fakeBackend, json, signedInUser } from '@/test/fakeBackend'
import { JobTeamIssues } from './JobTeamIssues'
import { TeamIssueSection } from './TeamIssueSection'

const issue = {
  id: 'i1',
  jobId: 'j1',
  ratingId: 'r2',
  raiserName: 'Trần Thảo Vy',
  mine: false,
  reason: 'Điểm này chưa đúng với phần việc mình làm.',
  phase: 'ANSWER',
  raisedAt: '2026-10-23T03:00:00Z',
  answerDueAt: '2026-10-28T03:00:00Z',
}

const panel = (overrides: object = {}) => ({
  jobId: 'j1',
  title: 'Sửa lỗi đặt bàn',
  mentorName: 'Đỗ Mạnh Hùng',
  ratings: [
    {
      ratingId: 'r1',
      stars: 2,
      visibleAt: '2026-10-01T03:00:00Z',
      closesAt: '2026-10-15T03:00:00Z',
      refusal: 'TEAM_ISSUE_ALREADY_RAISED',
      endedAt: '2026-10-10T03:00:00Z',
    },
    { ratingId: 'r2', stars: 3, visibleAt: '2026-10-20T03:00:00Z', closesAt: '2026-11-03T03:00:00Z' },
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

describe('"Khiếu nại nội bộ" in "Việc tôi tham gia" (FR-79, AD-40, UX-DR63)', () => {
  it('sends an issue about a Rating with its reason, and a used Rating says why it is disabled', { timeout: 15_000 }, async () => {
    const user = userEvent.setup()
    const sent: unknown[] = []
    let raised = false
    fakeBackend(signedInUser, {
      'GET /api/v1/team-issues': () =>
        json([
          raised
            ? panel({
                ratings: [panel().ratings[0], { ...panel().ratings[1], refusal: 'TEAM_ISSUE_ALREADY_RAISED' }],
                issues: [{ ...issue, mine: true, raiserName: null }],
              })
            : panel(),
        ]),
      'POST /api/v1/team-issues': async (request) => {
        sent.push(await request.json())
        raised = true
        return json({ ...issue, mine: true }, 201)
      },
    })
    renderWith(<TeamIssueSection />)

    expect(await screen.findByText('Người hướng dẫn: Đỗ Mạnh Hùng')).toBeInTheDocument()
    const ratings = screen.getAllByRole('radio', { name: /Điểm người hướng dẫn chấm tôi/ })
    expect(ratings).toHaveLength(2)
    expect(ratings[0]).toBeDisabled()
    expect(screen.getByText(/Bạn đã khiếu nại điểm này; khiếu nại kết thúc ngày 10\/10/)).toBeInTheDocument()
    expect(screen.getByText('3 sao · khiếu nại được đến 03/11')).toBeInTheDocument()
    expect(screen.queryByText(/Phần chia/)).toBeNull()
    expect(screen.getByRole('button', { name: 'Gửi khiếu nại' })).toBeDisabled()

    await user.click(ratings[1])
    await user.type(screen.getByRole('textbox', { name: 'Lý do' }), 'Điểm này chưa đúng.')
    await user.click(screen.getByRole('button', { name: 'Gửi khiếu nại' }))

    expect(await screen.findAllByText('Đã gửi khiếu nại nội bộ cho người hướng dẫn.')).not.toHaveLength(0)
    expect(sent).toEqual([{ jobId: 'j1', ratingId: 'r2', reason: 'Điểm này chưa đúng.' }])
    expect(await screen.findByText('Chờ người hướng dẫn trả lời trước 28/10')).toBeInTheDocument()
  })

  it('reads a refusal in plain words', { timeout: 15_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(signedInUser, {
      'GET /api/v1/team-issues': () => json([panel()]),
      'POST /api/v1/team-issues': () => json({ code: 'GUARD_FAILED', reason: 'TEAM_ISSUE_WINDOW_CLOSED' }, 422),
    })
    renderWith(<TeamIssueSection />)

    await user.click((await screen.findAllByRole('radio', { name: /Điểm người hướng dẫn chấm tôi/ }))[1])
    await user.type(screen.getByRole('textbox', { name: 'Lý do' }), 'Chưa công bằng.')
    await user.click(screen.getByRole('button', { name: 'Gửi khiếu nại' }))

    expect(await screen.findAllByText('Đã quá thời hạn gửi khiếu nại cho mục này.')).not.toHaveLength(0)
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

    expect(await screen.findByText('Trần Thảo Vy · Điểm người hướng dẫn chấm tôi')).toBeInTheDocument()
    expect(screen.getByText('Điểm này chưa đúng với phần việc mình làm.')).toBeInTheDocument()
    expect(screen.getByText('Hãy trả lời trước 28/10')).toBeInTheDocument()
  })

  it('reads how each ended issue came out, a moot one by its ending alone', async () => {
    const ended = { phase: 'ENDED', endedAt: '2026-10-30T03:00:00Z' }
    fakeBackend(signedInUser, {
      'GET /api/v1/boards/j1/team-issues': () =>
        json({
          jobId: 'j1',
          mentor: true,
          issues: [
            { ...issue, ...ended, id: 'i1', ending: 'MOOT', outcomeRatingRemoved: true },
            { ...issue, ...ended, id: 'i2', ending: 'DECIDED', outcomeRatingRemoved: false },
          ],
        }),
    })
    renderWith(<JobTeamIssues jobId="j1" />)

    expect(await screen.findByText('Kết thúc vì điểm đã được gỡ theo cách khác')).toBeInTheDocument()
    expect(screen.queryByText(/Điểm đã được rút lại/)).toBeNull()
    expect(screen.getByText('Kết thúc theo quyết định của quản trị viên · Điểm giữ nguyên')).toBeInTheDocument()
  })
})

describe('Story 12.2: the Mentor answers once, the raiser accepts or escalates (AD-40)', () => {
  it('keeps or withdraws the Rating and sends the answer after confirming', { timeout: 15_000 }, async () => {
    const user = userEvent.setup()
    const sent: unknown[] = []
    fakeBackend(signedInUser, {
      'GET /api/v1/boards/j1/team-issues': () => json({ jobId: 'j1', mentor: true, issues: [issue] }),
      'POST /api/v1/boards/j1/team-issues/i1/answer': async (request) => {
        sent.push(await request.json())
        return json({ ...issue, phase: 'ESCALATION', answerKind: 'WITHDRAW_RATING' })
      },
    })
    renderWith(<JobTeamIssues jobId="j1" />)

    expect(await screen.findByRole('radio', { name: 'Giữ nguyên điểm' })).toBeChecked()
    expect(screen.queryByText(/điểm phần trăm/)).toBeNull()
    await user.click(screen.getByRole('radio', { name: 'Rút lại điểm' }))
    await user.click(screen.getByRole('button', { name: 'Gửi câu trả lời' }))
    const dialog = await screen.findByRole('dialog', { name: 'Gửi câu trả lời này?' })
    await user.click(within(dialog).getByRole('button', { name: 'Gửi câu trả lời' }))

    expect(await screen.findAllByText('Đã gửi câu trả lời.')).not.toHaveLength(0)
    expect(sent).toEqual([{ kind: 'WITHDRAW_RATING' }])
  })

  it('shows the raiser the answer, then accepts it', { timeout: 15_000 }, async () => {
    const user = userEvent.setup()
    const chosen: string[] = []
    const answered = {
      ...issue,
      mine: true,
      raiserName: null,
      phase: 'ESCALATION',
      answerKind: 'WITHDRAW_RATING',
      escalationDueAt: '2026-11-02T03:00:00Z',
    }
    fakeBackend(signedInUser, {
      'GET /api/v1/team-issues': () => json([panel({ ratings: [], issues: [answered] })]),
      'POST /api/v1/team-issues/i1/accept': () => {
        chosen.push('accept')
        return json({ ...answered, phase: 'ENDED', ending: 'ACCEPTED', outcomeRatingRemoved: true, endedAt: '2026-10-25T03:00:00Z' })
      },
    })
    renderWith(<TeamIssueSection />)

    expect(await screen.findByText('Câu trả lời: rút lại điểm')).toBeInTheDocument()
    expect(screen.getByText(/Nếu bạn không chọn trước 02\/11/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Nhờ quản trị viên xem xét' })).toBeEnabled()
    await user.click(screen.getByRole('button', { name: 'Chấp nhận' }))

    expect(await screen.findAllByText('Đã chấp nhận câu trả lời.')).not.toHaveLength(0)
    expect(chosen).toEqual(['accept'])
  })

  it('offers only "Nhờ quản trị viên xem xét" after a silent Mentor', async () => {
    fakeBackend(signedInUser, {
      'GET /api/v1/team-issues': () =>
        json([panel({ ratings: [], issues: [{ ...issue, mine: true, phase: 'ESCALATION', escalationDueAt: '2026-11-02T03:00:00Z' }] })]),
    })
    renderWith(<TeamIssueSection />)

    expect(await screen.findByText('Người hướng dẫn chưa trả lời trong thời hạn.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Chấp nhận' })).toBeNull()
    expect(screen.getByRole('button', { name: 'Nhờ quản trị viên xem xét' })).toBeInTheDocument()
  })
})
