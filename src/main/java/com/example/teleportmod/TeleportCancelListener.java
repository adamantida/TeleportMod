package com.example.teleportmod;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentText;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

public class TeleportCancelListener {
    // Храним предыдущие позиции для игроков с отложенным телепортом
    private final Map<UUID, double[]> lastPositions = new ConcurrentHashMap<>();

    public TeleportCancelListener() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    // Отмена при получении урона
    @SubscribeEvent
    public void onPlayerHurt(LivingHurtEvent event) {
        if (!(event.entityLiving instanceof EntityPlayerMP)) return;
        cancel(event.entityLiving);
    }

    // Отмена при движении: проверяем каждый тик
    @SubscribeEvent
    public void onLivingUpdate(LivingEvent.LivingUpdateEvent event) {
        if (!(event.entity instanceof EntityPlayerMP)) return;
        EntityPlayerMP player = (EntityPlayerMP) event.entity;
        UUID id = player.getUniqueID();
        // если телепорт не запланирован — очищаем данные и выходим
        if (!TeleportMod.pendingTeleports.containsKey(id)) {
            lastPositions.remove(id);
            return;
        }
        // проверка движения
        double x = player.posX, y = player.posY, z = player.posZ;
        double[] prev = lastPositions.get(id);
        if (prev == null) {
            // первый тик после планирования
            lastPositions.put(id, new double[]{x, y, z});
            return;
        }
        // если игрок двинулся
        if (Math.abs(x - prev[0]) > 0.01 || Math.abs(y - prev[1]) > 0.01 || Math.abs(z - prev[2]) > 0.01) {
            cancel(player);
            lastPositions.remove(id);
        }
    }

    private void cancel(Object entity) {
        EntityPlayerMP player = (EntityPlayerMP) entity;
        UUID id = player.getUniqueID();
        ScheduledFuture<?> future = TeleportMod.pendingTeleports.remove(id);
        if (future != null && !future.isDone()) {
            future.cancel(false);
            player.addChatMessage(new ChatComponentText("§cTeleport cancelled by movement!"));
        }
    }
}
