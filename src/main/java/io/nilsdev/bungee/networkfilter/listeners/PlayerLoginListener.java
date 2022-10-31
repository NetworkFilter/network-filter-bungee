/*
 * MIT License
 *
 * Copyright (c) 2019 Nils Müller
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.nilsdev.bungee.networkfilter.listeners;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.nilsdev.bungee.networkfilter.NetworkFilter;
import io.nilsdev.bungee.networkfilter.json.ApiResponse;
import kong.unirest.HttpResponse;
import kong.unirest.JsonNode;
import kong.unirest.Unirest;
import kong.unirest.json.JSONObject;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.event.PreLoginEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;

import java.net.InetAddress;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@SuppressWarnings("unused")
public class PlayerLoginListener implements Listener {

    private final NetworkFilter plugin;
    private final Gson gson = new Gson();

    private Cache<String, ApiResponse> cache = CacheBuilder.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(15, TimeUnit.MINUTES)
            .build();

    public PlayerLoginListener(NetworkFilter plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onEvent(PostLoginEvent event) {
        long eventStart = System.currentTimeMillis();

        this.plugin.getProxy().getScheduler().runAsync(this.plugin, () -> {
            long start = System.currentTimeMillis();

            String address = event.getPlayer().getAddress().getAddress().getHostAddress();

            if (event.getPlayer().hasPermission("nabapi.allowvpn") || event.getPlayer().hasPermission("networkfilter.ignore")) {
                this.plugin.getLogger().info("[" + event.getPlayer().getName() + "|" + address + "] Querying " + address + " skip because authorized");
                return;
            }

            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("ip", address);
            requestBody.addProperty("apiKey", this.plugin.config.getString("apiKey"));

            ApiResponse cachedApiResponse = cache.getIfPresent(address);

            if (cachedApiResponse != null) {

                if (cachedApiResponse.isBlocked()) {
                    event.getPlayer().disconnect(TextComponent.fromLegacyText("§3§lNetworkFilter §8§l» §7Fehler beim Verbinden. Melde dich im Forum oder Support mit der Id §e" + cachedApiResponse.getAsn() + " (" + cachedApiResponse.getOrg() + ")"));
                }

                long elapsedTime = System.currentTimeMillis() - start;
                this.plugin.getLogger().info("[" + event.getPlayer().getName() + "|" + address + "] Querying " + address + " took " + elapsedTime + "ms returns CACHED -> " + cachedApiResponse.toString());

            } else {

                HttpResponse<JsonNode> response = Unirest.post("https://nf.ni.ls/api/check")
                        .header("Content-Type", "application/json")
                        .body(this.gson.toJson(requestBody))
                        .asJson();

                if (!response.isSuccess()) {
                    long elapsedTime = System.currentTimeMillis() - start;
                    this.plugin.getLogger().warning("[" + event.getPlayer().getName() + "|" + address + "] Querying " + address + " took " + elapsedTime + "ms returns http success false -> " + this.gson.toJson(response.getBody()));
                    return;
                }

                JSONObject body = response.getBody().getObject();

                if (!body.optBoolean("success", false)) {
                    long elapsedTime = System.currentTimeMillis() - start;
                    this.plugin.getLogger().warning("[" + event.getPlayer().getName() + "|" + address + "] Querying " + address + " took " + elapsedTime + "ms returns json success false -> " + this.gson.toJson(body));
                    return;
                }

                JSONObject data = body.getJSONObject("data");

                ApiResponse apiResponse = gson.fromJson(data.toString(), ApiResponse.class);

                this.cache.put(address, apiResponse);

                if (apiResponse.isBlocked()) {
                    event.getPlayer().disconnect(TextComponent.fromLegacyText("§3§lNetworkFilter §8§l» §7Fehler beim Verbinden. Melde dich im Forum oder Support mit der Id §e" + apiResponse.getAsn() + " (" + apiResponse.getOrg() + ")"));
                }

                long elapsedTime = System.currentTimeMillis() - start;
                this.plugin.getLogger().info("[" + event.getPlayer().getName() + "|" + address + "] Querying " + address + " took " + elapsedTime + "ms returns LIVE -> " + apiResponse.toString());
            }
        });
        long eventElapsedTime = System.currentTimeMillis() - eventStart;
        this.plugin.getLogger().warning("[" + event.getPlayer().getName() + "] took " + eventElapsedTime + "ms");
    }
}
