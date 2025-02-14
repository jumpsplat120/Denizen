package com.denizenscript.denizen.objects;

import com.denizenscript.denizen.events.BukkitScriptEvent;
import com.denizenscript.denizen.scripts.containers.core.InventoryScriptContainer;
import com.denizenscript.denizen.scripts.containers.core.InventoryScriptHelper;
import com.denizenscript.denizen.scripts.containers.core.ItemScriptHelper;
import com.denizenscript.denizen.utilities.AdvancedTextImpl;
import com.denizenscript.denizen.utilities.debugging.Debug;
import com.denizenscript.denizen.utilities.depends.Depends;
import com.denizenscript.denizen.utilities.inventory.InventoryTrackerSystem;
import com.denizenscript.denizen.utilities.inventory.RecipeHelper;
import com.denizenscript.denizen.utilities.inventory.SlotHelper;
import com.denizenscript.denizen.utilities.nbt.CustomNBT;
import com.denizenscript.denizencore.flags.AbstractFlagTracker;
import com.denizenscript.denizencore.flags.FlaggableObject;
import com.denizenscript.denizencore.flags.SavableMapFlagTracker;
import com.denizenscript.denizencore.objects.*;
import com.denizenscript.denizen.nms.NMSHandler;
import com.denizenscript.denizen.nms.abstracts.ImprovedOfflinePlayer;
import com.denizenscript.denizen.objects.notable.NotableManager;
import com.denizenscript.denizen.tags.BukkitTagContext;
import com.denizenscript.denizencore.objects.core.ElementTag;
import com.denizenscript.denizencore.objects.core.ListTag;
import com.denizenscript.denizencore.objects.core.MapTag;
import com.denizenscript.denizencore.objects.core.ScriptTag;
import com.denizenscript.denizencore.objects.notable.Notable;
import com.denizenscript.denizencore.objects.notable.Note;
import com.denizenscript.denizencore.objects.properties.PropertyParser;
import com.denizenscript.denizencore.scripts.ScriptRegistry;
import com.denizenscript.denizencore.tags.Attribute;
import com.denizenscript.denizencore.tags.ObjectTagProcessor;
import com.denizenscript.denizencore.tags.TagContext;
import com.denizenscript.denizencore.tags.TagRunnable;
import com.denizenscript.denizencore.utilities.CoreUtilities;
import com.denizenscript.denizencore.utilities.Deprecations;
import net.citizensnpcs.api.CitizensAPI;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.block.DoubleChest;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

public class InventoryTag implements ObjectTag, Notable, Adjustable, FlaggableObject {

    // <--[ObjectType]
    // @name InventoryTag
    // @prefix in
    // @base ElementTag
    // @implements FlaggableObject, PropertyHolderObject
    // @format
    // The identity format for inventories is a the classification type of inventory to use. All other data is specified through properties.
    //
    // @description
    // An InventoryTag represents an inventory, generically or attached to some in-the-world object.
    //
    // Inventories can be generically designed using inventory script containers,
    // and can be modified using the inventory command.
    //
    // Valid inventory type classifications:
    // "npc", "player", "crafting", "enderchest", "workbench", "entity", "location", "generic"
    //
    // This object type can be noted.
    //
    // This object type is flaggable when it is noted.
    // Flags on this object type will be stored in the notables.yml file.
    //
    // -->

    public static void trackTemporaryInventory(InventoryTag tagForm) {
        if (tagForm == null) {
            return;
        }
        InventoryTrackerSystem.trackTemporaryInventory(tagForm.inventory, tagForm);
    }

    public static void setupInventoryTracker() {
        InventoryTrackerSystem.setup();
    }

    public static InventoryTag mirrorBukkitInventory(Inventory inventory) {
        if (inventory == null) {
            return null;
        }
        InventoryTag result = InventoryTrackerSystem.getTagFormFor(inventory);
        if (result != null) {
            return result;
        }
        // Use the map to get noted inventories
        result = InventoryScriptHelper.notedInventories.get(inventory);
        if (result != null) {
            return result;
        }
        // Iterate through offline player inventories
        for (Map.Entry<UUID, PlayerInventory> inv : ImprovedOfflinePlayer.offlineInventories.entrySet()) {
            if (inv.getValue().equals(inventory)) {
                return new InventoryTag(NMSHandler.getPlayerHelper().getOfflineData(inv.getKey()));
            }
        }
        // Iterate through offline player enderchests
        for (Map.Entry<UUID, Inventory> inv : ImprovedOfflinePlayer.offlineEnderChests.entrySet()) {
            if (inv.getValue().equals(inventory)) {
                return new InventoryTag(NMSHandler.getPlayerHelper().getOfflineData(inv.getKey()), true);
            }
        }

        return new InventoryTag(inventory);
    }

    /////////////////////
    //   STATIC FIELDS
    /////////////////

    // The maximum number of slots a Bukkit inventory can have
    public final static int maxSlots = 54;

    // All of the inventory id types we use
    public final static String[] idTypes = {"npc", "player", "crafting", "enderchest", "workbench", "entity", "location", "generic"};

    /////////////////////
    //   NOTABLE METHODS
    /////////////////

    @Override
    public boolean isUnique() {
        return noteName != null;
    }

    public boolean isSaving = false;

    public AbstractFlagTracker flagTracker = null;

    @Note("Inventories")
    public Object getSaveObject() {
        isSaving = true;
        try {
            ConfigurationSection section = new YamlConfiguration();
            section.set("object", "in@" + idType + PropertyParser.getPropertiesString(this));
            section.set("flags", flagTracker.toString());
            return section;
        }
        finally {
            isSaving = false;
        }
    }

    public void setInventory(Inventory inventory) {
        if (isUnique()) {
            InventoryScriptHelper.notedInventories.remove(this.inventory);
            InventoryScriptHelper.notedInventories.put(inventory, this);
        }
        this.inventory = inventory;
    }

    public String noteName;

    public void makeUnique(String id) {
        InventoryTag toNote = new InventoryTag(inventory, idType, idHolder);
        toNote.uniquifier = null;
        String title = AdvancedTextImpl.instance.getTitle(inventory);
        if (title == null || title.startsWith("container.")) {
            title = toNote.inventory.getType().getDefaultTitle();
        }
        ItemStack[] contents = toNote.inventory.getContents();
        if (getInventoryType() == InventoryType.CHEST) {
            toNote.inventory = AdvancedTextImpl.instance.createInventory(null, toNote.inventory.getSize(), title);
        }
        else {
            toNote.inventory = AdvancedTextImpl.instance.createInventory(null, toNote.inventory.getType(), title);
        }
        toNote.inventory.setContents(contents);
        InventoryScriptHelper.notedInventories.put(toNote.inventory, toNote);
        if (!idType.equals("generic") && !idType.equals("script")) {
            toNote.idType = "generic";
            toNote.idHolder = new ElementTag(CoreUtilities.toLowerCase(getInventoryType().name()));
        }
        toNote.flagTracker = new SavableMapFlagTracker();
        NotableManager.saveAs(toNote, id);
        toNote.noteName = id;
    }

    @Override
    public void forget() {
        NotableManager.remove(this);
        InventoryScriptHelper.notedInventories.remove(inventory);
        flagTracker = null;
        noteName = null;
    }

    @Override
    public AbstractFlagTracker getFlagTracker() {
        return flagTracker;
    }

    @Override
    public void reapplyTracker(AbstractFlagTracker tracker) {
        if (noteName != null) {
            this.flagTracker = tracker;
        }
    }

    @Override
    public String getReasonNotFlaggable() {
        if (noteName == null) {
            return "the inventory is not noted - only noted inventories can hold flags";
        }
        return "unknown reason - something went wrong";
    }

    public static InventoryTag valueOf(String string, PlayerTag player, NPCTag npc, boolean silent) {
        return valueOf(string, new BukkitTagContext(player, npc, null, !silent, null));
    }

    public static InventoryTag valueOf(String string, PlayerTag player, NPCTag npc) {
        return valueOf(string, player, npc, false);
    }

    public static InventoryTag internalGetInventoryFor(TagContext context, List<String> properties) {
        String typeName = properties.get(0);
        String holder = null;
        int size = -1;
        for (String property : properties) {
            if (property.startsWith("holder=")) {
                holder = ObjectFetcher.unescapeProperty(property.substring("holder=".length()));
            }
            else if (property.startsWith("script_name=")) {
                holder = ObjectFetcher.unescapeProperty(property.substring("script_name=".length()));
                typeName = "script";
            }
            else if (property.startsWith("uniquifier=")) {
                String idText = property.substring("uniquifier=".length());
                if (!ArgumentHelper.matchesInteger(idText)) {
                    return null;
                }
                long id = Long.parseLong(idText);
                InventoryTag fixedResult = InventoryTrackerSystem.idTrackedInventories.get(id);
                if (fixedResult != null) {
                    trackTemporaryInventory(fixedResult);
                    return fixedResult;
                }
            }
            else if (property.startsWith("size=")) {
                String sizeText = property.substring("size=".length());
                if (!ArgumentHelper.matchesInteger(sizeText)) {
                    return null;
                }
                size = Integer.parseInt(sizeText);
            }
        }
        if (holder != null) {
            switch (typeName) {
                case "player":
                case "enderchest":
                case "workbench":
                case "crafting":
                    PlayerTag player = PlayerTag.valueOf(holder, context);
                    if (player == null) {
                        if (context == null || context.showErrors()) {
                            Debug.echoError("Invalid inventory player '" + holder + "'");
                        }
                        return null;
                    }
                    switch (typeName) {
                        case "player":
                            return player.getInventory();
                        case "enderchest":
                            return player.getEnderChest();
                        case "workbench":
                            return player.getWorkbench();
                        case "crafting":
                            Inventory opened = player.getPlayerEntity().getOpenInventory().getTopInventory();
                            if (opened instanceof CraftingInventory) {
                                return new InventoryTag(opened, player.getPlayerEntity());
                            }
                            else {
                                return player.getInventory();
                            }
                    }
                    break;
                case "npc":
                    NPCTag npc = NPCTag.valueOf(holder, context);
                    if (npc == null) {
                        if (context == null || context.showErrors()) {
                            Debug.echoError("Invalid inventory npc '" + holder + "'");
                        }
                        return null;
                    }
                    return npc.getDenizenInventory();
                case "entity":
                    EntityTag entity = EntityTag.valueOf(holder, context);
                    if (entity == null) {
                        if (context == null || context.showErrors()) {
                            Debug.echoError("Invalid inventory entity '" + holder + "'");
                        }
                        return null;
                    }
                    return entity.getInventory();
                case "location":
                    LocationTag location = LocationTag.valueOf(holder, context);
                    if (location == null) {
                        if (context == null || context.showErrors()) {
                            Debug.echoError("Invalid inventory location '" + holder + "'");
                        }
                        return null;
                    }
                    return location.getInventory();
            }
        }
        InventoryTag result = null;
        if (typeName.equals("generic")) {
            if (holder != null && !new ElementTag(holder).matchesEnum(InventoryType.values())) {
                if (context == null || context.showErrors()) {
                    Debug.echoError("Unknown inventory type '" + holder + "'");
                }
                return null;
            }
            InventoryType type = holder == null ? InventoryType.CHEST : InventoryType.valueOf(holder.toUpperCase());
            if (size == -1 || type != InventoryType.CHEST) {
                result = new InventoryTag(type);
            }
            else {
                result = new InventoryTag(size);
            }
        }
        else if (typeName.equals("script") && holder != null) {
            ScriptTag script = ScriptTag.valueOf(holder, context);
            if (script == null || !(script.getContainer() instanceof InventoryScriptContainer)) {
                if (context == null || context.showErrors()) {
                    Debug.echoError("Unknown inventory script '" + holder + "'");
                }
                return null;
            }
            result = ((InventoryScriptContainer) script.getContainer()).getInventoryFrom(context);
            if (size != -1) {
                result.setSize(size);
            }
        }
        if (result == null && holder != null) {
            ScriptTag script = ScriptTag.valueOf(holder, context);
            if (script != null && (script.getContainer() instanceof InventoryScriptContainer)) {
                result = ((InventoryScriptContainer) script.getContainer()).getInventoryFrom(context);
            }
        }
        if (result == null && new ElementTag(typeName).matchesEnum(InventoryType.values())) {
            InventoryType type = InventoryType.valueOf(typeName.toUpperCase());
            if (size == -1 || type != InventoryType.CHEST) {
                result = new InventoryTag(type);
            }
            else {
                result = new InventoryTag(size);
            }
        }
        if (result == null) {
            if (context == null || context.showErrors()) {
                Debug.echoError("Unknown inventory idType '" + typeName + "'");
            }
            return null;
        }
        internalApplyPropertySet(result, context, properties);
        return result;
    }

    public static void internalApplyPropertySet(InventoryTag result, TagContext context, List<String> properties) {
        for (int i = 1; i < properties.size(); i++) {
            List<String> data = CoreUtilities.split(properties.get(i), '=', 2);
            if (data.size() != 2) {
                if (context == null || context.showErrors()) {
                    Debug.echoError("Invalid property string '" + properties.get(i) + "'!");
                }
                continue;
            }
            String name = CoreUtilities.toLowerCase(data.get(0));
            String description = ObjectFetcher.unescapeProperty(data.get(1));
            ElementTag descriptionElement = new ElementTag(description);
            Mechanism mechanism = new Mechanism(data.get(0), descriptionElement, context);
            if (!name.equals("holder") && !name.equals("uniquifier") && !name.equals("size") && !name.equals("script_name")) {
                result.safeAdjust(mechanism);
            }
        }
    }

    @Fetchable("in")
    public static InventoryTag valueOf(String string, TagContext context) {
        if (string == null) {
            return null;
        }
        if (string.startsWith("in@")) {
            string = string.substring("in@".length());
        }
        List<String> properties = ObjectFetcher.separateProperties(string);
        if (properties != null && properties.size() > 1) {
            InventoryTag result = internalGetInventoryFor(context, properties);
            if (result == null) {
                if (context == null || context.showErrors()) {
                    Debug.echoError("Value of InventoryTag returning null. Invalid InventoryTag-with-properties specified: " + string);
                }
                return null;
            }
            trackTemporaryInventory(result);
            return result;
        }
        Notable noted = NotableManager.getSavedObject(string);
        if (noted instanceof InventoryTag) {
            return (InventoryTag) noted;
        }
        if (ScriptRegistry.containsScript(string, InventoryScriptContainer.class)) {
            return ScriptRegistry.getScriptContainerAs(string, InventoryScriptContainer.class).getInventoryFrom(context);
        }
        if (new ElementTag(string).matchesEnum(InventoryType.values())) {
            InventoryType type = InventoryType.valueOf(string.toUpperCase());
            return new InventoryTag(type);
        }
        if (context == null || context.showErrors()) {
            Debug.echoError("Value of InventoryTag returning null. Invalid InventoryTag specified: " + string);
        }
        return null;
    }

    public static boolean matches(String arg) {
        if (CoreUtilities.toLowerCase(arg).startsWith("in@")) {
            return true;
        }
        String tid = arg;
        if (arg.contains("[")) {
            tid = arg.substring(0, arg.indexOf('['));
        }
        if (new ElementTag(tid).matchesEnum(InventoryType.values())) {
            return true;
        }
        if (ScriptRegistry.containsScript(tid, InventoryScriptContainer.class)) {
            return true;
        }
        if (NotableManager.getSavedObject(tid) instanceof InventoryTag) {
            return true;
        }
        for (String idType : idTypes) {
            if (tid.equalsIgnoreCase(idType)) {
                return true;
            }
        }
        return false;
    }

    public boolean isGeneric() {
        return idType.equals("generic") || idType.equals("script") && !isUnique();
    }

    public static final ElementTag defaultIdHolder = new ElementTag("unknown");

    public String idType = null;

    public ObjectTag idHolder = defaultIdHolder;

    public Long uniquifier = null;

    public Inventory inventory = null;

    public String prefix = getObjectType();

    private InventoryTag(Inventory inventory) {
        this.inventory = inventory;
        loadIdentifiers(inventory.getHolder());
    }

    public InventoryTag(Inventory inventory, InventoryHolder holder) {
        this.inventory = inventory;
        loadIdentifiers(holder);
    }

    public InventoryTag(Inventory inventory, String type, ObjectTag holder) {
        this.inventory = inventory;
        this.idType = type;
        this.idHolder = holder;
    }

    public InventoryTag(ItemStack[] items) {
        inventory = Bukkit.getServer().createInventory(null, (int) Math.ceil(items.length / 9.0) * 9);
        idType = "generic";
        idHolder = new ElementTag("chest");
        setContents(items);
    }

    public InventoryTag(ImprovedOfflinePlayer offlinePlayer) {
        this(offlinePlayer, false);
    }

    public InventoryTag(ImprovedOfflinePlayer offlinePlayer, boolean isEnderChest) {
        inventory = isEnderChest ? offlinePlayer.getEnderChest() : offlinePlayer.getInventory();
        idType = isEnderChest ? "enderchest" : "player";
        idHolder = new PlayerTag(offlinePlayer.getUniqueId());
    }

    public InventoryTag(int size, String title) {
        if (size <= 0 || size % 9 != 0) {
            Debug.echoError("InventorySize must be multiple of 9, and greater than 0.");
            return;
        }
        inventory = AdvancedTextImpl.instance.createInventory(null, size, title);
        idType = "generic";
        idHolder = new ElementTag("chest");
    }

    public InventoryTag(InventoryType type) {
        inventory = Bukkit.getServer().createInventory(null, type);
        idType = "generic";
        idHolder = new ElementTag(CoreUtilities.toLowerCase(type.name()));
    }

    public InventoryTag(InventoryType type, String title) {
        inventory = AdvancedTextImpl.instance.createInventory(null, type, title);
        idType = "generic";
        idHolder = new ElementTag(CoreUtilities.toLowerCase(type.name()));
    }

    public InventoryTag(int size) {
        this(size, "Chest");
    }

    public Inventory getInventory() {
        return inventory;
    }

    public boolean containsItem(ItemTag item, int amount) {
        if (item == null) {
            return false;
        }
        item = new ItemTag(item.getItemStack().clone());
        item.setAmount(1);
        String myItem = CoreUtilities.toLowerCase(item.identify());
        for (int i = 0; i < inventory.getSize(); i++) {
            ItemStack is = inventory.getItem(i);
            if (is == null || item.getMaterial().getMaterial() != is.getType()) {
                continue;
            }
            is = is.clone();
            int count = is.getAmount();
            is.setAmount(1);
            String newItem = CoreUtilities.toLowerCase(new ItemTag(is).identify());
            if (myItem.equals(newItem)) {
                if (count <= amount) {
                    amount -= count;
                    if (amount == 0) {
                        return true;
                    }
                }
                else if (count > amount) {
                    return true;
                }
            }
        }
        return false;
    }

    public void setSize(int size) {
        if (!getIdType().equals("generic") && !getIdType().equals("script")) {
            return;
        }
        else if (size <= 0 || size % 9 != 0) {
            Debug.echoError("InventorySize must be multiple of 9, and greater than 0.");
            return;
        }
        else if (inventory == null) {
            inventory = Bukkit.getServer().createInventory(null, size, "Chest");
            return;
        }
        int oldSize = inventory.getSize();
        if (oldSize == size) {
            return;
        }
        ItemStack[] oldContents = inventory.getContents();
        ItemStack[] newContents = new ItemStack[size];
        if (oldSize > size) {
            // TODO: Why is this a manual copy?
            System.arraycopy(oldContents, 0, newContents, 0, size);
        }
        else {
            newContents = oldContents;
        }
        String title = AdvancedTextImpl.instance.getTitle(inventory);
        if (title == null) {
            setInventory(Bukkit.getServer().createInventory(null, size));
        }
        else {
            setInventory(AdvancedTextImpl.instance.createInventory(null, size, title));
        }
        inventory.setContents(newContents);
        trackTemporaryInventory(this);
    }

    private void loadIdentifiers(final InventoryHolder holder) {
        if (inventory == null) {
            return;
        }
        InventoryTag realInv = InventoryTrackerSystem.getTagFormFor(inventory);
        if (realInv != null) {
            Debug.echoError("Tried to load already-tracked inventory as new inventory?");
            return;
        }
        trackTemporaryInventory(this);
        if (holder != null) {
            if (holder instanceof NPCTag) {
                idType = "npc";
                idHolder = ((NPCTag) holder);
                return;
            }
            else if (holder instanceof Player) {
                if (Depends.citizens != null && CitizensAPI.getNPCRegistry().isNPC((Player) holder)) {
                    idType = "npc";
                    idHolder = (NPCTag.fromEntity((Player) holder));
                    return;
                }
                if (inventory.getType() == InventoryType.CRAFTING) {
                    idType = "crafting";
                }
                if (inventory.getType() == InventoryType.ENDER_CHEST) {
                    idType = "enderchest";
                }
                else if (inventory.getType() == InventoryType.WORKBENCH) {
                    idType = "workbench";
                }
                else {
                    idType = "player";
                }
                idHolder = new PlayerTag((Player) holder);
                return;
            }
            else if (holder instanceof Entity) {
                idType = "entity";
                idHolder = new EntityTag((Entity) holder);
                return;
            }
            else {
                idType = "location";
                idHolder = getLocation(holder);
                if (idHolder != null) {
                    return;
                }
            }
        }
        else if (inventory instanceof AnvilInventory && inventory.getLocation() != null) {
            idType = "location";
            idHolder = new LocationTag(inventory.getLocation());
        }
        else if (getIdType().equals("player")) {
            if (idHolder instanceof PlayerTag) {
                return;
            }
            // Iterate through offline player inventories
            for (Map.Entry<UUID, PlayerInventory> inv : ImprovedOfflinePlayer.offlineInventories.entrySet()) { // TODO: Less weird lookup?
                if (inv.getValue().equals(inventory)) {
                    idHolder = new PlayerTag(inv.getKey());
                    return;
                }
            }
        }
        else if (getIdType().equals("enderchest")) {
            if (idHolder instanceof PlayerTag) {
                return;
            }
            // Iterate through offline player enderchests
            for (Map.Entry<UUID, Inventory> inv : ImprovedOfflinePlayer.offlineEnderChests.entrySet()) { // TODO: Less weird lookup?
                if (inv.getValue().equals(inventory)) {
                    idHolder = new PlayerTag(inv.getKey());
                    return;
                }
            }
        }
        else if (getIdType().equals("script")) {
            if (idHolder instanceof ScriptTag) {
                return;
            }
            InventoryTag tracked = InventoryTrackerSystem.retainedInventoryLinks.get(inventory);
            if (tracked != null) {
                idHolder = tracked.idHolder;
                return;
            }
        }
        idType = "generic";
        idHolder = new ElementTag(CoreUtilities.toLowerCase(getInventory().getType().name()));
    }

    public String getIdType() {
        return idType == null ? "" : idType;
    }

    public ObjectTag getIdHolder() {
        return idHolder;
    }

    public LocationTag getLocation() {
        return getLocation(inventory.getHolder());
    }

    public LocationTag getLocation(InventoryHolder holder) {
        if (inventory != null && holder != null) {
            if (holder instanceof BlockState) {
                return new LocationTag(((BlockState) holder).getLocation());
            }
            else if (holder instanceof DoubleChest) {
                return new LocationTag(((DoubleChest) holder).getLocation());
            }
            else if (holder instanceof Entity) {
                return new LocationTag(((Entity) holder).getLocation());
            }
            else if (holder instanceof NPCTag) {
                NPCTag npc = (NPCTag) holder;
                if (npc.getLocation() == null) {
                    return new LocationTag(((NPCTag) holder).getCitizen().getStoredLocation());
                }
                return npc.getLocation();
            }
        }

        return null;
    }

    public ItemStack[] getContents() {
        if (inventory != null) {
            return inventory.getContents();
        }
        else {
            return new ItemStack[0];
        }
    }

    public ItemStack[] getStorageContents() {
        if (inventory != null) {
            return inventory.getStorageContents();
        }
        else {
            return new ItemStack[0];
        }
    }

    public static void addToMapIfNonAir(MapTag map, String name, ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return;
        }
        map.putObject(name, new ItemTag(item));
    }

    public MapTag getEquipmentMap() {
        MapTag output = new MapTag();
        if (inventory instanceof PlayerInventory) {
            ItemStack[] equipment = ((PlayerInventory) inventory).getArmorContents();
            addToMapIfNonAir(output, "boots", equipment[0]);
            addToMapIfNonAir(output, "leggings", equipment[1]);
            addToMapIfNonAir(output, "chestplate", equipment[2]);
            addToMapIfNonAir(output, "helmet", equipment[3]);
        }
        else if (inventory instanceof HorseInventory) {
            addToMapIfNonAir(output, "saddle", ((HorseInventory) inventory).getSaddle());
            addToMapIfNonAir(output, "armor", ((HorseInventory) inventory).getArmor());
        }
        else {
            return null;
        }
        return output;
    }

    public ListTag getEquipment() {
        ItemStack[] equipment = null;
        if (inventory instanceof PlayerInventory) {
            equipment = ((PlayerInventory) inventory).getArmorContents();
        }
        else if (inventory instanceof HorseInventory) {
            equipment = new ItemStack[] {((HorseInventory) inventory).getSaddle(), ((HorseInventory) inventory).getArmor()};
        }
        if (equipment == null) {
            return null;
        }
        ListTag equipmentList = new ListTag();
        for (ItemStack item : equipment) {
            equipmentList.addObject(new ItemTag(item));
        }
        return equipmentList;
    }

    public InventoryType getInventoryType() {
        return inventory.getType();
    }

    public int getSize() {
        return inventory.getSize();
    }

    public void setContents(ItemStack[] contents) {
        inventory.setContents(contents);
    }

    public void setContents(ListTag list, TagContext context) {
        int size;
        if (inventory == null) {
            size = (int) Math.ceil(list.size() / 9.0) * 9;
            if (size == 0) {
                size = 9;
            }
            inventory = Bukkit.getServer().createInventory(null, size);
            idType = "generic";
            idHolder = new ElementTag("chest");
        }
        else {
            size = inventory.getSize();
        }
        ItemStack[] contents = new ItemStack[size];
        int filled = 0;
        for (ItemTag item : list.filter(ItemTag.class, context)) {
            if (filled >= contents.length) {
                Debug.echoError("Cannot set contents of inventory to " + list.size() + " items, as the inventory size is only " + size + "!");
                break;
            }
            contents[filled] = item.getItemStack();
            filled++;
        }
        while (filled < size) {
            contents[filled] = new ItemStack(Material.AIR);
            filled++;
        }
        inventory.setContents(contents);
        if (Depends.citizens != null && idHolder instanceof NPCTag) {
            ((NPCTag) idHolder).getInventoryTrait().setContents(contents);
        }
    }

    public int firstPartial(int startSlot, ItemStack item) {
        ItemStack[] inventory = getContents();
        if (item == null) {
            return -1;
        }
        for (int i = startSlot; i < inventory.length; i++) {
            ItemStack item1 = inventory[i];
            if (item1 != null && item1.getAmount() < item.getMaxStackSize() && item1.isSimilar(item)) {
                return i;
            }
        }
        return -1;
    }

    public int firstEmpty(int startSlot) {
        ItemStack[] inventory = getStorageContents();
        for (int i = startSlot; i < inventory.length; i++) {
            if (inventory[i] == null) {
                return i;
            }
        }
        return -1;
    }

    // Somewhat simplified version of CraftBukkit's code
    public InventoryTag add(int slot, ItemStack... items) {
        if (inventory == null || items == null) {
            return this;
        }
        for (int i = 0; i < items.length; i++) {
            ItemStack item = items[i];
            if (item == null || item.getType().isAir()) {
                continue;
            }
            int amount = item.getAmount();
            int max = item.getMaxStackSize();
            while (true) {
                // Do we already have a stack of it?
                int firstPartial = firstPartial(slot, item);
                // Drat! no partial stack
                if (firstPartial == -1) {
                    // Find a free spot!
                    int firstFree = firstEmpty(slot);
                    if (firstFree == -1) {
                        // No space at all!
                        break;
                    }
                    else {
                        // More than a single stack!
                        if (amount > max) {
                            ItemStack clone = item.clone();
                            clone.setAmount(max);
                            NMSHandler.getItemHelper().setInventoryItem(inventory, clone, firstFree);
                            item.setAmount(amount -= max);
                        }
                        else {
                            // Just store it
                            NMSHandler.getItemHelper().setInventoryItem(inventory, item, firstFree);
                            break;
                        }
                    }
                }
                else {
                    // So, apparently it might only partially fit, well lets do just that
                    ItemStack partialItem = inventory.getItem(firstPartial);
                    int partialAmount = partialItem.getAmount();
                    int total = amount + partialAmount;
                    // Check if it fully fits
                    if (total <= max) {
                        partialItem.setAmount(total);
                        break;
                    }
                    // It fits partially
                    partialItem.setAmount(max);
                    item.setAmount(amount = total - max);
                }
            }
        }
        return this;
    }

    public List<ItemStack> addWithLeftovers(int slot, boolean keepMaxStackSize, ItemStack... items) {
        if (inventory == null || items == null) {
            return null;
        }
        List<ItemStack> leftovers = new ArrayList<>();
        for (int i = 0; i < items.length; i++) {
            ItemStack item = items[i];
            if (item == null || item.getType().isAir()) {
                continue;
            }
            int amount = item.getAmount();
            int max;
            if (keepMaxStackSize) {
                max = item.getMaxStackSize();
            }
            else {
                max = 64;
            }
            while (true) {
                // Do we already have a stack of it?
                int firstPartial = firstPartial(slot, item);
                // Drat! no partial stack
                if (firstPartial == -1) {
                    // Find a free spot!
                    int firstFree = firstEmpty(slot);

                    if (firstFree == -1) {
                        // No space at all!
                        leftovers.add(item);
                        break;
                    }
                    else {
                        // More than a single stack!
                        if (amount > max) {
                            ItemStack clone = item.clone();
                            clone.setAmount(max);
                            NMSHandler.getItemHelper().setInventoryItem(inventory, clone, firstFree);
                            item.setAmount(amount -= max);
                        }
                        else {
                            // Just store it
                            NMSHandler.getItemHelper().setInventoryItem(inventory, item, firstFree);
                            break;
                        }
                    }
                }
                else {
                    // So, apparently it might only partially fit, well lets do just that
                    ItemStack partialItem = inventory.getItem(firstPartial);
                    int partialAmount = partialItem.getAmount();
                    int total = amount + partialAmount;
                    // Check if it fully fits
                    if (total <= max) {
                        partialItem.setAmount(total);
                        break;
                    }
                    // It fits partially
                    partialItem.setAmount(max);
                    item.setAmount(amount = total - max);
                }
            }
        }
        if (Depends.citizens != null && idHolder instanceof NPCTag) {
            ((NPCTag) idHolder).getInventoryTrait().setContents(inventory.getContents());
        }
        return leftovers;
    }

    public int countByMaterial(Material material) {
        if (inventory == null) {
            return 0;
        }
        int qty = 0;
        for (ItemStack invStack : inventory) {
            if (invStack != null) {
                if (invStack.getType() == material && !(new ItemTag(invStack).isItemscript())) {
                    qty += invStack.getAmount();
                }
            }
        }
        return qty;
    }

    public int countByFlag(String flag) {
        if (inventory == null) {
            return 0;
        }
        int qty = 0;
        for (ItemStack invStack : inventory) {
            if (invStack != null) {
                ItemTag item = new ItemTag(invStack);
                if (item.getFlagTracker().hasFlag(flag)) {
                    qty += invStack.getAmount();
                }
            }
        }
        return qty;
    }

    public int countByScriptName(String scriptName) {
        if (inventory == null) {
            return 0;
        }
        int qty = 0;
        for (ItemStack invStack : inventory) {
            if (invStack != null) {
                ItemTag item = new ItemTag(invStack);
                if (item.isItemscript() && item.getScriptName().equalsIgnoreCase(scriptName)) {
                    qty += invStack.getAmount();
                }
            }
        }
        return qty;
    }

    /**
     * Count the number or quantities of stacks that
     * match an item in an inventory.
     *
     * @param item   The item (can be null)
     * @param stacks Whether stacks should be counted
     *               instead of item quantities
     * @return The number of stacks or quantity of items
     */
    public int count(ItemStack item, boolean stacks) {
        if (inventory == null) {
            return 0;
        }
        int qty = 0;
        for (ItemStack invStack : inventory) {
            // If ItemStacks are empty here, they are null
            if (invStack != null) {
                // If item is null, include all items in the inventory
                if (item == null || invStack.isSimilar(item)) {
                    // If stacks is true, only count the number of stacks
                    // Otherwise, count the quantities of stacks
                    qty += (stacks ? 1 : invStack.getAmount());
                }
            }
        }
        return qty;
    }

    public InventoryTag setSlots(int slot, ItemStack... items) {
        return setSlots(slot, items, items.length);
    }

    /**
     * Set items in an inventory, starting with a specified slot
     *
     * @param slot  The slot to start from
     * @param items The items to add
     * @return The resulting InventoryTag
     */
    public InventoryTag setSlots(int slot, ItemStack[] items, int c) {
        if (inventory == null || items == null) {
            return this;
        }
        for (int i = 0; i < c; i++) {
            if (i >= items.length || items[i] == null) {
                NMSHandler.getItemHelper().setInventoryItem(inventory, new ItemStack(Material.AIR), slot + i);
            }
            ItemStack item = items[i];
            if (slot + i < 0 || slot + i >= inventory.getSize()) {
                break;
            }
            NMSHandler.getItemHelper().setInventoryItem(inventory, item, slot + i);
        }
        if (Depends.citizens != null && idHolder instanceof NPCTag) {
            ((NPCTag) idHolder).getInventoryTrait().setContents(inventory.getContents());
        }
        return this;
    }

    public void clear() {
        if (inventory != null) {
            inventory.clear();
        }
    }

    @Override
    public String getObjectType() {
        return "Inventory";
    }

    @Override
    public String getPrefix() {
        return prefix;
    }

    @Override
    public InventoryTag setPrefix(String prefix) {
        this.prefix = prefix;
        return this;
    }

    @Override
    public String identify() {
        if (isUnique()) {
            return "in@" + NotableManager.getSavedId(this);
        }
        else {
            trackTemporaryInventory(this);
            return "in@" + idType + PropertyParser.getPropertiesString(this);
        }
    }

    @Override
    public String identifySimple() {
        return identify();
    }

    @Override
    public String toString() {
        return identify();
    }

    public static void registerTags() {

        AbstractFlagTracker.registerFlagHandlers(tagProcessor);
        PropertyParser.registerPropertyTagHandlers(tagProcessor);

        // <--[tag]
        // @attribute <InventoryTag.empty_slots>
        // @returns ElementTag(Number)
        // @description
        // Returns the number of empty slots in an inventory.
        // -->
        registerTag("empty_slots", (attribute, object) -> {
            InventoryTag dummyInv;
            if (object.inventory.getType() == InventoryType.PLAYER) {
                ItemStack[] contents = object.getStorageContents();
                dummyInv = new InventoryTag(contents.length);
                if (contents.length != dummyInv.getSize()) {
                    contents = Arrays.copyOf(contents, dummyInv.getSize());
                }
                dummyInv.setContents(contents);
            }
            else {
                dummyInv = object;
            }
            int full = dummyInv.count(null, true);
            return new ElementTag(dummyInv.getSize() - full);
        });

        // <--[tag]
        // @attribute <InventoryTag.can_fit[<item>|...]>
        // @returns ElementTag(Boolean)
        // @description
        // Returns whether the inventory can fit an item.
        // -->
        registerTag("can_fit", (attribute, object) -> {
            if (!attribute.hasContext(1)) {
                return null;
            }
            List<ItemTag> items = attribute.contextAsType(1, ListTag.class).filter(ItemTag.class, attribute.context, !attribute.hasAlternative());
            if (items == null || items.isEmpty()) {
                return null;
            }

            InventoryType type = object.inventory.getType();
            InventoryTag dummyInv = new InventoryTag(type == InventoryType.PLAYER ? InventoryType.CHEST : type, AdvancedTextImpl.instance.getTitle(object.inventory));
            ItemStack[] contents = object.getStorageContents();
            if (dummyInv.getInventoryType() == InventoryType.CHEST) {
                dummyInv.setSize(contents.length);
            }
            if (contents.length != dummyInv.getSize()) {
                contents = Arrays.copyOf(contents, dummyInv.getSize());
            }
            dummyInv.setContents(contents);

            // <--[tag]
            // @attribute <InventoryTag.can_fit[<item>].count>
            // @returns ElementTag(Number)
            // @description
            // Returns the total count of how many times an item can fit into an inventory.
            // -->
            if (attribute.startsWith("count", 2)) {
                ItemStack toAdd = items.get(0).getItemStack().clone();
                int totalCount = 64 * 64 * 4; // Technically nothing stops us from ridiculous numbers in an ItemStack amount.
                toAdd.setAmount(totalCount);
                List<ItemStack> leftovers = dummyInv.addWithLeftovers(0, true, toAdd);
                int result = 0;
                if (leftovers.size() > 0) {
                    result += leftovers.get(0).getAmount();
                }
                attribute.fulfill(1);
                return new ElementTag(totalCount - result);
            }

            // <--[tag]
            // @attribute <InventoryTag.can_fit[<item>].quantity[<#>]>
            // @returns ElementTag(Boolean)
            // @description
            // Returns whether the inventory can fit a certain quantity of an item.
            // -->
            if ((attribute.startsWith("quantity", 2) || attribute.startsWith("qty", 2)) && attribute.hasContext(2)) {
                if (attribute.startsWith("qty", 2)) {
                    Deprecations.qtyTags.warn(attribute.context);
                }
                int qty = attribute.getIntContext(2);
                ItemTag itemZero = new ItemTag(items.get(0).getItemStack().clone());
                itemZero.setAmount(qty);
                items.set(0, itemZero);
                attribute.fulfill(1);
            }

            // NOTE: Could just also convert items to an array and pass it all in at once...
            for (ItemTag itm : items) {
                List<ItemStack> leftovers = dummyInv.addWithLeftovers(0, true, itm.getItemStack().clone());
                if (!leftovers.isEmpty()) {
                    return new ElementTag(false);
                }
            }
            return new ElementTag(true);
        });

        // <--[tag]
        // @attribute <InventoryTag.include[<item>|...]>
        // @returns InventoryTag
        // @description
        // Returns a copy of the InventoryTag with items added.
        // -->
        registerTag("include", (attribute, object) -> {
            if (!attribute.hasContext(1)) {
                return null;
            }
            List<ItemTag> items = ListTag.getListFor(attribute.getContextObject(1), attribute.context).filter(ItemTag.class, attribute.context);
            InventoryTag dummyInv = new InventoryTag(object.inventory.getType(), AdvancedTextImpl.instance.getTitle(object.inventory));
            if (object.inventory.getType() == InventoryType.CHEST) {
                dummyInv.setSize(object.inventory.getSize());
            }
            dummyInv.setContents(object.getContents());
            if (object.idHolder instanceof ScriptTag) {
                dummyInv.idType = "script";
                dummyInv.idHolder = object.idHolder;
            }
            trackTemporaryInventory(dummyInv);

            // <--[tag]
            // @attribute <InventoryTag.include[<item>].quantity[<#>]>
            // @returns InventoryTag
            // @description
            // Returns the InventoryTag with a certain quantity of an item added.
            // -->
            if ((attribute.startsWith("quantity", 2) || attribute.startsWith("qty", 2)) && attribute.hasContext(2)) {
                if (attribute.startsWith("qty", 2)) {
                    Deprecations.qtyTags.warn(attribute.context);
                }
                int qty = attribute.getIntContext(2);
                ItemTag itemZero = new ItemTag(items.get(0).getItemStack().clone());
                itemZero.setAmount(qty);
                items.set(0, itemZero);
                attribute.fulfill(1);
            }
            for (ItemTag item: items) {
                dummyInv.add(0, item.getItemStack().clone());
            }
            return dummyInv;
        });

        // <--[tag]
        // @attribute <InventoryTag.exclude_item[<item_matcher>]>
        // @returns InventoryTag
        // @description
        // Returns a copy of the InventoryTag with all matching items excluded.
        // -->
        registerTag("exclude_item", (attribute, object) -> {
            if (!attribute.hasContext(1)) {
                return null;
            }
            String matcher = attribute.getContext(1);
            InventoryTag dummyInv = new InventoryTag(object.inventory.getType(), AdvancedTextImpl.instance.getTitle(object.inventory));
            if (object.inventory.getType() == InventoryType.CHEST) {
                dummyInv.setSize(object.inventory.getSize());
            }
            dummyInv.setContents(object.getContents());
            if (object.idHolder instanceof ScriptTag) {
                dummyInv.idType = "script";
                dummyInv.idHolder = object.idHolder;
            }
            trackTemporaryInventory(dummyInv);
            int quantity = Integer.MAX_VALUE;

            // <--[tag]
            // @attribute <InventoryTag.exclude_item[<item_matcher>].quantity[<#>]>
            // @returns InventoryTag
            // @description
            // Returns the InventoryTag with a certain quantity of matching items excluded.
            // -->
            if (attribute.startsWith("quantity", 2) && attribute.hasContext(2)) {
                quantity = attribute.getIntContext(2);
                attribute.fulfill(1);
            }
            for (int slot = 0; slot < dummyInv.inventory.getSize(); slot++) {
                ItemStack item = dummyInv.inventory.getItem(slot);
                if (item != null && BukkitScriptEvent.tryItem(new ItemTag(item), matcher)) {
                    quantity -= item.getAmount();
                    if (quantity >= 0) {
                        dummyInv.inventory.setItem(slot, null);
                    }
                    else {
                        item = item.clone();
                        item.setAmount(-quantity);
                        dummyInv.inventory.setItem(slot, item);
                    }
                    if (quantity <= 0) {
                        break;
                    }
                }
            }
            return dummyInv;
        });

        registerTag("exclude", (attribute, object) -> {
            Deprecations.inventoryNonMatcherTags.warn(attribute.context);
            if (!attribute.hasContext(1)) {
                return null;
            }
            List<ItemTag> items = ListTag.getListFor(attribute.getContextObject(1), attribute.context).filter(ItemTag.class, attribute.context);
            InventoryTag dummyInv = new InventoryTag(object.inventory.getType(), AdvancedTextImpl.instance.getTitle(object.inventory));
            if (object.inventory.getType() == InventoryType.CHEST) {
                dummyInv.setSize(object.inventory.getSize());
            }
            dummyInv.setContents(object.getContents());
            if (object.idHolder instanceof ScriptTag) {
                dummyInv.idType = "script";
                dummyInv.idHolder = object.idHolder;
            }
            trackTemporaryInventory(dummyInv);
            if ((attribute.startsWith("quantity", 2) || attribute.startsWith("qty", 2)) && attribute.hasContext(2)) {
                if (attribute.startsWith("qty", 2)) {
                    Deprecations.qtyTags.warn(attribute.context);
                }
                int qty = attribute.getIntContext(2);
                ItemTag itemZero = new ItemTag(items.get(0).getItemStack().clone());
                itemZero.setAmount(qty);
                items.set(0, itemZero);
                attribute.fulfill(1);
            }
            for (ItemTag item : items) {
                dummyInv.inventory.removeItem(item.getItemStack().clone());
            }
            return dummyInv;
        });

        // <--[tag]
        // @attribute <InventoryTag.is_empty>
        // @returns ElementTag(Boolean)
        // @description
        // Returns whether the inventory is empty.
        // -->
        registerTag("is_empty", (attribute, object) -> {
            boolean empty = true;
            for (ItemStack item : object.getStorageContents()) {
                if (item != null && item.getType() != Material.AIR) {
                    empty = false;
                    break;
                }
            }
            return new ElementTag(empty);
        });

        // <--[tag]
        // @attribute <InventoryTag.is_full>
        // @returns ElementTag(Boolean)
        // @description
        // Returns whether the inventory is completely full.
        // -->
        registerTag("is_full", (attribute, object) -> {
            boolean full = true;

            for (ItemStack item : object.getStorageContents()) {
                if ((item == null) ||
                        (item.getType() == Material.AIR) ||
                        (item.getAmount() < item.getMaxStackSize())) {
                    full = false;
                    break;
                }
            }
            return new ElementTag(full);
        });

        // <--[tag]
        // @attribute <InventoryTag.contains_item[<matcher>]>
        // @returns ElementTag(Boolean)
        // @description
        // Returns whether the inventory contains any item that matches the specified item matcher.
        // Uses the system behind <@link language Advanced Script Event Matching>.
        // -->
        registerTag("contains_item", (attribute, object) -> {
            if (!attribute.hasContext(1)) {
                return null;
            }
            int qty = 1;
            String matcher = attribute.getContext(1);

            // <--[tag]
            // @attribute <InventoryTag.contains_item[<matcher>].quantity[<#>]>
            // @returns ElementTag(Boolean)
            // @description
            // Returns whether the inventory contains a certain number of items that match the specified item matcher.
            // Uses the system behind <@link language Advanced Script Event Matching>.
            // -->
            if (attribute.startsWith("quantity", 2) && attribute.hasContext(2)) {
                qty = attribute.getIntContext(2);
                attribute.fulfill(1);
            }
            int found_items = 0;
            for (ItemStack item : object.getContents()) {
                if (item != null) {
                    if (BukkitScriptEvent.tryItem(new ItemTag(item), matcher)) {
                        found_items += item.getAmount();
                        if (found_items >= qty) {
                            break;
                        }
                    }
                }
            }
            return new ElementTag(found_items >= qty);
        });

        registerTag("contains", (attribute, object) -> {
            // <--[tag]
            // @attribute <InventoryTag.contains.display[(strict:)<element>]>
            // @returns ElementTag(Boolean)
            // @description
            // Returns whether the inventory contains an item with the specified display name.
            // Use 'strict:' in front of the search element to ensure the display name is EXACTLY the search element,
            // otherwise the searching will only check if the search element is contained in the display name.
            // -->
            if (attribute.startsWith("display", 2)) {
                if (!attribute.hasContext(2)) {
                    return null;
                }
                String search_string = attribute.getContext(2);
                boolean strict = false;
                if (CoreUtilities.toLowerCase(search_string).startsWith("strict:") && search_string.length() > 7) {
                    strict = true;
                    search_string = search_string.substring(7);
                }
                if (search_string.length() == 0) {
                    return null;
                }
                int qty = 1;

                // <--[tag]
                // @attribute <InventoryTag.contains.display[(strict:)<element>].quantity[<#>]>
                // @returns ElementTag(Boolean)
                // @description
                // Returns whether the inventory contains a certain quantity of an item with the specified display name.
                // Use 'strict:' in front of the search element to ensure the display name is EXACTLY the search element,
                // otherwise the searching will only check if the search element is contained in the display name.
                // -->
                if ((attribute.startsWith("quantity", 3) || attribute.startsWith("qty", 3)) && attribute.hasContext(3)) {
                    if (attribute.startsWith("qty", 3)) {
                        Deprecations.qtyTags.warn(attribute.context);
                    }
                    qty = attribute.getIntContext(3);
                    attribute.fulfill(1);
                }
                int found_items = 0;
                if (strict) {
                    for (ItemStack item : object.getContents()) {
                        if (item == null || !item.hasItemMeta()) {
                            continue;
                        }
                        ItemMeta meta = item.getItemMeta();
                        if (item.getType() == Material.WRITTEN_BOOK && ((BookMeta) meta).getTitle().equalsIgnoreCase(search_string)) {
                            found_items += item.getAmount();
                            if (found_items >= qty) {
                                break;
                            }
                        }
                        else if (meta.hasDisplayName() && meta.getDisplayName().equalsIgnoreCase(search_string)) {
                            found_items += item.getAmount();
                            if (found_items >= qty) {
                                break;
                            }
                        }
                    }
                }
                else {
                    for (ItemStack item : object.getContents()) {
                        if (item == null || !item.hasItemMeta()) {
                            continue;
                        }
                        ItemMeta meta = item.getItemMeta();
                        if (item.getType() == Material.WRITTEN_BOOK && CoreUtilities.toLowerCase(((BookMeta) meta).getTitle()).contains(CoreUtilities.toLowerCase(search_string))) {
                            found_items += item.getAmount();
                            if (found_items >= qty) {
                                break;
                            }
                        }
                        else if (meta.hasDisplayName() && CoreUtilities.toLowerCase(meta.getDisplayName()).contains(CoreUtilities.toLowerCase(search_string))) {
                            found_items += item.getAmount();
                            if (found_items >= qty) {
                                break;
                            }
                        }
                    }
                }
                attribute.fulfill(1);
                return new ElementTag(found_items >= qty);
            }
            // <--[tag]
            // @attribute <InventoryTag.contains.lore[(strict:)<element>|...]>
            // @returns ElementTag(Boolean)
            // @description
            // Returns whether the inventory contains an item with the specified lore.
            // Use 'strict:' in front of the search elements to ensure all lore lines are EXACTLY the search elements,
            // otherwise the searching will only check if the search elements are contained in the lore.
            // -->
            if (attribute.startsWith("lore", 2)) {
                if (!attribute.hasContext(2)) {
                    return null;
                }
                String search_string = attribute.getContext(2);
                boolean strict = false;
                if (CoreUtilities.toLowerCase(search_string).startsWith("strict:")) {
                    strict = true;
                    search_string = search_string.substring("strict:".length());
                }
                if (search_string.length() == 0) {
                    return null;
                }
                ListTag lore = ListTag.valueOf(search_string, attribute.context);
                int qty = 1;

                // <--[tag]
                // @attribute <InventoryTag.contains.lore[(strict:)<element>|...].quantity[<#>]>
                // @returns ElementTag(Boolean)
                // @description
                // Returns whether the inventory contains a certain quantity of an item with the specified lore.
                // Use 'strict:' in front of the search elements to ensure all lore lines are EXACTLY the search elements,
                // otherwise the searching will only check if the search elements are contained in the lore.
                // -->
                if ((attribute.startsWith("quantity", 3) || attribute.startsWith("qty", 3)) && attribute.hasContext(3)) {
                    if (attribute.startsWith("qty", 3)) {
                        Deprecations.qtyTags.warn(attribute.context);
                    }
                    qty = attribute.getIntContext(3);
                    attribute.fulfill(1);
                }
                int found_items = 0;
                if (strict) {
                    strict_items:
                    for (ItemStack item : object.getContents()) {
                        if (item == null || !item.hasItemMeta()) {
                            continue;
                        }
                        ItemMeta meta = item.getItemMeta();
                        if (meta.hasLore()) {
                            List<String> item_lore = meta.getLore();
                            if (lore.size() != item_lore.size()) {
                                continue;
                            }
                            for (int i = 0; i < item_lore.size(); i++) {
                                if (!lore.get(i).equalsIgnoreCase(item_lore.get(i))) {
                                    continue strict_items;
                                }
                            }
                            found_items += item.getAmount();
                            if (found_items >= qty) {
                                break;
                            }
                        }
                    }
                }
                else {
                    for (ItemStack item : object.getContents()) {
                        if (item == null || !item.hasItemMeta()) {
                            continue;
                        }
                        ItemMeta meta = item.getItemMeta();
                        if (meta.hasLore()) {
                            List<String> item_lore = meta.getLore();
                            int loreCount = 0;
                            lines:
                            for (String line : lore) {
                                for (String item_line : item_lore) {
                                    if (CoreUtilities.toLowerCase(item_line).contains(CoreUtilities.toLowerCase(line))) {
                                        loreCount++;
                                        continue lines;
                                    }
                                }
                            }
                            if (loreCount == lore.size()) {
                                found_items += item.getAmount();
                                if (found_items >= qty) {
                                    break;
                                }
                            }
                        }
                    }
                }
                attribute.fulfill(1);
                return new ElementTag(found_items >= qty);
            }
            if (attribute.startsWith("scriptname", 2)) {
                Deprecations.inventoryNonMatcherTags.warn(attribute.context);
                if (!attribute.hasContext(2)) {
                    return null;
                }
                ListTag scrNameList = attribute.contextAsType(2, ListTag.class);
                HashSet<String> scrNames = new HashSet<>();
                for (String name : scrNameList) {
                    scrNames.add(CoreUtilities.toLowerCase(name));
                }
                int qty = 1;

                if ((attribute.startsWith("quantity", 3) || attribute.startsWith("qty", 3)) && attribute.hasContext(3)) {
                    if (attribute.startsWith("qty", 3)) {
                        Deprecations.qtyTags.warn(attribute.context);
                    }
                    qty = attribute.getIntContext(3);
                    attribute.fulfill(1);
                }
                int found_items = 0;
                for (ItemStack item : object.getContents()) {
                    if (item != null) {
                        String itemName = new ItemTag(item).getScriptName();
                        if (itemName != null && scrNames.contains(CoreUtilities.toLowerCase(itemName))) {
                            found_items += item.getAmount();
                            if (found_items >= qty) {
                                break;
                            }
                        }
                    }
                }
                attribute.fulfill(1);
                return new ElementTag(found_items >= qty);
            }
            if (attribute.startsWith("flagged", 2)) {
                Deprecations.inventoryNonMatcherTags.warn(attribute.context);
                if (!attribute.hasContext(2)) {
                    return null;
                }
                ListTag scrNameList = attribute.contextAsType(2, ListTag.class);
                String[] flags = scrNameList.toArray(new String[0]);
                int qty = 1;

                if (attribute.startsWith("quantity", 3) && attribute.hasContext(3)) {
                    qty = attribute.getIntContext(3);
                    attribute.fulfill(1);
                }
                int found_items = 0;
                for (ItemStack item : object.getContents()) {
                    if (item != null) {
                        ItemTag itemTag = new ItemTag(item);
                        for (String flag : flags) {
                            if (itemTag.getFlagTracker().hasFlag(flag)) {
                                found_items += item.getAmount();
                                break;
                            }
                        }
                        if (found_items >= qty) {
                            break;
                        }
                    }
                }
                attribute.fulfill(1);
                return new ElementTag(found_items >= qty);
            }
            if (attribute.startsWith("nbt", 2)) {
                Deprecations.itemNbt.warn(attribute.context);
                if (!attribute.hasContext(2)) {
                    return null;
                }
                String keyName = attribute.getContext(2);
                int qty = 1;
                if ((attribute.startsWith("quantity", 3) || attribute.startsWith("qty", 3)) && attribute.hasContext(3)) {
                    if (attribute.startsWith("qty", 3)) {
                        Deprecations.qtyTags.warn(attribute.context);
                    }
                    qty = attribute.getIntContext(3);
                    attribute.fulfill(1);
                }
                int found_items = 0;
                for (ItemStack item : object.getContents()) {
                    if (CustomNBT.hasCustomNBT(item, keyName, CustomNBT.KEY_DENIZEN)) {
                        found_items += item.getAmount();
                        if (found_items >= qty) {
                            break;
                        }
                    }
                }
                attribute.fulfill(1);
                return new ElementTag(found_items >= qty);
            }
            if (attribute.startsWith("material", 2)) {
                Deprecations.inventoryNonMatcherTags.warn(attribute.context);
                if (!attribute.hasContext(2)) {
                    return null;
                }
                List<MaterialTag> materials = attribute.contextAsType(2, ListTag.class).filter(MaterialTag.class, attribute.context);
                int qty = 1;

                if ((attribute.startsWith("quantity", 3) || attribute.startsWith("qty", 3)) && attribute.hasContext(3)) {
                    if (attribute.startsWith("qty", 3)) {
                        Deprecations.qtyTags.warn(attribute.context);
                    }
                    qty = attribute.getIntContext(3);
                    attribute.fulfill(1);
                }
                int found_items = 0;
                mainLoop:
                for (ItemStack item : object.getContents()) {
                    if (item == null) {
                        continue;
                    }
                    for (MaterialTag material : materials) {
                        if (item.getType() == material.getMaterial() && !(new ItemTag(item).isItemscript())) {
                            found_items += item.getAmount();
                            if (found_items >= qty) {
                                break mainLoop;
                            }
                        }
                    }
                }
                attribute.fulfill(1);
                return new ElementTag(found_items >= qty);
            }
            if (!attribute.hasContext(1)) {
                return null;
            }
            ListTag list = attribute.contextAsType(1, ListTag.class);
            if (list.isEmpty()) {
                return null;
            }
            int qty = 1;

            Deprecations.inventoryNonMatcherTags.warn(attribute.context);
            if ((attribute.startsWith("quantity", 2) || attribute.startsWith("qty", 2)) && attribute.hasContext(2)) {
                if (attribute.startsWith("qty", 2)) {
                    Deprecations.qtyTags.warn(attribute.context);
                }
                qty = attribute.getIntContext(2);
                attribute.fulfill(1);
            }
            List<ItemTag> contains = list.filter(ItemTag.class, attribute.context, !attribute.hasAlternative());
            if (contains.size() == list.size()) {
                for (ItemTag item : contains) {
                    if (!object.containsItem(item, qty)) {
                        return new ElementTag(false);
                    }
                }
                return new ElementTag(true);
            }
            return new ElementTag(false);
        });

        registerTag("contains_any", (attribute, object) -> {
            Deprecations.inventoryNonMatcherTags.warn(attribute.context);
            if (!attribute.hasContext(1)) {
                return null;
            }
            ListTag list = attribute.contextAsType(1, ListTag.class);
            if (list.isEmpty()) {
                return null;
            }
            int qty = 1;

            if ((attribute.startsWith("quantity", 2) || attribute.startsWith("qty", 2)) && attribute.hasContext(2)) {
                if (attribute.startsWith("qty", 2)) {
                    Deprecations.qtyTags.warn(attribute.context);
                }
                qty = attribute.getIntContext(2);
                attribute.fulfill(1);
            }
            List<ItemTag> contains = list.filter(ItemTag.class, attribute.context, !attribute.hasAlternative());
            if (!contains.isEmpty()) {
                for (ItemTag item : contains) {
                    if (object.containsItem(item, qty)) {
                        return new ElementTag(true);
                    }
                }
            }
            return new ElementTag(false);
        });

        // <--[tag]
        // @attribute <InventoryTag.first_empty>
        // @returns ElementTag(Number)
        // @description
        // Returns the location of the first empty slot.
        // Returns -1 if the inventory is full.
        // -->
        registerTag("first_empty", (attribute, object) -> {
            int val = object.firstEmpty(0);
            return new ElementTag(val >= 0 ? (val + 1) : -1);
        });

        // <--[tag]
        // @attribute <InventoryTag.find_item[<matcher>]>
        // @returns ElementTag(Number)
        // @description
        // Returns the location of the first slot that contains an item that matches the given item matcher.
        // Returns -1 if there's no match.
        // Uses the system behind <@link language Advanced Script Event Matching>.
        // -->
        registerTag("find_item", (attribute, object) -> {
            if (!attribute.hasContext(1)) {
                return null;
            }
            String matcher = attribute.getContext(1);
            for (int i = 0; i < object.inventory.getSize(); i++) {
                ItemStack item = object.inventory.getItem(i);
                if (item != null) {
                    if (BukkitScriptEvent.tryItem(new ItemTag(item), matcher)) {
                        return new ElementTag(i + 1);
                    }
                }
            }
            return new ElementTag(-1);
        });

        registerTag("find", (attribute, object) -> {
            Deprecations.inventoryNonMatcherTags.warn(attribute.context);
            if (attribute.startsWith("material", 2)) {
                ListTag list = attribute.contextAsType(2, ListTag.class);
                if (list == null) {
                    return null;
                }
                HashSet<Material> materials = new HashSet<>();
                for (ObjectTag obj : list.objectForms) {
                    materials.add(obj.asType(MaterialTag.class, attribute.context).getMaterial());
                }
                int slot = -1;
                for (int i = 0; i < object.inventory.getSize(); i++) {
                    if (object.inventory.getItem(i) != null && materials.contains(object.inventory.getItem(i).getType())) {
                        slot = i + 1;
                        break;
                    }
                }
                attribute.fulfill(1);
                return new ElementTag(slot);
            }

            if (attribute.startsWith("scriptname", 2)) {
                String scrname = attribute.contextAsType(2, ItemTag.class).getScriptName();
                if (scrname == null) {
                    return null;
                }
                int slot = -1;
                for (int i = 0; i < object.inventory.getSize(); i++) {
                    if (object.inventory.getItem(i) != null
                            && scrname.equalsIgnoreCase(new ItemTag(object.inventory.getItem(i)).getScriptName())) {
                        slot = i + 1;
                        break;
                    }
                }
                attribute.fulfill(1);
                return new ElementTag(slot);
            }
            if (!attribute.hasContext(1) || !ItemTag.matches(attribute.getContext(1))) {
                return null;
            }
            ItemTag item = attribute.contextAsType(1, ItemTag.class);
            item.setAmount(1);
            int slot = -1;
            for (int i = 0; i < object.inventory.getSize(); i++) {
                if (object.inventory.getItem(i) != null) {
                    ItemTag compare_to = new ItemTag(object.inventory.getItem(i).clone());
                    compare_to.setAmount(1);
                    if (item.identify().equalsIgnoreCase(compare_to.identify())) {
                        slot = i + 1;
                        break;
                    }
                }
            }
            return new ElementTag(slot);
        });

        registerTag("find_imperfect", (attribute, object) -> {
            Deprecations.inventoryNonMatcherTags.warn(attribute.context);
            if (!attribute.hasContext(1) || !ItemTag.matches(attribute.getContext(1))) {
                return null;
            }
            ItemTag item = attribute.contextAsType(1, ItemTag.class);
            item.setAmount(1);
            int slot = -1;
            for (int i = 0; i < object.inventory.getSize(); i++) {
                if (object.inventory.getItem(i) != null) {
                    ItemTag compare_to = new ItemTag(object.inventory.getItem(i).clone());
                    compare_to.setAmount(1);
                    if (item.identify().equalsIgnoreCase(compare_to.identify())
                            || item.getScriptName().equalsIgnoreCase(compare_to.getScriptName())) {
                        slot = i + 1;
                        break;
                    }
                }
            }
            return new ElementTag(slot);
        });

        // <--[tag]
        // @attribute <InventoryTag.id_type>
        // @returns ElementTag
        // @description
        // Returns Denizen's type ID for this inventory (player, location, etc.).
        // -->
        registerTag("id_type", (attribute, object) -> {
            return new ElementTag(object.idType);
        });

        // <--[tag]
        // @attribute <InventoryTag.note_name>
        // @returns ElementTag
        // @description
        // Gets the name of a noted InventoryTag. If the inventory isn't noted, this is null.
        // -->
        registerTag("note_name", (attribute, object) -> {
            String noteName = NotableManager.getSavedId(object);
            if (noteName == null) {
                return null;
            }
            return new ElementTag(noteName);
        }, "notable_name");

        // <--[tag]
        // @attribute <InventoryTag.location>
        // @returns LocationTag
        // @description
        // Returns the location of this inventory's holder.
        // -->
        registerTag("location", (attribute, object) -> {
            return object.getLocation();
        });

        // <--[tag]
        // @attribute <InventoryTag.quantity_item[(<matcher>)]>
        // @returns ElementTag(Number)
        // @description
        // Returns the combined quantity of itemstacks that match an item matcher if one is specified,
        // or the combined quantity of all itemstacks if one is not.
        // Uses the system behind <@link language Advanced Script Event Matching>.
        // -->
        registerTag("quantity_item", (attribute, object) -> {
            String matcher = attribute.hasContext(1) ? attribute.getContext(1) : null;
            int found_items = 0;
            for (ItemStack item : object.getContents()) {
                if (item != null) {
                    if (matcher == null || BukkitScriptEvent.tryItem(new ItemTag(item), matcher)) {
                        found_items += item.getAmount();
                    }
                }
            }
            return new ElementTag(found_items);
        });

        registerTag("quantity", (attribute, object) -> {
            Deprecations.inventoryNonMatcherTags.warn(attribute.context);
            if (attribute.startsWith("scriptname", 2)) {
                if (!attribute.hasContext(2)) {
                    return null;
                }
                String scriptName = attribute.getContext(2);
                attribute.fulfill(1);
                return new ElementTag(object.countByScriptName(scriptName));
            }
            if (attribute.startsWith("flagged", 2)) {
                if (!attribute.hasContext(2)) {
                    return null;
                }
                String flag = attribute.getContext(2);
                attribute.fulfill(1);
                return new ElementTag(object.countByFlag(flag));
            }
            if (attribute.startsWith("material", 2)) {
                if (!attribute.hasContext(2) || !MaterialTag.matches(attribute.getContext(2))) {
                    return null;
                }
                MaterialTag material = attribute.contextAsType(2, MaterialTag.class);
                attribute.fulfill(1);
                return new ElementTag(object.countByMaterial(material.getMaterial()));
            }
            if (attribute.hasContext(1) && ItemTag.matches(attribute.getContext(1))) {
                return new ElementTag(object.count
                        (attribute.contextAsType(1, ItemTag.class).getItemStack(), false));
            }
            else {
                return new ElementTag(object.count(null, false));
            }
        }, "qty");

        // <--[tag]
        // @attribute <InventoryTag.stacks[(<item>)]>
        // @returns ElementTag(Number)
        // @description
        // Returns the number of itemstacks that match an item if one is specified, or the number of all itemstacks if one is not.
        // -->
        registerTag("stacks", (attribute, object) -> {
            if (attribute.hasContext(1) && ItemTag.matches(attribute.getContext(1))) {
                return new ElementTag(object.count(attribute.contextAsType(1, ItemTag.class).getItemStack(), true));
            }
            else {
                return new ElementTag(object.count(null, true));
            }
        });

        // <--[tag]
        // @attribute <InventoryTag.slot[<#>|...]>
        // @returns ObjectTag
        // @description
        // If one slot is specified, returns the ItemTag in the specified slot.
        // If more than what slot is specified, returns a ListTag(ItemTag) of the item in each given slot.
        // -->
        registerTag("slot", (attribute, object) -> {
            if (!attribute.hasContext(1)) {
                return null;
            }
            ListTag slots = ListTag.getListFor(attribute.getContextObject(1), attribute.context);
            if (slots.isEmpty()) {
                if (!attribute.hasAlternative()) {
                    Debug.echoError("Cannot get a list of zero slots.");
                }
                return null;
            }
            else if (slots.size() == 1) {
                int slot = SlotHelper.nameToIndexFor(attribute.getContext(1), object.getInventory().getHolder());
                if (slot < 0) {
                    slot = 0;
                }
                else if (slot > object.getInventory().getSize() - 1) {
                    slot = object.getInventory().getSize() - 1;
                }
                return new ItemTag(object.getInventory().getItem(slot));
            }
            else {
                ListTag result = new ListTag();
                for (String slotText : slots) {
                    int slot = SlotHelper.nameToIndexFor(slotText, object.getInventory().getHolder());
                    if (slot < 0) {
                        slot = 0;
                    }
                    else if (slot > object.getInventory().getSize() - 1) {
                        slot = object.getInventory().getSize() - 1;
                    }
                    result.addObject(new ItemTag(object.getInventory().getItem(slot)));
                }
                return result;
            }
        });

        // <--[tag]
        // @attribute <InventoryTag.inventory_type>
        // @returns ElementTag
        // @description
        // Returns the type of the inventory (e.g. "PLAYER", "CRAFTING", "HORSE").
        // -->
        registerTag("inventory_type", (attribute, object) -> {
            return new ElementTag(object.inventory instanceof HorseInventory ? "HORSE" : object.getInventory().getType().name());
        });

        // <--[tag]
        // @attribute <InventoryTag.equipment_map>
        // @returns MapTag
        // @description
        // Returns a MapTag containing the inventory's equipment.
        // Output keys for players are boots, leggings,  chestplate, helmet.
        // Output keys for horses are saddle, armor.
        // Air items will be left out of the map.
        // -->
        registerTag("equipment_map", (attribute, object) -> {
            return object.getEquipmentMap();
        });

        // <--[tag]
        // @attribute <InventoryTag.equipment>
        // @returns ListTag(ItemTag)
        // @description
        // Returns the equipment of an inventory as a list of items.
        // For players, the order is boots|leggings|chestplate|helmet.
        // For horses, the order is saddle|armor.
        // -->
        registerTag("equipment", (attribute, object) -> {
            return object.getEquipment();
        });

        // <--[tag]
        // @attribute <InventoryTag.matrix>
        // @returns ListTag(ItemTag)
        // @mechanism InventoryTag.matrix
        // @description
        // Returns the items currently in a crafting inventory's matrix.
        // -->
        registerTag("matrix", (attribute, object) -> {
            if (!(object.inventory instanceof CraftingInventory)) {
                return null;
            }
            ListTag recipeList = new ListTag();
            for (ItemStack item : ((CraftingInventory) object.inventory).getMatrix()) {
                if (item != null) {
                    recipeList.addObject(new ItemTag(item));
                }
                else {
                    recipeList.addObject(new ItemTag(Material.AIR));
                }
            }
            return recipeList;
        });

        // <--[tag]
        // @attribute <InventoryTag.recipe>
        // @returns ElementTag
        // @description
        // Returns the recipe ID for the recipe currently formed in a crafting inventory.
        // Returns a list in the Namespace:Key format, for example "minecraft:stick".
        // -->
        registerTag("recipe", (attribute, object) -> {
            Recipe recipe;
            if ((object.inventory instanceof CraftingInventory)) {
                recipe = ((CraftingInventory) object.inventory).getRecipe();
            }
            else {
                return null;
            }
            if (recipe == null) {
                return null;
            }
            return new ElementTag(((Keyed) recipe).getKey().toString());
        });

        // <--[tag]
        // @attribute <InventoryTag.craftable_quantity>
        // @returns ElementTag(Number)
        // @description
        // Returns the quantity of items that would be received if this crafting inventory were fully crafted (eg via a shift click).
        // -->
        registerTag("craftable_quantity", (attribute, object) -> {
            Recipe recipe;
            if ((object.inventory instanceof CraftingInventory)) {
                recipe = ((CraftingInventory) object.inventory).getRecipe();
            }
            else {
                return null;
            }
            if (recipe == null) {
                return null;
            }
            return new ElementTag(RecipeHelper.getMaximumOutputQuantity(recipe, (CraftingInventory) object.inventory) * recipe.getResult().getAmount());
        });

        // <--[tag]
        // @attribute <InventoryTag.result>
        // @returns ItemTag
        // @mechanism InventoryTag.result
        // @description
        // Returns the item currently in the result section of a crafting inventory or furnace inventory.
        // -->
        registerTag("result", (attribute, object) -> {
            ItemStack result;
            if ((object.inventory instanceof CraftingInventory)) {
                result = ((CraftingInventory) object.inventory).getResult();
            }
            else if ((object.inventory instanceof FurnaceInventory)) {
                result = ((FurnaceInventory) object.inventory).getResult();
            }
            else {
                return null;
            }
            if (result == null) {
                return null;
            }
            return new ItemTag(result);
        });

        // <--[tag]
        // @attribute <InventoryTag.anvil_repair_cost>
        // @returns ElementTag(Number)
        // @mechanism InventoryTag.anvil_repair_cost
        // @description
        // Returns the current repair cost on an anvil.
        // -->
        registerTag("anvil_repair_cost", (attribute, object) -> {
            if (!(object.inventory instanceof AnvilInventory)) {
                return null;
            }
            return new ElementTag(((AnvilInventory) object.inventory).getRepairCost());
        });

        // <--[tag]
        // @attribute <InventoryTag.anvil_max_repair_cost>
        // @returns ElementTag(Number)
        // @mechanism InventoryTag.anvil_max_repair_cost
        // @description
        // Returns the maximum repair cost on an anvil.
        // -->
        registerTag("anvil_max_repair_cost", (attribute, object) -> {
            if (!(object.inventory instanceof AnvilInventory)) {
                return null;
            }
            return new ElementTag(((AnvilInventory) object.inventory).getMaximumRepairCost());
        });

        // <--[tag]
        // @attribute <InventoryTag.anvil_rename_text>
        // @returns ElementTag
        // @description
        // Returns the current entered renaming text on an anvil.
        // -->
        registerTag("anvil_rename_text", (attribute, object) -> {
            if (!(object.inventory instanceof AnvilInventory)) {
                return null;
            }
            return new ElementTag(((AnvilInventory) object.inventory).getRenameText(), true);
        });

        // <--[tag]
        // @attribute <InventoryTag.fuel>
        // @returns ItemTag
        // @mechanism InventoryTag.fuel
        // @description
        // Returns the item currently in the fuel section of a furnace or brewing stand inventory.
        // -->
        registerTag("fuel", (attribute, object) -> {
            if (object.getInventory() instanceof FurnaceInventory) {
                return new ItemTag(((FurnaceInventory) object.getInventory()).getFuel());
            }
            if (object.getInventory() instanceof BrewerInventory) {
                return new ItemTag(((BrewerInventory) object.getInventory()).getFuel());
            }
            return null;
        });

        // <--[tag]
        // @attribute <InventoryTag.input>
        // @returns ItemTag
        // @mechanism InventoryTag.input
        // @description
        // Returns the item currently in the smelting slot of a furnace inventory, or the ingredient slot of a brewing stand inventory.
        // -->
        registerTag("input", (attribute, object) -> {
            if (object.getInventory() instanceof FurnaceInventory) {
                return new ItemTag(((FurnaceInventory) object.getInventory()).getSmelting());
            }
            if (object.getInventory() instanceof BrewerInventory) {
                return new ItemTag(((BrewerInventory) object.getInventory()).getIngredient());
            }
            return null;
        });
        tagProcessor.registerFutureTagDeprecation("smelting", "input");

        // <--[tag]
        // @attribute <InventoryTag.advanced_matches[<matcher>]>
        // @returns ElementTag(Boolean)
        // @group element checking
        // @description
        // Returns whether the inventory matches some matcher text, using the system behind <@link language Advanced Script Event Matching>.
        // -->
        registerTag("advanced_matches", (attribute, object) -> {
            if (!attribute.hasContext(1)) {
                return null;
            }
            return new ElementTag(BukkitScriptEvent.tryInventory(object, attribute.getContext(1)));
        });
    }

    public static ObjectTagProcessor<InventoryTag> tagProcessor = new ObjectTagProcessor<>();

    public static void registerTag(String name, TagRunnable.ObjectInterface<InventoryTag> runnable, String... variants) {
        tagProcessor.registerTag(name, runnable, variants);
    }

    @Override
    public ObjectTag getObjectAttribute(Attribute attribute) {
        return tagProcessor.getObjectAttribute(this, attribute);
    }

    public void applyProperty(Mechanism mechanism) {
        Debug.echoError("Cannot apply properties to non-generic inventory!");
    }

    @Override
    public void adjust(Mechanism mechanism) {

        CoreUtilities.autoPropertyMechanism(this, mechanism);

        // <--[mechanism]
        // @object InventoryTag
        // @name matrix
        // @input ListTag(ItemTag)
        // @description
        // Sets the items in the matrix slots of this crafting inventory.
        // @tags
        // <InventoryTag.matrix>
        // -->
        if (mechanism.matches("matrix") && mechanism.requireObject(ListTag.class)) {
            if (!(inventory instanceof CraftingInventory)) {
                Debug.echoError("Inventory is not a crafting inventory, cannot set matrix.");
                return;
            }
            CraftingInventory craftingInventory = (CraftingInventory) inventory;
            List<ItemTag> items = mechanism.valueAsType(ListTag.class).filter(ItemTag.class, mechanism.context);
            ItemStack[] itemStacks = new ItemStack[9];
            for (int i = 0; i < 9 && i < items.size(); i++) {
                itemStacks[i] = items.get(i).getItemStack();
            }
            craftingInventory.setMatrix(itemStacks);
            ((Player) inventory.getHolder()).updateInventory();
        }

        // <--[mechanism]
        // @object InventoryTag
        // @name result
        // @input ItemTag
        // @description
        // Sets the item in the result slot of this crafting inventory or furnace inventory.
        // @tags
        // <InventoryTag.result>
        // -->
        if (mechanism.matches("result") && mechanism.requireObject(ItemTag.class)) {
            if (inventory instanceof CraftingInventory) {
                CraftingInventory craftingInventory = (CraftingInventory) inventory;
                craftingInventory.setResult(mechanism.valueAsType(ItemTag.class).getItemStack());
                ((Player) inventory.getHolder()).updateInventory();
            }
            else if (inventory instanceof FurnaceInventory) {
                FurnaceInventory furnaceInventory = (FurnaceInventory) inventory;
                furnaceInventory.setResult(mechanism.valueAsType(ItemTag.class).getItemStack());
            }
            else {
                Debug.echoError("Inventory is not a crafting inventory or furnace inventory, cannot set result.");
            }
        }

        // <--[mechanism]
        // @object InventoryTag
        // @name fuel
        // @input ItemTag
        // @description
        // Sets the item in the fuel slot of this furnace or brewing stand inventory.
        // @tags
        // <InventoryTag.fuel>
        // -->
        if (mechanism.matches("fuel") && mechanism.requireObject(ItemTag.class)) {
            if (inventory instanceof FurnaceInventory) {
                ((FurnaceInventory) inventory).setFuel(mechanism.valueAsType(ItemTag.class).getItemStack());
            }
            else if (inventory instanceof BrewerInventory) {
                ((BrewerInventory) inventory).setFuel(mechanism.valueAsType(ItemTag.class).getItemStack());
            }
            else {
                Debug.echoError("Inventory is not a furnace or brewing stand inventory, cannot set fuel.");
            }
        }

        // <--[mechanism]
        // @object InventoryTag
        // @name input
        // @input ItemTag
        // @description
        // Sets the item in the smelting slot of a furnace inventory, or ingredient slot of a brewing stand inventory.
        // @tags
        // <InventoryTag.input>
        // -->
        if ((mechanism.matches("input") || mechanism.matches("smelting")) && mechanism.requireObject(ItemTag.class)) {
            if (inventory instanceof FurnaceInventory) {
                ((FurnaceInventory) inventory).setSmelting(mechanism.valueAsType(ItemTag.class).getItemStack());
            }
            else if (inventory instanceof BrewerInventory) {
                ((BrewerInventory) inventory).setIngredient(mechanism.valueAsType(ItemTag.class).getItemStack());
            }
            else {
                Debug.echoError("Inventory is not a furnace inventory, cannot set smelting.");
            }
        }

        // <--[mechanism]
        // @object InventoryTag
        // @name anvil_max_repair_cost
        // @input ElementTag(Number)
        // @description
        // Sets the maximum repair cost of an anvil.
        // @tags
        // <InventoryTag.anvil_max_repair_cost>
        // -->
        if (mechanism.matches("anvil_max_repair_cost") && mechanism.requireInteger()) {
            if (!(inventory instanceof AnvilInventory)) {
                Debug.echoError("Inventory is not an anvil, cannot set max repair cost.");
                return;
            }
            ((AnvilInventory) inventory).setMaximumRepairCost(mechanism.getValue().asInt());
        }

        // <--[mechanism]
        // @object InventoryTag
        // @name anvil_repair_cost
        // @input ElementTag(Number)
        // @description
        // Sets the current repair cost of an anvil.
        // @tags
        // <InventoryTag.anvil_repair_cost>
        // -->
        if (mechanism.matches("anvil_repair_cost") && mechanism.requireInteger()) {
            if (!(inventory instanceof AnvilInventory)) {
                Debug.echoError("Inventory is not an anvil, cannot set repair cost.");
                return;
            }
            ((AnvilInventory) inventory).setRepairCost(mechanism.getValue().asInt());
        }

        // <--[mechanism]
        // @object InventoryTag
        // @name reformat
        // @input ElementTag
        // @description
        // Reformats the contents of an inventory to ensure any items within will be stackable with new Denizen-produced items.
        // This is a simple handy cleanup tool that may sometimes be useful with Denizen updates.
        // This essentially just parses the item to Denizen text, back to an item, and replaces the slot.
        // Input can be "scripts" to only change items spawned by item scripts, or "all" to change ALL items.
        // Most users are recommended to only use "scripts".
        // -->
        if (mechanism.matches("reformat")) {
            ItemStack[] items = inventory.getContents();
            boolean any = false;
            boolean scriptsOnly = CoreUtilities.equalsIgnoreCase(mechanism.getValue().asString(), "scripts");
            if (!scriptsOnly && !CoreUtilities.equalsIgnoreCase(mechanism.getValue().asString(), "all")) {
                mechanism.echoError("Invalid input to 'reformat' mechanism.");
                return;
            }
            for (int i = 0; i < items.length; i++) {
                ItemStack item = items[i];
                if (item == null) {
                    continue;
                }
                if (scriptsOnly && !ItemScriptHelper.isItemscript(item)) {
                    continue;
                }
                any = true;
                String format = new ItemTag(item).identify();
                ItemTag result = ItemTag.valueOf(format, mechanism.context);
                if (result != null) {
                    items[i] = result.getItemStack();
                }
            }
            if (any) {
                inventory.setContents(items);
            }
        }
    }
}
