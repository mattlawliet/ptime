package dev.matt.ptime;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import java.time.ZoneId;
import java.time.zone.ZoneRulesException;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public final class PtimeClient implements ClientModInitializer {
    public static final String MOD_ID = "ptime";

    private final PtimeManager manager = new PtimeManager();
    private KeyMapping openKey;

    @Override
    public void onInitializeClient() {
        openKey = KeyMappingHelper.registerKeyMapping(
            new KeyMapping(
                "key.ptime.open",
                InputConstants.Type.KEYSYM,
                InputConstants.KEY_P,
                KeyMapping.Category.MISC
            )
        );

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.consumeClick()) {
                client.gui.setScreen(new PtimeScreen(manager));
            }
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> manager.onSessionStarted(client));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> manager.onSessionEnded());

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(ClientCommands.literal("wtime")
                .then(ClientCommands.literal("open")
                    .executes(context -> {
                        openScreen();
                        return Command.SINGLE_SUCCESS;
                    }))
                .then(ClientCommands.literal("self")
                    .then(ClientCommands.argument("timezone", StringArgumentType.greedyString())
                        .suggests((context, builder) -> {
                            for (String option : manager.suggestedTimezoneInputs()) {
                                builder.suggest(option);
                            }
                            return builder.buildFuture();
                        })
                        .executes(context -> {
                            ZoneId zoneId = parseTimezone(StringArgumentType.getString(context, "timezone"));
                            if (zoneId == null) {
                                return 0;
                            }

                            manager.setSelfTimezone(zoneId);
                            sendFeedback(Component.literal("Set your ptime timezone to " + zoneId.getId() + "."));
                            return Command.SINGLE_SUCCESS;
                        })))
                .then(ClientCommands.literal("remove")
                    .then(ClientCommands.argument("player", StringArgumentType.word())
                        .suggests((context, builder) -> {
                            for (String option : manager.trackedPlayerNames()) {
                                builder.suggest(option);
                            }
                            return builder.buildFuture();
                        })
                        .executes(context -> {
                            String player = StringArgumentType.getString(context, "player");
                            if (manager.remove(player)) {
                                sendFeedback(Component.literal("Removed " + player + " from ptime."));
                            } else {
                                sendFeedback(Component.literal(player + " is not tracked."));
                            }
                            return Command.SINGLE_SUCCESS;
                        })))
                .then(ClientCommands.literal("list")
                    .executes(context -> {
                        sendFeedback(manager.describeTrackedPlayers());
                        return Command.SINGLE_SUCCESS;
                    }))
                .then(ClientCommands.argument("player", StringArgumentType.word())
                    .suggests((context, builder) -> {
                        for (String option : manager.onlinePlayerNames()) {
                            builder.suggest(option);
                        }
                        return builder.buildFuture();
                    })
                    .then(ClientCommands.argument("timezone", StringArgumentType.greedyString())
                        .suggests((context, builder) -> {
                            for (String option : manager.suggestedTimezoneInputs()) {
                                builder.suggest(option);
                            }
                            return builder.buildFuture();
                        })
                        .executes(context -> addClock(
                            StringArgumentType.getString(context, "player"),
                            StringArgumentType.getString(context, "timezone")
                        ))))
                .executes(context -> {
                    openScreen();
                    return Command.SINGLE_SUCCESS;
                }))
        );
    }

    private int addClock(String playerName, String timezoneId) throws CommandSyntaxException {
        if (!PtimeManager.isValidPlayerName(playerName)) {
            sendFeedback(Component.literal("Player names must be 3-16 characters using letters, numbers, or _."));
            return 0;
        }

        ZoneId zoneId = parseTimezone(timezoneId);
        if (zoneId == null) {
            return 0;
        }

        manager.addOrUpdatePlayer(playerName, zoneId);
        sendFeedback(Component.literal("Tracking " + playerName + " in " + zoneId.getId() + "."));
        return Command.SINGLE_SUCCESS;
    }

    private ZoneId parseTimezone(String timezoneId) {
        try {
            return PtimeManager.resolveTimezone(timezoneId);
        } catch (ZoneRulesException ignored) {
        }

        try {
            ZoneId zoneId = manager.resolvePlaceToTimezone(timezoneId);
            sendFeedback(Component.literal("Resolved \"" + timezoneId + "\" to " + zoneId.getId() + "."));
            return zoneId;
        } catch (ZoneRulesException exception) {
            sendFeedback(Component.literal(exception.getMessage()));
            return null;
        }
    }

    private void openScreen() {
        Minecraft client = Minecraft.getInstance();
        if (client != null) {
            client.gui.setScreen(new PtimeScreen(manager));
        }
    }

    private void sendFeedback(Component message) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null) {
            return;
        }

        client.player.sendSystemMessage(message);
    }
}
