import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { routes } from '@/app/routes'
import type { Session } from '@/features/identity/api'
import { administratorAccount, fakeBackend, json } from '@/test/fakeBackend'

function renderAt(path: string) {
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  render(
    <QueryClientProvider client={createQueryClient()}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return router
}

const minutes = (n: number) => new Date(Date.now() + n * 60_000).toISOString()

const administrator: Session = {
  status: 'SIGNED_IN',
  privacyNoticeVersion: '2.0',
  account: administratorAccount,
  administrator: {
    email: 'phuong@vibefix.vn',
    displayName: 'Hà Phương',
    idleExpiresAt: minutes(30),
    absoluteExpiresAt: minutes(480),
  },
}

afterEach(() => {
  vi.unstubAllGlobals()
})

const overview = {
  failedOperations: { count: 1 },
  stalledEvents: { count: 0 },
  activeAdministrators: 2,
  disputes: { count: 0 },
  lowFeedback: { count: 0 },
  failedExecutions: { count: 0 },
  falsePositives: { count: 1 },
}

const operation = {
  id: 'op1',
  type: 'github.invite_member',
  errorCode: 'GITHUB_UNAVAILABLE',
  attempts: 5,
  failedAt: '2026-10-21T03:00:00Z',
  jobId: 'j1',
}

const falsePositive = {
  findingId: 'f1',
  productId: 'p1',
  productName: 'Sổ đặt bàn',
  ruleType: 'Khóa API',
  filePath: 'src/config.ts',
  reason: 'Khóa thử nghiệm',
  markedByName: 'Nguyễn Thị Lan',
  markedAt: '2026-10-20T03:00:00Z',
}

describe('ADM-7 failures (FR-64)', () => {
  it('retries a failed operation through the step-up and reopens a false positive', { timeout: 30_000 }, async () => {
    const user = userEvent.setup()
    const sent: unknown[] = []
    let reopened = false
    let retried = false
    const view = () => ({
      operations: retried ? [] : [operation],
      executions: [],
      stalledEvents: [],
      falsePositives: reopened ? [] : [falsePositive],
    })
    fakeBackend(administrator, {
      'GET /api/v1/admin/overview': () => json(overview),
      'GET /api/v1/admin/failures': () => json(view()),
      'POST /api/v1/admin/failures/operations/op1/retry': async (request) => {
        sent.push(await request.json())
        retried = true
        return json(view())
      },
      'POST /api/v1/admin/failures/findings/f1/reopen': () => {
        reopened = true
        return json(view())
      },
    })
    renderAt('/quan-tri/thao-tac-loi')

    expect(await screen.findByText('github.invite_member')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Thử lại' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByRole('textbox'), '123456')
    await user.click(within(dialog).getByRole('button', { name: 'Thử lại' }))
    await waitFor(() => expect(sent).toEqual([{ stepUpCode: '123456' }]))
    await waitFor(() => expect(screen.queryByText('github.invite_member')).toBeNull())

    expect(screen.getByText('Lý do: Khóa thử nghiệm')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Mở lại' }))
    await waitFor(() => expect(screen.queryByText('Lý do: Khóa thử nghiệm')).toBeNull())
  })
})
