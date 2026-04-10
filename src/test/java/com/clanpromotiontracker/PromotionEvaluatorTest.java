package com.clanpromotiontracker;

import org.junit.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class PromotionEvaluatorTest
{
	@Test
	public void defaultLadderMatchesRailwayAppThresholds()
	{
		List<RankRequirement> ladder = PromotionEvaluator.getDefaultLadder();

		assertEquals(13, ladder.size());
		assertEquals("Thief", ladder.get(0).getName());
		assertEquals(0, ladder.get(0).getMonthsRequired());
		assertEquals(0L, ladder.get(0).getXpRequired());
		assertEquals("Recruit", ladder.get(1).getName());
		assertEquals(100_000L, ladder.get(1).getXpRequired());
		assertEquals("Marshal", ladder.get(ladder.size() - 1).getName());
		assertEquals(24, ladder.get(ladder.size() - 1).getMonthsRequired());
		assertEquals(75_000_000L, ladder.get(ladder.size() - 1).getXpRequired());
	}

	@Test
	public void wholeMonthCalculationMatchesRailwaySemantics()
	{
		assertEquals(1, PromotionEvaluator.calculateWholeMonths(LocalDate.of(2026, 3, 31), LocalDate.of(2026, 4, 1)));
		assertEquals(0, PromotionEvaluator.calculateWholeMonths(LocalDate.of(2026, 4, 30), LocalDate.of(2026, 4, 1)));
		assertEquals(12, PromotionEvaluator.calculateWholeMonths(LocalDate.of(2025, 4, 30), LocalDate.of(2026, 4, 1)));
	}

	@Test
	public void evaluateOnlyPromotesToImmediateNextRank()
	{
		PromotionDecision decision = PromotionEvaluator.evaluate(
			"Sergeant",
			LocalDate.of(2025, 1, 1),
			100_000_000L,
			LocalDate.of(2026, 4, 9),
			PromotionEvaluator.getDefaultLadder(),
			false);

		assertEquals("Lieutenant", decision.getNextRankCandidate());
		assertEquals(PromotionStatus.READY, decision.getStatus());
	}

	@Test
	public void evaluateCanRecommendRankSkipsWhenEnabled()
	{
		PromotionDecision decision = PromotionEvaluator.evaluate(
			"Sergeant",
			LocalDate.of(2025, 1, 1),
			100_000_000L,
			LocalDate.of(2026, 4, 9),
			PromotionEvaluator.getDefaultLadder(),
			false,
			true);

		assertEquals("Colonel", decision.getNextRankCandidate());
		assertEquals(PromotionStatus.READY, decision.getStatus());
	}

	@Test
	public void approximateBaselineProducesApproximateStatusWhenReady()
	{
		PromotionDecision decision = PromotionEvaluator.evaluate(
			"Recruit",
			LocalDate.of(2026, 1, 1),
			600_000L,
			LocalDate.of(2026, 4, 9),
			PromotionEvaluator.getDefaultLadder(),
			true);

		assertEquals("Corporal", decision.getNextRankCandidate());
		assertEquals(PromotionStatus.APPROXIMATE_BASELINE, decision.getStatus());
	}

	@Test
	public void evaluateStillReturnsImmediateNextRankWhenSkipEnabledButNotReady()
	{
		PromotionDecision decision = PromotionEvaluator.evaluate(
			"Sergeant",
			LocalDate.of(2026, 3, 1),
			100_000L,
			LocalDate.of(2026, 4, 9),
			PromotionEvaluator.getDefaultLadder(),
			false,
			true);

		assertEquals("Lieutenant", decision.getNextRankCandidate());
		assertEquals(PromotionStatus.NOT_READY, decision.getStatus());
	}

	@Test
	public void customLadderOverrideCanReplaceDefaultThresholds()
	{
		List<RankRequirement> custom = PromotionEvaluator.resolveLadder(
			true,
			"Apprentice|0|0\nJourneyman|1|1.5");

		PromotionDecision decision = PromotionEvaluator.evaluate(
			"Apprentice",
			LocalDate.of(2026, 3, 1),
			1_500_000L,
			LocalDate.of(2026, 4, 9),
			custom,
			false);

		assertEquals(2, custom.size());
		assertEquals("Journeyman", decision.getNextRankCandidate());
		assertEquals(PromotionStatus.READY, decision.getStatus());
	}
}
