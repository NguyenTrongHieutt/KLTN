import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import type { Session } from '@/features/identity/api'
import { fakeBackend, json } from '@/test/fakeBackend'
import { ReportAbuseMenu } from './ReportAbuseMenu'

const student: Session = {
  status: 'SIGNED_IN',
  privacyNoticeVersion: '2.0',
  account: { id: '00000000-0000-7000-8000-00000000b001', role: 'STUDENT', theme: 'LIGHT' },
}

afterEach(() => {
  vi.unstubAllGlobals()
})

function renderMenu() {
  render(
    <QueryClientProvider client={createQueryClient()}>
      <ReportAbuseMenu targets={[{ kind: 'CREW_CHAT_MESSAGE', targetId: 'm1' }]} label="Thao tác khác với tin nhắn của Hùng" />
    </QueryClientProvider>,
  )
}

describe('Report abuse (FR-69, UX-DR64)', () => {
  it('requires a reason and confirms that Administrators will review it', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    const sent: unknown[] = []
    fakeBackend(student, {
      'POST /api/v1/abuse-reports': async (request) => {
        sent.push(await request.json())
        return json({ id: 'r1' }, 201)
      },
    })
    renderMenu()

    await user.click(screen.getByRole('button', { name: 'Thao tác khác với tin nhắn của Hùng' }))
    await user.click(await screen.findByRole('menuitem', { name: 'Báo cáo vi phạm' }))
    const dialog = await screen.findByRole('dialog', { name: 'Báo cáo vi phạm' })
    await user.click(within(dialog).getByRole('button', { name: 'Gửi báo cáo' }))
    expect(sent).toEqual([])
    await user.type(within(dialog).getByLabelText('Lý do'), 'Lời lẽ xúc phạm')
    await user.click(within(dialog).getByRole('button', { name: 'Gửi báo cáo' }))
    await waitFor(() => expect(sent).toEqual([{ kind: 'CREW_CHAT_MESSAGE', targetId: 'm1', reason: 'Lời lẽ xúc phạm' }]))
    expect(await within(dialog).findByText('Quản trị viên sẽ xem xét và báo kết quả cho bạn.')).toBeInTheDocument()
  })

  it('explains the daily limit', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(student, {
      'POST /api/v1/abuse-reports': () =>
        json({ status: 429, code: 'RATE_LIMITED', reason: 'ABUSE_REPORT_LIMIT', correlationId: 'c1' }, 429),
    })
    renderMenu()

    await user.click(screen.getByRole('button', { name: 'Thao tác khác với tin nhắn của Hùng' }))
    await user.click(await screen.findByRole('menuitem', { name: 'Báo cáo vi phạm' }))
    const dialog = await screen.findByRole('dialog', { name: 'Báo cáo vi phạm' })
    await user.type(within(dialog).getByLabelText('Lý do'), 'Spam')
    await user.click(within(dialog).getByRole('button', { name: 'Gửi báo cáo' }))
    expect(await within(dialog).findByText('Bạn đã gửi 10 báo cáo hôm nay. Hãy thử lại vào ngày mai.')).toBeInTheDocument()
  })
})
