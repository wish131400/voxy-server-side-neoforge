package dev.xantha.vss.compat.ftbchunks;

import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.config.VSSServerConfig;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ForcedChunksSavedData;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;

public final class FTBChunksForceLoadCompat {
    private static final Queue<PendingTicket> PENDING = new ArrayDeque<>();
    private static final Object LOCK = new Object();
    private static volatile boolean reflectionReady;
    private static Constructor<?> ticketOwnerConstructor;
    private static Method ticketTrackerAdd;
    private static Method ticketTrackerRemove;
    private static TicketType<Object> entityTickingTicketType;

    private FTBChunksForceLoadCompat() {
    }

    public static boolean tryHandle(ServerLevel level, String modId, UUID owner, int chunkX, int chunkZ, boolean add) {
        VSSServerConfig config = VSSServerConfig.CONFIG;
        if (!config.ftbChunksSafeForceLoad || !"ftbchunks".equals(modId)) {
            return false;
        }
        if (level == null || owner == null || !ModList.get().isLoaded(modId)) {
            return false;
        }

        PendingTicket ticket = new PendingTicket(level, modId, owner, chunkX, chunkZ, add);
        if (!add) {
            removePendingAdd(ticket);
            apply(ticket);
            return true;
        }

        synchronized (LOCK) {
            PENDING.add(ticket);
        }
        return true;
    }

    private static void removePendingAdd(PendingTicket removal) {
        synchronized (LOCK) {
            PENDING.removeIf(ticket -> ticket.add()
                    && ticket.level() == removal.level()
                    && ticket.owner().equals(removal.owner())
                    && ticket.chunkX() == removal.chunkX()
                    && ticket.chunkZ() == removal.chunkZ());
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !VSSServerConfig.CONFIG.ftbChunksSafeForceLoad) {
            return;
        }

        int budget = VSSServerConfig.CONFIG.ftbChunksForceLoadTicketsPerTick;
        while (budget-- > 0) {
            PendingTicket ticket;
            synchronized (LOCK) {
                ticket = PENDING.poll();
            }
            if (ticket == null) {
                return;
            }
            apply(ticket);
        }
    }

    private static void apply(PendingTicket ticket) {
        try {
            if (!initReflection()) {
                return;
            }

            ForcedChunksSavedData saveData = ticket.level().getDataStorage()
                    .computeIfAbsent(ForcedChunksSavedData::load, ForcedChunksSavedData::new, "chunks");
            ChunkPos pos = new ChunkPos(ticket.chunkX(), ticket.chunkZ());
            Object tracker = saveData.getEntityForcedChunks();
            Object owner = ticketOwnerConstructor.newInstance(ticket.modId(), ticket.owner());
            boolean changed = (boolean) (ticket.add() ? ticketTrackerAdd : ticketTrackerRemove)
                    .invoke(tracker, owner, pos.toLong(), true);

            if (changed) {
                saveData.setDirty(true);
                if (ticket.add()) {
                    ticket.level().getChunkSource().addRegionTicket(entityTickingTicketType, pos, 2, owner, true);
                } else {
                    ticket.level().getChunkSource().removeRegionTicket(entityTickingTicketType, pos, 2, owner, true);
                }
            }
        } catch (Throwable t) {
            VSSLogger.warn("FTB Chunks safe force-load compat failed at " + ticket.chunkX() + ", " + ticket.chunkZ(), t);
        }
    }

    private static boolean initReflection() throws ReflectiveOperationException {
        if (reflectionReady) {
            return true;
        }

        Class<?> ownerClass = Class.forName("net.minecraftforge.common.world.ForgeChunkManager$TicketOwner");
        Class<?> trackerClass = Class.forName("net.minecraftforge.common.world.ForgeChunkManager$TicketTracker");
        Class<?> managerClass = Class.forName("net.minecraftforge.common.world.ForgeChunkManager");

        Constructor<?> constructor = ownerClass.getDeclaredConstructor(String.class, Comparable.class);
        constructor.setAccessible(true);

        Method add = trackerClass.getDeclaredMethod("add", ownerClass, long.class, boolean.class);
        add.setAccessible(true);
        Method remove = trackerClass.getDeclaredMethod("remove", ownerClass, long.class, boolean.class);
        remove.setAccessible(true);
        Field entityTicking = managerClass.getDeclaredField("ENTITY_TICKING");
        entityTicking.setAccessible(true);

        ticketOwnerConstructor = constructor;
        ticketTrackerAdd = add;
        ticketTrackerRemove = remove;
        entityTickingTicketType = (TicketType<Object>) entityTicking.get(null);
        reflectionReady = true;
        VSSLogger.info("FTB Chunks safe force-load compat enabled; force-load tickets will be restored without synchronous chunk reads");
        return true;
    }

    private record PendingTicket(ServerLevel level, String modId, UUID owner, int chunkX, int chunkZ, boolean add) {
    }
}
