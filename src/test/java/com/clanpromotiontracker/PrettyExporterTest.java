package com.clanpromotiontracker;

import org.junit.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;

import static org.junit.Assert.assertTrue;

public class PrettyExporterTest
{
	@Test
	public void markdownIncludesSummaryAndSections()
	{
		PromotionRecord ready = new PromotionRecord(
			"Smirk",
			"smirk",
			"Recruit",
			LocalDate.of(2026, 1, 1),
			3,
			LocalDate.of(2026, 1, 1),
			1_000L,
			2_500L,
			1_500L,
			"Corporal",
			PromotionStatus.READY,
			false
		);

		PromotionRecord unresolved = new PromotionRecord(
			"Warmholes",
			"warmholes",
			"Sergeant",
			LocalDate.of(2025, 9, 10),
			7,
			null,
			null,
			null,
			null,
			"Lieutenant",
			PromotionStatus.XP_NOT_FETCHED,
			false
		);

		ZonedDateTime generatedAt = ZonedDateTime.of(2026, 4, 10, 2, 30, 0, 0, ZoneId.of("America/Los_Angeles"));
		String markdown = PrettyExporter.toMarkdown(Arrays.asList(ready, unresolved), generatedAt);

		assertTrue(markdown.contains("# Clan Promotion Tracker Report"));
		assertTrue(markdown.contains("Generated: 2026-04-10 02:30:00 PDT"));
		assertTrue(markdown.contains("Members: 2"));
		assertTrue(markdown.contains("Ready: 1"));
		assertTrue(markdown.contains("XP not fetched: 1"));
		assertTrue(markdown.contains("## Promotion Recommendations"));
		assertTrue(markdown.contains("| Smirk | Recruit | Corporal |"));
		assertTrue(markdown.contains("## XP Not Fetched"));
		assertTrue(markdown.contains("| Warmholes | Sergeant | Lieutenant |"));
	}
}
