import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { TermHint, TermScope } from './TermHint'

describe('TermHint (EXPERIENCE.md, UX changes of 2026-11-23)', () => {
  it('renders the term as plain text, with no hint control', () => {
    render(
      <TermScope>
        <p data-testid="sentence">
          Dùng tài khoản <TermHint term="github" /> và <TermHint term="khoMa" />.
        </p>
      </TermScope>,
    )

    expect(screen.queryByRole('button')).toBeNull()
    expect(screen.getByTestId('sentence')).toHaveTextContent(/^Dùng tài khoản GitHub và repo\.$/)
  })

  it('can start mid-sentence in lower case', () => {
    render(<TermHint term="mentor" lower />)

    expect(screen.getByText('người hướng dẫn')).toBeInTheDocument()
  })
})
