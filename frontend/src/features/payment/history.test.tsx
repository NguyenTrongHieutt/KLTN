import { render, screen, within } from '@testing-library/react'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { routes } from '@/app/routes'
import { fakeBackend, json, signedIn } from '@/test/fakeBackend'

function renderAt(path: string) {
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  render(
    <QueryClientProvider client={createQueryClient()}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return router
}

afterEach(() => {
  vi.unstubAllGlobals()
})

const release = (overrides: object = {}) => ({
  jobId: 'j1',
  jobTitle: 'Chỉnh giao diện điện thoại',
  number: 1,
  milestoneTitle: 'Giao diện điện thoại',
  releasedAt: '2026-08-25T03:00:00Z',
  baseAmountVnd: 1200000,
  yourPartVnd: 240000,
  yourPercent: 20,
  crewTermsVersionNo: 1,
  crew: [{ name: 'Trần Quang Minh', amountVnd: 960000, percent: 80 }],
  ...overrides,
})

describe('CUS-10: the payment history (FR-44)', () => {
  it('lists fundings and refunds per Job with one released total per Milestone', { timeout: 15_000 }, async () => {
    fakeBackend(signedIn('CUSTOMER'), {
      'GET /api/v1/payments/history': () =>
        json({
          heldVnd: 2000000,
          releasedVnd: 1200000,
          refundedVnd: 500000,
          jobs: [
            {
              jobId: 'j1',
              title: 'Sửa lỗi đặt bàn và thêm nhắc lịch',
              state: 'ACTIVE',
              rows: [
                { at: '2026-10-20T07:55:00Z', kind: 'REFUND', number: 1, amountVnd: 500000, status: 'CONFIRMED', simulated: true, paymentOrderId: 'o2' },
                { at: '2026-10-20T07:30:00Z', kind: 'FUNDING', number: 1, amountVnd: 2000000, status: 'PAID', simulated: false, paymentOrderId: 'o1' },
              ],
              milestones: [{ milestoneId: 'm1', number: 1, title: 'Sửa lỗi trùng giờ', state: 'ACCEPTED', releasedTotalVnd: 1200000 }],
            },
          ],
        }),
    })
    renderAt('/lich-su-thanh-toan')

    expect(await screen.findByRole('heading', { level: 1, name: 'Lịch sử thanh toán' })).toBeInTheDocument()
    expect(screen.getByText('Giao dịch thử nghiệm — không dùng tiền thật')).toBeInTheDocument()
    expect((await screen.findByText('vibefix đang giữ')).parentElement).toHaveTextContent('2.000.000 ₫')
    expect(screen.getByText('Đã hoàn lại').parentElement).toHaveTextContent('500.000 ₫')
    const table = screen.getByRole('table')
    expect(within(table).getByText('Hoàn tiền mô phỏng · Giai đoạn 1')).toBeInTheDocument()
    expect(within(table).getByText('Đã thanh toán')).toBeInTheDocument()
    expect(screen.getByText('Giai đoạn 1 · Sửa lỗi trùng giờ: đã chuyển 1.200.000 ₫')).toBeInTheDocument()
    expect(screen.getAllByText('tiền thử nghiệm, không phải tiền thật').length).toBeGreaterThan(3)
  })
})

describe('MEN-7 and STU-7: the test Balance (FR-44, AD-20)', () => {
  it('shows the Mentor the Crew parts of each release', { timeout: 15_000 }, async () => {
    fakeBackend(signedIn('MENTOR'), {
      'GET /api/v1/balance': () => json({ balanceVnd: 240000, releases: 1, sentToCrewVnd: 960000, heldVnd: 2000000, lines: [release()] }),
    })
    renderAt('/so-du')

    expect(await screen.findByRole('heading', { level: 1, name: 'Số dư thử nghiệm' })).toBeInTheDocument()
    expect(screen.getByText(/Số dư này không rút được/)).toBeInTheDocument()
    expect((await screen.findByText('Đã chuyển cho nhóm thực hiện')).parentElement).toHaveTextContent('960.000 ₫')
    expect(screen.getByRole('columnheader', { name: 'Nhóm thực hiện' })).toBeInTheDocument()
    expect(screen.getByText(/Trần Quang Minh 960\.000 ₫/)).toBeInTheDocument()
    expect(screen.getByText('Phiên bản 1')).toBeInTheDocument()
    expect(screen.getByText(/không thấy phần của từng người/)).toBeInTheDocument()
  })

  it('shows a Student only their own parts', { timeout: 15_000 }, async () => {
    fakeBackend(signedIn('STUDENT'), {
      'GET /api/v1/balance': () => json({ balanceVnd: 960000, releases: 1, lines: [release({ yourPartVnd: 960000, yourPercent: 80, crew: [] })] }),
    })
    renderAt('/so-du')

    expect(await screen.findByText('Từ 1 lần chuyển tiền')).toBeInTheDocument()
    expect(screen.queryByRole('columnheader', { name: 'Nhóm thực hiện' })).toBeNull()
    expect(screen.queryByText('Đã chuyển cho nhóm thực hiện')).toBeNull()
    expect(screen.getByText('80%')).toBeInTheDocument()
  })
})

describe("JOB-7 for the Mentor: each release by party (AD-20)", () => {
  it('lists the releases with their role, version, and percentage', { timeout: 15_000 }, async () => {
    fakeBackend(signedIn('MENTOR'), {
      'GET /api/v1/mentor/jobs/j1': () =>
        json({
          id: 'j1',
          title: 'Sửa lỗi đặt bàn và thêm nhắc lịch',
          productName: 'Sổ đặt bàn Quán Mây',
          state: 'ACTIVE',
          version: 12,
          jobTypes: ['BUG_FIX'],
          selectedAt: '2026-10-18T03:00:00Z',
          codeJob: true,
          commitEmailNoticeAcknowledged: true,
          agreementConfirmed: true,
        }),
      'GET /api/v1/jobs/j1/chat': () => json({ chatId: 'c1', unread: false }),
      'GET /api/v1/mentor/jobs/j1/funding': () =>
        json({
          jobId: 'j1',
          title: 'Sửa lỗi đặt bàn và thêm nhắc lịch',
          state: 'ACTIVE',
          milestones: [
            {
              milestoneId: 'm1',
              number: 1,
              title: 'Sửa lỗi trùng giờ',
              priceVnd: 2000000,
              state: 'ACCEPTED',
              fundedVnd: 2000000,
              topUpVnd: 0,
              refundedVnd: 0,
              releasedTotalVnd: 2000000,
              crewTermsVersionNo: 1,
              releases: [
                { name: 'Đỗ Mạnh Hùng', role: 'MENTOR', amountVnd: 400000, percent: 20, crewTermsVersionNo: 1, releasedAt: '2026-10-25T03:00:00Z' },
                { name: 'Trần Quang Minh', role: 'CREW', amountVnd: 1600000, percent: 80, crewTermsVersionNo: 1, releasedAt: '2026-10-25T03:00:00Z' },
              ],
            },
          ],
          orders: [{ number: 1, amountVnd: 2000000, status: 'PAID', createdAt: '2026-10-20T07:30:00Z' }],
        }),
    })
    renderAt('/cong-viec/j1/thanh-toan')

    expect(await screen.findByText('Trần Quang Minh')).toBeInTheDocument()
    expect(screen.getByText('Nhóm thực hiện')).toBeInTheDocument()
    expect(screen.getByText('Điều khoản phiên bản 1')).toBeInTheDocument()
    expect(screen.getByText('80%')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Thanh toán' })).toHaveAttribute('aria-current', 'page')
  })
})
