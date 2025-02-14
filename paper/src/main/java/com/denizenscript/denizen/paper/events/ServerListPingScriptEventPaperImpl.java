package com.denizenscript.denizen.paper.events;

import com.denizenscript.denizen.events.server.ListPingScriptEvent;
import com.denizenscript.denizen.objects.PlayerTag;
import com.denizenscript.denizen.paper.PaperModule;
import com.denizenscript.denizencore.objects.ArgumentHelper;
import com.denizenscript.denizencore.objects.ObjectTag;
import com.denizenscript.denizencore.objects.core.ElementTag;
import com.denizenscript.denizencore.objects.core.ListTag;
import com.denizenscript.denizencore.utilities.CoreUtilities;
import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;

import java.util.HashSet;
import java.util.Iterator;
import java.util.UUID;

public class ServerListPingScriptEventPaperImpl extends ListPingScriptEvent {

    @Override
    public boolean applyDetermination(ScriptPath path, ObjectTag determinationObj) {
        String determination = determinationObj.toString();
        String lower = CoreUtilities.toLowerCase(determination);
        if (lower.startsWith("protocol_version:") && ArgumentHelper.matchesInteger(determination.substring("protocol_version:".length()))) {
            ((PaperServerListPingEvent) event).setProtocolVersion(Integer.parseInt(determination.substring("protocol_version:".length())));
            return true;
        }
        else if (lower.startsWith("version_name:")) {
            ((PaperServerListPingEvent) event).setVersion(determination.substring("version_name:".length()));
            return true;
        }
        else if (lower.startsWith("exclude_players:")) {
            HashSet<UUID> exclusions = new HashSet<>();
            for (PlayerTag player : ListTag.valueOf(determination.substring("exclude_players:".length()), getTagContext(path)).filter(PlayerTag.class, getTagContext(path))) {
                exclusions.add(player.getUUID());
            }
            Iterator<Player> players = ((PaperServerListPingEvent) event).iterator();
            while (players.hasNext()) {
                if (exclusions.contains(players.next().getUniqueId())) {
                    players.remove();
                }
            }
            return true;
        }
        return super.applyDetermination(path, determinationObj);
    }

    @Override
    public void setMotd(String text) {
        event.motd(PaperModule.parseFormattedText(text, ChatColor.WHITE));
    }

    @Override
    public ObjectTag getContext(String name) {
        switch (name) {
            case "motd":
                return new ElementTag(PaperModule.stringifyComponent(event.motd(), ChatColor.WHITE));
            case "protocol_version":
                return new ElementTag(((PaperServerListPingEvent) event).getProtocolVersion());
            case "version_name":
                return new ElementTag(((PaperServerListPingEvent) event).getVersion());
            case "client_protocol_version":
                return new ElementTag(((PaperServerListPingEvent) event).getClient().getProtocolVersion());
        }
        return super.getContext(name);
    }

    @EventHandler
    public void onListPing(PaperServerListPingEvent event) {
        syncFire(event);
    }
}
