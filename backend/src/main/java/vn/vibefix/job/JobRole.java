package vn.vibefix.job;

import java.util.Objects;

import vn.vibefix.job.Crews.CrewRelationship;
import vn.vibefix.job.Crews.Relationship;

/**
 * A person's role in one Job (AD-43), as {@link JobRoles#roleOf} reads it: the Job's Customer, its Mentor, the
 * Customer who is also the Mentor of a Self-mentored Job (AD-47), a Crew member in their Crew relationship (AD-41),
 * or none. One person holds at most one role in a Job, and a role in one Job grants nothing in another. Open bids
 * and Crew Applications are subject relations, never roles.
 */
public sealed interface JobRole
		permits JobRole.Customer, JobRole.Mentor, JobRole.CustomerMentor, JobRole.Crew, JobRole.None {

	JobRole CUSTOMER = new Customer();

	JobRole MENTOR = new Mentor();

	JobRole CUSTOMER_MENTOR = new CustomerMentor();

	JobRole NONE = new None();

	/** A Crew member's role, or {@link #NONE} for the relationship {@code NONE}. */
	static JobRole crew(CrewRelationship relationship) {
		return relationship.kind() == Relationship.NONE ? NONE : new Crew(relationship);
	}

	/** The Job's Customer. */
	record Customer() implements JobRole {
	}

	/** The Job's Mentor, from J3 on. */
	record Mentor() implements JobRole {
	}

	/**
	 * {@code CUSTOMER_MENTOR}: the Customer of a Self-mentored Job, who is also its Mentor, from J11 or J12 on (FR-85,
	 * AD-47). It is one role, not two: {@link #isCustomer()} and {@link #isMentor()} both answer true, so a policy written
	 * for either admits it, and {@link #isSelfMentor()} tells it apart where the mode decides otherwise.
	 * {@link JobRoles#roleOf} reads it from the Job's mode, never from the Customer and the Mentor being one account.
	 */
	record CustomerMentor() implements JobRole {
	}

	/** A Crew member, in any relationship but {@code NONE}. */
	record Crew(CrewRelationship relationship) implements JobRole {

		public Crew {
			Objects.requireNonNull(relationship, "relationship");
			if (relationship.kind() == Relationship.NONE) {
				throw new IllegalArgumentException("A Crew role has a Crew relationship");
			}
		}

	}

	/** No role in the Job: the Job does not exist, or the account holds no relation to it. */
	record None() implements JobRole {
	}

	/** The Job's Customer, the Customer of a Self-mentored Job included. */
	default boolean isCustomer() {
		return this instanceof Customer || this instanceof CustomerMentor;
	}

	/** The Job's Mentor, the Customer of a Self-mentored Job included. */
	default boolean isMentor() {
		return this instanceof Mentor || this instanceof CustomerMentor;
	}

	/** The Customer of a Self-mentored Job, and nobody else (AD-47). */
	default boolean isSelfMentor() {
		return this instanceof CustomerMentor;
	}

	default boolean isCrew() {
		return this instanceof Crew;
	}

	/** The Crew relationship of a Crew member; {@link CrewRelationship#NONE} for every other role. */
	default CrewRelationship crewRelationship() {
		return this instanceof Crew crew ? crew.relationship() : CrewRelationship.NONE;
	}

	/** The kind of {@link #crewRelationship()}. */
	default Relationship crewKind() {
		return crewRelationship().kind();
	}

}
