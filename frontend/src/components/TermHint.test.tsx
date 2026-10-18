import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { TermHint, TermScope } from './TermHint'

describe('TermHint (UX-DR7, UX-DR29)', () => {
  it('is a button that opens the explanation on tap and closes on Escape', async () => {
    const user = userEvent.setup()
    render(
      <TermScope>
        <p>
          Dùng tài khoản <TermHint term="github" />.
        </p>
      </TermScope>,
    )

    const trigger = screen.getByRole('button', { name: 'GitHub' })
    expect(trigger).toHaveAttribute('aria-expanded', 'false')

    await user.click(trigger)
    expect(trigger).toHaveAttribute('aria-expanded', 'true')
    expect(await screen.findByText(/Dịch vụ lưu mã nguồn/)).toBeVisible()

    await user.keyboard('{Escape}')
    expect(trigger).toHaveAttribute('aria-expanded', 'false')
  })

  it('opens when keyboard focus reaches it', async () => {
    const user = userEvent.setup()
    render(
      <TermScope>
        <TermHint term="khoMa" />
      </TermScope>,
    )

    await user.tab()

    expect(screen.getByRole('button', { name: 'kho mã' })).toHaveAttribute('aria-expanded', 'true')
  })

  it('is a hint on the first occurrence in a scope only', () => {
    render(
      <TermScope>
        <p data-testid="sentence">
          <TermHint term="github" /> và <TermHint term="github" />
        </p>
      </TermScope>,
    )

    expect(screen.getAllByRole('button', { name: 'GitHub' })).toHaveLength(1)
    expect(screen.getByTestId('sentence')).toHaveTextContent(/^GitHub và GitHub$/)
  })

  it('starts again in each scope', () => {
    render(
      <>
        <TermScope>
          <TermHint term="github" />
        </TermScope>
        <TermScope>
          <TermHint term="github" />
        </TermScope>
      </>,
    )

    expect(screen.getAllByRole('button', { name: 'GitHub' })).toHaveLength(2)
  })

  it('can start mid-sentence in lower case', () => {
    render(
      <TermScope>
        <TermHint term="mentor" lower />
      </TermScope>,
    )

    expect(screen.getByRole('button', { name: 'người hướng dẫn' })).toBeInTheDocument()
  })

  it('renders a term without a hint as plain text', () => {
    render(
      <TermScope>
        <TermHint term="customer" />
      </TermScope>,
    )

    expect(screen.queryByRole('button')).toBeNull()
    expect(screen.getByText('Khách hàng')).toBeInTheDocument()
  })
})
