package com.denizenscript.denizen.events.block;

import com.denizenscript.denizen.events.BukkitScriptEvent;
import com.denizenscript.denizen.objects.EntityTag;
import com.denizenscript.denizen.objects.LocationTag;
import com.denizenscript.denizencore.objects.ObjectTag;
import com.denizenscript.denizencore.objects.core.ElementTag;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

import java.util.ArrayList;
import java.util.List;

public class BlockDestroyedByExplosionEvent extends BukkitScriptEvent implements Listener {

    // <--[event]
    // @Events
    // block destroyed by explosion
    // <block> destroyed by explosion
    //
    // @Regex ^on [^\s]+ destroyed by explosion$
    //
    // @Group Block
    //
    // @Location true
    //
    // @Cancellable true
    //
    // @Warning This event fires extremely rapidly. One single TNT detonation can destroy a hundred blocks.
    //
    // @Triggers when a block is destroyed by an explosion (caused by either an entity or a block exploding).
    //
    // @Switch source_entity:<matcher> to only fire the event if the source is an entity that matches the given type. Note that "Primed_Tnt" is an entity, not a block.
    // @Switch source_block:<matcher> to only fire the event if the source is a block that matches the given type.
    //
    // @Context
    // <context.block> returns the block that exploded.
    // <context.source_location> returns the location of the source block or entity.
    // <context.source_entity> returns the entity that exploded, if any.
    // <context.strength> returns an ElementTag(Decimal) of the strength of the explosion.
    //
    // -->

    public BlockDestroyedByExplosionEvent() {
        instance = this;
    }

    public static BlockDestroyedByExplosionEvent instance;
    public BlockExplodeEvent blockEvent;
    public EntityExplodeEvent entityEvent;
    public Block block;
    public List<Block> rawList;

    @Override
    public boolean couldMatch(ScriptPath path) {
        if (!path.eventArgLowerAt(1).equals("destroyed") || !path.eventArgLowerAt(2).equals("by") || !path.eventArgLowerAt(3).equals("explosion")) {
            return false;
        }
        if (!couldMatchBlock(path.eventArgLowerAt(0))) {
            return false;
        }
        return true;
    }

    @Override
    public boolean matches(ScriptPath path) {
        if (!tryMaterial(block.getType(), path.eventArgLowerAt(0))) {
            return false;
        }
        if (!runInCheck(path, block.getLocation())) {
            return false;
        }
        if (path.switches.containsKey("source_entity") && (entityEvent == null || !tryEntity(new EntityTag(entityEvent.getEntity()), path.switches.get("source_entity")))) {
            return false;
        }
        if (path.switches.containsKey("source_block") && (blockEvent == null || !tryMaterial(blockEvent.getBlock().getType(), path.switches.get("source_block")))) {
            return false;
        }
        return super.matches(path);
    }

    @Override
    public String getName() {
        return "BlockDestroyedByExplosion";
    }

    @Override
    public ObjectTag getContext(String name) {
        switch (name) {
            case "block": return new LocationTag(block.getLocation());
            case "source_location": return new LocationTag(blockEvent != null ? blockEvent.getBlock().getLocation() : entityEvent.getLocation());
            case "source_entity": return entityEvent == null ? null : new EntityTag(entityEvent.getEntity());
            case "strength": return new ElementTag(blockEvent != null ? blockEvent.getYield() : entityEvent.getYield());
            default: return super.getContext(name);
        }
    }

    @Override
    public void cancellationChanged() {
        if (cancelled) {
            rawList.remove(block);
        }
    }

    @EventHandler
    public void onBlockExplodes(BlockExplodeEvent event) {
        this.blockEvent = event;
        this.entityEvent = null;
        this.rawList = event.blockList();
        for (Block block : new ArrayList<>(rawList)) {
            this.block = block;
            fire(event);
        }
    }

    @EventHandler
    public void onEntityExplodes(EntityExplodeEvent event) {
        this.entityEvent = event;
        this.blockEvent = null;
        this.rawList = event.blockList();
        for (Block block : new ArrayList<>(rawList)) {
            this.block = block;
            fire(event);
        }
    }
}
