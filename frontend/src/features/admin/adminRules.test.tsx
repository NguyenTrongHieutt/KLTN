import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { routes } from '@/app/routes'
import type { Session } from '@/features/identity/api'
import { fakeBackend, json } from '@/test/fakeBackend'

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
  account: { id: '00000000-0000-7000-8000-00000000a001', role: 'ADMINISTRATOR', theme: 'LIGHT' },
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
  failedOperations: { count: 0 },
  stalledEvents: { count: 0 },
  activeAdministrators: 2,
  studentCards: { count: 0 },
  mentorApplications: { count: 0 },
  disputes: { count: 0 },
  lowFeedback: { count: 0 },
}

const rules = (autoAcceptance: number) => ({
  rules: [
    { key: 'AUTO_ACCEPTANCE_DAYS', value: autoAcceptance, min: 1, max: 30 },
    { key: 'MENTOR_ACTIVE_JOB_LIMIT', value: 3, min: 1, max: 10 },
  ],
  schoolDomains: ['hust.edu.vn'],
  history:
    autoAcceptance === 7
      ? []
      : [{ kind: 'RULE', rule: 'AUTO_ACCEPTANCE_DAYS', oldValue: 7, newValue: autoAcceptance, changedAt: '2026-10-21T03:00:00Z' }],
})

describe('ADM-10 rules (FR-61)', () => {
  it('refuses a value outside its bounds, then saves through the step-up', { timeout: 30_000 }, async () => {
    const user = userEvent.setup()
    const sent: unknown[] = []
    fakeBackend(administrator, {
      'GET /api/v1/admin/overview': () => json(overview),
      'GET /api/v1/admin/rules': () => json(rules(7)),
      'PUT /api/v1/admin/rules': async (request) => {
        sent.push(await request.json())
        return json(rules(10))
      },
    })
    renderAt('/quan-tri/quy-tac')

    const days = await screen.findByLabelText('Số ngày trước khi tự động nghiệm thu')
    await user.clear(days)
    await user.type(days, '31')
    expect(screen.getByRole('button', { name: 'Lưu thay đổi' })).toBeDisabled()
    await user.clear(days)
    await user.type(days, '10')
    await user.type(screen.getByLabelText('Tên miền email trường (mỗi dòng một tên miền)'), '{Enter}sis.vnu.edu.vn')
    await user.click(screen.getByRole('button', { name: 'Lưu thay đổi' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByRole('textbox'), '123456')
    await user.click(within(dialog).getByRole('button', { name: 'Lưu thay đổi' }))
    await waitFor(() =>
      expect(sent).toEqual([
        {
          values: { AUTO_ACCEPTANCE_DAYS: 10, MENTOR_ACTIVE_JOB_LIMIT: 3 },
          schoolDomains: ['hust.edu.vn', 'sis.vnu.edu.vn'],
          stepUpCode: '123456',
        },
      ]),
    )
    expect(await screen.findByText(/Số ngày trước khi tự động nghiệm thu: 7 → 10/)).toBeInTheDocument()
  })
})
