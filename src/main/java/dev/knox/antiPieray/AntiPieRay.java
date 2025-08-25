package dev.knox.antiPieray;

import io.netty.channel.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import net.kyori.adventure.text.Component;

import java.lang.reflect.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;

/**
 * Paper/Folia 1.21 single-file plugin: AntiPieRay
 * <p>
 * Prevents base-finding via the F3 debug pie by dropping invisible restricted block
 * entity packets before they reach the client. Netty outbound interception + fast ray cast.
 */
public final class AntiPieRay extends JavaPlugin implements Listener {
    // --- Config keys ---
    private static final String CFG_RADIUS = "radius"; // double
    private static final String CFG_RESTRICTED = "restricted-entities"; // List<String>
    private static final String CFG_DEBUG = "debug"; // boolean
    private static final String CFG_CACHE_SIZE = "visibility-cache-size"; // int (per-player)
    private static final String CFG_CACHE_TTL_MS = "visibility-cache-ttl"; // long ms
    private static final String CFG_FOLIA_FORCE = "folia-force-scheduler"; // boolean

    // --- Defaults ---
    private static final double DEFAULT_RADIUS = 64.0D;
    private static final int DEFAULT_CACHE_SIZE = 512;
    private static final long DEFAULT_CACHE_TTL_MS = 5_000L;

    // --- Runtime config ---
    private volatile double maxRadiusSq;
    private final Set<String> restricted = ConcurrentHashMap.newKeySet(96);
    private volatile boolean debug;
    private volatile boolean foliaForceScheduler;

    // visibility cache instance (rebuilt on reload to apply new size/ttl)
    private volatile VisibilityCache visibilityCache = new VisibilityCache(DEFAULT_CACHE_SIZE, Duration.ofMillis(DEFAULT_CACHE_TTL_MS));

    // --- Reflection handles for NMS classes/fields ---
    private static final String PKT_FQCN = "net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket";
    private static Class<?> pktClass; private static Method m_getPos, m_getTag, m_getType;
    private static Method m_bp_getX, m_bp_getY, m_bp_getZ; private static Class<?> nbtCompoundClass; private static Method m_nbt_getString;
    private static Method m_be_toString; private static Method m_getHandle;

    @Override public void onEnable() {
        saveDefaultConfigIfMissing();
        loadRuntimeConfig();
        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("antipieray") != null) Objects.requireNonNull(getCommand("antipieray")).setExecutor((sender, cmd, label, args) -> {
            if (args.length == 1 && "reload".equalsIgnoreCase(args[0])) { reloadConfig(); loadRuntimeConfig(); sender.sendMessage(Component.text("[AntiPieRay] Config reloaded.")); return true; }
            sender.sendMessage(Component.text("AntiPieRay v"+getDescription().getVersion()+" — /"+label+" reload")); return true; });
        tryInitNmsHandles();
        for (Player p : Bukkit.getOnlinePlayers()) tryInject(p);
        getLogger().info("AntiPieRay enabled");
    }

    @Override public void onDisable() { for (Player p : Bukkit.getOnlinePlayers()) tryRemove(p); visibilityCache.clear(); getLogger().info("AntiPieRay disabled"); }

    private void saveDefaultConfigIfMissing() {
        if (!getDataFolder().exists()) {
            if (!getDataFolder().mkdirs()) {
                getLogger().warning("Could not create plugin data folder: " + getDataFolder());
            }
        }
        if (getConfig().get(CFG_RADIUS) == null) getConfig().set(CFG_RADIUS, DEFAULT_RADIUS);
        if (getConfig().get(CFG_CACHE_SIZE) == null) getConfig().set(CFG_CACHE_SIZE, DEFAULT_CACHE_SIZE);
        if (getConfig().get(CFG_CACHE_TTL_MS) == null) getConfig().set(CFG_CACHE_TTL_MS, (int) DEFAULT_CACHE_TTL_MS);
        if (getConfig().get(CFG_FOLIA_FORCE) == null) getConfig().set(CFG_FOLIA_FORCE, Boolean.FALSE);
        if (getConfig().get(CFG_DEBUG) == null) getConfig().set(CFG_DEBUG, Boolean.FALSE);
        if (getConfig().get(CFG_RESTRICTED) == null) {
            getConfig().set(CFG_RESTRICTED, Arrays.asList(
                    // A near-complete list of block entity ids in modern MC (1.21)
                    "minecraft:banner", "minecraft:bed", "minecraft:beehive", "minecraft:bell",
                    "minecraft:blast_furnace", "minecraft:brewing_stand", "minecraft:brushable_block",
                    "minecraft:campfire", "minecraft:chest", "minecraft:chiseled_bookshelf",
                    "minecraft:command_block", "minecraft:conduit", "minecraft:crafter",
                    "minecraft:decorated_pot", "minecraft:dispenser", "minecraft:dropper",
                    "minecraft:enchanting_table", "minecraft:end_gateway", "minecraft:end_portal",
                    "minecraft:ender_chest", "minecraft:furnace", "minecraft:hopper",
                    "minecraft:jigsaw", "minecraft:jukebox", "minecraft:lectern", "minecraft:lodestone",
                    "minecraft:piston", "minecraft:respawn_anchor", "minecraft:sculk_catalyst",
                    "minecraft:sculk_sensor", "minecraft:calibrated_sculk_sensor", "minecraft:sculk_shrieker",
                    "minecraft:shulker_box", "minecraft:sign", "minecraft:hanging_sign", "minecraft:skull",
                    "minecraft:smoker", "minecraft:spawner", "minecraft:structure_block",
                    "minecraft:trial_spawner", "minecraft:vault", "minecraft:beacon", "minecraft:barrel",
                    "minecraft:flower_pot"
            ));
        }
        saveConfig();
    }

    private void loadRuntimeConfig() {
        double r = getConfig().getDouble(CFG_RADIUS, DEFAULT_RADIUS); if (r < 4) r = 4; this.maxRadiusSq = r*r;
        this.restricted.clear(); List<String> list = getConfig().getStringList(CFG_RESTRICTED);
        for (String s : list) { if (s!=null){ s=s.trim().toLowerCase(Locale.ROOT); if(!s.isEmpty()) restricted.add(s);} }
        this.debug = getConfig().getBoolean(CFG_DEBUG, false);
        this.foliaForceScheduler = getConfig().getBoolean(CFG_FOLIA_FORCE, false);
        int cacheSize = Math.max(64, getConfig().getInt(CFG_CACHE_SIZE, DEFAULT_CACHE_SIZE));
        long ttlMs = Math.max(100, getConfig().getLong(CFG_CACHE_TTL_MS, DEFAULT_CACHE_TTL_MS));
        this.visibilityCache = new VisibilityCache(cacheSize, Duration.ofMillis(ttlMs));
    }

    @EventHandler public void onJoin(PlayerJoinEvent e) { tryInject(e.getPlayer()); }
    @EventHandler public void onQuit(PlayerQuitEvent e) { tryRemove(e.getPlayer()); visibilityCache.evictPlayer(e.getPlayer().getUniqueId()); }

    // --- Netty Injection ---
    private void tryInject(Player player) {
        try {
            Channel ch = getPlayerChannel(player); if (ch == null) return;
            final String name = "AntiPieRay_packet_handler";
            if (ch.pipeline().get(name) == null) {
                ch.pipeline().addBefore("packet_handler", name, new PacketHandler(this, player.getUniqueId()));
                if (debug) getLogger().info("Injected handler for " + player.getName());
            }
        } catch (Throwable t) { getLogger().log(Level.WARNING, "Failed to inject Netty for " + player.getName(), t); }
    }
    private void tryRemove(Player player) {
        try { Channel ch = getPlayerChannel(player); if (ch == null) return; final String name = "AntiPieRay_packet_handler"; if (ch.pipeline().get(name)!=null) ch.pipeline().remove(name); } catch (Throwable ignored) {}
    }

    private Channel getPlayerChannel(Player p) {
        try {
            if (m_getHandle == null) m_getHandle = p.getClass().getMethod("getHandle");
            Object handle = m_getHandle.invoke(p);
            Field f_connection = findField(handle.getClass(), "connection"); Object sListener = f_connection.get(handle);
            Field f_conn2 = findField(sListener.getClass(), "connection"); Object connection = f_conn2.get(sListener);
            Field f_ch = findField(connection.getClass(), "channel"); return (Channel) f_ch.get(connection);
        } catch (Throwable t) { if (debug) getLogger().log(Level.WARNING, "Channel reflect fail for " + p.getName(), t); return null; }
    }
    private static Field findField(Class<?> c, String name) throws Exception { Field f = c.getDeclaredField(name); f.setAccessible(true); return f; }

    // --- Packet Handler ---
    private final class PacketHandler extends ChannelDuplexHandler {
        private final AntiPieRay plugin; private final UUID playerId; private volatile Player bukkitPlayer;
        PacketHandler(AntiPieRay plugin, UUID id) { this.plugin = plugin; this.playerId = id; }
        @Override public void channelInactive(ChannelHandlerContext ctx) throws Exception { super.channelInactive(ctx); plugin.visibilityCache.evictPlayer(playerId); }
        @Override public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            try {
                if (pktClass != null && pktClass.isInstance(msg)) {
                    Player pl = bukkitPlayer; if (pl == null) { pl = Bukkit.getPlayer(playerId); bukkitPlayer = pl; }
                    if (pl == null || !pl.isOnline()) { super.write(ctx, msg, promise); return; }

                    int bx, by, bz; Object tag = null, typeObj = null;
                    try {
                        Object bp = (m_getPos != null) ? m_getPos.invoke(msg) : null; if (bp == null) { super.write(ctx, msg, promise); return; }
                        bx = (int) m_bp_getX.invoke(bp); by = (int) m_bp_getY.invoke(bp); bz = (int) m_bp_getZ.invoke(bp);
                        if (m_getTag != null) tag = m_getTag.invoke(msg); if (m_getType != null) typeObj = m_getType.invoke(msg);
                    } catch (Throwable ignore) { super.write(ctx, msg, promise); return; }

                    Location eye = pl.getEyeLocation(); double dx = (bx + 0.5D) - eye.getX(), dy = (by + 0.5D) - eye.getY(), dz = (bz + 0.5D) - eye.getZ();
                    if (dx*dx + dy*dy + dz*dz > maxRadiusSq) { super.write(ctx, msg, promise); return; }

                    String key = resolveBlockEntityKey(typeObj, tag); if (key == null || !restricted.contains(key)) { super.write(ctx, msg, promise); return; }

                    long posKey = pack(bx, by, bz); Boolean cached = visibilityCache.get(playerId, posKey);
                    if (cached != null) { if (cached) super.write(ctx, msg, promise); else promise.setSuccess(); return; }

                    final Player fpl = pl; final int fx=bx,fy=by,fz=bz; final long fPosKey = posKey;
                    final CompletableFuture<Boolean> cf = new CompletableFuture<>();
                    if (foliaForceScheduler || hasFoliaScheduler(fpl)) {
                        try { fpl.getScheduler().run(plugin, t -> { boolean vis = FastRayCast.visible(fpl.getWorld(), fpl.getEyeLocation(), fx+0.5D, fy+0.5D, fz+0.5D); visibilityCache.put(playerId,fPosKey,vis); cf.complete(vis); }, null); }
                        catch (Throwable ex) { // graceful fallback
                            Bukkit.getScheduler().runTask(plugin, () -> { boolean vis = FastRayCast.visible(fpl.getWorld(), fpl.getEyeLocation(), fx+0.5D, fy+0.5D, fz+0.5D); visibilityCache.put(playerId,fPosKey,vis); cf.complete(vis); });
                        }
                    } else {
                        Bukkit.getScheduler().runTask(plugin, () -> { boolean vis = FastRayCast.visible(fpl.getWorld(), fpl.getEyeLocation(), fx+0.5D, fy+0.5D, fz+0.5D); visibilityCache.put(playerId,fPosKey,vis); cf.complete(vis); });
                    }

                    boolean visible = false; try { visible = cf.get(25L, TimeUnit.MILLISECONDS); } catch (Exception e) { visibilityCache.put(playerId,fPosKey,false); }
                    if (visible) super.write(ctx, msg, promise); else promise.setSuccess();
                    return;
                }
            } catch (Throwable t) { if (debug) getLogger().log(Level.FINE, "AntiPieRay write error", t); }
            super.write(ctx, msg, promise);
        }
    }

    private static boolean hasFoliaScheduler(Player p) { try { p.getScheduler(); return true; } catch (Throwable ignored) { return false; } }

    // --- Reflection bootstrap ---
    private static void tryInitNmsHandles() {
        if (pktClass != null) return;
        try {
            pktClass = Class.forName(PKT_FQCN);
            m_getPos = safeMethod(pktClass, "getPos"); m_getTag = safeMethod(pktClass, "getTag"); if (m_getTag == null) m_getTag = safeMethod(pktClass, "getNbt");
            m_getType = safeMethod(pktClass, "getType");
            Class<?> blockPosClass = Class.forName("net.minecraft.core.BlockPos");
            m_bp_getX = safeMethod(blockPosClass, "getX"); m_bp_getY = safeMethod(blockPosClass, "getY"); m_bp_getZ = safeMethod(blockPosClass, "getZ");
            nbtCompoundClass = Class.forName("net.minecraft.nbt.CompoundTag"); m_nbt_getString = safeMethod(nbtCompoundClass, "getString", String.class);
            Class.forName("net.minecraft.world.level.block.entity.BlockEntityType");
            m_be_toString = Object.class.getMethod("toString");
        } catch (Throwable ignored) { }
    }
    private static Method safeMethod(Class<?> c, String n, Class<?>... p) { try { Method m=c.getMethod(n,p); m.setAccessible(true); return m;}catch(Exception e){try{Method m=c.getDeclaredMethod(n,p);m.setAccessible(true);return m;}catch(Exception ex){return null;}}}

    private String resolveBlockEntityKey(Object typeObj, Object nbtTag) {
        try { if (nbtCompoundClass.isInstance(nbtTag) && m_nbt_getString != null) { String id = (String)m_nbt_getString.invoke(nbtTag, "id"); if (id!=null && !(id=id.trim().toLowerCase(Locale.ROOT)).isEmpty()) return id; } } catch(Throwable ignored){}
        try { if (typeObj!=null) { String s = m_be_toString!=null? String.valueOf(m_be_toString.invoke(typeObj)) : String.valueOf(typeObj); if (s!=null) { s=s.toLowerCase(Locale.ROOT); int idx=s.indexOf("minecraft:"); if(idx>=0){int end=idx+10;while(end<s.length()&&((s.charAt(end)>='a'&&s.charAt(end)<='z')||(s.charAt(end)>='0'&&s.charAt(end)<='9')||s.charAt(end)=='_'||s.charAt(end)==':'||s.charAt(end)=='/'))end++;return s.substring(idx,end);}} } } catch(Throwable ignored){}
        return null;
    }

    // --- Visibility cache (lightweight per-player LRU with TTL) ---
    private static final class VisibilityCache { private final long ttlNanos; private final int capacity; private final ConcurrentHashMap<UUID, PlayerMap> map = new ConcurrentHashMap<>();
        VisibilityCache(int capacity, Duration ttl) { this.capacity = Math.max(64, capacity); this.ttlNanos = ttl.toNanos(); }
        Boolean get(UUID id, long posKey) { PlayerMap pm = map.get(id); return pm==null?null:pm.get(posKey, ttlNanos); }
        void put(UUID id, long posKey, boolean visible) { map.computeIfAbsent(id, k -> new PlayerMap(capacity)).put(posKey, visible); }
        void evictPlayer(UUID id) { map.remove(id); }
        void clear() { map.clear(); }
        private static final class PlayerMap { private final int cap; private final LinkedHashMap<Long, Entry> lru;
            PlayerMap(int cap) { this.cap = cap; this.lru = new LinkedHashMap<>(cap, 0.75f, true) {
                protected boolean removeEldestEntry(Map.Entry<Long, Entry> e) {
                    return size() > PlayerMap.this.cap;
                }
            }; }
            synchronized Boolean get(long k, long ttlNanos) { Entry e = lru.get(k); if (e==null) return null; if (System.nanoTime() - e.t > ttlNanos) { lru.remove(k); return null; } return e.v; }
            synchronized void put(long k, boolean v) { lru.put(k, new Entry(v, System.nanoTime())); }

            private record Entry(boolean v, long t) {
            }
        }
    }

    // --- Fast Ray Cast (integer voxel traversal) ---
    private static final class FastRayCast { private static final int MAX_STEPS = 256; static boolean visible(World w, Location eye, double tx, double ty, double tz) {
        int sx=floor(eye.getX()), sy=floor(eye.getY()), sz=floor(eye.getZ()); int ex=floor(tx), ey=floor(ty), ez=floor(tz); if (sx==ex && sy==ey && sz==ez) return true;
        double x=eye.getX(), y=eye.getY(), z=eye.getZ(); double dx=tx-x, dy=ty-y, dz=tz-z; double ax=Math.abs(dx), ay=Math.abs(dy), az=Math.abs(dz);
        int stepx = dx>0?1:-1, stepy = dy>0?1:-1, stepz = dz>0?1:-1; double tMaxX=intBound(x,dx), tMaxY=intBound(y,dy), tMaxZ=intBound(z,dz), tDeltaX = ax==0?Double.POSITIVE_INFINITY:1.0/ax, tDeltaY = ay==0?Double.POSITIVE_INFINITY:1.0/ay, tDeltaZ = az==0?Double.POSITIVE_INFINITY:1.0/az; int ix=floor(x), iy=floor(y), iz=floor(z);
        for (int i=0;i<MAX_STEPS;i++) { if (!(i==0 && ix==sx && iy==sy && iz==sz)) { Block b = w.getBlockAt(ix,iy,iz); if (b.getType().isOccluding()) return false; } if (ix==ex && iy==ey && iz==ez) return true; if (tMaxX < tMaxY) { if (tMaxX < tMaxZ) { ix += stepx; tMaxX += tDeltaX; } else { iz += stepz; tMaxZ += tDeltaZ; } } else { if (tMaxY < tMaxZ) { iy += stepy; tMaxY += tDeltaY; } else { iz += stepz; tMaxZ += tDeltaZ; } } }
        return true; }
        private static int floor(double d){ int i=(int)d; return d<i?i-1:i; }
        private static double intBound(double s,double ds){ if (ds>0){ double si=Math.floor(s); return ((si+1.0)-s)/ds; } else if (ds<0){ double si=Math.floor(s); return (s-si)/-ds; } else return Double.POSITIVE_INFINITY; }
    }

    // Pack xyz into a long (21 bits for x/z, 20 bits for y)
    private static long pack(int x,int y,int z){ return ((long)(x & 0x1FFFFF) << 43) | ((long)(y & 0xFFFFF) << 23) | (long)(z & 0x7FFFFF); }
}
