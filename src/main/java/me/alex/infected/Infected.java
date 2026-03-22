package me.alex.infected;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;

public final class Infected extends JavaPlugin implements Listener {

    private ItemStack head_xxx;
    private ItemStack head_runner;

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);

        for (var world : Bukkit.getWorlds())
            for (var entity : world.getEntities())
                if (entity instanceof Zombie) entity.remove();

        head_xxx = buildHead("このグローバル変数のxxx部分に、ゾンビのタイプを導入して、カスタムヘッドのテクスチャはBase64で直接指定する");
        head_runner = buildHead("eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNjk1MWQ2YzNlZmM1ZTcwMjMwN2UxYjg5OTBhZmRmODhjZWEzN2I3NjNmMWEyY2U1NzRhOGM2OTE1NjgyMGJmYiJ9fX0=");
    }

    private ItemStack buildHead(String texture) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD); SkullMeta meta = (SkullMeta) head.getItemMeta();
        PlayerProfile profile = Bukkit.createProfile(UUID.randomUUID(), "infected");
        profile.setProperty(new ProfileProperty("textures", texture));
        meta.setPlayerProfile(profile);
        head.setItemMeta(meta);
        return head;
    }

    @EventHandler
    public void onZombieSpawn(CreatureSpawnEvent event) {
        if (!(event.getEntity() instanceof Zombie zombie)) return;

        zombie.getAttribute(org.bukkit.attribute.Attribute.MOVEMENT_SPEED).setBaseValue(0.46);
//        zombie.getAttribute(org.bukkit.attribute.Attribute.ATTACK_REACH).setBaseValue(3.0);
        zombie.getEquipment().setHelmet(head_runner.clone());
        zombie.getEquipment().setHelmetDropChance(0f);

        try {
            Object nmsEntity = zombie.getClass().getMethod("getHandle").invoke(zombie);

            Field fGoal   = findField(nmsEntity.getClass(), "goalSelector");
            Field fTarget = findField(nmsEntity.getClass(), "targetSelector");
            Method mNav   = findMethod(nmsEntity.getClass(), "getNavigation");

            if (fGoal != null)   fGoal.setAccessible(true);
            if (fTarget != null) fTarget.setAccessible(true);

            Object goalSel   = fGoal   != null ? fGoal.get(nmsEntity) : null;
            Object targetSel = fTarget != null ? fTarget.get(nmsEntity) : null;

            Method mRemoveGoal   = goalSel   != null ? findMethod(goalSel.getClass(),   "removeAllGoals") : null;
            Method mRemoveTarget = targetSel != null ? findMethod(targetSel.getClass(), "removeAllGoals") : null;

            new BukkitRunnable() {
                @Override
                public void run() {
                    if (!zombie.isValid() || zombie.isDead()) {
                        cancel();
                        return;
                    }
                    try {
                        var loc  = zombie.getLocation();
                        var vel  = zombie.getVelocity();
                        var world = zombie.getWorld();
                        boolean onGround = zombie.isOnGround();

                        // --- ターゲット取得 ---
                        org.bukkit.entity.Player target = null;
                        double nearest = Double.MAX_VALUE;
                        for (var p : world.getPlayers()) {
                            double d = p.getLocation().distanceSquared(loc);
                            if (d < nearest) { nearest = d; target = p; }
                        }

                        if (target != null) {
                            var toTarget = target.getLocation().toVector().subtract(loc.toVector()).normalize();

                            // --- 2マスの壁を大ジャンプで越える ---
                            if (onGround) {
                                // ゾンビの進行方向の1マス前の壁を確認
                                var ahead1 = loc.clone().add(toTarget.clone().multiply(0.6));
                                var ahead2 = loc.clone().add(toTarget.clone().multiply(1.1));
                                boolean wall1Low  = !world.getBlockAt(ahead1.getBlockX(), loc.getBlockY(),     ahead1.getBlockZ()).isPassable();
                                boolean wall1High = !world.getBlockAt(ahead1.getBlockX(), loc.getBlockY() + 1, ahead1.getBlockZ()).isPassable();
                                boolean wall2Low  = !world.getBlockAt(ahead2.getBlockX(), loc.getBlockY(),     ahead2.getBlockZ()).isPassable();
                                boolean wall2High = !world.getBlockAt(ahead2.getBlockX(), loc.getBlockY() + 1, ahead2.getBlockZ()).isPassable();

                                // 1マス壁 → 通常ジャンプ、2マス壁 → 大ジャンプ
                                if (wall1High || wall2High) {
                                    vel.setY(0.65); // 大ジャンプ（2マス越え）
                                    zombie.setVelocity(vel);
                                } else if (wall1Low || wall2Low) {
                                    vel.setY(0.42);
                                    zombie.setVelocity(vel);
                                }
                            }

                            // --- 2マスの崖を飛び越える ---
                            if (onGround) {
                                var ahead = loc.clone().add(toTarget.clone().multiply(0.8));
                                boolean floorAhead1 = !world.getBlockAt(ahead.getBlockX(), loc.getBlockY() - 1, ahead.getBlockZ()).isPassable();
                                boolean floorAhead2 = !world.getBlockAt(ahead.getBlockX(), loc.getBlockY() - 2, ahead.getBlockZ()).isPassable();
                                boolean noFloorAhead = world.getBlockAt(ahead.getBlockX(), loc.getBlockY() - 1, ahead.getBlockZ()).isPassable();

                                // 2マス以内に床があって1マス目が空なら飛び越える
                                if (noFloorAhead && floorAhead2) {
                                    vel.setX(toTarget.getX() * 0.8);
                                    vel.setY(0.3);
                                    vel.setZ(toTarget.getZ() * 0.8);
                                    zombie.setVelocity(vel);
                                }
                            }

                            // --- プレイヤーの正面をほんの少しだけ避ける ---
                            var playerFacing = target.getLocation().getDirection().normalize();
                            var toZombie = loc.toVector().subtract(target.getLocation().toVector()).normalize();
                            double dot = playerFacing.dot(toZombie); // 1に近いほど正面

                            if (dot > 0.85 && onGround) {
                                // 正面にいるとき → 左右どちらかにわずかにずれる
                                var right = new org.bukkit.util.Vector(
                                        playerFacing.getZ(), 0, -playerFacing.getX()
                                ).normalize();
                                double side = (zombie.getEntityId() % 2 == 0) ? 1 : -1; // 個体ごとに左右固定
                                vel.add(right.multiply(side * 0.12));
                                zombie.setVelocity(vel);
                            }

                        }

                    } catch (Exception e) {
                        getLogger().warning("行動制御失敗: " + e.getMessage());
                    }
                }
            }.runTaskTimer(this, 0L, 2L); // 2tick毎（負荷軽減）

        } catch (Exception e) {
            getLogger().warning("NMS解決失敗: " + e.getMessage());
        }
    }

    private Field findField(Class<?> clazz, String name) {
        while (clazz != null) {
            for (Field f : clazz.getDeclaredFields()) {
                if (f.getName().equals(name)) return f;
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    private Method findMethod(Class<?> clazz, String name) {
        while (clazz != null) {
            for (Method m : clazz.getDeclaredMethods()) {
                if (m.getName().equals(name)) return m;
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    @Override
    public void onDisable() {}
}