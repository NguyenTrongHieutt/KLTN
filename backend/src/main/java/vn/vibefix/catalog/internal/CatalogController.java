package vn.vibefix.catalog.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletResponse;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import vn.vibefix.catalog.Catalog;
import vn.vibefix.catalog.SkillCategory;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.Gate;

/**
 * The active Skill catalog for every signed-in screen with a Skill picker (AD-38, UX-DR75). It holds
 * no personal data, so unlike other API answers it may be cached: {@code private, no-cache} with an
 * ETag, and a matching {@code If-None-Match} answers 304 without a body.
 */
@RestController
@RequestMapping("/api/v1/catalog")
@Access(Gate.SIGNED_IN)
class CatalogController {

	/** @param categories the active Categories, each with its active Skills, in display order */
	record CatalogView(List<CatalogCategory> categories) {

		static CatalogView of(List<SkillCategory> categories) {
			return new CatalogView(categories.stream()
				.map(category -> new CatalogCategory(category.id(), category.name(), category.skills()
					.stream()
					.map(skill -> new CatalogSkill(skill.id(), skill.name(), skill.hint()))
					.toList()))
				.toList());
		}

	}

	record CatalogCategory(UUID id, String name, List<CatalogSkill> skills) {
	}

	/** @param hint the plain explanation Customer screens show beside the name (NFR-12) */
	record CatalogSkill(UUID id, String name, String hint) {
	}

	private final Catalog catalog;

	CatalogController(Catalog catalog) {
		this.catalog = catalog;
	}

	@GetMapping
	ResponseEntity<CatalogView> catalog(@RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) @Nullable String ifNoneMatch,
			HttpServletResponse response) {
		CatalogView view = CatalogView.of(this.catalog.active());
		String etag = etag(view);
		// Replaces the API's no-store (SecurityHeadersFilter): the browser keeps it and revalidates.
		response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-cache");
		if (etag.equals(ifNoneMatch)) {
			return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
		}
		return ResponseEntity.ok().eTag(etag).body(view);
	}

	/** A strong ETag over the whole answer: any change of a name, a hint, or the order changes it. */
	private static String etag(CatalogView view) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(view.toString().getBytes(StandardCharsets.UTF_8));
			return "\"" + HexFormat.of().formatHex(digest, 0, 16) + "\"";
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("Every Java platform has SHA-256", ex);
		}
	}

}
