package ai.dsa.analysis.service;

import ai.dsa.analysis.config.PerplexityProperties;
import ai.dsa.analysis.dto.UserCodingData;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class PerplexityService {

    private final WebClient webClient;
    private final PerplexityProperties properties;
    /**
     * Quick health check for Perplexity API
     */
    public boolean isApiHealthy() {
        try {
            // Quick test to verify API key and connectivity
            if (properties.getKey() == null ||
                    properties.getKey().equals("not-configured") ||
                    properties.getKey().trim().isEmpty()) {
                log.debug("API key not configured");
                return false;
            }

            String testResponse = callPerplexityAPI("Hello, respond with 'OK'")
                    .timeout(Duration.ofSeconds(5))
                    .block();

            boolean healthy = testResponse != null && !testResponse.trim().isEmpty();
            log.debug("Perplexity API health check: {}", healthy ? "PASS" : "FAIL");
            return healthy;

        } catch (Exception e) {
            log.debug("Perplexity API health check failed: {}", e.getMessage());
            return false;
        }
    }


    // ✅ Simple constructor with single parameter
    public PerplexityService(PerplexityProperties properties) {
        this.properties = properties;
        this.webClient = WebClient.builder().build();

        log.info("🤖 Perplexity service initialized with model: {}",
                properties != null ? properties.getModel() : "null");
    }

    public String analyzeUserCodingPatterns(UserCodingData userData) {
        log.info("🔍 Starting AI analysis for user: {}", userData.getUserId());

        // ✅ Check configuration properly
        if (properties.getKey() == null || properties.getKey().equals("not-configured") || properties.getKey().trim().isEmpty()) {
            log.warn("⚠️ Perplexity API key not configured, using fallback");
            return generateFallbackAnalysis(userData);
        }

        try {
            String prompt = buildDetailedAnalysisPrompt(userData);
            log.debug("📝 Generated prompt for AI: {}", prompt.substring(0, Math.min(200, prompt.length())) + "...");

            String response = callPerplexityAPI(prompt)
                    .timeout(Duration.ofSeconds(properties.getTimeout() / 1000))
                    .block();

            if (response != null && !response.trim().isEmpty()) {
                log.info("✅ AI analysis successful for user: {}", userData.getUserId());
                return response;
            } else {
                log.warn("⚠️ Empty AI response, using fallback");
                return generateFallbackAnalysis(userData);
            }

        } catch (Exception e) {
            log.error("❌ AI analysis failed for user {}: {}", userData.getUserId(), e.getMessage(), e);
            return generateFallbackAnalysis(userData);
        }
    }

    private Mono<String> callPerplexityAPI(String prompt) {
        Map<String, Object> requestBody = Map.of(
                "model", properties.getModel(),  // ✅ Use injected model
                "messages", List.of(
                        Map.of("role", "system", "content", "You are an expert coding mentor and technical interviewer. Provide detailed, actionable insights about coding patterns and improvement recommendations."),
                        Map.of("role", "user", "content", prompt)
                ),
                "max_tokens", properties.getMaxTokens(),  // ✅ Use injected config
                "temperature", properties.getTemperature()
        );

        log.debug("🚀 Calling Perplexity API at: {}", properties.getBaseUrl());

        return webClient.post()
                .uri(properties.getBaseUrl() + "/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getKey())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .bodyValue(requestBody)
                .retrieve()
                .onStatus(status -> status.is4xxClientError(),
                        response -> {
                            log.error("🚫 API Client Error: {}", response.statusCode());
                            return Mono.error(new RuntimeException("API authentication failed - check your API key"));
                        })
                .onStatus(status -> status.is5xxServerError(),
                        response -> {
                            log.error("🔥 API Server Error: {}", response.statusCode());
                            return Mono.error(new RuntimeException("Perplexity API server error"));
                        })
                .bodyToMono(JsonNode.class)
                .map(this::extractResponseContent)
                .retryWhen(Retry.backoff(2, Duration.ofSeconds(1))
                        .filter(throwable -> throwable instanceof WebClientResponseException.TooManyRequests))
                .doOnError(error -> log.error("🚨 API call failed: {}", error.getMessage()));
    }

    private String extractResponseContent(JsonNode response) {
        try {
            log.debug("📋 Parsing AI response");
            return response.path("choices")
                    .path(0)
                    .path("message")
                    .path("content")
                    .asText();
        } catch (Exception e) {
            log.error("❌ Failed to parse AI response: {}", e.getMessage());
            throw new RuntimeException("Failed to parse AI response", e);
        }
    }

    private String buildDetailedAnalysisPrompt(UserCodingData userData) {
        return String.format("""
            Analyze this developer's coding patterns and provide comprehensive insights:
            
            📊 CODING ACTIVITY:
            - Problems attempted: %d
            - Code executions: %d  
            - Successful submissions: %d
            - Analysis period: %d days
            - Languages used: %s
            - Most used language: %s
            - Problem categories: %s
            
            🎯 PROVIDE DETAILED ANALYSIS:
            1. **Problem-Solving Style**: Describe their approach (methodical, iterative, experimental, etc.)
            2. **Initial Approach Rating** (1-5): Rate how well they plan before coding
            3. **Code Quality Score** (1-5): Assess efficiency and best practices
            4. **Key Strengths**: What they do well (3-4 specific strengths)
            5. **Areas for Improvement**: Specific weaknesses to address (3-4 areas)
            6. **Actionable Recommendations**: Concrete next steps for improvement
            7. **Learning Path**: Suggested topics/resources for continued growth
            
            Please provide specific, actionable insights based on the data patterns. 
            Format your response with clear sections and be constructive in your feedback.
            """,
                userData.getTotalProblems(),
                userData.getTotalRuns(),
                userData.getTotalSubmits(),
                userData.getAnalysisPeriodDays(),
                userData.getLanguagesUsed(),
                userData.getMostUsedLanguage(),
                userData.getProblemCategories()
        );
    }

    private String generateFallbackAnalysis(UserCodingData userData) {
        log.info("🔄 Generating enhanced fallback analysis for user: {}", userData.getUserId());

        return String.format("""
            ## 🎯 **COMPREHENSIVE CODING ANALYSIS**
            
            ### **Problem-Solving Approach**
            Based on your %d code executions across %d problems, you demonstrate a %s approach to problem-solving. 
            Your run-to-submit ratio of %.1f indicates %s testing habits.
            
            ### **Activity & Consistency** 
            Over %d days, you've maintained %s engagement with %.1f executions per day on average.
            You've tackled %d unique problems, showing %s problem diversity.
            
            ### **Technical Breadth**
            You're working with %d programming language(s): %s.
            %s
            
            ### **Growth Recommendations**
            • **Immediate Focus**: %s
            • **Skill Building**: Practice algorithm complexity analysis
            • **Next Level**: Explore system design concepts
            • **Long-term**: Join competitive programming for rapid improvement
            
            ### **Strengths Identified**
            Your analysis reveals: consistent practice habits, %s problem-solving persistence, and %s technical approach.
            
            *This enhanced heuristic analysis provides comprehensive insights. For AI-powered deep analysis, ensure your Perplexity API is configured.*
            """,
                userData.getTotalRuns(),
                userData.getTotalProblems(),
                userData.getTotalRuns() > userData.getTotalSubmits() * 3 ? "thorough, iterative" : "confident, direct",
                userData.getTotalSubmits() > 0 ? (double) userData.getTotalRuns() / userData.getTotalSubmits() : 0,
                userData.getTotalRuns() > userData.getTotalSubmits() * 3 ? "excellent" : "efficient",
                userData.getAnalysisPeriodDays(),
                userData.getTotalRuns() > 20 ? "high" : userData.getTotalRuns() > 5 ? "moderate" : "light",
                (double) userData.getTotalRuns() / userData.getAnalysisPeriodDays(),
                userData.getTotalProblems(),
                userData.getTotalProblems() > 10 ? "excellent" : userData.getTotalProblems() > 5 ? "good" : "developing",
                userData.getLanguagesUsed().size(),
                String.join(", ", userData.getLanguagesUsed()),
                userData.getLanguagesUsed().size() > 1 ? "This shows good technical versatility." : "Consider exploring additional languages for broader perspectives.",
                userData.getProblemCategories().size() <= 2 ? "Expand into new problem categories (Trees, Graphs, DP)" : "Continue diversifying problem types",
                userData.getTotalRuns() > userData.getTotalSubmits() * 3 ? "methodical" : "decisive",
                userData.getLanguagesUsed().size() > 1 ? "versatile" : "focused"
        );
    }
}
