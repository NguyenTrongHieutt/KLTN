import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { routes } from '@/app/routes'
import { fakeBackend, json, signedInUser, inEveryJob } from '@/test/fakeBackend'

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

const job = {
  id: 'j1',
  productId: 'pr1',
  productName: 'Sổ đặt bàn Quán Mây',
  state: 'AGREEMENT',
  title: 'Sửa sổ đặt bàn',
  jobTypes: ['BUG_FIX'],
  skills: [],
  screenshots: [],
  createdAt: '2026-10-12T03:00:00Z',
  updatedAt: '2026-10-18T03:00:00Z',
  version: 4,
  agreementShared: false,
  agreementConfirmed: false,
  canCancelSelection: false,
}

describe('Leaving a Job that stalls before it starts (FR-40)', () => {
  it('lets the Customer cancel alone after the Agreement deadline', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    const sent: unknown[] = []
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1': () => json(job),
      'GET /api/v1/boards/j1/cancellation': () =>
        json({ jobId: 'j1', audience: 'CUSTOMER', state: 'AGREEMENT', version: 4, canPropose: true, held: false, canCancelAlone: true, proposal: null }),
      'POST /api/v1/boards/j1/cancellation/alone': async (request) => {
        sent.push(await request.json())
        return json({ jobId: 'j1', audience: 'CUSTOMER', state: 'CANCELLED', version: 5, canPropose: false, held: false, canCancelAlone: false, proposal: null })
      },
    })
    renderAt('/cong-viec/j1')

    const section = (await screen.findByRole('heading', { level: 2, name: 'Hủy công việc' })).closest('section')!
    expect(within(section).getByText(/Đã quá hạn chuẩn bị/)).toBeInTheDocument()
    await user.click(within(section).getByRole('button', { name: 'Hủy công việc một mình' }))
    await user.type(within(section).getByLabelText('Lý do'), 'Chờ quá lâu')
    await user.click(within(section).getByRole('button', { name: 'Hủy công việc' }))
    const dialog = await screen.findByRole('dialog', { name: 'Tự hủy công việc này?' })
    expect(within(dialog).queryByText(/hoàn đủ số tiền đó/)).toBeNull()
    await user.click(within(dialog).getByRole('button', { name: 'Hủy công việc' }))
    await waitFor(() => expect(sent).toEqual([{ reason: 'Chờ quá lâu', version: 4 }]))
  })
})
