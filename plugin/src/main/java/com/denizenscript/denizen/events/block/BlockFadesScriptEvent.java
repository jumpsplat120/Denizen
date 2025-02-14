package com.denizenscript.denizen.events.block;

import com.denizenscript.denizen.objects.LocationTag;
import com.denizenscript.denizen.objects.MaterialTag;
import com.denizenscript.denizen.events.BukkitScriptEvent;
import com.denizenscript.denizencore.objects.ObjectTag;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFadeEvent;

public class BlockFadesScriptEvent extends BukkitScriptEvent implements Listener {

    // <--[event]
    // @Events
    // block fades
    // <block> fades
    //
    // @Regex ^on [^\s]+ fades$
    //
    // @Group Block
    //
    // @Location true
    //
    // @Cancellable true
    //
    // @Triggers when a block fades, melts or disappears based on world conditions.
    //
    // @Context
    // <context.location> returns the LocationTag the block faded at.
    // <context.material> returns the MaterialTag of the block that faded.
    //
    // -->

    public BlockFadesScriptEvent() {
        instance = this;
    }

    public static BlockFadesScriptEvent instance;
    public LocationTag location;
    public MaterialTag material;
    public BlockFadeEvent event;

    @Override
    public boolean couldMatch(ScriptPath path) {
        if (!path.eventArgLowerAt(1).equals("fades")) {
            return false;
        }
        if (!couldMatchBlock(path.eventArgLowerAt(0))) {
            return false;
        }
        return true;
    }

    @Override
    public boolean matches(ScriptPath path) {
        if (!runInCheck(path, location)) {
            return false;
        }

        if (!tryMaterial(material, path.eventArgLowerAt(0))) {
            return false;
        }
        return super.matches(path);
    }

    @Override
    public String getName() {
        return "BlockFades";
    }

    @Override
    public ObjectTag getContext(String name) {
        switch (name) {
            case "location": return location;
            case "material": return material;
        }
        return super.getContext(name);
    }

    @EventHandler
    public void onBlockFades(BlockFadeEvent event) {
        location = new LocationTag(event.getBlock().getLocation());
        material = new MaterialTag(event.getBlock());
        this.event = event;
        fire(event);
    }
}
