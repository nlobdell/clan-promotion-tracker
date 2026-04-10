package com.clanpromotiontracker;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WomRequestBudgetTest
{
	@Test
	public void blocksRequestsWhenWindowIsFull()
	{
		AtomicLong now = new AtomicLong(1_000L);
		WomRequestBudget budget = new WomRequestBudget(60_000L, now::get);

		assertTrue(budget.tryAcquire(2));
		assertTrue(budget.tryAcquire(2));
		assertFalse(budget.tryAcquire(2));
		assertTrue(budget.retryAfterSeconds(2) >= 1L);
	}

	@Test
	public void allowsRequestsAgainAfterWindowExpires()
	{
		AtomicLong now = new AtomicLong(1_000L);
		WomRequestBudget budget = new WomRequestBudget(1_000L, now::get);

		assertTrue(budget.tryAcquire(1));
		assertFalse(budget.tryAcquire(1));

		now.addAndGet(1_100L);
		assertEquals(0L, budget.retryAfterSeconds(1));
		assertTrue(budget.tryAcquire(1));
		assertTrue(budget.retryAfterSeconds(1) >= 1L);
	}

	@Test
	public void roundsRetryAfterSecondsUp()
	{
		AtomicLong now = new AtomicLong(10_000L);
		WomRequestBudget budget = new WomRequestBudget(60_000L, now::get);

		assertTrue(budget.tryAcquire(1));
		assertFalse(budget.tryAcquire(1));

		now.addAndGet(59_100L);
		assertFalse(budget.tryAcquire(1));
		assertEquals(1L, budget.retryAfterSeconds(1));
	}
}
