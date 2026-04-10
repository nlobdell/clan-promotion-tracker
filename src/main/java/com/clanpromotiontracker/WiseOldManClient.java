package com.clanpromotiontracker;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import javax.inject.Inject;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

final class WiseOldManClient
{
	private static final String API_BASE_URL = "https://api.wiseoldman.net/v2";

	private final OkHttpClient okHttpClient;
	@SuppressWarnings("unused")
	private final Gson gson;

	@Inject
	WiseOldManClient(OkHttpClient okHttpClient, Gson gson)
	{
		this.okHttpClient = okHttpClient;
		this.gson = gson;
	}

	Map<String, GroupMember> fetchGroupMembers(int groupId) throws IOException
	{
		return fetchGroupMembers(groupId, null);
	}

	Map<String, GroupMember> fetchGroupMembers(int groupId, String apiKey) throws IOException
	{
		return fetchGroupMembersWithMeta(groupId, apiKey).getMembers();
	}

	GroupMembersResponse fetchGroupMembersWithMeta(int groupId, String apiKey) throws IOException
	{
		Request.Builder builder = new Request.Builder()
			.url(API_BASE_URL + "/groups/" + groupId)
			.header("Accept", "application/json")
			.header("User-Agent", "RuneLite-ClanPromotionTracker");
		if (apiKey != null && !apiKey.isBlank())
		{
			builder.header("x-api-key", apiKey.trim());
		}

		Request request = builder.build();
		ApiResult result = execute(request);
		return new GroupMembersResponse(parseGroupMembers(result.getBody()), result.getRateLimitMeta());
	}

	BaselineCacheEntry fetchBaseline(String username, LocalDate joinDate, LocalDate today) throws IOException
	{
		return fetchBaseline(username, joinDate, today, null);
	}

	BaselineCacheEntry fetchBaseline(String username, LocalDate joinDate, LocalDate today, String apiKey) throws IOException
	{
		return fetchBaselineWithMeta(username, joinDate, today, apiKey).getBaseline();
	}

	BaselineResponse fetchBaselineWithMeta(String username, LocalDate joinDate, LocalDate today, String apiKey) throws IOException
	{
		if (username == null || username.isBlank() || joinDate == null || today == null)
		{
			return new BaselineResponse(null, RateLimitMeta.empty(), false);
		}

		String encodedUsername = URLEncoder.encode(username, StandardCharsets.UTF_8.toString()).replace("+", "%20");
		String url = API_BASE_URL
			+ "/players/" + encodedUsername
			+ "/gained?startDate=" + joinDate
			+ "&endDate=" + today;

		Request.Builder builder = new Request.Builder()
			.url(url)
			.header("Accept", "application/json")
			.header("User-Agent", "RuneLite-ClanPromotionTracker");
		if (apiKey != null && !apiKey.isBlank())
		{
			builder.header("x-api-key", apiKey.trim());
		}
		Request request = builder.build();

		ApiResult result = execute(request);
		OverallGains gains = parseOverallGains(result.getBody());
		boolean nullWindow = hasNullOverallWindow(result.getBody());
		return new BaselineResponse(selectBaseline(gains, joinDate), result.getRateLimitMeta(), nullWindow && gains == null);
	}

	GroupGainsResponse fetchGroupGains(int groupId, String metric, LocalDate startDate, LocalDate endDate, int limit, String apiKey) throws IOException
	{
		if (groupId <= 0 || metric == null || metric.isBlank() || startDate == null || endDate == null)
		{
			return new GroupGainsResponse(new HashMap<>(), RateLimitMeta.empty());
		}

		String encodedMetric = URLEncoder.encode(metric, StandardCharsets.UTF_8.toString()).replace("+", "%20");
		StringBuilder urlBuilder = new StringBuilder(API_BASE_URL)
			.append("/groups/")
			.append(groupId)
			.append("/gained?metric=")
			.append(encodedMetric)
			.append("&startDate=")
			.append(startDate)
			.append("&endDate=")
			.append(endDate);
		if (limit > 0)
		{
			urlBuilder.append("&limit=").append(limit);
		}

		Request.Builder builder = new Request.Builder()
			.url(urlBuilder.toString())
			.header("Accept", "application/json")
			.header("User-Agent", "RuneLite-ClanPromotionTracker");
		if (apiKey != null && !apiKey.isBlank())
		{
			builder.header("x-api-key", apiKey.trim());
		}

		Request request = builder.build();
		ApiResult result = execute(request);
		return new GroupGainsResponse(parseGroupGains(result.getBody()), result.getRateLimitMeta());
	}

	static Map<String, GroupMember> parseGroupMembers(String json)
	{
		Map<String, GroupMember> members = new HashMap<>();
		JsonObject root = new JsonParser().parse(json).getAsJsonObject();
		JsonArray memberships = root.getAsJsonArray("memberships");
		if (memberships == null)
		{
			return members;
		}

		for (JsonElement membershipElement : memberships)
		{
			JsonObject membership = membershipElement.getAsJsonObject();
			JsonObject player = membership.getAsJsonObject("player");
			if (player == null)
			{
				continue;
			}

			String username = getAsString(player, "username");
			String displayName = getAsString(player, "displayName");
			String currentName = displayName.isBlank() ? username : displayName;
			if (currentName.isBlank())
			{
				continue;
			}

			String lookupName = username.isBlank() ? currentName : username;
			GroupMember groupMember = new GroupMember(currentName, lookupName, getAsLong(player, "exp"));
			members.put(normalizeName(currentName), groupMember);
			if (!username.isBlank())
			{
				members.putIfAbsent(normalizeName(username), groupMember);
			}
		}

		return members;
	}

	static Map<String, GroupGainEntry> parseGroupGains(String json)
	{
		Map<String, GroupGainEntry> gains = new HashMap<>();
		JsonElement rootElement = new JsonParser().parse(json);
		if (!rootElement.isJsonArray())
		{
			return gains;
		}

		JsonArray entries = rootElement.getAsJsonArray();
		for (JsonElement entryElement : entries)
		{
			if (entryElement == null || !entryElement.isJsonObject())
			{
				continue;
			}

			JsonObject entry = entryElement.getAsJsonObject();
			JsonObject player = getAsObject(entry, "player");
			if (player == null)
			{
				continue;
			}

			String username = getAsString(player, "username");
			String displayName = getAsString(player, "displayName");
			String currentName = displayName.isBlank() ? username : displayName;
			if (currentName.isBlank())
			{
				continue;
			}

			JsonObject data = getAsObject(entry, "data");
			LocalDate startDate = parseIsoDate(getAsString(entry, "startDate"));
			long startXp = getAsLong(data, "start");
			long endXp = getAsLong(data, "end");
			long gainedXp = getAsLong(data, "gained");
			GroupGainEntry gainEntry = new GroupGainEntry(currentName, startDate, startXp, endXp, gainedXp);
			gains.put(normalizeName(currentName), gainEntry);
			if (!username.isBlank())
			{
				gains.putIfAbsent(normalizeName(username), gainEntry);
			}
		}

		return gains;
	}

	static OverallGains parseOverallGains(String json)
	{
		JsonObject root = new JsonParser().parse(json).getAsJsonObject();
		String startsAt = getAsString(root, "startsAt");
		if (startsAt.isBlank())
		{
			return null;
		}

		JsonObject data = getAsObject(root, "data");
		JsonObject skills = getAsObject(data, "skills");
		JsonObject overall = getAsObject(skills, "overall");
		JsonObject experience = getAsObject(overall, "experience");
		Long startXp = getAsLongOrNull(experience, "start");
		Long endXp = getAsLongOrNull(experience, "end");
		if (startXp == null || endXp == null)
		{
			return null;
		}

		try
		{
			return new OverallGains(
				OffsetDateTime.parse(startsAt).toLocalDate(),
				startXp,
				endXp,
				Math.max(0L, endXp - startXp)
			);
		}
		catch (RuntimeException ex)
		{
			return null;
		}
	}

	static BaselineCacheEntry selectBaseline(OverallGains gains, LocalDate joinDate)
	{
		if (gains == null || gains.getStartDate() == null)
		{
			return null;
		}

		return new BaselineCacheEntry(gains.getStartDate(), gains.getStartXp(), !gains.getStartDate().equals(joinDate));
	}

	static String normalizeName(String name)
	{
		if (name == null)
		{
			return "";
		}

		String lower = name.toLowerCase(Locale.ENGLISH).trim();
		StringBuilder normalized = new StringBuilder(lower.length());
		lower.codePoints()
			.filter(Character::isLetterOrDigit)
			.forEach(normalized::appendCodePoint);
		return normalized.toString();
	}

	private ApiResult execute(Request request) throws IOException
	{
		try (Response response = okHttpClient.newCall(request).execute())
		{
			RateLimitMeta rateLimitMeta = parseRateLimitMeta(response);
			if (!response.isSuccessful())
			{
				if (response.code() == 429)
				{
					throw new RateLimitException("Wise Old Man request failed with HTTP 429", parseRetryAfterSeconds(response), rateLimitMeta);
				}

				throw new IOException("Wise Old Man request failed with HTTP " + response.code());
			}

			if (response.body() == null)
			{
				throw new IOException("Wise Old Man returned an empty response body");
			}

			return new ApiResult(response.body().string(), rateLimitMeta);
		}
	}

	private static boolean hasNullOverallWindow(String json)
	{
		JsonObject root = new JsonParser().parse(json).getAsJsonObject();
		String startsAt = getAsString(root, "startsAt");
		return startsAt.isBlank();
	}

	private static LocalDate parseIsoDate(String value)
	{
		if (value == null || value.isBlank())
		{
			return null;
		}

		try
		{
			return OffsetDateTime.parse(value).toLocalDate();
		}
		catch (RuntimeException ex)
		{
			return null;
		}
	}

	static long parseRetryAfterSeconds(Response response)
	{
		String retryAfterHeader = response.header("Retry-After");
		if (retryAfterHeader == null || retryAfterHeader.isBlank())
		{
			return 60L;
		}

		try
		{
			return Math.max(1L, Long.parseLong(retryAfterHeader.trim()));
		}
		catch (NumberFormatException ignored)
		{
			return 60L;
		}
	}

	private static RateLimitMeta parseRateLimitMeta(Response response)
	{
		int limit = parseIntHeader(response, "ratelimit-limit", -1);
		int remaining = parseIntHeader(response, "ratelimit-remaining", -1);
		long resetSeconds = parseLongHeader(response, "ratelimit-reset", -1L);
		long retryAfterSeconds = parseRetryAfterSeconds(response);
		return new RateLimitMeta(limit, remaining, resetSeconds, retryAfterSeconds);
	}

	private static int parseIntHeader(Response response, String headerName, int defaultValue)
	{
		String header = response.header(headerName);
		if (header == null || header.isBlank())
		{
			return defaultValue;
		}

		try
		{
			return Integer.parseInt(header.trim());
		}
		catch (NumberFormatException ex)
		{
			return defaultValue;
		}
	}

	private static long parseLongHeader(Response response, String headerName, long defaultValue)
	{
		String header = response.header(headerName);
		if (header == null || header.isBlank())
		{
			return defaultValue;
		}

		try
		{
			return Long.parseLong(header.trim());
		}
		catch (NumberFormatException ex)
		{
			return defaultValue;
		}
	}

	private static String getAsString(JsonObject object, String memberName)
	{
		if (object == null)
		{
			return "";
		}

		JsonElement element = object.get(memberName);
		return element == null || element.isJsonNull() ? "" : element.getAsString();
	}

	private static long getAsLong(JsonObject object, String memberName)
	{
		if (object == null)
		{
			return 0L;
		}

		JsonElement element = object.get(memberName);
		return element == null || element.isJsonNull() ? 0L : element.getAsLong();
	}

	private static Long getAsLongOrNull(JsonObject object, String memberName)
	{
		if (object == null)
		{
			return null;
		}

		JsonElement element = object.get(memberName);
		return element == null || element.isJsonNull() ? null : element.getAsLong();
	}

	private static JsonObject getAsObject(JsonObject object, String memberName)
	{
		if (object == null)
		{
			return null;
		}

		JsonElement element = object.get(memberName);
		return element == null || element.isJsonNull() ? null : element.getAsJsonObject();
	}

	static final class GroupMember
	{
		private final String currentName;
		private final String lookupName;
		private final long exp;

		GroupMember(String currentName, String lookupName, long exp)
		{
			this.currentName = currentName;
			this.lookupName = lookupName;
			this.exp = exp;
		}

		String getCurrentName()
		{
			return currentName;
		}

		String getLookupName()
		{
			return lookupName;
		}

		long getExp()
		{
			return exp;
		}
	}

	static final class GroupGainEntry
	{
		private final String currentName;
		private final LocalDate startDate;
		private final long startXp;
		private final long endXp;
		private final long gainedXp;

		GroupGainEntry(String currentName, LocalDate startDate, long startXp, long endXp, long gainedXp)
		{
			this.currentName = currentName;
			this.startDate = startDate;
			this.startXp = startXp;
			this.endXp = endXp;
			this.gainedXp = gainedXp;
		}

		String getCurrentName()
		{
			return currentName;
		}

		LocalDate getStartDate()
		{
			return startDate;
		}

		long getStartXp()
		{
			return startXp;
		}

		long getEndXp()
		{
			return endXp;
		}

		long getGainedXp()
		{
			return gainedXp;
		}
	}

	static final class OverallGains
	{
		private final LocalDate startDate;
		private final long startXp;
		private final long endXp;
		private final long gainedXp;

		OverallGains(LocalDate startDate, long startXp, long endXp, long gainedXp)
		{
			this.startDate = startDate;
			this.startXp = startXp;
			this.endXp = endXp;
			this.gainedXp = gainedXp;
		}

		LocalDate getStartDate()
		{
			return startDate;
		}

		long getStartXp()
		{
			return startXp;
		}

		long getEndXp()
		{
			return endXp;
		}

		long getGainedXp()
		{
			return gainedXp;
		}
	}

	static final class GroupMembersResponse
	{
		private final Map<String, GroupMember> members;
		private final RateLimitMeta rateLimitMeta;

		GroupMembersResponse(Map<String, GroupMember> members, RateLimitMeta rateLimitMeta)
		{
			this.members = members == null ? new HashMap<>() : members;
			this.rateLimitMeta = rateLimitMeta == null ? RateLimitMeta.empty() : rateLimitMeta;
		}

		Map<String, GroupMember> getMembers()
		{
			return members;
		}

		RateLimitMeta getRateLimitMeta()
		{
			return rateLimitMeta;
		}
	}

	static final class BaselineResponse
	{
		private final BaselineCacheEntry baseline;
		private final RateLimitMeta rateLimitMeta;
		private final boolean nullWindow;

		BaselineResponse(BaselineCacheEntry baseline, RateLimitMeta rateLimitMeta, boolean nullWindow)
		{
			this.baseline = baseline;
			this.rateLimitMeta = rateLimitMeta == null ? RateLimitMeta.empty() : rateLimitMeta;
			this.nullWindow = nullWindow;
		}

		BaselineCacheEntry getBaseline()
		{
			return baseline;
		}

		RateLimitMeta getRateLimitMeta()
		{
			return rateLimitMeta;
		}

		boolean isNullWindow()
		{
			return nullWindow;
		}
	}

	static final class GroupGainsResponse
	{
		private final Map<String, GroupGainEntry> gains;
		private final RateLimitMeta rateLimitMeta;

		GroupGainsResponse(Map<String, GroupGainEntry> gains, RateLimitMeta rateLimitMeta)
		{
			this.gains = gains == null ? new HashMap<>() : gains;
			this.rateLimitMeta = rateLimitMeta == null ? RateLimitMeta.empty() : rateLimitMeta;
		}

		Map<String, GroupGainEntry> getGains()
		{
			return gains;
		}

		RateLimitMeta getRateLimitMeta()
		{
			return rateLimitMeta;
		}
	}

	static final class RateLimitMeta
	{
		private final int limit;
		private final int remaining;
		private final long resetSeconds;
		private final long retryAfterSeconds;

		RateLimitMeta(int limit, int remaining, long resetSeconds, long retryAfterSeconds)
		{
			this.limit = limit;
			this.remaining = remaining;
			this.resetSeconds = resetSeconds;
			this.retryAfterSeconds = retryAfterSeconds;
		}

		static RateLimitMeta empty()
		{
			return new RateLimitMeta(-1, -1, -1L, 60L);
		}

		int getLimit()
		{
			return limit;
		}

		int getRemaining()
		{
			return remaining;
		}

		long getResetSeconds()
		{
			return resetSeconds;
		}

		long getRetryAfterSeconds()
		{
			return retryAfterSeconds;
		}
	}

	private static final class ApiResult
	{
		private final String body;
		private final RateLimitMeta rateLimitMeta;

		private ApiResult(String body, RateLimitMeta rateLimitMeta)
		{
			this.body = body;
			this.rateLimitMeta = rateLimitMeta == null ? RateLimitMeta.empty() : rateLimitMeta;
		}

		private String getBody()
		{
			return body;
		}

		private RateLimitMeta getRateLimitMeta()
		{
			return rateLimitMeta;
		}
	}

	static final class RateLimitException extends IOException
	{
		private final long retryAfterSeconds;
		private final RateLimitMeta rateLimitMeta;

		RateLimitException(String message, long retryAfterSeconds)
		{
			this(message, retryAfterSeconds, RateLimitMeta.empty());
		}

		RateLimitException(String message, long retryAfterSeconds, RateLimitMeta rateLimitMeta)
		{
			super(message);
			this.retryAfterSeconds = retryAfterSeconds;
			this.rateLimitMeta = rateLimitMeta == null ? RateLimitMeta.empty() : rateLimitMeta;
		}

		long getRetryAfterSeconds()
		{
			return retryAfterSeconds;
		}

		RateLimitMeta getRateLimitMeta()
		{
			return rateLimitMeta;
		}
	}
}
