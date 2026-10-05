package com.projectkorra.projectkorra.keybending;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.util.MultiAbilityManager;

/**
 * Key bending: players with the BendingKeys client mod pick and use abilities with keys instead of
 * the hotbar and left-click.
 * <p>
 * When the mod says hello, the player switches to key mode: their bound ability is whatever they
 * last selected with a key (not the held hotbar slot), and a real left-click no longer bends.
 * Sneaking works as usual with the selected ability. Players without the mod are unaffected.
 * Multi-abilities (WaterArms, Flight, ...) keep using the hotbar while they are active.
 * <p>
 * Messages on {@value #CHANNEL} are plain UTF-8 text:
 * <ul>
 * <li>{@code hello}: switch to key mode (the server answers {@code ok})</li>
 * <li>{@code select:<Ability>}: select an ability (sent before the mod sneaks for a held key)</li>
 * <li>{@code click:<Ability>}: select an ability and do its left-click action</li>
 * </ul>
 */
public final class KeyBending implements PluginMessageListener, Listener {

	public static final String CHANNEL = "projectkorra:keys";

	private static final Map<UUID, String> SELECTED = new ConcurrentHashMap<>();
	private static boolean simulatingClick;

	private final JavaPlugin plugin;

	private KeyBending(final JavaPlugin plugin) {
		this.plugin = plugin;
	}

	public static void register(final JavaPlugin plugin) {
		final KeyBending keyBending = new KeyBending(plugin);
		Bukkit.getMessenger().registerIncomingPluginChannel(plugin, CHANNEL, keyBending);
		Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
		Bukkit.getPluginManager().registerEvents(keyBending, plugin);
	}

	/** Whether this player bends with keys. */
	public static boolean isKeyMode(final Player player) {
		return SELECTED.containsKey(player.getUniqueId());
	}

	/**
	 * The key-selected ability that replaces the hotbar bind, or null to use the hotbar
	 * (no key mode, or a multi-ability is active). Empty if nothing is selected yet.
	 */
	public static String getSelected(final Player player) {
		final String selected = SELECTED.get(player.getUniqueId());
		if (selected == null || MultiAbilityManager.hasMultiAbilityBound(player)) {
			return null;
		}
		return selected;
	}

	/** Whether a real left-click should be ignored for bending (key mode, outside a key's click). */
	public static boolean ignoresRealClick(final Player player) {
		return !simulatingClick && getSelected(player) != null;
	}

	@Override
	public void onPluginMessageReceived(final String channel, final Player player, final byte[] data) {
		if (!CHANNEL.equals(channel)) {
			return;
		}
		final String message = new String(data, StandardCharsets.UTF_8).trim();
		if (message.equals("hello")) {
			SELECTED.putIfAbsent(player.getUniqueId(), "");
			player.sendPluginMessage(this.plugin, CHANNEL, "ok".getBytes(StandardCharsets.UTF_8));
			return;
		}
		final int colon = message.indexOf(':');
		if (colon < 0 || !isKeyMode(player)) {
			return;
		}
		final String command = message.substring(0, colon);
		final CoreAbility ability = CoreAbility.getAbility(message.substring(colon + 1));
		final BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(player);
		if (ability == null || bPlayer == null || !bPlayer.canBind(ability)) {
			return; // unknown ability, or one this player can't use
		}
		SELECTED.put(player.getUniqueId(), ability.getName());

		if (command.equals("click")) {
			// Exactly what a real left-click does, so all the usual checks (chi-block, bloodbending, ...) apply.
			simulatingClick = true;
			try {
				Bukkit.getPluginManager().callEvent(new PlayerInteractEvent(player, Action.LEFT_CLICK_AIR,
						player.getInventory().getItemInMainHand(), null, BlockFace.SELF, EquipmentSlot.HAND));
			} finally {
				simulatingClick = false;
			}
			player.swingMainHand();
		}
	}

	@EventHandler
	public void onQuit(final PlayerQuitEvent event) {
		SELECTED.remove(event.getPlayer().getUniqueId());
	}
}
