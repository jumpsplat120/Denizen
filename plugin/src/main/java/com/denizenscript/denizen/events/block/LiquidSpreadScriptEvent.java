package com.denizenscript.denizen.events.block;

import com.denizenscript.denizen.objects.LocationTag;
import com.denizenscript.denizen.objects.MaterialTag;
import com.denizenscript.denizen.events.BukkitScriptEvent;
import com.denizenscript.denizencore.objects.ObjectTag;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFromToEvent;

public class LiquidSpreadScriptEvent extends BukkitScriptEvent implements Listener {

    // <--[event]
    // @Events
    // liquid spreads
    // <liquid block> spreads
    // dragon egg moves
    //
    // @Regex ^on [^\s]+ spreads$
    //
    // @Switch type:<material> to only run if the block spreading matches the material input.
    //
    // @Group Block
    //
    // @Location true
    //
    // @Cancellable true
    //
    // @Triggers when a liquid block spreads or dragon egg moves.
    //
    // @Context
    // <context.destination> returns the LocationTag the block spread to.
    // <context.location> returns the LocationTag the block spread location.
    // <context.material> returns the MaterialTag of the block that spread.
    //
    // -->

    public LiquidSpreadScriptEvent() {
        instance = this;
    }

    public static LiquidSpreadScriptEvent instance;
    public MaterialTag material;
    public LocationTag location;
    public LocationTag destination;
    public BlockFromToEvent event;

    @Override
    public boolean couldMatch(ScriptPath path) {
        if (!path.eventArgLowerAt(1).equals("spreads")) {
            return false;
        }
        if (path.eventLower.startsWith("block")) {
            return false;
        }
        if (path.eventLower.startsWith("dragon egg moves")) {
            return true;
        }
        if (!path.eventLower.startsWith("liquid") && !couldMatchBlock(path.eventArgLowerAt(0))) {
            return false;
        }
        return true;
    }

    @Override
    public boolean matches(ScriptPath path) {
        if (path.eventLower.startsWith("dragon egg moves")) {
            if (material.getMaterial() != Material.DRAGON_EGG) {
                return false;
            }
        }
        else {
            String mat = path.eventArgLowerAt(0);
            if (!mat.equals("liquid") && !tryMaterial(material, mat)) {
                return false;
            }
        }
        if (path.switches.containsKey("type") && !tryMaterial(material, path.switches.get("type"))) {
            return false;
        }
        if (!runInCheck(path, location) && !runInCheck(path, destination)) {
            return false;
        }
        return super.matches(path);
    }

    @Override
    public String getName() {
        return "LiquidSpreads";
    }

    @Override
    public ObjectTag getContext(String name) {
        switch (name) {
            case "location": return location;
            case "destination": return destination;
            case "material": return material;
        }
        return super.getContext(name);
    }

    @EventHandler
    public void onLiquidSpreads(BlockFromToEvent event) {
        destination = new LocationTag(event.getToBlock().getLocation());
        location = new LocationTag(event.getBlock().getLocation());
        material = new MaterialTag(event.getBlock());
        this.event = event;
        fire(event);
    }
}
