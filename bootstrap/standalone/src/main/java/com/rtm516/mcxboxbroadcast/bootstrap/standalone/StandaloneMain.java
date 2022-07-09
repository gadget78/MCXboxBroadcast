package com.rtm516.mcxboxbroadcast.bootstrap.standalone;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.nukkitx.protocol.bedrock.BedrockClient;
import com.nukkitx.protocol.bedrock.BedrockPong;
import com.rtm516.mcxboxbroadcast.core.GenericLoggerImpl;
import com.rtm516.mcxboxbroadcast.core.Logger;
import com.rtm516.mcxboxbroadcast.core.SessionInfo;
import com.rtm516.mcxboxbroadcast.core.SessionManager;
import com.rtm516.mcxboxbroadcast.core.exceptions.SessionUpdateException;
import com.rtm516.mcxboxbroadcast.core.exceptions.XboxFriendsException;
import com.rtm516.mcxboxbroadcast.core.models.FollowerResponse;
import org.java_websocket.util.NamedThreadFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class StandaloneMain {
    private static StandaloneConfig config;
    private static Logger logger;

    public static void main(String[] args) throws Exception {
        logger = new GenericLoggerImpl();

        ScheduledExecutorService scheduledThreadPool = Executors.newScheduledThreadPool(2, new NamedThreadFactory("Scheduled Thread"));

        SessionManager sessionManager = new SessionManager("./cache", logger);

        String configFileName = "config.yml";
        File configFile = new File(configFileName);

        // Create the config file if it doesn't exist
        if (!configFile.exists()) {
            try (FileOutputStream fos = new FileOutputStream(configFileName)) {
                try (InputStream input = StandaloneMain.class.getClassLoader().getResourceAsStream(configFileName)) {
                    byte[] bytes = new byte[input.available()];

                    //noinspection ResultOfMethodCallIgnored
                    input.read(bytes);

                    fos.write(bytes);

                    fos.flush();
                }
            } catch (IOException e) {
                logger.error("Failed to create config", e);
                return;
            }
        }

        try {
            config = new ObjectMapper(new YAMLFactory()).readValue(configFile, StandaloneConfig.class);
        } catch (IOException e) {
            logger.error("Failed to load config", e);
            return;
        }

        SessionInfo sessionInfo = config.sessionConfig.sessionInfo;

        // Sync the session info from the server if needed
        updateSessionInfo(sessionInfo);

        logger.info("Creating session...");

        sessionManager.createSession(sessionInfo);

        logger.info("Created session!");

        scheduledThreadPool.scheduleWithFixedDelay(() -> {
            updateSessionInfo(sessionInfo);

            try {
                sessionManager.updateSession(sessionInfo);
                logger.info("Updated session!");
            } catch (SessionUpdateException e) {
                logger.error("Failed to update session", e);
            }
        }, config.sessionConfig.updateInterval, config.sessionConfig.updateInterval, TimeUnit.SECONDS);

        if (config.friendSyncConfig.autoFollow || config.friendSyncConfig.autoUnfollow) {
            scheduledThreadPool.scheduleWithFixedDelay(() -> {
                try {
                    for (FollowerResponse.Person person : sessionManager.getXboxFriends(true, true)) {
                        // Follow the person back
                        if (config.friendSyncConfig.autoFollow && person.isFollowingCaller && !person.isFollowedByCaller) {
                            logger.info("Added " + person.displayName + " (" + person.xuid + ") as a friend");
                            sessionManager.addXboxFriend(person.xuid);
                        }

                        // Unfollow the person
                        if (config.friendSyncConfig.autoUnfollow && !person.isFollowingCaller && person.isFollowedByCaller) {
                            logger.info("Removed " + person.displayName + " (" + person.xuid + ") as a friend");
                            sessionManager.removeXboxFriend(person.xuid);
                        }
                    }
                } catch (XboxFriendsException e) {
                    logger.error("Failed to sync friends", e);
                }
            }, config.friendSyncConfig.updateInterval, config.friendSyncConfig.updateInterval, TimeUnit.SECONDS);
        }
    }

    private static void updateSessionInfo(SessionInfo sessionInfo) {
        if (config.sessionConfig.queryServer) {
            BedrockClient client = null;
            try {
                InetSocketAddress bindAddress = new InetSocketAddress("0.0.0.0", 0);
                client = new BedrockClient(bindAddress);

                client.bind().join();

                InetSocketAddress addressToPing = new InetSocketAddress(sessionInfo.getIp(), sessionInfo.getPort());
                BedrockPong pong = client.ping(addressToPing, 1500, TimeUnit.MILLISECONDS).get();

                // Update the session information
                sessionInfo.setHostName(pong.getMotd());
                sessionInfo.setWorldName(pong.getSubMotd());
                sessionInfo.setVersion(pong.getVersion());
                sessionInfo.setProtocol(pong.getProtocolVersion());
                sessionInfo.setPlayers(pong.getPlayerCount());
                sessionInfo.setMaxPlayers(pong.getMaximumPlayerCount());
            } catch (InterruptedException | ExecutionException e) {
                logger.error("Failed to ping server", e);
            } finally {
                if (client != null) {
                    client.close();
                }
            }
        }
    }
}
