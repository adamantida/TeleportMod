package com.example.teleportmod;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.Mod.EventHandler;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;
import net.minecraft.entity.Entity;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Configuration;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.world.Teleporter;
import net.minecraft.world.WorldServer;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

@Mod(modid = TeleportMod.MODID, name = TeleportMod.NAME, version = TeleportMod.VERSION,
        acceptableRemoteVersions = "*")
public class TeleportMod {
    public static final String MODID = "teleportmod";
    public static final String NAME = "Teleport Mod";
    public static final String VERSION = "1.1";

    public static int maxHomes;
    public static int teleportDelay;
    public static int requestExpire;
    public static boolean enableWarpForAll;
    public static ScheduledExecutorService scheduler;

    private static File dataDir;
    private static Map<UUID, Map<String, HomePoint>> playerHomes = new ConcurrentHashMap<UUID, Map<String, HomePoint>>();
    private static Map<String, WarpPoint> warps = new ConcurrentHashMap<String, WarpPoint>();
    public static Map<UUID, TPARequest> tpaRequests = new ConcurrentHashMap<UUID, TPARequest>();
    public static Map<UUID, ScheduledFuture<?>> pendingTeleports = new ConcurrentHashMap<>();

    // --- EVENTS ---
    @EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        loadConfig(event.getSuggestedConfigurationFile());
    }

    @EventHandler
    public void serverStart(FMLServerStartingEvent event) {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        File worldDir = event.getServer().worldServers[0].getSaveHandler().getWorldDirectory();
        dataDir = new File(worldDir, "teleportmod_data");
        if (!dataDir.exists()) dataDir.mkdirs();
        loadAllData();
        // register commands
        event.registerServerCommand(new HomeCommand());
        event.registerServerCommand(new SetHomeCommand());
        event.registerServerCommand(new DelHomeCommand());
        event.registerServerCommand(new HomesCommand());
        event.registerServerCommand(new WarpCommand());
        event.registerServerCommand(new SetWarpCommand());
        event.registerServerCommand(new DelWarpCommand());
        event.registerServerCommand(new WarpsCommand());
        event.registerServerCommand(new TpaCommand());
        event.registerServerCommand(new TpAcceptCommand());
        event.registerServerCommand(new TpHereCommand());
        event.registerServerCommand(new TpDenyCommand());

        new TeleportCancelListener();
    }

    @EventHandler
    public void serverStop(FMLServerStoppingEvent event) {
        scheduler.shutdownNow();
        saveAllData();
    }

    // --- CONFIG ---
    private void loadConfig(File file) {
        Configuration cfg = new Configuration(file);
        cfg.load();
        maxHomes = cfg.getInt("maxHomes", Configuration.CATEGORY_GENERAL, 3, 1, 100, "Maximum homes per player");
        teleportDelay = cfg.getInt("teleportDelay", Configuration.CATEGORY_GENERAL, 5, 0, 60, "Teleport delay in seconds");
        requestExpire = cfg.getInt("requestExpire", Configuration.CATEGORY_GENERAL, 30, 5, 300, "TPA timeout in seconds");
        // FIX: Changed default to true to allow all players to use warp
        enableWarpForAll = cfg.getBoolean("enableWarpForAll", Configuration.CATEGORY_GENERAL, true, "Allow all to warp");
        if (cfg.hasChanged()) cfg.save();
    }

    // --- DATA HANDLING ---
    private static void saveAllData() {
        savePlayers();
        saveWarps();
    }

    private static void savePlayers() {
        for (UUID uuid : playerHomes.keySet()) {
            File f = new File(dataDir, uuid + ".homes.dat");
            FileOutputStream fos = null;
            try {
                fos = new FileOutputStream(f);
                NBTTagCompound root = new NBTTagCompound();
                NBTTagList list = new NBTTagList();
                for (Map.Entry<String, HomePoint> e : playerHomes.get(uuid).entrySet()) {
                    NBTTagCompound tag = new NBTTagCompound();
                    tag.setString("name", e.getKey());
                    HomePoint p = e.getValue();
                    tag.setInteger("dim", p.dim);
                    tag.setDouble("x", p.x);
                    tag.setDouble("y", p.y);
                    tag.setDouble("z", p.z);
                    tag.setFloat("yaw", p.yaw);
                    tag.setFloat("pitch", p.pitch);
                    list.appendTag(tag);
                }
                root.setTag("homes", list);
                CompressedStreamTools.writeCompressed(root, fos);
            } catch (IOException ex) {
                ex.printStackTrace();
            } finally {
                if (fos != null) try {
                    fos.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static void saveWarps() {
        File f = new File(dataDir, "warps.dat");
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(f);
            NBTTagCompound root = new NBTTagCompound();
            NBTTagList list = new NBTTagList();
            for (Map.Entry<String, WarpPoint> e : warps.entrySet()) {
                NBTTagCompound tag = new NBTTagCompound();
                tag.setString("name", e.getKey());
                WarpPoint p = e.getValue();
                tag.setInteger("dim", p.dim);
                tag.setDouble("x", p.x);
                tag.setDouble("y", p.y);
                tag.setDouble("z", p.z);
                tag.setFloat("yaw", p.yaw);
                tag.setFloat("pitch", p.pitch);
                list.appendTag(tag);
            }
            root.setTag("warps", list);
            CompressedStreamTools.writeCompressed(root, fos);
        } catch (IOException ex) {
            ex.printStackTrace();
        } finally {
            if (fos != null) try {
                fos.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void loadAllData() {
        playerHomes.clear();
        warps.clear();
        tpaRequests.clear();
        // load players
        File[] files = dataDir.listFiles(new FilenameFilter() {
            public boolean accept(File dir, String name) {
                return name.endsWith(".homes.dat");
            }
        });
        if (files != null) for (File f : files) {
            FileInputStream fis = null;
            try {
                fis = new FileInputStream(f);
                NBTTagCompound root = CompressedStreamTools.readCompressed(fis);
                NBTTagList list = root.getTagList("homes", 10);
                UUID uuid = UUID.fromString(f.getName().replace(".homes.dat", ""));
                Map<String, HomePoint> map = new ConcurrentHashMap<String, HomePoint>();
                for (int i = 0; i < list.tagCount(); i++) {
                    NBTTagCompound tag = list.getCompoundTagAt(i);
                    map.put(tag.getString("name"), new HomePoint(
                            tag.getInteger("dim"), tag.getDouble("x"), tag.getDouble("y"), tag.getDouble("z"),
                            tag.getFloat("yaw"), tag.getFloat("pitch")
                    ));
                }
                playerHomes.put(uuid, map);
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                if (fis != null) try {
                    fis.close();
                } catch (IOException ignored) {
                }
            }
        }
        // load warps
        File wf = new File(dataDir, "warps.dat");
        if (wf.exists()) {
            FileInputStream fis = null;
            try {
                fis = new FileInputStream(wf);
                NBTTagCompound root = CompressedStreamTools.readCompressed(fis);
                NBTTagList list = root.getTagList("warps", 10);
                for (int i = 0; i < list.tagCount(); i++) {
                    NBTTagCompound tag = list.getCompoundTagAt(i);
                    warps.put(tag.getString("name"), new WarpPoint(
                            tag.getInteger("dim"), tag.getDouble("x"), tag.getDouble("y"), tag.getDouble("z"),
                            tag.getFloat("yaw"), tag.getFloat("pitch")
                    ));
                }
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                if (fis != null) try {
                    fis.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    // --- DATA STRUCTURES ---
    private static class HomePoint {
        int dim;
        double x, y, z;
        float yaw, pitch;

        HomePoint(int dim, double x, double y, double z, float yaw, float pitch) {
            this.dim = dim;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }

    private static class WarpPoint extends HomePoint {
        WarpPoint(int dim, double x, double y, double z, float yaw, float pitch) {
            super(dim, x, y, z, yaw, pitch);
        }
    }

    public static class TPARequest {
        UUID sender;
        long timestamp;
        boolean isHere;

        TPARequest(UUID sender, long timestamp, boolean isHere) {
            this.sender = sender;
            this.timestamp = timestamp;
            this.isHere = isHere;
        }
    }

    // --- COMMANDS ---
    private static abstract class BaseCommand extends CommandBase {
        @Override
        public boolean canCommandSenderUseCommand(ICommandSender sender) {
            return sender instanceof EntityPlayerMP;
        }

        @Override
        public int getRequiredPermissionLevel() {
            return 0; // Allow all players to use
        }
    }

    public static class HomeCommand extends TeleportMod.BaseCommand {
        @Override public String getCommandName() { return "home"; }
        @Override public String getCommandUsage(ICommandSender s) { return "/home [name]"; }
        @Override public void processCommand(ICommandSender sender, String[] args) {
            EntityPlayerMP player = (EntityPlayerMP) sender;
            String name = args.length>0?args[0].toLowerCase():"default";
            TeleportMod.HomePoint p = TeleportMod.playerHomes
                    .getOrDefault(player.getUniqueID(), new ConcurrentHashMap<>())
                    .get(name);
            if(p==null){ player.addChatMessage(new ChatComponentText("§cHome not found")); return; }
            Runnable tp = ()->{
                TeleportMod.teleport(player,p);
                player.addChatMessage(new ChatComponentText("§aTeleported to home '"+name+"'")); };
            if(TeleportMod.teleportDelay>0){
                player.addChatMessage(new ChatComponentText("§eTeleporting in "+TeleportMod.teleportDelay+"s"));
                ScheduledFuture<?> f = TeleportMod.scheduler.schedule(tp,TeleportMod.teleportDelay,TimeUnit.SECONDS);
                TeleportMod.pendingTeleports.put(player.getUniqueID(),f);
            } else tp.run();
        }
    }


    private static class SetHomeCommand extends BaseCommand {
        @Override
        public String getCommandName() {
            return "sethome";
        }

        @Override
        public String getCommandUsage(ICommandSender s) {
            return "/sethome <name>";
        }

        @Override
        public void processCommand(ICommandSender sender, String[] args) {
            EntityPlayerMP player = (EntityPlayerMP) sender;
            if (args.length != 1) {
                player.addChatMessage(new ChatComponentText("§cUsage: /sethome <name>"));
                return;
            }
            String name = args[0].toLowerCase();
            Map<String, HomePoint> homes = playerHomes.get(player.getUniqueID());
            if (homes == null) {
                homes = new ConcurrentHashMap<String, HomePoint>();
                playerHomes.put(player.getUniqueID(), homes);
            }
            if (homes.size() >= maxHomes && !homes.containsKey(name)) {
                player.addChatMessage(new ChatComponentText("§cMax homes reached"));
                return;
            }
            homes.put(name, new HomePoint(player.dimension, player.posX, player.posY, player.posZ, player.rotationYaw, player.rotationPitch));
            savePlayers();
            player.addChatMessage(new ChatComponentText("§aHome set: '" + name + "'"));
        }
    }

    private static class DelHomeCommand extends BaseCommand {
        @Override
        public String getCommandName() {
            return "delhome";
        }

        @Override
        public String getCommandUsage(ICommandSender s) {
            return "/delhome <name>";
        }

        @Override
        public void processCommand(ICommandSender sender, String[] args) {
            EntityPlayerMP player = (EntityPlayerMP) sender;
            if (args.length != 1) {
                player.addChatMessage(new ChatComponentText("§cUsage: /delhome <name>"));
                return;
            }
            Map<String, HomePoint> homes = playerHomes.get(player.getUniqueID());
            if (homes != null) homes.remove(args[0].toLowerCase());
            savePlayers();
            player.addChatMessage(new ChatComponentText("§aHome deleted: '" + args[0].toLowerCase() + "'"));
        }
    }

    private static class HomesCommand extends BaseCommand {
        @Override
        public String getCommandName() {
            return "homes";
        }

        @Override
        public String getCommandUsage(ICommandSender s) {
            return "/homes";
        }

        @Override
        public void processCommand(ICommandSender sender, String[] args) {
            EntityPlayerMP player = (EntityPlayerMP) sender;
            Map<String, HomePoint> homes = playerHomes.get(player.getUniqueID());
            if (homes == null || homes.isEmpty()) {
                player.addChatMessage(new ChatComponentText("§eNo homes set"));
                return;
            }
            StringBuilder sb = new StringBuilder("§aHomes: §e");
            int i = 0;
            for (String n : homes.keySet()) {
                sb.append(n);
                if (++i < homes.size()) sb.append(", ");
            }
            player.addChatMessage(new ChatComponentText(sb.toString()));
        }
    }

    public static class WarpCommand extends TeleportMod.BaseCommand {
        @Override public String getCommandName() { return "warp"; }
        @Override public String getCommandUsage(ICommandSender s) { return "/warp <name>"; }
        @Override public void processCommand(ICommandSender sender,String[]args){
            EntityPlayerMP player=(EntityPlayerMP)sender;
            if(args.length!=1){TeleportMod.sendMessage(player,"§cUsage: /warp <name>");return;}
            if(!TeleportMod.enableWarpForAll && !MinecraftServer.getServer()
                    .getConfigurationManager().func_152596_g(player.getGameProfile())){
                TeleportMod.sendMessage(player,"§cNo permission");return;}
            TeleportMod.WarpPoint p = TeleportMod.warps.get(args[0].toLowerCase());
            if(p==null){TeleportMod.sendMessage(player,"§cWarp not found");return;}
            Runnable tp=()->{TeleportMod.teleport(player,p);
                TeleportMod.sendMessage(player,"§aWarped to '"+args[0].toLowerCase()+"'");};
            if(TeleportMod.teleportDelay>0){
                TeleportMod.sendMessage(player,"§eTeleporting in "+TeleportMod.teleportDelay+"s");
                ScheduledFuture<?> f=TeleportMod.scheduler.schedule(tp,TeleportMod.teleportDelay,TimeUnit.SECONDS);
                TeleportMod.pendingTeleports.put(player.getUniqueID(),f);
            } else tp.run();
        }
    }

    private static class SetWarpCommand extends BaseCommand {
        @Override
        public String getCommandName() {
            return "setwarp";
        }

        @Override
        public String getCommandUsage(ICommandSender s) {
            return "/setwarp <name>";
        }

        @Override
        public void processCommand(ICommandSender sender, String[] args) {
            EntityPlayerMP player = (EntityPlayerMP) sender;
            if (args.length != 1) {
                player.addChatMessage(new ChatComponentText("§cUsage: /setwarp <name>"));
                return;
            }
            if (!MinecraftServer.getServer().getConfigurationManager().func_152596_g(player.getGameProfile())) {
                player.addChatMessage(new ChatComponentText("§cAdmin only"));
                return;
            }
            warps.put(args[0].toLowerCase(), new WarpPoint(player.dimension, player.posX, player.posY, player.posZ, player.rotationYaw, player.rotationPitch));
            saveWarps();
            player.addChatMessage(new ChatComponentText("§aWarp set: '" + args[0].toLowerCase() + "'"));
        }
    }

    private static class DelWarpCommand extends BaseCommand {
        @Override
        public String getCommandName() {
            return "delwarp";
        }

        @Override
        public String getCommandUsage(ICommandSender s) {
            return "/delwarp <name>";
        }

        @Override
        public void processCommand(ICommandSender sender, String[] args) {
            EntityPlayerMP player = (EntityPlayerMP) sender;
            if (args.length != 1) {
                player.addChatMessage(new ChatComponentText("§cUsage: /delwarp <name>"));
                return;
            }
            if (!MinecraftServer.getServer().getConfigurationManager().func_152596_g(player.getGameProfile())) {
                player.addChatMessage(new ChatComponentText("§cAdmin only"));
                return;
            }
            warps.remove(args[0].toLowerCase());
            saveWarps();
            player.addChatMessage(new ChatComponentText("§aWarp deleted: '" + args[0].toLowerCase() + "'"));
        }
    }

    private static class WarpsCommand extends CommandBase {
        @Override
        public String getCommandName() {
            return "warps";
        }

        @Override
        public String getCommandUsage(ICommandSender s) {
            return "/warps";
        }

        @Override
        public int getRequiredPermissionLevel() {
            return 0;
        }

        @Override
        public void processCommand(ICommandSender sender, String[] args) {
            StringBuilder sb = new StringBuilder("§aWarps: §e");
            int count = 0;
            for (String n : warps.keySet()) {
                sb.append(n);
                if (++count < warps.size()) sb.append(", ");
            }
            sender.addChatMessage(new ChatComponentText((warps.isEmpty() ? "§eNo warps" : sb.toString())));
        }

        @Override
        public boolean canCommandSenderUseCommand(ICommandSender s) {
            return true;
        }
    }

    public static class TpaCommand extends TeleportMod.BaseCommand {
        @Override public String getCommandName() { return "tpa"; }
        @Override public String getCommandUsage(ICommandSender s) { return "/tpa <player>"; }
        @Override public void processCommand(ICommandSender sender, String[] args) {
            EntityPlayerMP player=(EntityPlayerMP)sender;
            if(args.length!=1){ TeleportMod.sendMessage(player,"§cUsage: /tpa <player>"); return; }
            EntityPlayerMP target = TeleportMod.findPlayer(args[0]);
            if(target==null||target.getUniqueID().equals(player.getUniqueID())){
                TeleportMod.sendMessage(player,"§cPlayer not found or self"); return; }
            TeleportMod.tpaRequests.put(target.getUniqueID(),
                    new TeleportMod.TPARequest(player.getUniqueID(),System.currentTimeMillis(),false));
            TeleportMod.sendMessage(player,"§aRequest sent to §e"+TeleportMod.getDisplayName(target));
            TeleportMod.sendMessage(target,"§e"+TeleportMod.getDisplayName(player)+" §fwants to teleport to you");
            TeleportMod.sendMessage(target,"§fType §a/tpaccept §for §c/tpdeny");
        }
        @Override public List<String> addTabCompletionOptions(ICommandSender s,String[]a){
            return a.length==1?TeleportMod.getListOfPlayersMatching(a[0]):null;
        }
    }

    public static class TpAcceptCommand extends TeleportMod.BaseCommand {
        @Override
        public String getCommandName() {
            return "tpaccept";
        }

        @Override
        public String getCommandUsage(ICommandSender sender) {
            return "/tpaccept";
        }

        @Override
        public boolean canCommandSenderUseCommand(ICommandSender sender) {
            return sender instanceof EntityPlayerMP;
        }

        @Override
        public void processCommand(ICommandSender sender, String[] args) {
            EntityPlayerMP accepter = (EntityPlayerMP) sender;
            UUID accepterId = accepter.getUniqueID();
            TeleportMod.TPARequest req = TeleportMod.tpaRequests.get(accepterId);

            if (req == null) {
                TeleportMod.sendMessage(accepter, "§cNo pending requests");
                return;
            }

            if ((System.currentTimeMillis() - req.timestamp) / 1000 > TeleportMod.requestExpire) {
                TeleportMod.sendMessage(accepter, "§cRequest expired");
                TeleportMod.tpaRequests.remove(accepterId);
                return;
            }

            EntityPlayerMP senderPlayer = TeleportMod.getPlayerByUUID(req.sender);
            if (senderPlayer == null || senderPlayer.isDead || senderPlayer.playerNetServerHandler == null) {
                TeleportMod.sendMessage(accepter, "§cPlayer is no longer online");
                TeleportMod.tpaRequests.remove(accepterId);
                return;
            }

            // Определяем, кто телепортируется
            boolean teleportSender = !req.isHere;
            EntityPlayerMP moving = teleportSender ? senderPlayer : accepter;

            Runnable tpTask = () -> {
                if (req.isHere) {
                    TeleportMod.teleport(accepter, TeleportMod.getPlayerPoint(senderPlayer));
                    TeleportMod.sendMessage(accepter, "§aTeleported to §e" + TeleportMod.getDisplayName(senderPlayer));
                    TeleportMod.sendMessage(senderPlayer, "§a" + TeleportMod.getDisplayName(accepter) + " §fteleported to you");
                } else {
                    TeleportMod.teleport(senderPlayer, TeleportMod.getPlayerPoint(accepter));
                    TeleportMod.sendMessage(senderPlayer, "§aTeleported to §e" + TeleportMod.getDisplayName(accepter));
                    TeleportMod.sendMessage(accepter, "§a" + TeleportMod.getDisplayName(senderPlayer) + " §fteleported to you");
                }
            };

            // Отправляем время только тому, кто вызывает телепорт
            if (TeleportMod.teleportDelay > 0) {
                TeleportMod.sendMessage(moving, "§eTeleporting in " + TeleportMod.teleportDelay + "s");
                ScheduledFuture<?> future = TeleportMod.scheduler.schedule(
                        tpTask, TeleportMod.teleportDelay, TimeUnit.SECONDS
                );
                TeleportMod.pendingTeleports.put(moving.getUniqueID(), future);
            } else {
                tpTask.run();
            }

            TeleportMod.tpaRequests.remove(accepterId);
        }
    }

    public static class TpHereCommand extends TeleportMod.BaseCommand {
        @Override
        public String getCommandName() {
            return "tpahere";
        }

        @Override
        public String getCommandUsage(ICommandSender sender) {
            return "/tpahere <player>";
        }

        @Override
        public void processCommand(ICommandSender sender, String[] args) {
            EntityPlayerMP player = (EntityPlayerMP) sender;
            if (args.length != 1) {
                TeleportMod.sendMessage(player, "§cUsage: /tpahere <player>");
                return;
            }

            String targetName = args[0];
            EntityPlayerMP target = TeleportMod.findPlayer(targetName);
            if (target == null) {
                TeleportMod.sendMessage(player, "§cPlayer not found: " + targetName);
                return;
            }

            TeleportMod.tpaRequests.put(
                    target.getUniqueID(),
                    new TeleportMod.TPARequest(player.getUniqueID(), System.currentTimeMillis(), true)
            );

            TeleportMod.sendMessage(player, "§aRequest sent to §e" + TeleportMod.getDisplayName(target));
            TeleportMod.sendMessage(target, "§e" + TeleportMod.getDisplayName(player) + " §fwants you to teleport to them");
            TeleportMod.sendMessage(target, "§fType §a/tpaccept §for §c/tpdeny");
        }

        @Override
        public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
            return args.length == 1 ? TeleportMod.getListOfPlayersMatching(args[0]) : null;
        }
    }

    private static class TpDenyCommand extends BaseCommand {
        @Override
        public String getCommandName() {
            return "tpdeny";
        }

        @Override
        public String getCommandUsage(ICommandSender s) {
            return "/tpdeny";
        }

        @Override
        public void processCommand(ICommandSender sender, String[] args) {
            EntityPlayerMP player = (EntityPlayerMP) sender;
            UUID playerId = player.getUniqueID();
            TPARequest req = tpaRequests.remove(playerId);

            if (req != null) {
                sendMessage(player, "§aRequest denied");

                EntityPlayerMP src = getPlayerByUUID(req.sender);
                if (src != null && src.playerNetServerHandler != null) {
                    sendMessage(src, "§c" + getDisplayName(player) + " §fdenied your request");
                }
            } else {
                sendMessage(player, "§cNo pending requests");
            }
        }
    }

    // Вспомогательные методы
    private static void sendMessage(EntityPlayerMP player, String message) {
        player.addChatMessage(new ChatComponentText(message));
    }

    private static String getDisplayName(EntityPlayerMP player) {
        return player.getCommandSenderName();
    }

    private static EntityPlayerMP findPlayer(String name) {
        for (Object obj : MinecraftServer.getServer().getConfigurationManager().playerEntityList) {
            EntityPlayerMP p = (EntityPlayerMP) obj;
            if (p.getCommandSenderName().equalsIgnoreCase(name)) {
                return p;
            }
        }
        return null;
    }

    // --- TELEPORT UTILITY ---
    private static void teleport(EntityPlayerMP player, HomePoint p) {
        if (player.dimension != p.dim) {
            WorldServer world = MinecraftServer.getServer().worldServerForDimension(p.dim);
            MinecraftServer.getServer().getConfigurationManager().transferPlayerToDimension(
                    player,
                    p.dim,
                    new SimpleTeleporter(world, p.x, p.y, p.z, p.yaw, p.pitch)
            );
        } else {
            player.playerNetServerHandler.setPlayerLocation(p.x, p.y, p.z, p.yaw, p.pitch);
        }
    }

    private static class SimpleTeleporter extends Teleporter {
        private final double x, y, z;
        private final float yaw, pitch;

        public SimpleTeleporter(WorldServer world, double x, double y, double z, float yaw, float pitch) {
            super(world);
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
        }

        @Override
        public void placeInPortal(Entity entity, double x, double y, double z, float yaw) {
            entity.setLocationAndAngles(this.x, this.y, this.z, this.yaw, this.pitch);
            entity.motionX = entity.motionY = entity.motionZ = 0.0F;
        }
    }

    private static HomePoint getPlayerPoint(EntityPlayerMP player) {
        return new HomePoint(
                player.dimension,
                player.posX,
                player.posY,
                player.posZ,
                player.rotationYaw,
                player.rotationPitch
        );
    }

    public static EntityPlayerMP getPlayerByUUID(UUID uuid) {
        for (Object obj : MinecraftServer.getServer().getConfigurationManager().playerEntityList) {
            EntityPlayerMP player = (EntityPlayerMP) obj;
            if (player.getUniqueID().equals(uuid)) {
                return player;
            }
        }
        return null;
    }
    private static List<String> getListOfPlayersMatching(String partialName) {
        List<String> matches = new ArrayList<String>();
        String search = partialName.toLowerCase();

        for (Object obj : MinecraftServer.getServer().getConfigurationManager().playerEntityList) {
            EntityPlayerMP player = (EntityPlayerMP) obj;
            String name = player.getCommandSenderName();
            if (name.toLowerCase().startsWith(search)) {
                matches.add(name);
            }
        }

        return matches;
    }
}