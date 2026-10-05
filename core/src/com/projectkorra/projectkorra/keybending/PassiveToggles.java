package com.projectkorra.projectkorra.keybending;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.util.PassiveManager;

/**
 * Individual passives a player has switched off (e.g. just GracefulDescent), saved with the player's
 * data. A switched-off passive counts as not available to that player wherever passives are checked.
 */
public final class PassiveToggles {

	private static final NamespacedKey KEY = new NamespacedKey(ProjectKorra.plugin, "disabled_passives");

	private PassiveToggles() {
	}

	public static Set<String> disabled(final Player player) {
		final String stored = player.getPersistentDataContainer().get(KEY, PersistentDataType.STRING);
		if (stored == null || stored.isBlank()) {
			return new LinkedHashSet<>();
		}
		return Arrays.stream(stored.split(",")).map(s -> s.trim().toLowerCase(Locale.ROOT))
				.filter(s -> !s.isEmpty()).collect(Collectors.toCollection(LinkedHashSet::new));
	}

	public static boolean isDisabled(final Player player, final CoreAbility passive) {
		return player != null && passive != null && disabled(player).contains(passive.getName().toLowerCase(Locale.ROOT));
	}

	/** Switches one passive on or off for this player; a running one stops right away. */
	public static void set(final Player player, final CoreAbility passive, final boolean enabled) {
		final Set<String> set = disabled(player);
		final String name = passive.getName().toLowerCase(Locale.ROOT);
		if (enabled) {
			set.remove(name);
		} else {
			set.add(name);
		}
		if (set.isEmpty()) {
			player.getPersistentDataContainer().remove(KEY);
		} else {
			player.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, String.join(",", set));
		}
		if (enabled) {
			PassiveManager.registerPassives(player);
		} else {
			final CoreAbility running = CoreAbility.getAbility(player, passive.getClass());
			if (running != null) {
				running.remove();
			}
		}
	}
}
