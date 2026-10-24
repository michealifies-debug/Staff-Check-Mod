package dev.kestrel.staffcheck;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StaffCheckMod implements ModInitializer {

    public static final String MOD_ID = "staffcheck";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                CheckCommand.register(dispatcher)
        );
        PlayerConnectionListener.register();
        LOGGER.info("[StaffCheck] Mod initialized — /check and /uncheck ready.");
    }
}
