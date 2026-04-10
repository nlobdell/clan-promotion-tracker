package com.clanpromotiontracker;

import org.junit.Test;

import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ClanPromotionTrackerUiLogicTest
{
	@Test
	public void statusSummaryIncludesExplicitBuckets()
	{
		String summary = ClanPromotionTrackerPlugin.buildStatusSummary(
			java.util.Arrays.asList(
				recordWithStatus("ReadyUser", PromotionStatus.READY),
				recordWithStatus("ApproxUser", PromotionStatus.APPROXIMATE_BASELINE),
				recordWithStatus("NotReadyUser", PromotionStatus.NOT_READY),
				recordWithStatus("PendingUser", PromotionStatus.XP_NOT_FETCHED),
				recordWithStatus("NoMatchUser", PromotionStatus.NO_WOM_MATCH),
				recordWithStatus("UnknownUser", PromotionStatus.UNKNOWN_RANK)
			),
			6
		);

		assertTrue(summary.contains("Ready: 1"));
		assertTrue(summary.contains("Approx: 1"));
		assertTrue(summary.contains("Not ready: 1"));
		assertTrue(summary.contains("XP pending: 1"));
		assertTrue(summary.contains("Not in WOM group: 1"));
		assertTrue(summary.contains("Unknown rank: 1"));
		assertTrue(summary.contains("Visible: 6/6"));
	}

	@Test
	public void localFilterSemanticsMatchLockedDefinitions()
	{
		PromotionRecord ready = recordWithStatus("ReadyUser", PromotionStatus.READY);
		PromotionRecord approximate = recordWithStatus("ApproxUser", PromotionStatus.APPROXIMATE_BASELINE);
		PromotionRecord pending = recordWithStatus("PendingUser", PromotionStatus.XP_NOT_FETCHED);
		PromotionRecord noMatch = recordWithStatus("NoMatchUser", PromotionStatus.NO_WOM_MATCH);
		PromotionRecord unknown = recordWithStatus("UnknownUser", PromotionStatus.UNKNOWN_RANK);

		assertTrue(ClanPromotionTrackerPanel.matchesLocalFilter(ready, ClanPromotionTrackerPanel.LocalFilter.ALL));
		assertTrue(ClanPromotionTrackerPanel.matchesLocalFilter(ready, ClanPromotionTrackerPanel.LocalFilter.READY));
		assertTrue(ClanPromotionTrackerPanel.matchesLocalFilter(approximate, ClanPromotionTrackerPanel.LocalFilter.READY));
		assertFalse(ClanPromotionTrackerPanel.matchesLocalFilter(ready, ClanPromotionTrackerPanel.LocalFilter.NEEDS_ATTENTION));
		assertTrue(ClanPromotionTrackerPanel.matchesLocalFilter(pending, ClanPromotionTrackerPanel.LocalFilter.NEEDS_ATTENTION));
		assertTrue(ClanPromotionTrackerPanel.matchesLocalFilter(noMatch, ClanPromotionTrackerPanel.LocalFilter.NEEDS_ATTENTION));
		assertTrue(ClanPromotionTrackerPanel.matchesLocalFilter(unknown, ClanPromotionTrackerPanel.LocalFilter.NEEDS_ATTENTION));
		assertTrue(ClanPromotionTrackerPanel.matchesLocalFilter(pending, ClanPromotionTrackerPanel.LocalFilter.MISSING));
		assertTrue(ClanPromotionTrackerPanel.matchesLocalFilter(noMatch, ClanPromotionTrackerPanel.LocalFilter.MISSING));
		assertFalse(ClanPromotionTrackerPanel.matchesLocalFilter(unknown, ClanPromotionTrackerPanel.LocalFilter.MISSING));
	}

	@Test
	public void reasonTextCoversDeferredAndNonDeferredCases()
	{
		long now = 1_000_000L;
		long cooldownUntil = now + 30_000L;

		String deferredReason = ClanPromotionTrackerPanel.buildReasonText(
			recordWithStatus("PendingUser", PromotionStatus.XP_NOT_FETCHED),
			1234,
			cooldownUntil,
			"WOM request budget active",
			now
		);
		assertTrue(deferredReason.contains("deferred"));
		assertTrue(deferredReason.contains("30s"));

		assertEquals(
			"Not found in WOM group 1234 snapshot.",
			ClanPromotionTrackerPanel.buildReasonText(
				recordWithStatus("NoMatchUser", PromotionStatus.NO_WOM_MATCH),
				1234,
				0L,
				null,
				now
			)
		);
		assertEquals(
			"Current rank is not in configured ladder.",
			ClanPromotionTrackerPanel.buildReasonText(
				recordWithStatus("UnknownUser", PromotionStatus.UNKNOWN_RANK),
				1234,
				0L,
				null,
				now
			)
		);
		assertEquals(
			"Does not yet meet month/XP requirements for next rank.",
			ClanPromotionTrackerPanel.buildReasonText(
				recordWithStatus("NotReadyUser", PromotionStatus.NOT_READY),
				1234,
				0L,
				null,
				now
			)
		);
	}

	@Test
	public void womLookupPrefersCanonicalUsernameAndFallsBackToRosterName()
	{
		PromotionRecord record = recordWithStatus("Baron groof", PromotionStatus.NO_WOM_MATCH);
		Map<String, WiseOldManClient.GroupMember> womMembers = new HashMap<>();
		womMembers.put(
			record.getNormalizedUsername(),
			new WiseOldManClient.GroupMember("Baron Groof", "Baron_Groof", 0L)
		);

		assertEquals("Baron_Groof", ClanPromotionTrackerPlugin.resolveWomLookupUsername(record, womMembers));
		assertEquals(
			"https://wiseoldman.net/players/Baron%20Groof",
			ClanPromotionTrackerPlugin.buildWomProfileUrl("Baron Groof")
		);
		assertEquals(
			"Baron groof",
			ClanPromotionTrackerPlugin.resolveWomLookupUsername(record, Collections.emptyMap())
		);
	}

	private PromotionRecord recordWithStatus(String username, PromotionStatus status)
	{
		return new PromotionRecord(
			username,
			WiseOldManClient.normalizeName(username),
			"Recruit",
			LocalDate.of(2026, 1, 1),
			3,
			null,
			null,
			null,
			null,
			"Corporal",
			status,
			false
		);
	}
}
