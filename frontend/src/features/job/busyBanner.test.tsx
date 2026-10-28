import { render, screen } from '@testing-library/react'
import { QueryClientProvider } from '@tanstack/react-query'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { Toaster } from '@/components/Toaster'
import { fakeBackend, json, signedInUser } from '@/test/fakeBackend'
import { BusyBanner } from './BusyBanner'

const merging = { busy: true, purpose: 'ACCEPTANCE', since: '2026-10-22T10:00:00Z', milestone: 1, handoverId: 'h1' }
const idle = { busy: false }

function renderBanner() {
  render(
    <QueryClientProvider client={createQueryClient()}>
      <Toaster>
        <BusyBanner jobId="j1" />
      </Toaster>
    </QueryClientProvider>,
  )
}

/** The marker as the poll reads it: busy first, then cleared. */
function clearsAfterOnePoll(handover: Record<string, unknown>) {
  let reads = 0
  fakeBackend(signedInUser, {
    'GET /api/v1/jobs/j1/busy': () => json(reads++ === 0 ? merging : idle),
    'GET /api/v1/handovers/h1': () => json({ id: 'h1', number: 1, ...handover }),
  })
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('The busy banner on JOB-H (UX-DR21, AD-28)', () => {
  it('says the acceptance merges, politely, and toasts the acceptance once it clears', { timeout: 15_000 }, async () => {
    clearsAfterOnePoll({ status: 'ACCEPTED', acceptance: null })
    renderBanner()

    const banner = await screen.findByRole('status')
    expect(banner).toHaveTextContent('Đang hợp nhất bàn giao Giai đoạn 1…')
    expect(banner).toHaveAttribute('aria-live', 'polite')
    expect(await screen.findByText('Đã nghiệm thu Giai đoạn 1.', {}, { timeout: 5_000 })).toBeInTheDocument()
    expect(screen.queryByText('Đang hợp nhất bàn giao Giai đoạn 1…')).toBeNull()
  })

  it('tells the Customer plainly when the merge did not go through', { timeout: 15_000 }, async () => {
    clearsAfterOnePoll({ status: 'SUBMITTED', acceptance: 'FAILED' })
    renderBanner()

    await screen.findByText('Đang hợp nhất bàn giao Giai đoạn 1…')
    // An error toast also has a copy for screen readers.
    const failed = await screen.findAllByText('Chưa hợp nhất được. Quản trị viên đã được báo; bạn chưa cần làm gì.', {}, { timeout: 5_000 })
    expect(failed.length).toBeGreaterThan(0)
  })

  it('shows nothing while the Job is not busy', async () => {
    let read = false
    fakeBackend(signedInUser, {
      'GET /api/v1/jobs/j1/busy': () => {
        read = true
        return json(idle)
      },
    })
    renderBanner()

    await vi.waitFor(() => expect(read).toBe(true))
    expect(screen.queryByRole('status')).toBeNull()
  })

  it('reads the Final Merge as the Customer’s own project to them and as the last merge to the team', async () => {
    const finalMerge = { busy: true, purpose: 'FINAL_MERGE', since: '2026-10-22T10:00:00Z' }
    fakeBackend(signedInUser, {
      'GET /api/v1/jobs/j1/busy': () => json({ ...finalMerge, audience: 'CUSTOMER' }),
    })
    renderBanner()
    expect(await screen.findByRole('status')).toHaveTextContent('Đang đưa mã vào dự án của bạn…')

    fakeBackend(signedInUser, {
      'GET /api/v1/jobs/j1/busy': () => json({ ...finalMerge, audience: 'MENTOR' }),
    })
    renderBanner()
    expect(await screen.findByText('Đang hợp nhất cuối…')).toBeInTheDocument()
  })
})
