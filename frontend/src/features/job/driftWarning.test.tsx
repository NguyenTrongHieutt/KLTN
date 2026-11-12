import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { fakeBackend, json, signedInUser } from '@/test/fakeBackend'
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
    fakeBackend(signedInUser, {
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
    fakeBackend(signedInUser, {
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

  it.each([
    [
      'GONE',
      'Ứng dụng vibefix không còn đến được dự án gốc lan/so-dat-ban: dự án đã bị xóa hoặc chuyển đi, hoặc danh sách kho của ứng dụng đã đổi (ghi nhận ngày 23/10). Cần sửa lại trên GitHub để ứng dụng chọn đúng một kho là dự án này.',
    ],
    [
      'APP_UNINSTALLED',
      'Ứng dụng vibefix trên dự án gốc đã bị gỡ, bị tạm ngưng hoặc thiếu quyền cần có, nên vibefix không còn đọc được dự án (ghi nhận ngày 23/10). Cần cài lại hoặc bật lại ứng dụng vibefix trên GitHub và chấp nhận đủ quyền.',
    ],
    [
      'WORKFLOW_CHANGED',
      'File workflow vibefix.yml trong dự án gốc đã bị xóa hoặc sửa khác bản vibefix công bố (ghi nhận ngày 23/10). Cần thêm lại đúng file trên GitHub.',
    ],
  ])('says in plain words what the Source App no longer finds: %s (Story 23.5b)', async (kind, text) => {
    fakeBackend(signedInUser, {
      'GET /api/v1/jobs/j1/drift': () =>
        json({ warnings: [warning(kind, { fullName: 'lan/so-dat-ban' })], reconfirmNeeded: false }),
    })
    renderWarning()

    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(screen.getByText(text)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Xác nhận lại dự án gốc' })).toBeNull()
  })

  it('gives the sync advice for new commits only, not for an uninstalled Source App (Story 23.5b)', async () => {
    fakeBackend(signedInUser, {
      'GET /api/v1/jobs/j1/drift': () => json({ warnings: [warning('APP_UNINSTALLED')], reconfirmNeeded: false }),
    })
    renderWarning()

    expect(await screen.findByText(/^Ứng dụng vibefix trên dự án gốc đã bị gỡ, bị tạm ngưng hoặc thiếu quyền/)).toBeInTheDocument()
    expect(screen.queryByText(/bạn sẽ nhận mã dưới dạng tệp ZIP/)).toBeNull()
    expect(screen.queryByRole('button', { name: 'Cách tạm dừng đồng bộ' })).toBeNull()
  })

  it('shows nothing without a warning', async () => {
    fakeBackend(signedInUser, { 'GET /api/v1/jobs/j1/drift': () => json({ warnings: [], reconfirmNeeded: false }) })
    renderWarning()

    await new Promise((resolve) => setTimeout(resolve, 50))
    expect(screen.queryByRole('alert')).toBeNull()
  })
})
