package com.denizenscript.denizen.objects;

import com.denizenscript.denizen.events.BukkitScriptEvent;
import com.denizenscript.denizen.nms.NMSVersion;
import com.denizenscript.denizen.nms.interfaces.EntityAnimation;
import com.denizenscript.denizen.nms.interfaces.PlayerHelper;
import com.denizenscript.denizen.objects.properties.entity.EntityAge;
import com.denizenscript.denizen.objects.properties.entity.EntityColor;
import com.denizenscript.denizen.objects.properties.entity.EntityTame;
import com.denizenscript.denizen.scripts.commands.player.DisguiseCommand;
import com.denizenscript.denizen.scripts.containers.core.EntityScriptContainer;
import com.denizenscript.denizen.scripts.containers.core.EntityScriptHelper;
import com.denizenscript.denizen.utilities.depends.Depends;
import com.denizenscript.denizen.utilities.entity.*;
import com.denizenscript.denizen.utilities.flags.DataPersistenceFlagTracker;
import com.denizenscript.denizen.utilities.nbt.CustomNBT;
import com.denizenscript.denizencore.DenizenCore;
import com.denizenscript.denizencore.flags.AbstractFlagTracker;
import com.denizenscript.denizencore.flags.FlaggableObject;
import com.denizenscript.denizencore.objects.*;
import com.denizenscript.denizen.nms.NMSHandler;
import com.denizenscript.denizen.nms.abstracts.ProfileEditor;
import com.denizenscript.denizen.nms.interfaces.EntityHelper;
import com.denizenscript.denizen.nms.interfaces.FakePlayer;
import com.denizenscript.denizen.npc.traits.MirrorTrait;
import com.denizenscript.denizencore.objects.core.*;
import com.denizenscript.denizencore.tags.ObjectTagProcessor;
import com.denizenscript.denizencore.tags.TagRunnable;
import com.denizenscript.denizencore.utilities.Deprecations;
import com.denizenscript.denizencore.utilities.debugging.Debug;
import com.denizenscript.denizencore.objects.properties.PropertyParser;
import com.denizenscript.denizencore.scripts.ScriptRegistry;
import com.denizenscript.denizencore.tags.Attribute;
import com.denizenscript.denizencore.tags.TagContext;
import com.denizenscript.denizencore.utilities.CoreUtilities;
import com.denizenscript.denizencore.utilities.text.StringHolder;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.npc.ai.NPCHolder;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.*;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.inventory.*;
import org.bukkit.loot.LootTable;
import org.bukkit.loot.Lootable;
import org.bukkit.potion.*;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.function.Predicate;

public class EntityTag implements ObjectTag, Adjustable, EntityFormObject, FlaggableObject, Cloneable {

    // <--[ObjectType]
    // @name EntityTag
    // @prefix e
    // @base ElementTag
    // @implements FlaggableObject, PropertyHolderObject
    // @format
    // The identity format for entities is a spawned entity's UUID, or an entity type.
    // For example, 'e@abc123' or 'e@zombie'.
    //
    // @description
    // An EntityTag represents a spawned entity, or a generic entity type.
    //
    // Note that players and NPCs are valid EntityTags, but are generally represented by the more specific
    // PlayerTag and NPCTag objects.
    //
    // Note that a spawned entity can be a living entity (a player, NPC, or mob) or a nonliving entity (a painting, item frame, etc).
    //
    // This object type is flaggable.
    // Flags on this object type will be stored in the world chunk files as a part of the entity's NBT.
    //
    // -->

    /////////////////////
    //   STATIC METHODS
    /////////////////

    // List a mechanism here if it can be safely run before spawn.
    public static HashSet<String> earlyValidMechanisms = new HashSet<>(Arrays.asList(
            "max_health", "health_data", "health",
            "visible", "armor_pose", "arms", "base_plate", "is_small", "marker",
            "velocity", "age", "is_using_riptide", "size"
    ));
    // Definitely not valid: "item"

    private static final Map<UUID, Entity> rememberedEntities = new HashMap<>();

    public static void rememberEntity(Entity entity) {
        if (entity == null) {
            return;
        }
        rememberedEntities.put(entity.getUniqueId(), entity);
    }

    public static void forgetEntity(Entity entity) {
        if (entity == null) {
            return;
        }
        rememberedEntities.remove(entity.getUniqueId());
    }

    public static boolean isNPC(Entity entity) {
        return entity != null && entity.hasMetadata("NPC") && entity.getMetadata("NPC").get(0).asBoolean();
    }

    public static boolean isCitizensNPC(Entity entity) {
        if (entity == null) {
            return false;
        }
        if (Depends.citizens == null) {
            return false;
        }
        if (!CitizensAPI.hasImplementation()) {
            return false;
        }
        if (!(entity instanceof NPCHolder)) {
            return false;
        }
        NPC npc = ((NPCHolder) entity).getNPC();
        if (npc == null) {
            return false;
        }
        return true;
    }

    public static NPCTag getNPCFrom(Entity entity) {
        if (isCitizensNPC(entity)) {
            return NPCTag.fromEntity(entity);
        }
        else {
            return null;
        }
    }

    public static boolean isPlayer(Entity entity) {
        return entity instanceof Player && !isNPC(entity);
    }

    public static PlayerTag getPlayerFrom(Entity entity) {
        if (isPlayer(entity)) {
            return PlayerTag.mirrorBukkitPlayer((Player) entity);
        }
        else {
            return null;
        }
    }

    public ItemTag getItemInHand() {
        if (isLivingEntity() && getLivingEntity().getEquipment() != null) {
            ItemStack its = getLivingEntity().getEquipment().getItemInMainHand();
            if (its == null) {
                return null;
            }
            return new ItemTag(its.clone());
        }
        return null;
    }

    //////////////////
    //    OBJECT FETCHER
    ////////////////

    @Deprecated
    public static EntityTag valueOf(String string) {
        return valueOf(string, null);
    }

    public static boolean allowDespawnedNpcs = false;

    @Fetchable("e")
    public static EntityTag valueOf(String string, TagContext context) {
        if (string == null) {
            return null;
        }
        if (ObjectFetcher.isObjectWithProperties(string)) {
            return ObjectFetcher.getObjectFromWithProperties(EntityTag.class, string, context);
        }
        string = CoreUtilities.toLowerCase(string);
        if (string.startsWith("e@")) {
            if (string.startsWith("e@fake:")) {
                try {
                    UUID entityID = UUID.fromString(string.substring("e@fake:".length()));
                    FakeEntity entity = FakeEntity.idsToEntities.get(entityID);
                    if (entity != null) {
                        return entity.entity;
                    }
                    return null;
                }
                catch (Exception ex) {
                    // DO NOTHING
                }
            }
            string = string.substring("e@".length());
        }
        // Choose a random entity type if "random" is used
        if (string.equals("random")) {
            EntityType randomType = null;
            // When selecting a random entity type, ignore invalid or inappropriate ones
            while (randomType == null ||
                    randomType.name().matches("^(COMPLEX_PART|DROPPED_ITEM|ENDER_CRYSTAL" +
                            "|ENDER_DRAGON|FISHING_HOOK|ITEM_FRAME|LEASH_HITCH|LIGHTNING" +
                            "|PAINTING|PLAYER|UNKNOWN|WEATHER|WITHER|WITHER_SKULL)$")) {

                randomType = EntityType.values()[CoreUtilities.getRandom().nextInt(EntityType.values().length)];
            }
            return new EntityTag(randomType, "RANDOM");
        }
        // NPC entity
        if (string.startsWith("n@")) {
            NPCTag npc = NPCTag.valueOf(string, context);
            if (npc != null) {
                if (npc.isSpawned()) {
                    return new EntityTag(npc);
                }
                else {
                    if (!allowDespawnedNpcs && context != null && context.showErrors()) {
                        Debug.echoDebug(context.entry, "NPC '" + string + "' is not spawned, errors may follow!");
                    }
                    return new EntityTag(npc);
                }
            }
            else {
                if (context == null || context.debug) {
                    Debug.echoError("NPC '" + string + "' does not exist!");
                }
            }
        }
        // Player entity
        else if (string.startsWith("p@")) {
            PlayerTag returnable = PlayerTag.valueOf(string, context);
            if (returnable != null && returnable.isOnline()) {
                return new EntityTag(returnable.getPlayerEntity());
            }
            else if (context == null || context.showErrors()) {
                Debug.echoError("Invalid Player! '" + string + "' could not be found. Has the player logged off?");
            }
        }
        UUID id = null;
        int slash = string.indexOf('/');
        if (slash != -1) {
            try {
                id = UUID.fromString(string.substring(0, slash));
                string = string.substring(slash + 1);
                Entity entity = getEntityForID(id);
                if (entity != null) {
                    EntityTag result = new EntityTag(entity);
                    if (string.equalsIgnoreCase(result.getEntityScript())
                        || string.equalsIgnoreCase(result.getBukkitEntityType().name())) {
                        return result;
                    }
                    else if (context == null || context.showErrors()) {
                        Debug.echoError("Invalid EntityTag! ID '" + id + "' is valid, but '" + string + "' does not match its type data.");
                    }
                }
            }
            catch (Exception ex) {
                // DO NOTHING
            }
        }
        if (ScriptRegistry.containsScript(string, EntityScriptContainer.class)) {
            // Construct a new custom unspawned entity from script
            EntityTag entity = ScriptRegistry.getScriptContainerAs(string, EntityScriptContainer.class).getEntityFrom();
            entity.uuid = id;
            return entity;
        }
        List<String> data = CoreUtilities.split(string, ',');
        // Handle custom DenizenEntityTypes
        if (DenizenEntityType.isRegistered(data.get(0))) {
            EntityTag entity = new EntityTag(DenizenEntityType.getByName(data.get(0)), data.size() > 1 ? data.get(1) : null);
            entity.uuid = id;
            return entity;
        }
        try {
            UUID entityID = id != null ? id : UUID.fromString(string);
            Entity entity = getEntityForID(entityID);
            if (entity != null) {
                return new EntityTag(entity);
            }
            return null;
        }
        catch (Exception ex) {
            // DO NOTHING
        }
        if (context == null || context.showErrors()) {
            Debug.log("valueOf EntityTag returning null: " + string);
        }
        return null;
    }

    public static Entity getEntityForID(UUID id) {
        if (rememberedEntities.containsKey(id)) {
            return rememberedEntities.get(id);
        }
        for (World world : Bukkit.getWorlds()) {
            Entity entity = NMSHandler.getEntityHelper().getEntity(world, id);
            if (entity != null) {
                return entity;
            }
        }
        return null;
    }

    public static boolean matches(String arg) {
        // Accept anything that starts with a valid entity object identifier.
        if (arg.startsWith("n@") || arg.startsWith("e@") || arg.startsWith("p@")) {
            return true;
        }
        // No longer picky about e@.. let's remove it from the arg
        arg = arg.replace("e@", "").toUpperCase();
        // Allow 'random'
        if (arg.equals("RANDOM")) {
            return true;
        }
        // Allow any entity script
        if (ScriptRegistry.containsScript(arg, EntityScriptContainer.class)) {
            return true;
        }
        // Check first word with a valid entity_type (other groups are datas used in constructors)
        if (DenizenEntityType.isRegistered(CoreUtilities.split(arg, ',').get(0))) {
            return true;
        }
        // No luck otherwise!
        return false;
    }

    /////////////////////
    //   CONSTRUCTORS
    //////////////////

    public EntityTag(Entity entity) {
        if (entity != null) {
            this.entity = entity;
            entityScript = EntityScriptHelper.getEntityScript(entity);
            this.uuid = entity.getUniqueId();
            this.entity_type = DenizenEntityType.getByEntity(entity);
            if (isCitizensNPC(entity)) {
                this.npc = getNPCFrom(entity);
            }
        }
        else {
            Debug.echoError("Entity referenced is null!");
        }
    }

    public EntityTag(EntityType entityType) {
        if (entityType != null) {
            this.entity = null;
            this.entity_type = DenizenEntityType.getByName(entityType.name());
        }
        else {
            Debug.echoError("Entity_type referenced is null!");
        }
    }

    public EntityTag(EntityType entityType, ArrayList<Mechanism> mechanisms) {
        this(entityType);
        this.mechanisms = mechanisms;
    }

    public EntityTag(EntityType entityType, String data1) {
        if (entityType != null) {
            this.entity = null;
            this.entity_type = DenizenEntityType.getByName(entityType.name());
            this.data1 = data1;
        }
        else {
            Debug.echoError("Entity_type referenced is null!");
        }
    }

    public EntityTag(DenizenEntityType entityType) {
        if (entityType != null) {
            this.entity = null;
            this.entity_type = entityType;
        }
        else {
            Debug.echoError("DenizenEntityType referenced is null!");
        }
    }

    public EntityTag(DenizenEntityType entityType, ArrayList<Mechanism> mechanisms) {
        this(entityType);
        this.mechanisms = mechanisms;
    }

    public EntityTag(DenizenEntityType entityType, String data1) {
        if (entityType != null) {
            this.entity = null;
            this.entity_type = entityType;
            this.data1 = data1;
        }
        else {
            Debug.echoError("DenizenEntityType referenced is null!");
        }
    }

    public EntityTag(NPCTag npc) {
        if (Depends.citizens == null) {
            return;
        }
        if (npc != null) {
            this.npc = npc;

            if (npc.isSpawned()) {
                this.entity = npc.getEntity();
                this.entity_type = DenizenEntityType.getByName(npc.getEntityType().name());
                this.uuid = entity.getUniqueId();
            }
        }
        else {
            Debug.echoError("NPC referenced is null!");
        }

    }

    /////////////////////
    //   INSTANCE FIELDS/METHODS
    /////////////////

    @Override
    public EntityTag duplicate() {
        if (isUnique()) {
            return this;
        }
        try {
            EntityTag copy = (EntityTag) clone();
            if (copy.mechanisms != null) {
                copy.mechanisms = new ArrayList<>(copy.mechanisms);
            }
            return copy;
        }
        catch (CloneNotSupportedException ex) {
            Debug.echoError(ex);
            return null;
        }
    }

    @Override
    public AbstractFlagTracker getFlagTracker() {
        if (isCitizensNPC()) {
            return getDenizenNPC().getFlagTracker();
        }
        else if (isPlayer()) {
            return getDenizenPlayer().getFlagTracker();
        }
        Entity ent = getBukkitEntity();
        if (ent != null) {
            return new DataPersistenceFlagTracker(ent);
        }
        else {
            // TODO: Warning?
            return null;
        }
    }

    @Override
    public String getReasonNotFlaggable() {
        if (!isSpawned() || getBukkitEntity() == null) {
            return "the entity is not spawned";
        }
        return "unknown reason - something went wrong";
    }

    @Override
    public void reapplyTracker(AbstractFlagTracker tracker) {
        if (cleanRateProtect + 60000 > DenizenCore.serverTimeMillis) {
            ((DataPersistenceFlagTracker) tracker).doTotalClean();
            cleanRateProtect = DenizenCore.serverTimeMillis;
        }
    }

    @Override
    public boolean isTruthy() {
        return isSpawnedOrValidForTag();
    }

    public Entity entity = null;
    public long cleanRateProtect = -60000;
    public DenizenEntityType entity_type = null;
    private String data1 = null;
    private DespawnedEntity despawned_entity = null;
    private NPCTag npc = null;
    public UUID uuid = null;
    private String entityScript = null;
    public boolean isFake = false;
    public boolean isFakeValid = false;

    public DenizenEntityType getEntityType() {
        return entity_type;
    }

    public EntityType getBukkitEntityType() {
        return entity_type.getBukkitEntityType();
    }

    public void setEntityScript(String entityScript) {
        this.entityScript = entityScript;
    }

    public String getEntityScript() {
        return entityScript;
    }

    public UUID getUUID() {
        if (uuid == null && entity != null) {
            uuid = entity.getUniqueId();
        }
        return uuid;
    }

    @Override
    public EntityTag getDenizenEntity() {
        return this;
    }

    public EntityFormObject getDenizenObject() {
        if (entity == null && npc == null) {
            return this;
        }
        if (isCitizensNPC()) {
            return getDenizenNPC();
        }
        else if (isPlayer()) {
            return new PlayerTag(getPlayer());
        }
        else {
            return this;
        }
    }

    public Entity getBukkitEntity() {
        if (uuid != null && (entity == null || !entity.isValid())) {
            if (!isFake) {
                Entity backup = Bukkit.getEntity(uuid);
                if (backup != null) {
                    entity = backup;
                }
            }
        }
        return entity;
    }

    public LivingEntity getLivingEntity() {
        if (entity instanceof LivingEntity) {
            return (LivingEntity) entity;
        }
        else {
            return null;
        }
    }

    public boolean isLivingEntity() {
        return (entity instanceof LivingEntity);
    }

    public boolean hasInventory() {
        return getBukkitEntity() instanceof InventoryHolder || isCitizensNPC();
    }

    public NPCTag getDenizenNPC() {
        if (npc != null) {
            return npc;
        }
        else {
            return getNPCFrom(entity);
        }
    }

    public boolean isNPC() {
        return npc != null || isNPC(entity);
    }

    public boolean isCitizensNPC() {
        return npc != null || isCitizensNPC(entity);
    }

    public Player getPlayer() {
        if (isPlayer()) {
            return (Player) entity;
        }
        else {
            return null;
        }
    }

    public PlayerTag getDenizenPlayer() {
        if (isPlayer()) {
            return new PlayerTag(getPlayer());
        }
        else {
            return null;
        }
    }

    public boolean isPlayer() {
        if (entity == null) {
            return entity_type.getBukkitEntityType() == EntityType.PLAYER && npc == null;
        }
        return entity instanceof Player && !isNPC();
    }

    public Projectile getProjectile() {
        return (Projectile) entity;
    }

    public boolean isProjectile() {
        return entity instanceof Projectile;
    }

    public EntityTag getShooter() {
        if (hasShooter()) {
            return new EntityTag((LivingEntity) getProjectile().getShooter());
        }
        else {
            return null;
        }
    }

    public void setShooter(EntityTag shooter) {
        if (isProjectile() && shooter.isLivingEntity()) {
            getProjectile().setShooter(shooter.getLivingEntity());
        }
    }

    public boolean hasShooter() {
        return isProjectile() && getProjectile().getShooter() != null && getProjectile().getShooter() instanceof LivingEntity;
        // TODO: Handle other shooter source thingy types
    }

    public Inventory getBukkitInventory() {
        if (hasInventory()) {
            if (!isCitizensNPC()) {
                return ((InventoryHolder) getBukkitEntity()).getInventory();
            }
        }
        return null;
    }

    public InventoryTag getInventory() {
        return hasInventory() ? isCitizensNPC() ? getDenizenNPC().getDenizenInventory() : InventoryTag.mirrorBukkitInventory(getBukkitInventory()) : null;
    }

    public String getName() {
        if (isCitizensNPC()) {
            return getDenizenNPC().getCitizen().getName();
        }
        if (entity instanceof FakePlayer) {
            return ((FakePlayer) entity).getFullName();
        }
        if (entity instanceof Player) {
            return entity.getName();
        }
        String customName = entity.getCustomName();
        if (customName != null) {
            return customName;
        }
        return entity_type.getName();
    }

    public ListTag getEquipment() {
        ItemStack[] equipment = getLivingEntity().getEquipment().getArmorContents();
        ListTag equipmentList = new ListTag();
        for (ItemStack item : equipment) {
            equipmentList.addObject(new ItemTag(item));
        }
        return equipmentList;
    }

    public boolean isGeneric() {
        return !isUnique();
    }

    @Override
    public LocationTag getLocation() {
        Entity entity = getBukkitEntity();
        if (entity != null) {
            return new LocationTag(entity.getLocation());
        }

        return null;
    }

    public LocationTag getEyeLocation() {
        Entity entity = getBukkitEntity();
        if (entity == null) {
            return null;
        }
        if (isPlayer()) {
            return new LocationTag(getPlayer().getEyeLocation());
        }
        else if (!isGeneric() && isLivingEntity()) {
            return new LocationTag(getLivingEntity().getEyeLocation());
        }
        else if (!isGeneric()) {
            return new LocationTag(entity.getLocation());
        }

        return null;
    }

    public Location getTargetBlockSafe(Set<Material> mats, int range) {
        try {
            NMSHandler.getChunkHelper().changeChunkServerThread(getWorld());
            return getLivingEntity().getTargetBlock(mats, range).getLocation();
        }
        finally {
            NMSHandler.getChunkHelper().restoreServerThread(getWorld());
        }
    }

    public Vector getVelocity() {
        Entity entity = getBukkitEntity();
        if (entity == null) {
            return null;
        }
        return entity.getVelocity();
    }

    public void setVelocity(Vector vector) {
        Entity entity = getBukkitEntity();
        if (entity == null) {
            return;
        }
        entity.setVelocity(vector);
    }

    public World getWorld() {
        Entity entity = getBukkitEntity();
        if (entity == null) {
            return null;
        }
        return entity.getWorld();
    }

    public void spawnAt(Location location) {
        // If the entity is already spawned, teleport it.
        if (isCitizensNPC()) {
            if (getDenizenNPC().getCitizen().isSpawned()) {
                getDenizenNPC().getCitizen().teleport(location, TeleportCause.PLUGIN);
            }
            else {
                getDenizenNPC().getCitizen().spawn(location);
                entity = getDenizenNPC().getCitizen().getEntity();
                uuid = getDenizenNPC().getCitizen().getEntity().getUniqueId();
            }
        }
        else if (isUnique() && entity != null) {
            teleport(location);
        }
        else {
            if (entity_type != null) {
                if (despawned_entity != null) {
                    // If entity had a custom_script, use the script to rebuild the base entity.
                    if (despawned_entity.custom_script != null) {
                        // TODO: Build entity from custom script
                    }
                    // Else, use the entity_type specified/remembered
                    else {
                        entity = entity_type.spawnNewEntity(location, mechanisms, entityScript);
                    }
                    getLivingEntity().teleport(location);
                    getLivingEntity().getEquipment().setArmorContents(despawned_entity.equipment);
                    getLivingEntity().setHealth(despawned_entity.health);

                    despawned_entity = null;
                }
                else {
                    if (entity_type.getBukkitEntityType() == EntityType.PLAYER) {
                        if (Depends.citizens == null) {
                            Debug.echoError("Cannot spawn entity of type PLAYER!");
                            return;
                        }
                        else {
                            NPCTag npc = new NPCTag(net.citizensnpcs.api.CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER, data1));
                            npc.getCitizen().spawn(location);
                            entity = npc.getEntity();
                            uuid = entity.getUniqueId();
                        }
                    }
                    else if (entity_type.getBukkitEntityType() == EntityType.FALLING_BLOCK) {
                        MaterialTag material = null;
                        if (data1 != null && MaterialTag.matches(data1)) {
                            material = MaterialTag.valueOf(data1, CoreUtilities.basicContext);
                            // If we did not get a block with "RANDOM", or we got
                            // air or portals, keep trying
                            while (data1.equalsIgnoreCase("RANDOM") &&
                                    ((!material.getMaterial().isBlock()) ||
                                            material.getMaterial() == Material.AIR ||
                                            material.getMaterial() == Material.NETHER_PORTAL ||
                                            material.getMaterial() == Material.END_PORTAL)) {
                                material = MaterialTag.valueOf(data1, CoreUtilities.basicContext);
                            }
                        }
                        else {
                            for (Mechanism mech : mechanisms) {
                                if (mech.getName().equals("fallingblock_type")) {
                                    material = mech.valueAsType(MaterialTag.class);
                                    mechanisms.remove(mech);
                                    break;
                                }
                            }
                        }
                        // If material is null or not a block, default to SAND
                        if (material == null || !material.getMaterial().isBlock() || !material.hasModernData()) {
                            material = new MaterialTag(Material.SAND);
                        }
                        // This is currently the only way to spawn a falling block
                        entity = location.getWorld().spawnFallingBlock(location, material.getModernData());
                        uuid = entity.getUniqueId();
                    }
                    else if (entity_type.getBukkitEntityType() == EntityType.PAINTING) {
                        entity = entity_type.spawnNewEntity(location, mechanisms, entityScript);
                        location = location.clone();
                        Painting painting = (Painting) entity;
                        Art art = null;
                        BlockFace face = null;
                        try {
                            for (Mechanism mech : mechanisms) {
                                if (mech.getName().equals("painting")) {
                                    art = Art.valueOf(mech.getValue().asString().toUpperCase());
                                }
                                else if (mech.getName().equals("rotation")) {
                                    face = BlockFace.valueOf(mech.getValue().asString().toUpperCase());
                                }
                            }
                        }
                        catch (Exception ex) {
                            // ignore
                        }
                        if (art != null && face != null) { // Paintings are the worst
                            if (art.getBlockHeight() % 2 == 0) {
                                location.subtract(0, 1, 0);
                            }
                            if (art.getBlockWidth() % 2 == 0) {
                                if (face == BlockFace.WEST) {
                                    location.subtract(0, 0, 1);
                                }
                                else if (face == BlockFace.SOUTH) {
                                    location.subtract(1, 0, 0);
                                }
                            }
                            painting.teleport(location);
                            painting.setFacingDirection(face, true);
                            painting.setArt(art, true);
                        }
                    }
                    else {
                        entity = entity_type.spawnNewEntity(location, mechanisms, entityScript);
                        if (entity == null) {
                            if (Debug.verbose) {
                                Debug.echoError("Failed to spawn entity of type " + entity_type.getName());
                            }
                            return;
                        }
                        uuid = entity.getUniqueId();
                        if (entityScript != null) {
                            EntityScriptHelper.setEntityScript(entity, entityScript);
                        }
                    }
                }
            }
            else {
                Debug.echoError("Cannot spawn a null EntityTag!");
            }
            if (!isUnique()) {
                Debug.echoError("Error spawning entity - bad entity type, blocked by another plugin, or tried to spawn in an unloaded chunk?");
                return;
            }
            for (Mechanism mechanism : mechanisms) {
                safeAdjust(new Mechanism(mechanism.getName(), mechanism.value, mechanism.context));
            }
            mechanisms.clear();
        }
    }

    public void despawn() {
        despawned_entity = new DespawnedEntity(this);
        getLivingEntity().remove();
    }

    public void respawn() {
        if (despawned_entity != null) {
            spawnAt(despawned_entity.location);
        }
        else if (entity == null) {
            Debug.echoError("Cannot respawn a null EntityTag!");
        }

    }

    public boolean isSpawnedOrValidForTag() {
        if (isFake) {
            return true;
        }
        if (entity == null) {
            return false;
        }
        NMSHandler.getChunkHelper().changeChunkServerThread(entity.getWorld());
        try {
            return isValid() || rememberedEntities.containsKey(entity.getUniqueId());
        }
        finally {
            NMSHandler.getChunkHelper().restoreServerThread(entity.getWorld());
        }
    }

    public boolean isSpawned() {
        return isValid();
    }

    public boolean isValid() {
        Entity entity = getBukkitEntity();
        return entity != null && (entity.isValid() || (isFake && isFakeValid));
    }

    public void remove() {
        entity.remove();
    }

    public void teleport(Location location) {
        if (isCitizensNPC()) {
            getDenizenNPC().getCitizen().teleport(location, TeleportCause.PLUGIN);
        }
        else if (isFake) {
            NMSHandler.getEntityHelper().snapPositionTo(entity, location.toVector());
            NMSHandler.getEntityHelper().look(entity, location.getYaw(), location.getPitch());
        }
        else {
            getBukkitEntity().teleport(location);
            if (entity.getWorld().equals(location.getWorld())) { // Force the teleport through (for things like mounts)
                NMSHandler.getEntityHelper().teleport(entity, location);
            }
        }
    }

    /**
     * Make this entity target another living entity, attempting both
     * old entity AI and new entity AI targeting methods
     *
     * @param target The LivingEntity target
     */

    public void target(LivingEntity target) {
        if (!isSpawned()) {
            return;
        }
        if (entity instanceof Creature) {
            NMSHandler.getEntityHelper().setTarget((Creature) entity, target);
        }
        else if (entity instanceof ShulkerBullet) {
            ((ShulkerBullet) entity).setTarget(target);
        }
        else {
            Debug.echoError(identify() + " is not an entity type that can hold a target!");
        }
    }

    public void setEntity(Entity entity) {
        this.entity = entity;
    }

    // Used to store some information about a livingEntity while it's despawned
    private class DespawnedEntity {

        Double health = null;
        Location location = null;
        ItemStack[] equipment = null;
        String custom_script = null;

        public DespawnedEntity(EntityTag entity) {
            if (entity != null) {
                // Save some important info to rebuild the entity
                health = entity.getLivingEntity().getHealth();
                location = entity.getLivingEntity().getLocation();
                equipment = entity.getLivingEntity().getEquipment().getArmorContents();

                if (CustomNBT.hasCustomNBT(entity.getLivingEntity(), "denizen-script-id")) {
                    custom_script = CustomNBT.getCustomNBT(entity.getLivingEntity(), "denizen-script-id");
                }
            }
        }
    }

    public int comparesTo(EntityTag entity) {
        // Never matches a null
        if (entity == null) {
            return 0;
        }

        // If provided is unique, and both are the same unique entity, return 1.
        if (entity.isUnique() && entity.identify().equals(identify())) {
            return 1;
        }

        // If provided isn't unique...
        if (!entity.isUnique()) {
            // Return 1 if this object isn't unique either, but matches
            if (!isUnique() && entity.identify().equals(identify())) {
                return 1;
            }
            // Return 1 if the provided object isn't unique, but whose entity_type
            // matches this object, even if this object is unique.
            if (entity_type == entity.entity_type) {
                return 1;
            }
        }

        return 0;
    }

    public boolean comparedTo(String compare) {
        compare = CoreUtilities.toLowerCase(compare);
        if (compare.equals("entity")) {
            return true;
        }
        else if (compare.equals("player")) {
            return isPlayer();
        }
        else if (compare.equals("npc")) {
            return isCitizensNPC() || isNPC();
        }
        else if (getEntityScript() != null && compare.equals(CoreUtilities.toLowerCase(getEntityScript()))) {
            return true;
        }
        else if (compare.equals(getEntityType().getLowercaseName())) {
            return true;
        }
        return false;
    }

    /////////////////////
    //  ObjectTag Methods
    ///////////////////

    private String prefix = "Entity";

    @Override
    public String getObjectType() {
        return "Entity";
    }

    @Override
    public String getPrefix() {
        return prefix;
    }

    @Override
    public EntityTag setPrefix(String prefix) {
        this.prefix = prefix;
        return this;
    }

    @Override
    public String debuggable() {
        if (npc != null) {
            return npc.debuggable();
        }
        if (entity != null) {
            if (isPlayer()) {
                return getDenizenPlayer().debuggable();
            }
            else if (isFake) {
                return "e@FAKE: " + getUUID() + "<GR>(FAKE-" + entity.getType().name() + "/" + entity.getName() + ")";
            }
            else if (isSpawnedOrValidForTag()) {
                return "e@ " + getUUID() + "<GR>(" + entity.getType().name() + "/" + entity.getName() + ")";
            }
        }
        return identify();
    }

    @Override
    public String savable() {
        if (npc != null) {
            return npc.savable();
        }
        else if (isPlayer()) {
            return getDenizenPlayer().savable();
        }
        else if (isFake) {
            return "e@fake:" + getUUID();
        }
        else {
            return identify();
        }
    }

    @Override
    public String identify() {
        if (npc != null) {
            return npc.identify();
        }
        if (isPlayer()) {
            return getDenizenPlayer().identify();
        }
        if (isFake) {
            return "e@fake:" + getUUID();
        }
        if (getUUID() != null) {
            if (entityScript != null) {
                return "e@" + getUUID() + "/" + entityScript + getWaitingMechanismsString();
            }
            if (entity_type != null) {
                return "e@" + getUUID() + "/" + entity_type.getLowercaseName() + getWaitingMechanismsString();
            }
        }
        if (entityScript != null) {
            return "e@" + entityScript + getWaitingMechanismsString();
        }
        if (entity_type != null) {
            return "e@" + entity_type.getLowercaseName() + getWaitingMechanismsString();
        }
        return "null";
    }

    public String getWaitingMechanismsString() {
        StringBuilder properties = new StringBuilder();
        for (Mechanism mechanism : mechanisms) {
            properties.append(mechanism.getName()).append("=").append(PropertyParser.escapePropertyValue(mechanism.getValue().asString())).append(";");
        }
        if (properties.length() > 0) {
            return "[" + properties.substring(0, properties.length() - 1) + "]";
        }
        return "";
    }

    @Override
    public String identifySimple() {
        if (npc != null && npc.isValid()) {
            return "n@" + npc.getId();
        }
        if (isPlayer()) {
            return "p@" + getPlayer().getName();
        }
        if (entityScript != null) {
            return "e@" + entityScript;
        }
        if (entity_type != null) {
            return "e@" + entity_type.getLowercaseName();
        }
        return "null";
    }

    @Override
    public String toString() {
        return identify();
    }

    @Override
    public boolean isUnique() {
        return entity != null || uuid != null || isFake;
    }

    public static void registerTags() {

        AbstractFlagTracker.registerFlagHandlers(tagProcessor);
        PropertyParser.registerPropertyTagHandlers(tagProcessor);

        /////////////////////
        //   UNSPAWNED ATTRIBUTES
        /////////////////

        // <--[tag]
        // @attribute <EntityTag.entity_type>
        // @returns ElementTag
        // @group data
        // @description
        // Returns the type of the entity.
        // -->
        registerTag("entity_type", (attribute, object) -> {
            return new ElementTag(object.entity_type.getName());
        });

        // <--[tag]
        // @attribute <EntityTag.translated_name>
        // @returns ElementTag
        // @description
        // Returns the localized name of the entity.
        // Note that this is a magic Denizen tool - refer to <@link language Denizen Text Formatting>.
        // -->
        registerTag("translated_name", (attribute, object) -> {
            String key = object.getEntityType().getBukkitEntityType().getKey().getKey();
            return new ElementTag(ChatColor.COLOR_CHAR + "[translate=entity.minecraft." + key + "]");
        });

        // <--[tag]
        // @attribute <EntityTag.is_spawned>
        // @returns ElementTag(Boolean)
        // @group data
        // @description
        // Returns whether the entity is spawned.
        // -->
        registerTag("is_spawned", (attribute, object) -> {
            return new ElementTag(object.isSpawned());
        });

        // <--[tag]
        // @attribute <EntityTag.eid>
        // @returns ElementTag(Number)
        // @group data
        // @description
        // Returns the entity's temporary server entity ID.
        // -->
        registerSpawnedOnlyTag("eid", (attribute, object) -> {
            return new ElementTag(object.getBukkitEntity().getEntityId());
        });

        // <--[tag]
        // @attribute <EntityTag.uuid>
        // @returns ElementTag
        // @group data
        // @description
        // Returns the permanent unique ID of the entity.
        // Works with offline players.
        // -->
        registerTag("uuid", (attribute, object) -> {
            return new ElementTag(object.getUUID().toString());
        });

        // <--[tag]
        // @attribute <EntityTag.script>
        // @returns ScriptTag
        // @group data
        // @description
        // Returns the entity script that spawned this entity, if any.
        // -->
        registerTag("script", (attribute, object) -> {
            if (object.entityScript == null) {
                return null;
            }
            ScriptTag tag = new ScriptTag(object.entityScript);
            if (tag.isValid()) {
                return tag;
            }
            return null;
        });

        registerTag("scriptname", (attribute, object) -> {
            Deprecations.hasScriptTags.warn(attribute.context);
            if (object.entityScript == null) {
                return null;
            }
            return new ElementTag(object.entityScript);
        });

        /////////////////////
        //   IDENTIFICATION ATTRIBUTES
        /////////////////

        registerSpawnedOnlyTag("custom_id", (attribute, object) -> {
            Deprecations.entityCustomIdTag.warn(attribute.context);
            if (CustomNBT.hasCustomNBT(object.getLivingEntity(), "denizen-script-id")) {
                return new ScriptTag(CustomNBT.getCustomNBT(object.getLivingEntity(), "denizen-script-id"));
            }
            else {
                return new ElementTag(object.getBukkitEntity().getType().name());
            }
        });

        // <--[tag]
        // @attribute <EntityTag.name>
        // @returns ElementTag
        // @group data
        // @description
        // Returns the name of the entity.
        // This can be a player name, an NPC name, a custom_name, or the entity type.
        // Works with offline players.
        // -->
        registerSpawnedOnlyTag("name", (attribute, object) -> {
            return new ElementTag(object.getName(), true);
        });

        /////////////////////
        //   INVENTORY ATTRIBUTES
        /////////////////

        // <--[tag]
        // @attribute <EntityTag.saddle>
        // @returns ItemTag
        // @group inventory
        // @description
        // If the entity is a horse or pig, returns the saddle as a ItemTag, or air if none.
        // -->
        registerSpawnedOnlyTag("saddle", (attribute, object) -> {
            if (object.getLivingEntity().getType() == EntityType.HORSE) {
                return new ItemTag(((Horse) object.getLivingEntity()).getInventory().getSaddle());
            }
            else if (object.getLivingEntity().getType() == EntityType.PIG) {
                return new ItemTag(((Pig) object.getLivingEntity()).hasSaddle() ? Material.SADDLE : Material.AIR);
            }
            return null;
        });

        // <--[tag]
        // @attribute <EntityTag.horse_armor>
        // @returns ItemTag
        // @group inventory
        // @description
        // If the entity is a horse, returns the item equipped as the horses armor, or air if none.
        // -->
        registerSpawnedOnlyTag("horse_armor", (attribute, object) -> {
            if (object.getBukkitEntityType() == EntityType.HORSE) {
                return new ItemTag(((Horse) object.getLivingEntity()).getInventory().getArmor());
            }
            return null;
        }, "horse_armour");

        // <--[tag]
        // @attribute <EntityTag.has_saddle>
        // @returns ElementTag(Boolean)
        // @group inventory
        // @description
        // If the entity is a pig or horse, returns whether it has a saddle equipped.
        // -->
        registerSpawnedOnlyTag("has_saddle", (attribute, object) -> {
            if (object.getBukkitEntityType() == EntityType.HORSE) {
                return new ElementTag(((Horse) object.getLivingEntity()).getInventory().getSaddle().getType() == Material.SADDLE);
            }
            else if (object.getBukkitEntityType() == EntityType.PIG) {
                return new ElementTag(((Pig) object.getLivingEntity()).hasSaddle());
            }
            return null;
        });

        // <--[tag]
        // @attribute <EntityTag.is_trading>
        // @returns ElementTag(Boolean)
        // @description
        // Returns whether the villager entity is trading.
        // -->
        registerSpawnedOnlyTag("is_trading", (attribute, object) -> {
            if (object.getBukkitEntity() instanceof Merchant) {
                return new ElementTag(((Merchant) object.getBukkitEntity()).isTrading());
            }
            return null;
        });

        // <--[tag]
        // @attribute <EntityTag.trading_with>
        // @returns PlayerTag
        // @description
        // Returns the player who is trading with the villager entity, or null if it is not trading.
        // -->
        registerSpawnedOnlyTag("trading_with", (attribute, object) -> {
            if (object.getBukkitEntity() instanceof Merchant && ((Merchant) object.getBukkitEntity()).getTrader() != null) {
                return new EntityTag(((Merchant) object.getBukkitEntity()).getTrader()).getDenizenObject();
            }
            return null;
        });

        /////////////////////
        //   LOCATION ATTRIBUTES
        /////////////////

        // <--[tag]
        // @attribute <EntityTag.map_trace>
        // @returns LocationTag
        // @group location
        // @description
        // Returns a 2D location indicating where on the map the entity's looking at.
        // Each coordinate is in the range of 0 to 128.
        // -->
        registerSpawnedOnlyTag("map_trace", (attribute, object) -> {
            EntityHelper.MapTraceResult mtr = NMSHandler.getEntityHelper().mapTrace(object.getLivingEntity(), 200);
            if (mtr != null) {
                double x = 0;
                double y;
                double basex = mtr.hitLocation.getX() - Math.floor(mtr.hitLocation.getX());
                double basey = mtr.hitLocation.getY() - Math.floor(mtr.hitLocation.getY());
                double basez = mtr.hitLocation.getZ() - Math.floor(mtr.hitLocation.getZ());
                if (mtr.angle == BlockFace.NORTH) {
                    x = 128f - (basex * 128f);
                }
                else if (mtr.angle == BlockFace.SOUTH) {
                    x = basex * 128f;
                }
                else if (mtr.angle == BlockFace.WEST) {
                    x = basez * 128f;
                }
                else if (mtr.angle == BlockFace.EAST) {
                    x = 128f - (basez * 128f);
                }
                y = 128f - (basey * 128f);
                return new LocationTag(null, Math.round(x), Math.round(y));
            }
            return null;
        });

        // <--[tag]
        // @attribute <EntityTag.can_see[<entity>]>
        // @returns ElementTag(Boolean)
        // @group location
        // @description
        // Returns whether the entity can see the specified other entity (has an uninterrupted line-of-sight).
        // -->
        registerSpawnedOnlyTag("can_see", (attribute, object) -> {
            if (object.isLivingEntity() && attribute.hasContext(1) && EntityTag.matches(attribute.getContext(1))) {
                EntityTag toEntity = attribute.contextAsType(1, EntityTag.class);
                if (toEntity != null && toEntity.isSpawnedOrValidForTag()) {
                    return new ElementTag(object.getLivingEntity().hasLineOfSight(toEntity.getBukkitEntity()));
                }
            }
            return null;
        });

        // <--[tag]
        // @attribute <EntityTag.eye_location>
        // @returns LocationTag
        // @group location
        // @description
        // Returns the location of the entity's eyes.
        // -->
        registerSpawnedOnlyTag("eye_location", (attribute, object) -> {
            return new LocationTag(object.getEyeLocation());
        });

        // <--[tag]
        // @attribute <EntityTag.eye_height>
        // @returns ElementTag(Number)
        // @group location
        // @description
        // Returns the height of the entity's eyes above its location.
        // -->
        registerSpawnedOnlyTag("eye_height", (attribute, object) -> {
            if (object.isLivingEntity()) {
                return new ElementTag(object.getLivingEntity().getEyeHeight());
            }
            return null;
        });

        // <--[tag]
        // @attribute <EntityTag.cursor_on_solid[(<range>)]>
        // @returns LocationTag
        // @group location
        // @description
        // Returns the location of the solid block the entity is looking at.
        // Optionally, specify a maximum range to find the location from (defaults to 200).
        // This uses logic equivalent to <@link tag LocationTag.precise_cursor_on_block[(range)]>.
        // Note that this will return null if there is no solid block in range.
        // This only uses solid blocks, ie it ignores passable blocks like tall-grass. Use <@link tag EntityTag.cursor_on> to include passable blocks.
        // -->
        registerSpawnedOnlyTag("cursor_on_solid", (attribute, object) -> {
            double range = attribute.getDoubleContext(1);
            if (range <= 0) {
                range = 200;
            }
            RayTraceResult traced = object.getWorld().rayTraceBlocks(object.getEyeLocation(), object.getEyeLocation().getDirection(), range, FluidCollisionMode.NEVER, true);
            if (traced != null && traced.getHitBlock() != null) {
                return new LocationTag(traced.getHitBlock().getLocation());
            }
            return null;
        });

        // <--[tag]
        // @attribute <EntityTag.cursor_on[(<range>)]>
        // @returns LocationTag
        // @group location
        // @description
        // Returns the location of the block the entity is looking at.
        // Optionally, specify a maximum range to find the location from (defaults to 200).
        // This uses logic equivalent to <@link tag LocationTag.precise_cursor_on_block[(range)]>.
        // Note that this will return null if there is no block in range.
        // This uses all blocks, ie it includes passable blocks like tall-grass and water. Use <@link tag EntityTag.cursor_on_solid> to include passable blocks.
        // -->
        registerSpawnedOnlyTag("cursor_on", (attribute, object) -> {
            double range = attribute.getDoubleContext(1);
            if (range <= 0) {
                range = 200;
            }
            RayTraceResult traced = object.getWorld().rayTraceBlocks(object.getEyeLocation(), object.getEyeLocation().getDirection(), range, FluidCollisionMode.ALWAYS, false);
            if (traced != null && traced.getHitBlock() != null) {
                return new LocationTag(traced.getHitBlock().getLocation());
            }
            return null;
        });

        // <--[tag]
        // @attribute <EntityTag.location>
        // @returns LocationTag
        // @group location
        // @description
        // Returns the location of the entity.
        // For living entities, this is at the center of their feet.
        // For eye location, use <@link tag EntityTag.eye_location>
        // Works with offline players.
        // -->
        registerSpawnedOnlyTag("location", (attribute, object) -> {
            if (attribute.startsWith("cursor_on", 2)) {
                Deprecations.entityLocationCursorOnTag.warn(attribute.context);
                int range = attribute.getIntContext(2);
                if (range < 1) {
                    range = 50;
                }
                Set<Material> set = new HashSet<>();
                set.add(Material.AIR);

                if (attribute.startsWith("ignore", 3) && attribute.hasContext(3)) {
                    List<MaterialTag> ignoreList = attribute.contextAsType(3, ListTag.class).filter(MaterialTag.class, attribute.context);
                    for (MaterialTag material : ignoreList) {
                        set.add(material.getMaterial());
                    }
                    attribute.fulfill(1);
                }
                attribute.fulfill(1);
                return new LocationTag(object.getTargetBlockSafe(set, range));
            }

            if (attribute.startsWith("standing_on", 2)) {
                Deprecations.entityStandingOn.warn(attribute.context);
                attribute.fulfill(1);
                return new LocationTag(object.getBukkitEntity().getLocation().clone().add(0, -0.5f, 0));
            }
            return new LocationTag(object.getBukkitEntity().getLocation());
        });

        // <--[tag]
        // @attribute <EntityTag.standing_on>
        // @returns LocationTag
        // @group location
        // @description
        // Returns the location of the block the entity is standing on top of (if on the ground, returns null if in the air).
        // -->
        registerSpawnedOnlyTag("standing_on", (attribute, object) -> {
            if (!object.getBukkitEntity().isOnGround()) {
                return null;
            }
            Location loc = object.getBukkitEntity().getLocation().clone().subtract(0, 0.05f, 0);
            return new LocationTag(loc.getWorld(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        });

        // <--[tag]
        // @attribute <EntityTag.body_yaw>
        // @returns ElementTag(Decimal)
        // @group location
        // @description
        // Returns the entity's body yaw (separate from head yaw).
        // -->
        registerSpawnedOnlyTag("body_yaw", (attribute, object) -> {
            return new ElementTag(NMSHandler.getEntityHelper().getBaseYaw(object.getBukkitEntity()));
        });

        // <--[tag]
        // @attribute <EntityTag.velocity>
        // @returns LocationTag
        // @group location
        // @mechanism EntityTag.velocity
        // @description
        // Returns the movement velocity of the entity.
        // Note: Does not accurately calculate player clientside movement velocity.
        // -->
        registerSpawnedOnlyTag("velocity", (attribute, object) -> {
            return new LocationTag(object.getBukkitEntity().getVelocity().toLocation(object.getBukkitEntity().getWorld()));
        });

        // <--[tag]
        // @attribute <EntityTag.world>
        // @returns WorldTag
        // @group location
        // @description
        // Returns the world the entity is in. Works with offline players.
        // -->
        registerSpawnedOnlyTag("world", (attribute, object) -> {
            return new WorldTag(object.getBukkitEntity().getWorld());
        });

        /////////////////////
        //   STATE ATTRIBUTES
        /////////////////

        // <--[tag]
        // @attribute <EntityTag.can_pickup_items>
        // @returns ElementTag(Boolean)
        // @mechanism EntityTag.can_pickup_items
        // @group attributes
        // @description
        // Returns whether the entity can pick up items.
        // -->
        registerSpawnedOnlyTag("can_pickup_items", (attribute, object) -> {
            if (object.isLivingEntity()) {
                return new ElementTag(object.getLivingEntity().getCanPickupItems());
            }
            return null;
        });

        // <--[tag]
        // @attribute <EntityTag.fallingblock_material>
        // @returns MaterialTag
        // @mechanism EntityTag.fallingblock_type
        // @group attributes
        // @description
        // Returns the material of a fallingblock-type entity.
        // -->
        registerSpawnedOnlyTag("fallingblock_material", (attribute, object) -> {
            if (!(object.getBukkitEntity() instanceof FallingBlock)) {
                return null;
            }
            return new MaterialTag(((FallingBlock) object.getBukkitEntity()).getBlockData());
        });

        // <--[tag]
        // @attribute <EntityTag.fall_distance>
        // @returns ElementTag(Decimal)
        // @mechanism EntityTag.fall_distance
        // @group attributes
        // @description
        // Returns how far the entity has fallen.
        // -->
        registerSpawnedOnlyTag("fall_distance", (attribute, object) -> {
            return new ElementTag(object.getBukkitEntity().getFallDistance());
        });

        // <--[tag]
        // @attribute <EntityTag.fire_time>
        // @returns DurationTag
        // @mechanism EntityTag.fire_time
        // @group attributes
        // @description
        // Returns the duration for which the entity will remain on fire
        // -->
        registerSpawnedOnlyTag("fire_time", (attribute, object) -> {
            return new DurationTag(object.getBukkitEntity().getFireTicks() / 20);
        });

        // <--[tag]
        // @attribute <EntityTag.on_fire>
        // @returns ElementTag(Boolean)
        // @group attributes
        // @description
        // Returns whether the entity is currently ablaze or not.
        // -->
        registerSpawnedOnlyTag("on_fire", (attribute, object) -> {
            return new ElementTag(object.getBukkitEntity().getFireTicks() > 0);
        });

        // <--[tag]
        // @attribute <EntityTag.leash_holder>
        // @returns EntityTag
        // @mechanism EntityTag.leash_holder
        // @group attributes
        // @description
        // Returns the leash holder of entity.
        // -->
        registerSpawnedOnlyTag("leash_holder", (attribute, object) -> {
            if (object.isLivingEntity() && object.getLivingEntity().isLeashed()) {
                return new EntityTag(object.getLivingEntity().getLeashHolder()).getDenizenObject();
            }
            return null;
        }, "get_leash_holder");

        // <--[tag]
        // @attribute <EntityTag.passengers>
        // @returns ListTag(EntityTag)
        // @mechanism EntityTag.passengers
        // @group attributes
        // @description
        // Returns a list of the entity's passengers, if any.
        // -->
        registerSpawnedOnlyTag("passengers", (attribute, object) -> {
            ArrayList<EntityTag> passengers = new ArrayList<>();
            for (Entity ent : object.getBukkitEntity().getPassengers()) {
                passengers.add(new EntityTag(ent));
            }
            return new ListTag(passengers);
        }, "get_passengers");

        // <--[tag]
        // @attribute <EntityTag.passenger>
        // @returns EntityTag
        // @mechanism EntityTag.passenger
        // @group attributes
        // @description
        // Returns the entity's passenger, if any.
        // -->
        registerSpawnedOnlyTag("passenger", (attribute, object) -> {
            if (!object.getBukkitEntity().isEmpty()) {
                return new EntityTag(object.getBukkitEntity().getPassenger()).getDenizenObject();
            }
            return null;
        }, "get_passenger");

        // <--[tag]
        // @attribute <EntityTag.shooter>
        // @returns EntityTag
        // @group attributes
        // @mechanism EntityTag.shooter
        // @synonyms EntityTag.arrow_firer,EntityTag.fishhook_shooter,EntityTag.snowball_thrower
        // @description
        // Returns the projectile's shooter, if any.
        // -->
        registerSpawnedOnlyTag("shooter", (attribute, object) -> {
            EntityTag shooter = object.getShooter();
            if (shooter == null) {
                return null;
            }
            return shooter.getDenizenObject();
        }, "get_shooter");

        // <--[tag]
        // @attribute <EntityTag.left_shoulder>
        // @returns EntityTag
        // @mechanism EntityTag.left_shoulder
        // @description
        // Returns the entity on the entity's left shoulder.
        // Only applies to player-typed entities.
        // NOTE: The returned entity will not be spawned within the world,
        // so most operations are invalid unless the entity is first spawned in.
        // -->
        registerSpawnedOnlyTag("left_shoulder", (attribute, object) -> {
            if (!(object.getLivingEntity() instanceof HumanEntity)) {
                return null;
            }
            return new EntityTag(((HumanEntity) object.getLivingEntity()).getShoulderEntityLeft()).getDenizenObject();
        });

        // <--[tag]
        // @attribute <EntityTag.right_shoulder>
        // @returns EntityTag
        // @mechanism EntityTag.right_shoulder
        // @description
        // Returns the entity on the entity's right shoulder.
        // Only applies to player-typed entities.
        // NOTE: The returned entity will not be spawned within the world,
        // so most operations are invalid unless the entity is first spawned in.
        // -->
        registerSpawnedOnlyTag("right_shoulder", (attribute, object) -> {
            if (!(object.getLivingEntity() instanceof HumanEntity)) {
                return null;
            }
            return new EntityTag(((HumanEntity) object.getLivingEntity()).getShoulderEntityRight()).getDenizenObject();
        });

        // <--[tag]
        // @attribute <EntityTag.vehicle>
        // @returns EntityTag
        // @group attributes
        // @description
        // If the entity is in a vehicle, returns the vehicle as a EntityTag.
        // -->
        registerSpawnedOnlyTag("vehicle", (attribute, object) -> {
            if (object.getBukkitEntity().isInsideVehicle()) {
                return new EntityTag(object.getBukkitEntity().getVehicle()).getDenizenObject();
            }
            return null;
        }, "get_vehicle");

        // <--[tag]
        // @attribute <EntityTag.can_breed>
        // @returns ElementTag(Boolean)
        // @mechanism EntityTag.can_breed
        // @group attributes
        // @description
        // Returns whether the animal entity is capable of mating with another of its kind.
        // -->
        registerSpawnedOnlyTag("can_breed", (attribute, object) -> {
            if (!(object.getLivingEntity() instanceof Breedable)) {
                return new ElementTag(false);
            }
            return new ElementTag(((Breedable) object.getLivingEntity()).canBreed());
        });

        // <--[tag]
        // @attribute <EntityTag.breeding>
        // @returns ElementTag(Boolean)
        // @mechanism EntityTag.breed
        // @group attributes
        // @description
        // Returns whether the animal entity is trying to with another of its kind.
        // -->
        registerSpawnedOnlyTag("breeding", (attribute, object) -> {
            if (!(object.getLivingEntity() instanceof Animals)) {
                return null;
            }
            return new ElementTag(((Animals) object.getLivingEntity()).getLoveModeTicks() > 0);
        }, "is_breeding");

        // <--[tag]
        // @attribute <EntityTag.has_passenger>
        // @returns ElementTag(Boolean)
        // @mechanism EntityTag.passenger
        // @group attributes
        // @description
        // Returns whether the entity has a passenger.
        // -->
        registerSpawnedOnlyTag("has_passenger", (attribute, object) -> {
            return new ElementTag(!object.getBukkitEntity().isEmpty());
        });

        // <--[tag]
        // @attribute <EntityTag.is_empty>
        // @returns ElementTag(Boolean)
        // @group attributes
        // @description
        // Returns whether the entity does not have a passenger.
        // -->
        registerSpawnedOnlyTag("is_empty", (attribute, object) -> {
            return new ElementTag(object.getBukkitEntity().isEmpty());
        }, "empty");

        // <--[tag]
        // @attribute <EntityTag.is_inside_vehicle>
        // @returns ElementTag(Boolean)
        // @group attributes
        // @description
        // Returns whether the entity is inside a vehicle.
        // -->
        registerSpawnedOnlyTag("is_inside_vehicle", (attribute, object) -> {
            return new ElementTag(object.getBukkitEntity().isInsideVehicle());
        }, "inside_vehicle");

        // <--[tag]
        // @attribute <EntityTag.is_leashed>
        // @returns ElementTag(Boolean)
        // @group attributes
        // @description
        // Returns whether the entity is leashed.
        // -->
        registerSpawnedOnlyTag("is_leashed", (attribute, object) -> {
            return new ElementTag(object.isLivingEntity() && object.getLivingEntity().isLeashed());
        }, "leashed");

        // <--[tag]
        // @attribute <EntityTag.is_sheared>
        // @returns ElementTag(Boolean)
        // @group attributes
        // @description
        // Returns whether a sheep is sheared.
        // -->
        registerSpawnedOnlyTag("is_sheared", (attribute, object) -> {
            if (!(object.getBukkitEntity() instanceof Sheep)) {
                return null;
            }
            return new ElementTag(((Sheep) object.getBukkitEntity()).isSheared());
        });

        // <--[tag]
        // @attribute <EntityTag.is_on_ground>
        // @returns ElementTag(Boolean)
        // @group attributes
        // @description
        // Returns whether the entity is supported by a block.
        // -->
        registerSpawnedOnlyTag("is_on_ground", (attribute, object) -> {
            return new ElementTag(object.getBukkitEntity().isOnGround());
        }, "on_ground");

        // <--[tag]
        // @attribute <EntityTag.is_persistent>
        // @returns ElementTag(Boolean)
        // @group attributes
        // @description
        // Returns whether the entity will not be removed completely when far away from players.
        // -->
        registerSpawnedOnlyTag("is_persistent", (attribute, object) -> {
            return new ElementTag(object.isLivingEntity() && !object.getLivingEntity().getRemoveWhenFarAway());
        }, "persistent");

        // <--[tag]
        // @attribute <EntityTag.is_collidable>
        // @returns ElementTag(Boolean)
        // @mechanism EntityTag.collidable
        // @group attributes
        // @description
        // Returns whether the entity is collidable.
        // Returns the persistent collidable value for NPCs.
        // -->
        registerSpawnedOnlyTag("is_collidable", (attribute, object) -> {
            if (object.isCitizensNPC()) {
                return new ElementTag(object.getDenizenNPC().getCitizen().data().get(NPC.COLLIDABLE_METADATA, true));
            }
            return new ElementTag(object.getLivingEntity().isCollidable());
        });

        // <--[tag]
        // @attribute <EntityTag.is_sleeping>
        // @returns ElementTag(Boolean)
        // @description
        // Returns whether the player, NPC, or villager is currently sleeping.
        // -->
        registerSpawnedOnlyTag("is_sleeping", (attribute, object) -> {
            if (object.getBukkitEntity() instanceof Player) {
                return new ElementTag(((Player) object.getBukkitEntity()).isSleeping());
            }
            else if (object.getBukkitEntity() instanceof Villager) {
                return new ElementTag(((Villager) object.getBukkitEntity()).isSleeping());
            }
            return null;
        });

        // <--[tag]
        // @attribute <EntityTag.killer>
        // @returns PlayerTag
        // @group attributes
        // @description
        // Returns the player that last killed the entity.
        // -->
        registerSpawnedOnlyTag("killer", (attribute, object) -> {
            return getPlayerFrom(object.getLivingEntity().getKiller());
        });

        registerSpawnedOnlyTag("last_damage", (attribute, object) -> {
            // <--[tag]
            // @attribute <EntityTag.last_damage.amount>
            // @returns ElementTag(Decimal)
            // @group attributes
            // @description
            // Returns the amount of the last damage taken by the entity.
            // -->
            if (attribute.startsWith("amount", 2)) {
                attribute.fulfill(1);
                return new ElementTag(object.getLivingEntity().getLastDamage());
            }
            // <--[tag]
            // @attribute <EntityTag.last_damage.cause>
            // @returns ElementTag
            // @group attributes
            // @description
            // Returns the cause of the last damage taken by the entity.
            // -->
            if (attribute.startsWith("cause", 2)) {
                attribute.fulfill(1);
                if (object.getBukkitEntity().getLastDamageCause() == null) {
                    return null;
                }
                return new ElementTag(object.getBukkitEntity().getLastDamageCause().getCause().name());
            }
            // <--[tag]
            // @attribute <EntityTag.last_damage.duration>
            // @returns DurationTag
            // @mechanism EntityTag.no_damage_duration
            // @group attributes
            // @description
            // Returns the duration of the last damage taken by the entity.
            // -->
            if (attribute.startsWith("duration", 2)) {
                attribute.fulfill(1);
                return new DurationTag((long) object.getLivingEntity().getNoDamageTicks());
            }
            // <--[tag]
            // @attribute <EntityTag.last_damage.max_duration>
            // @returns DurationTag
            // @mechanism EntityTag.max_no_damage_duration
            // @group attributes
            // @description
            // Returns the maximum duration of the last damage taken by the entity.
            // -->
            if (attribute.startsWith("max_duration", 2)) {
                attribute.fulfill(1);
                return new DurationTag((long) object.getLivingEntity().getMaximumNoDamageTicks());
            }
            return null;
        });

        // <--[tag]
        // @attribute <EntityTag.absorption_health>
        // @returns ElementTag(Decimal)
        // @mechanism EntityTag.absorption_health
        // @description
        // Returns the living entity's absorption health.
        // -->
        registerSpawnedOnlyTag("absorption_health", (attribute, object) -> {
            return new ElementTag(NMSHandler.getEntityHelper().getAbsorption(object.getLivingEntity()));
        });

        // <--[tag]
        // @attribute <EntityTag.max_oxygen>
        // @returns DurationTag
        // @group attributes
        // @description
        // Returns the maximum duration of oxygen the entity can have.
        // Works with offline players.
        // -->
        registerSpawnedOnlyTag("max_oxygen", (attribute, object) -> {
            return new DurationTag((long) object.getLivingEntity().getMaximumAir());
        });

        // <--[tag]
        // @attribute <EntityTag.oxygen>
        // @returns DurationTag
        // @mechanism EntityTag.oxygen
        // @group attributes
        // @description
        // Returns the duration of oxygen the entity has left.
        // Works with offline players.
        // -->
        registerSpawnedOnlyTag("oxygen", (attribute, object) -> {
            if (attribute.startsWith("max", 2)) {
                Deprecations.entityMaxOxygenTag.warn(attribute.context);
                attribute.fulfill(1);
                return new DurationTag((long) object.getLivingEntity().getMaximumAir());
            }
            return new DurationTag((long) object.getLivingEntity().getRemainingAir());
        });

        // <--[tag]
        // @attribute <EntityTag.persistent>
        // @returns ElementTag(Boolean)
        // @group attributes
        // @mechanism EntityTag.persistent
        // @description
        // Returns whether the entity should be be saved to file when chunks unload (otherwise, the entity is gone entirely if despawned for any reason).
        // -->
        registerSpawnedOnlyTag("persistent", (attribute, object) -> {
            return new ElementTag(!object.getLivingEntity().getRemoveWhenFarAway());
        });
        registerSpawnedOnlyTag("remove_when_far", (attribute, object) -> {
            Deprecations.entityRemoveWhenFar.warn(attribute.context);
            return new ElementTag(object.getLivingEntity().getRemoveWhenFarAway());
        });

        // <--[tag]
        // @attribute <EntityTag.target>
        // @returns EntityTag
        // @group attributes
        // @description
        // Returns the target entity of the creature or shulker_bullet, if any.
        // This is the entity that a hostile mob is currently trying to attack.
        // -->
        registerSpawnedOnlyTag("target", (attribute, object) -> {
            if (object.getBukkitEntity() instanceof Creature) {
                Entity target = ((Creature) object.getLivingEntity()).getTarget();
                if (target != null) {
                    return new EntityTag(target).getDenizenObject();
                }
            }
            else if (object.getBukkitEntity() instanceof ShulkerBullet) {
                Entity target = ((ShulkerBullet) object.getLivingEntity()).getTarget();
                if (target != null) {
                    return new EntityTag(target).getDenizenObject();
                }
            }
            return null;
        });

        // <--[tag]
        // @attribute <EntityTag.precise_target[(<range>)]>
        // @returns EntityTag
        // @description
        // Returns the entity this entity is looking at, using precise ray trace logic.
        // Optionally, specify a maximum range to find the entity from (defaults to 200).
        // -->
        registerSpawnedOnlyTag("precise_target", (attribute, object) -> {
            int range = attribute.getIntContext(1);
            if (range < 1) {
                range = 200;
            }
            Predicate<Entity> requirement;
            // <--[tag]
            // @attribute <EntityTag.precise_target[(<range>)].type[<matcher>]>
            // @returns EntityTag
            // @description
            // Returns the entity this entity is looking at, using precise ray trace logic.
            // Optionally, specify a maximum range to find the entity from (defaults to 200).
            // Specify an entity type matcher to only count matches as possible ray trace hits (types not listed will be ignored).
            // -->
            if (attribute.startsWith("type", 2) && attribute.hasContext(2)) {
                attribute.fulfill(1);
                String matcher = attribute.getContext(1);
                requirement = (e) -> !e.equals(object.getBukkitEntity()) && BukkitScriptEvent.tryEntity(new EntityTag(e), matcher);
            }
            else {
                requirement = (e) -> !e.equals(object.getBukkitEntity());
            }
            RayTraceResult result = object.getWorld().rayTrace(object.getEyeLocation(), object.getEyeLocation().getDirection(), range, FluidCollisionMode.NEVER, true, 0, requirement);
            if (result != null && result.getHitEntity() != null) {
                return new EntityTag(result.getHitEntity()).getDenizenObject();
            }
            return null;
        });

        // <--[tag]
        // @attribute <EntityTag.precise_target_position[(<range>)]>
        // @returns LocationTag
        // @description
        // Returns the location this entity is looking at, using precise ray trace (against entities) logic.
        // Optionally, specify a maximum range to find the target from (defaults to 200).
        // -->
        registerSpawnedOnlyTag("precise_target_position", (attribute, object) -> {
            int range = attribute.getIntContext(1);
            if (range < 1) {
                range = 200;
            }
            Predicate<Entity> requirement;
            // <--[tag]
            // @attribute <EntityTag.precise_target_position[(<range>)].type[<matcher>]>
            // @returns LocationTag
            // @description
            // Returns the location this entity is looking at, using precise ray trace (against entities) logic.
            // Optionally, specify a maximum range to find the target from (defaults to 200).
            // Specify an entity type matcher to only count matches as possible ray trace hits (types not listed will be ignored).
            // -->
            if (attribute.startsWith("type", 2) && attribute.hasContext(2)) {
                attribute.fulfill(1);
                String matcher = attribute.getContext(1);
                requirement = (e) -> !e.equals(object.getBukkitEntity()) && BukkitScriptEvent.tryEntity(new EntityTag(e), matcher);
            }
            else {
                requirement = (e) -> !e.equals(object.getBukkitEntity());
            }
            RayTraceResult result = object.getWorld().rayTrace(object.getEyeLocation(), object.getEyeLocation().getDirection(), range, FluidCollisionMode.NEVER, true, 0, requirement);
            if (result != null) {
                return new LocationTag(object.getWorld(), result.getHitPosition());
            }
            return null;
        });

        // <--[tag]
        // @attribute <EntityTag.time_lived>
        // @returns DurationTag
        // @mechanism EntityTag.time_lived
        // @group attributes
        // @description
        // Returns how long the entity has lived.
        // -->
        registerSpawnedOnlyTag("time_lived", (attribute, object) -> {
            return new DurationTag(object.getBukkitEntity().getTicksLived() / 20);
        });

        // <--[tag]
        // @attribute <EntityTag.pickup_delay>
        // @returns DurationTag
        // @mechanism EntityTag.pickup_delay
        // @group attributes
        // @description
        // Returns how long before the item-type entity can be picked up by a player.
        // -->
        registerSpawnedOnlyTag("pickup_delay", (attribute, object) -> {
            if (!(object.getBukkitEntity() instanceof Item)) {
                return null;
            }
            return new DurationTag(((Item) object.getBukkitEntity()).getPickupDelay() * 20);
        }, "pickupdelay");

        // <--[tag]
        // @attribute <EntityTag.is_in_block>
        // @returns ElementTag(Boolean)
        // @group attributes
        // @description
        // Returns whether or not the arrow/trident entity is in a block.
        // -->
        registerSpawnedOnlyTag("is_in_block", (attribute, object) -> {
            if (object.getBukkitEntity() instanceof Arrow) {
                return new ElementTag(((Arrow) object.getBukkitEntity()).isInBlock());
            }
            return null;
        });

        // <--[tag]
        // @attribute <EntityTag.attached_block>
        // @returns LocationTag
        // @group attributes
        // @description
        // Returns the location of the block that the arrow/trident or hanging entity is attached to.
        // -->
        registerSpawnedOnlyTag("attached_block", (attribute, object) -> {
            if (object.getBukkitEntity() instanceof Arrow) {
                Block attachedBlock = ((Arrow) object.getBukkitEntity()).getAttachedBlock();
                if (attachedBlock != null) {
                    return new LocationTag(attachedBlock.getLocation());
                }
            }
            else if (object.getBukkitEntity() instanceof Hanging) {
                Vector dir = ((Hanging) object.getBukkitEntity()).getAttachedFace().getDirection();
                return new LocationTag(object.getLocation().clone().add(dir.multiply(0.5))).getBlockLocation();
            }
            return null;
        });

        // <--[tag]
        // @attribute <EntityTag.gliding>
        // @returns ElementTag(Boolean)
        // @mechanism EntityTag.gliding
        // @group attributes
        // @description
        // Returns whether this entity is gliding.
        // -->
        registerSpawnedOnlyTag("gliding", (attribute, object) -> {
            return new ElementTag(object.getLivingEntity().isGliding());
        });

        // <--[tag]
        // @attribute <EntityTag.swimming>
        // @returns ElementTag(Boolean)
        // @mechanism EntityTag.swimming
        // @group attributes
        // @description
        // Returns whether this entity is swimming.
        // -->
        registerSpawnedOnlyTag("swimming", (attribute, object) -> {
            return new ElementTag(object.getLivingEntity().isSwimming());

        });

        // <--[tag]
        // @attribute <EntityTag.glowing>
        // @returns ElementTag(Boolean)
        // @mechanism EntityTag.glowing
        // @group attributes
        // @description
        // Returns whether this entity is glowing.
        // -->
        registerSpawnedOnlyTag("glowing", (attribute, object) -> {
            return new ElementTag(object.getBukkitEntity().isGlowing());
        });

        /////////////////////
        //   TYPE ATTRIBUTES
        /////////////////

        // <--[tag]
        // @attribute <EntityTag.is_living>
        // @returns ElementTag(Boolean)
        // @group data
        // @description
        // Returns whether the entity type is a living-type entity (eg a cow or a player or anything else that lives, as specifically opposed to non-living entities like paintings, etc).
        // Not to be confused with the idea of being alive - see <@link tag EntityTag.is_spawned>.
        // -->
        registerTag("is_living", (attribute, object) -> {
            if (object.getBukkitEntity() == null && object.entity_type != null) {
                return new ElementTag(object.entity_type.getBukkitEntityType().isAlive());
            }
            return new ElementTag(object.isLivingEntity());
        });

        // <--[tag]
        // @attribute <EntityTag.is_monster>
        // @returns ElementTag(Boolean)
        // @group data
        // @description
        // Returns whether the entity type is a hostile monster.
        // -->
        registerTag("is_monster", (attribute, object) -> {
            if (object.getBukkitEntity() == null && object.entity_type != null) {
                return new ElementTag(Monster.class.isAssignableFrom(object.entity_type.getBukkitEntityType().getEntityClass()));
            }
            return new ElementTag(object.getBukkitEntity() instanceof Monster);
        });

        // <--[tag]
        // @attribute <EntityTag.is_mob>
        // @returns ElementTag(Boolean)
        // @group data
        // @description
        // Returns whether the entity type is a mob (Not a player or NPC).
        // -->
        registerTag("is_mob", (attribute, object) -> {
            if (object.getBukkitEntity() == null && object.entity_type != null) {
                EntityType type = object.entity_type.getBukkitEntityType();
                return new ElementTag(Mob.class.isAssignableFrom(type.getEntityClass()));
            }
            return new ElementTag(!object.isNPC() && object.getBukkitEntity() instanceof Mob);
        });

        // <--[tag]
        // @attribute <EntityTag.is_npc>
        // @returns ElementTag(Boolean)
        // @group data
        // @description
        // Returns whether the entity is a Citizens NPC.
        // -->
        registerSpawnedOnlyTag("is_npc", (attribute, object) -> {
            return new ElementTag(object.isCitizensNPC());
        });

        // <--[tag]
        // @attribute <EntityTag.is_player>
        // @returns ElementTag(Boolean)
        // @group data
        // @description
        // Returns whether the entity is a player.
        // Works with offline players.
        // -->
        registerTag("is_player", (attribute, object) -> {
            return new ElementTag(object.isPlayer());
        });

        // <--[tag]
        // @attribute <EntityTag.is_projectile>
        // @returns ElementTag(Boolean)
        // @group data
        // @description
        // Returns whether the entity type is a projectile.
        // -->
        registerTag("is_projectile", (attribute, object) -> {
            if (object.getBukkitEntity() == null && object.entity_type != null) {
                return new ElementTag(Projectile.class.isAssignableFrom(object.entity_type.getBukkitEntityType().getEntityClass()));
            }
            return new ElementTag(object.isProjectile());
        });

        /////////////////////
        //   PROPERTY ATTRIBUTES
        /////////////////

        // <--[tag]
        // @attribute <EntityTag.tameable>
        // @returns ElementTag(Boolean)
        // @group properties
        // @description
        // Returns whether the entity is tameable.
        // If this returns true, it will enable access to:
        // <@link mechanism EntityTag.tame>, <@link mechanism EntityTag.owner>,
        // <@link tag EntityTag.is_tamed>, and <@link tag EntityTag.owner>
        // -->
        registerSpawnedOnlyTag("tameable", (attribute, object) -> {
            return new ElementTag(EntityTame.describes(object));
        }, "is_tameable");

        // <--[tag]
        // @attribute <EntityTag.ageable>
        // @returns ElementTag(Boolean)
        // @group properties
        // @description
        // Returns whether the entity is ageable.
        // If this returns true, it will enable access to:
        // <@link mechanism EntityTag.age>, <@link mechanism EntityTag.age_lock>,
        // <@link tag EntityTag.is_baby>, <@link tag EntityTag.age>,
        // and <@link tag EntityTag.is_age_locked>
        // -->
        registerSpawnedOnlyTag("ageable", (attribute, object) -> {
            return new ElementTag(EntityAge.describes(object));
        }, "is_ageable");

        // <--[tag]
        // @attribute <EntityTag.colorable>
        // @returns ElementTag(Boolean)
        // @group properties
        // @description
        // Returns whether the entity can be colored.
        // If this returns true, it will enable access to:
        // <@link mechanism EntityTag.color> and <@link tag EntityTag.color>
        // -->
        registerSpawnedOnlyTag("colorable", (attribute, object) -> {
            return new ElementTag(EntityColor.describes(object));
        }, "is_colorable");

        // <--[tag]
        // @attribute <EntityTag.experience>
        // @returns ElementTag(Number)
        // @mechanism EntityTag.experience
        // @group properties
        // @description
        // Returns the experience value of this experience orb entity.
        // -->
        registerSpawnedOnlyTag("experience", (attribute, object) -> {
            if (!(object.getBukkitEntity() instanceof ExperienceOrb)) {
                return null;
            }
            return new ElementTag(((ExperienceOrb) object.getBukkitEntity()).getExperience());
        });

        // <--[tag]
        // @attribute <EntityTag.fuse_ticks>
        // @returns ElementTag(Number)
        // @mechanism EntityTag.fuse_ticks
        // @group properties
        // @description
        // Returns the number of ticks until the explosion of the primed TNT.
        // -->
        registerSpawnedOnlyTag("fuse_ticks", (attribute, object) -> {
            if (!(object.getBukkitEntity() instanceof TNTPrimed)) {
                return null;
            }
            return new ElementTag(((TNTPrimed) object.getBukkitEntity()).getFuseTicks());
        });

        // <--[tag]
        // @attribute <EntityTag.dragon_phase>
        // @returns ElementTag
        // @mechanism EntityTag.dragon_phase
        // @group properties
        // @description
        // Returns the phase an EnderDragon is currently in.
        // Valid phases: <@link url https://hub.spigotmc.org/javadocs/spigot/org/bukkit/entity/EnderDragon.Phase.html>
        // -->
        registerSpawnedOnlyTag("dragon_phase", (attribute, object) -> {
            if (!(object.getBukkitEntity() instanceof EnderDragon)) {
                return null;
            }
            return new ElementTag(((EnderDragon) object.getLivingEntity()).getPhase().name());
        });

        // <--[tag]
        // @attribute <EntityTag.weapon_damage[(<entity>)]>
        // @returns ElementTag(Number)
        // @group properties
        // @description
        // Returns the amount of damage the entity will do based on its held item.
        // Optionally, specify a target entity to test how much damage will be done to that specific target
        // (modified based on enchantments and that entity's armor/status/etc).
        // Note that the result will not always be completely exact, as it doesn't take into account some specific factors
        // (eg sweeping vs single-hit, etc).
        // -->
        registerSpawnedOnlyTag("weapon_damage", (attribute, object) -> {
            Entity target = null;
            if (attribute.hasContext(1)) {
                target = attribute.contextAsType(1, EntityTag.class).getBukkitEntity();
            }
            return new ElementTag(NMSHandler.getEntityHelper().getDamageTo(object.getLivingEntity(), target));
        });

        // <--[tag]
        // @attribute <EntityTag.skin_layers>
        // @returns ListTag
        // @mechanism EntityTag.skin_layers
        // @description
        // Returns the skin layers currently visible on a player-type entity.
        // Output is a list of values from the set of:
        // CAPE, HAT, JACKET, LEFT_PANTS, LEFT_SLEEVE, RIGHT_PANTS, or RIGHT_SLEEVE.
        // -->
        registerSpawnedOnlyTag("skin_layers", (attribute, object) -> {
            byte flags = NMSHandler.getPlayerHelper().getSkinLayers((Player) object.getBukkitEntity());
            ListTag result = new ListTag();
            for (PlayerHelper.SkinLayer layer : PlayerHelper.SkinLayer.values()) {
                if ((flags & layer.flag) != 0) {
                    result.add(layer.name());
                }
            }
            return result;
        });

        // <--[tag]
        // @attribute <EntityTag.is_disguised[(<player>)]>
        // @returns ElementTag(Boolean)
        // @group properties
        // @description
        // Returns whether the entity is currently disguised, either globally (if no context input given), or to the specified player.
        // Relates to <@link command disguise>.
        // -->
        registerSpawnedOnlyTag("is_disguised", (attribute, object) -> {
            HashMap<UUID, DisguiseCommand.TrackedDisguise> map = DisguiseCommand.disguises.get(object.getUUID());
            if (map == null) {
                return new ElementTag(false);
            }
            if (attribute.hasContext(1)) {
                PlayerTag player = attribute.contextAsType(1, PlayerTag.class);
                if (player == null) {
                    attribute.echoError("Invalid player for is_disguised tag.");
                    return null;
                }
                return new ElementTag(map.containsKey(player.getUUID()) || map.containsKey(null));
            }
            else {
                return new ElementTag(map.containsKey(null));
            }
        });

        // <--[tag]
        // @attribute <EntityTag.disguised_type[(<player>)]>
        // @returns EntityTag
        // @group properties
        // @description
        // Returns the entity type the entity is disguised as, either globally (if no context input given), or to the specified player.
        // Relates to <@link command disguise>.
        // -->
        registerSpawnedOnlyTag("disguised_type", (attribute, object) -> {
            HashMap<UUID, DisguiseCommand.TrackedDisguise> map = DisguiseCommand.disguises.get(object.getUUID());
            if (map == null) {
                return null;
            }
            DisguiseCommand.TrackedDisguise disguise;
            if (attribute.hasContext(1)) {
                PlayerTag player = attribute.contextAsType(1, PlayerTag.class);
                if (player == null) {
                    attribute.echoError("Invalid player for is_disguised tag.");
                    return null;
                }
                disguise = map.get(player.getUUID());
                if (disguise == null) {
                    disguise = map.get(null);
                }
            }
            else {
                disguise = map.get(null);
            }
            if (disguise == null) {
                return null;
            }
            return disguise.as.duplicate();
        });

        // <--[tag]
        // @attribute <EntityTag.disguise_to_others[(<player>)]>
        // @returns EntityTag
        // @group properties
        // @description
        // Returns the fake entity used to disguise the entity in other's views, either globally (if no context input given), or to the specified player.
        // Relates to <@link command disguise>.
        // -->
        registerSpawnedOnlyTag("disguise_to_others", (attribute, object) -> {
            HashMap<UUID, DisguiseCommand.TrackedDisguise> map = DisguiseCommand.disguises.get(object.getUUID());
            if (map == null) {
                return null;
            }
            DisguiseCommand.TrackedDisguise disguise;
            if (attribute.hasContext(1)) {
                PlayerTag player = attribute.contextAsType(1, PlayerTag.class);
                if (player == null) {
                    attribute.echoError("Invalid player for is_disguised tag.");
                    return null;
                }
                disguise = map.get(player.getUUID());
                if (disguise == null) {
                    disguise = map.get(null);
                }
            }
            else {
                disguise = map.get(null);
            }
            if (disguise == null) {
                return null;
            }
            if (disguise.toOthers == null) {
                return null;
            }
            return disguise.toOthers.entity;
        });

        // <--[tag]
        // @attribute <EntityTag.describe>
        // @returns EntityTag
        // @group properties
        // @description
        // Returns the entity's full description, including all properties.
        // -->
        registerTag("describe", (attribute, object) -> {
            return object.describe(attribute.context);
        });

        // <--[tag]
        // @attribute <EntityTag.advanced_matches[<matcher>]>
        // @returns ElementTag(Boolean)
        // @group element checking
        // @description
        // Returns whether the entity matches some matcher text, using the system behind <@link language Advanced Script Event Matching>.
        // -->
        registerTag("advanced_matches", (attribute, object) -> {
            if (!attribute.hasContext(1)) {
                return null;
            }
            return new ElementTag(BukkitScriptEvent.tryEntity(object, attribute.getContext(1)));
        });

        // <--[tag]
        // @attribute <EntityTag.has_equipped[<item-matcher>]>
        // @returns ElementTag(Boolean)
        // @group element checking
        // @description
        // Returns whether the entity has any armor equipment item that matches the given item matcher, using the system behind <@link language Advanced Script Event Matching>.
        // For example, has_equipped[diamond_*] will return true if the entity is wearing at least one piece of diamond armor.
        // -->
        registerSpawnedOnlyTag("has_equipped", (attribute, object) -> {
            if (!attribute.hasContext(1)) {
                return null;
            }
            if (!object.isLivingEntity()) {
                return null;
            }
            String matcher = attribute.getContext(1);
            for (ItemStack item : object.getLivingEntity().getEquipment().getArmorContents()) {
                if (BukkitScriptEvent.tryItem(new ItemTag(item), matcher)) {
                    return new ElementTag(true);
                }
            }
            return new ElementTag(false);
        });

        // <--[tag]
        // @attribute <EntityTag.loot_table_id>
        // @returns ElementTag
        // @description
        // Returns an element indicating the minecraft key for the loot-table for the entity (if any).
        // -->
        registerSpawnedOnlyTag("loot_table_id", (attribute, object) -> {
            if (object.getBukkitEntity() instanceof Lootable) {
                LootTable table = ((Lootable) object.getBukkitEntity()).getLootTable();
                if (table != null) {
                    return new ElementTag(table.getKey().toString());
                }
            }
            return null;
        });

        // <--[tag]
        // @attribute <EntityTag.fish_hook_state>
        // @returns ElementTag
        // @description
        // Returns the current state of the fish hook, as any of: UNHOOKED, HOOKED_ENTITY, BOBBING (unhooked means the fishing hook is in the air or on ground).
        // -->
        registerSpawnedOnlyTag("fish_hook_state", (attribute, object) -> {
            if (!(object.getBukkitEntity() instanceof FishHook)) {
                attribute.echoError("EntityTag.fish_hook_state is only valid for fish hooks.");
                return null;
            }
            return new ElementTag(((FishHook) object.getBukkitEntity()).getState().name());
        });

        // <--[tag]
        // @attribute <EntityTag.fish_hook_lure_time>
        // @returns DurationTag
        // @mechanism EntityTag.fish_hook_lure_time
        // @description
        // Returns the remaining time before this fish hook will lure a fish.
        // -->
        registerSpawnedOnlyTag("fish_hook_lure_time", (attribute, object) -> {
            if (!(object.getBukkitEntity() instanceof FishHook)) {
                attribute.echoError("EntityTag.fish_hook_lure_time is only valid for fish hooks.");
                return null;
            }
            return new DurationTag((long) NMSHandler.getFishingHelper().getLureTime((FishHook) object.getBukkitEntity()));
        });

        // <--[tag]
        // @attribute <EntityTag.fish_hook_min_lure_time>
        // @returns DurationTag
        // @mechanism EntityTag.fish_hook_min_lure_time
        // @description
        // Returns the minimum possible time before this fish hook can lure a fish.
        // -->
        registerSpawnedOnlyTag("fish_hook_min_lure_time", (attribute, object) -> {
            if (!(object.getBukkitEntity() instanceof FishHook)) {
                attribute.echoError("EntityTag.fish_hook_min_lure_time is only valid for fish hooks.");
                return null;
            }
            return new DurationTag((long) ((FishHook) object.getBukkitEntity()).getMinWaitTime());
        });

        // <--[tag]
        // @attribute <EntityTag.fish_hook_max_lure_time>
        // @returns DurationTag
        // @mechanism EntityTag.fish_hook_max_lure_time
        // @description
        // Returns the maximum possible time before this fish hook will lure a fish.
        // -->
        registerSpawnedOnlyTag("fish_hook_max_lure_time", (attribute, object) -> {
            if (!(object.getBukkitEntity() instanceof FishHook)) {
                attribute.echoError("EntityTag.fish_hook_max_lure_time is only valid for fish hooks.");
                return null;
            }
            return new DurationTag((long) ((FishHook) object.getBukkitEntity()).getMaxWaitTime());
        });

        // <--[tag]
        // @attribute <EntityTag.fish_hook_hooked_entity>
        // @returns EntityTag
        // @mechanism EntityTag.fish_hook_hooked_entity
        // @description
        // Returns the entity this fish hook is attached to.
        // -->
        registerSpawnedOnlyTag("fish_hook_hooked_entity", (attribute, object) -> {
            if (!(object.getBukkitEntity() instanceof FishHook)) {
                attribute.echoError("EntityTag.fish_hook_hooked_entity is only valid for fish hooks.");
                return null;
            }
            Entity entity = ((FishHook) object.getBukkitEntity()).getHookedEntity();
            return entity != null ? new EntityTag(entity) : null;
        });

        // <--[tag]
        // @attribute <EntityTag.fish_hook_apply_lure>
        // @returns ElementTag(Boolean)
        // @mechanism EntityTag.fish_hook_apply_lure
        // @description
        // Returns whether this fish hook should respect the lure enchantment.
        // Every level of lure enchantment reduces lure time by 5 seconds.
        // -->
        registerSpawnedOnlyTag("fish_hook_apply_lure", (attribute, object) -> {
            if (!(object.getBukkitEntity() instanceof FishHook)) {
                attribute.echoError("EntityTag.fish_hook_apply_lure is only valid for fish hooks.");
                return null;
            }
            return new ElementTag(((FishHook) object.getBukkitEntity()).getApplyLure());
        });

        // <--[tag]
        // @attribute <EntityTag.fish_hook_in_open_water>
        // @returns ElementTag(Boolean)
        // @description
        // Returns whether this fish hook is in open water. Fish hooks in open water can catch treasure.
        // See <@link url https://minecraft.fandom.com/wiki/Fishing> for more info.
        // -->
        registerSpawnedOnlyTag("fish_hook_in_open_water", (attribute, object) -> {
            if (!(object.getBukkitEntity() instanceof FishHook)) {
                attribute.echoError("EntityTag.fish_hook_in_open_water is only valid for fish hooks.");
                return null;
            }
            return new ElementTag(((FishHook) object.getBukkitEntity()).isInOpenWater());
        });

        // <--[tag]
        // @attribute <EntityTag.attached_entities[(<player>)]>
        // @returns ListTag(EntityTag)
        // @description
        // Returns the entities attached to this entity by <@link command attach>.
        // Optionally, specify a player. If specified, will return entities attached visible to that player. If not specified, returns entities globally attached.
        // -->
        registerSpawnedOnlyTag("attached_entities", (attribute, object) -> {
            PlayerTag player = attribute.hasContext(1) ? attribute.contextAsType(1, PlayerTag.class) : null;
            EntityAttachmentHelper.EntityAttachedToMap data = EntityAttachmentHelper.toEntityToData.get(object.getUUID());
            ListTag result = new ListTag();
            if (data == null) {
                return result;
            }
            for (EntityAttachmentHelper.PlayerAttachMap map : data.attachedToMap.values()) {
                if (player == null || map.getAttachment(player.getUUID()) != null) {
                    result.addObject(map.attached);
                }
            }
            return result;
        });

        // <--[tag]
        // @attribute <EntityTag.attached_to[(<player>)]>
        // @returns EntityTag
        // @description
        // Returns the entity that this entity was attached to by <@link command attach>.
        // Optionally, specify a player. If specified, will return entity attachment visible to that player. If not specified, returns any entity global attachment.
        // -->
        registerSpawnedOnlyTag("attached_to", (attribute, object) -> {
            PlayerTag player = attribute.hasContext(1) ? attribute.contextAsType(1, PlayerTag.class) : null;
            EntityAttachmentHelper.PlayerAttachMap data = EntityAttachmentHelper.attachedEntityToData.get(object.getUUID());
            if (data == null) {
                return null;
            }
            EntityAttachmentHelper.AttachmentData attached = data.getAttachment(player == null ? null : player.getUUID());
            if (attached == null) {
                return null;
            }
            return attached.to;
        });

        // <--[tag]
        // @attribute <EntityTag.attached_offset[(<player>)]>
        // @returns LocationTag
        // @description
        // Returns the offset of an attachment for this entity to another that was attached by <@link command attach>.
        // Optionally, specify a player. If specified, will return entity attachment visible to that player. If not specified, returns any entity global attachment.
        // -->
        registerSpawnedOnlyTag("attached_offset", (attribute, object) -> {
            PlayerTag player = attribute.hasContext(1) ? attribute.contextAsType(1, PlayerTag.class) : null;
            EntityAttachmentHelper.PlayerAttachMap data = EntityAttachmentHelper.attachedEntityToData.get(object.getUUID());
            if (data == null) {
                return null;
            }
            EntityAttachmentHelper.AttachmentData attached = data.getAttachment(player == null ? null : player.getUUID());
            if (attached == null) {
                return null;
            }
            return attached.positionalOffset == null ? null : new LocationTag(attached.positionalOffset);
        });

        // <--[tag]
        // @attribute <EntityTag.attack_cooldown_duration>
        // @returns DurationTag
        // @mechanism EntityTag.attack_cooldown
        // @description
        // Returns the amount of time that passed since the start of the attack cooldown.
        // -->
        registerSpawnedOnlyTag("attack_cooldown_duration", (attribute, object) -> {
            if (!(object.getBukkitEntity() instanceof Player)) {
                attribute.echoError("Only player-type entities can have attack_cooldowns!");
                return null;
            }
            return new DurationTag((long) NMSHandler.getPlayerHelper().ticksPassedDuringCooldown((Player) object.getLivingEntity()));
        });

        // <--[tag]
        // @attribute <EntityTag.attack_cooldown_max_duration>
        // @returns DurationTag
        // @mechanism EntityTag.attack_cooldown
        // @description
        // Returns the maximum amount of time that can pass before the player's main hand has returned
        // to its original place after the cooldown has ended.
        // NOTE: This is slightly inaccurate and may not necessarily match with the actual attack
        // cooldown progress.
        // -->
        registerSpawnedOnlyTag("attack_cooldown_max_duration", (attribute, object) -> {
            if (!(object.getBukkitEntity() instanceof Player)) {
                attribute.echoError("Only player-type entities can have attack_cooldowns!");
                return null;
            }
            return new DurationTag((long) NMSHandler.getPlayerHelper().getMaxAttackCooldownTicks((Player) object.getLivingEntity()));
        });

        // <--[tag]
        // @attribute <EntityTag.attack_cooldown_percent>
        // @returns ElementTag(Decimal)
        // @mechanism EntityTag.attack_cooldown_percent
        // @description
        // Returns the progress of the attack cooldown. 0 means that the attack cooldown has just
        // started, while 100 means that the attack cooldown has finished.
        // NOTE: This may not match exactly with the clientside attack cooldown indicator.
        // -->
        registerSpawnedOnlyTag("attack_cooldown_percent", (attribute, object) -> {
            if (!(object.getBukkitEntity() instanceof Player)) {
                attribute.echoError("Only player-type entities can have attack_cooldowns!");
                return null;
            }
            return new ElementTag(NMSHandler.getPlayerHelper().getAttackCooldownPercent((Player) object.getLivingEntity()) * 100);
        });

        // <--[tag]
        // @attribute <EntityTag.is_hand_raised>
        // @returns ElementTag(Boolean)
        // @mechanism EntityTag.attack_cooldown_percent
        // @description
        // Returns whether the player's hand is currently raised. Valid for players for player-type NPCs.
        // A player's hand is raised when they are blocking with a shield, aiming a crossbow, looking through a spyglass, etc.
        // -->
        registerSpawnedOnlyTag("is_hand_raised", (attribute, object) -> {
            if (!(object.getBukkitEntity() instanceof HumanEntity)) {
                attribute.echoError("Only player-type entities can have is_hand_raised!");
                return null;
            }
            return new ElementTag(((HumanEntity) object.getLivingEntity()).isHandRaised());
        });
    }

    public EntityTag describe(TagContext context) {
        ArrayList<Mechanism> waitingMechs;
        if (isSpawnedOrValidForTag()) {
            waitingMechs = new ArrayList<>();
            for (Map.Entry<StringHolder, ObjectTag> property : PropertyParser.getPropertiesMap(this).map.entrySet()) {
                waitingMechs.add(new Mechanism(property.getKey().str, property.getValue(), context));
            }
        }
        else {
            waitingMechs = new ArrayList<>(getWaitingMechanisms());
        }
        EntityTag entity = new EntityTag(entity_type, waitingMechs);
        entity.entityScript = entityScript;
        return entity;
    }

    public static ObjectTagProcessor<EntityTag> tagProcessor = new ObjectTagProcessor<>();

    public static void registerSpawnedOnlyTag(String name, TagRunnable.ObjectInterface<EntityTag> runnable, String... variants) {
        TagRunnable.ObjectInterface<EntityTag> newRunnable = (attribute, object) -> {
            if (!object.isSpawnedOrValidForTag()) {
                if (!attribute.hasAlternative()) {
                    com.denizenscript.denizen.utilities.debugging.Debug.echoError("Entity is not spawned, but tag '" + attribute.getAttributeWithoutContext(1) + "' requires the entity be spawned, for entity: " + object.debuggable());
                }
                return null;
            }
            return runnable.run(attribute, object);
        };
        registerTag(name, newRunnable, variants);
    }

    public static void registerTag(String name, TagRunnable.ObjectInterface<EntityTag> runnable, String... variants) {
        tagProcessor.registerTag(name, runnable, variants);
    }

    @Override
    public ObjectTag getObjectAttribute(Attribute attribute) {
        return tagProcessor.getObjectAttribute(this, attribute);
    }

    public ArrayList<Mechanism> mechanisms = new ArrayList<>();

    public ArrayList<Mechanism> getWaitingMechanisms() {
        return mechanisms;
    }

    public void applyProperty(Mechanism mechanism) {
        if (isGeneric()) {
            mechanisms.add(mechanism);
            mechanism.fulfill();
        }
        else {
            Debug.echoError("Cannot apply properties to an already-spawned entity!");
        }
    }

    @Override
    public void adjust(Mechanism mechanism) {

        if (isGeneric()) {
            mechanisms.add(mechanism);
            mechanism.fulfill();
            return;
        }

        if (getBukkitEntity() == null) {
            if (isCitizensNPC()) {
                Debug.echoError("Cannot adjust not-spawned NPC " + getDenizenNPC());
            }
            else {
                Debug.echoError("Cannot adjust entity " + this);
            }
            return;
        }

        if (mechanism.matches("attach_to")) {
            Deprecations.attachToMech.warn(mechanism.context);
            if (mechanism.hasValue()) {
                ListTag list = mechanism.valueAsType(ListTag.class);
                Vector offset = null;
                boolean rotateWith = true;
                if (list.size() > 1) {
                    offset = LocationTag.valueOf(list.get(1), mechanism.context).toVector();
                    if (list.size() > 2) {
                        rotateWith = new ElementTag(list.get(2)).asBoolean();
                    }
                }
                EntityAttachmentHelper.forceAttachMove(this, EntityTag.valueOf(list.get(0), mechanism.context), offset, rotateWith);
            }
            else {
                EntityAttachmentHelper.forceAttachMove(this, null, null, false);
            }
        }

        // <--[mechanism]
        // @object EntityTag
        // @name shooter
        // @input EntityTag
        // @synonyms EntityTag.arrow_firer,EntityTag.fishhook_shooter,EntityTag.snowball_thrower
        // @description
        // Sets the projectile's shooter.
        // @tags
        // <EntityTag.shooter>
        // -->
        if (mechanism.matches("shooter")) {
            setShooter(mechanism.valueAsType(EntityTag.class));
        }

        // <--[mechanism]
        // @object EntityTag
        // @name can_pickup_items
        // @input ElementTag(Boolean)
        // @description
        // Sets whether the entity can pick up items.
        // The entity must be living.
        // @tags
        // <EntityTag.can_pickup_items>
        // -->
        if (mechanism.matches("can_pickup_items") && mechanism.requireBoolean()) {
            getLivingEntity().setCanPickupItems(mechanism.getValue().asBoolean());
        }

        // <--[mechanism]
        // @object EntityTag
        // @name fall_distance
        // @input ElementTag(Decimal)
        // @description
        // Sets the fall distance.
        // @tags
        // <EntityTag.fall_distance>
        // -->
        if (mechanism.matches("fall_distance") && mechanism.requireFloat()) {
            entity.setFallDistance(mechanism.getValue().asFloat());
        }

        // <--[mechanism]
        // @object EntityTag
        // @name fallingblock_drop_item
        // @input ElementTag(Boolean)
        // @description
        // Sets whether the falling block will drop an item if broken.
        // -->
        if (mechanism.matches("fallingblock_drop_item") && mechanism.requireBoolean()
                && entity instanceof FallingBlock) {
            ((FallingBlock) entity).setDropItem(mechanism.getValue().asBoolean());
        }

        // <--[mechanism]
        // @object EntityTag
        // @name fallingblock_hurt_entities
        // @input ElementTag(Boolean)
        // @description
        // Sets whether the falling block will hurt entities when it lands.
        // -->
        if (mechanism.matches("fallingblock_hurt_entities") && mechanism.requireBoolean()
                && entity instanceof FallingBlock) {
            ((FallingBlock) entity).setHurtEntities(mechanism.getValue().asBoolean());
        }

        // <--[mechanism]
        // @object EntityTag
        // @name fire_time
        // @input DurationTag
        // @description
        // Sets the entity's current fire time (time before the entity stops being on fire).
        // @tags
        // <EntityTag.fire_time>
        // -->
        if (mechanism.matches("fire_time") && mechanism.requireObject(DurationTag.class)) {
            entity.setFireTicks(mechanism.valueAsType(DurationTag.class).getTicksAsInt());
        }

        // <--[mechanism]
        // @object EntityTag
        // @name leash_holder
        // @input EntityTag
        // @description
        // Sets the entity holding this entity by leash.
        // The entity must be living.
        // @tags
        // <EntityTag.is_leashed>
        // <EntityTag.leash_holder>
        // -->
        if (mechanism.matches("leash_holder") && mechanism.requireObject(EntityTag.class)) {
            getLivingEntity().setLeashHolder(mechanism.valueAsType(EntityTag.class).getBukkitEntity());
        }

        // <--[mechanism]
        // @object EntityTag
        // @name can_breed
        // @input ElementTag(Boolean)
        // @description
        // Sets whether the entity is capable of mating with another of its kind.
        // The entity must be living and 'ageable'.
        // @tags
        // <EntityTag.can_breed>
        // -->
        if (mechanism.matches("can_breed") && mechanism.requireBoolean()) {
            ((Ageable) getLivingEntity()).setBreed(true);
        }

        // <--[mechanism]
        // @object EntityTag
        // @name breed
        // @input ElementTag(Boolean)
        // @description
        // Sets whether the entity is trying to mate with another of its kind.
        // The entity must be living and an animal.
        // @tags
        // <EntityTag.breeding>
        // -->
        if (mechanism.matches("breed") && mechanism.requireBoolean()) {
            ((Animals) getLivingEntity()).setLoveModeTicks(mechanism.getValue().asBoolean() ? 600 : 0);
        }

        // <--[mechanism]
        // @object EntityTag
        // @name passengers
        // @input ListTag(EntityTag)
        // @description
        // Sets the passengers of this entity.
        // @tags
        // <EntityTag.passengers>
        // <EntityTag.is_empty>
        // -->
        if (mechanism.matches("passengers")) {
            entity.eject();
            for (EntityTag ent : mechanism.valueAsType(ListTag.class).filter(EntityTag.class, mechanism.context)) {
                if (comparesTo(ent) == 1) {
                    continue;
                }
                if (!ent.isSpawned()) {
                    ent.spawnAt(getLocation());
                }
                if (ent.isSpawned()) {
                    entity.addPassenger(ent.getBukkitEntity());
                }
            }
        }

        // <--[mechanism]
        // @object EntityTag
        // @name passenger
        // @input EntityTag
        // @description
        // Sets the passenger of this entity.
        // @tags
        // <EntityTag.passenger>
        // <EntityTag.is_empty>
        // -->
        if (mechanism.matches("passenger") && mechanism.requireObject(EntityTag.class)) {
            EntityTag ent = mechanism.valueAsType(EntityTag.class);
            if (!ent.isSpawned()) {
                ent.spawnAt(getLocation());
            }
            entity.eject();
            if (ent.isSpawned()) {
                entity.addPassenger(ent.getBukkitEntity());
            }
        }

        // <--[mechanism]
        // @object EntityTag
        // @name time_lived
        // @input DurationTag
        // @description
        // Sets the amount of time this entity has lived for.
        // @tags
        // <EntityTag.time_lived>
        // -->
        if (mechanism.matches("time_lived") && mechanism.requireObject(DurationTag.class)) {
            NMSHandler.getEntityHelper().setTicksLived(entity, mechanism.valueAsType(DurationTag.class).getTicksAsInt());
        }

        // <--[mechanism]
        // @object EntityTag
        // @name absorption_health
        // @input ElementTag(Decimal)
        // @description
        // Sets the living entity's absorption health.
        // @tags
        // <EntityTag.absorption_health>
        // -->
        if (mechanism.matches("absorption_health") && mechanism.requireFloat()) {
            NMSHandler.getEntityHelper().setAbsorption(getLivingEntity(), mechanism.getValue().asDouble());
        }

        // <--[mechanism]
        // @object EntityTag
        // @name oxygen
        // @input DurationTag
        // @description
        // Sets how much air the entity has remaining before it drowns.
        // The entity must be living.
        // @tags
        // <EntityTag.oxygen>
        // <EntityTag.max_oxygen>
        // -->
        if (mechanism.matches("oxygen") && mechanism.requireObject(DurationTag.class)) {
            getLivingEntity().setRemainingAir(mechanism.valueAsType(DurationTag.class).getTicksAsInt());
        }

        if (mechanism.matches("remaining_air") && mechanism.requireInteger()) {
            Deprecations.entityRemainingAir.warn(mechanism.context);
            getLivingEntity().setRemainingAir(mechanism.getValue().asInt());
        }

        // <--[mechanism]
        // @object EntityTag
        // @name remove_effects
        // @input None
        // @description
        // Removes all potion effects from the entity.
        // The entity must be living.
        // @tags
        // <EntityTag.has_effect[<effect>]>
        // -->
        if (mechanism.matches("remove_effects")) {
            for (PotionEffect potionEffect : this.getLivingEntity().getActivePotionEffects()) {
                getLivingEntity().removePotionEffect(potionEffect.getType());
            }
        }

        // <--[mechanism]
        // @object EntityTag
        // @name release_left_shoulder
        // @input None
        // @description
        // Releases the player's left shoulder entity.
        // Only applies to player-typed entities.
        // @tags
        // <EntityTag.left_shoulder>
        // -->
        if (mechanism.matches("release_left_shoulder") && getLivingEntity() instanceof HumanEntity) {
            Entity bukkitEnt = ((HumanEntity) getLivingEntity()).getShoulderEntityLeft();
            if (bukkitEnt != null) {
                EntityTag ent = new EntityTag(bukkitEnt);
                String escript = ent.getEntityScript();
                ent = EntityTag.valueOf("e@" + (escript != null && escript.length() > 0 ? escript : ent.getEntityType().getLowercaseName())
                        + PropertyParser.getPropertiesString(ent), mechanism.context);
                ent.spawnAt(getEyeLocation());
                ((HumanEntity) getLivingEntity()).setShoulderEntityLeft(null);
            }
        }

        // <--[mechanism]
        // @object EntityTag
        // @name release_right_shoulder
        // @input None
        // @description
        // Releases the player's right shoulder entity.
        // Only applies to player-typed entities.
        // @tags
        // <EntityTag.right_shoulder>
        // -->
        if (mechanism.matches("release_right_shoulder") && getLivingEntity() instanceof HumanEntity) {
            Entity bukkitEnt = ((HumanEntity) getLivingEntity()).getShoulderEntityRight();
            if (bukkitEnt != null) {
                EntityTag ent = new EntityTag(bukkitEnt);
                String escript = ent.getEntityScript();
                ent = EntityTag.valueOf("e@" + (escript != null && escript.length() > 0 ? escript : ent.getEntityType().getLowercaseName())
                        + PropertyParser.getPropertiesString(ent), mechanism.context);
                ent.spawnAt(getEyeLocation());
                ((HumanEntity) getLivingEntity()).setShoulderEntityRight(null);
            }
        }

        // <--[mechanism]
        // @object EntityTag
        // @name left_shoulder
        // @input EntityTag
        // @description
        // Sets the entity's left shoulder entity.
        // Only applies to player-typed entities.
        // Provide no input to remove the shoulder entity.
        // NOTE: This mechanism will remove the current shoulder entity from the world.
        // Also note the client will currently only render parrot entities.
        // @tags
        // <EntityTag.left_shoulder>
        // -->
        if (mechanism.matches("left_shoulder") && getLivingEntity() instanceof HumanEntity) {
            if (mechanism.hasValue()) {
                if (mechanism.requireObject(EntityTag.class)) {
                    ((HumanEntity) getLivingEntity()).setShoulderEntityLeft(mechanism.valueAsType(EntityTag.class).getBukkitEntity());
                }
            }
            else {
                ((HumanEntity) getLivingEntity()).setShoulderEntityLeft(null);
            }
        }

        // <--[mechanism]
        // @object EntityTag
        // @name right_shoulder
        // @input EntityTag
        // @description
        // Sets the entity's right shoulder entity.
        // Only applies to player-typed entities.
        // Provide no input to remove the shoulder entity.
        // NOTE: This mechanism will remove the current shoulder entity from the world.
        // Also note the client will currently only render parrot entities.
        // @tags
        // <EntityTag.right_shoulder>
        // -->
        if (mechanism.matches("right_shoulder") && getLivingEntity() instanceof HumanEntity) {
            if (mechanism.hasValue()) {
                if (mechanism.requireObject(EntityTag.class)) {
                    ((HumanEntity) getLivingEntity()).setShoulderEntityRight(mechanism.valueAsType(EntityTag.class).getBukkitEntity());
                }
            }
            else {
                ((HumanEntity) getLivingEntity()).setShoulderEntityRight(null);
            }
        }

        // <--[mechanism]
        // @object EntityTag
        // @name persistent
        // @input ElementTag(Boolean)
        // @description
        // Sets whether the entity should be be saved to file when chunks unload (otherwise, the entity is gone entirely if despawned for any reason).
        // The entity must be living.
        // @tags
        // <EntityTag.persistent>
        // -->
        if (mechanism.matches("persistent") && mechanism.requireBoolean()) {
            getLivingEntity().setRemoveWhenFarAway(!mechanism.getValue().asBoolean());
        }
        if (mechanism.matches("remove_when_far_away") && mechanism.requireBoolean()) {
            Deprecations.entityRemoveWhenFar.warn(mechanism.context);
            getLivingEntity().setRemoveWhenFarAway(mechanism.getValue().asBoolean());
        }

        // <--[mechanism]
        // @object EntityTag
        // @name sheared
        // @input ElementTag(Boolean)
        // @description
        // Sets whether the sheep is sheared.
        // @tags
        // <EntityTag.is_sheared>
        // -->
        if (mechanism.matches("sheared") && mechanism.requireBoolean() && getBukkitEntity() instanceof Sheep) {
            ((Sheep) getBukkitEntity()).setSheared(mechanism.getValue().asBoolean());
        }

        // <--[mechanism]
        // @object EntityTag
        // @name collidable
        // @input ElementTag(Boolean)
        // @description
        // Sets whether the entity is collidable.
        // NOTE: To disable collision between two entities, set this mechanism to false on both entities.
        // Sets the persistent collidable value for NPCs.
        // @tags
        // <EntityTag.is_collidable>
        // -->
        if (mechanism.matches("collidable") && mechanism.requireBoolean()) {
            if (isCitizensNPC()) {
                getDenizenNPC().getCitizen().data().setPersistent(NPC.COLLIDABLE_METADATA, mechanism.getValue().asBoolean());
            }
            else {
                getLivingEntity().setCollidable(mechanism.getValue().asBoolean());
            }
        }

        // <--[mechanism]
        // @object EntityTag
        // @name no_damage_duration
        // @input DurationTag
        // @description
        // Sets the duration in which the entity will take no damage.
        // @tags
        // <EntityTag.last_damage.duration>
        // <EntityTag.last_damage.max_duration>
        // -->
        if (mechanism.matches("no_damage_duration") && mechanism.requireObject(DurationTag.class)) {
            getLivingEntity().setNoDamageTicks(mechanism.valueAsType(DurationTag.class).getTicksAsInt());
        }

        // <--[mechanism]
        // @object EntityTag
        // @name max_no_damage_duration
        // @input DurationTag
        // @description
        // Sets the maximum duration in which the entity will take no damage.
        // @tags
        // <EntityTag.last_damage.duration>
        // <EntityTag.last_damage.max_duration>
        // -->
        if (mechanism.matches("max_no_damage_duration") && mechanism.requireObject(DurationTag.class)) {
            getLivingEntity().setMaximumNoDamageTicks(mechanism.valueAsType(DurationTag.class).getTicksAsInt());
        }

        // <--[mechanism]
        // @object EntityTag
        // @name velocity
        // @input LocationTag
        // @description
        // Sets the entity's movement velocity vector.
        // @tags
        // <EntityTag.velocity>
        // -->
        if (mechanism.matches("velocity") && mechanism.requireObject(LocationTag.class)) {
            setVelocity(mechanism.valueAsType(LocationTag.class).toVector());
        }

        // <--[mechanism]
        // @object EntityTag
        // @name move
        // @input LocationTag
        // @description
        // Forces an entity to move in the direction of the velocity vector specified.
        // -->
        if (mechanism.matches("move") && mechanism.requireObject(LocationTag.class)) {
            NMSHandler.getEntityHelper().move(getBukkitEntity(), mechanism.valueAsType(LocationTag.class).toVector());
        }

        // <--[mechanism]
        // @object EntityTag
        // @name interact_with
        // @input LocationTag
        // @description
        // Makes a player-type entity interact with a block.
        // -->
        if (mechanism.matches("interact_with") && mechanism.requireObject(LocationTag.class)) {
            LocationTag interactLocation = mechanism.valueAsType(LocationTag.class);
            NMSHandler.getEntityHelper().forceInteraction(getPlayer(), interactLocation);
        }

        if (mechanism.matches("play_death")) {
            Deprecations.entityPlayDeath.warn(mechanism.context);
            getLivingEntity().playEffect(EntityEffect.DEATH);
        }

        // <--[mechanism]
        // @object EntityTag
        // @name pickup_delay
        // @input DurationTag
        // @description
        // Sets the pickup delay of this Item Entity.
        // @tags
        // <EntityTag.pickup_delay>
        // -->
        if ((mechanism.matches("pickup_delay") || mechanism.matches("pickupdelay")) &&
                getBukkitEntity() instanceof Item && mechanism.requireObject(DurationTag.class)) {
            ((Item) getBukkitEntity()).setPickupDelay(mechanism.valueAsType(DurationTag.class).getTicksAsInt());
        }

        // <--[mechanism]
        // @object EntityTag
        // @name gliding
        // @input ElementTag(Boolean)
        // @description
        // Sets whether this entity is gliding.
        // @tags
        // <EntityTag.gliding>
        // -->
        if (mechanism.matches("gliding") && mechanism.requireBoolean()) {
            getLivingEntity().setGliding(mechanism.getValue().asBoolean());
        }

        // <--[mechanism]
        // @object EntityTag
        // @name glowing
        // @input ElementTag(Boolean)
        // @description
        // Sets whether this entity is glowing.
        // @tags
        // <EntityTag.glowing>
        // -->
        if (mechanism.matches("glowing") && mechanism.requireBoolean()) {
            getBukkitEntity().setGlowing(mechanism.getValue().asBoolean());
            if (Depends.citizens != null && CitizensAPI.getNPCRegistry().isNPC(getLivingEntity())) {
                CitizensAPI.getNPCRegistry().getNPC(getLivingEntity()).data().setPersistent(NPC.GLOWING_METADATA, mechanism.getValue().asBoolean());
            }
        }

        // <--[mechanism]
        // @object EntityTag
        // @name dragon_phase
        // @input ElementTag
        // @description
        // Sets an EnderDragon's combat phase.
        // @tags
        // <EntityTag.dragon_phase>
        // -->
        if (mechanism.matches("dragon_phase")) {
            EnderDragon ed = (EnderDragon) getLivingEntity();
            ed.setPhase(EnderDragon.Phase.valueOf(mechanism.getValue().asString().toUpperCase()));
        }

        // <--[mechanism]
        // @object EntityTag
        // @name experience
        // @input ElementTag(Number)
        // @description
        // Sets the experience value of this experience orb entity.
        // @tags
        // <EntityTag.experience>
        // -->
        if (mechanism.matches("experience") && getBukkitEntity() instanceof ExperienceOrb && mechanism.requireInteger()) {
            ((ExperienceOrb) getBukkitEntity()).setExperience(mechanism.getValue().asInt());
        }

        // <--[mechanism]
        // @object EntityTag
        // @name fuse_ticks
        // @input ElementTag(Number)
        // @description
        // Sets the number of ticks until the TNT blows up after being primed.
        // @tags
        // <EntityTag.fuse_ticks>
        // -->
        if (mechanism.matches("fuse_ticks") && getBukkitEntity() instanceof TNTPrimed && mechanism.requireInteger()) {
            ((TNTPrimed) getBukkitEntity()).setFuseTicks(mechanism.getValue().asInt());
        }

        // <--[mechanism]
        // @object EntityTag
        // @name show_to_players
        // @input None
        // @description
        // Marks the entity as visible to players by default (if it was hidden).
        // See also <@link mechanism EntityTag.hide_from_players>.
        // To show to only one player, see <@link mechanism PlayerTag.show_entity>.
        // Works with offline players.
        // -->
        if (mechanism.matches("show_to_players")) {
            HideEntitiesHelper.unhideEntity(null, getBukkitEntity());
        }

        // <--[mechanism]
        // @object EntityTag
        // @name hide_from_players
        // @input None
        // @description
        // Hides the entity from players by default.
        // See also <@link mechanism EntityTag.show_to_players>.
        // To hide for only one player, see <@link mechanism PlayerTag.hide_entity>.
        // Works with offline players.
        // -->
        if (mechanism.matches("hide_from_players")) {
            HideEntitiesHelper.hideEntity(null, getBukkitEntity());
        }

        // <--[mechanism]
        // @object EntityTag
        // @name skin_layers
        // @input ListTag
        // @description
        // Sets the visible skin layers on a player-type entity (PlayerTag or player-type NPCTag).
        // Input is a list of values from the set of:
        // CAPE, HAT, JACKET, LEFT_PANTS, LEFT_SLEEVE, RIGHT_PANTS, RIGHT_SLEEVE, or "ALL"
        // @tags
        // <EntityTag.skin_layers>
        // -->
        if (mechanism.matches("skin_layers")) {
            int flags = 0;
            for (String str : mechanism.valueAsType(ListTag.class)) {
                String upper = str.toUpperCase();
                if (upper.equals("ALL")) {
                    flags = 0xFF;
                }
                else {
                    PlayerHelper.SkinLayer layer = PlayerHelper.SkinLayer.valueOf(upper);
                    flags |= layer.flag;
                }
            }
            NMSHandler.getPlayerHelper().setSkinLayers((Player) getBukkitEntity(), (byte) flags);
        }

        // <--[mechanism]
        // @object EntityTag
        // @name mirror_player
        // @input ElementTag(Boolean)
        // @description
        // Makes the player-like entity have the same skin as the player looking at it.
        // For NPCs, this will add the Mirror trait.
        // -->
        if (mechanism.matches("mirror_player") && mechanism.requireBoolean()) {
            if (isNPC()) {
                NPC npc = getDenizenNPC().getCitizen();
                if (!npc.hasTrait(MirrorTrait.class)) {
                    npc.addTrait(MirrorTrait.class);
                }
                MirrorTrait mirror = npc.getOrAddTrait(MirrorTrait.class);
                if (mechanism.getValue().asBoolean()) {
                    mirror.enableMirror();
                }
                else {
                    mirror.disableMirror();
                }
            }
            else {
                if (mechanism.getValue().asBoolean()) {
                    ProfileEditor.mirrorUUIDs.add(getUUID());
                }
                else {
                    ProfileEditor.mirrorUUIDs.remove(getUUID());
                }
            }
        }

        // <--[mechanism]
        // @object EntityTag
        // @name swimming
        // @input ElementTag(Boolean)
        // @description
        // Sets whether the entity is swimming.
        // @tags
        // <EntityTag.swimming>
        // -->
        if (mechanism.matches("swimming") && mechanism.requireBoolean()) {
            getLivingEntity().setSwimming(mechanism.getValue().asBoolean());
        }

        // <--[mechanism]
        // @object EntityTag
        // @name detonate
        // @input None
        // @description
        // If the entity is a firework or a creeper, detonates it.
        // -->
        if (mechanism.matches("detonate")) {
            if (getBukkitEntity() instanceof Firework) {
                ((Firework) getBukkitEntity()).detonate();
            }
            else if (getBukkitEntity() instanceof Creeper) {
                ((Creeper) getBukkitEntity()).explode();
            }
            else {
                Debug.echoError("Cannot detonate entity of type '" + getBukkitEntityType().name() + "'.");
            }
        }

        // <--[mechanism]
        // @object EntityTag
        // @name ignite
        // @input None
        // @description
        // If the entity is a creeper, ignites it.
        // -->
        if (mechanism.matches("ignite")) {
            if (getBukkitEntity() instanceof Creeper) {
                ((Creeper) getBukkitEntity()).ignite();
            }
            else {
                Debug.echoError("Cannot ignite entity of type '" + getBukkitEntityType().name() + "'.");
            }
        }

        // <--[mechanism]
        // @object EntityTag
        // @name head_angle
        // @input ElementTag(Decimal)
        // @description
        // Sets the raw head angle of a living entity.
        // This will not rotate the body at all. Most users should prefer <@link command look>.
        // -->
        if (mechanism.matches("head_angle") && mechanism.requireFloat()) {
            NMSHandler.getEntityHelper().setHeadAngle(getBukkitEntity(), mechanism.getValue().asFloat());
        }

        // <--[mechanism]
        // @object EntityTag
        // @name skeleton_arms_raised
        // @input ElementTag(Boolean)
        // @description
        // Sets whether the skeleton entity should raise its arms.
        // -->
        if (mechanism.matches("skeleton_arms_raised") && mechanism.requireBoolean()) {
            EntityAnimation entityAnimation = NMSHandler.getAnimationHelper().getEntityAnimation(mechanism.getValue().asBoolean() ? "SKELETON_START_SWING_ARM" : "SKELETON_STOP_SWING_ARM");
            entityAnimation.play(entity);
        }

        // <--[mechanism]
        // @object EntityTag
        // @name polar_bear_standing
        // @input ElementTag(Boolean)
        // @description
        // Sets whether the polar bear entity should stand up.
        // -->
        if (mechanism.matches("polar_bear_standing") && mechanism.requireBoolean()) {
            EntityAnimation entityAnimation = NMSHandler.getAnimationHelper().getEntityAnimation(mechanism.getValue().asBoolean() ? "POLAR_BEAR_START_STANDING" : "POLAR_BEAR_STOP_STANDING");
            entityAnimation.play(entity);
        }

        // <--[mechanism]
        // @object EntityTag
        // @name ghast_attacking
        // @input ElementTag(Boolean)
        // @description
        // Sets whether the ghast entity should show the attacking face.
        // -->
        if (mechanism.matches("ghast_attacking") && mechanism.requireBoolean()) {
            NMSHandler.getEntityHelper().setGhastAttacking(getBukkitEntity(), mechanism.getValue().asBoolean());
        }

        // <--[mechanism]
        // @object EntityTag
        // @name enderman_angry
        // @input ElementTag(Boolean)
        // @description
        // Sets whether the enderman entity should be screaming angrily.
        // -->
        if (mechanism.matches("enderman_angry") && mechanism.requireBoolean()) {
            NMSHandler.getEntityHelper().setEndermanAngry(getBukkitEntity(), mechanism.getValue().asBoolean());
        }

        // <--[mechanism]
        // @object EntityTag
        // @name melee_attack
        // @input EntityTag
        // @description
        // Causes this hostile-mob entity to immediately melee-attack the specified target entity once.
        // Works for Hostile Mobs, and Players.
        // Does not work with passive mobs, non-living entities, etc.
        // -->
        if (mechanism.matches("melee_attack") && mechanism.requireObject(EntityTag.class)) {
            Entity target = mechanism.valueAsType(EntityTag.class).getBukkitEntity();
            if (getLivingEntity() instanceof Player && NMSHandler.getVersion().isAtLeast(NMSVersion.v1_17)) {
                NMSHandler.getPlayerHelper().doAttack((Player) getLivingEntity(), target);
            }
            else {
                getLivingEntity().attack(target);
            }
        }

        // <--[mechanism]
        // @object EntityTag
        // @name last_hurt_by
        // @input EntityTag
        // @description
        // Tells this mob entity that it was last hurt by the specified entity.
        // Passive mobs will panic and run away when this is set.
        // Angerable mobs will get angry.
        // -->
        if (mechanism.matches("last_hurt_by") && mechanism.requireObject(EntityTag.class)) {
            NMSHandler.getEntityHelper().setLastHurtBy(getLivingEntity(), mechanism.valueAsType(EntityTag.class).getLivingEntity());
        }

        // <--[mechanism]
        // @object EntityTag
        // @name fish_hook_nibble_time
        // @input DurationTag
        // @description
        // Sets the time until this fish hook is next nibbled. If this value is set zero, biting will be processed instead.
        // if this value is set above zero, when it runs out, a nibble (failed bite) will occur.
        // -->
        if (mechanism.matches("fish_hook_nibble_time") && mechanism.requireObject(DurationTag.class)) {
            if (!(getBukkitEntity() instanceof FishHook)) {
                mechanism.echoError("fish_hook_nibble_time is only valid for FishHook entities.");
                return;
            }
            NMSHandler.getFishingHelper().setNibble((FishHook) getBukkitEntity(), mechanism.valueAsType(DurationTag.class).getTicksAsInt());
        }

        // <--[mechanism]
        // @object EntityTag
        // @name fish_hook_bite_time
        // @input DurationTag
        // @description
        // Sets the time until this fish hook is next bit. If this value and also nibble_time are set zero, luring will happen instead.
        // if this value is set above zero, when it runs out, a bite will occur (and a player can reel to catch it, or fail and have nibble set).
        // -->
        if (mechanism.matches("fish_hook_bite_time") && mechanism.requireObject(DurationTag.class)) {
            if (!(getBukkitEntity() instanceof FishHook)) {
                mechanism.echoError("fish_hook_hook_time is only valid for FishHook entities.");
                return;
            }
            NMSHandler.getFishingHelper().setHookTime((FishHook) getBukkitEntity(), mechanism.valueAsType(DurationTag.class).getTicksAsInt());
        }

        // <--[mechanism]
        // @object EntityTag
        // @name fish_hook_lure_time
        // @input DurationTag
        // @description
        // Sets the time until this fish hook is next lured. If this value and also bite_time and nibble_time are set zero, the luring value will be reset to a random amount.
        // if this value is set above zero, when it runs out, particles will spawn and bite_time will be set to a random amount.
        // @tags
        // <EntityTag.fish_hook_lure_time>
        // -->
        if (mechanism.matches("fish_hook_lure_time") && mechanism.requireObject(DurationTag.class)) {
            if (!(getBukkitEntity() instanceof FishHook)) {
                mechanism.echoError("fish_hook_lure_time is only valid for FishHook entities.");
                return;
            }
            NMSHandler.getFishingHelper().setLureTime((FishHook) getBukkitEntity(), mechanism.valueAsType(DurationTag.class).getTicksAsInt());
        }

        // <--[mechanism]
        // @object EntityTag
        // @name fish_hook_pull
        // @input None
        // @description
        // Pulls the entity this fish hook is attached to towards the caster.
        // -->
        if (mechanism.matches("fish_hook_pull")) {
            if (!(getBukkitEntity() instanceof FishHook)) {
                mechanism.echoError("fish_hook_pull is only valid for FishHook entities.");
                return;
            }
            ((FishHook) getBukkitEntity()).pullHookedEntity();
        }

        // <--[mechanism]
        // @object EntityTag
        // @name fish_hook_apply_lure
        // @input ElementTag(Boolean)
        // @description
        // Sets whether this fish hook should respect the lure enchantment.
        // Every level of lure enchantment reduces lure time by 5 seconds.
        // @tags
        // <EntityTag.fish_hook_apply_lure>
        // -->
        if (mechanism.matches("fish_hook_apply_lure") && mechanism.requireBoolean()) {
            if (!(getBukkitEntity() instanceof FishHook)) {
                mechanism.echoError("fish_hook_apply_lure is only valid for FishHook entities.");
                return;
            }
            ((FishHook) getBukkitEntity()).setApplyLure(mechanism.getValue().asBoolean());
        }

        // <--[mechanism]
        // @object EntityTag
        // @name fish_hook_hooked_entity
        // @input EntityTag
        // @description
        // Sets the entity this fish hook is attached to.
        // @tags
        // <EntityTag.fish_hook_hooked_entity>
        // -->
        if (mechanism.matches("fish_hook_hooked_entity") && mechanism.requireObject(EntityTag.class)) {
            if (!(getBukkitEntity() instanceof FishHook)) {
                mechanism.echoError("fish_hook_hooked_entity is only valid for FishHook entities.");
                return;
            }
            ((FishHook) getBukkitEntity()).setHookedEntity(mechanism.valueAsType(EntityTag.class).getBukkitEntity());
        }

        // <--[mechanism]
        // @object EntityTag
        // @name fish_hook_min_lure_time
        // @input DurationTag
        // @description
        // Returns the minimum possible time before this fish hook can lure a fish.
        // @tags
        // <EntityTag.fish_hook_min_lure_time>
        // -->
        if (mechanism.matches("fish_hook_min_lure_time") && mechanism.requireObject(DurationTag.class)) {
            if (!(getBukkitEntity() instanceof FishHook)) {
                mechanism.echoError("fish_hook_min_lure_time is only valid for FishHook entities.");
                return;
            }
            ((FishHook) getBukkitEntity()).setMinWaitTime(mechanism.valueAsType(DurationTag.class).getTicksAsInt());
        }

        // <--[mechanism]
        // @object EntityTag
        // @name fish_hook_max_lure_time
        // @input DurationTag
        // @description
        // Returns the maximum possible time before this fish hook will lure a fish.
        // @tags
        // <EntityTag.fish_hook_max_lure_time>
        // -->
        if (mechanism.matches("fish_hook_max_lure_time") && mechanism.requireObject(DurationTag.class)) {
            if (!(getBukkitEntity() instanceof FishHook)) {
                mechanism.echoError("fish_hook_max_lure_time is only valid for FishHook entities.");
                return;
            }
            ((FishHook) getBukkitEntity()).setMaxWaitTime(mechanism.valueAsType(DurationTag.class).getTicksAsInt());
        }
        // <--[mechanism]
        // @object EntityTag
        // @name redo_attack_cooldown
        // @input None
        // @description
        // Forces the player to wait for the full attack cooldown duration for the item in their hand.
        // NOTE: The clientside attack cooldown indicator will not reflect this change!
        // @tags
        // <EntityTag.attack_cooldown_duration>
        // <EntityTag.attack_cooldown_max_duration>
        // <EntityTag.attack_cooldown_percent>
        // -->
        if (mechanism.matches("redo_attack_cooldown")) {
            if (!(getLivingEntity() instanceof Player)) {
                mechanism.echoError("Only player-type entities can have attack_cooldowns!");
                return;
            }
            NMSHandler.getPlayerHelper().setAttackCooldown((Player) getLivingEntity(), 0);
        }

        // <--[mechanism]
        // @object EntityTag
        // @name reset_attack_cooldown
        // @input None
        // @description
        // Ends the player's attack cooldown.
        // NOTE: This will do nothing if the player's attack speed attribute is set to 0.
        // NOTE: The clientside attack cooldown indicator will not reflect this change!
        // @tags
        // <EntityTag.attack_cooldown_duration>
        // <EntityTag.attack_cooldown_max_duration>
        // <EntityTag.attack_cooldown_percent>
        // -->
        if (mechanism.matches("reset_attack_cooldown")) {
            if (!(getLivingEntity() instanceof Player)) {
                mechanism.echoError("Only player-type entities can have attack_cooldowns!");
                return;
            }
            PlayerHelper playerHelper = NMSHandler.getPlayerHelper();
            playerHelper.setAttackCooldown((Player) getLivingEntity(), Math.round(playerHelper.getMaxAttackCooldownTicks((Player) getLivingEntity())));
        }

        // <--[mechanism]
        // @object EntityTag
        // @name attack_cooldown_percent
        // @input ElementTag(Decimal)
        // @description
        // Sets the progress of the player's attack cooldown. Takes a decimal from 0 to 1.
        // 0 means the cooldown has just begun, while 1 means the cooldown has been completed.
        // NOTE: The clientside attack cooldown indicator will not reflect this change!
        // @tags
        // <EntityTag.attack_cooldown_duration>
        // <EntityTag.attack_cooldown_max_duration>
        // <EntityTag.attack_cooldown_percent>
        // -->
        if (mechanism.matches("attack_cooldown_percent") && mechanism.requireFloat()) {
            if (!(getLivingEntity() instanceof Player)) {
                mechanism.echoError("Only player-type entities can have attack_cooldowns!");
                return;
            }
            float percent = mechanism.getValue().asFloat();
            if (percent >= 0 && percent <= 1) {
                PlayerHelper playerHelper = NMSHandler.getPlayerHelper();
                playerHelper.setAttackCooldown((Player) getLivingEntity(), Math.round(playerHelper.getMaxAttackCooldownTicks((Player) getLivingEntity()) * mechanism.getValue().asFloat()));
            }
            else {
                com.denizenscript.denizen.utilities.debugging.Debug.echoError("Invalid percentage! \"" + percent + "\" is not between 0 and 1!");
            }
        }

        // <--[mechanism]
        // @object EntityTag
        // @name attack_cooldown
        // @input DurationTag
        // @description
        // Sets the player's time since their last attack. If the time is greater than the max duration of their
        // attack cooldown, then the cooldown is considered finished.
        // NOTE: The clientside attack cooldown indicator will not reflect this change!
        // @tags
        // <EntityTag.attack_cooldown_duration>
        // <EntityTag.attack_cooldown_max_duration>
        // <EntityTag.attack_cooldown_percent>
        // -->
        if (mechanism.matches("attack_cooldown") && mechanism.requireObject(DurationTag.class)) {
            if (!(getLivingEntity() instanceof Player)) {
                mechanism.echoError("Only player-type entities can have attack_cooldowns!");
                return;
            }
            NMSHandler.getPlayerHelper().setAttackCooldown((Player) getLivingEntity(), mechanism.getValue().asType(DurationTag.class, mechanism.context).getTicksAsInt());
        }

        // <--[mechanism]
        // @object EntityTag
        // @name fallingblock_type
        // @input MaterialTag
        // @description
        // Sets the block type of a falling_block entity (only valid while spawning).
        // @tags
        // <EntityTag.fallingblock_material>
        // -->
        if (mechanism.matches("fallingblock_type") && mechanism.requireObject(MaterialTag.class)) {
            if (!(getBukkitEntity() instanceof FallingBlock)) {
                mechanism.echoError("'fallingblock_type' is only valid for Falling Block entities.");
                return;
            }
            NMSHandler.getEntityHelper().setFallingBlockType((FallingBlock) getBukkitEntity(), mechanism.valueAsType(MaterialTag.class).getModernData());
        }


        CoreUtilities.autoPropertyMechanism(this, mechanism);
    }
}
