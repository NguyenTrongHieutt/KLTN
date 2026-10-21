import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { fakeBackend, json, signedIn } from '@/test/fakeBackend'
import { SetupTracker } from './SetupTracker'

const setup = (status: string, states: string[], extra: Record<string, unknown> = {}) => ({
  jobId: 'j1',
  jobState: 'STARTING',
  status,
  steps: ['CHECK', 'INTERMEDIARY', 'WORKING', 'INVITE', 'VERIFY'].map((key, index) => ({ key, state: states[index] ?? 'PENDING' })),
  canRetry: false,
  people: [],
  ...extra,
})

function renderTracker() {
  render(
    <QueryClientProvider client={createQueryClient()}>
      <SetupTracker jobId="j1" />
    </QueryClientProvider>,
  )
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('JOB-H step tracker while Starting (UX-DR23, AD-11)', () => {
  it('shows each step and tells everyone they need not act', async () => {
    fakeBackend(signedIn('CUSTOMER'), {
      'GET /api/v1/jobs/j1/setup': () => json(setup('RUNNING', ['DONE', 'CURRENT', 'PENDING'])),
    })
    renderTracker()

    expect(await screen.findByRole('heading', { name: 'Chuẩn bị kho mã' })).toBeInTheDocument()
    expect(screen.getByText('Kiểm tra lại dự án gốc')).toBeInTheDocument()
    expect(screen.getByText('Tạo kho làm việc')).toBeInTheDocument()
    expect(screen.getByText('Bạn không cần làm gì.')).toBeInTheDocument()
    expect(screen.getByText('Tạo kho trung gian: Đang làm')).toHaveAttribute('aria-live', 'polite')
  })

  it('tells the Customer what to fix after a block, and starts again on "Thử lại"', async () => {
    const user = userEvent.setup()
    const seen = fakeBackend(signedIn('CUSTOMER'), {
      'GET /api/v1/jobs/j1/setup': () =>
        json(setup('BLOCKED', ['BLOCKED', 'PENDING', 'PENDING'], { blockedReason: 'INTAKE_FINDING_OPEN', openFindings: 2, canRetry: true })),
      'POST /api/v1/jobs/j1/setup/retry': () => json(setup('RUNNING', ['CURRENT', 'PENDING', 'PENDING'])),
    })
    renderTracker()

    expect(await screen.findByText('Còn 2 vấn đề bảo mật chưa xử lý ở dự án gốc. Hãy xử lý rồi bấm Thử lại.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Thử lại' }))

    expect(await screen.findByText('Bạn không cần làm gì.')).toBeInTheDocument()
    expect(seen.some((request) => request.method === 'POST' && request.url.endsWith('/api/v1/jobs/j1/setup/retry'))).toBe(true)
  })

  it("lists each invitee with their state and a GitHub link on the viewer's own row", async () => {
    fakeBackend(signedIn('STUDENT'), {
      'GET /api/v1/jobs/j1/setup': () =>
        json(
          setup('DONE', ['DONE', 'DONE', 'DONE', 'CURRENT', 'PENDING'], {
            people: [
              { role: 'MENTOR', name: 'Đỗ Mạnh Hùng', state: 'ACCEPTED' },
              {
                role: 'CREW',
                name: 'Lê Thảo Vy',
                state: 'INVITED',
                expiresAt: '2026-10-28T03:00:00Z',
                githubUrl: 'https://github.com/vibefix-dev/job-1-working/invitations',
              },
              { role: 'CREW', name: '@hai-dev', state: 'TWO_FACTOR_MISSING' },
            ],
          }),
        ),
    })
    renderTracker()

    expect(await screen.findByText('Mời người hướng dẫn và nhóm trên GitHub')).toBeInTheDocument()
    expect(screen.getByText('· đã chấp nhận')).toBeInTheDocument()
    expect(screen.getByText('· chờ chấp nhận lời mời · hết hạn 28/10')).toBeInTheDocument()
    expect(screen.getByText('· cần bật xác thực hai lớp')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Mở trên GitHub/ })).toHaveAttribute(
      'href',
      'https://github.com/vibefix-dev/job-1-working/invitations',
    )
    // Spike S4 under 2FA: GitHub greys out the acceptance until it is on, so the own row says it first.
    expect(screen.getByText(/Bật xác thực hai lớp trên GitHub trước \(nếu chưa bật\), rồi chấp nhận lời mời/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /^Bật xác thực hai lớp/ })).toHaveAttribute('href', 'https://github.com/settings/security')
    expect(screen.queryByText('Bạn không cần làm gì.')).not.toBeInTheDocument()
    expect(screen.getByText('Mời người hướng dẫn và nhóm trên GitHub: Đang làm')).toHaveAttribute('aria-live', 'polite')
  })

  it('shows the Mentor that the Customer must act, without a retry', async () => {
    fakeBackend(signedIn('MENTOR'), {
      'GET /api/v1/jobs/j1/setup': () =>
        json(setup('BLOCKED', ['BLOCKED', 'PENDING', 'PENDING'], { blockedReason: 'SOURCE_NOT_PUBLIC' })),
    })
    renderTracker()

    expect(await screen.findByText('Đang chờ khách hàng xử lý ở dự án gốc.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Thử lại' })).toBeNull()
  })
})
