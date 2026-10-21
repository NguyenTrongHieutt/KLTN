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
  steps: ['CHECK', 'INTERMEDIARY', 'WORKING'].map((key, index) => ({ key, state: states[index] })),
  canRetry: false,
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
