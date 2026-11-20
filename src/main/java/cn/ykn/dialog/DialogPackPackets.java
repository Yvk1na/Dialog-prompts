package cn.ykn.dialog;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientResourcePackStatus;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.Plugin;

final class DialogPackPackets extends PacketListenerAbstract implements Runnable {
    private final Plugin plugin;
    private final DialogResourcePack pack;

    DialogPackPackets(Plugin plugin, DialogResourcePack pack) {
        super(PacketListenerPriority.LOWEST);
        this.plugin = plugin;
        this.pack = pack;
        PacketEvents.getAPI().getEventManager().registerListener(this);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        // CraftEngine consumes terminal PLAY responses for packs it sent, before Bukkit can see them.
        if (!plugin.isEnabled() || event.getPacketType() != PacketType.Play.Client.RESOURCE_PACK_STATUS
                || !(event.getPlayer() instanceof Player player)) return;
        var response = new WrapperPlayClientResourcePackStatus(event);
        var packId = response.getPackId();
        var status = PlayerResourcePackStatusEvent.Status.valueOf(response.getResult().name());
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) pack.status(player.getUniqueId(), packId, status);
        });
    }

    @Override
    public void run() {
        PacketEvents.getAPI().getEventManager().unregisterListener(this);
    }
}

