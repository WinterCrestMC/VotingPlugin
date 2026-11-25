package com.bencodez.votingplugin.tests.specialrewards.votecooldown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.bencodez.votingplugin.specialrewards.votecooldown.VoteCooldownRewardHandler;

class VoteCooldownRewardHandlerTest {
	private static final long HOUR = 3_600_000L;

	@Test
	void firstVoteAndElapsedCooldownAreMainDue() {
		assertTrue(VoteCooldownRewardHandler.isMainDue(0L, 1_000L, 24 * HOUR));
		assertTrue(VoteCooldownRewardHandler.isMainDue(HOUR, HOUR + 24 * HOUR, 24 * HOUR));
	}

	@Test
	void voteInsideCooldownIsNotMainDue() {
		assertFalse(VoteCooldownRewardHandler.isMainDue(HOUR, HOUR + 24 * HOUR - 1L, 24 * HOUR));
	}

	@Test
	void zeroCooldownAlwaysGivesMain() {
		assertTrue(VoteCooldownRewardHandler.isMainDue(HOUR, HOUR, 0L));
	}

	@Test
	void durationRoundsUpToWholeMinutes() {
		assertEquals("0m", VoteCooldownRewardHandler.formatDuration(0L));
		assertEquals("1m", VoteCooldownRewardHandler.formatDuration(1L));
		assertEquals("5h 12m", VoteCooldownRewardHandler.formatDuration(5 * HOUR + 12 * 60_000L));
	}

	@Test
	void storedTimesRoundTripInOrder() {
		Map<String, Long> times = new LinkedHashMap<>();
		times.put("SiteA", 123L);
		times.put("Site=B", 456L);

		String raw = VoteCooldownRewardHandler.serialize(times);

		assertEquals("SiteA=123;Site=B=456", raw);
		assertEquals(times, VoteCooldownRewardHandler.deserialize(raw));
	}

	@Test
	void missingOrCorruptStoredTimesAreIgnored() {
		assertTrue(VoteCooldownRewardHandler.deserialize(null).isEmpty());
		assertTrue(VoteCooldownRewardHandler.deserialize("  ").isEmpty());
		assertEquals(Map.of("Good", 5L), VoteCooldownRewardHandler.deserialize("Bad=notANumber;NoEquals;=9;Good=5"));
	}
}
