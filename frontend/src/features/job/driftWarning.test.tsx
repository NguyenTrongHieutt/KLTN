import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { fakeBackend, inEveryJob, json, signedInUser } from '@/test/fakeBackend'
import { DriftWarning } from './DriftWarning'
import { JobHeader } from './JobHeader'

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

    expect(await screen.findByRole('alert')).toHaveTextContent('Việc merge cuối có thể thất bại.')
    expect(screen.getByText('Có 3 thay đổi mới trong repo nguồn kể từ khi công việc bắt đầu (ghi nhận ngày 23/10).')).toBeInTheDocument()
    expect(screen.getByText(/bạn vẫn tự tải mã đã hoàn thiện từ repo trung gian trên GitHub/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Xác nhận lại repo nguồn' })).toBeNull()
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

    expect(await screen.findByText('Repo nguồn đã đổi tên thành lan/so-dat-ban-v2 (ghi nhận ngày 23/10).')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Xác nhận lại repo nguồn' }))
    expect(await screen.findByText(/không có quyền admin repo nguồn ở tên mới/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Xác nhận lại repo nguồn' }))
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    await screen.findByText('Repo nguồn đã đổi tên thành lan/so-dat-ban-v2 (ghi nhận ngày 23/10).')
    expect(screen.queryByRole('button', { name: 'Xác nhận lại repo nguồn' })).toBeNull()
  })

  it.each([
    [
      'GONE',
      'App vibefix không còn đến được repo nguồn lan/so-dat-ban: dự án đã bị xóa hoặc chuyển đi, hoặc danh sách repo của ứng dụng đã đổi (ghi nhận ngày 23/10). Cần sửa lại trên GitHub để ứng dụng có chọn dự án này.',
    ],
    [
      'APP_UNINSTALLED',
      'App vibefix trên repo nguồn đã bị gỡ, bị tạm ngưng hoặc thiếu quyền cần có, nên vibefix không còn đọc được dự án (ghi nhận ngày 23/10). Cần cài lại hoặc bật lại app vibefix trên GitHub và chấp nhận đủ quyền.',
    ],
    [
      'WORKFLOW_CHANGED',
      'File workflow vibefix.yml trong repo nguồn đã bị xóa hoặc sửa khác bản vibefix công bố (ghi nhận ngày 23/10). Cần thêm lại đúng file trên GitHub.',
    ],
  ])('says in plain words what the Source App no longer finds: %s (Story 23.5b)', async (kind, text) => {
    fakeBackend(signedInUser, {
      'GET /api/v1/jobs/j1/drift': () =>
        json({ warnings: [warning(kind, { fullName: 'lan/so-dat-ban' })], reconfirmNeeded: false }),
    })
    renderWarning()

    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(screen.getByText(text)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Xác nhận lại repo nguồn' })).toBeNull()
  })

  it('gives the sync advice for new commits only, not for an uninstalled Source App (Story 23.5b)', async () => {
    fakeBackend(signedInUser, {
      'GET /api/v1/jobs/j1/drift': () => json({ warnings: [warning('APP_UNINSTALLED')], reconfirmNeeded: false }),
    })
    renderWarning()

    expect(await screen.findByText(/^App vibefix trên repo nguồn đã bị gỡ, bị tạm ngưng hoặc thiếu quyền/)).toBeInTheDocument()
    expect(screen.queryByText(/bạn vẫn tự tải mã đã hoàn thiện từ repo trung gian trên GitHub/)).toBeNull()
    expect(screen.queryByRole('button', { name: 'Cách tạm dừng đồng bộ' })).toBeNull()
  })

  it('shows nothing without a warning', async () => {
    fakeBackend(signedInUser, { 'GET /api/v1/jobs/j1/drift': () => json({ warnings: [], reconfirmNeeded: false }) })
    renderWarning()

    await new Promise((resolve) => setTimeout(resolve, 50))
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('shows on a Starting Job, since drift is told from the Customer confirmation on (Story 24.6)', async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1/drift': () => json({ warnings: [warning('COMMITS', { commits: 2 })], reconfirmNeeded: false }),
    })
    const router = createMemoryRouter(
      [
        {
          path: '/cong-viec/:jobId',
          element: (
            <JobHeader title="Sửa lỗi đặt bàn" state="STARTING" meta="Sổ đặt bàn Quán Mây">
              {null}
            </JobHeader>
          ),
        },
      ],
      { initialEntries: ['/cong-viec/j1'] },
    )
    render(
      <QueryClientProvider client={createQueryClient()}>
        <RouterProvider router={router} />
      </QueryClientProvider>,
    )

    expect(
      await screen.findByText('Có 2 thay đổi mới trong repo nguồn kể từ khi công việc bắt đầu (ghi nhận ngày 23/10).'),
    ).toBeInTheDocument()
  })
})
