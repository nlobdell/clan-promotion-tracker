package com.clanpromotiontracker;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.LongSupplier;

final class WomRequestBudget
{
	private final Deque<Long> requestTimestamps = new ArrayDeque<>();
	private final long windowMillis;
	private final LongSupplier clock;

	WomRequestBudget(long windowMillis)
	{
		this(windowMillis, System::currentTimeMillis);
	}

	WomRequestBudget(long windowMillis, LongSupplier clock)
	{
		this.windowMillis = windowMillis;
		this.clock = clock;
	}

	synchronized boolean tryAcquire(int maxRequestsPerWindow)
	{
		if (maxRequestsPerWindow <= 0)
		{
			return false;
		}

		long now = clock.getAsLong();
		evictExpired(now);
		if (requestTimestamps.size() >= maxRequestsPerWindow)
		{
			return false;
		}

		requestTimestamps.addLast(now);
		return true;
	}

	synchronized long retryAfterSeconds(int maxRequestsPerWindow)
	{
		if (maxRequestsPerWindow <= 0)
		{
			return 60L;
		}

		long now = clock.getAsLong();
		evictExpired(now);
		if (requestTimestamps.size() < maxRequestsPerWindow)
		{
			return 0L;
		}

		Long oldest = requestTimestamps.peekFirst();
		if (oldest == null)
		{
			return 0L;
		}

		long remainingMillis = windowMillis - (now - oldest);
		if (remainingMillis <= 0L)
		{
			return 0L;
		}

		return Math.max(1L, (remainingMillis + 999L) / 1000L);
	}

	private void evictExpired(long now)
	{
		long threshold = now - windowMillis;
		while (!requestTimestamps.isEmpty() && requestTimestamps.peekFirst() < threshold)
		{
			requestTimestamps.removeFirst();
		}
	}
}
