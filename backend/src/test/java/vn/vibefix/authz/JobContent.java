package vn.vibefix.authz;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

import vn.vibefix.support.MutableClock;
import vn.vibefix.team.internal.TeamTestSupport;

/**
 * The rows a Job's people and content take (AD-41, AD-20), written directly, for the matrix fixtures and the
 * cross-role suite (Story 20.5): a Product, an Active Job, Crew rows in each state, the Job Chat and the Crew
 * Chat with their messages, the Board with a Work Item, its note and Attachment, and a Page. Each method writes
 * only what it names, so a world adds exactly the content its canaries need.
 */
public final class JobContent {

	/** When an Active Job's Crew signed, ten days before the default clock. */
	public static final Instant SIGNED = MutableClock.DEFAULT_INSTANT.minus(Duration.ofDays(10));

	private static final LocalDate VERIFIED_UNTIL = LocalDate.of(2026, 9, 30);

	private JobContent() {
	}

	/** A registered Product of {@code owner}; returns its ID. */
	public static UUID product(JdbcClient jdbc, UUID owner, String name) {
		UUID product = UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO product.product (id, customer_account_id, name, description, ai_tools, repository_github_id,
				    repository_full_name, repository_owner_type, repository_default_branch, public_notice_version,
				    copy_permission_version, detection_notice_version, acknowledged_at, created_at)
				VALUES (:id, :owner, :name, 'Mô tả', ARRAY['LOVABLE'], :github, 'matrix/san-pham', 'User', 'main', '1.0',
				    '1.0', '1.0', :at, :at)
				""")
			.param("id", product)
			.param("owner", owner)
			.param("name", name)
			.param("github", ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE))
			.param("at", Timestamp.from(SIGNED))
			.update();
		return product;
	}

	/** An Active Job of the Product, selected and started {@link #SIGNED}; returns its ID. */
	public static UUID activeJob(JdbcClient jdbc, UUID product, UUID customer, UUID mentor, String title) {
		return activeJob(jdbc, product, customer, mentor, title, SIGNED);
	}

	/** An Active Job of the Product, selected and started {@code signed}; returns its ID. */
	public static UUID activeJob(JdbcClient jdbc, UUID product, UUID customer, UUID mentor, String title, Instant signed) {
		return job(jdbc, product, customer, mentor, title, signed, "ACTIVE");
	}

	/**
	 * A Job of the Product selected and started {@code signed}, now in {@code state} ({@code ACTIVE}, or
	 * {@code CLOSED} for one that ended); returns its ID.
	 */
	public static UUID job(JdbcClient jdbc, UUID product, UUID customer, UUID mentor, String title, Instant signed,
			String state) {
		UUID job = UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users,
				    job_types, budget, deadline, rules, selection_days, opened_at, lapses_at, mentor_account_id,
				    selected_application_id, selected_at, version, created_at, updated_at)
				VALUES (:id, :product, :customer, :state, :title, 'Lỗi', 'Đúng', 'Khách', ARRAY['BUG_FIX'], 'UNDER_2M',
				    DATE '2026-12-01', '{}'::jsonb, 14, :signed, :lapses, :mentor, :application, :signed, 5, :signed,
				    :signed)
				""")
			.param("id", job)
			.param("product", product)
			.param("customer", customer)
			.param("state", state)
			.param("title", title)
			.param("signed", Timestamp.from(signed))
			.param("lapses", Timestamp.from(signed.plus(Duration.ofDays(14))))
			.param("mentor", mentor)
			.param("application", UUID.randomUUID())
			.update();
		return job;
	}

	/**
	 * The Mentor's bid that J3 selected (FR-13, FR-15), under the ID {@link #activeJob} recorded, submitted a day
	 * before {@code selected}: a running Job's selected bid is a real one. Returns its ID.
	 */
	public static UUID selectedBid(JdbcClient jdbc, UUID job, UUID product, UUID customer, UUID mentor, Instant selected) {
		UUID bid = jdbc.sql("SELECT selected_application_id FROM job.job WHERE id = :id").param("id", job)
			.query(UUID.class)
			.single();
		jdbc.sql("""
				INSERT INTO matching.application (id, job_id, product_id, customer_account_id, mentor_account_id, status,
				    price_vnd, timeline_days, approach, public_code_notice_version, submitted_at)
				VALUES (:id, :job, :product, :customer, :mentor, 'SELECTED', 800000, 7, 'Cách làm', '1.0', :at)
				""")
			.param("id", bid)
			.param("job", job)
			.param("product", product)
			.param("customer", customer)
			.param("mentor", mentor)
			.param("at", Timestamp.from(selected.minus(Duration.ofDays(1))))
			.update();
		return bid;
	}

	/**
	 * The Job's confirmed Agreement, version 1, which the Job records as J5 does, with one Milestone
	 * {@code milestone} titled {@code title} at {@code priceVnd}; the caller writes the Milestone's row in the state
	 * it needs.
	 */
	public static void confirmedAgreement(JdbcClient jdbc, UUID job, UUID customer, UUID mentor, boolean assessment,
			UUID milestone, String title, long priceVnd, Instant confirmed) {
		String content = """
				{"scope":"Sửa lỗi","priceVnd":%d,"codeJob":%s,"assessment":%s,"skills":[],"externalAccess":[],
				 "milestones":[{"id":"%s","title":"%s","priceVnd":%d,"dueOn":"2026-11-20","revisionRounds":2,
				   "criteria":[{"id":"%s","statement":"Đạt yêu cầu","checkSteps":"1. Mở."}]}]}
				""".formatted(priceVnd, !assessment, assessment, milestone, title, priceVnd, UUID.randomUUID());
		Timestamp at = Timestamp.from(confirmed);
		jdbc.sql("""
				INSERT INTO job.agreement_version (job_id, version_no, status, content, author_account_id, created_at)
				VALUES (:job, 1, 'SHARED', CAST(:content AS JSONB), :mentor, :at)
				""").param("job", job).param("content", content).param("mentor", mentor).param("at", at).update();
		jdbc.sql("""
				INSERT INTO job.confirmed_agreement (job_id, version_no, draft_version_no, content, crew_usernames,
				    freeze_committed, customer_account_id, approved_at, mentor_account_id, confirmed_at)
				VALUES (:job, 1, 1, CAST(:content AS JSONB), '{}', true, :customer, :at, :mentor, :at)
				""").param("job", job).param("content", content).param("customer", customer).param("mentor", mentor)
			.param("at", at)
			.update();
		jdbc.sql("UPDATE job.job SET agreement_version_no = 1 WHERE id = :id").param("id", job).update();
	}

	/** A Crew member with a profile and a live student verification whose name is shown. */
	public static UUID member(JdbcClient jdbc, String name) {
		return TeamTestSupport.student(jdbc, name, true, List.of(TeamTestSupport.REACT), "React", VERIFIED_UNTIL);
	}

	/**
	 * A Crew member who chose to hide their student name (FR-51), {@code hiddenStudentName}, and is shown as
	 * {@code displayName}, with a public profile like every verified member's.
	 */
	public static UUID hidingMember(JdbcClient jdbc, String displayName, String hiddenStudentName) {
		UUID member = TeamTestSupport.student(jdbc, hiddenStudentName, false, List.of(TeamTestSupport.REACT), "React",
				VERIFIED_UNTIL);
		jdbc.sql("UPDATE identity.profile SET display_name = :name WHERE account_id = :id")
			.param("name", displayName)
			.param("id", member)
			.update();
		return member;
	}

	/**
	 * A Crew member whose own words are the canaries: the shown name ({@code displayName}), the account's Google
	 * email, a LinkedIn link, and, when given, a student name they chose to hide (FR-51); without one, the shown
	 * student name is {@code displayName} too, so whichever name a view shows is a canary.
	 */
	public static UUID canaryMember(JdbcClient jdbc, String displayName, String email, @Nullable String linkedIn,
			@Nullable String hiddenStudentName) {
		UUID member = TeamTestSupport.student(jdbc, hiddenStudentName == null ? displayName : hiddenStudentName,
				hiddenStudentName == null, List.of(TeamTestSupport.REACT), "React", VERIFIED_UNTIL);
		jdbc.sql("UPDATE identity.profile SET display_name = :name WHERE account_id = :id")
			.param("name", displayName)
			.param("id", member)
			.update();
		jdbc.sql("UPDATE identity.account SET google_sub = 'google-' || id, google_email = :email WHERE id = :id")
			.param("email", email)
			.param("id", member)
			.update();
		if (linkedIn != null) {
			jdbc.sql("INSERT INTO identity.profile_link (account_id, position, url) VALUES (:id, 0, :url)")
				.param("id", member)
				.param("url", linkedIn)
				.update();
		}
		return member;
	}

	/**
	 * The member's Crew row in {@code state}: {@code ASSIGNED}, {@code ACTIVE}, or the reason a row that became
	 * ACTIVE was released with ({@code REMOVED}, {@code LEFT}), released at {@code windowEnd}; every row but
	 * ASSIGNED signed and became ACTIVE {@link #SIGNED}. Returns the row's ID.
	 */
	public static UUID crewRow(JdbcClient jdbc, UUID job, UUID member, String state, @Nullable Instant windowEnd) {
		return crewRow(jdbc, job, member, state, windowEnd, SIGNED);
	}

	/** As {@link #crewRow(JdbcClient, UUID, UUID, String, Instant)}, signed and active since {@code signedAt}. */
	public static UUID crewRow(JdbcClient jdbc, UUID job, UUID member, String state, @Nullable Instant windowEnd,
			Instant signedAt) {
		boolean signed = !"ASSIGNED".equals(state);
		boolean released = signed && !"ACTIVE".equals(state);
		if (released && windowEnd == null) {
			throw new IllegalArgumentException("A released row has a window end");
		}
		UUID row = UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO job.crew_row (id, job_id, member_account_id, status, signed_at, activated_at, window_end,
				    released_at, released_reason, created_at)
				VALUES (:id, :job, :member, :status, :signed, :signed, :end, :end, :reason, :created)
				""")
			.param("id", row)
			.param("job", job)
			.param("member", member)
			.param("status", released ? "RELEASED" : state)
			.param("signed", signed ? Timestamp.from(signedAt) : null)
			.param("end", released ? Timestamp.from(windowEnd) : null)
			.param("reason", released ? state : null)
			.param("created", Timestamp.from(signedAt.minus(Duration.ofHours(1))))
			.update();
		return row;
	}

	/** The Job's Chat of {@code kind} ({@code JOB} or {@code CREW}), opened {@link #SIGNED}; returns its ID. */
	public static UUID chat(JdbcClient jdbc, UUID job, UUID product, String kind) {
		UUID chat = UUID.randomUUID();
		jdbc.sql("INSERT INTO chat.chat (id, job_id, product_id, kind, opened_at) VALUES (:id, :job, :product, :kind, :at)")
			.param("id", chat)
			.param("job", job)
			.param("product", product)
			.param("kind", kind)
			.param("at", Timestamp.from(SIGNED))
			.update();
		return chat;
	}

	/** A message of {@code author} in the Chat; returns its ID. */
	public static UUID message(JdbcClient jdbc, UUID chat, UUID author, String body, Instant at) {
		UUID id = UUID.randomUUID();
		jdbc.sql("INSERT INTO chat.message (id, chat_id, author_account_id, body, sent_at) VALUES (:id, :chat, :author, :body, :at)")
			.param("id", id)
			.param("chat", chat)
			.param("author", author)
			.param("body", body)
			.param("at", Timestamp.from(at))
			.update();
		return id;
	}

	/**
	 * A message of {@code author} in the Chat whose only content is the stored file {@code fileId}, named
	 * {@code fileName}, as a chat attachment is sent (AD-21); returns its ID.
	 */
	public static UUID attachmentMessage(JdbcClient jdbc, UUID chat, UUID author, UUID fileId, String fileName,
			Instant at) {
		UUID id = UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO chat.message (id, chat_id, author_account_id, body, attachment_file_id, attachment_name,
				    attachment_media_type, attachment_size, sent_at)
				VALUES (:id, :chat, :author, '', :file, :name, 'application/pdf', 2048, :at)
				""")
			.param("id", id)
			.param("chat", chat)
			.param("author", author)
			.param("file", fileId)
			.param("name", fileName)
			.param("at", Timestamp.from(at))
			.update();
		return id;
	}

	/** The Job's Board, whose code is {@code code}, with room for one Work Item. */
	public static void board(JdbcClient jdbc, UUID job, UUID product, String code) {
		board(jdbc, job, product, code, 1);
	}

	/** The Job's Board, whose code is {@code code}, with room for {@code items} Work Items. */
	public static void board(JdbcClient jdbc, UUID job, UUID product, String code, int items) {
		jdbc.sql("""
				INSERT INTO work.board (job_id, product_id, code, next_number, created_at)
				VALUES (:job, :product, :code, :next, :at)
				""")
			.param("job", job)
			.param("product", product)
			.param("code", code)
			.param("next", items + 1)
			.param("at", Timestamp.from(SIGNED))
			.update();
	}

	/** Work Item 1 of the Board, a Story for the criterion when one is given; returns its ID. */
	public static UUID workItem(JdbcClient jdbc, UUID job, @Nullable UUID milestone, @Nullable UUID criterion, Instant at) {
		return workItem(jdbc, job, 1, "Hai khách không đặt trùng bàn", milestone, criterion, at);
	}

	/** Work Item {@code number} of the Board titled {@code title}, a Story for the criterion when one is given. */
	public static UUID workItem(JdbcClient jdbc, UUID job, int number, String title, @Nullable UUID milestone,
			@Nullable UUID criterion, Instant at) {
		UUID item = UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO work.work_item (id, job_id, number, type, title, description, criterion_id, milestone_id, created_at,
				    updated_at)
				VALUES (:id, :job, :number, 'STORY', :title, '1. Mở. 2. Đặt.', :criterion, :milestone, :at, :at)
				""")
			.param("id", item)
			.param("job", job)
			.param("number", number)
			.param("title", title)
			.param("criterion", criterion)
			.param("milestone", milestone)
			.param("at", Timestamp.from(at))
			.update();
		return item;
	}

	/** A note of {@code author} on the Work Item; returns its history entry's ID. */
	public static UUID note(JdbcClient jdbc, UUID item, UUID job, UUID author, String text, Instant at) {
		UUID note = UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO work.work_item_history (id, work_item_id, job_id, actor_account_id, action, changes, comment, at)
				VALUES (:id, :item, :job, :author, 'NOTE', '{}'::jsonb, :note, :at)
				""")
			.param("id", note)
			.param("item", item)
			.param("job", job)
			.param("author", author)
			.param("note", text)
			.param("at", Timestamp.from(at))
			.update();
		return note;
	}

	/** A file Attachment named {@code fileName} that {@code author} added to the Work Item; returns its ID. */
	public static UUID attachment(JdbcClient jdbc, UUID item, UUID job, UUID author, String fileName, Instant at) {
		return attachment(jdbc, item, job, author, fileName, UUID.randomUUID(), at);
	}

	/** A file Attachment of the stored file {@code fileId}, named {@code fileName}; returns its ID. */
	public static UUID attachment(JdbcClient jdbc, UUID item, UUID job, UUID author, String fileName, UUID fileId,
			Instant at) {
		UUID attachment = UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO work.attachment (id, work_item_id, job_id, kind, file_id, file_name, media_type, size_bytes,
				    author_account_id, added_at)
				VALUES (:id, :item, :job, 'FILE', :file, :name, 'application/pdf', 2048, :author, :at)
				""")
			.param("id", attachment)
			.param("item", item)
			.param("job", job)
			.param("file", fileId)
			.param("name", fileName)
			.param("author", author)
			.param("at", Timestamp.from(at))
			.update();
		jdbc.sql("""
				INSERT INTO work.work_item_history (id, work_item_id, job_id, actor_account_id, action, changes, attachment_id, at)
				VALUES (:id, :item, :job, :author, 'ATTACHED', '{}'::jsonb, :attachment, :at)
				""")
			.param("id", UUID.randomUUID())
			.param("item", item)
			.param("job", job)
			.param("author", author)
			.param("attachment", attachment)
			.param("at", Timestamp.from(at))
			.update();
		return attachment;
	}

	/** A team-only Page of {@code author} at version 1 with {@code body}; returns its ID. */
	public static UUID page(JdbcClient jdbc, UUID job, UUID author, String body, Instant at) {
		return page(jdbc, job, author, "Ghi chú kỹ thuật", body, at);
	}

	/** A team-only Page of {@code author} titled {@code title} at version 1 with {@code body}; returns its ID. */
	public static UUID page(JdbcClient jdbc, UUID job, UUID author, String title, String body, Instant at) {
		UUID page = UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO work.page (id, job_id, title, version_no, created_by_account_id, created_at, updated_at)
				VALUES (:id, :job, :title, 1, :author, :at, :at)
				""").param("id", page).param("job", job).param("title", title).param("author", author)
			.param("at", Timestamp.from(at)).update();
		jdbc.sql("""
				INSERT INTO work.page_version (page_id, version_no, title, body, author_account_id, created_at)
				VALUES (:id, 1, :title, :body, :author, :at)
				""").param("id", page).param("title", title).param("body", body).param("author", author)
			.param("at", Timestamp.from(at)).update();
		return page;
	}

	/**
	 * A Milestone of the confirmed Agreement at {@code position} in {@code state}: {@code PLANNED} not Funded yet, so
	 * pinned to no Crew Terms version, which funding pins; or {@code ACCEPTED}, Funded at {@code funded} under Crew
	 * Terms version 1 and accepted at {@code accepted}.
	 */
	public static void milestone(JdbcClient jdbc, UUID job, UUID milestone, int position, String title, long priceVnd,
			String state, @Nullable Instant funded, @Nullable Instant accepted, @Nullable UUID handover, Instant created) {
		jdbc.sql("""
				INSERT INTO job.milestone (id, job_id, position, title, price_vnd, due_on, revision_rounds, created_at, state,
				    funded_at, crew_terms_version_no, submitted_at, handover_id, accepted_at)
				VALUES (:id, :job, :position, :title, :price, DATE '2026-11-20', 2, :created, :state, :funded, :version,
				    :submitted, :handover, :accepted)
				""")
			.param("id", milestone)
			.param("job", job)
			.param("position", position)
			.param("title", title)
			.param("price", priceVnd)
			.param("created", Timestamp.from(created))
			.param("state", state)
			.param("funded", funded == null ? null : Timestamp.from(funded))
			.param("version", funded == null ? null : 1)
			.param("submitted", accepted == null ? null : Timestamp.from(accepted.minus(Duration.ofDays(1))))
			.param("handover", handover)
			.param("accepted", accepted == null ? null : Timestamp.from(accepted))
			.update();
	}

	/** Crew Terms version 1, in force since {@code at}, of the Job's Mentor. */
	public static void crewTerms(JdbcClient jdbc, UUID job, UUID mentor, Instant at) {
		jdbc.sql("""
				INSERT INTO job.crew_terms_version (job_id, version_no, status, cap_percent, author_account_id, created_at)
				VALUES (:job, 1, 'IN_FORCE', 40, :mentor, :at)
				""").param("job", job).param("mentor", mentor).param("at", Timestamp.from(at)).update();
	}

	/** One party's share of Crew Terms version 1: the Mentor's has no Crew row. */
	public static void share(JdbcClient jdbc, UUID job, UUID party, @Nullable UUID row, int percent) {
		jdbc.sql("""
				INSERT INTO job.crew_terms_share (job_id, version_no, party_account_id, crew_row_id, percent)
				VALUES (:job, 1, :party, :row, :percent)
				""").param("job", job).param("party", party).param("row", row).param("percent", percent).update();
	}

	/**
	 * Funds the Milestone as M1 does (FR-41, AD-15): its ledger account, the Customer's paid funding order of
	 * {@code amountVnd}, and the FUND entry, all at {@code at}. Returns the order's ID.
	 */
	public static UUID fund(JdbcClient jdbc, UUID job, UUID product, UUID milestone, UUID customer, long amountVnd,
			Instant at) {
		Timestamp when = Timestamp.from(at);
		jdbc.sql("""
				INSERT INTO payment.ledger_account (milestone_id, job_id, product_id, created_at)
				VALUES (:milestone, :job, :product, :at)
				""").param("milestone", milestone).param("job", job).param("product", product).param("at", when).update();
		UUID order = UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO payment.payment_order (id, job_id, product_id, milestone_id, customer_account_id, purpose,
				    amount_vnd, agreement_version_no, txn_ref, status, created_at, expires_at, finished_at)
				VALUES (:id, :job, :product, :milestone, :customer, 'FUNDING', :amount, 1, :ref, 'PAID', :at, :at, :at)
				""").param("id", order).param("job", job).param("product", product).param("milestone", milestone)
			.param("customer", customer)
			.param("amount", amountVnd)
			.param("ref", "M" + Long.toString(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE), 36))
			.param("at", when)
			.update();
		jdbc.sql("""
				INSERT INTO payment.ledger_entry (id, operation_key, entry_type, bucket, job_id, product_id, milestone_id,
				    payment_order_id, amount_vnd, vnp_transaction_no, created_at)
				VALUES (:id, :key, 'FUND', 'MILESTONE', :job, :product, :milestone, :order, :amount, :no, :at)
				""").param("id", UUID.randomUUID()).param("key", "FUND:" + UUID.randomUUID()).param("job", job)
			.param("product", product).param("milestone", milestone).param("order", order).param("amount", amountVnd)
			.param("no", String.valueOf(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE))).param("at", when)
			.update();
		return order;
	}

	/**
	 * One party's part of a Milestone's release under Crew Terms version 1 (FR-42, AD-15), keyed by the release's
	 * transition as {@code MilestoneReleases} keys it: {@code RELEASE}, or {@code RELEASE_HELD} for a party a share
	 * Team Issue holds.
	 */
	public static void release(JdbcClient jdbc, String type, UUID transition, UUID job, UUID product, UUID milestone,
			UUID party, long amountVnd, int percent, long baseVnd, Instant at) {
		jdbc.sql("""
				INSERT INTO payment.ledger_entry (id, operation_key, entry_type, job_id, product_id, milestone_id, amount_vnd,
				    party_account_id, crew_terms_version_no, share_percent, base_amount_vnd, created_at)
				VALUES (:id, :key, :type, :job, :product, :milestone, :amount, :party, 1, :percent, :base, :at)
				""")
			.param("id", UUID.randomUUID())
			.param("key", type + ":" + transition + ":" + party)
			.param("type", type)
			.param("job", job)
			.param("product", product)
			.param("milestone", milestone)
			.param("amount", amountVnd)
			.param("party", party)
			.param("percent", percent)
			.param("base", baseVnd)
			.param("at", Timestamp.from(at))
			.update();
	}

	/** The Customer's profile, with a display name and a contact email. */
	public static void customerProfile(JdbcClient jdbc, UUID customer, String displayName, String contactEmail) {
		jdbc.sql("""
				INSERT INTO identity.profile (account_id, kind, display_name, contact_email, updated_at)
				VALUES (:account, 'INDIVIDUAL', :name, :email, :at)
				ON CONFLICT (account_id) DO UPDATE SET display_name = EXCLUDED.display_name,
				    contact_email = EXCLUDED.contact_email
				""")
			.param("account", customer)
			.param("name", displayName)
			.param("email", contactEmail)
			.param("at", Timestamp.from(SIGNED))
			.update();
	}

}
