package com.projectkorra.projectkorra.util;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;

/**
 * Looks up {@link Sound}s by name without {@code Sound.valueOf}, which is
 * marked for removal now that sounds are registry based.
 */
public final class SoundUtil {

	private static final Map<String, Sound> BY_FIELD_NAME = new HashMap<>();

	static {
		for (final Field field : Sound.class.getFields()) {
			if (Modifier.isStatic(field.getModifiers()) && field.getType() == Sound.class) {
				try {
					BY_FIELD_NAME.put(field.getName(), (Sound) field.get(null));
				} catch (final IllegalAccessException ignored) {
				}
			}
		}
	}

	private SoundUtil() {}

	/**
	 * Gets a sound from either its legacy constant name (e.g.
	 * {@code ENTITY_CREEPER_HURT}) or its key (e.g. {@code entity.creeper.hurt}).
	 *
	 * @param name the sound name
	 * @return the matching sound
	 * @throws IllegalArgumentException if no sound matches
	 */
	public static Sound getSound(final String name) {
		if (name == null) {
			throw new IllegalArgumentException("Sound name cannot be null");
		}
		final Sound sound = BY_FIELD_NAME.get(name.toUpperCase(Locale.ROOT));
		if (sound != null) {
			return sound;
		}
		final NamespacedKey key = NamespacedKey.fromString(name.toLowerCase(Locale.ROOT));
		final Sound fromRegistry = key == null ? null : Registry.SOUNDS.get(key);
		if (fromRegistry == null) {
			throw new IllegalArgumentException("No sound named " + name);
		}
		return fromRegistry;
	}
}
