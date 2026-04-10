package com.clanpromotiontracker;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Provides;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.clan.ClanID;
import net.runelite.api.clan.ClanMember;
import net.runelite.api.clan.ClanRank;
import net.runelite.api.clan.ClanSettings;
import net.runelite.api.clan.ClanTitle;
import net.runelite.api.events.ClanChannelChanged;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.Notifier;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@PluginDescriptor(
	name = "Clan Promotion Tracker",
	description = "Track clan promotions with Wise Old Man XP baselines and highlight ready members",
	tags = {"clan", "rank", "promotion", "wiseoldman", "xp"}
)
public class ClanPromotionTrackerPlugin extends Plugin
{
	private static final Logger LOG = LoggerFactory.getLogger(ClanPromotionTrackerPlugin.class);
	private static final String CONFIG_GROUP = "clanpromotiontracker";
	private static final String BASELINE_CACHE_KEY = "baselineCache";
	private static final String UNRESOLVED_BASELINE_CACHE_KEY = "unresolvedBaselineCache";

	private static final long DEFAULT_RATE_LIMIT_COOLDOWN_SECONDS = 60L;
	private static final long WOM_GROUP_CACHE_TTL_SECONDS = 60L;
	private static final long WOM_REQUEST_WINDOW_SECONDS = 60L;
	private static final int WOM_MAX_REQUESTS_PER_WINDOW = 18;
	private static final int WOM_MAX_REQUESTS_PER_WINDOW_WITH_API_KEY = 90;
	private static final long WOM_MIN_REQUEST_SPACING_MILLIS = 3_500L;
	private static final long UNRESOLVED_RETRY_MILLIS = TimeUnit.HOURS.toMillis(6);
	private static final int GROUP_COHORT_THRESHOLD = 10;
	private static final int GROUP_GAINS_LIMIT_MIN = 1000;
	private static final int GROUP_GAINS_LIMIT_PADDING = 50;
	private static final int CACHE_EXPORT_FORMAT_VERSION = 1;

	private static final Comparator<PromotionRecord> RECORD_COMPARATOR = Comparator
		.comparingInt((PromotionRecord record) -> statusPriority(record.getStatus()))
		.thenComparing(Comparator.comparingInt(PromotionRecord::getMonthsInClan).reversed())
		.thenComparing(Comparator.comparingLong((PromotionRecord record) -> record.getXpGained() == null ? -1L : record.getXpGained()).reversed())
		.thenComparing(PromotionRecord::getUsername, String.CASE_INSENSITIVE_ORDER);

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private Notifier notifier;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private ClanPromotionTrackerConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ScheduledExecutorService scheduler;

	@Inject
	private WiseOldManClient wiseOldManClient;

	@Inject
	private ClanPromotionTrackerOverlay overlay;

	@Inject
	private Gson gson;

	private final AtomicBoolean refreshInProgress = new AtomicBoolean(false);
	private final Map<String, String> notifiedToday = new ConcurrentHashMap<>();
	private final WomRequestBudget womRequestBudget = new WomRequestBudget(TimeUnit.SECONDS.toMillis(WOM_REQUEST_WINDOW_SECONDS));
	private final Object hydrationLock = new Object();

	private ClanPromotionTrackerPanel panel;
	private NavigationButton navigationButton;
	private ScheduledFuture<?> autoRefreshFuture;
	private ScheduledFuture<?> hydrationFuture;

	private volatile List<PromotionRecord> allRecords = Collections.emptyList();
	private volatile Map<String, String> actionableTargetRanks = Collections.emptyMap();
	private volatile Map<String, String> actionableCurrentRanks = Collections.emptyMap();
	private volatile Map<String, BaselineCacheEntry> baselineCache = new ConcurrentHashMap<>();
	private volatile Map<String, Long> unresolvedBaselineCache = new ConcurrentHashMap<>();
	private volatile List<ClanRosterMember> clanRosterCache = Collections.emptyList();
	private volatile Map<String, WiseOldManClient.GroupMember> womGroupCache = Collections.emptyMap();
	private volatile int womGroupCacheId = -1;
	private volatile long womGroupCacheExpiresAtMillis;
	private volatile long womGroupCooldownUntilMillis;
	private volatile LocalDate lastNotificationDate;
	private volatile long hydrationRunSequence;
	private volatile HydrationRun activeHydrationRun;

	@Provides
	ClanPromotionTrackerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ClanPromotionTrackerConfig.class);
	}

	@Override
	protected void startUp()
	{
		baselineCache = new ConcurrentHashMap<>(loadBaselineCache());
		unresolvedBaselineCache = new ConcurrentHashMap<>(pruneUnresolvedCache(loadUnresolvedBaselineCache(), System.currentTimeMillis()));
		panel = new ClanPromotionTrackerPanel(
			() -> refreshAsync(true),
			this::stopHydrationFromPanel,
			this::copyCsvToClipboard,
			this::saveCsvToFile,
			this::copyPrettyReportToClipboard,
			this::savePrettyReportToFile,
			this::exportCacheToFile,
			this::importCacheFromFile,
			this::ignoreUserFromPanel
		);

		navigationButton = NavigationButton.builder()
			.tooltip("Clan Promotion Tracker")
			.icon(loadPluginIcon())
			.priority(5)
			.panel(panel)
			.build();

		clientToolbar.addNavigation(navigationButton);
		overlayManager.add(overlay);
		rescheduleAutoRefresh();
		refreshAsync(false);
	}

	@Override
	protected void shutDown()
	{
		if (autoRefreshFuture != null)
		{
			autoRefreshFuture.cancel(true);
			autoRefreshFuture = null;
		}

		synchronized (hydrationLock)
		{
			if (hydrationFuture != null)
			{
				hydrationFuture.cancel(true);
				hydrationFuture = null;
			}
			if (activeHydrationRun != null)
			{
				finalizeHydrationRunLocked(activeHydrationRun);
				activeHydrationRun = null;
			}
		}

		overlayManager.remove(overlay);
		if (navigationButton != null)
		{
			clientToolbar.removeNavigation(navigationButton);
			navigationButton = null;
		}

		panel = null;
		actionableTargetRanks = Collections.emptyMap();
		actionableCurrentRanks = Collections.emptyMap();
		allRecords = Collections.emptyList();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			refreshAsync(false);
		}
	}

	@Subscribe
	public void onClanChannelChanged(ClanChannelChanged event)
	{
		refreshAsync(false);
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!CONFIG_GROUP.equals(event.getGroup()))
		{
			return;
		}

		if (BASELINE_CACHE_KEY.equals(event.getKey()) || UNRESOLVED_BASELINE_CACHE_KEY.equals(event.getKey()))
		{
			return;
		}

		rescheduleAutoRefresh();
		refreshAsync(false);
	}

	Map<String, String> getActionableTargetRanks()
	{
		return actionableTargetRanks;
	}

	Map<String, String> getActionableCurrentRanks()
	{
		return actionableCurrentRanks;
	}

	private void refreshAsync(boolean manual)
	{
		if (manual)
		{
			long activeRunId = getActiveHydrationRunIdIfInProgress();
			if (activeRunId > 0)
			{
				publishRunState(activeRunId);
				if (panel != null)
				{
					panel.setInfoText("Hydration already running. Continuing current queue without restart.");
				}
				return;
			}
		}

		if (!refreshInProgress.compareAndSet(false, true))
		{
			if (manual && panel != null)
			{
				panel.setInfoText("Refresh already in progress.");
			}
			return;
		}

		if (panel != null)
		{
			panel.setBusy(true);
			panel.setInfoText(manual ? "Refreshing clan and Wise Old Man data..." : "Refreshing data...");
		}

		scheduler.submit(() ->
		{
			try
			{
				refreshNow(manual);
			}
			catch (Exception ex)
			{
				LOG.warn("Failed to refresh clan promotion data", ex);
				if (panel != null)
				{
					panel.setInfoText("Refresh failed: " + ex.getMessage());
				}
			}
			finally
			{
				refreshInProgress.set(false);
				if (panel != null)
				{
					panel.setBusy(false);
				}
			}
		});
	}

	private long getActiveHydrationRunIdIfInProgress()
	{
		synchronized (hydrationLock)
		{
			HydrationRun run = activeHydrationRun;
			if (run == null)
			{
				return -1L;
			}

			boolean hasPendingTasks = !run.pendingTasks.isEmpty();
			boolean workerActive = hydrationFuture != null && !hydrationFuture.isDone();
			boolean requestInFlight = run.inFlightTasks > 0;
			boolean inProgress = hasPendingTasks || workerActive || requestInFlight;
			return inProgress ? run.runId : -1L;
		}
	}

	private void stopHydrationFromPanel()
	{
		if (stopActiveHydrationRun("stopped by user", true))
		{
			return;
		}

		if (panel != null)
		{
			panel.setInfoText("No active hydration run to stop.");
		}
	}

	private boolean stopActiveHydrationRun(String interruptionReason, boolean publishState)
	{
		long runId;
		synchronized (hydrationLock)
		{
			HydrationRun run = activeHydrationRun;
			if (run == null)
			{
				return false;
			}

			boolean hasPendingTasks = !run.pendingTasks.isEmpty();
			boolean workerActive = hydrationFuture != null && !hydrationFuture.isDone();
			boolean requestInFlight = run.inFlightTasks > 0;
			if (!hasPendingTasks && !workerActive && !requestInFlight)
			{
				return false;
			}

			if (hydrationFuture != null)
			{
				hydrationFuture.cancel(false);
				hydrationFuture = null;
			}

			run.interruptedTasks = run.pendingTasks.size();
			run.pendingTasks.clear();
			run.interrupted = true;
			run.interruptionReason = interruptionReason;
			run.cooldownUntilMillis = 0L;
			run.cooldownReason = null;
			finalizeHydrationRunLocked(run);
			runId = run.runId;
		}

		if (publishState)
		{
			publishRunState(runId);
		}
		return true;
	}

	private void refreshNow(boolean manual) throws Exception
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			updateResults(Collections.emptyList(), "Log into RuneLite to start tracking promotions.");
			return;
		}

		List<ClanRosterMember> clanMembers = snapshotClanRoster();
		String rosterNote = null;
		if (clanMembers.isEmpty() && !clanRosterCache.isEmpty())
		{
			clanMembers = clanRosterCache;
			rosterNote = "Clan roster API returned no members; using the last successful clan roster snapshot.";
		}
		else if (!clanMembers.isEmpty())
		{
			clanRosterCache = Collections.unmodifiableList(new ArrayList<>(clanMembers));
		}

		if (clanMembers.isEmpty())
		{
			if (!allRecords.isEmpty())
			{
				updateInfoTextOnly("No clan members with readable roster data were found; keeping previous results visible.");
			}
			else
			{
				updateResults(Collections.emptyList(), "No clan members with readable roster data were found.");
			}
			return;
		}

		LocalDate today = LocalDate.now();
		List<RankRequirement> ladder = PromotionEvaluator.resolveLadder(config.useCustomRankLadder(), config.customRankLadder());
		String womApiKey = config.womApiKey();
		Map<String, WiseOldManClient.GroupMember> womMembers = Collections.emptyMap();
		String refreshNote;
		boolean womAvailable = false;
		WiseOldManClient.RateLimitMeta rateLimitMeta = WiseOldManClient.RateLimitMeta.empty();

		if (config.womGroupId() > 0)
		{
			GroupMembersResult groupMembersResult = fetchGroupMembers(config.womGroupId(), womApiKey);
			womMembers = groupMembersResult.getMembers();
			womAvailable = groupMembersResult.isAvailable();
			refreshNote = groupMembersResult.getNote();
			rateLimitMeta = groupMembersResult.getRateLimitMeta();
		}
		else
		{
			refreshNote = "Set a Wise Old Man group ID to fetch XP gains.";
		}

		refreshNote = appendRefreshNote(refreshNote, rosterNote);
		Map<String, BaselineCacheEntry> workingCache = new HashMap<>(baselineCache);
		Map<String, Long> unresolvedCache = pruneUnresolvedCache(unresolvedBaselineCache, System.currentTimeMillis());
		long runId = initializeHydrationRun(
			clanMembers, today, ladder, womAvailable, womMembers, workingCache, unresolvedCache,
			womApiKey, config.womGroupId(), refreshNote, rateLimitMeta, manual
		);
		publishRunState(runId);
	}

	private long initializeHydrationRun(
		List<ClanRosterMember> clanMembers,
		LocalDate today,
		List<RankRequirement> ladder,
		boolean womAvailable,
		Map<String, WiseOldManClient.GroupMember> womMembers,
		Map<String, BaselineCacheEntry> workingCache,
		Map<String, Long> unresolvedCache,
		String womApiKey,
		int womGroupId,
		String refreshNote,
		WiseOldManClient.RateLimitMeta rateLimitMeta,
		boolean forceRetrySuppressed)
	{
		HydrationRun run;
		synchronized (hydrationLock)
		{
			if (activeHydrationRun != null)
			{
				finalizeHydrationRunLocked(activeHydrationRun);
			}

			if (hydrationFuture != null)
			{
				hydrationFuture.cancel(false);
				hydrationFuture = null;
			}

			long runId = ++hydrationRunSequence;
			run = new HydrationRun(
				runId,
				today,
				ladder,
				Collections.unmodifiableList(new ArrayList<>(clanMembers)),
				Collections.unmodifiableMap(new HashMap<>(womMembers)),
				womAvailable,
				womApiKey,
				womGroupId,
				refreshNote,
				forceRetrySuppressed,
				workingCache,
				unresolvedCache
			);
			run.nextRequestAtMillis = System.currentTimeMillis() + computeSpacingMillis(rateLimitMeta);
			planHydrationTasksLocked(run);
			activeHydrationRun = run;
			if (!run.pendingTasks.isEmpty())
			{
				scheduleHydrationWorkerLocked(run, computeNextDelayLocked(run, System.currentTimeMillis()));
			}
			else
			{
				finalizeHydrationRunLocked(run);
			}
		}

		return run.runId;
	}

	private void planHydrationTasksLocked(HydrationRun run)
	{
		if (!run.womAvailable || run.womMembers.isEmpty() || run.womGroupId <= 0)
		{
			return;
		}

		long now = System.currentTimeMillis();
		Map<LocalDate, List<ClanRosterMember>> cohorts = new HashMap<>();
		for (ClanRosterMember member : run.clanMembers)
		{
			if (!shouldHydrateMember(run, member, now))
			{
				continue;
			}

			cohorts.computeIfAbsent(member.joinDate, ignored -> new ArrayList<>()).add(member);
		}

		List<Map.Entry<LocalDate, List<ClanRosterMember>>> sortedCohorts = new ArrayList<>(cohorts.entrySet());
		sortedCohorts.sort(Comparator
			.<Map.Entry<LocalDate, List<ClanRosterMember>>>comparingInt(entry -> entry.getValue().size())
			.reversed()
			.thenComparing(Map.Entry::getKey));

		for (Map.Entry<LocalDate, List<ClanRosterMember>> entry : sortedCohorts)
		{
			LocalDate joinDate = entry.getKey();
			List<ClanRosterMember> members = entry.getValue();
			if (shouldUseGroupCohortStrategy(members.size()))
			{
				run.pendingTasks.addLast(HydrationTask.group(joinDate, members));
				run.totalTasks++;
				continue;
			}

			for (ClanRosterMember member : members)
			{
				String cacheKey = buildBaselineCacheKey(WiseOldManClient.normalizeName(member.username), member.joinDate);
				addPlayerTaskLocked(run, member, cacheKey, false);
			}
		}
	}

	private boolean shouldHydrateMember(HydrationRun run, ClanRosterMember member, long now)
	{
		if (member.joinDate == null)
		{
			return false;
		}

		PromotionDecision baseDecision = PromotionEvaluator.evaluate(
			member.currentRank,
			member.joinDate,
			null,
			run.today,
			run.ladder,
			false,
			config.recommendRankSkips()
		);
		if (baseDecision.getStatus() == PromotionStatus.UNKNOWN_RANK || baseDecision.getNextRankCandidate() == null)
		{
			return false;
		}

		String normalizedName = WiseOldManClient.normalizeName(member.username);
		if (!run.womMembers.containsKey(normalizedName))
		{
			return false;
		}

		String cacheKey = buildBaselineCacheKey(normalizedName, member.joinDate);
		if (run.workingCache.containsKey(cacheKey))
		{
			return false;
		}

		Long retryAt = run.unresolvedCache.get(cacheKey);
		if (!run.forceRetrySuppressed && retryAt != null && retryAt > now)
		{
			run.suppressedByRetryWindow++;
			return false;
		}

		if (retryAt != null)
		{
			run.unresolvedCache.remove(cacheKey);
			run.unresolvedDirty = true;
		}

		return true;
	}

	private void addPlayerTaskLocked(HydrationRun run, ClanRosterMember member, String cacheKey, boolean fallback)
	{
		if (!run.queuedPlayerKeys.add(cacheKey))
		{
			return;
		}

		run.pendingTasks.addLast(HydrationTask.player(member, cacheKey, fallback));
		run.totalTasks++;
		if (fallback)
		{
			run.fallbackPlayerTasks++;
		}
	}

	private void scheduleHydrationWorkerLocked(HydrationRun run, long delayMillis)
	{
		if (run == null || activeHydrationRun == null || activeHydrationRun.runId != run.runId)
		{
			return;
		}

		if (hydrationFuture != null && !hydrationFuture.isDone())
		{
			return;
		}

		long safeDelay = Math.max(0L, delayMillis);
		hydrationFuture = scheduler.schedule(
			() -> processHydrationQueue(run.runId),
			safeDelay,
			TimeUnit.MILLISECONDS
		);
	}

	private void processHydrationQueue(long runId)
	{
		HydrationTask task = null;
		boolean budgetBlocked = false;
		HydrationRun inFlightRun = null;
		try
		{
			synchronized (hydrationLock)
			{
				hydrationFuture = null;
				HydrationRun run = activeHydrationRun;
				if (run == null || run.runId != runId)
				{
					return;
				}

				if (run.interrupted)
				{
					return;
				}

				long now = System.currentTimeMillis();
				long waitMillis = computeNextDelayLocked(run, now);
				if (waitMillis > 0L)
				{
					scheduleHydrationWorkerLocked(run, waitMillis);
					return;
				}

				if (run.pendingTasks.isEmpty())
				{
					finalizeHydrationRunLocked(run);
					return;
				}

				if (!tryConsumeWomRequestBudget(run.womApiKey))
				{
					long retryAfterSeconds = getWomBudgetRetryAfterSeconds(run.womApiKey);
					run.deferredTasks++;
					run.cooldownUntilMillis = now + TimeUnit.SECONDS.toMillis(retryAfterSeconds);
					run.cooldownReason = "WOM request budget active";
					budgetBlocked = true;
				}
				else
				{
					task = run.pendingTasks.pollFirst();
					if (task != null)
					{
						run.inFlightTasks++;
						inFlightRun = run;
					}
				}
			}

			if (budgetBlocked || task == null)
			{
				return;
			}

			if (task.strategy == HydrationStrategy.GROUP_COHORT)
			{
				processGroupTask(runId, task);
			}
			else
			{
				processPlayerTask(runId, task);
			}
		}
		catch (WiseOldManClient.RateLimitException ex)
		{
			handleHydrationRateLimit(runId, task, ex);
		}
		catch (IOException ex)
		{
			handleHydrationIOException(runId, task, ex);
		}
		catch (RuntimeException ex)
		{
			handleHydrationRuntimeException(runId, task, ex);
		}
		finally
		{
			markHydrationTaskFinished(inFlightRun);
			publishRunState(runId);
			scheduleNextHydrationStep(runId);
		}
	}

	private void markHydrationTaskFinished(HydrationRun run)
	{
		if (run == null)
		{
			return;
		}

		synchronized (hydrationLock)
		{
			if (run.inFlightTasks > 0)
			{
				run.inFlightTasks--;
			}
		}
	}

	private void processGroupTask(long runId, HydrationTask task) throws IOException
	{
		HydrationRun runSnapshot;
		int limit;
		synchronized (hydrationLock)
		{
			HydrationRun run = activeHydrationRun;
			if (run == null || run.runId != runId)
			{
				return;
			}

			if (run.interrupted)
			{
				return;
			}

			runSnapshot = run;
			limit = Math.max(GROUP_GAINS_LIMIT_MIN, run.womMembers.size() + GROUP_GAINS_LIMIT_PADDING);
		}

		WiseOldManClient.GroupGainsResponse response = wiseOldManClient.fetchGroupGains(
			runSnapshot.womGroupId,
			"overall",
			task.joinDate,
			runSnapshot.today,
			limit,
			runSnapshot.womApiKey
		);

		synchronized (hydrationLock)
		{
			HydrationRun run = activeHydrationRun;
			if (run == null || run.runId != runId)
			{
				return;
			}

			if (run.interrupted)
			{
				return;
			}

			run.groupRequests++;
			run.completedTasks++;
			applyRateLimitMetaLocked(run, response.getRateLimitMeta());
			long now = System.currentTimeMillis();
			for (ClanRosterMember member : task.cohortMembers)
			{
				String cacheKey = buildBaselineCacheKey(WiseOldManClient.normalizeName(member.username), member.joinDate);
				if (run.workingCache.containsKey(cacheKey))
				{
					continue;
				}

				WiseOldManClient.GroupGainEntry entry = response.getGains().get(WiseOldManClient.normalizeName(member.username));
				if (entry != null && entry.getStartDate() != null)
				{
					BaselineCacheEntry baseline = new BaselineCacheEntry(
						entry.getStartDate(),
						entry.getStartXp(),
						!entry.getStartDate().equals(member.joinDate)
					);
					run.workingCache.put(cacheKey, baseline);
					run.unresolvedCache.remove(cacheKey);
					run.baselineDirty = true;
					run.unresolvedDirty = true;
					run.hydratedTasks++;
					continue;
				}

				Long retryAt = run.unresolvedCache.get(cacheKey);
				if (retryAt != null && retryAt > now)
				{
					continue;
				}

				addPlayerTaskLocked(run, member, cacheKey, true);
			}
		}
	}

	private void processPlayerTask(long runId, HydrationTask task) throws IOException
	{
		HydrationRun runSnapshot;
		synchronized (hydrationLock)
		{
			HydrationRun run = activeHydrationRun;
			if (run == null || run.runId != runId)
			{
				return;
			}

			if (run.interrupted)
			{
				return;
			}

			runSnapshot = run;
		}

		WiseOldManClient.BaselineResponse response = wiseOldManClient.fetchBaselineWithMeta(
			task.member.username,
			task.member.joinDate,
			runSnapshot.today,
			runSnapshot.womApiKey
		);

		synchronized (hydrationLock)
		{
			HydrationRun run = activeHydrationRun;
			if (run == null || run.runId != runId)
			{
				return;
			}

			if (run.interrupted)
			{
				return;
			}

			run.playerRequests++;
			run.completedTasks++;
			applyRateLimitMetaLocked(run, response.getRateLimitMeta());
			BaselineCacheEntry fetched = response.getBaseline();
			if (fetched != null)
			{
				run.workingCache.put(task.cacheKey, fetched);
				run.unresolvedCache.remove(task.cacheKey);
				run.baselineDirty = true;
				run.unresolvedDirty = true;
				run.hydratedTasks++;
				return;
			}

			if (response.isNullWindow())
			{
				run.unresolvedCache.put(task.cacheKey, System.currentTimeMillis() + UNRESOLVED_RETRY_MILLIS);
				run.unresolvedDirty = true;
			}
		}
	}

	private void handleHydrationRateLimit(long runId, HydrationTask task, WiseOldManClient.RateLimitException ex)
	{
		long retryAfterSeconds = ex.getRetryAfterSeconds() > 0 ? ex.getRetryAfterSeconds() : DEFAULT_RATE_LIMIT_COOLDOWN_SECONDS;
		synchronized (hydrationLock)
		{
			HydrationRun run = activeHydrationRun;
			if (run == null || run.runId != runId)
			{
				return;
			}

			if (task != null)
			{
				run.pendingTasks.addFirst(task);
			}
			run.deferredTasks++;
			run.cooldownUntilMillis = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(retryAfterSeconds);
			run.cooldownReason = "WOM rate limited";
			applyRateLimitMetaLocked(run, ex.getRateLimitMeta());
		}
	}

	private void handleHydrationIOException(long runId, HydrationTask task, IOException ex)
	{
		if (task != null)
		{
			LOG.debug("WOM hydration request failed", ex);
		}

		synchronized (hydrationLock)
		{
			HydrationRun run = activeHydrationRun;
			if (run == null || run.runId != runId)
			{
				return;
			}

			if (task != null)
			{
				run.completedTasks++;
			}
		}
	}

	private void handleHydrationRuntimeException(long runId, HydrationTask task, RuntimeException ex)
	{
		LOG.warn("Unexpected error while processing WOM hydration task", ex);
		synchronized (hydrationLock)
		{
			HydrationRun run = activeHydrationRun;
			if (run == null || run.runId != runId)
			{
				return;
			}

			if (task != null)
			{
				run.completedTasks++;
			}
		}
	}

	private void scheduleNextHydrationStep(long runId)
	{
		synchronized (hydrationLock)
		{
			HydrationRun run = activeHydrationRun;
			if (run == null || run.runId != runId)
			{
				return;
			}

			if (run.pendingTasks.isEmpty())
			{
				finalizeHydrationRunLocked(run);
				return;
			}

			long delayMillis = computeNextDelayLocked(run, System.currentTimeMillis());
			scheduleHydrationWorkerLocked(run, delayMillis);
		}
	}

	private long computeNextDelayLocked(HydrationRun run, long now)
	{
		long nextAllowed = Math.max(run.cooldownUntilMillis, run.nextRequestAtMillis);
		return Math.max(0L, nextAllowed - now);
	}

	private void applyRateLimitMetaLocked(HydrationRun run, WiseOldManClient.RateLimitMeta rateLimitMeta)
	{
		if (rateLimitMeta == null)
		{
			run.nextRequestAtMillis = Math.max(run.nextRequestAtMillis, System.currentTimeMillis() + WOM_MIN_REQUEST_SPACING_MILLIS);
			return;
		}

		run.lastRateLimitMeta = rateLimitMeta;
		long now = System.currentTimeMillis();
		run.nextRequestAtMillis = Math.max(run.nextRequestAtMillis, now + computeSpacingMillis(rateLimitMeta));
		if (rateLimitMeta.getRemaining() == 0 && rateLimitMeta.getResetSeconds() > 0)
		{
			run.cooldownUntilMillis = Math.max(run.cooldownUntilMillis, now + TimeUnit.SECONDS.toMillis(rateLimitMeta.getResetSeconds()));
			run.cooldownReason = "WOM header cooldown";
		}
	}

	private long computeSpacingMillis(WiseOldManClient.RateLimitMeta rateLimitMeta)
	{
		long spacing = WOM_MIN_REQUEST_SPACING_MILLIS;
		if (rateLimitMeta == null)
		{
			return spacing;
		}

		int remaining = rateLimitMeta.getRemaining();
		long resetSeconds = rateLimitMeta.getResetSeconds();
		if (remaining > 0 && resetSeconds > 0)
		{
			long adaptiveSpacing = (long) Math.ceil((double) TimeUnit.SECONDS.toMillis(resetSeconds) / remaining);
			spacing = Math.max(spacing, adaptiveSpacing);
		}
		else if (remaining == 0 && resetSeconds > 0)
		{
			spacing = Math.max(spacing, TimeUnit.SECONDS.toMillis(resetSeconds));
		}

		return spacing;
	}

	private void finalizeHydrationRunLocked(HydrationRun run)
	{
		baselineCache = new ConcurrentHashMap<>(run.workingCache);
		Map<String, Long> prunedUnresolved = pruneUnresolvedCache(run.unresolvedCache, System.currentTimeMillis());
		unresolvedBaselineCache = new ConcurrentHashMap<>(prunedUnresolved);
		if (run.baselineDirty)
		{
			saveBaselineCache(run.workingCache);
			run.baselineDirty = false;
		}
		if (run.unresolvedDirty)
		{
			saveUnresolvedBaselineCache(prunedUnresolved);
			run.unresolvedDirty = false;
		}
		run.cooldownReason = null;
	}

	private void publishRunState(long runId)
	{
		List<PromotionRecord> computed;
		List<PromotionRecord> visibleRecords;
		List<PromotionRecord> displayRecords;
		String infoText;

		synchronized (hydrationLock)
		{
			HydrationRun run = activeHydrationRun;
			if (run == null || run.runId != runId)
			{
				return;
			}

			computed = buildComputedRecords(run);
			computed.sort(RECORD_COMPARATOR);
			visibleRecords = computed.stream()
				.filter(this::shouldDisplayRecord)
				.collect(Collectors.toList());

			displayRecords = visibleRecords;
			String refreshNote = run.refreshNote;
			if (displayRecords.isEmpty() && !computed.isEmpty())
			{
				displayRecords = computed;
				refreshNote = appendRefreshNote(refreshNote, "No members matched the current sidebar filters; showing all " + computed.size() + " members.");
			}

			infoText = buildSummary(computed, visibleRecords, refreshNote, buildHydrationNote(run));
			Map<String, String> actionable = buildActionableTargetRanks(visibleRecords);
			Map<String, String> actionableCurrent = buildActionableCurrentRanks(visibleRecords);
			allRecords = Collections.unmodifiableList(new ArrayList<>(computed));
			actionableTargetRanks = Collections.unmodifiableMap(actionable);
			actionableCurrentRanks = Collections.unmodifiableMap(actionableCurrent);
		}

		updateResults(displayRecords, infoText);
		notifyActionableMembers(visibleRecords);
	}

	private List<PromotionRecord> buildComputedRecords(HydrationRun run)
	{
		List<PromotionRecord> computed = new ArrayList<>();
		for (ClanRosterMember member : run.clanMembers)
		{
			computed.add(buildRecord(member, run.today, run.ladder, run.womAvailable, run.womMembers, run.workingCache));
		}
		return computed;
	}

	private String buildHydrationNote(HydrationRun run)
	{
		if (!run.womAvailable)
		{
			return null;
		}

		if (run.interrupted)
		{
			StringBuilder interruptedNote = new StringBuilder();
			interruptedNote.append("Hydration stopped ")
				.append(run.hydratedTasks)
				.append('/')
				.append(run.totalTasks);
			if (run.interruptedTasks > 0)
			{
				interruptedNote.append(" | remaining ").append(run.interruptedTasks);
			}
			if (run.interruptionReason != null && !run.interruptionReason.isBlank())
			{
				interruptedNote.append(" | ").append(run.interruptionReason);
			}
			return interruptedNote.toString();
		}

		int pending = run.pendingTasks.size();
		if (run.totalTasks <= 0)
		{
			if (run.suppressedByRetryWindow > 0)
			{
				return "Hydration paused by retry cooldown for " + run.suppressedByRetryWindow
					+ " members. Press Refresh to force a recheck now.";
			}
			return "Hydration up to date.";
		}

		boolean completed = pending == 0 && run.completedTasks >= run.totalTasks;
		if (completed)
		{
			int unresolved = Math.max(0, run.totalTasks - run.hydratedTasks);
			StringBuilder done = new StringBuilder();
			done.append("Hydration complete ")
				.append(run.hydratedTasks)
				.append('/')
				.append(run.totalTasks);
			if (unresolved > 0)
			{
				done.append(" | unresolved ").append(unresolved);
			}
			if (run.groupRequests > 0 || run.playerRequests > 0)
			{
				done.append(" | req G:")
					.append(run.groupRequests)
					.append(" P:")
					.append(run.playerRequests);
			}
			if (run.suppressedByRetryWindow > 0)
			{
				done.append(" | retry-suppressed ").append(run.suppressedByRetryWindow);
			}
			return done.toString();
		}

		StringBuilder note = new StringBuilder();
		note.append("Hydration ")
			.append(run.hydratedTasks)
			.append('/')
			.append(run.totalTasks)
			.append(" | pending ")
			.append(pending);
		if (run.inFlightTasks > 0)
		{
			note.append(" | active ").append(run.inFlightTasks);
		}
		if (run.deferredTasks > 0)
		{
			note.append(" | deferred ").append(run.deferredTasks);
		}
		if (run.groupRequests > 0 || run.playerRequests > 0)
		{
			note.append(" | req G:")
				.append(run.groupRequests)
				.append(" P:")
				.append(run.playerRequests);
		}
		if (run.cooldownReason != null && !run.cooldownReason.isBlank())
		{
			long remainingSeconds = Math.max(0L, TimeUnit.MILLISECONDS.toSeconds(run.cooldownUntilMillis - System.currentTimeMillis()));
			if (remainingSeconds > 0L)
			{
				note.append(" | ").append(run.cooldownReason).append(' ').append(remainingSeconds).append('s');
			}
		}
		if (run.fallbackPlayerTasks > 0)
		{
			note.append(" | fallback ").append(run.fallbackPlayerTasks);
		}
		if (run.suppressedByRetryWindow > 0)
		{
			note.append(" | retry-suppressed ").append(run.suppressedByRetryWindow);
		}
		return note.toString();
	}

	private GroupMembersResult fetchGroupMembers(int groupId, String womApiKey)
	{
		long now = System.currentTimeMillis();
		if (womGroupCacheId != groupId)
		{
			womGroupCacheId = groupId;
			womGroupCache = Collections.emptyMap();
			womGroupCacheExpiresAtMillis = 0L;
			womGroupCooldownUntilMillis = 0L;
		}

		if (!womGroupCache.isEmpty() && now < womGroupCacheExpiresAtMillis)
		{
			return GroupMembersResult.available(womGroupCache, null, WiseOldManClient.RateLimitMeta.empty());
		}

		long remainingCooldownSeconds = Math.max(0L, TimeUnit.MILLISECONDS.toSeconds(womGroupCooldownUntilMillis - now));
		if (remainingCooldownSeconds > 0L)
		{
			if (!womGroupCache.isEmpty())
			{
				return GroupMembersResult.available(
					womGroupCache,
					"WOM group cooldown active; using cached group data for about " + remainingCooldownSeconds + "s.",
					WiseOldManClient.RateLimitMeta.empty()
				);
			}

			return GroupMembersResult.unavailable(
				"WOM group cooldown active; retrying group fetch in about " + remainingCooldownSeconds + "s.",
				WiseOldManClient.RateLimitMeta.empty()
			);
		}

		if (!tryConsumeWomRequestBudget(womApiKey))
		{
			long retryAfterSeconds = getWomBudgetRetryAfterSeconds(womApiKey);
			if (!womGroupCache.isEmpty())
			{
				return GroupMembersResult.available(
					womGroupCache,
					"WOM request budget active; using cached group data for about " + retryAfterSeconds + "s.",
					WiseOldManClient.RateLimitMeta.empty()
				);
			}

			return GroupMembersResult.unavailable(
				"WOM request budget active; retrying group fetch in about " + retryAfterSeconds + "s.",
				WiseOldManClient.RateLimitMeta.empty()
			);
		}

		try
		{
			WiseOldManClient.GroupMembersResponse response = wiseOldManClient.fetchGroupMembersWithMeta(groupId, womApiKey);
			Map<String, WiseOldManClient.GroupMember> fetched = response.getMembers();
			womGroupCache = Collections.unmodifiableMap(new HashMap<>(fetched));
			womGroupCacheExpiresAtMillis = now + TimeUnit.SECONDS.toMillis(WOM_GROUP_CACHE_TTL_SECONDS);
			womGroupCooldownUntilMillis = 0L;
			return GroupMembersResult.available(womGroupCache, null, response.getRateLimitMeta());
		}
		catch (WiseOldManClient.RateLimitException ex)
		{
			long retryAfterSeconds = ex.getRetryAfterSeconds() > 0 ? ex.getRetryAfterSeconds() : DEFAULT_RATE_LIMIT_COOLDOWN_SECONDS;
			womGroupCooldownUntilMillis = now + TimeUnit.SECONDS.toMillis(retryAfterSeconds);
			LOG.debug("Wise Old Man group requests are rate limited for {} seconds", retryAfterSeconds);
			if (!womGroupCache.isEmpty())
			{
				return GroupMembersResult.available(
					womGroupCache,
					"WOM group rate limited; using cached group data for about " + retryAfterSeconds + "s.",
					ex.getRateLimitMeta()
				);
			}

			return GroupMembersResult.unavailable("WOM group rate limited; retry in about " + retryAfterSeconds + "s.", ex.getRateLimitMeta());
		}
		catch (IOException ex)
		{
			LOG.debug("Wise Old Man group fetch failed", ex);
			if (!womGroupCache.isEmpty())
			{
				return GroupMembersResult.available(
					womGroupCache,
					"Could not refresh Wise Old Man group data; using cached group snapshot.",
					WiseOldManClient.RateLimitMeta.empty()
				);
			}

			return GroupMembersResult.unavailable("Could not fetch Wise Old Man group data.", WiseOldManClient.RateLimitMeta.empty());
		}
	}

	private PromotionRecord buildRecord(
		ClanRosterMember member,
		LocalDate today,
		List<RankRequirement> ladder,
		boolean womAvailable,
		Map<String, WiseOldManClient.GroupMember> womMembers,
		Map<String, BaselineCacheEntry> workingCache)
	{
		String normalizedName = WiseOldManClient.normalizeName(member.username);
		PromotionDecision baseDecision = PromotionEvaluator.evaluate(
			member.currentRank,
			member.joinDate,
			null,
			today,
			ladder,
			false,
			config.recommendRankSkips()
		);
		int monthsInClan = baseDecision.getMonthsInClan();
		String nextRankCandidate = baseDecision.getNextRankCandidate();

		if (baseDecision.getStatus() == PromotionStatus.UNKNOWN_RANK)
		{
			return new PromotionRecord(member.username, normalizedName, member.currentRank, member.joinDate, monthsInClan,
				null, null, null, null, null, PromotionStatus.UNKNOWN_RANK, false);
		}

		if (nextRankCandidate == null)
		{
			return new PromotionRecord(member.username, normalizedName, member.currentRank, member.joinDate, monthsInClan,
				null, null, null, null, null, PromotionStatus.NOT_READY, false);
		}

		if (!womAvailable)
		{
			return new PromotionRecord(member.username, normalizedName, member.currentRank, member.joinDate, monthsInClan,
				null, null, null, null, nextRankCandidate, PromotionStatus.XP_NOT_FETCHED, false);
		}

		WiseOldManClient.GroupMember womMember = womMembers.get(normalizedName);
		if (womMember == null)
		{
			return new PromotionRecord(member.username, normalizedName, member.currentRank, member.joinDate, monthsInClan,
				null, null, null, null, nextRankCandidate, PromotionStatus.NO_WOM_MATCH, false);
		}

		Long currentXp = womMember.getExp();
		BaselineCacheEntry baselineEntry = member.joinDate == null ? null : workingCache.get(buildBaselineCacheKey(normalizedName, member.joinDate));
		if (baselineEntry == null)
		{
			return new PromotionRecord(member.username, normalizedName, member.currentRank, member.joinDate, monthsInClan,
				null, null, currentXp, null, nextRankCandidate, PromotionStatus.XP_NOT_FETCHED, false);
		}

		Long baselineXp = baselineEntry.getBaselineXp();
		Long xpGained = currentXp == null ? null : Math.max(0L, currentXp - baselineXp);
		PromotionDecision finalDecision = PromotionEvaluator.evaluate(
			member.currentRank,
			member.joinDate,
			xpGained,
			today,
			ladder,
			baselineEntry.isApproximate(),
			config.recommendRankSkips()
		);
		return new PromotionRecord(
			member.username,
			normalizedName,
			member.currentRank,
			member.joinDate,
			finalDecision.getMonthsInClan(),
			baselineEntry.getBaselineDateValue(),
			baselineXp,
			currentXp,
			xpGained,
			finalDecision.getNextRankCandidate(),
			finalDecision.getStatus(),
			baselineEntry.isApproximate()
		);
	}

	private List<ClanRosterMember> snapshotClanRoster() throws Exception
	{
		CompletableFuture<List<ClanRosterMember>> future = new CompletableFuture<>();
		clientThread.invoke(() ->
		{
			try
			{
				future.complete(readClanRoster());
			}
			catch (Throwable ex)
			{
				future.completeExceptionally(ex);
			}
		});

		return future.get(5, TimeUnit.SECONDS);
	}

	private List<ClanRosterMember> readClanRoster()
	{
		ClanSettings clan = getClanSettings();
		if (clan == null || clan.getMembers() == null)
		{
			return Collections.emptyList();
		}

		List<ClanRosterMember> members = new ArrayList<>();
		for (ClanMember member : clan.getMembers())
		{
			String username = member.getName();
			if (username == null || username.isBlank())
			{
				continue;
			}

			members.add(new ClanRosterMember(
				username,
				resolveCurrentRankName(clan, member),
				resolveJoinDate(member)
			));
		}

		return members;
	}

	private ClanSettings getClanSettings()
	{
		try
		{
			ClanSettings clanSettings = client.getClanSettings(ClanID.CLAN);
			if (clanSettings != null && clanSettings.getMembers() != null)
			{
				return clanSettings;
			}
		}
		catch (Throwable ignored)
		{
		}

		try
		{
			ClanSettings clanSettings = client.getGuestClanSettings();
			if (clanSettings != null && clanSettings.getMembers() != null)
			{
				return clanSettings;
			}
		}
		catch (Throwable ignored)
		{
		}

		try
		{
			ClanSettings clanSettings = client.getClanSettings(ClanID.GROUP_IRONMAN);
			if (clanSettings != null && clanSettings.getMembers() != null)
			{
				return clanSettings;
			}
		}
		catch (Throwable ignored)
		{
		}

		try
		{
			ClanSettings clanSettings = client.getClanSettings();
			if (clanSettings != null && clanSettings.getMembers() != null)
			{
				return clanSettings;
			}
		}
		catch (Throwable ignored)
		{
		}

		return null;
	}

	private LocalDate resolveJoinDate(ClanMember member)
	{
		try
		{
			return member.getJoinDate();
		}
		catch (Throwable ignored)
		{
			return null;
		}
	}

	private String resolveCurrentRankName(ClanSettings clanSettings, ClanMember member)
	{
		try
		{
			ClanTitle title = clanSettings.titleForRank(member.getRank());
			if (title != null && title.getName() != null && !title.getName().isBlank())
			{
				return title.getName().trim();
			}
		}
		catch (Throwable ignored)
		{
		}

		ClanRank rank = member.getRank();
		if (rank == null)
		{
			return "Unknown";
		}

		String lower = rank.toString().toLowerCase(Locale.ENGLISH).replace('_', ' ');
		StringBuilder builder = new StringBuilder(lower.length());
		boolean capitalizeNext = true;
		for (char c : lower.toCharArray())
		{
			if (c == ' ')
			{
				capitalizeNext = true;
				builder.append(c);
			}
			else if (capitalizeNext)
			{
				builder.append(Character.toUpperCase(c));
				capitalizeNext = false;
			}
			else
			{
				builder.append(c);
			}
		}
		return builder.toString();
	}

	private boolean shouldDisplayRecord(PromotionRecord record)
	{
		Set<String> eligibleCurrentRanks = parseConfigSet(config.eligibleCurrentRanks());
		Set<String> ignoredCurrentRanks = parseConfigSet(config.ignoredCurrentRanks());
		Set<String> ignoredTargetRanks = parseConfigSet(config.ignoredTargetRanks());
		Set<String> ignoredUsers = parseConfigSet(config.ignoredUsers());

		if (ignoredUsers.contains(WiseOldManClient.normalizeName(record.getUsername())))
		{
			return false;
		}

		String normalizedCurrentRank = normalizeRank(record.getCurrentRank());
		if (!eligibleCurrentRanks.isEmpty() && !eligibleCurrentRanks.contains(normalizedCurrentRank))
		{
			return false;
		}

		if (ignoredCurrentRanks.contains(normalizedCurrentRank))
		{
			return false;
		}

		String targetRank = normalizeRank(record.getNextRankCandidate());
		return targetRank.isEmpty() || !ignoredTargetRanks.contains(targetRank);
	}

	private Map<String, String> buildActionableTargetRanks(List<PromotionRecord> visibleRecords)
	{
		Map<String, String> actionable = new LinkedHashMap<>();
		for (PromotionRecord record : visibleRecords)
		{
			if (record.isActionable())
			{
				actionable.put(record.getNormalizedUsername(), record.getNextRankCandidate());
			}
		}
		return actionable;
	}

	private Map<String, String> buildActionableCurrentRanks(List<PromotionRecord> visibleRecords)
	{
		Map<String, String> actionable = new LinkedHashMap<>();
		for (PromotionRecord record : visibleRecords)
		{
			if (record.isActionable())
			{
				actionable.put(record.getNormalizedUsername(), record.getCurrentRank());
			}
		}
		return actionable;
	}

	private void notifyActionableMembers(List<PromotionRecord> visibleRecords)
	{
		LocalDate today = LocalDate.now();
		if (!today.equals(lastNotificationDate))
		{
			notifiedToday.clear();
			lastNotificationDate = today;
		}

		if (config.muteNotifications())
		{
			return;
		}

		for (PromotionRecord record : visibleRecords)
		{
			if (!record.isActionable())
			{
				continue;
			}

			String token = record.getStatus().name() + ':' + record.getNextRankCandidate();
			if (Objects.equals(notifiedToday.get(record.getNormalizedUsername()), token))
			{
				continue;
			}

			notifier.notify(buildNotificationMessage(record));
			notifiedToday.put(record.getNormalizedUsername(), token);
		}
	}

	private String buildNotificationMessage(PromotionRecord record)
	{
		String suffix = record.getStatus() == PromotionStatus.APPROXIMATE_BASELINE ? " (approximate baseline)" : "";
		return "[Clan Promotion] "
			+ record.getUsername()
			+ " is ready for "
			+ record.getNextRankCandidate()
			+ suffix
			+ " after "
			+ record.getMonthsInClan()
			+ " months and "
			+ PromotionEvaluator.formatXp(record.getXpGained())
			+ " gained.";
	}

	private String buildSummary(List<PromotionRecord> all, List<PromotionRecord> visible, String refreshNote, String hydrationNote)
	{
		long readyCount = visible.stream().filter(record -> record.getStatus() == PromotionStatus.READY).count();
		long approximateCount = visible.stream().filter(record -> record.getStatus() == PromotionStatus.APPROXIMATE_BASELINE).count();
		long missingCount = visible.stream().filter(record ->
			record.getStatus() == PromotionStatus.NO_WOM_MATCH
				|| record.getStatus() == PromotionStatus.XP_NOT_FETCHED
				|| record.getStatus() == PromotionStatus.UNKNOWN_RANK).count();

		String summary = String.format(
			Locale.ENGLISH,
			"Ready: %d | Approx: %d | Missing: %d | Visible: %d/%d",
			readyCount,
			approximateCount,
			missingCount,
			visible.size(),
			all.size());

		String combined = refreshNote == null || refreshNote.isBlank() ? summary : refreshNote + " | " + summary;
		return hydrationNote == null || hydrationNote.isBlank() ? combined : combined + " | " + hydrationNote;
	}

	private void updateResults(List<PromotionRecord> visibleRecords, String infoText)
	{
		if (panel != null)
		{
			panel.setInfoText(infoText);
			panel.setRecords(visibleRecords, config.maxDisplayedInPanel());
		}
	}

	private void updateInfoTextOnly(String infoText)
	{
		if (panel != null)
		{
			panel.setInfoText(infoText);
		}
	}

	private String appendRefreshNote(String existing, String note)
	{
		if (note == null || note.isBlank())
		{
			return existing;
		}

		if (existing == null || existing.isBlank())
		{
			return note;
		}

		return existing + " | " + note;
	}

	private void copyCsvToClipboard()
	{
		String csv = CsvExporter.toCsv(allRecords);
		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(csv), null);
		if (panel != null)
		{
			panel.setInfoText("Copied CSV for " + allRecords.size() + " members.");
		}
	}

	private void saveCsvToFile()
	{
		JFileChooser chooser = new JFileChooser();
		chooser.setSelectedFile(new java.io.File("clan-promotion-tracker-" + LocalDate.now() + ".csv"));
		int result = chooser.showSaveDialog(panel);
		if (result != JFileChooser.APPROVE_OPTION)
		{
			return;
		}

		try
		{
			Files.writeString(chooser.getSelectedFile().toPath(), CsvExporter.toCsv(allRecords), StandardCharsets.UTF_8);
			if (panel != null)
			{
				panel.setInfoText("Saved CSV to " + chooser.getSelectedFile().getName() + '.');
			}
		}
		catch (IOException ex)
		{
			LOG.warn("Failed to save CSV", ex);
			if (panel != null)
			{
				panel.setInfoText("Failed to save CSV: " + ex.getMessage());
			}
		}
	}

	private void copyPrettyReportToClipboard()
	{
		String report = PrettyExporter.toMarkdown(allRecords);
		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(report), null);
		if (panel != null)
		{
			panel.setInfoText("Copied pretty report for " + allRecords.size() + " members.");
		}
	}

	private void savePrettyReportToFile()
	{
		JFileChooser chooser = new JFileChooser();
		chooser.setSelectedFile(new java.io.File("clan-promotion-report-" + LocalDate.now() + ".md"));
		int result = chooser.showSaveDialog(panel);
		if (result != JFileChooser.APPROVE_OPTION)
		{
			return;
		}

		try
		{
			Files.writeString(chooser.getSelectedFile().toPath(), PrettyExporter.toMarkdown(allRecords), StandardCharsets.UTF_8);
			if (panel != null)
			{
				panel.setInfoText("Saved pretty report to " + chooser.getSelectedFile().getName() + '.');
			}
		}
		catch (IOException ex)
		{
			LOG.warn("Failed to save pretty report", ex);
			if (panel != null)
			{
				panel.setInfoText("Failed to save pretty report: " + ex.getMessage());
			}
		}
	}

	private void exportCacheToFile()
	{
		Map<String, BaselineCacheEntry> baselineSnapshot = new HashMap<>(baselineCache);
		Map<String, Long> unresolvedSnapshot = pruneUnresolvedCache(unresolvedBaselineCache, System.currentTimeMillis());
		CacheExportPayload payload = new CacheExportPayload(
			CACHE_EXPORT_FORMAT_VERSION,
			LocalDate.now().toString(),
			baselineSnapshot,
			unresolvedSnapshot
		);

		JFileChooser chooser = new JFileChooser();
		chooser.setSelectedFile(new java.io.File("clan-promotion-cache-" + LocalDate.now() + ".json"));
		int result = chooser.showSaveDialog(panel);
		if (result != JFileChooser.APPROVE_OPTION)
		{
			return;
		}

		try
		{
			Files.writeString(chooser.getSelectedFile().toPath(), gson.toJson(payload), StandardCharsets.UTF_8);
			if (panel != null)
			{
				panel.setInfoText(
					"Exported cache: "
						+ baselineSnapshot.size()
						+ " baselines, "
						+ unresolvedSnapshot.size()
						+ " unresolved entries."
				);
			}
		}
		catch (IOException ex)
		{
			LOG.warn("Failed to export cache", ex);
			if (panel != null)
			{
				panel.setInfoText("Failed to export cache: " + ex.getMessage());
			}
		}
	}

	private void importCacheFromFile()
	{
		if (getActiveHydrationRunIdIfInProgress() > 0)
		{
			int confirmation = JOptionPane.showConfirmDialog(
				panel,
				"Hydration is currently running.\nStop hydration and continue importing cache?",
				"Interrupt Hydration",
				JOptionPane.YES_NO_OPTION,
				JOptionPane.WARNING_MESSAGE
			);
			if (confirmation != JOptionPane.YES_OPTION)
			{
				if (panel != null)
				{
					panel.setInfoText("Import cancelled. Hydration is still running.");
				}
				return;
			}

			if (!stopActiveHydrationRun("stopped for cache import", true))
			{
				if (panel != null)
				{
					panel.setInfoText("Could not stop hydration. Try again in a moment.");
				}
				return;
			}
		}

		JFileChooser chooser = new JFileChooser();
		int result = chooser.showOpenDialog(panel);
		if (result != JFileChooser.APPROVE_OPTION)
		{
			return;
		}

		try
		{
			String json = Files.readString(chooser.getSelectedFile().toPath(), StandardCharsets.UTF_8);
			CacheExportPayload payload = gson.fromJson(json, CacheExportPayload.class);
			if (payload == null)
			{
				if (panel != null)
				{
					panel.setInfoText("Import failed: invalid cache file.");
				}
				return;
			}

			Map<String, BaselineCacheEntry> importedBaselines = sanitizeImportedBaselineCache(payload.baselineCache);
			Map<String, Long> importedUnresolved = pruneUnresolvedCache(payload.unresolvedBaselineCache, System.currentTimeMillis());
			if (importedBaselines.isEmpty() && importedUnresolved.isEmpty())
			{
				if (panel != null)
				{
					panel.setInfoText("Import file contains no valid cache entries.");
				}
				return;
			}

			Map<String, BaselineCacheEntry> mergedBaseline;
			Map<String, Long> mergedUnresolved;
			synchronized (hydrationLock)
			{
				mergedBaseline = new HashMap<>(baselineCache);
				mergedBaseline.putAll(importedBaselines);

				mergedUnresolved = pruneUnresolvedCache(unresolvedBaselineCache, System.currentTimeMillis());
				for (Map.Entry<String, Long> entry : importedUnresolved.entrySet())
				{
					mergedUnresolved.merge(entry.getKey(), entry.getValue(), Math::max);
				}

				baselineCache = new ConcurrentHashMap<>(mergedBaseline);
				unresolvedBaselineCache = new ConcurrentHashMap<>(mergedUnresolved);
				saveBaselineCache(mergedBaseline);
				saveUnresolvedBaselineCache(mergedUnresolved);
			}

			if (panel != null)
			{
				panel.setInfoText(
					"Imported cache: "
						+ importedBaselines.size()
						+ " baselines, "
						+ importedUnresolved.size()
						+ " unresolved entries."
				);
			}
			refreshAsync(false);
		}
		catch (Exception ex)
		{
			LOG.warn("Failed to import cache", ex);
			if (panel != null)
			{
				panel.setInfoText("Failed to import cache: " + ex.getMessage());
			}
		}
	}

	private Map<String, BaselineCacheEntry> sanitizeImportedBaselineCache(Map<String, BaselineCacheEntry> imported)
	{
		if (imported == null || imported.isEmpty())
		{
			return Collections.emptyMap();
		}

		Map<String, BaselineCacheEntry> sanitized = new HashMap<>();
		for (Map.Entry<String, BaselineCacheEntry> entry : imported.entrySet())
		{
			String key = entry.getKey();
			BaselineCacheEntry value = entry.getValue();
			if (key == null || key.isBlank() || value == null)
			{
				continue;
			}

			if (value.getBaselineDateValue() == null)
			{
				continue;
			}

			sanitized.put(key.trim(), value);
		}
		return sanitized;
	}

	private void ignoreUserFromPanel(String username)
	{
		if (username == null || username.isBlank())
		{
			return;
		}

		Set<String> ignoredUsers = new LinkedHashSet<>();
		if (!config.ignoredUsers().isBlank())
		{
			for (String value : config.ignoredUsers().split("[,\\n\\r]+"))
			{
				String trimmed = value.trim();
				if (!trimmed.isEmpty())
				{
					ignoredUsers.add(trimmed);
				}
			}
		}

		boolean exists = ignoredUsers.stream().anyMatch(value -> value.equalsIgnoreCase(username));
		if (!exists)
		{
			ignoredUsers.add(username);
			configManager.setConfiguration(CONFIG_GROUP, "ignoredUsers", String.join(", ", ignoredUsers));
		}
	}

	private void rescheduleAutoRefresh()
	{
		if (autoRefreshFuture != null)
		{
			autoRefreshFuture.cancel(false);
			autoRefreshFuture = null;
		}

		if (!config.autoRefreshEnabled())
		{
			return;
		}

		int refreshInterval = Math.max(1, config.refreshIntervalMinutes());
		autoRefreshFuture = scheduler.scheduleAtFixedRate(() -> refreshAsync(false), refreshInterval, refreshInterval, TimeUnit.MINUTES);
	}

	private BufferedImage loadPluginIcon()
	{
		try
		{
			BufferedImage image = ImageUtil.loadImageResource(ClanPromotionTrackerPlugin.class, "icon.png");
			if (image != null)
			{
				return image;
			}
		}
		catch (Exception ignored)
		{
		}

		return new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
	}

	private Map<String, BaselineCacheEntry> loadBaselineCache()
	{
		String json = configManager.getConfiguration(CONFIG_GROUP, BASELINE_CACHE_KEY);
		if (json == null || json.isBlank())
		{
			return Collections.emptyMap();
		}

		Type type = new TypeToken<Map<String, BaselineCacheEntry>>() {}.getType();
		Map<String, BaselineCacheEntry> cache = gson.fromJson(json, type);
		return cache == null ? Collections.emptyMap() : cache;
	}

	private void saveBaselineCache(Map<String, BaselineCacheEntry> cache)
	{
		configManager.setConfiguration(CONFIG_GROUP, BASELINE_CACHE_KEY, gson.toJson(cache));
	}

	private Map<String, Long> loadUnresolvedBaselineCache()
	{
		String json = configManager.getConfiguration(CONFIG_GROUP, UNRESOLVED_BASELINE_CACHE_KEY);
		if (json == null || json.isBlank())
		{
			return Collections.emptyMap();
		}

		Type type = new TypeToken<Map<String, Long>>() {}.getType();
		Map<String, Long> cache = gson.fromJson(json, type);
		return cache == null ? Collections.emptyMap() : cache;
	}

	private void saveUnresolvedBaselineCache(Map<String, Long> cache)
	{
		configManager.setConfiguration(CONFIG_GROUP, UNRESOLVED_BASELINE_CACHE_KEY, gson.toJson(cache));
	}

	private Map<String, Long> pruneUnresolvedCache(Map<String, Long> source, long now)
	{
		if (source == null || source.isEmpty())
		{
			return new HashMap<>();
		}

		Map<String, Long> pruned = new HashMap<>();
		for (Map.Entry<String, Long> entry : source.entrySet())
		{
			Long retryAt = entry.getValue();
			if (retryAt != null && retryAt > now)
			{
				pruned.put(entry.getKey(), retryAt);
			}
		}
		return pruned;
	}

	private boolean tryConsumeWomRequestBudget(String womApiKey)
	{
		return womRequestBudget.tryAcquire(getWomRequestBudgetLimit(womApiKey));
	}

	private long getWomBudgetRetryAfterSeconds(String womApiKey)
	{
		long retryAfterSeconds = womRequestBudget.retryAfterSeconds(getWomRequestBudgetLimit(womApiKey));
		return retryAfterSeconds <= 0L ? 1L : retryAfterSeconds;
	}

	private int getWomRequestBudgetLimit(String womApiKey)
	{
		return womApiKey == null || womApiKey.isBlank()
			? WOM_MAX_REQUESTS_PER_WINDOW
			: WOM_MAX_REQUESTS_PER_WINDOW_WITH_API_KEY;
	}

	private String buildBaselineCacheKey(String normalizedUsername, LocalDate joinDate)
	{
		return normalizedUsername + '|' + (joinDate == null ? "unknown" : joinDate.toString());
	}

	private Set<String> parseConfigSet(String rawValue)
	{
		if (rawValue == null || rawValue.isBlank())
		{
			return Collections.emptySet();
		}

		Set<String> values = new HashSet<>();
		for (String part : rawValue.split("[,\\n\\r]+"))
		{
			String trimmed = part.trim();
			if (!trimmed.isEmpty())
			{
				values.add(normalizeRank(trimmed));
			}
		}
		return values;
	}

	private String normalizeRank(String rank)
	{
		return rank == null ? "" : rank.trim().toLowerCase(Locale.ENGLISH);
	}

	static boolean shouldUseGroupCohortStrategy(int cohortSize)
	{
		return cohortSize >= GROUP_COHORT_THRESHOLD;
	}

	private static int statusPriority(PromotionStatus status)
	{
		switch (status)
		{
			case READY:
				return 0;
			case APPROXIMATE_BASELINE:
				return 1;
			case NO_WOM_MATCH:
				return 2;
			case XP_NOT_FETCHED:
				return 3;
			case UNKNOWN_RANK:
				return 4;
			case NOT_READY:
			default:
				return 5;
		}
	}

	private enum HydrationStrategy
	{
		GROUP_COHORT,
		PLAYER_MEMBER
	}

	private static final class HydrationTask
	{
		private final HydrationStrategy strategy;
		private final LocalDate joinDate;
		private final List<ClanRosterMember> cohortMembers;
		private final ClanRosterMember member;
		private final String cacheKey;
		private final boolean fallback;

		private HydrationTask(
			HydrationStrategy strategy,
			LocalDate joinDate,
			List<ClanRosterMember> cohortMembers,
			ClanRosterMember member,
			String cacheKey,
			boolean fallback)
		{
			this.strategy = strategy;
			this.joinDate = joinDate;
			this.cohortMembers = cohortMembers;
			this.member = member;
			this.cacheKey = cacheKey;
			this.fallback = fallback;
		}

		private static HydrationTask group(LocalDate joinDate, List<ClanRosterMember> members)
		{
			return new HydrationTask(
				HydrationStrategy.GROUP_COHORT,
				joinDate,
				Collections.unmodifiableList(new ArrayList<>(members)),
				null,
				null,
				false
			);
		}

		private static HydrationTask player(ClanRosterMember member, String cacheKey, boolean fallback)
		{
			return new HydrationTask(
				HydrationStrategy.PLAYER_MEMBER,
				member.joinDate,
				Collections.emptyList(),
				member,
				cacheKey,
				fallback
			);
		}
	}

	private static final class HydrationRun
	{
		private final long runId;
		private final LocalDate today;
		private final List<RankRequirement> ladder;
		private final List<ClanRosterMember> clanMembers;
		private final Map<String, WiseOldManClient.GroupMember> womMembers;
		private final boolean womAvailable;
		private final String womApiKey;
		private final int womGroupId;
		private final String refreshNote;
		private final boolean forceRetrySuppressed;
		private final Map<String, BaselineCacheEntry> workingCache;
		private final Map<String, Long> unresolvedCache;
		private final Deque<HydrationTask> pendingTasks = new ArrayDeque<>();
		private final Set<String> queuedPlayerKeys = new HashSet<>();

		private int totalTasks;
		private int completedTasks;
		private int deferredTasks;
		private int hydratedTasks;
		private int fallbackPlayerTasks;
		private int groupRequests;
		private int playerRequests;
		private int suppressedByRetryWindow;
		private int inFlightTasks;
		private boolean baselineDirty;
		private boolean unresolvedDirty;
		private boolean interrupted;
		private int interruptedTasks;
		private String interruptionReason;
		private long nextRequestAtMillis;
		private long cooldownUntilMillis;
		private String cooldownReason;
		private WiseOldManClient.RateLimitMeta lastRateLimitMeta = WiseOldManClient.RateLimitMeta.empty();

		private HydrationRun(
			long runId,
			LocalDate today,
			List<RankRequirement> ladder,
			List<ClanRosterMember> clanMembers,
			Map<String, WiseOldManClient.GroupMember> womMembers,
			boolean womAvailable,
			String womApiKey,
			int womGroupId,
			String refreshNote,
			boolean forceRetrySuppressed,
			Map<String, BaselineCacheEntry> workingCache,
			Map<String, Long> unresolvedCache)
		{
			this.runId = runId;
			this.today = today;
			this.ladder = Collections.unmodifiableList(new ArrayList<>(ladder));
			this.clanMembers = clanMembers;
			this.womMembers = womMembers;
			this.womAvailable = womAvailable;
			this.womApiKey = womApiKey;
			this.womGroupId = womGroupId;
			this.refreshNote = refreshNote;
			this.forceRetrySuppressed = forceRetrySuppressed;
			this.workingCache = workingCache;
			this.unresolvedCache = unresolvedCache;
		}
	}

	private static final class GroupMembersResult
	{
		private final Map<String, WiseOldManClient.GroupMember> members;
		private final boolean available;
		private final String note;
		private final WiseOldManClient.RateLimitMeta rateLimitMeta;

		private GroupMembersResult(
			Map<String, WiseOldManClient.GroupMember> members,
			boolean available,
			String note,
			WiseOldManClient.RateLimitMeta rateLimitMeta)
		{
			this.members = members;
			this.available = available;
			this.note = note;
			this.rateLimitMeta = rateLimitMeta == null ? WiseOldManClient.RateLimitMeta.empty() : rateLimitMeta;
		}

		private static GroupMembersResult available(
			Map<String, WiseOldManClient.GroupMember> members,
			String note,
			WiseOldManClient.RateLimitMeta rateLimitMeta)
		{
			return new GroupMembersResult(members == null ? Collections.emptyMap() : members, true, note, rateLimitMeta);
		}

		private static GroupMembersResult unavailable(String note, WiseOldManClient.RateLimitMeta rateLimitMeta)
		{
			return new GroupMembersResult(Collections.emptyMap(), false, note, rateLimitMeta);
		}

		private Map<String, WiseOldManClient.GroupMember> getMembers()
		{
			return members;
		}

		private boolean isAvailable()
		{
			return available;
		}

		private String getNote()
		{
			return note;
		}

		private WiseOldManClient.RateLimitMeta getRateLimitMeta()
		{
			return rateLimitMeta;
		}
	}

	private static final class CacheExportPayload
	{
		private int formatVersion;
		private String exportedAt;
		private Map<String, BaselineCacheEntry> baselineCache;
		private Map<String, Long> unresolvedBaselineCache;

		private CacheExportPayload()
		{
		}

		private CacheExportPayload(
			int formatVersion,
			String exportedAt,
			Map<String, BaselineCacheEntry> baselineCache,
			Map<String, Long> unresolvedBaselineCache)
		{
			this.formatVersion = formatVersion;
			this.exportedAt = exportedAt;
			this.baselineCache = baselineCache;
			this.unresolvedBaselineCache = unresolvedBaselineCache;
		}
	}

	private static final class ClanRosterMember
	{
		private final String username;
		private final String currentRank;
		private final LocalDate joinDate;

		private ClanRosterMember(String username, String currentRank, LocalDate joinDate)
		{
			this.username = username;
			this.currentRank = currentRank;
			this.joinDate = joinDate;
		}
	}
}
