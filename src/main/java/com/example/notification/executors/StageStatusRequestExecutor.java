/*
 * Copyright 2018 ThoughtWorks, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.notification.executors;

import com.example.notification.PluginRequest;
import com.example.notification.PluginSettings;
import com.example.notification.RequestExecutor;
import com.example.notification.requests.StageStatusRequest;
import com.google.common.base.Strings;
import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.thoughtworks.go.plugin.api.logging.Logger;
import com.thoughtworks.go.plugin.api.response.DefaultGoPluginApiResponse;
import com.thoughtworks.go.plugin.api.response.GoPluginApiResponse;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.HttpEntities;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;

public class StageStatusRequestExecutor implements RequestExecutor {
    public static final Logger LOG = Logger.getLoggerFor(StageStatusRequestExecutor.class);
    private static final Gson GSON = new GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create();

    private final StageStatusRequest request;
    private final PluginRequest pluginRequest;

    public StageStatusRequestExecutor(StageStatusRequest request, PluginRequest pluginRequest) {
        this.request = request;
        this.pluginRequest = pluginRequest;
    }

    @Override
    public GoPluginApiResponse execute() throws Exception {
        HashMap<String, Object> responseJson = new HashMap<>();
        try {
            sendNotification();
            responseJson.put("status", "success");
        } catch (Exception e) {
            responseJson.put("status", "failure");
            responseJson.put("messages", Arrays.asList(e.getMessage()));
        }
        return new DefaultGoPluginApiResponse(200, GSON.toJson(responseJson));
    }

    protected void sendNotification() throws Exception {
        PluginSettings pluginSettings = pluginRequest.getPluginSettings();

        String apiUrl = pluginSettings.getApiUrl();
        String apiUser = pluginSettings.getApiUser();
        String goServerUrl = pluginSettings.getGoServerUrl();
        if (Strings.isNullOrEmpty(goServerUrl) || Strings.isNullOrEmpty(apiUrl) || Strings.isNullOrEmpty(apiUser)) {
            LOG.info("apiUrl: {}, apiUser: {}, goServerUrl: {}", apiUrl, apiUser, goServerUrl);
            return;
        }

        if (!request.pipeline.stage.state.toLowerCase().contains("failed")) {
            return;
        }

        String text = "Go to fix it please!";
        String title = String.format("Pipeline %s stage %s %s", request.pipeline.name, request.pipeline.stage.name, request.pipeline.stage.state);
        String messageUrl = String.format("%s/go/pipelines/%s/%s/%s/%s", goServerUrl, request.pipeline.name, request.pipeline.counter, request.pipeline.stage.name,  request.pipeline.stage.counter);
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpPost request = new HttpPost(apiUrl);
            request.addHeader("Content-Type", "application/json");

            String requestBody = String.format("{\n" +
                            "    \"msgtype\": \"link\",\n" +
                            "    \"link\": {\n" +
                            "        \"text\": \"%s - %s\",\n" +
                            "        \"title\": \"%s\",\n" +
                            "        \"picUrl\": \"\",\n" +
                            "        \"messageUrl\": \"%s\"\n" +
                            "    }\n" +
                            "}",
                    apiUser, text, title, messageUrl
            );
            request.setEntity(HttpEntities.create(requestBody, StandardCharsets.UTF_8));
            CloseableHttpResponse response = client.execute(request);
            EntityUtils.consume(response.getEntity());
        } catch (IOException e) {
            LOG.error("failed", e);
        }
    }
}
