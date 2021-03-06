package xyz.gnarbot.gnar.music;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.bandcamp.BandcampAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.beam.BeamAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.twitch.TwitchStreamAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.vimeo.VimeoAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.youtube.YoutubeAudioSourceManager;
import com.sedmelluq.lava.extensions.youtuberotator.YoutubeIpRotatorSetup;
import com.sedmelluq.lava.extensions.youtuberotator.planner.AbstractRoutePlanner;
import com.sedmelluq.lava.extensions.youtuberotator.planner.RotatingNanoIpRoutePlanner;
import com.sedmelluq.lava.extensions.youtuberotator.tools.ip.IpBlock;
import com.sedmelluq.lava.extensions.youtuberotator.tools.ip.Ipv6Block;
import net.dv8tion.jda.api.entities.Guild;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.gnarbot.gnar.Bot;
import xyz.gnarbot.gnar.Configuration;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.net.InetAddress;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class PlayerRegistry {
    private final Logger LOG = LoggerFactory.getLogger("PlayerRegistry");

    private final Map<Long, MusicManager> registry;

    private final Bot bot;
    private final ScheduledExecutorService executor;
    private final AudioPlayerManager playerManager;

    public PlayerRegistry(Bot bot, ScheduledExecutorService executor) {
        this.bot = bot;
        this.executor = executor;

        registry = new ConcurrentHashMap<>(bot.getConfiguration().getMusicLimit());
        executor.scheduleAtFixedRate(() -> clear(false), 20, 10, TimeUnit.MINUTES);

        this.playerManager = new DefaultAudioPlayerManager();
        YoutubeAudioSourceManager youtubeAudioSourceManager = new YoutubeAudioSourceManager(true);

        Configuration config = bot.getConfiguration();
        if (!config.getIpv6Block().isEmpty()) {
            AbstractRoutePlanner planner;
            String block = config.getIpv6Block();
            List<IpBlock> blocks = Collections.singletonList(new Ipv6Block(block));

            if (config.getIpv6Exclude().isEmpty())
                planner = new RotatingNanoIpRoutePlanner(blocks);
            else {
                try {
                    InetAddress blacklistedGW = InetAddress.getByName(config.getIpv6Exclude());
                    planner = new RotatingNanoIpRoutePlanner(blocks, inetAddress -> !inetAddress.equals(blacklistedGW));
                } catch (Exception ex) {
                    planner = new RotatingNanoIpRoutePlanner(blocks);
                    ex.printStackTrace();
                }
            }

            new YoutubeIpRotatorSetup(planner)
                    .forSource(youtubeAudioSourceManager)
                    .setup();
        }

        playerManager.registerSourceManager(youtubeAudioSourceManager);
        playerManager.registerSourceManager(SoundCloudAudioSourceManager.createDefault());
        playerManager.registerSourceManager(new BandcampAudioSourceManager());
        playerManager.registerSourceManager(new VimeoAudioSourceManager());
        playerManager.registerSourceManager(new TwitchStreamAudioSourceManager());
        playerManager.registerSourceManager(new BeamAudioSourceManager());
    }

    public ScheduledExecutorService getExecutor() {
        return executor;
    }

    @Nonnull
    public MusicManager get(Guild guild) throws MusicLimitException {
        MusicManager manager = registry.get(guild.getIdLong());

        if (manager == null) {
            if (size() >= bot.getConfiguration().getMusicLimit() && !bot.getOptions().ofGuild(guild).isPremium()) {
                throw new MusicLimitException();
            }

            manager = new MusicManager(bot, guild, this, playerManager);
            registry.put(guild.getIdLong(), manager);
        }

        return manager;
    }

    @Nullable
    public MusicManager getExisting(long id) {
        return registry.get(id);
    }

    @Nullable
    public MusicManager getExisting(Guild guild) {
        return getExisting(guild.getIdLong());
    }

    public void destroy(long id) {
        MusicManager manager = registry.get(id);
        if (manager != null) {
            manager.destroy();
            registry.remove(id);
        }
    }

    public void destroy(Guild guild) {
        destroy(guild.getIdLong());
    }

    public boolean contains(long id) {
        return registry.containsKey(id);
    }

    public boolean contains(Guild guild) {
        return registry.containsKey(guild.getIdLong());
    }

    public void shutdown() {
        clear(true);
    }

    public void clear(boolean force) {
        LOG.info("Cleaning up players (forceful: " + force + ")");
        Iterator<Map.Entry<Long, MusicManager>> iterator = registry.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, MusicManager> entry = iterator.next();
            try {
                if (entry.getValue() == null) {
                    iterator.remove();
                    LOG.warn("Null manager for id " + entry.getKey());
                } else if (force
                        || !entry.getValue().getGuild().getSelfMember().getVoiceState().inVoiceChannel()
                        || entry.getValue().getPlayer().getPlayingTrack() == null) {
                    entry.getValue().destroy();
                    iterator.remove();
                }
            } catch (Exception e) {
                LOG.warn("Exception occured while trying to clean up id " + entry.getKey(), e);
            }
        }

        LOG.info("Finished cleaning up players.");
    }

    public Map<Long, MusicManager> getRegistry() {
        return registry;
    }

    public int size() {
        return registry.size();
    }
}
