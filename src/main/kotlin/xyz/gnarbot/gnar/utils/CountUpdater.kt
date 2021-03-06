package xyz.gnarbot.gnar.utils

import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.sharding.ShardManager
import okhttp3.*
import org.json.JSONObject
import xyz.gnarbot.gnar.Bot
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class CountUpdater(private val bot: Bot, shardManager: ShardManager) {
    val client: OkHttpClient = OkHttpClient.Builder().build()
    val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    var index = 0

    init {
        executor.scheduleAtFixedRate({
            for (shard in shardManager.shards) {
                update(shard); index++
            }
        }, 10L + index * 5, 30, TimeUnit.MINUTES)
    }

    private fun update(jda: JDA) {
        if (jda.status != JDA.Status.CONNECTED)
            return

        Bot.getLogger().info("Sending shard updates for shard ${jda.shardInfo.shardId}")
        updateCarbonitex(jda)
        updateGuildCount(jda)
    }

    private fun createCallback(name: String, jda: JDA): Callback {
        return object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Bot.getLogger().error("$name update failed for shard ${jda.shardInfo.shardId}: ${e.message}")
                call.cancel()
            }

            override fun onResponse(call: Call, response: Response) {
                response.close()
            }
        }
    }

    private fun updateGuildCount(jda: JDA) {
        val auth = bot.credentials.discordBots ?: return

        val json = JSONObject()
                .put("shards", jda.guildCache.size())
                .put("shard_id", jda.shardInfo.shardId)
                .put("shard_count", bot.credentials.totalShards)

        val request = Request.Builder()
                .url("https://top.gg/api/bots/138481382794985472/stats")
                .header("User-Agent", "Octave")
                .header("Authorization", auth)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .post(RequestBody.create(HttpUtils.JSON, json.toString()))
                .build()

        client.newCall(request).enqueue(createCallback("top.gg", jda))
    }

    private fun updateCarbonitex(jda: JDA) {
        val authCarbon = bot.credentials.carbonitex ?: return

        val json = JSONObject()
                .put("key", authCarbon)
                .put("shardid", jda.shardInfo.shardId)
                .put("shardcount", bot.credentials.totalShards)
                .put("servercount", jda.guildCache.size())

        val request = Request.Builder()
                .url("https://www.carbonitex.net/discord/data/botdata.php")
                .header("User-Agent", "Octave")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .post(RequestBody.create(HttpUtils.JSON, json.toString()))
                .build()

        client.newCall(request).enqueue(createCallback("carbonitex.net", jda))
    }
}