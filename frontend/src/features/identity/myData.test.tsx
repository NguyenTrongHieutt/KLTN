import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { routes } from '@/app/routes'
import type { Session } from '@/features/identity/api'
import { capabilities, fakeBackend, json } from '@/test/fakeBackend'

const customer: Session = {
  status: 'SIGNED_IN',
  privacyNoticeVersion: '2.0',
  account: {
    id: '00000000-0000-7000-8000-00000000c001',
    kind: 'USER',
    capabilities: capabilities(),
    theme: 'LIGHT',
  },
}

afterEach(() => {
  vi.unstubAllGlobals()
})

function renderAt(path: string) {
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  render(
    <QueryClientProvider client={createQueryClient()}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return router
}

describe('ALL-2 my data (NFR-11)', () => {
  it('downloads the data and requests the deletion, which waits for a running Job', { timeout: 30_000 }, async () => {
    const user = userEvent.setup()
    let requested = false
    fakeBackend(customer, {
      'GET /api/v1/account/data': () =>
        json({ deletionRequestedAt: null, deletionCompletedAt: null, blockers: [] }),
      'POST /api/v1/account/deletion-request': () => {
        requested = true
        return json(
          {
            deletionRequestedAt: '2026-10-20T03:00:00Z',
            deletionCompletedAt: null,
            blockers: [
              { module: 'job', reason: 'JOB_RUNNING', jobId: 'j1', name: 'Sửa lỗi đặt bàn' },
              { module: 'dispute', reason: 'DISPUTE_OPEN', jobId: 'j1', name: 'Sửa lỗi đặt bàn' },
              { module: 'job', reason: 'MILESTONE_PENDING', jobId: 'j2', name: 'Trang đặt món' },
              { module: 'job', reason: 'SPLIT_PENDING', jobId: 'j3', name: 'Báo cáo đặt bàn' },
            ],
          },
          201,
        )
      },
    })
    renderAt('/tai-khoan/du-lieu')

    expect(await screen.findByRole('link', { name: 'Tải dữ liệu của tôi' })).toHaveAttribute('href', '/api/v1/account/data/export')
    await user.click(await screen.findByRole('button', { name: 'Yêu cầu xóa tài khoản' }))
    const dialog = await screen.findByRole('dialog', { name: 'Yêu cầu xóa tài khoản?' })
    await user.click(within(dialog).getByRole('button', { name: 'Gửi yêu cầu' }))
    await waitFor(() => expect(requested).toBe(true))
    expect(await screen.findByText(/Yêu cầu xóa tài khoản đã gửi ngày .*\. Quản trị viên sẽ xử lý\./)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Công việc đang chạy · Sửa lỗi đặt bàn' })).toHaveAttribute('href', '/cong-viec/j1')
    expect(screen.getByRole('link', { name: 'Tranh chấp đang mở · Sửa lỗi đặt bàn' })).toHaveAttribute('href', '/cong-viec/j1')
    expect(
      screen.getByRole('link', { name: 'Cột mốc bạn đã tham gia chưa thanh toán xong · Trang đặt món' }),
    ).toHaveAttribute('href', '/cong-viec/j2')
    expect(
      screen.getByRole('link', { name: 'Tiền của một cột mốc bạn tham gia chưa chia xong · Báo cáo đặt bàn' }),
    ).toHaveAttribute('href', '/cong-viec/j3')
    // R31: no GitHub token is kept from now on, and whoever linked earlier still learns where to remove vibefix.
    expect(screen.getByText(/Từ nay vibefix không giữ quyền GitHub nào/)).toHaveTextContent(
      'Nếu bạn đã liên kết GitHub trước đây, sau khi tài khoản bị xóa bạn có thể gỡ vibefix trong GitHub, ở mục Settings › Applications.',
    )
  })
})
