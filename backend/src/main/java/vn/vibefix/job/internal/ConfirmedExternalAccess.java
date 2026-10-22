package vn.vibefix.job.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.identity.MentorDirectory;
import vn.vibefix.identity.StudentDirectory.StudentCard;
import vn.vibefix.job.ExternalAccessItems;
import vn.vibefix.job.internal.Agreements.ExternalAccess;
import vn.vibefix.job.internal.Jobs.Job;

/**
 * {@link ExternalAccessItems} on the confirmed Agreement and {@code job.external_access_identifier}:
 * the identifiers of the Mentor and of every Crew member who ever held a row, released ones included,
 * since their access is what the Customer removes.
 */
@Component
class ConfirmedExternalAccess implements ExternalAccessItems {

	private final JdbcClient jdbc;

	private final AgreementReviews reviews;

	private final ExternalAccessGrants grants;

	private final MentorDirectory mentors;

	private final CrewService crews;

	ConfirmedExternalAccess(JdbcClient jdbc, AgreementReviews reviews, ExternalAccessGrants grants, MentorDirectory mentors,
			CrewService crews) {
		this.jdbc = jdbc;
		this.reviews = reviews;
		this.grants = grants;
		this.mentors = mentors;
		this.crews = crews;
	}

	@Override
	@Transactional(readOnly = true)
	public List<Item> items(UUID jobId) {
		Job job = this.grants.job(jobId).orElse(null);
		AgreementReviews.Confirmed confirmed = job == null ? null : this.reviews.confirmed(jobId).orElse(null);
		if (job == null || confirmed == null) {
			return List.of();
		}
		Map<UUID, Map<UUID, String>> entered = new HashMap<>();
		this.jdbc.sql("SELECT access_id, account_id, identifier FROM job.external_access_identifier WHERE job_id = :job")
			.param("job", jobId)
			.query((rs, n) -> entered.computeIfAbsent(rs.getObject("access_id", UUID.class), id -> new HashMap<>())
				.put(rs.getObject("account_id", UUID.class), rs.getString("identifier")))
			.list();
		Map<UUID, String> people = people(job);
		List<Item> items = new ArrayList<>();
		for (ExternalAccess access : confirmed.content().externalAccess()) {
			Map<UUID, String> byPerson = entered.getOrDefault(access.id(), Map.of());
			List<Identifier> identifiers = new ArrayList<>();
			people.forEach((person, login) -> {
				String identifier = byPerson.get(person);
				if (identifier != null) {
					identifiers.add(new Identifier(login, identifier));
				}
			});
			items.add(new Item(access.id(), access.system(), access.purpose(), access.environment().name(),
					access.accessLevel().name(), identifiers));
		}
		return items;
	}

	/** The Mentor and every Crew member with a row, by GitHub username. */
	private Map<UUID, String> people(Job job) {
		Map<UUID, String> people = new LinkedHashMap<>();
		if (job.mentorId() != null) {
			this.mentors.cards(List.of(job.mentorId()))
				.stream()
				.filter(card -> card.githubLogin() != null)
				.forEach(card -> people.put(card.accountId(), card.githubLogin()));
		}
		List<UUID> crew = this.crews.rows(job.id()).stream().map(CrewService.Row::studentId).distinct().toList();
		Map<UUID, StudentCard> cards = this.crews.cards(crew);
		for (UUID student : crew) {
			StudentCard card = cards.get(student);
			if (card != null && card.githubLogin() != null) {
				people.put(student, card.githubLogin());
			}
		}
		return people;
	}

}
