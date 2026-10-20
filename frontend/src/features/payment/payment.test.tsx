import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { routes } from '@/app/routes'
import { redirectTo } from '@/lib/redirect'
import { fakeBackend, json, signedIn } from '@/test/fakeBackend'

vi.mock('@/lib/redirect', () => ({ redirectTo: vi.fn() }))

function renderAt(path: string) {
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  render(
    <QueryClientProvider client={createQueryClient()}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return router
}

const job = {
  id: 'j1',
  productId: 'p1',
  productName: 'Sổ đặt bàn Quán Mây',
  state: 'AGREEMENT',
  title: 'Sửa lỗi đặt bàn và thêm nhắc lịch',
  jobTypes: ['BUG_FIX'],
  skills: [],
  screenshots: [],
  createdAt: '2026-10-12T03:00:00Z',
  updatedAt: '2026-10-18T03:00:00Z',
  version: 11,
  mentor: { displayName: 'Đỗ Mạnh Hùng', githubLogin: 'hungdm' },
  canCancelSelection: false,
  agreementShared: true,
  agreementConfirmed: true,
}

const money = { fundedVnd: 0, topUpVnd: 0, refundedVnd: 0, releasedTotalVnd: 0 }

const funding = (overrides: object = {}, first: object = {}) => ({
  jobId: 'j1',
  title: job.title,
  state: 'AGREEMENT',
  version: 11,
  configured: true,
  milestones: [
    { milestoneId: 'm1', number: 1, title: 'Sửa lỗi trùng giờ', priceVnd: 2000000, state: 'PLANNED', fundable: true, ...money, ...first },
    { milestoneId: 'm2', number: 2, title: 'Email nhắc lịch', priceVnd: 1500000, state: 'PLANNED', fundable: false, ...money },
  ],
  orders: [],
  ...overrides,
})

const order = (status: string) => ({
  id: 'o1',
  jobId: 'j1',
  jobTitle: job.title,
  number: 1,
  milestoneTitle: 'Sửa lỗi trùng giờ',
  amountVnd: 2000000,
  status,
  createdAt: '2026-10-20T07:30:00Z',
  expiresAt: '2026-10-20T07:45:00Z',
  reference: '0192A0B1C2D3E4F5A6B7C8D9E0F1A2B3',
})

const backend = (extra: Record<string, (request: Request) => Response | Promise<Response>>) =>
  fakeBackend(signedIn('CUSTOMER'), {
    'GET /api/v1/jobs/j1': () => json(job),
    'GET /api/v1/jobs/j1/chat': () => json({ chatId: 'c1', unread: false }),
    'GET /api/v1/payment/test-card': () =>
      json({ configured: true, bank: 'NCB', number: '9704 0000 0000 0018', holder: 'NGUYEN VAN A', issued: '07/15', otp: '123456' }),
    ...extra,
  })

afterEach(() => {
  vi.unstubAllGlobals()
  vi.mocked(redirectTo).mockReset()
})

describe('JOB-7 for the Customer: fund a Milestone (FR-41, N-13)', () => {
  it('shows the test mode, then creates the order and goes to VNPay', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    let body: unknown
    backend({
      'GET /api/v1/jobs/j1/funding': () => json(funding()),
      'POST /api/v1/jobs/j1/milestones/m1/payment-orders': async (request) => {
        body = await request.json()
        return json({ id: 'o1', payUrl: 'https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?vnp_TxnRef=X', expiresAt: '2026-10-20T07:45:00Z' }, 201)
      },
    })
    renderAt('/cong-viec/j1/thanh-toan')

    expect(await screen.findByText('Giao dịch thử nghiệm — không dùng tiền thật')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Cách thanh toán thử' }))
    expect(await screen.findByText('Ngân hàng: NCB')).toBeInTheDocument()
    expect(screen.getByText('Mã OTP: 123456')).toBeInTheDocument()

    const table = screen.getByRole('table')
    expect(within(table).getByText('Chưa thanh toán')).toBeInTheDocument()
    expect(within(table).getByText('Chưa bắt đầu')).toBeInTheDocument()
    expect(screen.getByText('Chưa có lệnh thanh toán nào.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Thanh toán' })).toHaveAttribute('aria-current', 'page')

    await user.click(screen.getByRole('button', { name: 'Thanh toán Giai đoạn 1' }))

    await vi.waitFor(() => expect(redirectTo).toHaveBeenCalledWith('https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?vnp_TxnRef=X'))
    expect(body).toEqual({ version: 11 })
  })

  it('says why Milestone 1 waits, and lists the orders', { timeout: 15_000 }, async () => {
    backend({
      'GET /api/v1/jobs/j1/funding': () =>
        json(
          funding(
            {
              orders: [
                { id: 'o1', milestoneId: 'm1', number: 1, amountVnd: 2000000, status: 'EXPIRED', createdAt: '2026-10-20T07:30:00Z', expiresAt: '2026-10-20T07:45:00Z', reference: 'ABCDEF0123456789' },
                {
                  id: 'o2',
                  milestoneId: 'm1',
                  number: 1,
                  amountVnd: 500000,
                  status: 'UNAPPLIED',
                  createdAt: '2026-10-20T07:50:00Z',
                  expiresAt: '2026-10-20T08:05:00Z',
                  reference: 'BBCDEF0123456789',
                  refund: { amountVnd: 500000, status: 'REQUESTED', simulated: true, reason: 'WRONG_AMOUNT', requestedAt: '2026-10-20T07:55:00Z' },
                },
              ],
            },
            { fundable: false, blockedReason: 'CREW_NOT_READY' },
          ),
        ),
    })
    renderAt('/cong-viec/j1/thanh-toan')

    expect(await screen.findByText('Đang chờ người hướng dẫn hoàn tất nhóm thực hiện')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Thanh toán Giai đoạn 1' })).toBeDisabled()
    expect(screen.getByText('Hết hạn')).toBeInTheDocument()
    expect(screen.getByText('Chờ hoàn tiền')).toBeInTheDocument()
    expect(screen.getByText('Đang hoàn 500.000 ₫')).toBeInTheDocument()
    expect(screen.getAllByRole('link', { name: 'Xem kết quả' })[0]).toHaveAttribute('href', '/thanh-toan/o1')
  })
})

describe('CUS-9: the payment result, read from the server only (AD-14)', () => {
  it('waits for VNPay and shows the paid order once the IPN recorded it', { timeout: 20_000 }, async () => {
    let calls = 0
    backend({
      'GET /api/v1/payment-orders/o1': () => {
        calls += 1
        return json(order(calls === 1 ? 'OPEN' : 'PAID'))
      },
    })
    renderAt('/thanh-toan/o1')

    expect(await screen.findByRole('heading', { level: 1, name: 'Đang chờ VNPay xác nhận' })).toBeInTheDocument()
    expect(screen.getByText('Bạn có thể rời trang; vibefix sẽ báo khi có kết quả.')).toBeInTheDocument()
    expect(await screen.findByRole('heading', { level: 1, name: 'Đã thanh toán' }, { timeout: 6000 })).toBeInTheDocument()
    expect(screen.getByText('Thanh toán Giai đoạn 1')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Về trang thanh toán của công việc' })).toHaveAttribute('href', '/cong-viec/j1/thanh-toan')
  })

  it('shows why unapplied money is refunded, as a simulated refund', { timeout: 15_000 }, async () => {
    backend({
      'GET /api/v1/payment-orders/o1': () =>
        json({
          ...order('UNAPPLIED'),
          refund: { amountVnd: 2000000, status: 'CONFIRMED', simulated: true, reason: 'CREW_CHANGED', requestedAt: '2026-10-20T07:40:00Z', confirmedAt: '2026-10-20T07:41:00Z' },
        }),
    })
    renderAt('/thanh-toan/o1')

    expect(await screen.findByRole('heading', { level: 1, name: 'Đã nhận tiền nhưng chưa áp dụng được' })).toBeInTheDocument()
    expect(screen.getByText('Hoàn tiền mô phỏng ·')).toBeInTheDocument()
    expect(screen.getByText('Đã hoàn 2.000.000 ₫')).toBeInTheDocument()
    expect(screen.getByText(/Nhóm thực hiện thay đổi trước khi bắt đầu/)).toBeInTheDocument()
    expect(screen.getByText(/chỉ được ghi trên vibefix/)).toBeInTheDocument()
  })

  it('says a failed payment moved no money', { timeout: 15_000 }, async () => {
    backend({ 'GET /api/v1/payment-orders/o1': () => json(order('FAILED')) })
    renderAt('/thanh-toan/o1')

    expect(await screen.findByRole('heading', { level: 1, name: 'Thanh toán chưa thành công' })).toBeInTheDocument()
    expect(screen.getByText(/Không có tiền nào bị trừ/)).toBeInTheDocument()
  })
})
