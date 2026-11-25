package com.bencodez.votingplugin.specialrewards.votecooldown;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.configuration.ConfigurationSection;

import com.bencodez.advancedcore.api.rewards.RewardOptions;
import com.bencodez.votingplugin.VotingPluginMain;
import com.bencodez.votingplugin.user.VotingPluginUser;
import com.bencodez.votingplugin.votesites.VoteSite;

/**
 * Per-site main/secondary rewards for sites that allow several votes per day.
 *
 * <p>
 * The first vote on a site, or the first vote after {@code CooldownHours} since
 * that site's last main reward, gives {@code MainReward} and restarts the
 * timer. Any other vote on the site gives {@code SecondaryReward}.
 */
public class VoteCooldownRewardHandler {

	public static final String SECTION = "CooldownRewards";
	public static final String DATA_KEY_LAST_MAIN = "VoteCooldownRewardLastMain";
	public static final double DEFAULT_COOLDOWN_HOURS = 24.0;

	private final VotingPluginMain plugin;
	private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

	public VoteCooldownRewardHandler(VotingPluginMain plugin) {
		this.plugin = plugin;
	}

	public static String getSectionPath(String siteKey) {
		return "VoteSites." + siteKey + "." + SECTION;
	}

	public static String getMainRewardPath(String siteKey) {
		return getSectionPath(siteKey) + ".MainReward";
	}

	public static String getSecondaryRewardPath(String siteKey) {
		return getSectionPath(siteKey) + ".SecondaryReward";
	}

	public static boolean isMainDue(long lastMainMs, long voteTimeMs, long cooldownMs) {
		if (lastMainMs <= 0L || cooldownMs <= 0L) {
			return true;
		}
		return voteTimeMs - lastMainMs >= cooldownMs;
	}

	/** Formats a duration, rounding up to whole minutes (for example 5h 12m). */
	public static String formatDuration(long ms) {
		if (ms <= 0L) {
			return "0m";
		}
		long totalMinutes = (ms + 59999L) / 60000L;
		long hours = totalMinutes / 60L;
		long minutes = totalMinutes % 60L;
		return hours <= 0L ? minutes + "m" : hours + "h " + minutes + "m";
	}

	public static Map<String, Long> deserialize(String raw) {
		Map<String, Long> map = new LinkedHashMap<>();
		if (raw == null || raw.trim().isEmpty()) {
			return map;
		}
		for (String entry : raw.split(";")) {
			int idx = entry.lastIndexOf('=');
			if (idx > 0) {
				try {
					map.put(entry.substring(0, idx), Long.parseLong(entry.substring(idx + 1).trim()));
				} catch (NumberFormatException ignored) {
					// Skip a corrupt entry; that site simply becomes due for its main reward.
				}
			}
		}
		return map;
	}

	public static String serialize(Map<String, Long> map) {
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, Long> e : map.entrySet()) {
			if (sb.length() > 0) {
				sb.append(';');
			}
			sb.append(e.getKey()).append('=').append(e.getValue());
		}
		return sb.toString();
	}

	private ConfigurationSection getSection(VoteSite site) {
		if (site == null || plugin.getConfigVoteSites() == null || plugin.getConfigVoteSites().getData() == null) {
			return null;
		}
		return plugin.getConfigVoteSites().getData().getConfigurationSection(getSectionPath(site.getKey()));
	}

	public boolean isEnabled(VoteSite site) {
		ConfigurationSection section = getSection(site);
		return section != null && section.getBoolean("Enabled", false);
	}

	public long getCooldownMs(VoteSite site) {
		ConfigurationSection section = getSection(site);
		double hours = section == null ? DEFAULT_COOLDOWN_HOURS
				: section.getDouble("CooldownHours", DEFAULT_COOLDOWN_HOURS);
		return hours <= 0.0 ? 0L : (long) (hours * 3600000.0);
	}

	private Map<String, Long> readMap(VotingPluginUser user) {
		return deserialize(user.getUserData().getString(DATA_KEY_LAST_MAIN));
	}

	public long getLastMainRewardTime(VotingPluginUser user, VoteSite site) {
		Long value = readMap(user).get(site.getKey());
		return value == null ? 0L : value;
	}

	public long getTimeUntilMain(VotingPluginUser user, VoteSite site) {
		long last = getLastMainRewardTime(user, site);
		long cooldown = getCooldownMs(site);
		long now = System.currentTimeMillis();
		return isMainDue(last, now, cooldown) ? 0L : Math.max(0L, last + cooldown - now);
	}

	/**
	 * Decides and gives the reward for one vote.
	 *
	 * @return the reward given, or {@code null} when the site has no enabled
	 *         {@code CooldownRewards} section
	 */
	public Result processVote(VotingPluginUser user, VoteSite site, long voteTimeMs, boolean forceBungee,
			UUID voteUUID) {
		if (user == null || site == null || !isEnabled(site)) {
			return null;
		}
		if (voteTimeMs <= 0L) {
			voteTimeMs = System.currentTimeMillis();
		}

		long cooldownMs = getCooldownMs(site);
		Object lock = locks.computeIfAbsent(user.getUUID(), k -> new Object());
		Result result;
		long nextMainAt;
		synchronized (lock) {
			Map<String, Long> map = readMap(user);
			Long stored = map.get(site.getKey());
			long lastMain = stored == null ? 0L : stored;
			if (isMainDue(lastMain, voteTimeMs, cooldownMs)) {
				result = Result.MAIN;
				map.put(site.getKey(), voteTimeMs);
				user.getUserData().setString(DATA_KEY_LAST_MAIN, serialize(map));
				nextMainAt = voteTimeMs + cooldownMs;
			} else {
				result = Result.SECONDARY;
				nextMainAt = lastMain + cooldownMs;
			}
		}

		String path = result == Result.MAIN ? getMainRewardPath(site.getKey()) : getSecondaryRewardPath(site.getKey());
		HashMap<String, String> placeholders = new HashMap<>();
		placeholders.put("SiteName", site.getDisplayName());
		placeholders.put("ServiceSite", site.getServiceSite());
		placeholders.put("CooldownReward", result.name());
		placeholders.put("NextMainReward", formatDuration(nextMainAt - System.currentTimeMillis()));
		plugin.debug("[CooldownRewards] " + user.getPlayerName() + " vote on " + site.getKey() + " -> " + result
				+ " (voteTime=" + voteTimeMs + ", nextMainAt=" + nextMainAt + ", vote=" + voteUUID + ")");

		try {
			RewardOptions options = new RewardOptions().setPrefix("CooldownReward-" + site.getKey() + "-" + result.name())
					.setServer(forceBungee).setPlaceholders(placeholders);
			plugin.getRewardHandler().giveReward(user, plugin.getConfigVoteSites().getData(), path, options);
		} catch (Exception e) {
			plugin.getLogger()
					.warning("Failed to give CooldownRewards " + path + " to " + user.getPlayerName() + ": " + e.getMessage());
			plugin.debug(e);
		}
		return result;
	}

	public enum Result {
		MAIN, SECONDARY
	}
}
