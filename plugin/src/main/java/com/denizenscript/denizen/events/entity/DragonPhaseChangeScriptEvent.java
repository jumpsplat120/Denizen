package com.denizenscript.denizen.events.entity;

import com.denizenscript.denizen.objects.EntityTag;
import com.denizenscript.denizen.events.BukkitScriptEvent;
import com.denizenscript.denizencore.objects.core.ElementTag;
import com.denizenscript.denizencore.objects.ObjectTag;
import org.bukkit.entity.EnderDragon;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EnderDragonChangePhaseEvent;

public class DragonPhaseChangeScriptEvent extends BukkitScriptEvent implements Listener {

    // <--[event]
    // @Events
    // dragon changes phase
    // <entity> changes phase
    //
    // @Regex ^on [^\s]+ changes phase$
    //
    // @Group Entity
    //
    // @Location true
    // @Switch from:<phase> to only process the event if the dragon was previously in the specified phase.
    // @Switch to:<phase> to only process the event if the dragon is changing to the specified phase.
    //
    // @Cancellable true
    //
    // @Triggers when a dragon's combat phase changes.
    //
    // @Context
    // <context.entity> returns the EntityTag of the dragon.
    // <context.new_phase> returns an ElementTag of the dragon's new phase. Phases: <@link url https://hub.spigotmc.org/javadocs/spigot/org/bukkit/event/entity/EnderDragonChangePhaseEvent.html>
    // <context.old_phase> returns an ElementTag of the dragon's old phase. Can be any phase or 'null' in some cases.
    //
    // @Determine
    // ElementTag to change the dragon's new phase.
    //
    // -->

    public DragonPhaseChangeScriptEvent() {
        instance = this;
    }

    public static DragonPhaseChangeScriptEvent instance;
    public EntityTag entity;
    public EnderDragonChangePhaseEvent event;

    @Override
    public boolean couldMatch(ScriptPath path) {
        if (!path.eventLower.contains("changes phase")) {
            return false;
        }
        if (!couldMatchEntity(path.eventArgLowerAt(0))) {
            return false;
        }
        return true;
    }

    @Override
    public boolean matches(ScriptPath path) {
        String target = path.eventArgLowerAt(0);

        if (!tryEntity(entity, target)) {
            return false;
        }

        if (!runInCheck(path, entity.getLocation())) {
            return false;
        }

        if (!runGenericSwitchCheck(path, "from", event.getCurrentPhase() == null ? "null" : event.getCurrentPhase().name())) {
            return false;
        }

        if (!runGenericSwitchCheck(path, "to", event.getNewPhase().name())) {
            return false;
        }

        return super.matches(path);
    }

    @Override
    public String getName() {
        return "DragonPhaseChanged";
    }

    @Override
    public boolean applyDetermination(ScriptPath path, ObjectTag determinationObj) {
        if (exactMatchesEnum(determinationObj.toString(), EnderDragon.Phase.values())) {
            EnderDragon.Phase phase = EnderDragon.Phase.valueOf(determinationObj.toString().toUpperCase());
            event.setNewPhase(phase);
            return true;
        }
        return super.applyDetermination(path, determinationObj);
    }

    @Override
    public ObjectTag getContext(String name) {
        switch (name) {
            case "entity":
                return entity.getDenizenObject();
            case "old_phase":
                return new ElementTag(event.getCurrentPhase() == null ? "null" : event.getCurrentPhase().name());
            case "new_phase":
                return new ElementTag(event.getNewPhase().name());
        }
        return super.getContext(name);
    }

    @EventHandler
    public void onEnderDragonChangePhase(EnderDragonChangePhaseEvent event) {
        entity = new EntityTag(event.getEntity());
        this.event = event;
        fire(event);
    }
}
