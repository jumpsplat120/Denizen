package com.denizenscript.denizen.events.player;

import com.denizenscript.denizen.events.BukkitScriptEvent;
import com.denizenscript.denizen.objects.PlayerTag;
import com.denizenscript.denizen.utilities.implementation.BukkitScriptEntryData;
import com.denizenscript.denizen.utilities.packets.NetworkInterceptHelper;
import com.denizenscript.denizencore.objects.ObjectTag;
import com.denizenscript.denizencore.objects.core.ElementTag;
import com.denizenscript.denizencore.scripts.ScriptEntryData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashSet;
import java.util.UUID;

public class PlayerHoldsItemEvent extends BukkitScriptEvent implements Listener {

    // <--[event]
    // @Events
    // player raises <item>
    // player lowers <item>
    // player toggles <item>
    // player (raises/lowers/toggles) <item>
    //
    // @Regex ^on player (toggles|raises|lowers) [^\s]+$
    //
    // @Synonyms player raises shield, player raises spyglass
    //
    // @Group Player
    //
    // @Location true
    //
    // @Triggers when a player starts or stops holding up an item, such as a shield, spyglass, or crossbow.
    //
    // @Context
    // <context.state> returns an ElementTag(Boolean) with a value of "true" if the player is now holding a shield and "false" otherwise.
    //
    // @Player Always.
    //
    // -->

    public PlayerHoldsItemEvent() {
        instance = this;
    }

    public static PlayerHoldsItemEvent instance;
    public PlayerTag player;
    public boolean state;

    @Override
    public boolean couldMatch(ScriptPath path) {
        if (!path.eventArgLowerAt(0).equals("player")) {
            return false;
        }
        String middleWord = path.eventArgAt(1);
        if (!(middleWord.equals("raises") || middleWord.equals("lowers") || middleWord.equals("toggles"))) {
            return false;
        }
        if (!path.eventArgLowerAt(2).equals("item") && !couldMatchItem(path.eventArgLowerAt(2))) {
            return false;
        }
        return true;
    }

    @Override
    public boolean matches(ScriptPath path) {
        String cmd = path.eventArgLowerAt(1);
        if (cmd.equals("raises") && !state) {
            return false;
        }
        if (cmd.equals("lowers") && state) {
            return false;
        }
        if (!runInCheck(path, player.getLocation())) {
            return false;
        }
        String item = path.eventArgLowerAt(2);
        if (!item.equals("item") && !tryItem(player.getHeldItem(), item)) {
            return false;
        }
        return super.matches(path);
    }

    @Override
    public String getName() {
        return "PlayerHoldsItem";
    }

    public boolean enabled = false;

    @Override
    public ScriptEntryData getScriptEntryData() {
        return new BukkitScriptEntryData(player, null);
    }

    @Override
    public ObjectTag getContext(String name) {
        if (name.equals("state")) {
            return new ElementTag(state);
        }
        return super.getContext(name);
    }

    public static HashSet<UUID> raisedShields = new HashSet<>();

    @Override
    public void init() {
        NetworkInterceptHelper.enable();
        enabled = true;
    }

    @Override
    public void destroy() {
        enabled = false;
    }

    public static void signalDidRaise(Player player) {
        if (raisedShields.contains(player.getUniqueId())) {
            return;
        }
        raisedShields.add(player.getUniqueId());
        instance.state = true;
        instance.player = new PlayerTag(player);
        instance.cancelled = false;
        instance.fire();
    }

    public static void signalDidLower(Player player) {
        if (!raisedShields.remove(player.getUniqueId())) {
            return;
        }
        instance.state = false;
        instance.player = new PlayerTag(player);
        instance.cancelled = false;
        instance.fire();
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        signalDidLower(event.getPlayer());
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        signalDidLower(event.getEntity());
    }

    @EventHandler
    public void onPlayerChangeItem(PlayerItemHeldEvent event) {
        signalDidLower(event.getPlayer());
    }

    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        signalDidLower(event.getPlayer());
    }
}
