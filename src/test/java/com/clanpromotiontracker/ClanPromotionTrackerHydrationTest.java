package com.clanpromotiontracker;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ClanPromotionTrackerHydrationTest
{
	@Test
	public void cohortThresholdUsesPlayerStrategyForNineMembers()
	{
		assertFalse(ClanPromotionTrackerPlugin.shouldUseGroupCohortStrategy(9));
	}

	@Test
	public void cohortThresholdUsesGroupStrategyForTenMembers()
	{
		assertTrue(ClanPromotionTrackerPlugin.shouldUseGroupCohortStrategy(10));
	}
}
