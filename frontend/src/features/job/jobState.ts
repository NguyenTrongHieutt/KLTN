/** The Job states with a Board: from Active on (J7 seeds it, FR-22). */
const BOARD_STATES = ['ACTIVE', 'CLOSING', 'CLOSED']

export function hasBoard(state: string): boolean {
  return BOARD_STATES.includes(state)
}
