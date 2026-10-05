package com.projectkorra.projectkorra.keybending;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.PKListener;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.ComboAbility;
import com.projectkorra.projectkorra.ability.PassiveAbility;
import com.projectkorra.projectkorra.ability.util.MultiAbilityManager;
import com.projectkorra.projectkorra.ability.util.PassiveManager;
import com.projectkorra.projectkorra.event.PlayerBindChangeEvent;

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
 * <li>{@code click:<Ability>}: tap: select an ability and do its left-click action</li>
 * <li>{@code sneakdown:<Ability>} / {@code sneakup:<Ability>}: a held key: select an ability and start /
 * end a virtual sneak with it, without the player actually crouching</li>
 * <li>{@code shift:<Ability>}: Shift + key: select an ability and do a quick sneak (start and end)</li>
 * <li>{@code select:<Ability>}: only select an ability</li>
 * <li>{@code sync}: ask for the lists below (the mod's bending menu)</li>
 * <li>{@code bind:<slot>:<Ability>} / {@code unbind:<slot>}: change a quick button (ProjectKorra bind 1-9)</li>
 * <li>{@code passive:<Passive>:on|off}: switch one passive on or off</li>
 * </ul>
 * The server sends (besides {@code ok}): {@code binds:1=AirBlast;3=AirShield},
 * {@code abilities:AirBlast,Air,#aaaaaa;...} (what the player may bind) and
 * {@code passives:AirAgility,Air,#aaaaaa,on;...}.
 * In key mode a real left-click and a real Shift no longer do ability actions (Shift is just crouching;
 * passives such as FastSwim still work). Abilities that keep checking whether the player is sneaking use
 * {@link #isSneaking(Player)}: a held key or real crouching.
 */
public final class KeyBending implements PluginMessageListener, Listener {

	public static final String CHANNEL = "projectkorra:keys";

	private static final Map<UUID, String> SELECTED = new ConcurrentHashMap<>();
	/** Players holding an ability key: they count as sneaking for bending. */
	private static final Set<UUID> VIRTUAL_SNEAK = ConcurrentHashMap.newKeySet();
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

	/**
	 * Whether this sneak toggle should skip ability sneak actions: in key mode only the mod's keys do them
	 * (a real Shift would use whatever ability was selected last). Passives still run.
	 */
	public static boolean ignoresRealSneak(final Player player, final boolean sneaking) {
		return getSelected(player) != null;
	}

	/** Whether the player counts as sneaking for bending: really crouching, or holding an ability key. */
	public static boolean isSneaking(final Player player) {
		return player.isSneaking() || (VIRTUAL_SNEAK.contains(player.getUniqueId()) && getSelected(player) != null);
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
			this.send(player, "ok");
			this.sendAll(player);
			return;
		}
		if (message.equals("sync")) {
			this.sendAll(player);
			return;
		}
		if (message.startsWith("bind:") || message.startsWith("unbind:") || message.startsWith("passive:")) {
			this.handleMenu(player, message);
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

		// Like a real sneak: the action sees the state from before the toggle, then the state changes.
		switch (command) {
			case "sneakdown" -> {
				PKListener.handleSneak(player, false, true);
				VIRTUAL_SNEAK.add(player.getUniqueId());
			}
			case "sneakup" -> {
				PKListener.handleSneak(player, true, true);
				VIRTUAL_SNEAK.remove(player.getUniqueId());
			}
			case "shift" -> {
				PKListener.handleSneak(player, false, true);
				PKListener.handleSneak(player, true, true);
			}
			default -> { }
		}

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

	private void handleMenu(final Player player, final String message) {
		final BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(player);
		if (bPlayer == null) {
			return;
		}
		final String[] parts = message.split(":");
		try {
			switch (parts[0]) {
				case "bind" -> {
					final int slot = Integer.parseInt(parts[1]);
					final CoreAbility ability = CoreAbility.getAbility(parts[2]);
					if (slot >= 1 && slot <= 9 && ability != null && isBindable(bPlayer, ability)) {
						bPlayer.bindAbility(ability.getName(), slot);
					}
				}
				case "unbind" -> {
					final int slot = Integer.parseInt(parts[1]);
					if (slot >= 1 && slot <= 9 && bPlayer.getAbilities().get(slot) != null
							&& !MultiAbilityManager.hasMultiAbilityBound(player)) {
						final PlayerBindChangeEvent event = new PlayerBindChangeEvent(player, bPlayer.getAbilities().get(slot), slot, false, false);
						Bukkit.getPluginManager().callEvent(event);
						if (!event.isCancelled()) {
							bPlayer.getAbilities().remove(slot);
							bPlayer.saveAbility(null, slot);
						}
					}
				}
				case "passive" -> {
					CoreAbility passive = null;
					for (final CoreAbility p : PassiveManager.getPassives().values()) {
						if (p.getName().equalsIgnoreCase(parts[1])) {
							passive = p;
						}
					}
					if (passive != null) {
						PassiveToggles.set(player, passive, parts[2].equalsIgnoreCase("on"));
					}
				}
				default -> { }
			}
		} catch (final RuntimeException e) {
			return; // malformed message
		}
		this.sendAll(player);
	}

	/** What a player may put on a quick button: like /bending bind, so no passives or combos. */
	private static boolean isBindable(final BendingPlayer bPlayer, final CoreAbility ability) {
		return !ability.isHiddenAbility() && bPlayer.canBind(ability)
				&& !(ability instanceof PassiveAbility) && !(ability instanceof ComboAbility);
	}

	private void sendAll(final Player player) {
		final BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(player);
		if (bPlayer == null) {
			return;
		}
		this.sendBinds(player, bPlayer);

		final Map<String, CoreAbility> bindable = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		for (final CoreAbility ability : CoreAbility.getAbilities()) {
			if (isBindable(bPlayer, ability)) {
				bindable.putIfAbsent(ability.getName(), ability);
			}
		}
		final List<String> abilities = new ArrayList<>();
		for (final CoreAbility ability : bindable.values()) {
			abilities.add(ability.getName() + "," + elementName(ability) + "," + color(ability));
		}
		this.send(player, "abilities:" + String.join(";", abilities));

		final Map<String, CoreAbility> passives = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		for (final CoreAbility passive : PassiveManager.getPassives().values()) {
			// (ProjectKorra marks every passive as hidden, so that isn't checked here)
			if (passive.isEnabled() && bPlayer.hasElement(passive.getElement() instanceof Element.SubElement sub ? sub.getParentElement() : passive.getElement())
					&& player.hasPermission("bending.ability." + passive.getName())) {
				passives.putIfAbsent(passive.getName(), passive);
			}
		}
		final List<String> list = new ArrayList<>();
		for (final CoreAbility passive : passives.values()) {
			list.add(passive.getName() + "," + elementName(passive) + "," + color(passive) + ","
					+ (PassiveToggles.isDisabled(player, passive) ? "off" : "on"));
		}
		this.send(player, "passives:" + String.join(";", list));
	}

	private void sendBinds(final Player player, final BendingPlayer bPlayer) {
		final List<String> binds = new ArrayList<>();
		for (int slot = 1; slot <= 9; slot++) {
			final String name = bPlayer.getAbilities().get(slot);
			if (name != null) {
				binds.add(slot + "=" + name);
			}
		}
		this.send(player, "binds:" + String.join(";", binds));
	}

	/** Keep the mod's quick buttons current when binds change some other way (/b bind, presets, ...). */
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onBindChange(final PlayerBindChangeEvent event) {
		final Player player = event.getPlayer() instanceof Player p ? p : null;
		if (player == null || !isKeyMode(player)) {
			return;
		}
		Bukkit.getScheduler().runTask(this.plugin, () -> {
			final BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(player);
			if (bPlayer != null && player.isOnline()) {
				this.sendBinds(player, bPlayer);
			}
		});
	}

	private static String elementName(final CoreAbility ability) {
		final Element element = ability.getElement();
		final Element main = element instanceof Element.SubElement sub ? sub.getParentElement() : element;
		return main == null ? "" : main.getName();
	}

	private static String color(final CoreAbility ability) {
		try {
			final java.awt.Color c = ability.getElement().getColor().getColor();
			return c == null ? "#ffffff" : String.format("#%06x", c.getRGB() & 0xffffff);
		} catch (final RuntimeException e) {
			return "#ffffff";
		}
	}

	private void send(final Player player, final String message) {
		player.sendPluginMessage(this.plugin, CHANNEL, message.getBytes(StandardCharsets.UTF_8));
	}

	@EventHandler
	public void onQuit(final PlayerQuitEvent event) {
		SELECTED.remove(event.getPlayer().getUniqueId());
		VIRTUAL_SNEAK.remove(event.getPlayer().getUniqueId());
	}
}
