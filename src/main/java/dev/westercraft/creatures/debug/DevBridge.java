package dev.westercraft.creatures.debug;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.westercraft.creatures.CreaturesOfWesterosMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * DEV-ONLY localhost debug bridge for the Claude testing harness
 * (see claude-testing/minecraft_bridge.py). Lets the harness execute
 * commands and inspect entity state via a tiny newline-delimited JSON
 * protocol on 127.0.0.1, without needing keyboard-simulated chat input.
 *
 * Only ever binds to the loopback address, only ever starts when
 * FabricLoader.isDevelopmentEnvironment() is true, and does nothing at all
 * in a production build -- see CreaturesOfWesterosMod for the wiring.
 */
public final class DevBridge {
    private DevBridge() {}

    private static volatile boolean running = false;
    private static ServerSocket serverSocket;

    public static void start(MinecraftServer server) {
        if (!FabricLoader.getInstance().isDevelopmentEnvironment()) {
            return;
        }
        int port = Integer.getInteger("creaturesofwesteros.devBridgePort", 25585);
        try {
            serverSocket = new ServerSocket(port, 4, InetAddress.getLoopbackAddress());
        } catch (IOException e) {
            CreaturesOfWesterosMod.LOGGER.warn("[DevBridge] Failed to bind 127.0.0.1:{} ({}) -- debug bridge disabled this session", port, e.toString());
            return;
        }
        running = true;
        CreaturesOfWesterosMod.LOGGER.info("[DevBridge] DEV-ONLY debug bridge listening on 127.0.0.1:{}", port);

        Thread acceptThread = new Thread(() -> acceptLoop(server), "creaturesofwesteros-devbridge-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public static void stop() {
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void acceptLoop(MinecraftServer server) {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                Thread clientThread = new Thread(() -> handleClient(server, client), "creaturesofwesteros-devbridge-client");
                clientThread.setDaemon(true);
                clientThread.start();
            } catch (IOException e) {
                if (running) {
                    CreaturesOfWesterosMod.LOGGER.warn("[DevBridge] accept() failed: {}", e.toString());
                }
            }
        }
    }

    private static void handleClient(MinecraftServer server, Socket client) {
        try (client;
             BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
             OutputStream out = client.getOutputStream()) {
            String line;
            while ((line = in.readLine()) != null) {
                JsonObject response = handleRequest(server, line);
                out.write((response.toString() + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (IOException ignored) {
            // Client disconnected -- nothing to clean up.
        }
    }

    private static JsonObject handleRequest(MinecraftServer server, String line) {
        JsonObject request;
        try {
            request = JsonParser.parseString(line).getAsJsonObject();
        } catch (Exception e) {
            return error("invalid JSON request: " + e.getMessage());
        }

        String action = request.has("action") ? request.get("action").getAsString() : "";
        CompletableFuture<JsonObject> future = new CompletableFuture<>();

        server.execute(() -> {
            try {
                future.complete(dispatch(server, action, request));
            } catch (Exception e) {
                future.complete(error("server-thread exception: " + e));
            }
        });

        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return error("timed out waiting for the server thread: " + e);
        }
    }

    private static JsonObject dispatch(MinecraftServer server, String action, JsonObject request) {
        return switch (action) {
            case "ping" -> {
                JsonObject r = new JsonObject();
                r.addProperty("ok", true);
                r.addProperty("pong", true);
                yield r;
            }
            case "command" -> runCommand(server, request);
            case "debug_entity" -> debugEntity(server, request);
            case "list_entities" -> listEntities(server, request);
            default -> error("unknown action: " + action);
        };
    }

    private static ServerPlayerEntity firstPlayer(MinecraftServer server) {
        List<ServerPlayerEntity> players = server.getPlayerManager().getPlayerList();
        return players.isEmpty() ? null : players.get(0);
    }

    private static JsonObject runCommand(MinecraftServer server, JsonObject request) {
        if (!request.has("command")) {
            return error("missing 'command'");
        }
        String command = request.get("command").getAsString();
        String toRun = command.startsWith("/") ? command.substring(1) : command;

        ServerPlayerEntity player = firstPlayer(server);
        ServerCommandSource source = player != null ? player.getCommandSource() : server.getCommandSource();

        try {
            int successCount = server.getCommandManager().executeWithPrefix(source, toRun);
            JsonObject r = new JsonObject();
            r.addProperty("ok", true);
            r.addProperty("success_count", successCount);
            r.addProperty("ran_as", player != null ? player.getGameProfile().getName() : "server console");
            return r;
        } catch (Exception e) {
            return error("command execution failed: " + e);
        }
    }

    private static JsonObject debugEntity(MinecraftServer server, JsonObject request) {
        if (!request.has("entity_type")) {
            return error("missing 'entity_type' (e.g. 'creaturesofwesteros:viserion')");
        }
        String typeId = request.get("entity_type").getAsString();
        double radius = request.has("radius") ? request.get("radius").getAsDouble() : 64.0;

        Identifier id = Identifier.tryParse(typeId);
        Optional<EntityType<?>> typeOpt = id == null ? Optional.empty() : Registries.ENTITY_TYPE.getOrEmpty(id);
        if (typeOpt.isEmpty()) {
            return error("unknown entity_type: " + typeId);
        }

        ServerPlayerEntity player = firstPlayer(server);
        if (player == null) {
            return error("no player online in the dev world");
        }

        ServerWorld world = player.getServerWorld();
        EntityType<?> type = typeOpt.get();
        Box box = player.getBoundingBox().expand(radius);

        Entity nearest = null;
        double bestDistSq = Double.MAX_VALUE;
        for (Entity e : world.getEntitiesByType(type, box, e -> true)) {
            double d = e.squaredDistanceTo(player);
            if (d < bestDistSq) {
                bestDistSq = d;
                nearest = e;
            }
        }

        if (nearest == null) {
            return error("no entity of type " + typeId + " within " + radius + " blocks of the player");
        }

        JsonObject r = new JsonObject();
        r.addProperty("ok", true);
        r.add("entity", EntityDebugDump.dump(nearest));
        return r;
    }

    private static JsonObject listEntities(MinecraftServer server, JsonObject request) {
        if (!request.has("entity_type")) {
            return error("missing 'entity_type'");
        }
        String typeId = request.get("entity_type").getAsString();
        double radius = request.has("radius") ? request.get("radius").getAsDouble() : 256.0;

        Identifier id = Identifier.tryParse(typeId);
        Optional<EntityType<?>> typeOpt = id == null ? Optional.empty() : Registries.ENTITY_TYPE.getOrEmpty(id);
        if (typeOpt.isEmpty()) {
            return error("unknown entity_type: " + typeId);
        }

        ServerPlayerEntity player = firstPlayer(server);
        if (player == null) {
            return error("no player online in the dev world");
        }

        ServerWorld world = player.getServerWorld();
        Box box = player.getBoundingBox().expand(radius);
        List<? extends Entity> found = world.getEntitiesByType(typeOpt.get(), box, e -> true);

        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        for (Entity e : found) {
            arr.add(EntityDebugDump.dump(e));
        }

        JsonObject r = new JsonObject();
        r.addProperty("ok", true);
        r.addProperty("count", found.size());
        r.add("entities", arr);
        return r;
    }

    private static JsonObject error(String message) {
        JsonObject r = new JsonObject();
        r.addProperty("ok", false);
        r.addProperty("error", message);
        return r;
    }
}
