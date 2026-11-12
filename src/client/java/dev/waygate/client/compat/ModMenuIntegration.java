package dev.waygate.client.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

import dev.waygate.client.WaygateScreen;

/** Adds a "Configure" button for Waygate in Mod Menu. Only loaded when Mod Menu is installed. */
public final class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return WaygateScreen::new;
	}
}
