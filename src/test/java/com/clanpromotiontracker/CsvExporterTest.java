package com.clanpromotiontracker;

import org.junit.Test;

import java.time.LocalDate;
import java.util.Collections;

import static org.junit.Assert.assertTrue;

public class CsvExporterTest
{
	@Test
	public void csvIncludesExpectedColumnsAndValues()
	{
		PromotionRecord record = new PromotionRecord(
			"Smirk",
			"smirk",
			"Recruit",
			LocalDate.of(2026, 1, 1),
			3,
			LocalDate.of(2026, 1, 2),
			1000L,
			5000L,
			4000L,
			"Corporal",
			PromotionStatus.READY,
			true
		);

		String csv = CsvExporter.toCsv(Collections.singletonList(record));

		assertTrue(csv.contains("username,current_rank,join_date"));
		assertTrue(csv.contains("Smirk,Recruit,2026-01-01,3,2026-01-02,1000,5000,4000,Corporal,Ready,true"));
	}
}
