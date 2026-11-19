/** The Job states a started Job is in: from Active on (J7, FR-22), where its handovers live. */
const STARTED_STATES = ['ACTIVE', 'CLOSING', 'CLOSED']

export function hasStarted(state: string): boolean {
  return STARTED_STATES.includes(state)
}
