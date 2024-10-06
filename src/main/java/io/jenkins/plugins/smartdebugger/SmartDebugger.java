package io.jenkins.plugins.smartdebugger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import java.io.IOException;
import java.io.PrintStream;
import java.util.List;
import jenkins.tasks.SimpleBuildStep;
import okhttp3.*;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

@Extension
public class SmartDebugger extends Recorder implements SimpleBuildStep {

    private Secret apiToken;
    private String selectedModel;

    @DataBoundConstructor
    public SmartDebugger(Secret apiToken, String selectedModel) {
        this.apiToken = apiToken;
        this.selectedModel = selectedModel;
    }

    public String getApiToken() {
        return apiToken != null ? apiToken.getPlainText() : "";
    }

    @DataBoundSetter
    public void setApiToken(Secret apiToken) {
        this.apiToken = apiToken;
    }

    public String getSelectedModel() {
        return selectedModel;
    }

    @DataBoundSetter
    public void setSelectedModel(String selectedModel) {
        this.selectedModel = selectedModel;
    }

    public SmartDebugger() {
        // No-argument constructor
    }

    @Override
    public void perform(Run<?, ?> run, FilePath workspace, Launcher launcher, TaskListener listener)
            throws InterruptedException, IOException {
        PrintStream logger = listener.getLogger();

        // Capture build logs
        String buildLogs = getBuildLogs(run);

        // Call LLM API
        String debuggingSuggestions = getDebuggingSuggestions(buildLogs);

        // Display results
        logger.println("\n--- Smart Debugger Analysis ---");
        logger.println("Key issues and suggestions:");
        logger.println(debuggingSuggestions);
        logger.println("--- End of Smart Debugger Analysis ---\n");
    }

    private String getBuildLogs(Run<?, ?> run) throws IOException {
        List<String> logLines = run.getLog(Integer.MAX_VALUE);
        return String.join("\n", logLines);
    }


    private String getDebuggingSuggestions(String buildLogs) {
        // Check if API token is null or empty
        if (apiToken == null || apiToken.getPlainText().isEmpty()) {
            return "Error: API token not found. Please configure the API token in the job settings.";
        }
    
        OkHttpClient client = new OkHttpClient();
        ObjectMapper mapper = new ObjectMapper();
    
        MediaType JSON = MediaType.get("application/json; charset=utf-8");
        String truncatedLogs = buildLogs.length() > 4000 ? buildLogs.substring(0, 4000) : buildLogs;
        String jsonBody = String.format(
                "{\"messages\": [{\"role\": \"user\", \"content\": \"Analyze these Jenkins build logs and provide debugging suggestions: %s. Format your response as a numbered list of short, actionable points, focusing on the most critical issues.\"}], \"model\": \"%s\"}",
                truncatedLogs.replace("\"", "\\\"").replace("\n", "\\n"), selectedModel);
    
        RequestBody body = RequestBody.create(jsonBody, JSON);
        Request request = new Request.Builder()
                .url("https://api.groq.com/openai/v1/chat/completions")
                .addHeader("Authorization", "Bearer " + apiToken.getPlainText())
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build();
    
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return "Unexpected response code: " + response.code() + ". Error: " + (response.body() != null ? response.body().string() : "No error body");
            }
    
            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                return "Error: Received null response body from API.";
            }
    
            String responseBodyString = responseBody.string();
            if (responseBodyString == null || responseBodyString.isEmpty()) {
                return "Error: Received empty response body from API.";
            }
    
            JsonNode jsonNode = mapper.readTree(responseBodyString);
            if (jsonNode == null) {
                return "Error: Failed to parse JSON response.";
            }
    
            JsonNode choicesNode = jsonNode.path("choices");
            if (!choicesNode.isArray() || choicesNode.size() == 0) {
                return "Error: Unexpected response format from API. No choices found.";
            }
    
            JsonNode firstChoice = choicesNode.get(0);
            if (firstChoice == null) {
                return "Error: First choice in API response is null.";
            }
    
            JsonNode messageNode = firstChoice.path("message");
            if (messageNode.isMissingNode()) {
                return "Error: 'message' node is missing in API response.";
            }
    
            JsonNode contentNode = messageNode.path("content");
            if (contentNode.isMissingNode()) {
                return "Error: 'content' node is missing in API response.";
            }
    
            String content = contentNode.asText();
            return content != null ? content : "Error: Content is null.";
        } catch (IOException e) {
            e.printStackTrace();
            return "Error fetching debugging suggestions: " + e.getMessage();
        }
    }
    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {
        @Override
        public String getDisplayName() {
            return "Smart Debugger";
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        public ListBoxModel doFillSelectedModelItems() {
            ListBoxModel items = new ListBoxModel();
            items.add("LLaMA 3 8B", "llama3-8b-8192");
            items.add("LLaMA 3 70B", "llama3-70b-8192");
            items.add("Mixtral 8x7B", "mixtral-8x7b-32768");
            items.add("Gemma 7B", "gemma-7b-it");
            return items;
        }
    }
}