import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { fakeBackend, json, signedIn } from '@/test/fakeBackend'
import { DriftWarning } from './DriftWarning'

const warning = (kind: string, extra: Record<string, unknown> = {}) => ({
  kind,
  detectedAt: '2026-10-22T22:00:00Z',
  commits: 0,
  reconfirmed: false,
  ...extra,
})

function renderWarning() {
  render(
    <QueryClientProvider client={createQueryClient()}>
      <DriftWarning jobId="j1" />
    </QueryClientProvider>,
  )
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('The Drift Warning on JOB-H and JOB-1 (FR-20, UX-DR27)', () => {
  it('says what changed, what it means, and how to pause the sync', async () => {
    const user = userEvent.setup()
    fakeBackend(signedIn('MENTOR'), {
      'GET /api/v1/jobs/j1/drift': () => json({ warnings: [warning('COMMITS', { commits: 3 })], reconfirmNeeded: false }),
    })
    renderWarning()

    expect(await screen.findByRole('alert')).toHaveTextContent('Việc hợp nhất cuối có thể thất bại.')
    expect(screen.getByText('Có 3 thay đổi mới trong dự án gốc kể từ khi công việc bắt đầu (ghi nhận ngày 23/10).')).toBeInTheDocument()
    expect(screen.getByText(/bạn sẽ nhận mã dưới dạng tệp ZIP/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Xác nhận lại dự án gốc' })).toBeNull()
    await user.click(screen.getByRole('button', { name: 'Cách tạm dừng đồng bộ' }))
    expect(screen.getByText(/Lovable:/)).toBeInTheDocument()
  })

  it('lets the Customer re-confirm a renamed repository, and shows a refusal plainly', async () => {
    const user = userEvent.setup()
    let refuse = true
    fakeBackend(signedIn('CUSTOMER'), {
      'GET /api/v1/jobs/j1/drift': () =>
        json({ warnings: [warning('RENAMED', { fullName: 'lan/so-dat-ban-v2' })], reconfirmNeeded: true }),
      'POST /api/v1/jobs/j1/source/reconfirm': () => {
        if (refuse) {
          refuse = false
          return json({ status: 422, code: 'GUARD_FAILED', reason: 'NO_ADMIN_PERMISSION' }, 422)
        }
        return json({ warnings: [warning('RENAMED', { fullName: 'lan/so-dat-ban-v2', reconfirmed: true })], reconfirmNeeded: false })
      },
    })
    renderWarning()

    expect(await screen.findByText('Dự án gốc đã đổi tên thành lan/so-dat-ban-v2 (ghi nhận ngày 23/10).')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Xác nhận lại dự án gốc' }))
    expect(await screen.findByText(/không có quyền quản trị dự án gốc ở tên mới/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Xác nhận lại dự án gốc' }))
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    await screen.findByText('Dự án gốc đã đổi tên thành lan/so-dat-ban-v2 (ghi nhận ngày 23/10).')
    expect(screen.queryByRole('button', { name: 'Xác nhận lại dự án gốc' })).toBeNull()
  })

  it('shows nothing without a warning', async () => {
    fakeBackend(signedIn('CUSTOMER'), { 'GET /api/v1/jobs/j1/drift': () => json({ warnings: [], reconfirmNeeded: false }) })
    renderWarning()

    await new Promise((resolve) => setTimeout(resolve, 50))
    expect(screen.queryByRole('alert')).toBeNull()
  })
})
