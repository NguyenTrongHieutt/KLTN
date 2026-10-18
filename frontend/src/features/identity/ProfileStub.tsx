import { MentorProfileStub } from '@/features/mentor/MentorProfileStub'
import { NotFoundScreen } from '@/features/shared/NotFoundScreen'
import { StudentProfileStub } from '@/features/student/StudentProfileStub'
import { useSession } from './useSession'

/**
 * `/ho-so`, one route for each role's profile: MEN-5 for a Mentor, STU-5 for a Student. A
 * Customer's CUS-11 arrives with Story 1.8; until then the route has nothing for them.
 */
export function ProfileStub() {
  const role = useSession().data?.account?.role
  if (role === 'MENTOR') {
    return <MentorProfileStub />
  }
  if (role === 'STUDENT') {
    return <StudentProfileStub />
  }
  return <NotFoundScreen />
}
