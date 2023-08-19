package com.envyful.api.discord.yaml;

import com.envyful.api.config.yaml.AbstractYamlConfig;
import com.envyful.api.discord.JSONObject;
import com.google.common.collect.Maps;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;

import javax.net.ssl.HttpsURLConnection;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@ConfigSerializable
public class DiscordWebHookConfig extends AbstractYamlConfig {

    private boolean enabled;
    private String url;
    private String content;
    private String username;
    private String avatarUrl;
    private boolean tts;
    private Map<String, DiscordEmbedConfig> embeds;

    public DiscordWebHookConfig(Builder builder) {
        this.enabled = builder.enabled;
        this.url = builder.url;
        this.content = builder.content;
        this.username = builder.username;
        this.avatarUrl = builder.avatarUrl;
        this.tts = builder.tts;
        this.embeds = Maps.newHashMap();

        for (DiscordEmbedConfig embed : builder.embeds) {
            this.embeds.put("example-" + this.embeds.size(), embed);
        }
    }

    /**
     *
     * Executes the message and sends it to the web hook URL
     *
     * @throws IOException Exception when something is incorrect or goes wrong
     */
    public void execute() throws IOException {
        if (!this.enabled) {
            return;
        }

        if (this.content == null && this.embeds.isEmpty()) {
            throw new IllegalArgumentException(
                    "Set content or add at least one EmbedObject"
            );
        }

        JSONObject json = this.toJson();

        URL url = new URL(this.url);
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        connection.addRequestProperty("Content-Type", "application/json");
        connection.addRequestProperty("User-Agent", "Java-DiscordWebhook-BY-Gelox_");
        connection.setDoOutput(true);
        connection.setRequestMethod("POST");

        OutputStream stream = connection.getOutputStream();
        stream.write(json.toString().getBytes(StandardCharsets.UTF_8));
        stream.flush();
        stream.close();

        connection.getInputStream().close();
        connection.disconnect();
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();

        json.put("content", this.content);
        json.put("username", this.username);
        json.put("avatar_url", this.avatarUrl);
        json.put("tts", this.tts);

        if (!this.embeds.isEmpty()) {
            List<JSONObject> embedObjects = new ArrayList<>();

            for (DiscordEmbedConfig embed : this.embeds.values()) {
                embedObjects.add(embed.toJson());
            }

            json.put("embeds", embedObjects.toArray());
        }

        return json;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        private boolean enabled = true;
        private String url;
        private String content;
        private String username;
        private String avatarUrl;
        private boolean tts;
        private List<DiscordEmbedConfig> embeds = new ArrayList<>();

        Builder() {}

        public Builder enabled() {
            this.enabled = true;
            return this;
        }

        public Builder disabled() {
            this.enabled = false;
            return this;
        }

        public Builder url(String url) {
            this.url = url;
            return this;
        }

        public Builder content(String content) {
            this.content = content;
            return this;
        }

        public Builder username(String username) {
            this.username = username;
            return this;
        }

        public Builder avatarUrl(String avatarUrl) {
            this.avatarUrl = avatarUrl;
            return this;
        }

        public Builder tts(boolean tts) {
            this.tts = tts;
            return this;
        }

        public Builder embeds(DiscordEmbedConfig... embeds) {
            this.embeds.addAll(Arrays.asList(embeds));
            return this;
        }

        public DiscordWebHookConfig build() {
            return new DiscordWebHookConfig(this);
        }
    }
}
