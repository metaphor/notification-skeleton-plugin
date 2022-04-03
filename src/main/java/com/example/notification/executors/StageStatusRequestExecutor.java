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
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;

public class StageStatusRequestExecutor implements RequestExecutor {
    private static final Logger LOG = Logger.getLoggerFor(StageStatusRequestExecutor.class);
    private static final Gson GSON = new GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create();

    private static final Set<String> FAILED_PIPELINES = new ConcurrentSkipListSet<>();
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

        if (isPassed()) {
            if (tryRecover()) {
                LOG.info("pipeline {} recovered", request.pipeline.name);
                doSendToDingTalk(apiUrl, apiUser, goServerUrl, "Nice! Someone has fixed the pipeline!", "https://icon-library.com/images/success-icon/success-icon-11.jpg");
            }
            return;
        }

        if (notFailedPipeline()) {
            return;
        }
        FAILED_PIPELINES.add(pipelineStageName());
        doSendToDingTalk(apiUrl, apiUser, goServerUrl, "Fix the pipeline please!", "https://cdn0.iconfinder.com/data/icons/coding-and-programming-1/32/fail_error_problem_crash_round_shape-512.png");
    }

    private String pipelineStageName() {
        return String.format("%s|%s", request.pipeline.name, request.pipeline.stage.name);
    }

    private boolean isPassed() {
        return request.pipeline.stage.state.toLowerCase().contains("passed");
    }

    private boolean tryRecover() {
        return FAILED_PIPELINES.remove(pipelineStageName());
    }

    private boolean notFailedPipeline() {
        return !request.pipeline.stage.state.toLowerCase().contains("failed");
    }

    private void doSendToDingTalk(String apiUrl, String apiUser, String goServerUrl, String text, String picUrl) {
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
                            "        \"picUrl\": \"%s\",\n" +
                            "        \"messageUrl\": \"%s\"\n" +
                            "    }\n" +
                            "}",
                    apiUser, text, title, picUrl, messageUrl
            );
            request.setEntity(HttpEntities.create(requestBody, StandardCharsets.UTF_8));
            CloseableHttpResponse response = client.execute(request);
            EntityUtils.consume(response.getEntity());
        } catch (IOException e) {
            LOG.error("failed", e);
        }
    }
}
