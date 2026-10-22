import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { Toaster } from '@/components/Toaster'
import { fakeBackend, json, signedIn } from '@/test/fakeBackend'
import { RevocationChecklist } from './RevocationChecklist'

const supabase = {
  accessId: 'a1',
  system: 'Supabase',
  purpose: 'Xem bảng đặt bàn',
  environment: 'NON_PRODUCTION',
  accessLevel: 'READ',
  identifiers: [{ githubLogin: 'hung-dev', identifier: 'hung@supabase.test' }],
  done: false,
}

function renderChecklist() {
  render(
    <QueryClientProvider client={createQueryClient()}>
      <Toaster>
        <RevocationChecklist jobId="j1" />
      </Toaster>
    </QueryClientProvider>,
  )
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('The Revocation Checklist (FR-66, UX-DR51, UX-DR55)', () => {
  it('shows what to remove and records each confirmation after a short dialog', { timeout: 15_000 }, async () => {
    const user = userEvent.setup()
    const sent: unknown[] = []
    fakeBackend(signedIn('CUSTOMER'), {
      'GET /api/v1/boards/j1/revocation-checklist': () => json({ jobId: 'j1', audience: 'CUSTOMER', items: [supabase] }),
      'POST /api/v1/boards/j1/revocation-checklist/a1': async (request) => {
        sent.push(await request.json())
        return json({
          jobId: 'j1',
          audience: 'CUSTOMER',
          items: [{ ...supabase, done: true, outcome: 'REMOVED', confirmedAt: '2026-10-23T03:00:00Z' }],
        })
      },
    })
    renderChecklist()

    expect(await screen.findByText('hung@supabase.test')).toBeInTheDocument()
    expect(screen.getByText(/Bản thử · Chỉ xem/)).toBeInTheDocument()
    expect(screen.getByText(/Công việc không chờ danh sách này/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Đã gỡ quyền' }))
    const dialog = await screen.findByRole('dialog', { name: 'Xác nhận đã thu hồi quyền Supabase?' })
    await user.click(within(dialog).getByRole('button', { name: 'Xác nhận' }))

    expect(await screen.findByText('Đã gỡ quyền 10:00 23/10')).toBeInTheDocument()
    expect(sent).toEqual([{ outcome: 'REMOVED' }])
  })

  it('shows the Mentor each item done or pending, without what to remove', async () => {
    fakeBackend(signedIn('MENTOR'), {
      'GET /api/v1/boards/j1/revocation-checklist': () =>
        json({ jobId: 'j1', audience: 'MENTOR', items: [{ ...supabase, identifiers: [], done: true }] }),
    })
    renderChecklist()

    expect(await screen.findByText('Khách hàng: thu hồi quyền Supabase')).toBeInTheDocument()
    expect(screen.getByText('Đã xong')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Đã gỡ quyền' })).toBeNull()
  })

  it('shows nothing for a Job without External Access', async () => {
    let read = false
    fakeBackend(signedIn('CUSTOMER'), {
      'GET /api/v1/boards/j1/revocation-checklist': () => {
        read = true
        return json({ jobId: 'j1', audience: 'CUSTOMER', items: [] })
      },
    })
    renderChecklist()
    await vi.waitFor(() => expect(read).toBe(true))
    expect(screen.queryByText(/thu hồi quyền/i)).toBeNull()
  })
})
