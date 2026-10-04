package com.projectkorra.projectkorra.command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.configuration.ConfigManager;

/**
 * Executor for /bending set. Extends {@link PKCommand}.
 * <p>
 * Shows or changes an ability's config values (force, range, cooldown, damage, ...) and saves
 * config.yml. Abilities read their config each time they are used, so changes apply to the
 * next use without a reload.
 */
public class SetCommand extends PKCommand {

	public SetCommand() {
		super("set", "/bending set <ability> [option] [value|default]", ConfigManager.languageConfig.get().getString("Commands.Set.Description"), new String[] { "set" });
	}

	@Override
	public void execute(final CommandSender sender, final List<String> args) {
		if (!this.hasPermission(sender) || !this.correctLength(sender, args.size(), 1, 3)) {
			return;
		}
		final FileConfiguration config = ConfigManager.defaultConfig.get();
		final String section = findSection(config, args.get(0));
		if (section == null) {
			sender.sendMessage(ChatColor.RED + "No ability called '" + args.get(0) + "' has config options.");
			return;
		}
		final String ability = section.substring(section.lastIndexOf('.') + 1);
		final Map<String, Object> options = options(config, section);

		if (args.size() == 1) {
			sender.sendMessage(ChatColor.GOLD + ability + " options:");
			for (final Map.Entry<String, Object> e : options.entrySet()) {
				sender.sendMessage(ChatColor.YELLOW + "  " + e.getKey() + ": " + ChatColor.WHITE + e.getValue());
			}
			sender.sendMessage(ChatColor.GRAY + "Change one with /bending set " + ability + " <option> <value>, or 'default' to reset it.");
			return;
		}

		final String option = matchOption(options, args.get(1));
		if (option == null) {
			sender.sendMessage(ChatColor.RED + ability + " has no option '" + args.get(1) + "'. Use /bending set " + ability + " to list them.");
			return;
		}
		final String path = section + "." + option;
		final Object current = config.get(path);

		if (args.size() == 2) {
			sender.sendMessage(ChatColor.YELLOW + ability + " " + option + ": " + ChatColor.WHITE + current + defaultNote(config, path));
			return;
		}

		final String input = args.get(2);
		final Object value;
		if (input.equalsIgnoreCase("default")) {
			final Configuration defaults = config.getDefaults();
			if (defaults == null || !defaults.contains(path)) {
				sender.sendMessage(ChatColor.RED + "There is no default for " + option + ".");
				return;
			}
			value = defaults.get(path);
		} else {
			value = parse(current, input);
			if (value == null) {
				sender.sendMessage(ChatColor.RED + "'" + input + "' isn't a valid " + typeName(current) + " for " + option + ".");
				return;
			}
		}

		config.set(path, value);
		ConfigManager.defaultConfig.save();
		sender.sendMessage(ChatColor.GREEN + ability + " " + option + ": " + ChatColor.GRAY + current + ChatColor.GREEN + " -> " + ChatColor.WHITE + value);
	}

	/** The config section of an ability, e.g. "Abilities.Air.AirBlast", matched case-insensitively. */
	private static String findSection(final FileConfiguration config, final String name) {
		final ConfigurationSection abilities = config.getConfigurationSection("Abilities");
		if (abilities == null) {
			return null;
		}
		String best = null;
		for (final String key : abilities.getKeys(true)) {
			if (key.startsWith("Avatar.AvatarState")) {
				continue; // Avatar State overrides, not the ability itself
			}
			final String last = key.substring(key.lastIndexOf('.') + 1);
			final int depth = key.split("\\.").length;
			// Element.Ability, or Element.Combo.Ability / Element.Passive.Ability
			if (depth < 2 || depth > 3 || !last.equalsIgnoreCase(name) || !abilities.isConfigurationSection(key)) {
				continue;
			}
			if (best == null || depth < best.split("\\.").length) {
				best = key;
			}
		}
		return best == null ? null : "Abilities." + best;
	}

	/** Every value under the ability's section, by path relative to it (e.g. "Push.Entities"). */
	private static Map<String, Object> options(final FileConfiguration config, final String section) {
		final Map<String, Object> options = new LinkedHashMap<>();
		final ConfigurationSection s = config.getConfigurationSection(section);
		if (s == null) {
			return options;
		}
		for (final String key : s.getKeys(true)) {
			if (!s.isConfigurationSection(key)) {
				options.put(key, s.get(key));
			}
		}
		return options;
	}

	private static String matchOption(final Map<String, Object> options, final String input) {
		for (final String key : options.keySet()) {
			if (key.equalsIgnoreCase(input)) {
				return key;
			}
		}
		return null;
	}

	/** Parses the input as the same type as the current value; null if it doesn't fit. */
	private static Object parse(final Object current, final String input) {
		try {
			if (current instanceof Boolean) {
				if (input.equalsIgnoreCase("true") || input.equalsIgnoreCase("false")) {
					return Boolean.parseBoolean(input.toLowerCase(Locale.ROOT));
				}
				return null;
			} else if (current instanceof Integer || current instanceof Long) {
				// Whole numbers stay whole; a decimal is allowed too, since most abilities read these as decimals
				// (and one read as a whole number is rounded down).
				if (!input.contains(".")) {
					final long whole = Long.parseLong(input);
					return current instanceof Integer && whole == (int) whole ? (Object) (int) whole : (Object) whole;
				}
				return Double.parseDouble(input);
			} else if (current instanceof Number) {
				return Double.parseDouble(input);
			} else if (current instanceof String) {
				return input;
			}
		} catch (final NumberFormatException e) {
			return null;
		}
		return null; // lists and other types aren't editable here
	}

	private static String typeName(final Object current) {
		if (current instanceof Boolean) {
			return "true/false value";
		} else if (current instanceof Number) {
			return "number";
		}
		return "value";
	}

	private static String defaultNote(final FileConfiguration config, final String path) {
		final Configuration defaults = config.getDefaults();
		if (defaults == null || !defaults.contains(path)) {
			return "";
		}
		final Object def = defaults.get(path);
		return def != null && !def.equals(config.get(path)) ? ChatColor.GRAY + " (default " + def + ")" : ChatColor.GRAY + " (default)";
	}

	@Override
	protected List<String> getTabCompletion(final CommandSender sender, final List<String> args) {
		final List<String> list = new ArrayList<>();
		if (!sender.hasPermission("bending.command.set")) {
			return list;
		}
		final FileConfiguration config = ConfigManager.defaultConfig.get();
		if (args.isEmpty()) {
			for (final CoreAbility ability : CoreAbility.getAbilities()) {
				if (!list.contains(ability.getName()) && findSection(config, ability.getName()) != null) {
					list.add(ability.getName());
				}
			}
		} else if (args.size() == 1) {
			final String section = findSection(config, args.get(0));
			if (section != null) {
				list.addAll(options(config, section).keySet());
			}
		} else if (args.size() == 2) {
			final String section = findSection(config, args.get(0));
			if (section != null) {
				final Object current = config.get(section + "." + args.get(1));
				if (current != null) {
					list.add(String.valueOf(current));
				}
				list.add("default");
			}
		}
		return list;
	}
}
