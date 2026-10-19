import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
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

const mentorJob = (overrides: object = {}) => ({
  id: 'j1',
  title: 'Sửa lỗi mất đơn hàng',
  productName: 'Shop Gốm Bát Tràng',
  state: 'SELECTING',
  version: 2,
  jobTypes: ['BUG_FIX'],
  selectedAt: '2026-10-18T03:00:00Z',
  ndaDueAt: '2026-10-21T03:00:00Z',
  codeJob: true,
  commitEmailNoticeAcknowledged: false,
  ...overrides,
})

const selectingJob = (overrides: object = {}) => ({
  id: 'j1',
  productId: 'p1',
  productName: 'Shop Gốm Bát Tràng',
  state: 'SELECTING',
  title: 'Sửa lỗi mất đơn hàng',
  jobTypes: ['BUG_FIX'],
  skills: [],
  screenshots: [],
  createdAt: '2026-10-15T03:00:00Z',
  updatedAt: '2026-10-18T03:00:00Z',
  version: 2,
  mentor: { displayName: 'Đỗ Mạnh Hùng', githubLogin: 'hungdm' },
  selectedAt: '2026-10-18T03:00:00Z',
  ndaDueAt: '2026-10-21T03:00:00Z',
  canCancelSelection: false,
  ...overrides,
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('JOB-1 for the selected Mentor, the NDA panel (FR-15, FR-18)', () => {
  it('shows N-14, the NDA version, and N-23, and signs once "Tôi đã hiểu" is ticked', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    let sent: unknown
    let signed = false
    fakeBackend(signedIn('MENTOR'), {
      'GET /api/v1/mentor/jobs/j1': () => json(signed ? mentorJob({ state: 'AGREEMENT', ndaDueAt: undefined }) : mentorJob()),
      'POST /api/v1/mentor/jobs/j1/nda': async (request) => {
        sent = await request.json()
        signed = true
        return new Response(null, { status: 204 })
      },
    })
    renderAt('/cong-viec/j1')

    expect(await screen.findByRole('heading', { level: 2, name: 'Cam kết bảo mật (NDA) · phiên bản 1.0' })).toBeInTheDocument()
    expect(screen.getByText('Chờ bạn ký cam kết bảo mật')).toBeInTheDocument()
    expect(screen.getByText(/Mã nguồn là công khai nên không thuộc phạm vi này/)).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Toàn văn cam kết bảo mật' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Dùng email ẩn của GitHub/ })).toHaveAttribute('target', '_blank')
    const sign = screen.getByRole('button', { name: 'Ký cam kết' })
    expect(sign).toBeDisabled()
    await user.click(screen.getByRole('checkbox', { name: 'Tôi đã hiểu' }))
    await user.click(sign)

    await waitFor(() => expect(sent).toEqual({ ndaVersion: '1.0', commitEmailNoticeAcknowledged: true }))
    expect(await screen.findByRole('heading', { level: 2, name: 'Bạn đã ký cam kết bảo mật' })).toBeInTheDocument()
  })

  it('skips N-23 once acknowledged, and declines after a confirmation', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    let declined: unknown
    fakeBackend(signedIn('MENTOR'), {
      'GET /api/v1/mentor/jobs/j1': () => json(mentorJob({ commitEmailNoticeAcknowledged: true })),
      'GET /api/v1/mentor/jobs': () => json([]),
      'GET /api/v1/mentor/applications': () => json([]),
      'POST /api/v1/mentor/jobs/j1/decline': async (request) => {
        declined = await request.json()
        return new Response(null, { status: 204 })
      },
    })
    const router = renderAt('/cong-viec/j1')

    expect(await screen.findByRole('button', { name: 'Ký cam kết' })).toBeEnabled()
    expect(screen.queryByRole('checkbox', { name: 'Tôi đã hiểu' })).toBeNull()
    await user.click(screen.getByRole('button', { name: 'Từ chối' }))
    const dialog = await screen.findByRole('dialog', { name: 'Từ chối công việc này?' })
    await user.click(within(dialog).getByRole('button', { name: 'Từ chối' }))

    await waitFor(() => expect(declined).toEqual({ version: 2 }))
    await waitFor(() => expect(router.state.location.pathname).toBe('/cong-viec'))
  })

  it('lists the Mentor\'s Jobs on MEN-4', { timeout: 15_000 }, async () => {
    fakeBackend(signedIn('MENTOR'), {
      'GET /api/v1/mentor/jobs': () =>
        json([{ id: 'j1', title: 'Sửa lỗi mất đơn hàng', productName: 'Shop Gốm Bát Tràng', state: 'SELECTING', selectedAt: '2026-10-18T03:00:00Z' }]),
      'GET /api/v1/mentor/applications': () => json([]),
    })
    renderAt('/cong-viec')

    expect(await screen.findByRole('link', { name: /Sửa lỗi mất đơn hàng/ })).toHaveAttribute('href', '/cong-viec/j1')
    expect(screen.getByText('Chờ bạn ký cam kết bảo mật')).toBeInTheDocument()
  })
})

describe('JOB-1 for the Customer while Selecting (FR-15)', () => {
  it('shows the held Applications and cancels the selection once the period passed', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    let sent: unknown
    fakeBackend(signedIn('CUSTOMER'), {
      'GET /api/v1/jobs/j1': () => json(selectingJob({ canCancelSelection: true })),
      'GET /api/v1/jobs/j1/applications': () =>
        json([
          {
            id: 'a2',
            status: 'ON_HOLD',
            priceVnd: 2_800_000,
            timelineDays: 10,
            approach: 'x',
            submittedAt: '2026-10-16T03:00:00Z',
            mentor: { displayName: 'Phạm Thu Hà', yearsOfExperience: 6, teamSize: 1 },
          },
        ]),
      'POST /api/v1/jobs/j1/selection/cancel': async (request) => {
        sent = await request.json()
        return json(selectingJob({ state: 'OPEN' }))
      },
    })
    renderAt('/cong-viec/j1')

    expect(await screen.findByRole('heading', { level: 2, name: 'Đang chờ Đỗ Mạnh Hùng ký cam kết bảo mật' })).toBeInTheDocument()
    expect(await screen.findByText('Phạm Thu Hà')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Hủy lựa chọn' }))
    const dialog = await screen.findByRole('dialog', { name: 'Hủy lựa chọn này?' })
    await user.click(within(dialog).getByRole('button', { name: 'Hủy lựa chọn' }))

    await waitFor(() => expect(sent).toEqual({ version: 2 }))
  })

  it('keeps "Hủy lựa chọn" away while the Mentor still has time', { timeout: 15_000 }, async () => {
    fakeBackend(signedIn('CUSTOMER'), {
      'GET /api/v1/jobs/j1': () => json(selectingJob()),
      'GET /api/v1/jobs/j1/applications': () => json([]),
    })
    renderAt('/cong-viec/j1')

    expect(await screen.findByRole('heading', { level: 2, name: 'Đang chờ Đỗ Mạnh Hùng ký cam kết bảo mật' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Hủy lựa chọn' })).toBeNull()
  })
})
