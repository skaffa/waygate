package dev.waygate.client;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Client entrypoint. Milestone 1 only loads; features arrive in later milestones (see PLAN.md). */
public final class WaygateClient implements ClientModInitializer {
	public static final String MOD_ID = "waygate";
	public static final Logger LOG = LoggerFactory.getLogger("Waygate");

	@Override
	public void onInitializeClient() {
		LOG.info("Waygate loaded");
	}
}
