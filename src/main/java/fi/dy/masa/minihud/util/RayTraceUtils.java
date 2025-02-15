package fi.dy.masa.minihud.util;

import java.util.List;
import java.util.Optional;
import javax.annotation.Nonnull;
import com.llamalad7.mixinextras.lib.apache.commons.tuple.Pair;
import org.jetbrains.annotations.Nullable;

import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.LecternBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.block.entity.EnderChestBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.PiglinEntity;
import net.minecraft.entity.passive.AbstractHorseEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.EnderChestInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import fi.dy.masa.malilib.render.InventoryOverlay;
import fi.dy.masa.malilib.util.Constants;
import fi.dy.masa.malilib.util.EntityUtils;
import fi.dy.masa.malilib.util.InventoryUtils;
import fi.dy.masa.malilib.util.WorldUtils;
import fi.dy.masa.malilib.util.nbt.NbtBlockUtils;
import fi.dy.masa.malilib.util.nbt.NbtKeys;
import fi.dy.masa.minihud.config.Configs;
import fi.dy.masa.minihud.data.EntitiesDataManager;
import fi.dy.masa.minihud.event.RenderHandler;
import fi.dy.masa.minihud.mixin.IMixinAbstractHorseEntity;
import fi.dy.masa.minihud.mixin.IMixinPiglinEntity;

public class RayTraceUtils
{
    private static Pair<BlockPos, InventoryOverlay.Context> lastBlockEntityContext = null;
    private static Pair<Integer,  InventoryOverlay.Context> lastEntityContext = null;

    @Nonnull
    public static HitResult getRayTraceFromEntity(World worldIn, Entity entityIn, boolean useLiquids)
    {
        //double reach = 5.0d;
        double reach = entityIn instanceof PlayerEntity pe ? pe.getBlockInteractionRange() + 1.0d : 5.0d;
        return getRayTraceFromEntity(worldIn, entityIn, useLiquids, reach);
    }

    @Nonnull
    public static HitResult getRayTraceFromEntity(World worldIn, Entity entityIn, boolean useLiquids, double range)
    {
        Vec3d eyesVec = new Vec3d(entityIn.getX(), entityIn.getY() + entityIn.getStandingEyeHeight(), entityIn.getZ());
        Vec3d rangedLookRot = entityIn.getRotationVec(1f).multiply(range);
        Vec3d lookVec = eyesVec.add(rangedLookRot);

        RaycastContext.FluidHandling fluidMode = useLiquids ? RaycastContext.FluidHandling.SOURCE_ONLY : RaycastContext.FluidHandling.NONE;
        RaycastContext context = new RaycastContext(eyesVec, lookVec, RaycastContext.ShapeType.COLLIDER, fluidMode, entityIn);
        HitResult result = worldIn.raycast(context);

        if (result == null)
        {
            result = BlockHitResult.createMissed(Vec3d.ZERO, Direction.UP, BlockPos.ORIGIN);
        }

        Box bb = entityIn.getBoundingBox().expand(rangedLookRot.x, rangedLookRot.y, rangedLookRot.z).expand(1d, 1d, 1d);
        List<Entity> list = worldIn.getOtherEntities(entityIn, bb);

        double closest = result.getType() == HitResult.Type.BLOCK ? eyesVec.distanceTo(result.getPos()) : Double.MAX_VALUE;
        Optional<Vec3d> entityTrace = Optional.empty();
        Entity targetEntity = null;

        for (Entity entity : list)
        {
            bb = entity.getBoundingBox();
            Optional<Vec3d> traceTmp = bb.raycast(lookVec, eyesVec);

            if (traceTmp.isPresent())
            {
                double distance = eyesVec.distanceTo(traceTmp.get());

                if (distance <= closest)
                {
                    targetEntity = entity;
                    entityTrace = traceTmp;
                    closest = distance;
                }
            }
        }

        if (targetEntity != null)
        {
            result = new EntityHitResult(targetEntity, entityTrace.get());
        }

        return result;
    }

    public static @Nullable InventoryOverlay.Context getTargetInventory(MinecraftClient mc, boolean newScreen)
    {
        World world = WorldUtils.getBestWorld(mc);
        Entity cameraEntity = EntityUtils.getCameraEntity();

        if (mc.player == null || world == null)
        {
            return null;
        }

        if (cameraEntity == mc.player && world instanceof ServerWorld)
        {
            // We need to get the player from the server world (if available, ie. in single player),
            // so that the player itself won't be included in the ray trace
            Entity serverPlayer = world.getPlayerByUuid(mc.player.getUuid());

            if (serverPlayer != null)
            {
                cameraEntity = serverPlayer;
            }
        }

        HitResult trace = getRayTraceFromEntity(world, cameraEntity, false);
        NbtCompound nbt = new NbtCompound();

        if (trace.getType() == HitResult.Type.BLOCK)
        {
            BlockPos pos = ((BlockHitResult) trace).getBlockPos();
            BlockState state = world.getBlockState(pos);
            Block blockTmp = state.getBlock();
            BlockEntity be = null;

            // Keep screen from getting 'stuck' if trying to use toggle on a lectern
            if (blockTmp instanceof LecternBlock && !newScreen)
            {
                return null;
            }

            //MiniHUD.logger.warn("getTarget():1: pos [{}], state [{}]", pos.toShortString(), state.toString());

            if (blockTmp instanceof BlockEntityProvider)
            {
                if (world instanceof ServerWorld)
                {
                    be = world.getWorldChunk(pos).getBlockEntity(pos);

                    if (be != null)
                    {
                        nbt = be.createNbtWithIdentifyingData(world.getRegistryManager());
                    }
                }
                else
                {
                    Pair<BlockEntity, NbtCompound> pair = RenderHandler.getInstance().requestBlockEntityAt(world, pos);

                    if (pair != null)
                    {
                        nbt = pair.getRight();

                        if (Configs.Generic.ENTITY_DATA_LOAD_NBT.getBooleanValue())
                        {
                            be = pair.getLeft();
                        }
                    }
                }

                //MiniHUD.logger.warn("getTarget():2: pos [{}], be [{}], nbt [{}]", pos.toShortString(), be != null, nbt != null);
                InventoryOverlay.Context ctx = getTargetInventoryFromBlock(world, pos, be, nbt);
                //dumpContext(ctx);

                if (lastBlockEntityContext != null && !lastBlockEntityContext.getLeft().equals(pos))
                {
                    lastBlockEntityContext = null;
                }

                if (ctx != null && ctx.inv() != null)
                {
                    lastBlockEntityContext = Pair.of(pos, ctx);
                    return ctx;
                }
                else if (lastBlockEntityContext != null && lastBlockEntityContext.getLeft().equals(pos))
                {
                    return lastBlockEntityContext.getRight();
                }
            }

            return null;
        }
        else if (trace.getType() == HitResult.Type.ENTITY)
        {
            Entity entity = ((EntityHitResult) trace).getEntity();

            if (world instanceof ServerWorld)
            {
                entity.saveSelfNbt(nbt);
            }
            else
            {
                Pair<Entity, NbtCompound> pair = EntitiesDataManager.getInstance().requestEntity(entity.getId());

                if (pair != null)
                {
                    nbt = pair.getRight();

                    if (Configs.Generic.ENTITY_DATA_LOAD_NBT.getBooleanValue())
                    {
                        entity = pair.getLeft();
                    }
                }
            }

            //MiniHUD.logger.error("getTarget(): Entity [{}] raw NBT [{}]", entity.getId(), nbt.toString());
            InventoryOverlay.Context ctx = getTargetInventoryFromEntity(world.getEntityById(entity.getId()), nbt);
            //dumpContext(ctx);

            if (lastEntityContext != null && !lastEntityContext.getLeft().equals(entity.getId()))
            {
                lastEntityContext = null;
            }

            if (ctx != null && ctx.inv() != null)
            {
                lastEntityContext = Pair.of(entity.getId(), ctx);
                return ctx;
            }
            // Non-Inventory/Empty Entity
            else if (ctx != null &&
                    (ctx.type() == InventoryOverlay.InventoryRenderType.WOLF ||
                     ctx.type() == InventoryOverlay.InventoryRenderType.VILLAGER ||
                     ctx.type() == InventoryOverlay.InventoryRenderType.HORSE ||
                     ctx.type() == InventoryOverlay.InventoryRenderType.PLAYER ||
                     ctx.type() == InventoryOverlay.InventoryRenderType.ARMOR_STAND ||
                     ctx.type() == InventoryOverlay.InventoryRenderType.LIVING_ENTITY))
            {
                lastEntityContext = Pair.of(entity.getId(), ctx);
                return ctx;
            }
            else if (lastEntityContext != null && lastEntityContext.getLeft().equals(entity.getId()))
            {
                return lastEntityContext.getRight();
            }
        }

        return null;
    }

    private static void dumpContext(InventoryOverlay.Context ctx)
    {
        System.out.print("Context Dump --> ");

        if (ctx == null)
        {
            System.out.print("NULL!\n");
            return;
        }

        System.out.printf("\nTYPE: [%s]\n", ctx.type().name());
        System.out.printf("BE  : [%s]\n", ctx.be() != null ? Registries.BLOCK_ENTITY_TYPE.getId(ctx.be().getType()) : "<NULL>");
        System.out.printf("ENT : [%s]\n", ctx.entity() != null ? Registries.ENTITY_TYPE.getId(ctx.entity().getType()) : "<NULL>");
        System.out.printf("INV : [%s]\n", ctx.inv() != null ? "size: "+ctx.inv().size()+"/ empty: "+ctx.inv().isEmpty() : "<NULL>");
        System.out.printf("NBT : [%s]\n", ctx.nbt() != null ? ctx.nbt().toString() : "<NULL>");

        System.out.print("--> EOF\n");
    }

    public static @Nullable InventoryOverlay.Context getTargetInventoryFromBlock(World world, BlockPos pos, @Nullable BlockEntity be, NbtCompound nbt)
    {
        Inventory inv;

        if (be != null)
        {
            if (nbt.isEmpty())
            {
                nbt = be.createNbtWithIdentifyingData(world.getRegistryManager());
            }

            inv = InventoryUtils.getInventory(world, pos);
        }
        else
        {
            if (nbt.isEmpty())
            {
                Pair<BlockEntity, NbtCompound> pair = RenderHandler.getInstance().requestBlockEntityAt(world, pos);

                if (pair != null)
                {
                    nbt = pair.getRight();

                    if (Configs.Generic.ENTITY_DATA_LOAD_NBT.getBooleanValue())
                    {
                        be = pair.getLeft();
                    }
                }
            }

            inv = EntitiesDataManager.getInstance().getBlockInventory(world, pos, false);
        }

        BlockEntityType<?> beType = nbt != null ? NbtBlockUtils.getBlockEntityTypeFromNbt(nbt) : null;

        if ((beType != null && beType.equals(BlockEntityType.ENDER_CHEST)) ||
            be instanceof EnderChestBlockEntity)
        {
            if (MinecraftClient.getInstance().player != null)
            {
                PlayerEntity player = world.getPlayerByUuid(MinecraftClient.getInstance().player.getUuid());

                if (player != null)
                {
                    // Fetch your own EnderItems from Server ...
                    Pair<Entity, NbtCompound> enderPair = EntitiesDataManager.getInstance().requestEntity(player.getId());
                    EnderChestInventory enderItems;

                    if (enderPair != null && enderPair.getRight() != null && enderPair.getRight().contains(NbtKeys.ENDER_ITEMS))
                    {
                        enderItems = InventoryUtils.getPlayerEnderItemsFromNbt(enderPair.getRight(), world.getRegistryManager());
                    }
                    else
                    {
                        enderItems = player.getEnderChestInventory();
                    }

                    if (enderItems != null)
                    {
                        inv = enderItems;
                    }
                }
            }
        }

        if (nbt != null && !nbt.isEmpty())
        {
            //MiniHUD.logger.warn("getTargetInventoryFromBlock(): rawNbt: [{}]", nbt.toString());

            Inventory inv2 = InventoryUtils.getNbtInventory(nbt, inv != null ? inv.size() : -1, world.getRegistryManager());

            if (inv == null)
            {
                inv = inv2;
            }
        }

        //MiniHUD.logger.warn("getTarget():3: pos [{}], inv [{}], be [{}], nbt [{}]", pos.toShortString(), inv != null, be != null, nbt != null ? nbt.getString("id") : new NbtCompound());

        if (inv == null || nbt == null)
        {
            return null;
        }

        return new InventoryOverlay.Context(InventoryOverlay.getBestInventoryType(inv, nbt), inv, be != null ? be : world.getBlockEntity(pos), null, nbt);
    }

    public static @Nullable InventoryOverlay.Context getTargetInventoryFromEntity(Entity entity, NbtCompound nbt)
    {
        Inventory inv = null;
        LivingEntity entityLivingBase = null;

        if (entity instanceof LivingEntity)
        {
            entityLivingBase = (LivingEntity) entity;
        }

        if (entity instanceof Inventory)
        {
            inv = (Inventory) entity;
        }
        else if (entity instanceof PlayerEntity player)
        {
            inv = new SimpleInventory(player.getInventory().main.toArray(new ItemStack[36]));
        }
        else if (entity instanceof VillagerEntity)
        {
            inv = ((VillagerEntity) entity).getInventory();
        }
        else if (entity instanceof AbstractHorseEntity)
        {
            inv = ((IMixinAbstractHorseEntity) entity).minihud_getHorseInventory();
        }
        else if (entity instanceof PiglinEntity)
        {
            inv = ((IMixinPiglinEntity) entity).minihud_inventory();
        }
        if (!nbt.isEmpty())
        {
            Inventory inv2;

            //MiniHUD.logger.warn("getTargetInventoryFromEntity(): rawNbt: [{}]", nbt.toString());

            // Fix for empty horse inv
            if (inv != null &&
                nbt.contains(NbtKeys.ITEMS) &&
                nbt.getList(NbtKeys.ITEMS, Constants.NBT.TAG_COMPOUND).size() > 1)
            {
                if (entity instanceof AbstractHorseEntity)
                {
                    inv2 = InventoryUtils.getNbtInventoryHorseFix(nbt, -1, entity.getRegistryManager());
                }
                else
                {
                    inv2 = InventoryUtils.getNbtInventory(nbt, -1, entity.getRegistryManager());
                }
                inv = null;
            }
            // Fix for saddled horse, no inv
            else if (inv != null &&
                    inv.size() == 1 &&
                    nbt.contains(NbtKeys.SADDLE))
            {
                inv2 = InventoryUtils.getNbtInventoryHorseFix(nbt, -1, entity.getRegistryManager());
                inv = null;
            }
            // Fix for empty Villager/Piglin inv
            else if (inv != null && inv.size() == 8 &&
                    nbt.contains(NbtKeys.INVENTORY) &&
                    !nbt.getList(NbtKeys.INVENTORY, Constants.NBT.TAG_COMPOUND).isEmpty())
            {
                inv2 = InventoryUtils.getNbtInventory(nbt, 8, entity.getRegistryManager());
                inv = null;
            }
            else
            {
                inv2 = InventoryUtils.getNbtInventory(nbt, inv != null ? inv.size() : -1, entity.getRegistryManager());

                if (inv2 != null)
                {
                    inv = null;
                }
            }

            //MiniHUD.logger.error("getTargetInventoryFromEntity(): inv.size [{}], inv2.size [{}]", inv != null ? inv.size() : "null", inv2 != null ? inv2.size() : "null");

            if (inv2 != null)
            {
                inv = inv2;
            }
        }

        if (inv == null && entityLivingBase == null)
        {
            return null;
        }

        return new InventoryOverlay.Context(inv != null ? InventoryOverlay.getBestInventoryType(inv, nbt) : InventoryOverlay.getInventoryType(nbt),
                                            inv, null, entityLivingBase, nbt);
    }
}
