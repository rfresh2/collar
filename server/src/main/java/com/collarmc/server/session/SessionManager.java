package com.collarmc.server.session;


import com.collarmc.api.identity.ClientIdentity;
import com.collarmc.api.session.Player;
import com.collarmc.protocol.PacketIO;
import com.collarmc.protocol.ProtocolResponse;
import com.collarmc.protocol.SessionStopReason;
import com.collarmc.protocol.session.SessionFailedResponse.SessionErrorResponse;
import com.collarmc.security.messages.CipherException;
import com.collarmc.security.mojang.MinecraftPlayer;
import com.collarmc.server.security.ServerIdentityStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jetty.websocket.api.Session;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

public final class SessionManager {

    private static final Logger LOGGER = LogManager.getLogger(SessionManager.class.getName());

    private final ConcurrentMap<Session, SessionState> sessions = new ConcurrentHashMap<>();

    private final ObjectMapper messagePack;
    private final ServerIdentityStore store;

    public SessionManager(ObjectMapper messagePack, ServerIdentityStore store) {
        this.messagePack = messagePack;
        this.store = store;
    }

    public void identify(Session session, ClientIdentity identity, MinecraftPlayer player, BiConsumer<ClientIdentity, Player> callback) {
        SessionState state = new SessionState(session, identity, player);
        SessionState computed = sessions.compute(session, (theSession, sessionState) -> {
            if (sessionState != null && sessionState.minecraftPlayer != null) {
                throw new IllegalStateException("session cannot be identified with a single player more than once");
            }
            return state;
        });
        if (computed.minecraftPlayer != null) {
            callback.accept(state.identity, state.toPlayer());
        }
    }

    public boolean isIdentified(Session session) {
        return getIdentity(session).isPresent();
    }


    public void stopSession(Session session,
                            SessionStopReason reason,
                            String message,
                            Throwable e,
                            BiConsumer<ClientIdentity, Player> callback) {
        // Run callback
        SessionState state = sessions.get(session);
        if (state != null && callback != null) {
            callback.accept(state.identity, state.toPlayer());
        }
        // Start removing state
        if (e == null) {
            LOGGER.info(reason.message(message));
        } else {
            LOGGER.error(reason.message(message), e);
        }
        SessionState sessionState = sessions.remove(session);
        if (sessionState != null) {
            if (session.isOpen()) {
                try {
                    send(session, sessionState.identity, new SessionErrorResponse(reason, message));
                } catch (IOException | CipherException ex) {
                    throw new IllegalStateException("Couldn't send SessionErrorResponse", ex);
                }
            }
        } else {
            session.close(reason.code, "Session stopped");
        }
    }

    public void send(Session session, ClientIdentity recipient, ProtocolResponse resp) throws IOException, CipherException {
        PacketIO packetIO = new PacketIO(messagePack, store.cipher());
        ByteBuffer buffer;
        if (isIdentified(session)) {
            buffer = ByteBuffer.wrap(packetIO.encodeEncrypted(recipient, resp));
        } else {
            buffer = ByteBuffer.wrap(packetIO.encodePlain(resp));
        }
        session.getRemote().sendBytes(buffer);
    }

    public Optional<ClientIdentity> getIdentity(Session session) {
        if (session == null) {
            return Optional.empty();
        }
        SessionState sessionState = sessions.get(session);
        return sessionState == null ? Optional.empty() : Optional.of(sessionState.identity);
    }

    public Optional<ClientIdentity> getIdentity(MinecraftPlayer player) {
        if (player == null) {
            return Optional.empty();
        }
        return sessions.values().stream().filter(sessionState -> sessionState.minecraftPlayer != null && sessionState.minecraftPlayer.equals(player))
                .findFirst()
                .map(sessionState -> sessionState.identity);
    }

    public List<Player> findPlayers(ClientIdentity identity, @Nonnull List<UUID> players) {
        if (identity == null) {
            return new ArrayList<>();
        }
        MinecraftPlayer player = findMinecraftPlayer(identity).orElseThrow(() -> new IllegalStateException("cannot find player for " + identity));
        return sessions.values().stream()
                .filter(sessionState -> sessionState.minecraftPlayer != null && sessionState.minecraftPlayer.inServerWith(player))
                .filter(sessionState -> players.contains(sessionState.minecraftPlayer.id))
                .map(SessionState::toPlayer)
                .collect(Collectors.toList());
    }

    public Optional<MinecraftPlayer> findMinecraftPlayer(ClientIdentity identity) {
        if (identity == null) {
            return Optional.empty();
        }
        return sessions.values().stream()
                .filter(sessionState -> sessionState.identity.equals(identity) && sessionState.minecraftPlayer != null)
                .findFirst()
                .map(sessionState -> sessionState.minecraftPlayer);
    }

    public Optional<Player> findPlayer(ClientIdentity identity) {
        if (identity == null) {
            return Optional.empty();
        }
        return sessions.values().stream()
                .filter(sessionState -> sessionState.minecraftPlayer != null && sessionState.identity.equals(identity))
                .findFirst()
                .map(SessionState::toPlayer);
    }

    public Optional<Session> getSession(ClientIdentity identity) {
        if (identity == null) {
            return Optional.empty();
        }
        return sessions.values().stream()
                .filter(sessionState -> sessionState.identity.equals(identity))
                .findFirst()
                .map(sessionState -> sessionState.session);
    }

    public Optional<SessionState> getSessionStateByOwner(UUID owner) {
        return sessions.values().stream()
                .filter(sessionState -> sessionState.identity.id().equals(owner))
                .findFirst();
    }

    public Optional<SessionState> getSessionStateByPlayer(UUID player) {
        return sessions.values().stream()
                .filter(sessionState -> sessionState.minecraftPlayer != null && sessionState.minecraftPlayer.id.equals(player))
                .findFirst();
    }

    public Optional<ClientIdentity> getIdentity(Player player) {
        return sessions.values().stream().filter(sessionState -> sessionState.identity.id().equals(player.identity.id()))
                .findAny()
                .map(sessionState -> sessionState.identity);
    }

    public Optional<ClientIdentity> getIdentityByMinecraftPlayerId(UUID playerId) {
        return sessions.values().stream().filter(sessionState -> sessionState.minecraftPlayer != null && sessionState.minecraftPlayer.id.equals(playerId))
                .findAny()
                .map(sessionState -> sessionState.identity);
    }

    public Optional<Player> findPlayerByProfile(UUID profile) {
        return sessions.values().stream().filter(sessionState -> sessionState.identity.id().equals(profile))
                .findFirst()
                .map(SessionState::toPlayer);
    }

    public long count() {
        return sessions.size();
    }

    public static final class SessionState {
        @Nonnull
        public final Session session;
        @Nonnull
        public final ClientIdentity identity;
        @Nullable
        public final MinecraftPlayer minecraftPlayer;

        public SessionState(@Nonnull Session session, @Nonnull ClientIdentity identity, @Nullable MinecraftPlayer minecraftPlayer) {
            this.session = session;
            this.identity = identity;
            this.minecraftPlayer = minecraftPlayer;
        }

        public Player toPlayer() {
            return new Player(identity, minecraftPlayer);
        }
    }
}
