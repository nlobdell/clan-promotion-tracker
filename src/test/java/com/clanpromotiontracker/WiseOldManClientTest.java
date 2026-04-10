package com.clanpromotiontracker;

import org.junit.Test;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.time.LocalDate;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class WiseOldManClientTest
{
	@Test
	public void parseGroupMembersNormalizesCurrentNames()
	{
		String json = "{\n"
			+ "  \"memberships\": [\n"
			+ "    {\"player\": {\"username\": \"Some_User\", \"displayName\": \"Some User\", \"exp\": 12345}},\n"
			+ "    {\"player\": {\"username\": \"Another-User\", \"displayName\": \"Another User\", \"exp\": 54321}}\n"
			+ "  ]\n"
			+ "}";

		Map<String, WiseOldManClient.GroupMember> members = WiseOldManClient.parseGroupMembers(json);

		assertEquals(2, members.size());
		assertEquals(12345L, members.get("someuser").getExp());
		assertEquals(54321L, members.get("anotheruser").getExp());
	}

	@Test
	public void parseOverallGainsExtractsOverallWindow()
	{
		String json = "{\n"
			+ "  \"startsAt\": \"2026-04-01T12:00:00.000Z\",\n"
			+ "  \"endsAt\": \"2026-04-09T12:00:00.000Z\",\n"
			+ "  \"data\": {\n"
			+ "    \"skills\": {\n"
			+ "      \"overall\": {\n"
			+ "        \"experience\": {\n"
			+ "          \"start\": 1000,\n"
			+ "          \"end\": 2000,\n"
			+ "          \"gained\": 1000\n"
			+ "        }\n"
			+ "      }\n"
			+ "    }\n"
			+ "  }\n"
			+ "}";

		WiseOldManClient.OverallGains gains = WiseOldManClient.parseOverallGains(json);

		assertNotNull(gains);
		assertEquals(LocalDate.of(2026, 4, 1), gains.getStartDate());
		assertEquals(1000L, gains.getStartXp());
		assertEquals(2000L, gains.getEndXp());
		assertEquals(1000L, gains.getGainedXp());
	}

	@Test
	public void selectBaselineMarksLaterGainsStartAsApproximate()
	{
		WiseOldManClient.OverallGains gains = new WiseOldManClient.OverallGains(
			LocalDate.of(2026, 4, 3),
			1500L,
			2600L,
			1100L
		);

		BaselineCacheEntry baseline = WiseOldManClient.selectBaseline(gains, LocalDate.of(2026, 4, 1));

		assertNotNull(baseline);
		assertEquals("2026-04-03", baseline.getBaselineDate());
		assertEquals(1500L, baseline.getBaselineXp());
		assertTrue(baseline.isApproximate());
	}

	@Test
	public void parseOverallGainsReturnsNullWhenOverallExperienceIsMissing()
	{
		String json = "{\n"
			+ "  \"startsAt\": \"2026-04-01T12:00:00.000Z\",\n"
			+ "  \"data\": {\n"
			+ "    \"skills\": {\n"
			+ "      \"overall\": {}\n"
			+ "    }\n"
			+ "  }\n"
			+ "}";

		assertNull(WiseOldManClient.parseOverallGains(json));
	}

	@Test
	public void parseGroupGainsIncludesZeroGainRowsAndNormalizesNames()
	{
		String json = "[\n"
			+ "  {\n"
			+ "    \"player\": {\"username\": \"Some_User\", \"displayName\": \"Some User\"},\n"
			+ "    \"data\": {\"start\": 1000, \"end\": 1000, \"gained\": 0},\n"
			+ "    \"startDate\": \"2026-04-01T00:00:00.000Z\",\n"
			+ "    \"endDate\": \"2026-04-10T00:00:00.000Z\"\n"
			+ "  },\n"
			+ "  {\n"
			+ "    \"player\": {\"username\": \"Second-Name\", \"displayName\": \"\"},\n"
			+ "    \"data\": {\"start\": 500, \"end\": 1500, \"gained\": 1000},\n"
			+ "    \"startDate\": \"2026-04-03T12:34:56.000Z\",\n"
			+ "    \"endDate\": \"2026-04-10T00:00:00.000Z\"\n"
			+ "  }\n"
			+ "]";

		Map<String, WiseOldManClient.GroupGainEntry> gains = WiseOldManClient.parseGroupGains(json);

		assertEquals(2, gains.size());
		assertEquals(LocalDate.of(2026, 4, 1), gains.get("someuser").getStartDate());
		assertEquals(0L, gains.get("someuser").getGainedXp());
		assertEquals(LocalDate.of(2026, 4, 3), gains.get("secondname").getStartDate());
		assertEquals(500L, gains.get("secondname").getStartXp());
	}

	@Test
	public void parseRetryAfterFallsBackWhenHeaderIsInvalid()
	{
		Response response = new Response.Builder()
			.request(new Request.Builder().url("https://api.wiseoldman.net/v2/groups/1").build())
			.protocol(Protocol.HTTP_1_1)
			.code(429)
			.message("Too Many Requests")
			.header("Retry-After", "invalid-value")
			.body(ResponseBody.create(null, ""))
			.build();

		assertEquals(60L, WiseOldManClient.parseRetryAfterSeconds(response));
	}
}
